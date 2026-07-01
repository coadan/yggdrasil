(ns ygg.cli
  "Command line interface."
  (:require [ygg.agent-install :as agent-install]
            [ygg.audit-scope :as audit-scope]
            [ygg.cli-bench :as cli-bench]
            [ygg.cli-options :refer [json-output?
                                     option-value
                                     option-values
                                     parse-double-option
                                     parse-limit
                                     parse-long-option
                                     parse-optional-double
                                     parse-optional-long
                                     positional-args
                                     project-scope]]
            [ygg.cli-query :as cli-query]
            [ygg.cli-start :as cli-start]
            [ygg.cli-sync :as cli-sync]
            [ygg.context :as context]
            [ygg.corrections :as corrections]
            [ygg.corrections-api :as corrections-api]
            [ygg.daemon-contract :as daemon-contract]
            [ygg.dependency :as dependency]
            [ygg.embedding :as embedding]
            [ygg.embedding.local :as local-embedding]
            [ygg.evidence :as evidence]
            [ygg.hook :as hook]
            [ygg.index-maintenance :as index-maintenance]
            [ygg.index-maintenance-worker :as index-maintenance-worker]
            [ygg.memory :as memory]
            [ygg.metadata :as metadata]
            [ygg.mcp :as mcp]
            [ygg.cli-plugin :as cli-plugin]
            [ygg.cli-project :as cli-project]
            [ygg.project :as project]
            [ygg.project-registry :as registry]
            [ygg.queue :as queue]
            [ygg.system :as system]
            [ygg.system.decision-classifier :as decision-classifier]
            [ygg.watch :as watch]
            [ygg.work :as work]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
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
  [xtdb project-info]
  (when-let [{:keys [project config-path]} project-info]
    (let [overlay (when (store/xtdb-handle? xtdb)
                    (corrections/overlay xtdb (:id project)))
          summary (evidence/summarize xtdb
                                      project
                                      {:correction-overlay overlay
                                       :config-path config-path
                                       :summary? true})]
      (evidence/packet-freshness summary))))

(defn- comma-keywords
  [value]
  (->> (str/split (str value) #",")
       (map str/trim)
       (remove str/blank?)
       (map keyword)
       vec))

(defn- query-input-options
  [args]
  (cond-> {:task (keyword (or (option-value args "--task") "auto"))
           :anchors (option-values args "--anchor")
           :symbols (option-values args "--symbol")
           :literals (option-values args "--literal")
           :changed-only? (boolean (some #{"--changed-only"} args))}
    (option-value args "--lanes")
    (assoc :lanes (comma-keywords (option-value args "--lanes")))

    (option-value args "--since")
    (assoc :since (option-value args "--since"))))

(defn- context-packet-options
  [xtdb args {:keys [project-id repo-id retriever embedding-client read-context active-indexing
                     fts-weight]}]
  (let [project-info (matching-context-project args project-id)
        freshness (context-packet-freshness xtdb project-info)
        plugins (not-empty (get-in project-info [:project :plugins]))]
    (cond-> {:project-id project-id
             :repo-id repo-id
             :retriever retriever
             :embedding-client embedding-client
             :fts-weight (or fts-weight (parse-optional-double args "--fts-weight"))
             :read-context read-context
             :output (keyword (or (option-value args "--output") "compact"))
             :proof-commands? (boolean (some #{"--proof-commands"} args))
             :query-input (query-input-options args)
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

      active-indexing
      (assoc :active-indexing active-indexing)

      plugins
      (assoc :plugins plugins))))

(defn- validate-work-result
  [root id]
  (work/validate-result root id))

(defn- apply-work-result!
  [xtdb root id]
  (work/apply-result! xtdb root id))

(defn- print-json
  [value]
  (println (json/write-json-str value {:indent-str "  "})))

(defn- queue-project-id
  [args]
  (or (option-value args "--project")
      (try
        (:project-id (registry/resolve-project {:cwd (System/getProperty "user.dir")}))
        (catch Exception _
          nil))))

(defn- project-queue-root
  [project-id]
  (when-not (str/blank? (str project-id))
    (store/project-sqlite-path project-id)))

(defn- queue-root
  ([args]
   (or (option-value args "--queue-dir")
       (some-> (queue-project-id args) project-queue-root)
       queue/default-root))
  ([args project-id]
   (or (option-value args "--queue-dir")
       (project-queue-root project-id)
       (queue-root args))))

(defn- corrections-project-ref
  [args]
  (if-let [config-path (option-value args "--config")]
    {:project (project/read-project config-path)
     :config-path config-path
     :source :config-path}
    (registry/resolve-project {:project-id (option-value args "--project")
                               :cwd (System/getProperty "user.dir")})))

(defn- with-corrections-project
  [args f]
  (let [{:keys [project]} (corrections-project-ref args)]
    (store/with-node (store/storage-path (:id project))
      (fn [xtdb]
        (f xtdb project)))))

(defn- with-memory-project
  [args f]
  (with-corrections-project args f))

(defn- memory-status-option
  [args]
  (cond
    (some #{"--reviewed"} args) :reviewed
    (option-value args "--status") (keyword (option-value args "--status"))
    :else nil))

(defn- memory-add!
  [args]
  (let [text (or (option-value args "--text")
                 (not-empty (str/join " " (positional-args args))))]
    (when (str/blank? (str text))
      (throw (ex-info "Missing memory text."
                      {:usage (usage)})))
    (with-memory-project
      args
      (fn [xtdb project]
        (print-json
         {:schema memory/write-schema
          :action "add"
          :project-id (:id project)
          :memory (memory/packet-row
                   (memory/add!
                    xtdb
                    (:id project)
                    (cond-> {:repo-id (option-value args "--repo")
                             :scope (option-value args "--scope")
                             :visibility (option-value args "--visibility")
                             :owner (option-value args "--owner")
                             :agent-id (option-value args "--agent")
                             :kind (option-value args "--kind")
                             :status (or (memory-status-option args) :suggested)
                             :target-ids (option-values args "--target")
                             :tags (option-values args "--tag")
                             :text text
                             :summary (option-value args "--summary")
                             :source {:kind :human}}
                      (option-value args "--reason")
                      (assoc :reason (option-value args "--reason")))))})))))

(defn- memory-review!
  [args]
  (with-memory-project
    args
    (fn [xtdb project]
      (print-json
       (memory/review xtdb
                      {:project-id (:id project)
                       :repo-id (option-value args "--repo")
                       :owner (option-value args "--owner")
                       :status (keyword (or (option-value args "--status")
                                            "suggested"))
                       :limit (or (parse-limit args) 50)})))))

(defn- memory-write-one!
  [args action f]
  (let [id (first (positional-args args))]
    (when (str/blank? (str id))
      (throw (ex-info "Missing memory id."
                      {:usage (usage)})))
    (with-memory-project
      args
      (fn [xtdb project]
        (print-json
         {:schema memory/write-schema
          :action action
          :project-id (:id project)
          :memory (memory/packet-row (f xtdb id))})))))

(defn- memory-supersede!
  [args]
  (let [id (first (positional-args args))
        text (option-value args "--text")]
    (when (str/blank? (str id))
      (throw (ex-info "Missing memory id."
                      {:usage (usage)})))
    (when (str/blank? (str text))
      (throw (ex-info "Missing replacement memory text."
                      {:usage (usage)})))
    (with-memory-project
      args
      (fn [xtdb project]
        (let [replacement (cond-> {:text text
                                   :reason (option-value args "--reason")
                                   :source {:kind :human}}
                            (option-value args "--repo")
                            (assoc :repo-id (option-value args "--repo"))
                            (option-value args "--scope")
                            (assoc :scope (option-value args "--scope"))
                            (option-value args "--visibility")
                            (assoc :visibility (option-value args "--visibility"))
                            (option-value args "--owner")
                            (assoc :owner (option-value args "--owner"))
                            (option-value args "--agent")
                            (assoc :agent-id (option-value args "--agent"))
                            (option-value args "--kind")
                            (assoc :kind (option-value args "--kind"))
                            (memory-status-option args)
                            (assoc :status (memory-status-option args))
                            (seq (option-values args "--target"))
                            (assoc :target-ids (option-values args "--target"))
                            (seq (option-values args "--tag"))
                            (assoc :tags (option-values args "--tag"))
                            (option-value args "--summary")
                            (assoc :summary (option-value args "--summary")))
              result (memory/supersede! xtdb id replacement)]
          (print-json (assoc result :project-id (:id project))))))))

(defn- memory-attach!
  [args]
  (let [[id positional-target] (positional-args args)
        targets (cond-> (option-values args "--target")
                  positional-target (conj positional-target))]
    (when (str/blank? (str id))
      (throw (ex-info "Missing memory id."
                      {:usage (usage)})))
    (with-memory-project
      args
      (fn [xtdb project]
        (print-json
         {:schema memory/write-schema
          :action "attach"
          :project-id (:id project)
          :memory (memory/packet-row
                   (memory/attach! xtdb
                                   id
                                   targets
                                   (option-value args "--reason")))})))))

(defn- memory!
  [args]
  (let [action (keyword (first args))
        memory-args (vec (rest args))]
    (case action
      :add (memory-add! memory-args)
      :review (memory-review! memory-args)
      :accept (memory-write-one! memory-args
                                 "accept"
                                 #(memory/accept! %1
                                                  %2
                                                  (option-value memory-args
                                                                "--reason")))
      :reject (memory-write-one! memory-args
                                 "reject"
                                 #(memory/reject! %1
                                                  %2
                                                  (option-value memory-args
                                                                "--reason")))
      :supersede (memory-supersede! memory-args)
      :attach (memory-attach! memory-args)
      (throw (ex-info "Unknown memory command."
                      {:command action
                       :usage (usage)})))))

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
                      {:root (queue-root args project-id)
                       :kind kind
                       :project-id project-id
                       :priority (queue-priority args)})))
    (print-json payload)))

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
   :temporal-options temporal-options})

