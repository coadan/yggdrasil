(ns agraph.cli
  "Command line interface."
  (:require [agraph.affected :as affected]
            [agraph.agent-install :as agent-install]
            [agraph.activity :as activity]
            [agraph.audit-scope :as audit-scope]
            [agraph.cli-bench :as cli-bench]
            [agraph.cli-options :refer [append-option
                                        dry-run?
                                        json-output?
                                        option-value
                                        parse-case-ids
                                        parse-depth
                                        parse-double-option
                                        parse-limit
                                        parse-long-option
                                        parse-optional-double
                                        parse-optional-long
                                        positional-args
                                        project-scope
                                        remove-option
                                        system-path?]]
            [agraph.command :as command]
            [agraph.cli-start :as cli-start]
            [agraph.cli-sync :as cli-sync]
            [agraph.context :as context]
            [agraph.coverage :as coverage]
            [agraph.cursor :as cursor]
            [agraph.dependency :as dependency]
            [agraph.dependency-review :as dependency-review]
            [agraph.embedding :as embedding]
            [agraph.embedding.openai :as openai]
            [agraph.embedding.openrouter :as openrouter]
            [agraph.evidence :as evidence]
            [agraph.graph :as graph]
            [agraph.hash :as hash]
            [agraph.hook :as hook]
            [agraph.index :as index]
            [agraph.init :as init]
            [agraph.infra-review :as infra-review]
            [agraph.llm.openai-compatible :as llm]
            [agraph.map :as graph-map]
            [agraph.metadata :as metadata]
            [agraph.mcp :as mcp]
            [agraph.cli-plugin :as cli-plugin]
            [agraph.project :as project]
            [agraph.queue :as queue]
            [agraph.query :as query]
            [agraph.report :as report]
            [agraph.system :as system]
            [agraph.system.decision-classifier :as decision-classifier]
            [agraph.watch :as watch]
            [agraph.xtdb :as store]
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
    (graph-map/file-exists? graph-map/default-path) graph-map/default-path
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
  [xtdb args project-id map-path]
  (when-let [{:keys [project config-path]} (matching-context-project args project-id)]
    (let [overlay (when (and map-path (graph-map/file-exists? map-path))
                    (graph-map/read-map map-path))
          summary (evidence/summarize xtdb
                                      project
                                      {:map-overlay overlay
                                       :config-path config-path
                                       :map-path map-path})]
      (evidence/packet-freshness summary))))

(defn- context-packet-options
  [xtdb args {:keys [project-id repo-id retriever embedding-client read-context]}]
  (let [map-path (default-map-path args)
        freshness (context-packet-freshness xtdb args project-id map-path)]
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
      (assoc :freshness freshness))))

(defn- required-map-path
  [args]
  (or (default-map-path args)
      (throw (ex-info "Missing --map and agraph.map.json was not found."
                      {:usage (usage)}))))

(defn- apply-work-result!
  [root id map-path]
  (let [found (or (queue/find-item root id)
                  (throw (ex-info "Queue item not found." {:id id})))
        payload-schema (get-in found [:item :payload :schema])]
    (case payload-schema
      "agraph.infra.review-packet/v1"
      (infra-review/apply-work-result! root id map-path)

      "agraph.dependency.review-packet/v1"
      (dependency-review/apply-work-result! root id map-path)

      "agraph.maintenance.decision-packet/v1"
      (decision-classifier/apply-work-result! root id map-path)

      {:schema "agraph.sync.work.apply/v1"
       :status "failed"
       :errors [{:path [:payload :schema]
                 :error "No apply handler for work item payload schema."
                 :value payload-schema}]
       :item (queue/item-summary found)})))

(defn- print-query-result
  [result]
  (println (format "%.2f  %s  %s"
                   (:score result)
                   (name (:result-kind result))
                   (or (:label result) (:path result))))
  (println "      " (:path result) (when-let [line (:source-line result)] (str "L" line)))
  (when-let [{:keys [semantic lexical graph exact]} (:score-components result)]
    (println "       "
             (format "semantic %.2f lexical %.2f graph %.2f exact %.2f"
                     (double semantic)
                     (double lexical)
                     (double graph)
                     (double exact))))
  (when-let [reason (:reason result)]
    (println "       " reason)))

(defn- print-ask-answerability-warning
  [packet]
  (let [answerability (:answerability packet)
        missing (map name (:missing answerability))
        weak (map name (:weak answerability))
        unsupported (map name (:unsupported answerability))
        warning-limit 3
        next-limit 4
        warnings (take warning-limit (:warnings answerability))
        next-steps (take next-limit (:next answerability))
        extra-warnings (max 0 (- (count (:warnings answerability)) warning-limit))
        extra-next (max 0 (- (count (:next answerability)) next-limit))]
    (binding [*out* *err*]
      (println "No query results.")
      (println "Answerability" (name (:status answerability)))
      (when (seq missing)
        (println "Missing evidence:" (str/join ", " missing)))
      (when (seq weak)
        (println "Weak evidence:" (str/join ", " weak)))
      (when (seq unsupported)
        (println "Unsupported evidence:" (str/join ", " unsupported)))
      (doseq [warning warnings]
        (println "Warning:" warning))
      (when (pos? extra-warnings)
        (println "Warning:" extra-warnings "more warnings in --json output."))
      (when (seq next-steps)
        (println "Next:" (str/join " | " next-steps))
        (when (pos? extra-next)
          (println "Next:" extra-next "more commands in --json output."))))))

(defn- print-ask-no-results
  [xtdb query-text {:keys [project-id repo-id retriever embedding-client temporal args]}]
  (if (str/blank? (str project-id))
    (binding [*out* *err*]
      (println "No query results.")
      (println "Use --project to include answerability details."))
    (print-ask-answerability-warning
     (context/context-packet xtdb
                             query-text
                             (context-packet-options xtdb
                                                     args
                                                     {:project-id project-id
                                                      :repo-id repo-id
                                                      :retriever retriever
                                                      :embedding-client embedding-client
                                                      :read-context temporal})))))

