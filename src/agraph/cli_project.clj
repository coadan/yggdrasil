(ns agraph.cli-project
  (:require [agraph.affected :as affected]
            [agraph.cli-options :refer [json-output? option-value parse-double-option positional-args]]
            [agraph.evidence :as evidence]
            [agraph.index :as index]
            [agraph.map :as graph-map]
            [agraph.project :as project]
            [agraph.xtdb :as store]
            [clojure.string :as str]))

(def ^:dynamic *deps* {})

(defn- call-dep
  [k & args]
  (apply (or (get *deps* k)
             (throw (ex-info "Missing CLI project dependency." {:dependency k})))
         args))

(defn- usage [] (call-dep :usage))
(defn- print-json [data] (call-dep :print-json data))
(defn- default-map-path [args] (call-dep :default-map-path args))
(defn- temporal-options [args] (call-dep :temporal-options args))

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
(defn print-project-status!
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
(defn print-project-index-summary
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
(defn print-project-add-repo-summary
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
(defn print-source-coverage
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
(defn print-audit-scope-report
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
(defn affected!
  ([args]
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
  ([args deps]
   (binding [*deps* deps]
     (affected! args))))
(defn print-sync-summary
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
(defn print-system-summary
  [{:keys [project-id status system-evidence system-nodes system-edges search-docs]}]
  (println "# System Graph")
  (println "- project" project-id)
  (println "- status" (name status))
  (println "- evidence" system-evidence)
  (println "- nodes" system-nodes)
  (println "- edges" system-edges)
  (println "- search-docs" search-docs))
(defn print-map-summary
  [path {:keys [schema project systems reject edges packageImports]}]
  (println "# Graph Map")
  (println "- schema" schema)
  (println "- project" project)
  (println "- systems" (count systems))
  (println "- rejects" (count reject))
  (println "- edges" (count edges))
  (println "- package-imports" (count packageImports))
  (println "- output" path))
(defn print-map-system
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
