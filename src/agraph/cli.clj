(ns agraph.cli
  "Command line interface."
  (:require [agraph.agent-install :as agent-install]
            [agraph.activity :as activity]
            [agraph.audit-scope :as audit-scope]
            [agraph.benchmark :as benchmark]
            [agraph.command :as command]
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
            [agraph.plugin-package :as plugin-package]
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

(declare usage dispatch print-json json-output?)

(defn- silence-jul!
  []
  (.reset (LogManager/getLogManager)))

(defn- parse-limit
  [args]
  (let [idx (.indexOf args "--limit")]
    (when-not (neg? idx)
      (Long/parseLong (nth args (inc idx))))))

(defn- option-value
  [args flag]
  (let [idx (.indexOf args flag)]
    (when-not (neg? idx)
      (nth args (inc idx)))))

(defn- parse-case-ids
  [args]
  (some->> (option-value args "--cases")
           (#(str/split % #","))
           (map str/trim)
           (remove str/blank?)
           vec
           not-empty))

(defn- parse-long-option
  [args flag default]
  (if-let [value (option-value args flag)]
    (Long/parseLong value)
    default))

(defn- parse-optional-long
  [args flag]
  (some-> (option-value args flag) Long/parseLong))

(defn- parse-depth
  [args]
  (parse-long-option args "--depth" graph/default-depth))

(defn- parse-double-option
  [args flag default]
  (if-let [value (option-value args flag)]
    (Double/parseDouble value)
    default))

(defn- parse-optional-double
  [args flag]
  (some-> (option-value args flag) Double/parseDouble))

(def value-options
  #{"--limit" "--retriever" "--model" "--batch-size" "--provider" "--depth" "--out"
    "--project" "--repo" "--min-confidence" "--map" "--reason" "--budget"
    "--entity-limit" "--edge-limit" "--doc-limit" "--snippet-chars" "--role"
    "--heading" "--start-line" "--end-line" "--valid-at" "--known-at"
    "--snapshot-token" "--type" "--source" "--confidence" "--view" "--query"
    "--relation" "--base-url" "--detail" "--queue-dir" "--status" "--agent"
    "--lease-minutes" "--result" "--kind" "--priority" "--format" "--platform"
    "--debounce-ms" "--name" "--workbench" "--task" "--case" "--mode" "--tools"
    "--id" "--plugin"
    "--ecosystem" "--package" "--prompt-profile" "--report-out" "--command"
    "--vector-command" "--vector-model" "--parser-worker"
    "--ref" "--subdir" "--cache-dir"
    "--timeout-ms" "--index-timeout-ms" "--min-cases" "--min-runs"
    "--retrieval-limit"
    "--min-file-recall-at-5" "--min-file-recall-at-10"
    "--min-file-recall-at-20" "--min-mrr" "--max-noise-at-20"
    "--min-evidence-citation-rate"
    "--min-path-evidence-citation-rate"
    "--min-case-file-recall-at-5" "--min-case-file-recall-at-10"
    "--min-case-file-recall-at-20" "--min-case-mrr"
    "--min-case-evidence-citation-rate"
    "--min-case-path-evidence-citation-rate"
    "--max-case-noise-at-20"
    "--max-input-hinted-cases" "--max-unsupported-ground-truth-files"
    "--max-empty-result-runs" "--max-missing-predicted-file-runs"
    "--max-commandless-runs" "--max-warning-runs"
    "--max-hint-diagnostic-runs"
    "--max-identity-mismatch-runs"
    "--max-unverified-score-runs"
    "--max-graph-expectation-failures" "--max-missing-declared-source-kind-runs"
    "--max-missed-runs"
    "--max-missed-but-present-in-context-runs"
    "--max-missed-and-absent-from-context-runs"
    "--max-ranked-outside-top-5-runs"
    "--max-ranked-outside-top-10-runs"
    "--max-ranked-outside-top-20-runs"
    "--max-active-stage-ms" "--max-parser-worker-profiles"
    "--min-measured-problem-classes" "--min-measured-architecture-classes"
    "--require-parser-worker" "--regression-tolerance" "--skip-existing"})

(def boolean-options
  #{"--dry-run" "--systems" "--no-map" "--json" "--index" "--infer" "--enqueue"
    "--check" "--query-index" "--force" "--hooks" "--sync" "--allow-missing"
    "--allow-duplicate-runs" "--allow-unverified-scores"
    "--skip-existing" "--with-conflicts" "--without-import-evidence"
    "--no-progress" "--extractor" "--report"})

(defn- positional-args
  [args]
  (loop [remaining args
         out []]
    (if-let [arg (first remaining)]
      (if (contains? value-options arg)
        (recur (nnext remaining) out)
        (if (contains? boolean-options arg)
          (recur (next remaining) out)
          (recur (next remaining) (conj out arg))))
      out)))

(defn- dry-run?
  [args]
  (boolean (some #{"--dry-run"} args)))

(defn- system-path?
  [args]
  (some #{"--systems"} args))

(defn- project-scope
  [args]
  {:project-id (option-value args "--project")
   :repo-id (option-value args "--repo")})

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

(defn- remove-option
  [args flag]
  (loop [remaining args
         out []]
    (if-let [arg (first remaining)]
      (if (= flag arg)
        (recur (nnext remaining) out)
        (recur (next remaining) (conj out arg)))
      out)))

(defn- append-option
  [args flag value]
  (cond-> args
    value (conj flag value)))

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
                             {:project-id project-id
                              :repo-id repo-id
                              :retriever retriever
                              :embedding-client embedding-client
                              :map-path (default-map-path args)
                              :read-context temporal
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
                                                                   0.55)}))))

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

(defn- print-evidence-summary
  [{:keys [available counts freshness next]}]
  (println)
  (println "## Evidence Surface")
  (println "- available"
           (if (seq available)
             (str/join ", " (map name available))
             "none"))
  (println "- files" (:files counts 0))
  (println "- nodes" (:nodes counts 0))
  (println "- edges" (:edges counts 0))
  (println "- docs" (:search-docs counts 0))
  (println "- packages" (:packages counts 0))
  (println "- systems" (+ (:system-nodes counts 0) (:system-edges counts 0)))
  (println "- diagnostics" (:diagnostics counts 0))
  (println "- activity-events" (:activity-events counts 0))
  (println "- validation-events" (:validation-events counts 0))
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
           extractors diagnostics repos]}]
  (println "# Source Coverage")
  (println "- project" project-id)
  (println "- repos" (count repos))
  (println "- files" (:files totals))
  (println "- supported" (:supported totals))
  (println "- skipped" (:skipped totals))
  (println "- diagnostics" (:total diagnostics))
  (print-count-rows "## Files By Kind" :kind files-by-kind)
  (print-count-rows "## Skipped By Extension" :ext skipped-by-extension)
  (print-count-rows "## Skipped By Reason" :reason skipped-by-reason)
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

(defn- print-node-finding
  [{:keys [label kind repo-id path-prefix]}]
  (println (str "- " label
                " [" (name kind) "]"
                " repo " repo-id
                (when path-prefix (str " path " path-prefix)))))

(defn- print-edge-finding
  [{:keys [relation confidence source-id target-id]}]
  (println "-" (name relation) (format "%.2f" (double confidence)) source-id "->" target-id))

