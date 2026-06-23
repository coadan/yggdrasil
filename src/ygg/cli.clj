(ns ygg.cli
  "Command line interface."
  (:require [ygg.agent-install :as agent-install]
            [ygg.audit-scope :as audit-scope]
            [ygg.cli-bench :as cli-bench]
            [ygg.cli-options :refer [dry-run?
                                     json-output?
                                     option-value
                                     parse-depth
                                     parse-double-option
                                     parse-limit
                                     parse-long-option
                                     parse-optional-long
                                     positional-args
                                     project-scope
                                     system-path?]]
            [ygg.cli-query :as cli-query]
            [ygg.cli-start :as cli-start]
            [ygg.cli-sync :as cli-sync]
            [ygg.context :as context]
            [ygg.cursor :as cursor]
            [ygg.dependency :as dependency]
            [ygg.dependency-review :as dependency-review]
            [ygg.embedding :as embedding]
            [ygg.evidence :as evidence]
            [ygg.graph :as graph]
            [ygg.hook :as hook]
            [ygg.index :as index]
            [ygg.infra-review :as infra-review]
            [ygg.map :as graph-map]
            [ygg.map-api :as map-api]
            [ygg.map-store :as map-store]
            [ygg.metadata :as metadata]
            [ygg.mcp :as mcp]
            [ygg.cli-plugin :as cli-plugin]
            [ygg.cli-project :as cli-project]
            [ygg.project :as project]
            [ygg.queue :as queue]
            [ygg.query :as query]
            [ygg.report :as report]
            [ygg.system :as system]
            [ygg.system.decision-classifier :as decision-classifier]
            [ygg.watch :as watch]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.util.logging LogManager]))

(declare usage dispatch print-json)

(defn- silence-jul!
  []
  (.reset (LogManager/getLogManager)))

(defn- temporal-options
  [args]
  (cond-> {}
    (option-value args "--valid-at") (assoc :valid-at (option-value args "--valid-at"))
    (option-value args "--known-at") (assoc :known-at (option-value args "--known-at"))
    (option-value args "--snapshot-token") (assoc :snapshot-token
                                                  (option-value args "--snapshot-token"))))