(defn- print-embed-summary
  [{:keys [provider model search-docs pending embedded skipped]}]
  (println "# Embeddings")
  (println "- provider" (name provider))
  (println "- model" model)
  (println "- search-docs" search-docs)
  (println "- pending" pending)
  (println "- embedded" embedded)
  (println "- skipped" skipped))

(defn- project-inspect-result
  [xtdb project {:keys [config-path map-path]}]
  (let [overlay (when (and map-path (graph-map/file-exists? map-path))
                  (graph-map/read-map map-path))
        evidence-summary (evidence/summarize xtdb
                                             project
                                             {:map-overlay overlay
                                              :config-path (or config-path
                                                               (:path project))
                                              :map-path map-path})]
    {:schema "agraph.project.inspect/v1"
     :project {:id (:id project)
               :name (:name project)
               :config-path (or config-path (:path project))}
     :repos (mapv #(select-keys % [:id :root :role]) (:repos project))
     :freshness (:freshness evidence-summary)
     :coverage (evidence/status-coverage evidence-summary)
     :nextActions (:nextActions evidence-summary)
     :evidence evidence-summary}))

(defn- family-count-text
  [counts]
  (->> counts
       (sort-by (comp name key))
       (map (fn [[k v]]
              (str (name k) "=" v)))
       (str/join ", ")))

(defn- result-schema-status-text
  [statuses]
  (->> statuses
       (sort-by (comp name key))
       (map (fn [[status n]]
              (str (name status) "=" n)))
       (str/join ", ")))

(defn- print-evidence-family
  [{:keys [family status counts]}]
  (println (str "- "
                (name family)
                " "
                (name status)
                (when (seq counts)
                  (str " " (family-count-text counts))))))

(defn- print-evidence-summary
  [{:keys [available counts freshness next families]}]
  (println)
  (println "## Evidence Surface")
  (println "- available"
           (if (seq available)
             (str/join ", " (map name available))
             "none"))
  (when (seq families)
    (println)
    (println "## Evidence Families")
    (doseq [family families]
      (print-evidence-family family)))
  (println "- files" (:files counts 0))
  (println "- nodes" (:nodes counts 0))
  (println "- edges" (:edges counts 0))
  (println "- docs" (:search-docs counts 0))
  (println "- packages" (:packages counts 0))
  (println "- systems" (+ (:system-nodes counts 0) (:system-edges counts 0)))
  (println "- diagnostics" (:diagnostics counts 0))
  (println "- activity-events" (:activity-events counts 0))
  (println "- validation-events" (:validation-events counts 0))
  (println "- result-schema-status-items" (:result-schema-status-items counts 0))
  (when (seq (:result-schema-statuses counts))
    (println "- result-schema-statuses"
             (result-schema-status-text (:result-schema-statuses counts))))
  (println "- result-schema-mismatch-events" (:result-schema-mismatch-events counts 0))
  (when freshness
    (println)
    (println "## Freshness")
    (println "- status" (name (:status freshness)))
    (when (:basis freshness)
      (println "- basis" (:basis freshness)))
    (when (:projectConfig freshness)
      (println "- project-config" (:projectConfig freshness)))
    (when (:map freshness)
      (println "- map" (:map freshness) "exists" (boolean (:mapExists freshness))))
    (when (contains? freshness :missingQueryIndex)
      (println "- missing-query-index" (boolean (:missingQueryIndex freshness))))
    (println "- indexed" (get-in freshness [:counts :indexed] 0))
    (println "- current" (get-in freshness [:counts :current] 0))
    (println "- changed" (get-in freshness [:counts :changed] 0))
    (println "- missing" (get-in freshness [:counts :missing] 0))
    (println "- unindexed" (get-in freshness [:counts :unindexed] 0)))
  (when (seq next)
    (println)
    (println "## Next")
    (doseq [command next]
      (println "-" command))))

(defn- print-project-inspect
  [{:keys [project repos evidence]}]
  (println "# Project")
  (println "- id" (:id project))
  (println "- name" (:name project))
  (when (:config-path project)
    (println "- config" (:config-path project)))
  (println)
  (println "## Repos")
  (doseq [{:keys [id root role]} repos]
    (println "-" id (clojure.core/name role) root))
  (print-evidence-summary evidence))

(defn- print-project-status!
  ([config-path args]
   (when-not config-path
     (throw (ex-info "Missing project config path." {:usage (usage)})))
   (print-project-status! (project/read-project config-path) config-path args))
  ([project config-path args]
   (let [map-path (default-map-path args)]
     (store/with-node (store/storage-path)
       (fn [xtdb]
         (let [result (project-inspect-result xtdb
                                              project
                                              {:config-path config-path
                                               :map-path map-path})]
           (if (json-output? args)
             (print-json result)
             (print-project-inspect result))))))))

(defn- timing-total-text
  [stats]
  (when-let [total-ms (get-in stats [:timings-ms :total-ms])]
    (str ", " total-ms "ms total")))

(defn- print-project-index-summary
  [{:keys [project-id status repos]}]
  (println "# Project Index")
  (println "- project" project-id)
  (println "- status" (name status))
  (doseq [{:keys [repo-id status stats index-profile]} repos]
    (println "-"
             repo-id
             (name status)
             (str "profile=" (name (or index-profile index/default-index-profile)))
             (:files-scanned stats)
             "scanned,"
             (:files-indexed stats)
             "indexed,"
             (:files-skipped stats)
             (str "skipped" (or (timing-total-text stats) "")))))

(defn- print-project-add-repo-summary
  [{:keys [project repo index-summary system-summary next]}]
  (println "# Project Repo Added")
  (println "- project" (:id project))
  (println "- repo" (:id repo))
  (println "- root" (:root repo))
  (println "- role" (name (:role repo)))
  (when index-summary
    (println "- indexed" (:files-indexed (:stats index-summary)) "files"))
  (when system-summary
    (println "- inferred" (:system-nodes system-summary) "system nodes"))
  (when (seq next)
    (println)
    (println "## Next")
    (doseq [command next]
      (println "-" command))))

(defn- print-count-rows
  [title label-key rows]
  (when (seq rows)
    (println)
    (println title)
    (doseq [row rows]
      (let [samples (->> (:samples row)
                         (take 3)
                         (map (fn [{:keys [repo-id path]}]
                                (if repo-id
                                  (str repo-id ":" path)
                                  path)))
                         (remove str/blank?))]
        (if (seq samples)
          (println "-" (get row label-key) (:count row)
                   "samples" (str/join "," samples))
          (println "-" (get row label-key) (:count row)))))))

(defn- print-source-coverage
  [{:keys [project-id totals files-by-kind skipped-by-extension skipped-by-reason
           extractors diagnostics indexedConnectivity repos]}]
  (println "# Source Coverage")
  (println "- project" project-id)
  (println "- repos" (count repos))
  (println "- files" (:files totals))
  (println "- supported" (:supported totals))
  (println "- skipped" (:skipped totals))
  (println "- diagnostics" (:total diagnostics))
  (when indexedConnectivity
    (println "- indexed-connectivity"
             (str "indexed=" (:indexedFiles indexedConnectivity 0))
             (str "nodes=" (:nodes indexedConnectivity 0))
             (str "edges=" (:edges indexedConnectivity 0))
             (str "connected=" (:connectedFiles indexedConnectivity 0))
             (str "cross-file=" (:crossFileConnectedFiles indexedConnectivity 0))
             (str "isolated=" (:isolatedFiles indexedConnectivity 0))))
  (print-count-rows "## Files By Kind" :kind files-by-kind)
  (print-count-rows "## Skipped By Extension" :ext skipped-by-extension)
  (print-count-rows "## Skipped By Reason" :reason skipped-by-reason)
  (when (seq (:byKind indexedConnectivity))
    (println)
    (println "## Connectivity By Kind")
    (doseq [{:keys [kind indexedFiles connectedFiles crossFileConnectedFiles
                    isolatedFiles]} (:byKind indexedConnectivity)]
      (println "-" kind
               (str "indexed=" (or indexedFiles 0))
               (str "connected=" (or connectedFiles 0))
               (str "cross-file=" (or crossFileConnectedFiles 0))
               (str "isolated=" (or isolatedFiles 0)))))
  (when (seq extractors)
    (println)
    (println "## Extractors")
    (doseq [{:keys [kind extractor-version files]} extractors]
      (println "-" kind extractor-version files "files")))
  (when (seq (:by-extractor diagnostics))
    (println)
    (println "## Diagnostics By Extractor")
    (doseq [{:keys [kind extractor-version stage count]} (:by-extractor diagnostics)]
      (println "-" kind extractor-version stage count))))

(defn- print-audit-scope-report
  [{:keys [project-id repo-id coverage scopes nextActions]}]
  (println "# Audit Scopes")
  (println "- project" project-id)
  (when repo-id
    (println "- repo" repo-id))
  (println "- files" (:files coverage 0))
  (println "- supported" (:supportedFiles coverage 0))
  (println "- skipped" (:skippedFiles coverage 0))
  (println "- diagnostics" (:diagnostics coverage 0))
  (doseq [{:keys [kind supportedFiles skippedFiles facts diagnostics overlayCount
                  topEvidenceTypes samples]} scopes]
    (println)
    (println "##" kind)
    (println "- supported-files" supportedFiles)
    (println "- skipped-files" skippedFiles)
    (println "- facts" facts)
    (println "- diagnostics" diagnostics)
    (println "- overlays" overlayCount)
    (when (seq topEvidenceTypes)
      (println "- evidence"
               (str/join ", "
                         (map (fn [{:keys [kind count]}]
                                (str kind ":" count))
                              topEvidenceTypes))))
    (when (seq samples)
      (println "- samples"
               (str/join ", "
                         (keep (fn [{:keys [repo-id path id target reason]}]
                                 (or (when path
                                       (if repo-id
                                         (str repo-id ":" path)
                                         path))
                                     id
                                     target
                                     reason))
                               samples)))))
  (when (seq nextActions)
    (println)
    (println "## Next")
    (doseq [{:keys [command]} nextActions]
      (println "-" command))))

(defn- print-affected-summary
  [{:keys [project-id basis inputs changedFiles changedNodes affectedFiles
           unsupportedIncidentEdges warnings nextActions]}]
  (println "# Affected Files")
  (println "- project" project-id)
  (println "- mode" (:mode basis))
  (when-let [repo-id (:repo-id basis)]
    (println "- repo" repo-id))
  (when-let [since (:since basis)]
    (println "- since" since))
  (println "- inputs" (count inputs))
  (println "- changed-files" (count changedFiles))
  (println "- changed-nodes" (count changedNodes))
  (println "- affected-files" (count affectedFiles))
  (println "- unsupported-incident-edges" (:count unsupportedIncidentEdges 0))
  (when (seq (:byRelation unsupportedIncidentEdges))
    (println "- unsupported-incident-relations"
             (str/join ", "
                       (map (fn [{:keys [relation count]}]
                              (str relation ":" count))
                            (:byRelation unsupportedIncidentEdges)))))
  (when (:testsOnly basis)
    (println "- tests-only true"))
  (when (seq affectedFiles)
    (println)
    (println "## Files")
    (doseq [{:keys [repo-id path edgeCount directions]} affectedFiles]
      (println "-" (if repo-id (str repo-id ":" path) path)
               "edges" edgeCount
               "directions" (str/join "," directions))))
  (when (seq warnings)
    (println)
    (println "## Boundaries")
    (doseq [{:keys [kind message]} warnings]
      (println "-" kind message)))
  (when (seq nextActions)
    (println)
    (println "## Next")
    (doseq [{:keys [command]} nextActions]
      (println "-" command))))

(defn- affected-files-option
  [args config-path]
  (let [from-option (some-> (option-value args "--files") affected/split-files)
        from-positionals (->> (positional-args args)
                              rest
                              (remove #(= % config-path)))]
    (vec (concat from-option from-positionals))))

(defn- affected!
  [args]
  (let [config-path (first (positional-args args))]
    (when-not config-path
      (throw (ex-info "Missing project config path." {:usage (usage)})))
    (let [project (project/read-project config-path)
          files (affected-files-option args config-path)
          opts (cond-> {:repo-id (option-value args "--repo")
                        :since (option-value args "--since")
                        :config-path config-path
                        :tests-only? (boolean (some #{"--tests"} args))
                        :read-context (temporal-options args)}
                 (seq files) (assoc :files files))]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (let [result (affected/analyze xtdb project opts)]
            (if (json-output? args)
              (print-json result)
              (print-affected-summary result))))))))

(defn- print-sync-summary
  [{:keys [project-id repo-id index-summary system-summary check-report enqueued]}]
  (println "# Sync")
  (println "- project" project-id)
  (when repo-id
    (println "- repo" repo-id))
  (when index-summary
    (println "- indexed-repos" (count (:repos index-summary)))
    (doseq [{:keys [repo-id status stats index-profile]} (:repos index-summary)]
      (println "-"
               repo-id
               (name status)
               (str "profile=" (name (or index-profile index/default-index-profile)))
               (:files-scanned stats)
               "scanned,"
               (:files-indexed stats)
               "indexed,"
               (:files-skipped stats)
               (str "skipped" (or (timing-total-text stats) "")))))
  (when system-summary
    (println "- system-evidence" (:system-evidence system-summary))
    (println "- system-nodes" (:system-nodes system-summary))
    (println "- system-edges" (:system-edges system-summary)))
  (when check-report
    (println "- maintenance-decisions" (get-in check-report [:counts :maintenance-decisions] 0))
    (println "- infra-review-items" (get-in check-report [:counts :infra-review-items] 0)))
  (when enqueued
    (println "- enqueued" (count enqueued))))

(defn- print-system-summary
  [{:keys [project-id status system-evidence system-nodes system-edges search-docs]}]
  (println "# System Graph")
  (println "- project" project-id)
  (println "- status" (name status))
  (println "- evidence" system-evidence)
  (println "- nodes" system-nodes)
  (println "- edges" system-edges)
  (println "- search-docs" search-docs))

(defn- print-map-summary
  [path {:keys [schema project systems reject edges packageImports]}]
  (println "# Graph Map")
  (println "- schema" schema)
  (println "- project" project)
  (println "- systems" (count systems))
  (println "- rejects" (count reject))
  (println "- edges" (count edges))
  (println "- package-imports" (count packageImports))
  (println "- output" path))

(defn- print-map-system
  [system]
  (if-not system
    (println "System not found.")
    (do
      (println "# Map System")
      (doseq [k [:id :label :kind :status :provenance :repo :pathPrefix :reason]]
        (when-let [value (get system k)]
          (println "-" (name k) value)))
      (when (seq (:aliases system))
        (println "- aliases" (str/join ", " (:aliases system))))
      (when (seq (:includes system))
        (println)
        (println "## Includes")
        (doseq [include (:includes system)]
          (println "-" (:repo include) (:path include)))))))

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
      (System/getenv "AGRAPH_AGENT_ID")
      (System/getProperty "user.name")
      "agent"))

(defn- queue-lease-ms
  [args]
  (* 60 1000 (parse-long-option args "--lease-minutes" 30)))

(defn- queue-enqueued-result
  [found]
  {:schema "agraph.queue.enqueued/v1"
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

(defn- active-project-systems
  [xtdb project-id]
  (->> (store/rows-by-field xtdb (:system-nodes store/tables) :project-id project-id)
       (filter :active?)
       vec))

(defn- active-project-system-edges
  [xtdb project-id]
  (->> (store/rows-by-field xtdb (:system-edges store/tables) :project-id project-id)
       (filter :active?)
       vec))

(defn- parse-include
  [value]
  (let [idx (.indexOf value ":")]
    (when (neg? idx)
      (throw (ex-info "Include must use repo:path." {:value value})))
    {:repo (subs value 0 idx)
     :path (subs value (inc idx))}))

(defn- parse-source
  [value]
  (parse-include value))

(defn- parse-package-target
  [value]
  (let [idx (.indexOf (str value) ":")]
    (when (neg? idx)
      (throw (ex-info "Package target must use ecosystem:package."
                      {:value value})))
    {:ecosystem (subs value 0 idx)
     :package (subs value (inc idx))}))

(defn- reject-match
  [kind value]
  (case kind
    "external-api" {:kind "external-api" :host value}
    {:kind kind :label value}))

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





























(defn- candidate-system?
  [system]
  (contains? #{:candidate-system :repo-boundary} (:kind system)))

(defn- print-candidate-systems
  [systems limit]
  (println "# System Candidates")
  (println "- count" (count systems))
  (doseq [system (take limit systems)]
    (println (str "- "
                  (:label system)
                  " [" (name (:kind system)) "]"
                  " repo " (:repo-id system)
                  " types " (str/join "," (map name (:candidate-types system)))
                  " files " (get-in system [:metrics :file-count] 0)
                  " nodes " (get-in system [:metrics :node-count] 0)
                  (when-let [path (:path-prefix system)]
                    (str " path " path))))))

(defn- default-provider
  []
  (if (openrouter/api-key)
    :openrouter
    :openai))

(defn- provider-option
  [args]
  (keyword (or (option-value args "--provider")
               (name (default-provider)))))

(defn- default-model
  [provider]
  (case provider
    :openrouter openrouter/default-model
    :openai openai/default-model
    (throw (ex-info "Unsupported embedding provider."
                    {:provider provider
                     :supported [:openrouter :openai]}))))

(defn- provider-api-key
  [provider]
  (case provider
    :openrouter (openrouter/api-key)
    :openai (openai/api-key)
    nil))

(defn- provider-client
  [provider model]
  (case provider
    :openrouter (openrouter/client {:model model})
    :openai (openai/client {:model model})
    (throw (ex-info "Unsupported embedding provider."
                    {:provider provider
                     :supported [:openrouter :openai]}))))

(defn- missing-key-message
  [provider]
  (case provider
    :openrouter "Missing OpenRouter API key. Set AGRAPH_OPENROUTER_API_KEY or OPENROUTER_API_KEY."
    :openai "Missing OpenAI API key. Set AGRAPH_OPENAI_API_KEY or OPENAI_API_KEY."
    "Missing embedding provider API key."))

(defn- llm-provider-option
  [args]
  (keyword (or (option-value args "--provider") "openrouter")))

(defn- llm-model
  [provider args]
  (or (option-value args "--model")
      (llm/default-model provider)))

(defn- llm-client
  [provider model args]
  (llm/client {:provider provider
               :model model
               :base-url (option-value args "--base-url")}))

(defn- print-edge-target
  [edge]
  (println "-" (name (:relation edge)) "->" (or (get-in edge [:target :label])
                                                (query/display-id (:target-id edge)))))

(defn- print-edge-source
  [edge]
  (println "-" (name (:relation edge)) "<-" (or (get-in edge [:source :label])
                                                (query/display-id (:source-id edge)))))

(defn- print-deps
  [{:keys [node incoming outgoing definitions defined-by]}]
  (if-not node
    (println "Node not found.")
    (do
      (println "# Dependencies:" (:label node))
      (println)
      (println "Outgoing:")
      (if (seq outgoing)
        (doseq [edge outgoing]
          (print-edge-target edge))
        (println "- none"))
      (println)
      (println "Incoming:")
      (if (seq incoming)
        (doseq [edge incoming]
          (print-edge-source edge))
        (println "- none"))
      (when (seq definitions)
        (println)
        (println "Definitions:" (count definitions))
        (doseq [edge (take 20 definitions)]
          (print-edge-target edge))
        (when (< 20 (count definitions))
          (println "-" (- (count definitions) 20) "more definitions")))
      (when (seq defined-by)
        (println)
        (println "Defined by:")
        (doseq [edge defined-by]
          (print-edge-source edge))))))

(defn- print-package-source
  [source]
  (println (str "  - " (:path source)
                (when-let [line (:line source)]
                  (str ":" line))
                (when-let [version-range (:version-range source)]
                  (str " " version-range))
                (when-let [scope (:dependency-scope source)]
                  (str " [" (name scope) "]")))))

(defn- print-package-entry
  [entry]
  (println "-" (:label entry)
           (str "(declared " (count (:declared-by entry))
                ", versions " (count (:resolved-versions entry))
                ", imports " (count (:imported-by entry)) ")")))

(defn- print-package-report
  [{:keys [project-id repo-id counts ecosystems packages
           declared-without-import-evidence unresolved-imports version-conflicts]}]
  (println "# Packages")
  (when project-id
    (println "- project" project-id))
  (when repo-id
    (println "- repo" repo-id))
  (println "- packages" (:packages counts))
  (println "- versions" (:versions counts))
  (println "- import evidence" (:imports-package counts))
  (println "- unresolved imports" (:unresolved-imports counts))
  (println "- declared without import evidence"
           (:declared-without-import-evidence counts))
  (println "- version conflicts" (:version-conflicts counts))
  (when (seq ecosystems)
    (println)
    (println "Ecosystems:")
    (doseq [{:keys [ecosystem packages versions imports]} ecosystems]
      (println (str "- " (name ecosystem)
                    ": packages " packages
                    ", versions " versions
                    ", imports " imports))))
  (when (seq version-conflicts)
    (println)
    (println "Version conflicts:")
    (doseq [{:keys [label versions]} version-conflicts]
      (println "-" label (str/join ", " versions))))
  (when (seq declared-without-import-evidence)
    (println)
    (println "Declared without import evidence:")
    (doseq [entry declared-without-import-evidence]
      (print-package-entry entry)
      (doseq [source (take 3 (:declared-by entry))]
        (print-package-source source))))
  (when (seq unresolved-imports)
    (println)
    (println "Unresolved imports:")
    (doseq [{:keys [path line import kind]} unresolved-imports]
      (println (str "- " path
                    (when line (str ":" line))
                    " " import
                    (when kind (str " [" (name kind) "]"))))))
  (when (seq packages)
    (println)
    (println "Packages:")
    (doseq [entry packages]
      (print-package-entry entry))))

(defn- print-path
  [nodes]
  (if (seq nodes)
    (doseq [[idx node] (map-indexed vector nodes)]
      (println (str idx ".") (:label node)))
    (println "No path found.")))

(defn- default-graph-out
  [mode value]
  (str ".dev/reports/"
       (name mode)
       "-"
       (hash/short-hash [(name mode) value (System/currentTimeMillis)])
       ".html"))

(defn- graph-output
  [args mode value]
  (or (option-value args "--out")
      (default-graph-out mode value)))

(defn- default-graph-json-out
  [mode value]
  (str ".dev/reports/"
       (name mode)
       "-"
       (hash/short-hash [(name mode) value (System/currentTimeMillis)])
       ".json"))

(defn- graph-json-output
  [args mode value]
  (or (option-value args "--out")
      (default-graph-json-out mode value)))

(defn- query-embedding-client
  [retriever provider model]
  (cond
    (= :lexical retriever) nil
    (provider-api-key provider) (provider-client provider model)
    (= :auto retriever) nil
    :else (throw (ex-info (missing-key-message provider)
                          {:retriever retriever
                           :provider provider}))))

(defn- print-graph-output
  [path data]
  (println "# Graph")
  (println "- title" (:title data))
  (println "- schema" (:schema data))
  (println "- nodes" (count (:nodes data)))
  (println "- edges" (count (:edges data)))
  (when (contains? data :clusters)
    (println "- clusters" (count (:clusters data))))
  (println "- output" path))

(defn- print-canonical-output
  [path data]
  (println "# Graph Export")
  (println "- title" (:title data))
  (println "- schema" (:schema data))
  (println "- nodes" (count (:nodes data)))
  (println "- edges" (count (:edges data)))
  (when (contains? data :clusters)
    (println "- clusters" (count (:clusters data))))
  (println "- output" path))

(defn- graph-output-value
  [mode graph-args]
  (case mode
    :overview "overview"
    :deps (first (positional-args graph-args))
    :query (str/join " " (positional-args graph-args))
    :systems (option-value graph-args "--project")
    :clusters (option-value graph-args "--project")
    :cluster (str (option-value graph-args "--project") ":" (first (positional-args graph-args)))
    "graph"))

(defn- graph-data-for-mode
  [xtdb mode graph-args limit depth]
  (case mode
    :overview
    (let [{:keys [project-id repo-id]} (project-scope graph-args)
          temporal (temporal-options graph-args)]
      (graph/overview-graph xtdb {:limit limit
                                  :project-id project-id
                                  :repo-id repo-id
                                  :read-context temporal
                                  :view-id (option-value graph-args "--view")}))

    :deps
    (let [value (first (positional-args graph-args))]
      (when-not value
        (throw (ex-info "Missing deps graph node query." {:usage (usage)})))
      (let [{:keys [project-id repo-id]} (project-scope graph-args)
            temporal (temporal-options graph-args)]
        (graph/deps-graph xtdb value {:depth depth
                                      :limit limit
                                      :project-id project-id
                                      :repo-id repo-id
                                      :read-context temporal
                                      :view-id (option-value graph-args "--view")})))

    :query
    (let [query-text (str/join " " (positional-args graph-args))
          retriever (keyword (or (option-value graph-args "--retriever") "auto"))
          provider (provider-option graph-args)
          model (or (option-value graph-args "--model") (default-model provider))
          embedding-client (query-embedding-client retriever provider model)
          {:keys [project-id repo-id]} (project-scope graph-args)
          temporal (temporal-options graph-args)]
      (when (str/blank? query-text)
        (throw (ex-info "Missing graph query text." {:usage (usage)})))
      (graph/query-graph xtdb query-text {:depth depth
                                          :limit limit
                                          :retriever retriever
                                          :embedding-client embedding-client
                                          :project-id project-id
                                          :repo-id repo-id
                                          :read-context temporal
                                          :view-id (option-value graph-args "--view")}))

    :systems
    (let [project-id (option-value graph-args "--project")]
      (when (str/blank? (str project-id))
        (throw (ex-info "Missing --project for systems graph." {:usage (usage)})))
      (let [min-confidence (parse-double-option graph-args "--min-confidence" 0.55)]
        (graph/system-graph xtdb project-id {:limit limit
                                             :min-confidence min-confidence
                                             :detail (keyword (or (option-value graph-args "--detail")
                                                                  "primary"))
                                             :map-path (default-map-path graph-args)
                                             :read-context (temporal-options graph-args)
                                             :view-id (option-value graph-args "--view")})))

    :clusters
    (let [project-id (option-value graph-args "--project")]
      (when (str/blank? (str project-id))
        (throw (ex-info "Missing --project for clusters graph." {:usage (usage)})))
      (graph/system-graph xtdb project-id {:limit limit
                                           :detail (keyword (or (option-value graph-args "--detail")
                                                                "primary"))
                                           :map-path (default-map-path graph-args)
                                           :read-context (temporal-options graph-args)
                                           :view-id (option-value graph-args "--view")}))

    :cluster
    (let [cluster-id (first (positional-args graph-args))
          project-id (option-value graph-args "--project")]
      (when-not cluster-id
        (throw (ex-info "Missing cluster id." {:usage (usage)})))
      (when (str/blank? (str project-id))
        (throw (ex-info "Missing --project for cluster graph." {:usage (usage)})))
      (graph/cluster-graph xtdb
                           project-id
                           cluster-id
                           {:limit limit
                            :detail (keyword (or (option-value graph-args "--detail")
                                                 "expanded"))
                            :map-path (default-map-path graph-args)
                            :read-context (temporal-options graph-args)
                            :view-id (option-value graph-args "--view")}))

    (throw (ex-info "Unknown graph mode." {:mode mode
                                           :usage (usage)}))))

(defn- retrieval-options
  [args]
  (let [retriever (keyword (or (option-value args "--retriever") "auto"))
        provider (provider-option args)
        model (or (option-value args "--model") (default-model provider))
        embedding-client (query-embedding-client retriever provider model)]
    (when (and (= :auto retriever) (nil? embedding-client))
      (binding [*out* *err*]
        (println "No embedding provider API key found; using lexical retrieval.")))
    {:retriever retriever
     :provider provider
     :model model
     :embedding-client embedding-client}))

(defn- ask!
  [args]
  (let [query-text (str/join " " (positional-args args))
        {:keys [retriever embedding-client]} (retrieval-options args)
        {:keys [project-id repo-id]} (project-scope args)
        temporal (temporal-options args)]
    (when (str/blank? query-text)
      (throw (ex-info "Missing ask text." {:usage (usage)})))
    (store/with-node (store/storage-path)
      (fn [xtdb]
        (if (json-output? args)
          (print-json
           (context/context-packet xtdb
                                   query-text
                                   (context-packet-options xtdb
                                                           args
                                                           {:project-id project-id
                                                            :repo-id repo-id
                                                            :retriever retriever
                                                            :embedding-client embedding-client
                                                            :read-context temporal})))
          (let [results (query/semantic-query xtdb
                                              query-text
                                              {:limit (or (parse-limit args) 10)
                                               :retriever retriever
                                               :embedding-client embedding-client
                                               :project-id project-id
                                               :repo-id repo-id
                                               :read-context temporal})]
            (if (seq results)
              (doseq [result results]
                (print-query-result result))
              (print-ask-no-results xtdb
                                    query-text
                                    {:project-id project-id
                                     :repo-id repo-id
                                     :retriever retriever
                                     :embedding-client embedding-client
                                     :temporal temporal
                                     :args args}))))))))

(def ^:private cursor-actions
  #{"create" "show" "open" "expand" "docs" "search"})

(defn- cursor-action?
  [args]
  (contains? cursor-actions (first args)))

(defn- view!
  [args]
  (let [format (keyword (or (option-value args "--format") "html"))
        view-args (remove-option args "--format")]
    (case format
      :html (dispatch "graph" view-args)
      :json (dispatch "graph" (into ["export"] view-args))
      (throw (ex-info "Unknown view format."
                      {:format format
                       :supported [:html :json]
                       :usage (usage)})))))

(defn- report!
  [args]
  (let [config-path (first (positional-args args))]
    (when (str/blank? (str config-path))
      (throw (ex-info "Missing project config." {:usage (usage)})))
    (let [project (project/read-project config-path)
          map-path (default-map-path args)]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (print-json
           (report/bundle! xtdb
                           project
                           {:out (or (option-value args "--out")
                                     report/default-output-dir)
                            :map-path map-path
                            :detail (keyword (or (option-value args "--detail")
                                                 (name report/default-detail)))
                            :force? (boolean (some #{"--force"} args))})))))))






























(defn- print-benchmark-summary
  [result]
  (cli-bench/print-benchmark-summary result))

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

(defn- start-next-actions
  [project-id config-path map-path report-out]
  (cli-start/start-next-actions project-id config-path map-path report-out))

(defn- start-next-commands
  [actions]
  (cli-start/start-next-commands actions))






















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
    "  start <repo-root> [--project ID] [--name NAME] [--out project.edn] [--map agraph.map.json] [--report-out agraph-out] [--force] [--query-index]"
    "  init <repo-root> [--project ID] [--name NAME] [--out project.edn] [--force] [--sync] [--map agraph.map.json]"
    "  init --workbench <root> [--task TASK] [--project ID] [--name NAME] [--out project.edn] [--force]"
    ""
    "Sync and maintenance:"
    "  status <project.edn> [--map PATH] [--json]"
    "  audit-scope <project.edn> [--repo ID] [--map PATH] [--json]"
    "  sync <project.edn> [--repo ID] [--map PATH] [--check] [--enqueue] [--query-index] [--dry-run] [--json]"
    "  sync inspect <project.edn>"
    "  sync coverage <project.edn> [--json]"
    "  sync activity <project.edn> [--queue-dir DIR] [--json]"
    "  sync add-repo <project.edn> <repo-path> [--repo ID] [--role ROLE]"
    "  sync check <project.edn> [--map PATH] [--json] [--enqueue] [--queue-dir DIR]"
    "  sync init|propose <project.edn> [--out agraph.map.json]"
    "  sync explain <target> [--map agraph.map.json]"
    "  sync set-kind <target> <kind> [--map agraph.map.json]"
    "  sync include <target> <repo>:<path> [--map agraph.map.json]"
    "  sync ignore external-api <host> [--map agraph.map.json] [--reason TEXT]"
    "  sync package import <import-prefix> <ecosystem>:<package> [--repo ID] [--map agraph.map.json] [--reason TEXT]"
    "  sync docs candidates <target> [--project ID] [--limit N] [--snippet-chars N]"
    "  sync docs attach <target> <repo>:<path> [--map agraph.map.json] [--role ROLE] [--heading HEADING] [--start-line N] [--end-line N] [--reason TEXT]"
    "  sync docs for <target> [--project ID] [--map PATH] [--snippet-chars N]"
    "  sync docs audit [--project ID] [--map PATH]"
    "  sync meta defs|set|get|unset ..."
    "  sync view list|show ..."
    "  sync work list [--queue-dir DIR] [--status ready|claimed|done|rejected|failed] [--project ID] [--kind KIND] [--limit N]"
    "  sync work pull [--queue-dir DIR] [--project ID] [--kind KIND] [--agent ID] [--lease-minutes N]"
    "  sync work show <work-id> [--queue-dir DIR]"
    "  sync work complete <work-id> --result result.json [--queue-dir DIR]"
    "  sync work apply <work-id> --map agraph.map.json [--queue-dir DIR]"
    "  sync work reject <work-id> --reason TEXT [--queue-dir DIR]"
    "  sync work release <work-id> [--reason TEXT] [--queue-dir DIR]"
    "  sync work heartbeat <work-id> [--queue-dir DIR] [--agent ID] [--lease-minutes N]"
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
    "  report <project.edn> [--map agraph.map.json] [--out agraph-out] [--detail primary|expanded|evidence|raw] [--force]"
    ""
    "Plugins:"
    "  plugin new <dir> [--id ID] [--file-kind KIND] [--path-glob GLOB] [--scan-glob GLOB] [--fixture PATH] [--extractor] [--report] [--force] [--json]"
    "  plugin validate <dir> [--json]"
    "  plugin diagnose <dir> [--json]"
    "  plugin dry-run extractor <dir> <repo-root> <file> [--plugin ID] [--json]"
    "  plugin dry-run report <dir> [--plugin ID] [--json]"
    "  plugin install <project.edn> <git-url-or-path> [--ref REF] [--subdir DIR] [--cache-dir DIR] [--force] [--json]"
    "  plugin list <project.edn> [--json]"
    "  plugin remove <project.edn> <package-id> [--json]"
    "  plugin registry validate <registry.edn> [--json]"
    ""
    "Agent integration:"
    "  agent install --platform codex --project [--hooks] [--print-config]"
    "  agent uninstall --platform codex --project"
    "  agent list"
    "  watch <project.edn> [--map agraph.map.json] [--query-index] [--debounce-ms N]"
    "  hook install <project.edn> [--map agraph.map.json] [--query-index]"
    "  hook uninstall <project.edn>"
    "  hook status <project.edn>"
    ""
    "Server integration:"
    "  mcp [--root DIR] [--config project.edn] [--map agraph.map.json] [--queue-dir DIR] [--tools default,cursor,sync,work,ask|all]"
    ""
    "Benchmarks:"
    "  bench prepare|run|report <benchmark.edn> [--case ID] [--cases ID,ID] [--parser-worker none|java|dotnet|all] [--index-timeout-ms N] [--out DIR] [--json]"
    "  bench show <benchmark.edn> --case ID [--out DIR] [--json]"
    "  bench agent-packet <benchmark.edn> [--case ID] [--cases ID,ID] [--mode agraph|shell-only] [--parser-worker none|java|dotnet|all] [--enqueue] [--queue-dir DIR] [--out DIR] [--json]"
    "  bench agent-baseline <benchmark.edn> [--case ID] [--cases ID,ID] [--retriever auto|hybrid|lexical|semantic|local-vector] [--limit N] [--doc-limit N] [--retrieval-limit N] [--vector-model MODEL] [--vector-command CMD] [--parser-worker none|java|dotnet|all] [--index-timeout-ms N] [--skip-existing] [--out DIR] [--json]"
    "  bench agent-run <benchmark.edn> --agent ID --command CMD [--case ID] [--cases ID,ID] [--mode agraph|shell-only] [--prompt-profile standard|fast] [--timeout-ms N] [--parser-worker none|java|dotnet|all] [--index-timeout-ms N] [--skip-existing] [--out DIR] [--json]"
    "  bench agent-score <benchmark.edn> --case ID --result result.json [--parser-worker none|java|dotnet|all] [--out DIR] [--json]"
    "  bench agent-report <benchmark.edn> [--case ID] [--cases ID,ID] [--mode agraph|shell-only] [--agent ID] [--allow-unverified-scores] [--out DIR] [--json]"
    "  bench agent-check <benchmark.edn> [--case ID] [--cases ID,ID] [--mode agraph|shell-only] [--agent ID] [--min-cases N] [--min-runs N] [--min-file-recall-at-5 N] [--min-file-recall-at-10 N] [--min-file-recall-at-20 N] [--min-case-file-recall-at-5 N] [--min-case-file-recall-at-10 N] [--min-case-file-recall-at-20 N] [--min-mrr N] [--min-case-mrr N] [--min-evidence-citation-rate N] [--min-path-evidence-citation-rate N] [--min-case-evidence-citation-rate N] [--min-case-path-evidence-citation-rate N] [--max-noise-at-20 N] [--max-case-noise-at-20 N] [--max-input-hinted-cases N] [--max-unsupported-ground-truth-files N] [--max-empty-result-runs N] [--max-missing-predicted-file-runs N] [--max-commandless-runs N] [--max-warning-runs N] [--max-hint-diagnostic-runs N] [--max-identity-mismatch-runs N] [--max-unverified-score-runs N] [--max-graph-expectation-failures N] [--max-missing-declared-source-kind-runs N] [--max-missed-runs N] [--max-missed-but-present-in-context-runs N] [--max-missed-and-absent-from-context-runs N] [--max-ranked-outside-top-5-runs N] [--max-ranked-outside-top-10-runs N] [--max-ranked-outside-top-20-runs N] [--max-active-stage-ms N] [--max-parser-worker-profiles N] [--min-measured-problem-classes N] [--min-measured-architecture-classes N] [--require-parser-worker none|java|dotnet|all] [--allow-missing] [--allow-duplicate-runs] [--allow-unverified-scores] [--out DIR] [--json]"
    "  bench agent-compare <benchmark.edn> --baseline-report before.json --candidate-report after.json [--regression-tolerance N] [--out DIR] [--json]"
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
                  :agraph-bin (System/getenv "AGRAPH_BIN")}]
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
               (->> (active-project-systems xtdb project-id)
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
                          {:schema "agraph.queue.error/v1"
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
              out (or (option-value map-args "--out") graph-map/default-path)]
          (when-not config-path
            (throw (ex-info "Missing project config path." {:usage (usage)})))
          (let [project (project/read-project config-path)
                data (graph-map/empty-map (:id project))]
            (print-map-summary (graph-map/write-map! out data) data)))

        :propose
        (let [config-path (first (positional-args map-args))
              out (or (option-value map-args "--out") graph-map/default-path)]
          (when-not config-path
            (throw (ex-info "Missing project config path." {:usage (usage)})))
          (let [project (project/read-project config-path)]
            (store/with-node (store/storage-path)
              (fn [xtdb]
                (let [systems (active-project-systems xtdb (:id project))
                      edges (active-project-system-edges xtdb (:id project))
                      data (graph-map/propose-map (:id project) systems edges)]
                  (print-map-summary (graph-map/write-map! out data) data))))))

        :explain
        (let [[map-path value] (positional-args map-args)]
          (when-not (and map-path value)
            (throw (ex-info "Missing map path or system id/label." {:usage (usage)})))
          (print-map-system (graph-map/system-by-id-or-label (graph-map/read-map map-path) value)))

        :set-kind
        (let [[map-path value kind] (positional-args map-args)]
          (when-not (and map-path value kind)
            (throw (ex-info "Missing map path, system id/label, or kind." {:usage (usage)})))
          (let [data (graph-map/set-kind (graph-map/read-map map-path) value kind)]
            (print-map-summary (graph-map/write-map! map-path data) data)))

        :include
        (let [[map-path value include] (positional-args map-args)]
          (when-not (and map-path value include)
            (throw (ex-info "Missing map path, system id/label, or repo:path include."
                            {:usage (usage)})))
          (let [data (graph-map/add-include (graph-map/read-map map-path)
                                            value
                                            (parse-include include))]
            (print-map-summary (graph-map/write-map! map-path data) data)))

        :reject
        (let [[map-path kind value] (positional-args map-args)
              reason (option-value map-args "--reason")]
          (when-not (and map-path kind value)
            (throw (ex-info "Missing map path, reject kind, or reject value."
                            {:usage (usage)})))
          (let [data (graph-map/add-reject (graph-map/read-map map-path)
                                           (reject-match kind value)
                                           reason)]
            (print-map-summary (graph-map/write-map! map-path data) data)))

        :package-import
        (let [[map-path import-prefix package-target] (positional-args map-args)
              target (some-> package-target parse-package-target)]
          (when-not (and map-path import-prefix package-target)
            (throw (ex-info "Missing map path, import prefix, or ecosystem:package target."
                            {:usage (usage)})))
          (let [data (graph-map/add-package-import
                      (graph-map/read-map map-path)
                      (merge target
                             {:import import-prefix
                              :repo (option-value map-args "--repo")
                              :reason (option-value map-args "--reason")}))]
            (print-map-summary (graph-map/write-map! map-path data) data)))

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
        (let [[map-path target source-value] positional
              _ (when-not (and map-path target source-value)
                  (throw (ex-info "Missing map path, target, or repo:path source."
                                  {:usage (usage)})))
              source (parse-source source-value)
              data (graph-map/add-doc (graph-map/read-map map-path)
                                      target
                                      source
                                      {:role (option-value docs-args "--role")
                                       :heading (option-value docs-args "--heading")
                                       :start-line (parse-optional-long docs-args "--start-line")
                                       :end-line (parse-optional-long docs-args "--end-line")
                                       :reason (option-value docs-args "--reason")})]
          (print-map-summary (graph-map/write-map! map-path data) data))

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
                                (conj (str "agraph project index " config-path))
                                (not infer?)
                                (conj (str "agraph project infer " config-path))
                                true
                                (conj (str "agraph project maintain " config-path)))]
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
                                              (graph-map/read-map map-path))})]
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
                 map-path (assoc :map-overlay (graph-map/read-map map-path)))]
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