(defn- json-output?
  [args]
  (some #{"--json"} args))

(defn- print-maintenance-report
  [{:keys [project-id graph-basis map counts scale graph-health fold-in orphaned-systems
           dangling-edges low-confidence-edges decision-summary
           external-api-review decision-queue infra-review-queue dependency-review-queue]}]
  (println "# Maintain")
  (println "- project" project-id)
  (when graph-basis
    (println "- graph-basis" (:hash graph-basis)))
  (when map
    (println "- map-rejects" (:rejects map))
    (println "- map-rejected-systems" (:rejected-systems map)))
  (doseq [[k v] counts]
    (println "-" (name k) v))
  (when scale
    (println "- scale" (name (:tier scale)))
    (println "- noise-ratio" (format "%.2f" (double (get-in scale [:ratios :noise]))))
    (println "- orphan-ratio" (format "%.2f" (double (get-in scale [:ratios :orphaned])))))
  (when (pos? (long (:total decision-summary 0)))
    (println "- decision-summary"
             "severity"
             (str/join ","
                       (clojure.core/map (fn [{:keys [severity count]}]
                                           (str (name severity) ":" count))
                                         (:bySeverity decision-summary)))
             "kind"
             (str/join ","
                       (clojure.core/map (fn [{:keys [kind count]}]
                                           (str (name kind) ":" count))
                                         (:byKind decision-summary)))
             "action"
             (str/join ","
                       (clojure.core/map (fn [{:keys [action count]}]
                                           (str (name action) ":" count))
                                         (:byRecommendedAction decision-summary)))))
  (when (seq (or (:high-degree-hubs graph-health) (:top-hubs scale)))
    (println)
    (println "## Top Hubs")
    (doseq [{:keys [label kind repo-id degree]} (take 10 (or (:high-degree-hubs graph-health)
                                                             (:top-hubs scale)))]
      (println "-" label "[" (name kind) "]" "repo" repo-id "degree" degree)))
  (when (seq (:cross-cluster-edges graph-health))
    (println)
    (println "## Cross Cluster Edges")
    (doseq [{:keys [relation salience source target]} (take 10 (:cross-cluster-edges graph-health))]
      (println "-"
               (name relation)
               (format "%.2f" (double (or salience 0.0)))
               (:label source)
               "->"
               (:label target))))
  (when (seq (:evidence-concentrations graph-health))
    (println)
    (println "## Evidence Concentrations")
    (doseq [{:keys [system kind count]} (take 10 (:evidence-concentrations graph-health))]
      (println "-"
               (:label system)
               "[" (name kind) "]"
               "count"
               count)))
  (when (or (pos? (long (get-in external-api-review [:counts :nodes] 0)))
            (seq (:source-fanouts external-api-review)))
    (println)
    (println "## External API Review")
    (doseq [[k v] (:counts external-api-review)]
      (println "-" (name k) v))
    (when (seq (:source-fanouts external-api-review))
      (println)
      (println "### Source Fanouts")
      (doseq [{:keys [peer relation visibility direction target-count evidence-count]} (take 10 (:source-fanouts external-api-review))]
        (println "-"
                 (:label peer)
                 (or (some-> relation name) relation)
                 (or (some-> direction name) direction)
                 "visibility"
                 visibility
                 "targets"
                 target-count
                 "evidence"
                 evidence-count))))
  (when (seq orphaned-systems)
    (println)
    (println "## Orphaned Systems")
    (doseq [node (take 20 orphaned-systems)]
      (print-node-finding node)))
  (when (seq dangling-edges)
    (println)
    (println "## Dangling Edges")
    (doseq [edge (take 20 dangling-edges)]
      (print-edge-finding edge)))
  (when (seq low-confidence-edges)
    (println)
    (println "## Low Confidence Edges")
    (doseq [edge (take 20 low-confidence-edges)]
      (print-edge-finding edge)))
  (when (seq decision-queue)
    (println)
    (println "## Decision Queue")
    (doseq [{:keys [id kind severity target reason]} (take 20 decision-queue)]
      (println "-" (name severity) (name kind) target "-" reason)
      (println " " id)))
  (when (seq infra-review-queue)
    (println)
    (println "## Infra Review Queue")
    (doseq [{:keys [reviewId kind artifact question]} (take 20 infra-review-queue)]
      (println "-" kind artifact "-" question)
      (println " " reviewId)))
  (when (seq dependency-review-queue)
    (println)
    (println "## Dependency Review Queue")
    (doseq [{:keys [reviewId question facts]} (take 20 dependency-review-queue)]
      (let [unresolved (:unresolvedImport facts)]
        (println "-"
                 (:import unresolved)
                 (when-let [path (:path unresolved)]
                   (str path (when-let [line (:line unresolved)]
                               (str ":" line))))
                 "-"
                 question)
        (println " " reviewId))))
  (when (seq (:actions fold-in))
    (println)
    (println "## Fold In")
    (doseq [{:keys [kind command reason]} (:actions fold-in)]
      (println "-" (name kind) command)
      (println " " reason))))

(defn- repo-run-summary
  [run]
  {:repo-id (:repo-id run)
   :status (:status run)
   :index-profile (:index-profile run)
   :stats (:stats run)})

(defn- query-index?
  [args]
  (some #{"--query-index"} args))

(defn- sync-index-profile
  [args]
  (if (and (not (query-index? args))
           (or (some #{"--check"} args)
               (enqueue-output? args)))
    :graph
    :query))

(defn- progress-output?
  [args]
  (and (not (json-output? args))
       (not (some #{"--no-progress"} args))))

(defn- count-text
  [n singular plural]
  (str (long (or n 0)) " " (if (= 1 (long (or n 0))) singular plural)))

(defn- sync-progress-line
  [{:keys [phase repo-id index-profile files-scanned files-changed files-skipped
           files-deleted files-extracted files-indexed dependency-edges
           chunks search-docs diagnostics total-ms path]}]
  (case phase
    :repo-start
    (str "- " repo-id " start profile=" (name (or index-profile index/default-index-profile)))

    :scan-complete
    (str "- " repo-id " scanned " (count-text files-scanned "file" "files"))

    :plan-complete
    (str "- " repo-id " plan "
         (count-text files-changed "changed file" "changed files")
         ", "
         (count-text files-skipped "skipped file" "skipped files")
         ", "
         (count-text files-deleted "deleted file" "deleted files"))

    :extract-start
    (str "- " repo-id " extracting " (count-text files-changed "changed file" "changed files"))

    :extract-progress
    (str "- " repo-id " extracted " files-extracted "/" files-changed
         (when path
           (str " " path)))

    :extract-complete
    (str "- " repo-id " extracted " (count-text files-extracted "file" "files"))

    :commit-start
    (str "- " repo-id " committing " (count-text files-indexed "file" "files"))

    :commit-complete
    (str "- " repo-id " committed " (count-text files-indexed "file" "files")
         ", " (count-text chunks "chunk" "chunks")
         ", " (count-text search-docs "search doc" "search docs")
         ", " (count-text diagnostics "diagnostic" "diagnostics"))

    :delete-complete
    (str "- " repo-id " deleted " (count-text files-deleted "stale file" "stale files"))

    :dependency-start
    (str "- " repo-id " deriving dependency edges")

    :dependency-complete
    (str "- " repo-id " derived " (count-text dependency-edges "dependency edge" "dependency edges"))

    :dry-run-complete
    (str "- " repo-id " dry-run complete " (count-text files-scanned "file" "files")
         (when total-ms
           (str ", " total-ms "ms")))

    :repo-complete
    (str "- " repo-id " complete "
         (count-text files-scanned "scanned file" "scanned files")
         ", "
         (count-text files-indexed "indexed file" "indexed files")
         ", "
         (count-text files-skipped "skipped file" "skipped files")
         (when total-ms
           (str ", " total-ms "ms")))

    nil))

(defn- sync-progress-fn
  [args]
  (when (progress-output? args)
    (let [printed-header? (atom false)]
      (fn [event]
        (when-let [line (sync-progress-line event)]
          (binding [*out* *err*]
            (when (compare-and-set! printed-header? false true)
              (println "# Sync Progress"))
            (println line)
            (flush)))))))

(defn- sync-index-project!
  [xtdb project args]
  (let [map-path (default-map-path args)
        progress-fn (sync-progress-fn args)
        opts (cond-> {:dry-run? (dry-run? args)
                      :index-profile (sync-index-profile args)
                      :map-overlay (when map-path
                                     (graph-map/read-map map-path))}
               progress-fn (assoc :progress-fn progress-fn))]
    (if-let [repo-id (option-value args "--repo")]
      (let [run (project/index-project-repo! xtdb project repo-id opts)]
        {:project-id (:id project)
         :status (:status run)
         :repos [(repo-run-summary run)]})
      (project/index-project! xtdb project opts))))

(defn- maintenance-report
  [xtdb project args]
  (let [map-path (default-map-path args)]
    (project/maintain-project
     xtdb
     project
     {:low-confidence-threshold (parse-double-option args "--min-confidence" 0.60)
      :map-overlay (when map-path
                     (graph-map/read-map map-path))})))

(defn- enqueue-maintenance-decisions!
  [args decisions]
  (mapv
   (fn [decision]
     (queue/item-summary
      (queue/enqueue!
       (decision-classifier/decision-packet decision)
       {:root (queue-root args)
        :kind "maintenance-decision"
        :project-id (:project-id decision)
        :priority (queue-priority args (severity-priority (:severity decision)))})))
   decisions))

(defn- enqueue-infra-review-packets!
  [args packets]
  (mapv
   (fn [packet]
     (queue/item-summary
      (queue/enqueue!
       packet
       {:root (queue-root args)
        :kind infra-review/work-kind
        :project-id (:project-id packet)
        :priority (queue-priority args 50)})))
   packets))

(defn- enqueue-dependency-review-packets!
  [args packets]
  (mapv
   (fn [packet]
     (queue/item-summary
      (queue/enqueue!
       packet
       {:root (queue-root args)
        :kind dependency-review/work-kind
        :project-id (:project-id packet)
        :priority (queue-priority args 45)})))
   packets))

(defn- enqueue-sync-work!
  [args report]
  (vec
   (concat
    (enqueue-maintenance-decisions! args (:decision-queue report))
    (enqueue-infra-review-packets! args (:infra-review-queue report))
    (enqueue-dependency-review-packets! args (:dependency-review-queue report)))))

(defn- maintenance-result
  [args report]
  (let [enqueued (when (enqueue-output? args)
                   (enqueue-sync-work! args report))]
    (cond-> {:schema "agraph.sync.check/v1"
             :report report}
      enqueued (assoc :enqueued enqueued))))

(defn- print-maintenance-result
  [args result]
  (if (json-output? args)
    (print-json result)
    (do
      (print-maintenance-report (:report result))
      (when-let [enqueued (:enqueued result)]
        (println)
        (println "## Enqueued")
        (doseq [item enqueued]
          (println "-" (:id item) (:kind item) (:status item)))))))

(defn- sync-check!
  [args]
  (let [config-path (first (positional-args args))]
    (when-not config-path
      (throw (ex-info "Missing project config path." {:usage (usage)})))
    (let [project (project/read-project config-path)]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (print-maintenance-result args
                                    (maintenance-result
                                     args
                                     (maintenance-report xtdb project args))))))))

(defn- sync-coverage!
  [args]
  (let [config-path (first (positional-args args))]
    (when-not config-path
      (throw (ex-info "Missing project config path." {:usage (usage)})))
    (let [project (project/read-project config-path)]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (let [report (coverage/project-coverage xtdb project {})]
            (if (json-output? args)
              (print-json report)
              (print-source-coverage report))))))))

(defn- print-activity-sync
  [{:keys [project-id queue-root counts result-schema-mismatches]}]
  (println "# Activity Sync")
  (println "- project" project-id)
  (println "- queue-root" queue-root)
  (println "- items" (:items counts 0))
  (println "- events" (:events counts 0))
  (println "- validation-events" (:validation-events counts 0))
  (println "- result-schema-mismatch-events" (:result-schema-mismatch-events counts 0))
  (println "- ready" (:ready counts 0))
  (println "- claimed" (:claimed counts 0))
  (println "- done" (:done counts 0))
  (println "- rejected" (:rejected counts 0))
  (println "- failed" (:failed counts 0))
  (when (seq result-schema-mismatches)
    (println)
    (println "## Result Schema Mismatches")
    (doseq [{:keys [sourceId itemId expectedResultSchema resultSchema status]} result-schema-mismatches]
      (println "-" sourceId
               "expected" expectedResultSchema
               "actual" resultSchema
               "status" status
               "item" itemId))))

(defn- sync-activity!
  [args]
  (let [config-path (first (positional-args args))]
    (when-not config-path
      (throw (ex-info "Missing project config path." {:usage (usage)})))
    (let [project (project/read-project config-path)]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (let [result (activity/sync-queue! xtdb
                                             project
                                             {:queue-root (queue-root args)})]
            (if (json-output? args)
              (print-json result)
              (print-activity-sync result))))))))

(defn- sync-project!
  [args]
  (let [config-path (first (positional-args args))]
    (when-not config-path
      (throw (ex-info "Missing project config path." {:usage (usage)})))
    (let [project (project/read-project config-path)
          repo-id (option-value args "--repo")
          check? (or (some #{"--check"} args)
                     (enqueue-output? args))]
      (if (dry-run? args)
        (let [index-summary (sync-index-project! nil project args)
              result {:schema "agraph.sync/v1"
                      :project-id (:id project)
                      :repo-id repo-id
                      :index-summary index-summary}]
          (if (json-output? args)
            (print-json result)
            (print-sync-summary result)))
        (store/with-node (store/storage-path)
          (fn [xtdb]
            (let [index-summary (sync-index-project! xtdb project args)
                  system-summary (project/infer-project! xtdb project)
                  report (when check?
                           (maintenance-report xtdb project args))
                  enqueued (when (and report (enqueue-output? args))
                             (enqueue-sync-work! args report))
                  result (cond-> {:schema "agraph.sync/v1"
                                  :project-id (:id project)
                                  :repo-id repo-id
                                  :index-summary index-summary
                                  :system-summary system-summary}
                           report (assoc :check-report report)
                           enqueued (assoc :enqueued enqueued))]
              (if (json-output? args)
                (print-json result)
                (print-sync-summary result)))))))))

(defn- sync-add-repo!
  [args]
  (let [[config-path repo-root] (positional-args args)]
    (when-not (and config-path repo-root)
      (throw (ex-info "Missing project config path or repo path."
                      {:usage (usage)})))
    (let [project (project/add-repo-to-config!
                   config-path
                   repo-root
                   {:repo-id (option-value args "--repo")
                    :role (some-> (option-value args "--role") keyword)})
          repo (last (:repos project))]
      (print-project-add-repo-summary
       {:project project
        :repo repo
        :next [(str "agraph sync " config-path)]}))))

(defn- sync-work!
  [args]
  (let [action (keyword (first args))
        work-args (vec (rest args))
        positional (positional-args work-args)
        root (queue-root work-args)]
    (case action
      :list
      (print-json
       (queue/list-summary root
                           {:status (option-value work-args "--status")
                            :project-id (option-value work-args "--project")
                            :kind (option-value work-args "--kind")
                            :limit (parse-limit work-args)}))

      :pull
      (print-json
       (if-let [found (queue/claim-next!
                       root
                       {:agent-id (queue-agent work-args)
                        :lease-ms (queue-lease-ms work-args)
                        :project-id (option-value work-args "--project")
                        :kind (option-value work-args "--kind")})]
         (assoc (queue/item-summary found) :item (:item found))
         {:schema queue/summary-schema
          :status "empty"
          :root root}))

      :show
      (let [id (first positional)]
        (when-not id
          (throw (ex-info "Missing sync work id." {:usage (usage)})))
        (print-json (if-let [found (queue/find-item root id)]
                      (assoc (queue/item-summary found) :item (:item found))
                      {:schema "agraph.queue.error/v1"
                       :error "sync work item not found"
                       :id id})))

      :complete
      (let [id (first positional)
            result-path (option-value work-args "--result")]
        (when-not (and id result-path)
          (throw (ex-info "Missing sync work id or --result path."
                          {:usage (usage)})))
        (print-json
         (queue/item-summary
          (queue/complete! root id (queue/read-json-file result-path)))))

      :apply
      (let [id (first positional)
            map-path (required-map-path work-args)]
        (when-not id
          (throw (ex-info "Missing sync work id."
                          {:usage (usage)})))
        (print-json
         (apply-work-result! root id map-path)))

      :reject
      (let [id (first positional)
            reason (option-value work-args "--reason")]
        (when-not (and id reason)
          (throw (ex-info "Missing sync work id or --reason."
                          {:usage (usage)})))
        (print-json (queue/item-summary (queue/reject! root id reason))))

      :release
      (let [id (first positional)]
        (when-not id
          (throw (ex-info "Missing sync work id." {:usage (usage)})))
        (print-json
         (queue/item-summary
          (queue/release! root id (or (option-value work-args "--reason")
                                      "manual release")))))

      :heartbeat
      (let [id (first positional)]
        (when-not id
          (throw (ex-info "Missing sync work id." {:usage (usage)})))
        (print-json
         (queue/item-summary
          (queue/heartbeat! root
                            id
                            {:agent-id (queue-agent work-args)
                             :lease-ms (queue-lease-ms work-args)}))))

      (throw (ex-info "Unknown sync work command." {:command action
                                                    :usage (usage)})))))

(defn- sync-map-command!
  [action args]
  (case action
    (:init :propose)
    (dispatch "map" (into [(name action)] args))

    :explain
    (let [target (first (positional-args args))]
      (when-not target
        (throw (ex-info "Missing sync target." {:usage (usage)})))
      (dispatch "map" ["explain" (required-map-path args) target]))

    :set-kind
    (let [[target kind] (positional-args args)]
      (when-not (and target kind)
        (throw (ex-info "Missing sync target or kind." {:usage (usage)})))
      (dispatch "map" ["set-kind" (required-map-path args) target kind]))

    :include
    (let [[target include] (positional-args args)]
      (when-not (and target include)
        (throw (ex-info "Missing sync target or repo:path include."
                        {:usage (usage)})))
      (dispatch "map" ["include" (required-map-path args) target include]))

    :ignore
    (let [[kind value] (positional-args args)
          map-args (append-option ["reject" (required-map-path args) kind value]
                                  "--reason"
                                  (option-value args "--reason"))]
      (when-not (and kind value)
        (throw (ex-info "Missing ignore kind or value." {:usage (usage)})))
      (dispatch "map" map-args))

    :package
    (let [[subcommand import-prefix package-target] (positional-args args)]
      (when-not (= "import" subcommand)
        (throw (ex-info "Unknown sync package command."
                        {:command subcommand
                         :usage (usage)})))
      (when-not (and import-prefix package-target)
        (throw (ex-info "Missing package import prefix or ecosystem:package target."
                        {:usage (usage)})))
      (dispatch "map"
                (-> ["package-import"
                     (required-map-path args)
                     import-prefix
                     package-target]
                    (append-option "--repo" (option-value args "--repo"))
                    (append-option "--reason" (option-value args "--reason")))))

    (throw (ex-info "Unknown sync command." {:command action
                                             :usage (usage)}))))

(defn- sync-docs!
  [args]
  (let [action (keyword (first args))
        docs-args (vec (rest args))]
    (case action
      :attach
      (let [[target source] (positional-args docs-args)
            map-path (required-map-path docs-args)
            forwarded (-> ["attach" map-path target source]
                          (append-option "--role" (option-value docs-args "--role"))
                          (append-option "--heading" (option-value docs-args "--heading"))
                          (append-option "--start-line" (option-value docs-args "--start-line"))
                          (append-option "--end-line" (option-value docs-args "--end-line"))
                          (append-option "--reason" (option-value docs-args "--reason")))]
        (when-not (and target source)
          (throw (ex-info "Missing docs target or repo:path source."
                          {:usage (usage)})))
        (dispatch "docs" forwarded))

      (:candidates :for :audit)
      (dispatch "docs" args)

      (throw (ex-info "Unknown sync docs command." {:command action
                                                    :usage (usage)})))))

(defn- sync-dispatch!
  [args]
  (let [action-name (first args)
        action (keyword action-name)
        action-args (vec (rest args))]
    (case action
      :inspect
      (let [config-path (first (positional-args action-args))]
        (print-project-status! config-path action-args))

      :add-repo
      (sync-add-repo! action-args)

      :check
      (sync-check! action-args)

      :coverage
      (sync-coverage! action-args)

      :activity
      (sync-activity! action-args)

      :work
      (sync-work! action-args)

      :docs
      (sync-docs! action-args)

      :meta
      (dispatch "meta" action-args)

      :view
      (dispatch "views" action-args)

      (:init :propose :explain :set-kind :include :ignore :package)
      (sync-map-command! action action-args)

      (if action-name
        (sync-project! args)
        (throw (ex-info "Missing sync project config path." {:usage (usage)}))))))

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
                                   {:project-id project-id
                                    :repo-id repo-id
                                    :retriever retriever
                                    :embedding-client embedding-client
                                    :map-path (default-map-path args)
                                    :read-context temporal
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
                                                                         0.55)}))
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

(def start-schema
  "agraph.start/v1")

(defn- ensure-init-map!
  [project-id map-path]
  (when (and map-path
             (not (graph-map/file-exists? map-path)))
    (graph-map/write-map! map-path (graph-map/empty-map project-id))))

(defn- start-map-path
  [args]
  (cond
    (some #{"--no-map"} args) nil
    (option-value args "--map") (option-value args "--map")
    :else graph-map/default-path))

(defn- project-config-exists?
  [path]
  (.exists (io/file path)))

(defn- init-sync-args
  [config-path map-path query-index?]
  (cond-> [config-path "--check" "--no-progress"]
    map-path (into ["--map" map-path])
    query-index? (conj "--query-index")))

(defn- init!
  [args]
  (let [workbench-root (option-value args "--workbench")
        root (or workbench-root (first (positional-args args)) ".")
        map-path (option-value args "--map")
        result (init/init! root
                           {:out (option-value args "--out")
                            :force? (boolean (some #{"--force"} args))
                            :project-id (option-value args "--project")
                            :name (option-value args "--name")
                            :workbench? (boolean workbench-root)
                            :task (option-value args "--task")
                            :map-path map-path})]
    (ensure-init-map! (:project-id result) map-path)
    (print-json
     (cond-> result
       (some #{"--sync"} args)
       (assoc :sync-output
              (with-out-str
                (dispatch "sync"
                          (init-sync-args (:config result)
                                          map-path
                                          (query-index? args)))))))))

(defn- sync-project-result!
  [xtdb project args]
  (let [repo-id (option-value args "--repo")
        check? (or (some #{"--check"} args)
                   (enqueue-output? args))
        index-summary (sync-index-project! xtdb project args)
        system-summary (project/infer-project! xtdb project)
        report (when check?
                 (maintenance-report xtdb project args))
        enqueued (when (and report (enqueue-output? args))
                   (enqueue-sync-work! args report))]
    (cond-> {:schema "agraph.sync/v1"
             :project-id (:id project)
             :repo-id repo-id
             :index-summary index-summary
             :system-summary system-summary}
      report (assoc :check-report report)
      enqueued (assoc :enqueued enqueued))))

(defn- start-project!
  [root config-path map-path args]
  (if (and (project-config-exists? config-path)
           (not (some #{"--force"} args)))
    {:mode "existing"
     :config config-path
     :project (project/read-project config-path)}
    (let [result (init/init! root
                             {:out config-path
                              :force? (boolean (some #{"--force"} args))
                              :project-id (option-value args "--project")
                              :name (option-value args "--name")
                              :workbench? (boolean (option-value args "--workbench"))
                              :task (option-value args "--task")
                              :map-path map-path})]
      {:mode "initialized"
       :config (:config result)
       :init result
       :project (project/read-project (:config result))})))

(defn- start-next-actions
  [project-id config-path map-path report-out]
  (cond-> [{:kind :ask
            :label "Ask a graph-grounded implementation question"
            :command (str "agraph ask \"where is this handled?\" --project "
                          (command/shell-token project-id)
                          " --json")}
           {:kind :explore
            :label "Create a persistent exploration cursor"
            :command (str "agraph explore create \"runtime boundary\" --project "
                          (command/shell-token project-id))}
           {:kind :systems
            :label "Inspect system graph"
            :command (str "agraph view systems --project "
                          (command/shell-token project-id))}
           {:kind :report
            :label "Open or regenerate local report bundle"
            :command (str "agraph report " (command/shell-token config-path)
                          (when map-path
                            (str " --map " (command/shell-token map-path)))
                          " --out " (command/shell-token report-out))}
           {:kind :install-agent
            :label "Install project-local agent guidance"
            :command "agraph install-agent --platform codex --project"}]
    true vec))

(defn- start-next-commands
  [actions]
  (mapv :command actions))

(defn- sum-repo-stats
  [sync-result]
  (->> (get-in sync-result [:index-summary :repos])
       (map :stats)
       (apply merge-with +)))

(defn- compact-counts
  [sync-result activity-result]
  (let [repo-stats (sum-repo-stats sync-result)
        system-summary (:system-summary sync-result)
        maintenance-counts (get-in sync-result [:check-report :counts])
        activity-counts (:counts activity-result)]
    {:files {:scanned (:files-scanned repo-stats 0)
             :indexed (:files-indexed repo-stats 0)
             :skipped (:files-skipped repo-stats 0)
             :deleted (:files-deleted repo-stats 0)
             :diagnostics (:diagnostics repo-stats 0)}
     :graph {:nodes (:nodes repo-stats 0)
             :edges (:edges repo-stats 0)
             :file-facts (:file-facts repo-stats 0)
             :chunks (:chunks repo-stats 0)
             :search-docs (:search-docs repo-stats 0)}
     :systems {:nodes (:system-nodes system-summary 0)
               :edges (:system-edges system-summary 0)
               :evidence (:system-evidence system-summary 0)
               :maintenance-decisions (:maintenance-decisions maintenance-counts 0)
               :orphaned-candidates (:orphaned-systems maintenance-counts 0)}
     :activity {:items (:items activity-counts 0)
                :events (:events activity-counts 0)
                :validation-events (:validation-events activity-counts 0)
                :result-schema-mismatch-events (:result-schema-mismatch-events
                                                activity-counts
                                                0)}}))

(defn- compact-report
  [report-result]
  {:out (:out report-result)
   :files (:files report-result)})

(defn- start-result
  [project start-info map-path report-out sync-result activity-result report-result]
  (let [actions (start-next-actions (:id project)
                                    (:config start-info)
                                    map-path
                                    report-out)]
    (cond-> {:schema start-schema
             :project-id (:id project)
             :mode (:mode start-info)
             :config (:config start-info)
             :map map-path
             :report (compact-report report-result)
             :counts (compact-counts sync-result activity-result)
             :evidence (:evidence report-result)
             :next (start-next-commands actions)
             :nextActions actions}
      (:init start-info) (assoc :initialized true))))

(defn- start!
  [args]
  (let [workbench-root (option-value args "--workbench")
        root (or workbench-root (first (positional-args args)) ".")
        config-path (or (option-value args "--out") "project.edn")
        map-path (start-map-path args)
        report-out (or (option-value args "--report-out") report/default-output-dir)
        start-info (start-project! root config-path map-path args)
        project (:project start-info)]
    (ensure-init-map! (:id project) map-path)
    (store/with-node (store/storage-path)
      (fn [xtdb]
        (let [sync-args (init-sync-args (:config start-info)
                                        map-path
                                        (query-index? args))
              sync-result (sync-project-result! xtdb project sync-args)
              activity-result (activity/sync-queue! xtdb
                                                    project
                                                    {:queue-root (queue-root args)})
              report-result (report/bundle! xtdb
                                            project
                                            {:out report-out
                                             :map-path map-path
                                             :detail (keyword (or (option-value args "--detail")
                                                                  (name report/default-detail)))
                                             :force? (boolean (some #{"--force"} args))})]
          (print-json
           (start-result project
                         start-info
                         map-path
                         report-out
                         sync-result
                         activity-result
                         report-result)))))))

(defn- bench-opts
  [args]
  (cond-> {:case-id (option-value args "--case")
           :out (option-value args "--out")
           :retriever (option-value args "--retriever")
           :parser-worker (option-value args "--parser-worker")
           :mode (option-value args "--mode")
           :result-path (option-value args "--result")
           :command (option-value args "--command")}
    (parse-case-ids args) (assoc :case-ids (parse-case-ids args))
    (option-value args "--baseline-report") (assoc :baseline-report
                                                   (option-value args "--baseline-report"))
    (option-value args "--candidate-report") (assoc :candidate-report
                                                    (option-value args "--candidate-report"))
    (option-value args "--vector-command") (assoc :vector-command
                                                  (option-value args "--vector-command"))
    (option-value args "--vector-model") (assoc :vector-model
                                                (option-value args "--vector-model"))
    (option-value args "--agent") (assoc :agent-id (option-value args "--agent"))
    (option-value args "--prompt-profile") (assoc :prompt-profile
                                                  (option-value args "--prompt-profile"))
    (parse-limit args) (assoc :limit (parse-limit args))
    (parse-optional-long args "--timeout-ms") (assoc :timeout-ms
                                                     (parse-optional-long args
                                                                          "--timeout-ms"))
    (parse-optional-long args "--index-timeout-ms") (assoc :index-timeout-ms
                                                           (parse-optional-long
                                                            args
                                                            "--index-timeout-ms"))
    (parse-optional-long args "--min-cases") (assoc :min-cases
                                                    (parse-optional-long args
                                                                         "--min-cases"))
    (parse-optional-long args "--min-runs") (assoc :min-runs
                                                   (parse-optional-long args
                                                                        "--min-runs"))
    (parse-optional-long args "--budget") (assoc :budget (parse-optional-long args "--budget"))
    (parse-optional-long args "--doc-limit") (assoc :doc-limit (parse-optional-long args "--doc-limit"))
    (parse-optional-long args "--retrieval-limit") (assoc :retrieval-limit
                                                          (parse-optional-long args
                                                                               "--retrieval-limit"))
    (parse-optional-long args "--snippet-chars") (assoc :snippet-chars
                                                        (parse-optional-long args
                                                                             "--snippet-chars"))
    (parse-optional-double args "--min-file-recall-at-5") (assoc :min-file-recall-at-5
                                                                 (parse-optional-double
                                                                  args
                                                                  "--min-file-recall-at-5"))
    (parse-optional-double args "--min-file-recall-at-10") (assoc :min-file-recall-at-10
                                                                  (parse-optional-double
                                                                   args
                                                                   "--min-file-recall-at-10"))
    (parse-optional-double args "--min-file-recall-at-20") (assoc :min-file-recall-at-20
                                                                  (parse-optional-double
                                                                   args
                                                                   "--min-file-recall-at-20"))
    (parse-optional-double args "--min-mrr") (assoc :min-mrr
                                                    (parse-optional-double args
                                                                           "--min-mrr"))
    (parse-optional-double args "--max-noise-at-20") (assoc :max-noise-at-20
                                                            (parse-optional-double
                                                             args
                                                             "--max-noise-at-20"))
    (parse-optional-double args "--min-evidence-citation-rate") (assoc
                                                                 :min-evidence-citation-rate
                                                                 (parse-optional-double
                                                                  args
                                                                  "--min-evidence-citation-rate"))
    (parse-optional-double args "--min-path-evidence-citation-rate") (assoc
                                                                      :min-path-evidence-citation-rate
                                                                      (parse-optional-double
                                                                       args
                                                                       "--min-path-evidence-citation-rate"))
    (parse-optional-double args "--min-case-file-recall-at-5") (assoc
                                                                :min-case-file-recall-at-5
                                                                (parse-optional-double
                                                                 args
                                                                 "--min-case-file-recall-at-5"))
    (parse-optional-double args "--min-case-file-recall-at-10") (assoc
                                                                 :min-case-file-recall-at-10
                                                                 (parse-optional-double
                                                                  args
                                                                  "--min-case-file-recall-at-10"))
    (parse-optional-double args "--min-case-file-recall-at-20") (assoc
                                                                 :min-case-file-recall-at-20
                                                                 (parse-optional-double
                                                                  args
                                                                  "--min-case-file-recall-at-20"))
    (parse-optional-double args "--min-case-mrr") (assoc :min-case-mrr
                                                         (parse-optional-double
                                                          args
                                                          "--min-case-mrr"))
    (parse-optional-double args "--min-case-evidence-citation-rate") (assoc
                                                                      :min-case-evidence-citation-rate
                                                                      (parse-optional-double
                                                                       args
                                                                       "--min-case-evidence-citation-rate"))
    (parse-optional-double args "--min-case-path-evidence-citation-rate") (assoc
                                                                           :min-case-path-evidence-citation-rate
                                                                           (parse-optional-double
                                                                            args
                                                                            "--min-case-path-evidence-citation-rate"))
    (parse-optional-double args "--max-case-noise-at-20") (assoc
                                                           :max-case-noise-at-20
                                                           (parse-optional-double
                                                            args
                                                            "--max-case-noise-at-20"))
    (parse-optional-double args "--max-input-hinted-cases") (assoc :max-input-hinted-cases
                                                                   (parse-optional-double
                                                                    args
                                                                    "--max-input-hinted-cases"))
    (parse-optional-double args "--max-unsupported-ground-truth-files") (assoc
                                                                         :max-unsupported-ground-truth-files
                                                                         (parse-optional-double
                                                                          args
                                                                          "--max-unsupported-ground-truth-files"))
    (parse-optional-double args "--max-empty-result-runs") (assoc
                                                            :max-empty-result-runs
                                                            (parse-optional-double
                                                             args
                                                             "--max-empty-result-runs"))
    (parse-optional-double args "--max-missing-predicted-file-runs") (assoc
                                                                      :max-missing-predicted-file-runs
                                                                      (parse-optional-double
                                                                       args
                                                                       "--max-missing-predicted-file-runs"))
    (parse-optional-double args "--max-commandless-runs") (assoc
                                                           :max-commandless-runs
                                                           (parse-optional-double
                                                            args
                                                            "--max-commandless-runs"))
    (parse-optional-double args "--max-warning-runs") (assoc
                                                       :max-warning-runs
                                                       (parse-optional-double
                                                        args
                                                        "--max-warning-runs"))
    (parse-optional-double args "--max-hint-diagnostic-runs") (assoc
                                                               :max-hint-diagnostic-runs
                                                               (parse-optional-double
                                                                args
                                                                "--max-hint-diagnostic-runs"))
    (parse-optional-double args "--max-identity-mismatch-runs") (assoc
                                                                 :max-identity-mismatch-runs
                                                                 (parse-optional-double
                                                                  args
                                                                  "--max-identity-mismatch-runs"))
    (parse-optional-double args "--max-unverified-score-runs") (assoc
                                                                :max-unverified-score-runs
                                                                (parse-optional-double
                                                                 args
                                                                 "--max-unverified-score-runs"))
    (parse-optional-double args "--max-graph-expectation-failures") (assoc
                                                                     :max-graph-expectation-failures
                                                                     (parse-optional-double
                                                                      args
                                                                      "--max-graph-expectation-failures"))
    (parse-optional-double args "--max-missing-declared-source-kind-runs") (assoc
                                                                            :max-missing-declared-source-kind-runs
                                                                            (parse-optional-double
                                                                             args
                                                                             "--max-missing-declared-source-kind-runs"))
    (parse-optional-double args "--max-missed-runs") (assoc
                                                      :max-missed-runs
                                                      (parse-optional-double
                                                       args
                                                       "--max-missed-runs"))
    (parse-optional-double args "--max-missed-but-present-in-context-runs") (assoc
                                                                             :max-missed-but-present-in-context-runs
                                                                             (parse-optional-double
                                                                              args
                                                                              "--max-missed-but-present-in-context-runs"))
    (parse-optional-double args "--max-missed-and-absent-from-context-runs") (assoc
                                                                              :max-missed-and-absent-from-context-runs
                                                                              (parse-optional-double
                                                                               args
                                                                               "--max-missed-and-absent-from-context-runs"))
    (parse-optional-double args "--max-ranked-outside-top-5-runs") (assoc
                                                                    :max-ranked-outside-top-5-runs
                                                                    (parse-optional-double
                                                                     args
                                                                     "--max-ranked-outside-top-5-runs"))
    (parse-optional-double args "--max-ranked-outside-top-10-runs") (assoc
                                                                     :max-ranked-outside-top-10-runs
                                                                     (parse-optional-double
                                                                      args
                                                                      "--max-ranked-outside-top-10-runs"))
    (parse-optional-double args "--max-ranked-outside-top-20-runs") (assoc
                                                                     :max-ranked-outside-top-20-runs
                                                                     (parse-optional-double
                                                                      args
                                                                      "--max-ranked-outside-top-20-runs"))
    (parse-optional-long args "--max-active-stage-ms") (assoc :max-active-stage-ms
                                                              (parse-optional-long
                                                               args
                                                               "--max-active-stage-ms"))
    (parse-optional-long args "--max-parser-worker-profiles") (assoc
                                                               :max-parser-worker-profiles
                                                               (parse-optional-long
                                                                args
                                                                "--max-parser-worker-profiles"))
    (parse-optional-long args "--min-measured-problem-classes") (assoc
                                                                 :min-measured-problem-classes
                                                                 (parse-optional-long
                                                                  args
                                                                  "--min-measured-problem-classes"))
    (parse-optional-long args "--min-measured-architecture-classes") (assoc
                                                                      :min-measured-architecture-classes
                                                                      (parse-optional-long
                                                                       args
                                                                       "--min-measured-architecture-classes"))
    (option-value args "--require-parser-worker") (assoc :require-parser-worker
                                                         (option-value
                                                          args
                                                          "--require-parser-worker"))
    (parse-optional-double args "--regression-tolerance") (assoc
                                                           :regression-tolerance
                                                           (parse-optional-double
                                                            args
                                                            "--regression-tolerance"))
    (some #{"--skip-existing"} args) (assoc :skip-existing? true)
    (some #{"--allow-missing"} args) (assoc :allow-missing? true)
    (some #{"--allow-duplicate-runs"} args) (assoc :allow-duplicate-runs? true)
    (some #{"--allow-unverified-scores"} args) (assoc :allow-unverified-scores? true)))

(defn- print-benchmark-case-summary
  [case]
  (println "-"
           (:case-id case)
           (:repo-id case)
           "recall@10"
           (format "%.2f" (double (get-in case [:scores :fileRecallAt10] 0.0)))
           "mrr"
           (format "%.2f" (double (get-in case [:scores :meanReciprocalRankFile] 0.0)))))

(defn- parser-worker-summary-label
  [profile]
  (str (:mode profile)
       "/"
       (:source profile)
       ":"
       (:runs profile)))

(defn- print-parser-worker-summary
  [profiles]
  (when (seq profiles)
    (println "- parser-workers"
             (str/join ", " (map parser-worker-summary-label profiles)))))

(defn- print-agent-diagnostic-count
  [diagnostics label count-key case-ids-key & {:keys [extra-key extra-label]}]
  (let [count-value (long (or (get diagnostics count-key) 0))]
    (when (pos? count-value)
      (println
       (str/join " "
                 (cond-> [(str "- " label)
                          (str count-value)]
                   extra-key
                   (conj (str extra-label " " (or (get diagnostics extra-key) 0)))
                   true
                   (conj "cases"
                         (str/join "," (get diagnostics case-ids-key)))))))))

(defn- print-agent-diagnostics-summary
  [diagnostics]
  (when diagnostics
    (print-agent-diagnostic-count diagnostics
                                  "missing-predicted-file-runs"
                                  :missingPredictedFileRuns
                                  :missingPredictedFileCaseIds
                                  :extra-key :missingPredictedFiles
                                  :extra-label "files")
    (print-agent-diagnostic-count diagnostics
                                  "commandless-runs"
                                  :commandlessRuns
                                  :commandlessCaseIds)
    (print-agent-diagnostic-count diagnostics
                                  "warning-runs"
                                  :warningRuns
                                  :warningCaseIds)
    (print-agent-diagnostic-count diagnostics
                                  "hint-diagnostic-runs"
                                  :hintDiagnosticRuns
                                  :hintDiagnosticCaseIds
                                  :extra-key :hintDiagnosticRows
                                  :extra-label "rows")))

(defn- print-artifact-diagnostics-summary
  [diagnostics]
  (when diagnostics
    (print-agent-diagnostic-count diagnostics
                                  "unverified-score-runs"
                                  :unverifiedScoreRuns
                                  :unverifiedScoreCaseIds)
    (let [obsolete-runs (long (or (:obsoleteScoreSchemaRuns diagnostics) 0))]
      (when (pos? obsolete-runs)
        (println "- obsolete-score-schema-runs"
                 obsolete-runs
                 "schemas"
                 (str/join "," (:obsoleteScoreSchemas diagnostics))
                 "expected"
                 (:expectedScoreSchema diagnostics)
                 "cases"
                 (str/join "," (:obsoleteScoreSchemaCaseIds diagnostics)))))
    (let [obsolete-runs (long (or (:obsoleteAgentResultSchemaRuns diagnostics) 0))]
      (when (pos? obsolete-runs)
        (println "- obsolete-agent-result-schema-runs"
                 obsolete-runs
                 "schemas"
                 (str/join "," (:obsoleteAgentResultSchemas diagnostics))
                 "expected"
                 (:expectedAgentResultSchema diagnostics)
                 "cases"
                 (str/join "," (:obsoleteAgentResultSchemaCaseIds diagnostics)))))
    (print-agent-diagnostic-count diagnostics
                                  "stale-score-runs"
                                  :staleScoreRuns
                                  :staleScoreCaseIds)))

(defn- print-claim-readiness
  [claim-readiness]
  (when claim-readiness
    (println "- claim-readiness" (:status claim-readiness))
    (when (seq (:measuredProblemClassTags claim-readiness))
      (println "- measured-problem-classes"
               (str/join "," (:measuredProblemClassTags claim-readiness))))
    (when (seq (:measuredArchitectureClassTags claim-readiness))
      (println "- measured-architecture-classes"
               (str/join "," (:measuredArchitectureClassTags claim-readiness))))
    (when (seq (:warnings claim-readiness))
      (println "## Claim Readiness Warnings")
      (doseq [warning (:warnings claim-readiness)]
        (println "-" warning)))))

(defn- print-benchmark-summary
  [result]
  (println "# Benchmark")
  (println "- schema" (:schema result))
  (println "- suite" (:suite-id result))
  (cond
    (= benchmark/agent-runs-schema (:schema result))
    (do
      (println "- completed" (:completed result))
      (println "- failed" (:failed result))
      (println "- skipped" (:skipped result 0))
      (doseq [run (:runs result)]
        (println "-"
                 (:case-id run)
                 (:repo-id run)
                 "agent"
                 (:agentId run)
                 "status"
                 (:status run)
                 "recall@10"
                 (format "%.2f" (double (get-in run [:scores :fileRecallAt10] 0.0)))
                 "mrr"
                 (format "%.2f" (double (get-in run
                                                [:scores :meanReciprocalRankFile]
                                                0.0))))))

    (:baselines result)
    (do
      (when (contains? result :skipped)
        (println "- skipped" (:skipped result 0)))
      (doseq [baseline (:baselines result)]
        (println "-"
                 (:case-id baseline)
                 (:repo-id baseline)
                 "agent"
                 (:agentId baseline)
                 "status"
                 (or (:status baseline) "ran")
                 "recall@10"
                 (format "%.2f" (double (get-in baseline [:scores :fileRecallAt10] 0.0)))
                 "mrr"
                 (format "%.2f" (double (get-in baseline
                                                [:scores :meanReciprocalRankFile]
                                                0.0))))))

    (:completed result)
    (do
      (println "- cases" (:cases result))
      (println "- completed" (:completed result))
      (println "- file-recall@10"
               (format "%.2f" (double (get-in result [:scores :fileRecallAt10] 0.0))))
      (println "- mrr"
               (format "%.2f" (double (get-in result [:scores :meanReciprocalRankFile] 0.0))))
      (println "- evidence-citation"
               (format "%.2f" (double (get-in result [:scores :evidenceCitationRate] 0.0))))
      (print-parser-worker-summary (:parserWorkers result))
      (print-agent-diagnostics-summary (:agentDiagnostics result))
      (print-artifact-diagnostics-summary (:artifactDiagnostics result))
      (print-claim-readiness (:claimReadiness result))
      (when-let [blocker (first (get-in result
                                        [:localizationDiagnostics
                                         :rankedOutsideTop5BlockingFiles]))]
        (println "- top5-blocker"
                 (:path blocker)
                 "occurrences"
                 (:occurrences blocker)
                 "best-rank"
                 (:bestRank blocker)))
      (when (pos? (long (get-in result
                                [:graphExpectationDiagnostics :failedRuns]
                                0)))
        (println "- graph-expectation-failures"
                 (get-in result [:graphExpectationDiagnostics :failedRuns])
                 "cases"
                 (str/join "," (get-in result
                                       [:graphExpectationDiagnostics :failedCaseIds]))))
      (when-let [timings (:timings result)]
        (println "- timing-ms" (:elapsedMs timings)
                 "running" (:runningCases timings)
                 "failed" (:failedCases timings))
        (when-let [slowest (first (:slowestCases timings))]
          (println "- slowest"
                   (:case-id slowest)
                   (:status slowest)
                   (:elapsedMs slowest)
                   "ms")))
      (when (seq (:missing result))
        (println "- missing" (str/join "," (:missing result)))))

    (:cases result)
    (doseq [case (:cases result)]
      (if (:scores case)
        (print-benchmark-case-summary case)
        (println "-" (:case-id case) (:repo-id case) "prepared")))

    (:packets result)
    (doseq [packet (:packets result)]
      (println "-"
               (:case-id packet)
               (:repo-id packet)
               "mode"
               (:mode packet)
               "packet"
               (get-in packet [:artifacts :packetPath])))

    (= benchmark/agent-check-schema (:schema result))
    (do
      (println "- status" (:status result))
      (println "- completed" (get-in result [:report :completed]) "/" (get-in result [:report :cases]))
      (println "- runs" (get-in result [:report :runs]))
      (println "- file-recall@10"
               (format "%.2f" (double (get-in result
                                              [:report :scores :fileRecallAt10]
                                              0.0))))
      (println "- mrr"
               (format "%.2f" (double (get-in result
                                              [:report :scores :meanReciprocalRankFile]
                                              0.0))))
      (println "- evidence-citation"
               (format "%.2f" (double (get-in result
                                              [:report :scores :evidenceCitationRate]
                                              0.0))))
      (print-parser-worker-summary (get-in result [:report :parserWorkers]))
      (print-agent-diagnostics-summary (get-in result [:report :agentDiagnostics]))
      (print-artifact-diagnostics-summary (get-in result [:report :artifactDiagnostics]))
      (print-claim-readiness (get-in result [:report :claimReadiness]))
      (println "- noise@20"
               (format "%.2f" (double (get-in result
                                              [:report :scores :noiseRatioAt20]
                                              0.0))))
      (when-let [blocker (first (get-in result
                                        [:report
                                         :localizationDiagnostics
                                         :rankedOutsideTop5BlockingFiles]))]
        (println "- top5-blocker"
                 (:path blocker)
                 "occurrences"
                 (:occurrences blocker)
                 "best-rank"
                 (:bestRank blocker)))
      (when (pos? (long (get-in result
                                [:report
                                 :graphExpectationDiagnostics
                                 :failedRuns]
                                0)))
        (println "- graph-expectation-failures"
                 (get-in result [:report :graphExpectationDiagnostics :failedRuns])
                 "cases"
                 (str/join "," (get-in result
                                       [:report
                                        :graphExpectationDiagnostics
                                        :failedCaseIds]))))
      (when (seq (:failures result))
        (println "## Failures")
        (doseq [{:keys [metric operator expected actual message]} (:failures result)]
          (println "-" metric operator expected "actual" actual)
          (when message
            (println " " message)))))

    (= benchmark/agent-compare-schema (:schema result))
    (do
      (println "- status" (:status result))
      (println "- tolerance" (:tolerance result))
      (println "- aggregate-comparable" (:aggregateComparable result))
      (when (seq (:aggregateComparableReasons result))
        (println "- aggregate-comparable-reasons"
                 (str/join "," (:aggregateComparableReasons result))))
      (println "- file-recall@10"
               (format "%.2f -> %.2f"
                       (double (get-in result [:baseline :scores :fileRecallAt10] 0.0))
                       (double (get-in result [:candidate :scores :fileRecallAt10] 0.0))))
      (println "- mrr"
               (format "%.2f -> %.2f"
                       (double (get-in result [:baseline :scores :meanReciprocalRankFile] 0.0))
                       (double (get-in result [:candidate :scores :meanReciprocalRankFile] 0.0))))
      (when (seq (:regressions result))
        (println "## Regressions")
        (doseq [{:keys [metric baseline candidate delta]} (:regressions result)]
          (println "-" metric "baseline" baseline "candidate" candidate "delta" delta))))

    (= benchmark/agent-score-schema (:schema result))
    (do
      (println "- case" (:case-id result))
      (println "- agent" (get-in result [:agent :agentId]))
      (println "- mode" (get-in result [:agent :mode]))
      (println "- file-recall@10"
               (format "%.2f" (double (get-in result [:scores :fileRecallAt10] 0.0))))
      (println "- mrr"
               (format "%.2f" (double (get-in result [:scores :meanReciprocalRankFile] 0.0)))))))

(defn- enqueue-benchmark-agent-packets
  [args result]
  (assoc result
         :enqueued
         (mapv (fn [packet]
                 (queue/item-summary
                  (queue/enqueue! packet
                                  {:root (queue-root args)
                                   :kind "benchmark-agent"
                                   :project-id (:project-id packet)
                                   :priority (queue-priority args 50)})))
               (:packets result))))

(defn- bench!
  [args]
  (let [action (keyword (first args))
        bench-args (vec (rest args))
        suite-path (first (positional-args bench-args))]
    (when-not suite-path
      (throw (ex-info "Missing benchmark suite path." {:usage (usage)})))
    (let [suite (benchmark/read-suite suite-path)
          opts (bench-opts bench-args)
          result (case action
                   :prepare (benchmark/prepare-suite! suite opts)
                   :run (benchmark/run-suite! suite opts)
                   :report (benchmark/report-suite suite opts)
                   :agent-report (benchmark/report-agent-suite suite opts)
                   :agent-baseline (benchmark/agent-baselines! suite opts)
                   :agent-run (benchmark/agent-runs! suite opts)
                   :agent-check (benchmark/check-agent-suite suite opts)
                   :agent-compare (benchmark/compare-agent-report-files! suite opts)
                   :show (benchmark/show-case suite
                                              (or (:case-id opts)
                                                  (throw (ex-info "Missing --case."
                                                                  {:usage (usage)})))
                                              opts)
                   :agent-packet (let [result (benchmark/agent-packets! suite opts)]
                                   (if (enqueue-output? bench-args)
                                     (enqueue-benchmark-agent-packets bench-args result)
                                     result))
                   :agent-score (benchmark/score-agent-result!
                                 suite
                                 (first (benchmark/selected-cases
                                         suite
                                         (or (:case-id opts)
                                             (throw (ex-info "Missing --case."
                                                             {:usage (usage)})))))
                                 opts)
                   (throw (ex-info "Unknown benchmark command."
                                   {:command action
                                    :usage (usage)})))]
      (if (json-output? bench-args)
        (print-json result)
        (print-benchmark-summary result))
      (when (and (or (= benchmark/agent-check-schema (:schema result))
                     (= benchmark/agent-compare-schema (:schema result)))
                 (= "failed" (:status result)))
        (throw (ex-info "Benchmark gate failed."
                        {:schema (:schema result)
                         :suite-id (:suite-id result)
                         :status (:status result)
                         :failures (or (:failures result)
                                       (:regressions result))}))))))

(defn- print-plugin-package
  [package]
  (println "-"
           (:id package)
           (str "version=" (:version package))
           (str "extractors=" (:extractor-plugins package))
           (str "reports=" (:report-plugins package))
           (str "benchmark=" (name (or (:benchmark-status package) :unbenchmarked))))
  (when-let [source (:source package)]
    (println "  source" (:url source) (str "rev=" (:rev source))))
  (when-let [fingerprint (:manifest-fingerprint package)]
    (println "  manifest-fingerprint" fingerprint))
  (when (seq (:warnings package))
    (doseq [warning (:warnings package)]
      (println "  warning" warning))))

(defn- print-plugin-install
  [{:keys [project-id package entry force?]}]
  (println "# Plugin Installed")
  (println "- project" project-id)
  (println "- force" force?)
  (print-plugin-package package)
  (if-let [fingerprint (:manifest-fingerprint entry)]
    (println "- manifest" (:manifest entry) (str "fingerprint=" fingerprint))
    (println "- manifest" (:manifest entry)))
  (println "- path" (:path entry)))

(defn- print-plugin-list
  [{:keys [project-id packages]}]
  (println "# Plugins")
  (println "- project" project-id)
  (println "- packages" (count packages))
  (doseq [package packages]
    (print-plugin-package package)))

(defn- print-plugin-new
  [{:keys [package-id path manifest files extractor? report?]}]
  (println "# Plugin Package Created")
  (println "- package" package-id)
  (println "- path" path)
  (println "- manifest" manifest)
  (println "- extractor" extractor?)
  (println "- report" report?)
  (println "- files" (count files)))

(defn- print-plugin-validation
  [{:keys [status package extractor-plugins report-plugins warnings errors]}]
  (println "# Plugin Validation")
  (println "- status" (name status))
  (when package
    (println "- package" (:id package) (str "version=" (:version package))))
  (println "- extractors" (count extractor-plugins))
  (println "- reports" (count report-plugins))
  (doseq [warning warnings]
    (println "- warning" warning))
  (doseq [error errors]
    (println "- error" error)))

(defn- print-plugin-diagnosis
  [{:keys [status package diagnostics readiness]}]
  (println "# Plugin Diagnosis")
  (println "- status" (name status))
  (when package
    (println "- package" (:id package) (str "version=" (:version package))))
  (println "## Readiness")
  (doseq [[k {:keys [status reason next-actions]}] readiness]
    (println "-" (name k) (name status) "-" reason)
    (doseq [action next-actions]
      (println "  next" action)))
  (when (seq diagnostics)
    (println "## Diagnostics")
    (doseq [{:keys [severity code message]} diagnostics]
      (println "-" (name severity) (name code) "-" message))))

(defn- print-plugin-dry-run
  [{:keys [kind status package plugins file core-counts enhanced-counts counts diagnostics]}]
  (println "# Plugin Dry Run")
  (println "- status" (name status))
  (println "- kind" (name (or kind :extractor)))
  (println "- package" (:id package) (str "version=" (:version package)))
  (when file
    (println "- file" (:path file) (str "kind=" (name (:kind file)))))
  (println "- plugins" (str/join "," (map :id plugins)))
  (when core-counts
    (println "- core" core-counts))
  (when enhanced-counts
    (println "- enhanced" enhanced-counts))
  (when counts
    (println "- output" counts))
  (when (seq diagnostics)
    (println "## Diagnostics")
    (doseq [{:keys [stage message path]} diagnostics]
      (println "-" (str stage) path "-" message))))

(defn- print-plugin-registry-validation
  [{:keys [status path counts errors packages]}]
  (println "# Plugin Registry Validation")
  (println "- status" (name status))
  (println "- path" path)
  (println "- packages" (:packages counts))
  (println "- passed" (:passed counts))
  (println "- failed" (:failed counts))
  (doseq [{:keys [code message]} errors]
    (println "- error" (name code) "-" message))
  (doseq [{:keys [id status errors]} packages]
    (println "-" id (name status))
    (doseq [{:keys [code message]} errors]
      (println "  error" (name code) "-" message))))

(defn- plugin-new!
  [args]
  (let [dir (first (positional-args args))]
    (when-not dir
      (throw (ex-info "Missing plugin package directory."
                      {:usage (usage)})))
    (let [result (plugin-package/new!
                  dir
                  {:id (option-value args "--id")
                   :extractor? (boolean (some #{"--extractor"} args))
                   :report? (boolean (some #{"--report"} args))
                   :force? (boolean (some #{"--force"} args))})]
      (if (json-output? args)
        (print-json result)
        (print-plugin-new result)))))

(defn- plugin-validate!
  [args]
  (let [dir (first (positional-args args))]
    (when-not dir
      (throw (ex-info "Missing plugin package directory."
                      {:usage (usage)})))
    (let [result (plugin-package/validate-local dir)]
      (if (json-output? args)
        (print-json result)
        (print-plugin-validation result))
      (when (= :failed (:status result))
        (throw (ex-info "Plugin validation failed."
                        {:errors (:errors result)}))))))

(defn- plugin-diagnose!
  [args]
  (let [dir (first (positional-args args))]
    (when-not dir
      (throw (ex-info "Missing plugin package directory."
                      {:usage (usage)})))
    (let [result (plugin-package/diagnose-local dir)]
      (if (json-output? args)
        (print-json result)
        (print-plugin-diagnosis result))
      (when (= :failed (:status result))
        (throw (ex-info "Plugin diagnosis failed."
                        {:diagnostics (:diagnostics result)}))))))

(defn- plugin-dry-run!
  [args]
  (let [[kind package-dir root file] (positional-args args)]
    (when-not (#{"extractor" "report"} kind)
      (throw (ex-info "Unsupported plugin dry-run kind."
                      {:kind kind
                       :supported ["extractor" "report"]
                       :usage (usage)})))
    (when-not package-dir
      (throw (ex-info "Missing plugin dry-run package directory."
                      {:usage (usage)})))
    (when (and (= "extractor" kind)
               (not (and root file)))
      (throw (ex-info "Missing plugin dry-run repo root or file."
                      {:usage (usage)})))
    (let [opts {:plugin-id (option-value args "--plugin")}
          result (case kind
                   "extractor"
                   (plugin-package/dry-run-extractor package-dir root file opts)

                   "report"
                   (plugin-package/dry-run-report package-dir opts))]
      (if (json-output? args)
        (print-json result)
        (print-plugin-dry-run result)))))

(defn- plugin-install!
  [args]
  (let [[config-path source] (positional-args args)]
    (when-not (and config-path source)
      (throw (ex-info "Missing plugin project config path or git source."
                      {:usage (usage)})))
    (let [result (plugin-package/install!
                  config-path
                  source
                  {:ref (option-value args "--ref")
                   :subdir (option-value args "--subdir")
                   :cache-root (option-value args "--cache-dir")
                   :force? (boolean (some #{"--force"} args))})]
      (if (json-output? args)
        (print-json result)
        (print-plugin-install result)))))

(defn- plugin-list!
  [args]
  (let [config-path (first (positional-args args))]
    (when-not config-path
      (throw (ex-info "Missing plugin project config path."
                      {:usage (usage)})))
    (let [result (plugin-package/list-installed config-path)]
      (if (json-output? args)
        (print-json result)
        (print-plugin-list result)))))

(defn- plugin-registry!
  [args]
  (let [[action registry-path] (positional-args args)]
    (when-not (= "validate" action)
      (throw (ex-info "Unknown plugin registry command."
                      {:command action
                       :supported ["validate"]
                       :usage (usage)})))
    (when-not registry-path
      (throw (ex-info "Missing plugin registry path."
                      {:usage (usage)})))
    (let [result (plugin-package/validate-registry registry-path)]
      (if (json-output? args)
        (print-json result)
        (print-plugin-registry-validation result))
      (when (= :failed (:status result))
        (throw (ex-info "Plugin registry validation failed."
                        {:errors (:errors result)
                         :packages (:packages result)}))))))

(defn- plugin!
  [args]
  (let [action (first args)
        action-args (vec (rest args))]
    (case action
      "new"
      (plugin-new! action-args)

      "validate"
      (plugin-validate! action-args)

      "diagnose"
      (plugin-diagnose! action-args)

      "dry-run"
      (plugin-dry-run! action-args)

      "install"
      (plugin-install! action-args)

      "list"
      (plugin-list! action-args)

      "registry"
      (plugin-registry! action-args)

      (throw (ex-info "Unknown plugin command."
                      {:command action
                       :usage (usage)})))))

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
    "  install-agent --platform codex --project [--hooks]"
    "  install-agent list"
    "  install-agent uninstall --platform codex --project"
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
    "  ask <text> [--project ID] [--repo ID] [--limit N] [--json] [--retriever auto|hybrid|lexical|semantic] [--provider openrouter|openai] [--model MODEL] [--map PATH] [--valid-at INSTANT]"
    "  explore <text> [--project ID] [--repo ID] [--limit N] [--json] [--retriever auto|hybrid|lexical|semantic] [--provider openrouter|openai] [--model MODEL] [--map PATH] [--valid-at INSTANT]"
    "  explore create [query text] --project ID [--budget N] [--limit N] [--retriever auto|hybrid|lexical|semantic] [--provider openrouter|openai] [--model MODEL] [--map PATH] [--no-map] [--view ID] [--valid-at INSTANT] [--enqueue] [--queue-dir DIR]"
    "  explore show|open|expand|docs|search ..."
    ""
    "View and report:"
    "  view overview|deps|query|systems|clusters|cluster [args] [--project ID] [--repo ID] [--depth N] [--limit N] [--detail primary|expanded|evidence|raw] [--map PATH] [--no-map] [--view ID] [--format html|json] [--out PATH] [--valid-at INSTANT]"
    "  packages [--project ID] [--repo ID] [--ecosystem npm|cargo|go] [--package NAME] [--with-conflicts] [--without-import-evidence] [--limit N] [--json]"
    "  report <project.edn> [--map agraph.map.json] [--out agraph-out] [--detail primary|expanded|evidence|raw] [--force]"
    ""
    "Plugins:"
    "  plugin new <dir> [--id ID] [--extractor] [--report] [--force] [--json]"
    "  plugin validate <dir> [--json]"
    "  plugin diagnose <dir> [--json]"
    "  plugin dry-run extractor <dir> <repo-root> <file> [--plugin ID] [--json]"
    "  plugin dry-run report <dir> [--plugin ID] [--json]"
    "  plugin install <project.edn> <git-url-or-path> [--ref REF] [--subdir DIR] [--cache-dir DIR] [--force] [--json]"
    "  plugin list <project.edn> [--json]"
    "  plugin registry validate <registry.edn> [--json]"
    ""
    "Agent integration:"
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

    "install-agent"
    (let [action (first args)
          agent-args (if (#{"list" "uninstall"} action)
                       (vec (rest args))
                       args)
          platform (or (option-value agent-args "--platform") "codex")
          opts {:project? (boolean (some #{"--project"} agent-args))
                :hooks? (boolean (some #{"--hooks"} agent-args))
                :force? (boolean (some #{"--force"} agent-args))}]
      (case action
        "list"
        (print-json (agent-install/list-platforms))

        "uninstall"
        (print-json (agent-install/uninstall! platform opts))

        (print-json (agent-install/install! platform opts))))

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
                                   {:project-id project-id
                                    :repo-id repo-id
                                    :retriever retriever
                                    :embedding-client embedding-client
                                    :map-path (default-map-path args)
                                    :read-context temporal
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
                                                                         0.55)})))))

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
