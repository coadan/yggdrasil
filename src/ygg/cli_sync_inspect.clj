(ns ygg.cli-sync-inspect
  (:require [ygg.cli-options :refer [json-output?]]
            [ygg.corrections :as corrections]
            [ygg.daemon-contract :as daemon-contract]
            [ygg.evidence :as evidence]
            [ygg.project :as project]
            [ygg.xtdb :as store]
            [clojure.string :as str])
  (:import [java.util.logging LogManager]))

(defn- silence-jul!
  []
  (.reset (LogManager/getLogManager)))

(defn project-inspect-result
  [xtdb project {:keys [config-path]}]
  (let [overlay (when (store/xtdb-handle? xtdb)
                  (corrections/overlay xtdb (:id project)))
        evidence-summary (evidence/summarize xtdb
                                             project
                                             {:correction-overlay overlay
                                              :config-path (or config-path
                                                               (:path project))
                                              :summary? true})
        freshness (:freshness evidence-summary)]
    {:schema "ygg.project.inspect/v1"
     :project {:id (:id project)
               :name (:name project)
               :config-path (or config-path (:path project))}
     :repos (mapv #(select-keys % [:id :root :role]) (:repos project))
     :freshness freshness
     :freshnessCounts (:counts freshness)
     :repoFreshness (mapv (fn [repo]
                            (cond-> {:id (:repo-id repo)
                                     :status (:status repo)
                                     :counts (:counts repo)}
                              (:samples repo) (assoc :samples (:samples repo))))
                          (:repos freshness))
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

(defn print-project-inspect
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

(defn print-project-status-result!
  [result args deps]
  (if (json-output? args)
    ((:print-json deps) result)
    (print-project-inspect result)))

(defn print-project-status-on-node-with-deps!
  ([xtdb config-path args deps]
   (when-not config-path
     (throw (ex-info "Missing project config path." {:usage ((:usage deps))})))
   (print-project-status-on-node-with-deps!
    xtdb
    (project/read-project config-path)
    config-path
    args
    deps))
  ([xtdb project config-path args deps]
   (let [result (project-inspect-result xtdb
                                        project
                                        {:config-path config-path})]
     (print-project-status-result! result args deps))))

(defn print-project-status-with-deps!
  ([config-path args deps]
   (when-not config-path
     (throw (ex-info "Missing project config path." {:usage ((:usage deps))})))
   (print-project-status-with-deps! (project/read-project config-path) config-path args deps))
  ([project config-path args deps]
   (store/with-node (store/storage-path (:id project))
     (fn [xtdb]
       (print-project-status-on-node-with-deps! xtdb project config-path args deps)))))

(defn direct-main-response
  [_args]
  (daemon-contract/direct-entrypoint-response "ygg.cli-sync-inspect" "ygg sync inspect"))

(defn -main
  [& args]
  (silence-jul!)
  (daemon-contract/exit! (direct-main-response (vec args))))