(defn- print-project-status!
  ([config-path args]
   (binding [cli-project/*deps* (project-deps)]
     (cli-project/print-project-status! config-path args)))
  ([project config-path args]
   (binding [cli-project/*deps* (project-deps)]
     (cli-project/print-project-status! project config-path args))))

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

(defn sync-deps
  []
  {:usage usage
   :print-json print-json
   :enqueue-output? enqueue-output?
   :queue-root queue-root
   :queue-priority queue-priority
   :severity-priority severity-priority
   :print-source-coverage print-source-coverage
   :print-sync-summary print-sync-summary
   :print-project-add-repo-summary print-project-add-repo-summary
   :queue-agent queue-agent
   :queue-lease-ms queue-lease-ms
   :apply-work-result! apply-work-result!
   :validate-work-result validate-work-result
   :dispatch dispatch
   :print-project-status! print-project-status!})

(defn- query-index?
  [args]
  (cli-sync/query-index? args))

(defn- sync-dispatch!
  [args]
  (cli-sync/sync-dispatch! args (sync-deps)))

(defn query-deps
  []
  {:usage usage
   :print-json print-json
   :temporal-options temporal-options
   :context-packet-options context-packet-options})

(defn- print-embed-summary [result] (cli-query/print-embed-summary result))
(defn- print-local-embedding-setup-summary
  [{:keys [venv python requirements created? default?]}]
  (println "# Local Embeddings")
  (println "- venv" venv)
  (println "- python" python)
  (println "- requirements" requirements)
  (println "- created" (boolean created?))
  (println "- default" (boolean default?)))
(defn- candidate-system? [system] (cli-query/candidate-system? system))
(defn- print-candidate-systems [systems limit] (cli-query/print-candidate-systems systems limit))
(defn- embedding-options [args] (cli-query/embedding-options args))
(defn- provider-api-key [provider] (cli-query/provider-api-key provider))
(defn- provider-client
  ([provider model] (cli-query/provider-client provider model))
  ([provider model opts] (cli-query/provider-client provider model opts)))
(defn- missing-key-message [provider] (cli-query/missing-key-message provider))
(defn- llm-provider-option [args] (cli-query/llm-provider-option args))
(defn- llm-model [provider args] (cli-query/llm-model provider args))
(defn- llm-client [provider model args] (cli-query/llm-client provider model args))
(defn- print-package-report [result] (cli-query/print-package-report result))
(defn- query-embedding-client
  ([retriever provider model]
   (cli-query/query-embedding-client retriever provider model))
  ([retriever opts]
   (cli-query/query-embedding-client retriever opts)))
(defn- query! [args]
  (binding [cli-query/*deps* (query-deps)]
    (cli-query/query! args)))
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

(defn- agent!
  [args]
  (let [action (first args)
        agent-args (vec (rest args))
        platform (or (option-value agent-args "--platform") "codex")
        opts {:project? (boolean (some #{"--project"} agent-args))
              :hooks? (boolean (some #{"--hooks"} agent-args))
              :skill? (boolean (some #{"--skill"} agent-args))
              :mcp? (boolean (some #{"--mcp"} agent-args))
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

(defn- project-summary
  [project]
  {:id (:id project)
   :name (:name project)
   :repos (mapv #(select-keys % [:id :root :role]) (:repos project))})

(defn- print-project-summary
  [project]
  (println "# Project")
  (println "- id" (:id project))
  (println "- name" (:name project))
  (println "- repos" (count (:repos project)))
  (doseq [{:keys [id root role]} (:repos project)]
    (println "-" id (name role) root)))

(defn- current!
  [args]
  (let [{:keys [project source root registry project-ref]} (registry/resolve-project
                                                            {:project-id (option-value args "--project")
                                                             :cwd (System/getProperty "user.dir")})
        result {:schema "ygg.current/v1"
                :project (project-summary project)
                :source source
                :matched-root root
                :project-ref project-ref
                :storage-path (store/storage-path (:id project))
                :registry registry}]
    (if (json-output? args)
      (print-json result)
      (do
        (print-project-summary project)
        (println "- source" (name source))
        (when root
          (println "- matched-root" root))
        (when project-ref
          (println "- project-ref" project-ref))
        (println "- storage" (:storage-path result))
        (println "- registry" registry)))))

(defn- use-project!
  [args]
  (let [project-id (first (positional-args args))]
    (when (str/blank? (str project-id))
      (throw (ex-info "Missing project id." {:usage "use <project-id> [--json]"})))
    (let [result (registry/use-project! project-id (System/getProperty "user.dir"))]
      (if (json-output? args)
        (print-json result)
        (do
          (println "# Active Project")
          (println "- project" (:project-id result))
          (println "- root" (:root result))
          (println "- project-ref" (:project-ref result))
          (println "- registry" (:registry result)))))))

(defn- project-list!
  [args]
  (let [registry-data (registry/read-registry)
        project-ids (sort (keys (registry/projects registry-data)))
        rows (mapv (fn [project-id]
                     (project-summary (registry/read-project registry-data project-id)))
                   project-ids)]
    (if (json-output? args)
      (print-json {:schema "ygg.projects.list/v1"
                   :registry (registry/registry-path)
                   :projects rows})
      (do
        (println "# Projects")
        (println "- registry" (registry/registry-path))
        (doseq [{:keys [id name repos]} rows]
          (println "-" id (str "(" name ", " (count repos) " repos)")))))))

(defn- projects!
  [args]
  (let [action (keyword (or (first args) "list"))
        project-args (vec (rest args))]
    (case action
      :list
      (project-list! project-args)

      :show
      (let [project-id (first (positional-args project-args))]
        (when (str/blank? (str project-id))
          (throw (ex-info "Missing project id." {:usage "projects show <project-id>"})))
        (let [project (registry/read-project project-id)]
          (if (json-output? project-args)
            (print-json {:schema "ygg.projects.show/v1"
                         :registry (registry/registry-path)
                         :project (project-summary project)})
            (print-project-summary project))))

      :register
      (let [config-path (first (positional-args project-args))]
        (when (str/blank? (str config-path))
          (throw (ex-info "Missing project config path."
                          {:usage "projects register <project.edn>"})))
        (let [result (registry/register-project-config! config-path)]
          (if (json-output? project-args)
            (print-json result)
            (do
              (println "# Project Registered")
              (println "- project" (:project-id result))
              (println "- config" (:config-path result))
              (println "- registry" (:registry result))))))

      :remove
      (let [project-id (first (positional-args project-args))]
        (when (str/blank? (str project-id))
          (throw (ex-info "Missing project id." {:usage "projects remove <project-id>"})))
        (let [result (registry/remove-project! project-id)]
          (if (json-output? project-args)
            (print-json result)
            (do
              (println "# Project Removed")
              (println "- project" (:project-id result))
              (println "- removed" (:removed? result))))))

      (throw (ex-info "Unknown projects command."
                      {:command (name action)
                       :usage "projects list|show <project-id>|register <project.edn>|remove <project-id>"})))))

(defn- maintenance-status-result
  ([project config-path]
   (maintenance-status-result project config-path nil))
  ([project config-path project-ref]
   {:schema "ygg.maintenance.config/v1"
    :config-path config-path
    :project-ref project-ref
    :status (index-maintenance-worker/config-status project)}))

(defn- executor-label
  [{:keys [id type provider model kinds reasoning available missing-env]}]
  (str id
       " "
       (name type)
       (when provider
         (str "/" (name provider)))
       (when model
         (str " " model))
       (when reasoning
         (str " reasoning=" reasoning))
       " kinds="
       (str/join "," kinds)
       " "
       (if available "available" (str "missing-env=" missing-env))))

(defn- schedule-label
  [{:keys [id enabled every-minutes task check enqueue query-index run-on-start]}]
  (if task
    (str (if enabled "enabled" "disabled")
         " "
         id
         " "
         (name task)
         " every="
         every-minutes
         "m"
         " check="
         check
         " enqueue="
         enqueue
         " query-index="
         query-index
         " run-on-start="
         run-on-start)
    "not-configured"))

(defn- print-maintenance-status
  [{:keys [config-path project-ref status]}]
  (println "# Auto Maintenance")
  (println "- project" (:project-id status))
  (when config-path
    (println "- config" config-path))
  (when project-ref
    (println "- project-ref" project-ref))
  (println "- maintenance" (cond
                             (not (:configured status)) "not-configured"
                             (:enabled status) "enabled"
                             :else "disabled"))
  (println "- schedules" (count (:schedules status)))
  (doseq [schedule (:schedules status)]
    (println "  schedule" (schedule-label schedule)))
  (when-let [work (:work status)]
    (println "- work"
             (str "decisions=" (:max-decisions work))
             (str "per-kind=" (:max-decisions-per-kind work))
             (str "infra=" (:max-infra-reviews work))
             (str "dependency=" (:max-dependency-reviews work))
             (str "decision-batch=" (:decision-batch-size work))
             (str "review-batch=" (:review-batch-size work))))
  (println "- worker" (cond
                        (not (get-in status [:worker :configured])) "not-configured"
                        (get-in status [:worker :enabled]) "enabled"
                        :else "disabled"))
  (println "- executors"
           (str (get-in status [:worker :availableExecutorCount])
                "/"
                (get-in status [:worker :executorCount])
                " available"))
  (when (:queueRoot status)
    (println "- queue" (:queueRoot status)))
  (when (:reportDir status)
    (println "- reports" (:reportDir status)))
  (doseq [executor (get-in status [:worker :executors])]
    (println "  uses" (executor-label executor))))

(defn- print-maintenance-result
  [args result]
  (if (json-output? args)
    (print-json result)
    (print-maintenance-status result)))

(defn- schedule-options
  [args]
  (let [disable? (boolean (some #{"--disable"} args))
        every-minutes (parse-optional-long args "--every-minutes")]
    (cond-> {:enabled (not disable?)}
      every-minutes (assoc :every-minutes every-minutes)
      (some #{"--check"} args) (assoc :check true)
      (some #{"--no-check"} args) (assoc :check false)
      (some #{"--enqueue"} args) (assoc :enqueue true)
      (some #{"--no-enqueue"} args) (assoc :enqueue false)
      (some #{"--query-index"} args) (assoc :query-index true)
      (some #{"--no-query-index"} args) (assoc :query-index false)
      (some #{"--run-on-start"} args) (assoc :run-on-start true)
      (some #{"--no-run-on-start"} args) (assoc :run-on-start false))))

(defn- maintenance-candidates!
  [args]
  (let [{:keys [project-id]} (project-scope args)
        limit (or (parse-limit args) 50)]
    (when (str/blank? (str project-id))
      (throw (ex-info "Missing --project for maintenance candidates."
                      {:usage "maintenance candidates --project ID"})))
    (store/with-node (store/storage-path project-id)
      (fn [xtdb]
        (print-candidate-systems
         (->> (candidate-system-rows xtdb project-id)
              (filter candidate-system?)
              (sort-by (juxt :repo-id :label))
              vec)
         limit)))))

(defn- maintenance-classify!
  [args]
  (let [decision-id (first (positional-args args))
        {:keys [project-id]} (project-scope args)
        provider (llm-provider-option args)
        model (llm-model provider args)]
    (when-not decision-id
      (throw (ex-info "Missing decision id."
                      {:usage "maintenance classify <decision-id> --project ID"})))
    (when (str/blank? (str project-id))
      (throw (ex-info "Missing --project for maintenance classification."
                      {:usage "maintenance classify <decision-id> --project ID"})))
    (store/with-node (store/storage-path project-id)
      (fn [xtdb]
        (let [report (system/maintenance-report
                      xtdb
                      project-id
                      {:low-confidence-threshold
                       (parse-double-option args "--min-confidence" 0.60)})
              decision (decision-classifier/decision-by-id
                        (:decision-queue report)
                        decision-id)]
          (when-not decision
            (throw (ex-info "Maintenance decision not found."
                            {:decision-id decision-id
                             :project-id project-id})))
          (if (enqueue-output? args)
            (print-json
             (queue-enqueued-result
              (queue/enqueue!
               (decision-classifier/decision-packet decision)
               {:root (queue-root args project-id)
                :kind "maintenance-decision"
                :project-id project-id
                :source {:schema index-maintenance/source-schema
                         :producer index-maintenance/producer
                         :lane index-maintenance/graph-lane}
                :priority (queue-priority args
                                          (severity-priority (:severity decision)))})))
            (print-json
             (decision-classifier/classify
              {:client (llm-client provider model args)
               :decision decision}))))))))

(defn- maintenance-status-project
  [args]
  (registry/resolve-project {:project-id (option-value args "--project")
                             :config-path (first (positional-args args))
                             :cwd (System/getProperty "user.dir")}))

(defn- maintenance!
  [args]
  (let [action (keyword (or (first args) "status"))
        maintenance-args (vec (rest args))]
    (cond
      (= :worker action)
      (let [worker-action (keyword (first maintenance-args))
            worker-args (vec (rest maintenance-args))
            config-path (first (positional-args worker-args))]
        (when-not config-path
          (throw (ex-info "Missing project config path."
                          {:usage "maintenance worker enable|disable <project.edn>"})))
        (let [project (case worker-action
                        :enable (project/set-maintenance-worker-enabled! config-path true)
                        :disable (project/set-maintenance-worker-enabled! config-path false)
                        (throw (ex-info "Unknown maintenance worker command."
                                        {:command worker-action
                                         :usage "maintenance worker enable|disable <project.edn>"})))]
          (print-maintenance-result worker-args
                                    (maintenance-status-result project config-path))))

      (= :candidates action)
      (maintenance-candidates! maintenance-args)

      (= :classify action)
      (maintenance-classify! maintenance-args)

      (= :status action)
      (let [{:keys [project config-path project-ref]} (maintenance-status-project
                                                       maintenance-args)]
        (print-maintenance-result maintenance-args
                                  (maintenance-status-result project
                                                             config-path
                                                             project-ref)))

      :else
      (let [config-path (first (positional-args maintenance-args))]
        (when-not config-path
          (throw (ex-info "Missing project config path."
                          {:usage (str "maintenance enable|disable|schedule <project.edn>"
                                       " | maintenance status [<project.edn>]"
                                       " | maintenance candidates|classify ...")})))
        (let [project (case action
                        :status (project/read-project config-path)
                        :enable (project/set-maintenance-enabled! config-path true)
                        :disable (project/set-maintenance-enabled! config-path false)
                        :schedule (let [opts (schedule-options maintenance-args)
                                        task (or (:task opts) "sync")
                                        schedule-id (or (option-value maintenance-args "--id")
                                                        task)]
                                    (project/set-maintenance-schedule!
                                     config-path
                                     schedule-id
                                     opts))
                        (throw (ex-info "Unknown maintenance command."
                                        {:command action
                                         :usage (str "maintenance status [<project.edn>]"
                                                     " | maintenance enable|disable|schedule|worker <project.edn>"
                                                     " | maintenance candidates|classify ...")})))
              result (maintenance-status-result project config-path)]
          (print-maintenance-result maintenance-args result))))))

(defn usage
  []
  (str/join
   "\n"
   ["Usage:"
    ""
    "Setup:"
    "  init <repo-root> [--project ID] [--name NAME] [--out project.edn] [--force] [--sync] [--harness codex|auto|none] [--hooks] [--skill] [--mcp] [--maintenance none|harness|deepseek|openrouter] [--maintenance-model MODEL] [--maintenance-reasoning low|medium|high|xhigh] [--maintenance-command CMD] [--yes|--no-input]"
    "  init --workbench <root> [--task TASK] [--project ID] [--name NAME] [--out project.edn] [--force]"
    "  current [--project ID] [--json]"
    "  use <project-id> [--json]"
    "  projects list|show <project-id>|register <project.edn>|remove <project-id> [--json]"
    ""
    "Sync and maintenance:"
    "  audit-scope [<project.edn>] [--project ID] [--repo ID] [--json]"
    "  sync [<project.edn>] [--project ID] [--repo ID] [--check] [--enqueue] [--query-index] [--dry-run] [--json]"
    "  sync inspect [<project.edn>] [--project ID] [--json]"
    "  sync coverage [<project.edn>] [--project ID] [--json]"
    "  sync activity [<project.edn>] [--project ID] [--queue-dir DIR] [--json]"
    "  sync add-repo <project.edn> <repo-path> [--repo ID] [--role ROLE]"
    "  sync docs candidates <target> [--project ID] [--limit N] [--snippet-chars N]"
    "  sync docs for <target> [--project ID] [--snippet-chars N]"
    "  sync docs audit [--project ID]"
    "  sync meta defs|set|get|unset ..."
    "  sync view list|show ..."
    "  sync work list [--queue-dir DIR] [--status ready|claimed|done|rejected|failed] [--project ID] [--kind KIND] [--limit N]"
    "  sync work pull [--queue-dir DIR] [--project ID] [--kind KIND] [--agent ID] [--lease-minutes N]"
    "  sync work show <work-id> [--queue-dir DIR]"
    "  sync work complete <work-id> --result result.json [--queue-dir DIR]"
    "  sync work validate <work-id> [--queue-dir DIR]"
    "  sync work apply <work-id> [--queue-dir DIR] [--project ID]"
    "  sync work reject <work-id> --reason TEXT [--queue-dir DIR]"
    "  sync work release <work-id> [--reason TEXT] [--queue-dir DIR]"
    "  sync work release-expired [--queue-dir DIR]"
    "  sync work heartbeat <work-id> [--queue-dir DIR] [--agent ID] [--lease-minutes N]"
    "  sync work auto <project.edn> [--json]"
    "  maintenance status [<project.edn>] [--project ID] [--json]"
    "  maintenance enable|disable <project.edn> [--json]"
    "  maintenance worker enable|disable <project.edn> [--json]"
    "  maintenance schedule <project.edn> [--id ID] [--every-minutes N] [--check|--no-check] [--enqueue|--no-enqueue] [--query-index|--no-query-index] [--run-on-start|--no-run-on-start] [--disable] [--json]"
    "  maintenance candidates --project ID [--limit N]"
    "  maintenance classify <decision-id> --project ID [--enqueue] [--provider local|openrouter|openai] [--model MODEL]"
    "  corrections status [--project ID] [--json]"
    "  corrections review [--project ID] [--limit N] [--json]"
    "  corrections accept system <target> --kind KIND --label LABEL --include repo:path --reason TEXT [--project ID]"
    "  corrections set-kind <target> <kind> --reason TEXT [--project ID]"
    "  corrections include <target> <repo>:<path> --reason TEXT [--project ID]"
    "  corrections reject <kind> <value> --reason TEXT [--project ID]"
    "  corrections edge add <source> <target> <relation> --reason TEXT [--project ID]"
    "  corrections docs attach <target> <repo>:<path> --role ROLE --reason TEXT [--project ID]"
    "  corrections package import <import-prefix> <ecosystem>:<package> [--repo ID] --reason TEXT [--project ID]"
    "  memory add --text TEXT [--target ID] [--tag TAG] [--kind KIND] [--scope developer|project|repo] [--project ID]"
    "  memory review [--status suggested|observed|reviewed] [--project ID] [--json]"
    "  memory accept <memory-id> --reason TEXT [--project ID]"
    "  memory reject <memory-id> --reason TEXT [--project ID]"
    "  memory supersede <memory-id> --text TEXT --reason TEXT [--project ID]"
    "  memory attach <memory-id> <target-id> --reason TEXT [--project ID]"
    ""
    "Query:"
    "  query <text> [--project ID] [--repo ID] [--config project.edn] [--limit N] [--json] [--retriever auto|hybrid|lexical|semantic] [--provider local|openrouter|openai] [--model MODEL] [--fts-weight N] [--valid-at INSTANT]"
    "  affected <project.edn> [--files PATH,PATH | --since REV] [--repo ID] [--tests] [--json]"
    ""
    "View and report:"
    "  view overview|deps|query|systems|clusters|cluster [args] [--project ID] [--repo ID] [--depth N] [--limit N] [--detail primary|expanded|evidence|raw] [--view ID] [--format html|json] [--out PATH] [--valid-at INSTANT]"
    "  packages [--project ID] [--repo ID] [--ecosystem npm|cargo|go] [--package NAME] [--with-conflicts] [--without-import-evidence] [--limit N] [--json]"
    "  report [<project.edn>] [--project ID] [--out ygg-out] [--detail primary|expanded|evidence|raw] [--force]"
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
    "  agent install --platform codex --project [--hooks] [--skill] [--mcp] [--print-config]"
    "  agent uninstall --platform codex --project"
    "  agent list"
    "  watch <project.edn> [--query-index] [--debounce-ms N]"
    "  hook install <project.edn> [--query-index]"
    "  hook uninstall <project.edn>"
    "  hook status <project.edn>"
    ""
    "Server integration:"
    "  start"
    "  service start-at-login enable|disable|status [--json]"
    "  status [--json]"
    "  stop"
    "  mcp [--root DIR] [--config project.edn] [--queue-dir DIR] [--tools default,sync,work|all]"
    ""
    "Benchmarks:"
    "  bench prepare|run|report <benchmark.edn> [--case ID] [--cases ID,ID] [--parser-worker none|java|dotnet|javascript|typescript|all] [--index-timeout-ms N] [--out DIR] [--json]"
    "  bench show <benchmark.edn> --case ID [--out DIR] [--json]"
    "  bench agent-packet <benchmark.edn> [--case ID] [--cases ID,ID] [--mode ygg|shell-only] [--agent ID] [--parser-worker none|java|dotnet|javascript|typescript|all] [--enqueue] [--queue-dir DIR] [--out DIR] [--json]"
    "  bench agent-baseline <benchmark.edn> [--case ID] [--cases ID,ID] [--retriever auto|hybrid|lexical|semantic|local-vector|codebase-memory|graphify] [--provider local|openrouter|openai] [--model MODEL] [--batch-size N] [--embedding-input-max-chars N] [--embedding-request-timeout-ms N] [--embedding-max-retries N] [--embedding-provider-limit N] [--limit N] [--doc-limit N] [--retrieval-limit N] [--fusion-strategy weighted|rrf] [--sqlite-fts] [--fts-candidate-limit N] [--fts-weight N] [--vector-model MODEL] [--vector-command CMD] [--codebase-memory-command CMD] [--codebase-memory-bin PATH] [--codebase-memory-cache-dir DIR] [--graphify-command CMD] [--graphify-bin CMD] [--graphify-output-dir DIR] [--graphify-query-budget N] [--graphify-max-workers N] [--graphify-include-non-code] [--parser-worker none|java|dotnet|javascript|typescript|all] [--index-timeout-ms N] [--skip-existing] [--out DIR] [--json]"
    "  bench agent-run <benchmark.edn> --agent ID --command CMD [--case ID] [--cases ID,ID] [--mode ygg|shell-only] [--prompt-profile standard|fast] [--timeout-ms N] [--parser-worker none|java|dotnet|javascript|typescript|all] [--index-timeout-ms N] [--skip-existing] [--out DIR] [--json]"
    "  bench agent-rerun <benchmark.edn> --agent ID --command CMD [--agent-report agent-report.json] [--case ID] [--cases ID,ID] [--mode ygg|shell-only] [--prompt-profile standard|fast] [--timeout-ms N] [--parser-worker none|java|dotnet|javascript|typescript|all] [--index-timeout-ms N] [--out DIR] [--json]"
    "  bench agent-score <benchmark.edn> --case ID --result result.json [--parser-worker none|java|dotnet|javascript|typescript|all] [--out DIR] [--json]"
    "  bench agent-report <benchmark.edn> [--case ID] [--cases ID,ID] [--mode ygg|shell-only] [--agent ID] [--allow-unverified-scores] [--out DIR] [--json]"
    "  bench improve <benchmark.edn> [--case ID] [--cases ID,ID] [--mode ygg|shell-only] [--agent ID] [--allow-unverified-scores] [--out DIR] [--json]"
    "  bench agent-check <benchmark.edn> [--case ID] [--cases ID,ID] [--mode ygg|shell-only] [--agent ID] [--min-cases N] [--min-runs N] [--min-file-recall-at-5 N] [--min-file-recall-at-10 N] [--min-file-recall-at-20 N] [--min-case-file-recall-at-5 N] [--min-case-file-recall-at-10 N] [--min-case-file-recall-at-20 N] [--min-mrr N] [--min-case-mrr N] [--min-evidence-citation-rate N] [--min-path-evidence-citation-rate N] [--min-decision-f1 N] [--min-decision-evidence-citation-rate N] [--min-case-evidence-citation-rate N] [--min-case-path-evidence-citation-rate N] [--min-case-decision-f1 N] [--max-total-tokens N] [--max-input-tokens N] [--max-output-tokens N] [--max-cost-usd N] [--max-case-total-tokens N] [--max-case-input-tokens N] [--max-case-output-tokens N] [--max-case-cost-usd N] [--max-noise-at-20 N] [--max-case-noise-at-20 N] [--max-input-hinted-cases N] [--max-unsupported-ground-truth-files N] [--max-empty-result-runs N] [--max-missing-predicted-file-runs N] [--max-missing-decision-runs N] [--max-commandless-runs N] [--max-warning-runs N] [--max-hint-diagnostic-runs N] [--max-identity-mismatch-runs N] [--max-unverified-score-runs N] [--max-graph-expectation-failures N] [--max-maintenance-preflight-blockers N] [--max-missing-declared-source-kind-runs N] [--max-missed-runs N] [--max-context-rank-missing-runs N] [--max-missed-but-present-in-context-runs N] [--max-missed-and-absent-from-context-runs N] [--max-ranked-outside-top-5-runs N] [--max-ranked-outside-top-10-runs N] [--max-ranked-outside-top-20-runs N] [--max-improvement-target-runs N] [--max-improvement-target-kind-runs KIND=N] [--max-active-stage-ms N] [--max-parser-worker-profiles N] [--min-measured-problem-classes N] [--min-measured-architecture-classes N] [--require-parser-worker none|java|dotnet|javascript|typescript|all] [--allow-missing] [--allow-duplicate-runs] [--allow-unverified-scores] [--out DIR] [--json]"
    "  bench agent-compare <benchmark.edn> --baseline-report before.json --candidate-report after.json [--regression-tolerance N] [--out DIR] [--json]"
    "  bench claim-pack <benchmark.edn> --shell-report shell/agent-report.json --ygg-report ygg/agent-report.json [--min-shared-cases N] [--out DIR] [--json]"
    "  bench repos check [--manifest PATH] [--suite PATH] [--repo ID] [--json]"
    "  bench efficiency <shell-agent-report.json> <ygg-agent-report.json> [--out report.json] [--markdown-out REPORT.md] [--json] [--min-shared-cases N]"
    "  embed setup [--venv PATH] [--python PYTHON] [--json]"
    "  embed [--provider local|openrouter|openai] [--model MODEL] [--batch-size N] [--limit N]"]))

(defn dispatch
  [command args]
  (case command
    ("help" "--help" "-h")
    (println (usage))

    "init"
    (init! args)

    "current"
    (current! args)

    "use"
    (use-project! args)

    "projects"
    (projects! args)

    "sync"
    (sync-dispatch! args)

    "maintenance"
    (maintenance! args)

    "status"
    (print-project-status! (first (positional-args args)) args)

    "audit-scope"
    (let [input-config-path (first (positional-args args))
          {:keys [project config-path]} (if input-config-path
                                          {:project (project/read-project input-config-path)
                                           :config-path input-config-path}
                                          (registry/resolve-project
                                           {:project-id (option-value args "--project")
                                            :cwd (System/getProperty "user.dir")}))
          opts {:config-path config-path
                :repo-id (option-value args "--repo")
                :read-context (temporal-options args)}]
      (store/with-node (store/storage-path (:id project))
        (fn [xtdb]
          (let [report (audit-scope/report xtdb project opts)]
            (if (json-output? args)
              (print-json report)
              (print-audit-scope-report report))))))

    "affected"
    (affected! args)

    "query"
    (query! args)

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

    "corrections"
    (let [action (keyword (first args))
          correction-args (vec (rest args))]
      (case action
        :init
        (with-corrections-project
          correction-args
          (fn [xtdb project]
            (print-json (corrections-api/init! xtdb (:id project)))))

        :status
        (with-corrections-project
          correction-args
          (fn [xtdb project]
            (print-json (corrections-api/status xtdb (:id project)))))

        :review
        (with-corrections-project
          correction-args
          (fn [xtdb project]
            (print-json (corrections-api/review xtdb
                                                project
                                                {:limit (parse-limit correction-args)}))))

        :explain
        (let [value (first (positional-args correction-args))]
          (when-not value
            (throw (ex-info "Missing system id/label." {:usage (usage)})))
          (with-corrections-project
            correction-args
            (fn [xtdb project]
              (print-json (or (corrections-api/explain xtdb (:id project) value)
                              {:schema corrections-api/status-schema
                               :status "not-found"
                               :project-id (:id project)
                               :target value})))))

        :accept
        (let [[kind target] (positional-args correction-args)]
          (case (keyword kind)
            :system
            (do
              (when-not target
                (throw (ex-info "Missing system target." {:usage (usage)})))
              (with-corrections-project
                correction-args
                (fn [xtdb project]
                  (print-json
                   (corrections-api/accept-system!
                    xtdb
                    (:id project)
                    target
                    {:kind (option-value correction-args "--kind")
                     :label (option-value correction-args "--label")
                     :include (some-> (option-value correction-args "--include")
                                      corrections-api/parse-include)
                     :reason (option-value correction-args "--reason")})))))

            (throw (ex-info "Unknown corrections accept kind."
                            {:kind kind
                             :usage (usage)}))))

        :set-kind
        (let [[value kind] (positional-args correction-args)]
          (when-not (and value kind)
            (throw (ex-info "Missing system id/label or kind." {:usage (usage)})))
          (with-corrections-project
            correction-args
            (fn [xtdb project]
              (print-json
               (corrections-api/set-kind! xtdb
                                          (:id project)
                                          value
                                          kind
                                          (option-value correction-args "--reason"))))))

        :include
        (let [[value include] (positional-args correction-args)]
          (when-not (and value include)
            (throw (ex-info "Missing system id/label or repo:path include."
                            {:usage (usage)})))
          (with-corrections-project
            correction-args
            (fn [xtdb project]
              (print-json
               (corrections-api/include! xtdb
                                         (:id project)
                                         value
                                         (corrections-api/parse-include include)
                                         (option-value correction-args "--reason"))))))

        :reject
        (let [[kind value] (positional-args correction-args)
              reason (option-value correction-args "--reason")]
          (when-not (and kind value)
            (throw (ex-info "Missing reject kind or reject value."
                            {:usage (usage)})))
          (with-corrections-project
            correction-args
            (fn [xtdb project]
              (print-json
               (corrections-api/reject! xtdb (:id project) kind value reason)))))

        :package
        (let [[subcommand import-prefix package-target] (positional-args correction-args)]
          (when-not (= "import" subcommand)
            (throw (ex-info "Unknown corrections package command."
                            {:command subcommand
                             :usage (usage)})))
          (when-not (and import-prefix package-target)
            (throw (ex-info "Missing import prefix or ecosystem:package target."
                            {:usage (usage)})))
          (with-corrections-project
            correction-args
            (fn [xtdb project]
              (print-json
               (corrections-api/package-import!
                xtdb
                (:id project)
                import-prefix
                package-target
                {:repo (option-value correction-args "--repo")
                 :reason (option-value correction-args "--reason")})))))

        :docs
        (let [[subcommand target source-value] (positional-args correction-args)]
          (when-not (= "attach" subcommand)
            (throw (ex-info "Unknown corrections docs command."
                            {:command subcommand
                             :usage (usage)})))
          (when-not (and target source-value)
            (throw (ex-info "Missing docs target or repo:path source."
                            {:usage (usage)})))
          (with-corrections-project
            correction-args
            (fn [xtdb project]
              (print-json
               (corrections-api/docs-attach!
                xtdb
                (:id project)
                target
                (corrections-api/parse-source source-value)
                {:role (option-value correction-args "--role")
                 :heading (option-value correction-args "--heading")
                 :start-line (parse-optional-long correction-args "--start-line")
                 :end-line (parse-optional-long correction-args "--end-line")
                 :reason (option-value correction-args "--reason")})))))

        :edge
        (let [[subcommand source target relation] (positional-args correction-args)]
          (when-not (= "add" subcommand)
            (throw (ex-info "Unknown corrections edge command."
                            {:command subcommand
                             :usage (usage)})))
          (when-not (and source target relation)
            (throw (ex-info "Missing source, target, or relation." {:usage (usage)})))
          (with-corrections-project
            correction-args
            (fn [xtdb project]
              (print-json
               (corrections-api/edge-add!
                xtdb
                (:id project)
                (cond-> {:source source
                         :target target
                         :relation relation
                         :reason (option-value correction-args "--reason")}
                  (option-value correction-args "--visibility")
                  (assoc :visibility (option-value correction-args "--visibility"))
                  (option-value correction-args "--importance")
                  (assoc :importance (option-value correction-args "--importance"))
                  (option-value correction-args "--confidence")
                  (assoc :confidence (Double/parseDouble
                                      (option-value correction-args "--confidence")))))))))

        (throw (ex-info "Unknown corrections command."
                        {:command action
                         :usage (usage)}))))

    "memory"
    (memory! args)

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
        (throw (ex-info "Doc attachment corrections are handled by ygg corrections."
                        {:command "docs attach"
                         :replacement "ygg corrections docs attach"
                         :usage (usage)}))

        :for
        (let [target (first positional)
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
                                             :snippet-chars snippet-chars})))))

        :audit
        (store/with-node (store/storage-path)
          (fn [xtdb]
            (print-json (context/docs-audit xtdb
                                            {:project-id project-id}))))

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
          {:keys [provider] :as embedding-opts} (embedding-options args)
          embedding-client (query-embedding-client retriever embedding-opts)
          {:keys [project-id repo-id]} (project-scope args)
          temporal (temporal-options args)]
      (when (and (= :auto retriever) (nil? embedding-client))
        (binding [*out* *err*]
          (println (str (missing-key-message provider) " Using lexical retrieval."))))
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

    "embed"
    (if (= "setup" (first (positional-args args)))
      (let [result (local-embedding/setup-venv! {:venv-path (option-value args "--venv")
                                                 :python (option-value args "--python")})]
        (if (json-output? args)
          (print-json result)
          (print-local-embedding-setup-summary result)))
      (let [{:keys [provider model] :as embedding-opts} (embedding-options args)
            batch-size (parse-long-option args "--batch-size" embedding/default-batch-size)
            limit (parse-limit args)
            {:keys [project-id repo-id]} (project-scope args)]
        (when (and (not= :local provider)
                   (not (provider-api-key provider)))
          (throw (ex-info (missing-key-message provider)
                          {:provider provider})))
        (store/with-node (store/storage-path)
          (fn [xtdb]
            (print-embed-summary
             (embedding/embed-search-docs! xtdb
                                           (provider-client provider
                                                            model
                                                            embedding-opts)
                                           {:batch-size batch-size
                                            :limit limit
                                            :project-id project-id
                                            :repo-id repo-id}))))))

    "packages"
    (let [scope (project-scope args)
          opts (cond-> {:limit (parse-limit args)}
                 (option-value args "--ecosystem") (assoc :ecosystem
                                                          (option-value args "--ecosystem"))
                 (option-value args "--package") (assoc :package
                                                        (option-value args "--package"))
                 (some #{"--with-conflicts"} args) (assoc :with-conflicts? true)
                 (some #{"--without-import-evidence"} args)
                 (assoc :without-import-evidence? true))]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (let [project-id (:project-id scope)
                opts (cond-> opts
                       project-id (assoc :correction-overlay
                                         (corrections/overlay xtdb project-id)))
                report (dependency/package-report xtdb scope opts)]
            (if (json-output? args)
              (print-json report)
              (print-package-report report))))))

    "report"
    (report! args)

    "mcp"
    (mcp/run-stdio! (mcp/server-context args))

    (throw (ex-info "Unknown command." {:command command
                                        :usage (usage)}))))

(defn direct-main-response
  [_args]
  (daemon-contract/direct-entrypoint-response "ygg.cli" "ygg <command>"))

(defn -main
  [& args]
  (silence-jul!)
  (daemon-contract/exit! (direct-main-response (vec args))))