(defn- default-map-path
  [args]
  (cond
    (some #{"--no-map"} args) nil
    (option-value args "--map") (option-value args "--map")
    (map-store/file-exists? graph-map/default-path) graph-map/default-path
    :else nil))

(defn- context-config-ref
  [args]
  (if-let [config-path (option-value args "--config")]
    {:path config-path
     :explicit? true}
    (let [file (io/file "project.edn")]
      (when (.isFile file)
        {:path (.getPath file)
         :explicit? false}))))

(defn- read-context-project
  [{:keys [path explicit?]}]
  (if explicit?
    (project/read-project path)
    (try
      (project/read-project path)
      (catch Exception _
        nil))))

(defn- matching-context-project
  [args project-id]
  (when-not (str/blank? (str project-id))
    (when-let [{:keys [path explicit?] :as config-ref} (context-config-ref args)]
      (when-let [project (read-context-project config-ref)]
        (cond
          (= (str project-id) (str (:id project)))
          {:project project
           :config-path path}

          explicit?
          (throw (ex-info "--config project id does not match --project."
                          {:config path
                           :config-project-id (:id project)
                           :project-id project-id}))

          :else
          nil)))))

(defn- context-packet-freshness
  [xtdb project-info map-path]
  (when-let [{:keys [project config-path]} project-info]
    (let [overlay (when (and map-path (map-store/file-exists? map-path))
                    (map-store/read-map map-path))
          summary (evidence/summarize xtdb
                                      project
                                      {:map-overlay overlay
                                       :config-path config-path
                                       :map-path map-path})]
      (evidence/packet-freshness summary))))

(defn- context-packet-options
  [xtdb args {:keys [project-id repo-id retriever embedding-client read-context]}]
  (let [map-path (default-map-path args)
        project-info (matching-context-project args project-id)
        freshness (context-packet-freshness xtdb project-info map-path)
        plugins (not-empty (get-in project-info [:project :plugins]))]
    (cond-> {:project-id project-id
             :repo-id repo-id
             :retriever retriever
             :embedding-client embedding-client
             :map-path map-path
             :read-context read-context
             :budget (parse-long-option args
                                        "--budget"
                                        context/default-budget)
             :entity-limit (parse-long-option args
                                              "--entity-limit"
                                              context/default-entity-limit)
             :edge-limit (parse-long-option args
                                            "--edge-limit"
                                            context/default-edge-limit)
             :doc-limit (parse-long-option args
                                           "--doc-limit"
                                           context/default-doc-limit)
             :snippet-chars (parse-long-option args
                                               "--snippet-chars"
                                               context/default-snippet-chars)
             :min-confidence (parse-double-option args
                                                  "--min-confidence"
                                                  0.55)}
      freshness
      (assoc :freshness freshness)

      plugins
      (assoc :plugins plugins))))

(defn- required-map-path
  [args]
  (or (default-map-path args)
      (throw (ex-info "Missing --map and ygg.map.json was not found."
                      {:usage (usage)}))))

(defn- apply-work-result-without-validation!
  [root id map-path payload-schema]
  (case payload-schema
    "ygg.infra.review-packet/v1"
    (infra-review/apply-work-result! root id map-path)

    "ygg.dependency.review-packet/v1"
    (dependency-review/apply-work-result! root id map-path)

    "ygg.maintenance.decision-packet/v1"
    (decision-classifier/apply-work-result! root id map-path)

    {:schema "ygg.sync.work.apply/v1"
     :status "failed"
     :errors [{:path [:payload :schema]
               :error "No apply handler for work item payload schema."
               :value payload-schema}]}))

(defn- work-apply-schema
  [payload-schema]
  (case payload-schema
    "ygg.infra.review-packet/v1" infra-review/apply-schema
    "ygg.dependency.review-packet/v1" dependency-review/apply-schema
    "ygg.maintenance.decision-packet/v1" decision-classifier/apply-schema
    "ygg.sync.work.apply/v1"))

(defn- validate-work-result
  [root id]
  (let [found (or (queue/find-item root id)
                  (throw (ex-info "Queue item not found." {:id id})))
        item (:item found)
        payload-schema (get-in item [:payload :schema])
        result-schema (get-in item [:result :schema])
        status (queue/status-name (:status item))
        expected-result-schema (get-in item [:payload :expectedResultSchema])
        raw-errors (case payload-schema
                     "ygg.infra.review-packet/v1"
                     (infra-review/validate-result item)

                     "ygg.dependency.review-packet/v1"
                     (dependency-review/validate-result item)

                     "ygg.maintenance.decision-packet/v1"
                     (decision-classifier/validate-result item)

                     nil)
        errors (when (= "done" status)
                 raw-errors)]
    (cond-> {:schema "ygg.sync.work.validation/v1"
             :status (cond
                       (not= "done" status) "not-done"
                       (nil? raw-errors) "unsupported"
                       (seq errors) "invalid"
                       :else "valid")
             :payload-schema payload-schema
             :item (queue/item-summary found)}
      result-schema (assoc :result-schema result-schema)
      expected-result-schema (assoc :expected-result-schema expected-result-schema)
      (seq errors) (assoc :errors errors)
      (and (= "done" status)
           (nil? raw-errors))
      (assoc :errors [{:path [:payload :schema]
                       :error "No validation handler for work item payload schema."
                       :value payload-schema}]))))

(defn- validation-apply-errors
  [validation]
  (or (seq (:errors validation))
      [{:path [:validation :status]
        :error "Work result must validate before apply."
        :value (:status validation)}]))

(defn- failed-validated-apply-result
  [root id validation]
  (let [errors (vec (validation-apply-errors validation))
        done? (= "done" (get-in validation [:item :status]))
        failed (when done?
                 (queue/fail! root
                              id
                              (str "Invalid sync work result: "
                                   (str/join "; " (map :error errors)))))]
    {:schema (work-apply-schema (:payload-schema validation))
     :status "failed"
     :errors errors
     :validation validation
     :item (if failed
             (queue/item-summary failed)
             (:item validation))}))

(defn- apply-work-result!
  [root id map-path]
  (let [validation (validate-work-result root id)]
    (if (= "valid" (:status validation))
      (assoc (apply-work-result-without-validation! root
                                                    id
                                                    map-path
                                                    (:payload-schema validation))
             :validation validation)
      (failed-validated-apply-result root id validation))))

(defn- print-json
  [value]
  (println (json/write-json-str value {:indent-str "  "})))

(defn- queue-root
  [args]
  (or (option-value args "--queue-dir") queue/default-root))

(defn- enqueue-output?
  [args]
  (some #{"--enqueue"} args))

(defn- severity-priority
  [severity]
  ({:high 90 :medium 60 :low 30
    "high" 90 "medium" 60 "low" 30}
   severity
   50))

(defn- queue-priority
  ([args] (queue-priority args 50))
  ([args default]
   (parse-long-option args "--priority" default)))

(defn- queue-agent
  [args]
  (or (option-value args "--agent")
      (System/getenv "YGG_AGENT_ID")
      (System/getProperty "user.name")
      "agent"))

(defn- queue-lease-ms
  [args]
  (* 60 1000 (parse-long-option args "--lease-minutes" 30)))

(defn- queue-enqueued-result
  [found]
  {:schema "ygg.queue.enqueued/v1"
   :item (queue/item-summary found)})

(defn- emit-json-or-enqueue
  [args kind project-id payload]
  (if (enqueue-output? args)
    (print-json
     (queue-enqueued-result
      (queue/enqueue! payload
                      {:root (queue-root args)
                       :kind kind
                       :project-id project-id
                       :priority (queue-priority args)})))
    (print-json payload)))

(defn- emit-cursor-packet
  [args payload]
  (emit-json-or-enqueue args
                        "cursor"
                        (get-in payload [:basis :project-id])
                        payload))

(defn- target-kind
  [target-id]
  (cond
    (str/starts-with? target-id "file:") :file
    (str/starts-with? target-id "node:") :node
    (str/starts-with? target-id "edge:") :edge
    (str/starts-with? target-id "chunk:") :chunk
    (str/starts-with? target-id "system:") :system-node
    (str/starts-with? target-id "system-node:") :system-node
    (str/starts-with? target-id "system-edge:") :system-edge
    :else :target))

(defn- metadata-row-from-cli
  [args target key value]
  (let [{:keys [project-id repo-id]} (project-scope args)]
    (metadata/row {:project-id project-id
                   :repo-id repo-id
                   :target-id target
                   :target-kind (target-kind target)
                   :key (metadata/parse-key key)
                   :value value
                   :value-type (some-> (option-value args "--type") keyword)
                   :source (keyword (or (option-value args "--source")
                                        (name metadata/default-source)))
                   :confidence (some-> (option-value args "--confidence")
                                       Double/parseDouble)})))

(defn- print-meta-summary
  [summary]
  (println "# Metadata")
  (doseq [[k v] summary]
    (println "-" (name k) v)))

(defn- print-views-summary
  [views]
  (println "# Graph Views")
  (if (seq views)
    (doseq [view views]
      (println "-" (:xt/id view) (:label view)))
    (println "- none")))

(def ^:private candidate-system-kinds
  [:candidate-system :repo-boundary])

(def ^:private candidate-system-row-fields
  [:xt/id
   :project-id
   :repo-id
   :label
   :kind
   :candidate-types
   :metrics
   :path-prefix
   :active?])

(defn- candidate-system-rows
  [xtdb project-id]
  (vec (store/rows-with-field-values
        xtdb
        {:table (:system-nodes store/tables)
         :field :kind
         :values candidate-system-kinds
         :constraints {:project-id project-id
                       :active? true}
         :return-fields candidate-system-row-fields})))

(defn- project-deps
  []
  {:usage usage
   :print-json print-json
   :default-map-path default-map-path
   :temporal-options temporal-options})

(defn- print-project-status!
  ([config-path args]
   (binding [cli-project/*deps* (project-deps)]
     (cli-project/print-project-status! config-path args)))
  ([project config-path args]
   (binding [cli-project/*deps* (project-deps)]
     (cli-project/print-project-status! project config-path args))))

(defn- print-project-index-summary
  [result]
  (cli-project/print-project-index-summary result))

(defn- print-project-add-repo-summary
  [result]
  (cli-project/print-project-add-repo-summary result))

(defn- print-source-coverage
  [report]
  (cli-project/print-source-coverage report))

(defn- print-audit-scope-report
  [report]
  (cli-project/print-audit-scope-report report))

(defn- affected!
  [args]
  (cli-project/affected! args (project-deps)))

(defn- print-sync-summary
  [result]
  (cli-project/print-sync-summary result))

(defn- print-system-summary
  [result]
  (cli-project/print-system-summary result))

(defn- print-map-summary
  [path data]
  (cli-project/print-map-summary path data))

(defn- print-map-system
  [system]
  (cli-project/print-map-system system))

(defn- print-map-write-result
  [args result]
  (if (json-output? args)
    (print-json result)
    (print-map-summary (:path result) (map-store/read-map (:path result)))))

(defn- sync-deps
  []
  {:usage usage
   :print-json print-json
   :default-map-path default-map-path
   :enqueue-output? enqueue-output?
   :queue-root queue-root
   :queue-priority queue-priority
   :severity-priority severity-priority
   :print-source-coverage print-source-coverage
   :print-sync-summary print-sync-summary
   :print-project-add-repo-summary print-project-add-repo-summary
   :queue-agent queue-agent
   :queue-lease-ms queue-lease-ms
   :required-map-path required-map-path
   :apply-work-result! apply-work-result!
   :validate-work-result validate-work-result
   :dispatch dispatch
   :print-project-status! print-project-status!})

(defn- print-maintenance-report
  [report]
  (cli-sync/print-maintenance-report report))

(defn- query-index?
  [args]
  (cli-sync/query-index? args))

(defn- sync-index-project!
  [xtdb project args]
  (cli-sync/sync-index-project! xtdb project args (sync-deps)))

(defn- maintenance-report
  [xtdb project args]
  (cli-sync/maintenance-report xtdb project args (sync-deps)))

(defn- enqueue-sync-work!
  [args report]
  (cli-sync/enqueue-sync-work! args report (sync-deps)))

(defn- sync-dispatch!
  [args]
  (cli-sync/sync-dispatch! args (sync-deps)))

(defn- query-deps
  []
  {:usage usage
   :print-json print-json
   :default-map-path default-map-path
   :temporal-options temporal-options
   :context-packet-options context-packet-options
   :dispatch dispatch})

(defn- print-query-result [result] (cli-query/print-query-result result))
(defn- print-embed-summary [result] (cli-query/print-embed-summary result))
(defn- candidate-system? [system] (cli-query/candidate-system? system))
(defn- print-candidate-systems [systems limit] (cli-query/print-candidate-systems systems limit))
(defn- provider-option [args] (cli-query/provider-option args))
(defn- default-model [provider] (cli-query/default-model provider))
(defn- provider-api-key [provider] (cli-query/provider-api-key provider))
(defn- provider-client [provider model] (cli-query/provider-client provider model))
(defn- missing-key-message [provider] (cli-query/missing-key-message provider))
(defn- llm-provider-option [args] (cli-query/llm-provider-option args))
(defn- llm-model [provider args] (cli-query/llm-model provider args))
(defn- llm-client [provider model args] (cli-query/llm-client provider model args))
(defn- print-deps [result] (cli-query/print-deps result))
(defn- print-package-report [result] (cli-query/print-package-report result))
(defn- print-path [nodes] (cli-query/print-path nodes))
(defn- graph-output [args mode value] (cli-query/graph-output args mode value))
(defn- graph-json-output [args mode value] (cli-query/graph-json-output args mode value))
(defn- query-embedding-client [retriever provider model]
  (cli-query/query-embedding-client retriever provider model))
(defn- print-graph-output [path data] (cli-query/print-graph-output path data))
(defn- print-canonical-output [path data] (cli-query/print-canonical-output path data))
(defn- graph-output-value [mode graph-args] (cli-query/graph-output-value mode graph-args))
(defn- graph-data-for-mode [xtdb mode graph-args limit depth]
  (binding [cli-query/*deps* (query-deps)]
    (cli-query/graph-data-for-mode xtdb mode graph-args limit depth)))
(defn- ask! [args]
  (binding [cli-query/*deps* (query-deps)]
    (cli-query/ask! args)))
(defn- cursor-action? [args] (cli-query/cursor-action? args))
(defn- view! [args]
  (binding [cli-query/*deps* (query-deps)]
    (cli-query/view! args)))
(defn- report! [args]
  (binding [cli-query/*deps* (query-deps)]
    (cli-query/report! args)))

(defn- bench!
  [args]
  (cli-bench/bench! args
                    {:usage usage
                     :print-json print-json
                     :enqueue-output? enqueue-output?
                     :queue-root queue-root
                     :queue-priority queue-priority}))

(defn- plugin!
  [args]
  (cli-plugin/plugin! args
                      {:usage usage
                       :print-json print-json}))

(defn- init!
  [args]
  (cli-start/init! args
                   {:print-json print-json
                    :dispatch dispatch
                    :query-index? query-index?}))

(defn- start!
  [args]
  (cli-start/start! args
                    {:print-json print-json
                     :query-index? query-index?
                     :enqueue-output? enqueue-output?
                     :sync-index-project! sync-index-project!
                     :maintenance-report maintenance-report
                     :enqueue-sync-work! enqueue-sync-work!
                     :queue-root queue-root}))

(defn- agent!
  [args]
  (let [action (first args)
        agent-args (vec (rest args))
        platform (or (option-value agent-args "--platform") "codex")
        opts {:project? (boolean (some #{"--project"} agent-args))
              :hooks? (boolean (some #{"--hooks"} agent-args))
              :force? (boolean (some #{"--force"} agent-args))
              :print-config? (boolean (some #{"--print-config"} agent-args))}]
    (case action
      "list"
      (print-json (agent-install/list-platforms))

      "uninstall"
      (print-json (agent-install/uninstall! platform opts))

      "install"
      (print-json (agent-install/install! platform opts))

      (throw (ex-info "Unknown agent command."
                      {:command action
                       :usage "agent install|uninstall|list"})))))

(defn usage
  []
  (str/join
   "\n"
   ["Usage:"
    ""
    "Setup:"
    "  start <repo-root> [--project ID] [--name NAME] [--out project.edn] [--map ygg.map.json] [--report-out ygg-out] [--force] [--query-index]"
    "  init <repo-root> [--project ID] [--name NAME] [--out project.edn] [--force] [--sync] [--map ygg.map.json]"
    "  init --workbench <root> [--task TASK] [--project ID] [--name NAME] [--out project.edn] [--force]"
    ""
    "Sync and maintenance:"
    "  audit-scope <project.edn> [--repo ID] [--map PATH] [--json]"
    "  sync <project.edn> [--repo ID] [--map PATH] [--check] [--enqueue] [--query-index] [--dry-run] [--json]"
    "  sync inspect <project.edn> [--map PATH] [--json]"
    "  sync coverage <project.edn> [--json]"
    "  sync activity <project.edn> [--queue-dir DIR] [--json]"
    "  sync add-repo <project.edn> <repo-path> [--repo ID] [--role ROLE]"
    "  sync check <project.edn> [--map PATH] [--json] [--enqueue] [--queue-dir DIR]"
    "  sync docs candidates <target> [--project ID] [--limit N] [--snippet-chars N]"
    "  sync docs for <target> [--project ID] [--map PATH] [--snippet-chars N]"
    "  sync docs audit [--project ID] [--map PATH]"
    "  sync meta defs|set|get|unset ..."
    "  sync view list|show ..."
    "  sync work list [--queue-dir DIR] [--status ready|claimed|done|rejected|failed] [--project ID] [--kind KIND] [--limit N]"
    "  sync work pull [--queue-dir DIR] [--project ID] [--kind KIND] [--agent ID] [--lease-minutes N]"
    "  sync work show <work-id> [--queue-dir DIR]"
    "  sync work complete <work-id> --result result.json [--queue-dir DIR]"
    "  sync work validate <work-id> [--queue-dir DIR]"
    "  sync work apply <work-id> --map ygg.map.json [--queue-dir DIR]"
    "  sync work reject <work-id> --reason TEXT [--queue-dir DIR]"
    "  sync work release <work-id> [--reason TEXT] [--queue-dir DIR]"
    "  sync work heartbeat <work-id> [--queue-dir DIR] [--agent ID] [--lease-minutes N]"
    "  map init <project.edn> [--map ygg.map.json]"
    "  map status <project.edn> [--map ygg.map.json] [--json]"
    "  map review <project.edn> [--map ygg.map.json] [--limit N] [--json]"
    "  map accept system <target> --kind KIND --label LABEL --include repo:path --reason TEXT [--map ygg.map.json]"
    "  map set-kind <target> <kind> --reason TEXT [--map ygg.map.json]"
    "  map include <target> <repo>:<path> --reason TEXT [--map ygg.map.json]"
    "  map reject <kind> <value> --reason TEXT [--map ygg.map.json]"
    "  map edge add <source> <target> <relation> --reason TEXT [--map ygg.map.json]"
    "  map docs attach <target> <repo>:<path> --role ROLE --reason TEXT [--map ygg.map.json]"
    "  map package import <import-prefix> <ecosystem>:<package> [--repo ID] --reason TEXT [--map ygg.map.json]"
    "  map work apply <work-id> --map ygg.map.json [--queue-dir DIR]"
    ""
    "Ask and explore:"
    "  ask <text> [--project ID] [--repo ID] [--config project.edn] [--limit N] [--json] [--retriever auto|hybrid|lexical|semantic] [--provider openrouter|openai] [--model MODEL] [--map PATH] [--valid-at INSTANT]"
    "  explore <text> [--project ID] [--repo ID] [--config project.edn] [--limit N] [--json] [--retriever auto|hybrid|lexical|semantic] [--provider openrouter|openai] [--model MODEL] [--map PATH] [--valid-at INSTANT]"
    "  explore create [query text] --project ID [--budget N] [--limit N] [--retriever auto|hybrid|lexical|semantic] [--provider openrouter|openai] [--model MODEL] [--map PATH] [--no-map] [--view ID] [--valid-at INSTANT] [--enqueue] [--queue-dir DIR]"
    "  explore show|open|expand|docs|search ..."
    "  affected <project.edn> [--files PATH,PATH | --since REV] [--repo ID] [--tests] [--json]"
    ""
    "View and report:"
    "  view overview|deps|query|systems|clusters|cluster [args] [--project ID] [--repo ID] [--depth N] [--limit N] [--detail primary|expanded|evidence|raw] [--map PATH] [--no-map] [--view ID] [--format html|json] [--out PATH] [--valid-at INSTANT]"
    "  packages [--project ID] [--repo ID] [--ecosystem npm|cargo|go] [--package NAME] [--with-conflicts] [--without-import-evidence] [--limit N] [--json]"
    "  report <project.edn> [--map ygg.map.json] [--out ygg-out] [--detail primary|expanded|evidence|raw] [--force]"
    ""
    "Plugins:"
    "  plugin new <dir> [--id ID] [--file-kind KIND] [--path-glob GLOB] [--scan-glob GLOB] [--fixture PATH] [--extractor] [--report] [--public-base] [--force] [--json]"
    "  plugin validate <dir> [--json]"
    "  plugin diagnose <dir> [--json]"
    "  plugin core-check <dir> [--json]"
    "  plugin dry-run extractor <dir> <repo-root> <file> [--plugin ID] [--json] [--no-progress]"
    "  plugin dry-run report <dir> [--plugin ID] [--json] [--no-progress]"
    "  plugin input extractor <dir> <repo-root> <file> [--plugin ID] [--json]"
    "  plugin input report <dir> [--plugin ID] [--json]"
    "  plugin gap extractor <dir> <repo-root> <file> [--plugin ID] [--json]"
    "  plugin gap report <dir> [--plugin ID] [--json]"
    "  plugin install <project.edn> <git-url-or-path> [--ref REF] [--subdir DIR] [--cache-dir DIR] [--force] [--json] [--no-progress]"
    "  plugin update <project.edn> <package-id> [--ref REF] [--subdir DIR] [--cache-dir DIR] [--json] [--no-progress]"
    "  plugin list <project.edn> [--kind extractor|report] [--query TEXT] [--json]"
    "  plugin remove <project.edn> <package-id> [--json]"
    "  plugin registry list <registry.edn> [--kind extractor|report] [--query TEXT] [--json] [--no-progress]"
    "  plugin registry validate <registry.edn> [--json] [--no-progress]"
    "  plugin registry install <registry.edn> <project.edn> <package-id> [--cache-dir DIR] [--force] [--json] [--no-progress]"
    ""
    "Agent integration:"
    "  agent install --platform codex --project [--hooks] [--print-config]"
    "  agent uninstall --platform codex --project"
    "  agent list"
    "  watch <project.edn> [--map ygg.map.json] [--query-index] [--debounce-ms N]"
    "  hook install <project.edn> [--map ygg.map.json] [--query-index]"
    "  hook uninstall <project.edn>"
    "  hook status <project.edn>"
    ""
    "Server integration:"
    "  mcp [--root DIR] [--config project.edn] [--map ygg.map.json] [--queue-dir DIR] [--tools default,cursor,sync,work,ask|all]"
    ""
    "Benchmarks:"
    "  bench prepare|run|report <benchmark.edn> [--case ID] [--cases ID,ID] [--parser-worker none|java|dotnet|all] [--index-timeout-ms N] [--out DIR] [--json]"
    "  bench show <benchmark.edn> --case ID [--out DIR] [--json]"
    "  bench agent-packet <benchmark.edn> [--case ID] [--cases ID,ID] [--mode ygg|shell-only] [--parser-worker none|java|dotnet|all] [--enqueue] [--queue-dir DIR] [--out DIR] [--json]"
    "  bench agent-baseline <benchmark.edn> [--case ID] [--cases ID,ID] [--retriever auto|hybrid|lexical|semantic|local-vector|codebase-memory] [--limit N] [--doc-limit N] [--retrieval-limit N] [--vector-model MODEL] [--vector-command CMD] [--codebase-memory-command CMD] [--codebase-memory-bin PATH] [--codebase-memory-cache-dir DIR] [--parser-worker none|java|dotnet|all] [--index-timeout-ms N] [--skip-existing] [--out DIR] [--json]"
    "  bench agent-run <benchmark.edn> --agent ID --command CMD [--case ID] [--cases ID,ID] [--mode ygg|shell-only] [--prompt-profile standard|fast] [--timeout-ms N] [--parser-worker none|java|dotnet|all] [--index-timeout-ms N] [--skip-existing] [--out DIR] [--json]"
    "  bench agent-rerun <benchmark.edn> --agent ID --command CMD [--agent-report agent-report.json] [--case ID] [--cases ID,ID] [--mode ygg|shell-only] [--prompt-profile standard|fast] [--timeout-ms N] [--parser-worker none|java|dotnet|all] [--index-timeout-ms N] [--out DIR] [--json]"
    "  bench agent-score <benchmark.edn> --case ID --result result.json [--parser-worker none|java|dotnet|all] [--out DIR] [--json]"
    "  bench agent-report <benchmark.edn> [--case ID] [--cases ID,ID] [--mode ygg|shell-only] [--agent ID] [--allow-unverified-scores] [--out DIR] [--json]"
    "  bench improve <benchmark.edn> [--case ID] [--cases ID,ID] [--mode ygg|shell-only] [--agent ID] [--allow-unverified-scores] [--out DIR] [--json]"
    "  bench agent-check <benchmark.edn> [--case ID] [--cases ID,ID] [--mode ygg|shell-only] [--agent ID] [--min-cases N] [--min-runs N] [--min-file-recall-at-5 N] [--min-file-recall-at-10 N] [--min-file-recall-at-20 N] [--min-case-file-recall-at-5 N] [--min-case-file-recall-at-10 N] [--min-case-file-recall-at-20 N] [--min-mrr N] [--min-case-mrr N] [--min-evidence-citation-rate N] [--min-path-evidence-citation-rate N] [--min-decision-f1 N] [--min-decision-evidence-citation-rate N] [--min-case-evidence-citation-rate N] [--min-case-path-evidence-citation-rate N] [--min-case-decision-f1 N] [--max-total-tokens N] [--max-input-tokens N] [--max-output-tokens N] [--max-cost-usd N] [--max-case-total-tokens N] [--max-case-input-tokens N] [--max-case-output-tokens N] [--max-case-cost-usd N] [--max-noise-at-20 N] [--max-case-noise-at-20 N] [--max-input-hinted-cases N] [--max-unsupported-ground-truth-files N] [--max-empty-result-runs N] [--max-missing-predicted-file-runs N] [--max-missing-decision-runs N] [--max-commandless-runs N] [--max-warning-runs N] [--max-hint-diagnostic-runs N] [--max-identity-mismatch-runs N] [--max-unverified-score-runs N] [--max-graph-expectation-failures N] [--max-maintenance-preflight-blockers N] [--max-missing-declared-source-kind-runs N] [--max-missed-runs N] [--max-context-rank-missing-runs N] [--max-missed-but-present-in-context-runs N] [--max-missed-and-absent-from-context-runs N] [--max-ranked-outside-top-5-runs N] [--max-ranked-outside-top-10-runs N] [--max-ranked-outside-top-20-runs N] [--max-improvement-target-runs N] [--max-improvement-target-kind-runs KIND=N] [--max-active-stage-ms N] [--max-parser-worker-profiles N] [--min-measured-problem-classes N] [--min-measured-architecture-classes N] [--require-parser-worker none|java|dotnet|all] [--allow-missing] [--allow-duplicate-runs] [--allow-unverified-scores] [--out DIR] [--json]"
    "  bench agent-compare <benchmark.edn> --baseline-report before.json --candidate-report after.json [--regression-tolerance N] [--out DIR] [--json]"
    "  bench claim-pack <benchmark.edn> --shell-report shell/agent-report.json --ygg-report ygg/agent-report.json [--min-shared-cases N] [--out DIR] [--json]"
    "  embed [--provider openrouter|openai] [--model MODEL] [--batch-size N] [--limit N]"
    ""
    "Compatibility commands remain during migration: index, project, systems, classify, queue, map, docs, meta, views, context, cursor, query, graph, deps, path."]))

(defn dispatch
  [command args]
  (case command
    ("help" "--help" "-h")
    (println (usage))

    "init"
    (init! args)

    "start"
    (start! args)

    "sync"
    (sync-dispatch! args)

    "status"
    (print-project-status! (first (positional-args args)) args)

    "audit-scope"
    (let [config-path (first (positional-args args))]
      (when-not config-path
        (throw (ex-info "Missing project config path." {:usage (usage)})))
      (let [project (project/read-project config-path)
            opts {:config-path config-path
                  :repo-id (option-value args "--repo")
                  :map-path (default-map-path args)
                  :read-context (temporal-options args)}]
        (store/with-node (store/storage-path)
          (fn [xtdb]
            (let [report (audit-scope/report xtdb project opts)]
              (if (json-output? args)
                (print-json report)
                (print-audit-scope-report report)))))))

    "affected"
    (affected! args)

    "ask"
    (ask! args)

    "explore"
    (if (cursor-action? args)
      (dispatch "cursor" args)
      (ask! args))

    "view"
    (view! args)

    "bench"
    (bench! args)

    "plugin"
    (plugin! args)

    "agent"
    (agent! args)

    "watch"
    (let [config-path (first (positional-args args))]
      (when-not config-path
        (throw (ex-info "Missing project config path." {:usage (usage)})))
      (watch/watch! (project/read-project config-path)
                    {:config-path config-path
                     :map-path (default-map-path args)
                     :query-index? (boolean (query-index? args))
                     :debounce-ms (parse-long-option args
                                                     "--debounce-ms"
                                                     watch/default-debounce-ms)}))

    "hook"
    (let [action (keyword (first args))
          hook-args (vec (rest args))
          config-path (first (positional-args hook-args))]
      (when-not config-path
        (throw (ex-info "Missing project config path." {:usage (usage)})))
      (let [project (project/read-project config-path)
            opts {:config-path config-path
                  :map-path (default-map-path hook-args)
                  :query-index? (boolean (query-index? hook-args))
                  :ygg-bin (System/getenv "YGG_BIN")}]
        (case action
          :install
          (print-json (hook/install! project opts))

          :uninstall
          (print-json (hook/uninstall! project))

          :status
          (print-json (hook/status project))

          (throw (ex-info "Unknown hook command." {:command action
                                                   :usage (usage)})))))

    "index"
    (let [root (first args)]
      (when-not root
        (throw (ex-info "Missing repo path." {:usage (usage)})))
      (if (dry-run? args)
        (pprint/pprint (index/index-repo! nil root {:dry-run? true}))
        (store/with-node (store/storage-path)
          (fn [xtdb]
            (pprint/pprint (index/index-repo! xtdb root {}))))))

    "systems"
    (let [action (keyword (first args))
          system-args (vec (rest args))]
      (case action
        :candidates
        (let [{:keys [project-id]} (project-scope system-args)
              limit (or (parse-limit system-args) 50)]
          (when (str/blank? (str project-id))
            (throw (ex-info "Missing --project for system candidates." {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-candidate-systems
               (->> (candidate-system-rows xtdb project-id)
                    (filter candidate-system?)
                    (sort-by (juxt :repo-id :label))
                    vec)
               limit))))

        (throw (ex-info "Unknown systems command." {:command action
                                                    :usage (usage)}))))

    "classify"
    (let [action (keyword (first args))
          classify-args (vec (rest args))]
      (case action
        :decision
        (let [decision-id (first (positional-args classify-args))
              {:keys [project-id]} (project-scope classify-args)
              provider (llm-provider-option classify-args)
              model (llm-model provider classify-args)]
          (when-not decision-id
            (throw (ex-info "Missing decision id." {:usage (usage)})))
          (when (str/blank? (str project-id))
            (throw (ex-info "Missing --project for decision classification."
                            {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (let [report (system/maintenance-report
                            xtdb
                            project-id
                            {:low-confidence-threshold
                             (parse-double-option classify-args "--min-confidence" 0.60)})
                    decision (decision-classifier/decision-by-id
                              (:decision-queue report)
                              decision-id)]
                (when-not decision
                  (throw (ex-info "Maintenance decision not found."
                                  {:decision-id decision-id
                                   :project-id project-id})))
                (if (enqueue-output? classify-args)
                  (print-json
                   (queue-enqueued-result
                    (queue/enqueue!
                     (decision-classifier/decision-packet decision)
                     {:root (queue-root classify-args)
                      :kind "maintenance-decision"
                      :project-id project-id
                      :priority (queue-priority classify-args
                                                (severity-priority (:severity decision)))})))
                  (print-json
                   (decision-classifier/classify
                    {:client (llm-client provider model classify-args)
                     :decision decision})))))))

        (throw (ex-info "Unknown classify command." {:command action
                                                     :usage (usage)}))))

    "queue"
    (let [action (keyword (first args))
          queue-args (vec (rest args))
          positional (positional-args queue-args)
          root (queue-root queue-args)]
      (case action
        :add
        (let [path (first positional)]
          (when-not path
            (throw (ex-info "Missing packet JSON path." {:usage (usage)})))
          (print-json
           (queue-enqueued-result
            (queue/enqueue! (queue/read-json-file path)
                            {:root root
                             :kind (option-value queue-args "--kind")
                             :project-id (option-value queue-args "--project")
                             :priority (queue-priority queue-args)
                             :source {:kind "file"
                                      :path path}}))))

        :list
        (print-json
         (queue/list-summary root
                             {:status (option-value queue-args "--status")
                              :project-id (option-value queue-args "--project")
                              :kind (option-value queue-args "--kind")
                              :limit (parse-limit queue-args)}))

        :show
        (let [id (first positional)]
          (when-not id
            (throw (ex-info "Missing queue item id." {:usage (usage)})))
          (print-json (or (queue/find-item root id)
                          {:schema "ygg.queue.error/v1"
                           :error "queue item not found"
                           :id id})))

        :claim
        (let [target (first positional)]
          (when-not (= "next" target)
            (throw (ex-info "Only `queue claim next` is supported."
                            {:usage (usage)})))
          (print-json (or (some-> (queue/claim-next!
                                   root
                                   {:agent-id (queue-agent queue-args)
                                    :lease-ms (queue-lease-ms queue-args)
                                    :project-id (option-value queue-args "--project")
                                    :kind (option-value queue-args "--kind")})
                                  queue/item-summary)
                          {:schema queue/summary-schema
                           :status "empty"
                           :root root})))

        :complete
        (let [id (first positional)
              result-path (option-value queue-args "--result")]
          (when-not (and id result-path)
            (throw (ex-info "Missing queue item id or --result path."
                            {:usage (usage)})))
          (print-json
           (queue/item-summary
            (queue/complete! root id (queue/read-json-file result-path)))))

        :reject
        (let [id (first positional)
              reason (option-value queue-args "--reason")]
          (when-not (and id reason)
            (throw (ex-info "Missing queue item id or --reason."
                            {:usage (usage)})))
          (print-json (queue/item-summary (queue/reject! root id reason))))

        :fail
        (let [id (first positional)
              reason (option-value queue-args "--reason")]
          (when-not (and id reason)
            (throw (ex-info "Missing queue item id or --reason."
                            {:usage (usage)})))
          (print-json (queue/item-summary (queue/fail! root id reason))))

        :release
        (let [id (first positional)]
          (when-not id
            (throw (ex-info "Missing queue item id." {:usage (usage)})))
          (print-json
           (queue/item-summary
            (queue/release! root id (or (option-value queue-args "--reason")
                                        "manual release")))))

        :heartbeat
        (let [id (first positional)]
          (when-not id
            (throw (ex-info "Missing queue item id." {:usage (usage)})))
          (print-json
           (queue/item-summary
            (queue/heartbeat! root
                              id
                              {:agent-id (queue-agent queue-args)
                               :lease-ms (queue-lease-ms queue-args)}))))

        (throw (ex-info "Unknown queue command." {:command action
                                                  :usage (usage)}))))

    "map"
    (let [action (keyword (first args))
          map-args (vec (rest args))]
      (case action
        :init
        (let [config-path (first (positional-args map-args))
              out (or (option-value map-args "--map")
                      (option-value map-args "--out")
                      graph-map/default-path)]
          (when-not config-path
            (throw (ex-info "Missing project config path." {:usage (usage)})))
          (let [project (project/read-project config-path)]
            (print-map-write-result map-args (map-api/init! out (:id project)))))

        :status
        (let [config-path (first (positional-args map-args))
              map-path (or (option-value map-args "--map") graph-map/default-path)]
          (when-not config-path
            (throw (ex-info "Missing project config path." {:usage (usage)})))
          (let [project (project/read-project config-path)]
            (print-json (map-api/status map-path (:id project)))))

        :review
        (let [config-path (first (positional-args map-args))
              map-path (default-map-path map-args)
              limit (parse-limit map-args)]
          (when-not config-path
            (throw (ex-info "Missing project config path." {:usage (usage)})))
          (let [project (project/read-project config-path)]
            (store/with-node (store/storage-path)
              (fn [xtdb]
                (print-json (map-api/review xtdb
                                            project
                                            {:map-path map-path
                                             :limit limit}))))))

        :explain
        (let [value (first (positional-args map-args))
              map-path (required-map-path map-args)]
          (when-not value
            (throw (ex-info "Missing system id/label." {:usage (usage)})))
          (print-map-system (map-api/explain map-path value)))

        :accept
        (let [[kind target] (positional-args map-args)
              map-path (required-map-path map-args)]
          (case (keyword kind)
            :system
            (do
              (when-not target
                (throw (ex-info "Missing system target." {:usage (usage)})))
              (print-map-write-result
               map-args
               (map-api/accept-system!
                map-path
                target
                {:kind (option-value map-args "--kind")
                 :label (option-value map-args "--label")
                 :include (some-> (option-value map-args "--include")
                                  map-api/parse-include)
                 :reason (option-value map-args "--reason")})))

            (throw (ex-info "Unknown map accept kind." {:kind kind
                                                        :usage (usage)}))))

        :set-kind
        (let [[value kind] (positional-args map-args)
              map-path (required-map-path map-args)]
          (when-not (and value kind)
            (throw (ex-info "Missing system id/label or kind." {:usage (usage)})))
          (print-map-write-result
           map-args
           (map-api/set-kind! map-path value kind (option-value map-args "--reason"))))

        :include
        (let [[value include] (positional-args map-args)
              map-path (required-map-path map-args)]
          (when-not (and value include)
            (throw (ex-info "Missing system id/label or repo:path include."
                            {:usage (usage)})))
          (print-map-write-result
           map-args
           (map-api/include! map-path
                             value
                             (map-api/parse-include include)
                             (option-value map-args "--reason"))))

        :reject
        (let [[kind value] (positional-args map-args)
              map-path (required-map-path map-args)
              reason (option-value map-args "--reason")]
          (when-not (and kind value)
            (throw (ex-info "Missing reject kind or reject value."
                            {:usage (usage)})))
          (print-map-write-result map-args (map-api/reject! map-path kind value reason)))

        :package
        (let [[subcommand import-prefix package-target] (positional-args map-args)
              map-path (required-map-path map-args)]
          (when-not (= "import" subcommand)
            (throw (ex-info "Unknown map package command." {:command subcommand
                                                            :usage (usage)})))
          (when-not (and import-prefix package-target)
            (throw (ex-info "Missing import prefix or ecosystem:package target."
                            {:usage (usage)})))
          (print-map-write-result
           map-args
           (map-api/package-import! map-path
                                    import-prefix
                                    package-target
                                    {:repo (option-value map-args "--repo")
                                     :reason (option-value map-args "--reason")})))

        :package-import
        (throw (ex-info "Package import corrections use map package import."
                        {:command "map package-import"
                         :replacement "ygg map package import"
                         :usage (usage)}))

        :docs
        (let [[subcommand target source-value] (positional-args map-args)
              map-path (required-map-path map-args)]
          (when-not (= "attach" subcommand)
            (throw (ex-info "Unknown map docs command." {:command subcommand
                                                         :usage (usage)})))
          (when-not (and target source-value)
            (throw (ex-info "Missing docs target or repo:path source."
                            {:usage (usage)})))
          (print-map-write-result
           map-args
           (map-api/docs-attach! map-path
                                 target
                                 (map-api/parse-source source-value)
                                 {:role (option-value map-args "--role")
                                  :heading (option-value map-args "--heading")
                                  :start-line (parse-optional-long map-args "--start-line")
                                  :end-line (parse-optional-long map-args "--end-line")
                                  :reason (option-value map-args "--reason")})))

        :edge
        (let [[subcommand source target relation] (positional-args map-args)
              map-path (required-map-path map-args)]
          (when-not (= "add" subcommand)
            (throw (ex-info "Unknown map edge command." {:command subcommand
                                                         :usage (usage)})))
          (when-not (and source target relation)
            (throw (ex-info "Missing source, target, or relation." {:usage (usage)})))
          (print-map-write-result
           map-args
           (map-api/edge-add! map-path
                              (cond-> {:source source
                                       :target target
                                       :relation relation
                                       :reason (option-value map-args "--reason")}
                                (option-value map-args "--visibility")
                                (assoc :visibility (option-value map-args "--visibility"))
                                (option-value map-args "--importance")
                                (assoc :importance (option-value map-args "--importance"))
                                (option-value map-args "--confidence")
                                (assoc :confidence (Double/parseDouble
                                                    (option-value map-args "--confidence")))))))

        :work
        (let [[subcommand id] (positional-args map-args)
              map-path (required-map-path map-args)
              root (queue-root map-args)]
          (when-not (= "apply" subcommand)
            (throw (ex-info "Unknown map work command." {:command subcommand
                                                         :usage (usage)})))
          (when-not id
            (throw (ex-info "Missing work id." {:usage (usage)})))
          (print-json (apply-work-result! root id map-path)))

        (throw (ex-info "Unknown map command." {:command action
                                                :usage (usage)}))))

    "docs"
    (let [action (keyword (first args))
          docs-args (vec (rest args))
          positional (positional-args docs-args)
          {:keys [project-id]} (project-scope docs-args)]
      (case action
        :candidates
        (let [target (first positional)
              limit (or (parse-limit docs-args) context/default-doc-limit)
              snippet-chars (parse-long-option docs-args
                                               "--snippet-chars"
                                               context/default-snippet-chars)]
          (when-not target
            (throw (ex-info "Missing docs target." {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-json (context/doc-candidates xtdb
                                                  target
                                                  {:project-id project-id
                                                   :limit limit
                                                   :snippet-chars snippet-chars})))))

        :attach
        (throw (ex-info "Map doc attachments are handled by the ygg map API."
                        {:command "docs attach"
                         :replacement "ygg map docs attach"
                         :usage (usage)}))

        :for
        (let [target (first positional)
              map-path (default-map-path docs-args)
              snippet-chars (parse-long-option docs-args
                                               "--snippet-chars"
                                               context/default-snippet-chars)]
          (when-not target
            (throw (ex-info "Missing docs target." {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-json (context/docs-for xtdb
                                            target
                                            {:project-id project-id
                                             :map-path map-path
                                             :snippet-chars snippet-chars})))))

        :audit
        (let [map-path (default-map-path docs-args)]
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-json (context/docs-audit xtdb
                                              {:project-id project-id
                                               :map-path map-path})))))

        (throw (ex-info "Unknown docs command." {:command action
                                                 :usage (usage)}))))

    "meta"
    (let [action (keyword (first args))
          meta-args (vec (rest args))
          positional (positional-args meta-args)
          scope (merge (project-scope meta-args) (temporal-options meta-args))]
      (case action
        :defs
        (store/with-node (store/storage-path)
          (fn [xtdb]
            (print-json (->> (vals (store/metadata-defs xtdb scope))
                             (sort-by (comp metadata/key-name :key))
                             (mapv metadata/export-definition)))))

        :set
        (let [[target key value] positional]
          (when-not (and target key value)
            (throw (ex-info "Missing metadata target, key, or value."
                            {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-meta-summary
               (store/commit-metadata! xtdb
                                       [(metadata-row-from-cli meta-args target key value)]
                                       {:valid-from (:valid-at (temporal-options meta-args))})))))

        :get
        (let [target (first positional)]
          (when-not target
            (throw (ex-info "Missing metadata target." {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-json (store/metadata-for-targets xtdb [target] scope)))))

        :unset
        (let [[target key] positional]
          (when-not (and target key)
            (throw (ex-info "Missing metadata target or key." {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-meta-summary
               (store/delete-metadata! xtdb
                                       (merge scope
                                              {:target-id target
                                               :key key
                                               :source (keyword (or (option-value meta-args "--source")
                                                                    (name metadata/default-source)))
                                               :valid-from (:valid-at (temporal-options meta-args))}))))))

        (throw (ex-info "Unknown meta command." {:command action
                                                 :usage (usage)}))))

    "views"
    (let [action (keyword (first args))
          view-args (vec (rest args))
          positional (positional-args view-args)
          scope (merge (project-scope view-args) (temporal-options view-args))]
      (case action
        :list
        (store/with-node (store/storage-path)
          (fn [xtdb]
            (print-views-summary (store/graph-views xtdb scope))))

        :show
        (let [view-id (first positional)]
          (when-not view-id
            (throw (ex-info "Missing view id." {:usage (usage)})))
          (store/with-node (store/storage-path)
            (fn [xtdb]
              (print-json (or (store/graph-view xtdb view-id scope)
                              {:error "view not found"
                               :view view-id})))))

        (throw (ex-info "Unknown views command." {:command action
                                                  :usage (usage)}))))

    "context"
    (let [query-text (str/join " " (positional-args args))
          retriever (keyword (or (option-value args "--retriever") "auto"))
          provider (provider-option args)
          model (or (option-value args "--model") (default-model provider))
          embedding-client (query-embedding-client retriever provider model)
          {:keys [project-id repo-id]} (project-scope args)
          temporal (temporal-options args)]
      (when (and (= :auto retriever) (nil? embedding-client))
        (binding [*out* *err*]
          (println "No embedding provider API key found; using lexical retrieval.")))
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (emit-json-or-enqueue
           args
           "context"
           project-id
           (context/context-packet xtdb
                                   query-text
                                   (context-packet-options xtdb
                                                           args
                                                           {:project-id project-id
                                                            :repo-id repo-id
                                                            :retriever retriever
                                                            :embedding-client embedding-client
                                                            :read-context temporal}))))))

    "cursor"
    (let [action (keyword (first args))
          cursor-args (vec (rest args))
          positional (positional-args cursor-args)
          retriever (keyword (or (option-value cursor-args "--retriever") "auto"))
          provider (provider-option cursor-args)
          model (or (option-value cursor-args "--model") (default-model provider))
          embedding-client (query-embedding-client retriever provider model)
          budget (parse-optional-long cursor-args "--budget")]
      (when (and (= :auto retriever) (nil? embedding-client))
        (binding [*out* *err*]
          (println "No embedding provider API key found; using lexical retrieval.")))
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (case action
            :create
            (let [query-text (or (option-value cursor-args "--query")
                                 (not-empty (str/trim (str/join " " positional))))
                  {:keys [project-id]} (project-scope cursor-args)]
              (emit-cursor-packet
               cursor-args
               (cursor/create! xtdb
                               {:project-id project-id
                                :query-text query-text
                                :retriever retriever
                                :embedding-client embedding-client
                                :map-path (default-map-path cursor-args)
                                :view-id (option-value cursor-args "--view")
                                :read-context (temporal-options cursor-args)
                                :budget (or budget context/default-budget)
                                :node-limit (or (parse-limit cursor-args)
                                                (parse-optional-long cursor-args
                                                                     "--entity-limit")
                                                context/default-entity-limit)
                                :edge-limit (parse-long-option cursor-args
                                                               "--edge-limit"
                                                               context/default-edge-limit)
                                :doc-limit (parse-long-option cursor-args
                                                              "--doc-limit"
                                                              context/default-doc-limit)
                                :snippet-chars (parse-long-option cursor-args
                                                                  "--snippet-chars"
                                                                  context/default-snippet-chars)
                                :min-confidence (parse-double-option cursor-args
                                                                     "--min-confidence"
                                                                     0.55)})))

            :show
            (let [cursor-id (first positional)]
              (when-not cursor-id
                (throw (ex-info "Missing cursor id." {:usage (usage)})))
              (emit-cursor-packet cursor-args
                                  (cursor/show xtdb cursor-id {:budget budget})))

            :open
            (let [[cursor-id target] positional]
              (when-not (and cursor-id target)
                (throw (ex-info "Missing cursor id or target."
                                {:usage (usage)})))
              (emit-cursor-packet cursor-args
                                  (cursor/open! xtdb cursor-id target {:budget budget})))

            :expand
            (let [[cursor-id target] positional]
              (when-not (and cursor-id target)
                (throw (ex-info "Missing cursor id or target."
                                {:usage (usage)})))
              (emit-cursor-packet cursor-args
                                  (cursor/expand! xtdb
                                                  cursor-id
                                                  target
                                                  {:budget budget
                                                   :relation (option-value cursor-args
                                                                           "--relation")
                                                   :limit (parse-limit cursor-args)})))

            :docs
            (let [[cursor-id target] positional]
              (when-not (and cursor-id target)
                (throw (ex-info "Missing cursor id or target."
                                {:usage (usage)})))
              (emit-cursor-packet cursor-args
                                  (cursor/docs! xtdb cursor-id target {:budget budget})))

            :search
            (let [[cursor-id & query-parts] positional
                  query-text (or (option-value cursor-args "--query")
                                 (str/join " " query-parts))]
              (when-not cursor-id
                (throw (ex-info "Missing cursor id." {:usage (usage)})))
              (emit-cursor-packet cursor-args
                                  (cursor/search! xtdb
                                                  cursor-id
                                                  query-text
                                                  {:budget budget
                                                   :retriever retriever
                                                   :embedding-client embedding-client
                                                   :limit (parse-limit cursor-args)})))

            (throw (ex-info "Unknown cursor command." {:command action
                                                       :usage (usage)}))))))

    "embed"
    (let [provider (provider-option args)
          model (or (option-value args "--model") (default-model provider))
          batch-size (parse-long-option args "--batch-size" embedding/default-batch-size)
          limit (parse-limit args)
          {:keys [project-id repo-id]} (project-scope args)]
      (when-not (provider-api-key provider)
        (throw (ex-info (missing-key-message provider)
                        {:provider provider})))
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (print-embed-summary
           (embedding/embed-search-docs! xtdb
                                         (provider-client provider model)
                                         {:batch-size batch-size
                                          :limit limit
                                          :project-id project-id
                                          :repo-id repo-id})))))

    "query"
    (let [query-text (str/join " " (positional-args args))
          retriever (keyword (or (option-value args "--retriever") "auto"))
          provider (provider-option args)
          model (or (option-value args "--model") (default-model provider))
          limit (or (parse-limit args) 10)
          embedding-client (query-embedding-client retriever provider model)
          {:keys [project-id repo-id]} (project-scope args)
          temporal (temporal-options args)]
      (when (and (= :auto retriever) (nil? embedding-client))
        (binding [*out* *err*]
          (println "No embedding provider API key found; using lexical retrieval.")))
      (when (str/blank? query-text)
        (throw (ex-info "Missing query text." {:usage (usage)})))
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (if (json-output? args)
            (print-json
             (query/search-report xtdb
                                  query-text
                                  {:limit limit
                                   :retriever retriever
                                   :embedding-client embedding-client
                                   :project-id project-id
                                   :repo-id repo-id
                                   :read-context temporal}))
            (doseq [result (query/semantic-query xtdb
                                                 query-text
                                                 {:limit limit
                                                  :retriever retriever
                                                  :embedding-client embedding-client
                                                  :project-id project-id
                                                  :repo-id repo-id
                                                  :read-context temporal})]
              (print-query-result result))))))

    "project"
    (let [action (keyword (first args))
          project-args (vec (rest args))
          config-path (first (positional-args project-args))]
      (when-not config-path
        (throw (ex-info "Missing project config path." {:usage (usage)})))
      (case action
        :add-repo
        (let [[_ repo-root] (positional-args project-args)]
          (when-not repo-root
            (throw (ex-info "Missing repo path for project add-repo."
                            {:usage (usage)})))
          (let [project (project/add-repo-to-config!
                         config-path
                         repo-root
                         {:repo-id (option-value project-args "--repo")
                          :role (some-> (option-value project-args "--role") keyword)})
                repo (last (:repos project))
                index? (or (some #{"--index"} project-args)
                           (some #{"--infer"} project-args))
                infer? (some #{"--infer"} project-args)
                next-commands (cond-> []
                                (not index?)
                                (conj (str "ygg project index " config-path))
                                (not infer?)
                                (conj (str "ygg project infer " config-path))
                                true
                                (conj (str "ygg project maintain " config-path)))]
            (if index?
              (store/with-node (store/storage-path)
                (fn [xtdb]
                  (let [index-summary (project/index-project-repo! xtdb
                                                                   project
                                                                   (:id repo)
                                                                   {})
                        system-summary (when infer?
                                         (project/infer-project! xtdb project))]
                    (print-project-add-repo-summary
                     {:project project
                      :repo repo
                      :index-summary index-summary
                      :system-summary system-summary
                      :next next-commands}))))
              (print-project-add-repo-summary
               {:project project
                :repo repo
                :next next-commands}))))

        (let [project (project/read-project config-path)]
          (case action
            :inspect
            (print-project-status! project config-path project-args)

            :index
            (if (dry-run? project-args)
              (print-project-index-summary (project/index-project! nil project {:dry-run? true}))
              (store/with-node (store/storage-path)
                (fn [xtdb]
                  (print-project-index-summary (project/index-project! xtdb project {})))))

            :infer
            (store/with-node (store/storage-path)
              (fn [xtdb]
                (print-system-summary (project/infer-project! xtdb project))))

            :maintain
            (store/with-node (store/storage-path)
              (fn [xtdb]
                (let [map-path (default-map-path project-args)
                      report (project/maintain-project
                              xtdb
                              project
                              {:low-confidence-threshold
                               (parse-double-option project-args
                                                    "--min-confidence"
                                                    0.60)
                               :map-overlay (when map-path
                                              (map-store/read-map map-path))})]
                  (if (json-output? project-args)
                    (print-json report)
                    (print-maintenance-report report)))))

            (throw (ex-info "Unknown project command." {:command action
                                                        :usage (usage)}))))))

    "graph"
    (let [raw-mode (keyword (first args))
          raw-graph-args (vec (rest args))
          export? (= :export raw-mode)
          mode (if export?
                 (keyword (first raw-graph-args))
                 raw-mode)
          graph-args (if export?
                       (vec (rest raw-graph-args))
                       raw-graph-args)
          limit (or (parse-limit graph-args)
                    (case mode :query 40 graph/default-node-limit))
          depth (parse-depth graph-args)
          value (graph-output-value mode graph-args)]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (let [data (graph-data-for-mode xtdb mode graph-args limit depth)]
            (if export?
              (print-canonical-output
               (graph/write-canonical! (graph-json-output graph-args mode value) data)
               data)
              (print-graph-output
               (report/write-graph-viewer! (graph-output graph-args mode value) data)
               data))))))

    "deps"
    (let [value (first (positional-args args))
          scope (assoc (project-scope args) :read-context (temporal-options args))]
      (when-not value
        (throw (ex-info "Missing node query." {:usage (usage)})))
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (print-deps (query/deps xtdb value scope)))))

    "packages"
    (let [scope (project-scope args)
          map-path (default-map-path args)
          opts (cond-> {:limit (parse-limit args)}
                 (option-value args "--ecosystem") (assoc :ecosystem
                                                          (option-value args "--ecosystem"))
                 (option-value args "--package") (assoc :package
                                                        (option-value args "--package"))
                 (some #{"--with-conflicts"} args) (assoc :with-conflicts? true)
                 (some #{"--without-import-evidence"} args)
                 (assoc :without-import-evidence? true)
                 map-path (assoc :map-overlay (map-store/read-map map-path)))]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (let [report (dependency/package-report xtdb scope opts)]
            (if (json-output? args)
              (print-json report)
              (print-package-report report))))))

    "path"
    (let [[source target] (positional-args args)
          scope (assoc (project-scope args) :read-context (temporal-options args))]
      (when-not (and source target)
        (throw (ex-info "Missing source or target." {:usage (usage)})))
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (print-path (if (system-path? args)
                        (query/system-path xtdb source target scope)
                        (query/graph-path xtdb source target scope))))))

    "report"
    (report! args)

    "mcp"
    (mcp/run-stdio! (mcp/server-context args))

    (throw (ex-info "Unknown command." {:command command
                                        :usage (usage)}))))

(defn -main
  [& args]
  (try
    (silence-jul!)
    (if-let [command (first args)]
      (dispatch command (vec (rest args)))
      (println (usage)))
    (shutdown-agents)
    (catch Exception e
      (binding [*out* *err*]
        (let [data (ex-data e)]
          (if (= cursor/error-schema (:schema data))
            (print-json data)
            (do
              (println (ex-message e))
              (when data
                (pprint/pprint data))))))
      (shutdown-agents)
      (System/exit 1))))
