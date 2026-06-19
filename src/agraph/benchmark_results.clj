(ns agraph.benchmark-results
  (:require [agraph.benchmark-io :as benchmark-io]
            [agraph.benchmark-paths :as benchmark-paths]
            [agraph.benchmark-progress :as benchmark-progress]
            [agraph.benchmark-suite :as benchmark-suite]
            [agraph.fs :as fs]
            [clojure.java.io :as io]))

(defn progress-summary
  [suite case opts]
  (let [path (benchmark-paths/progress-path suite case opts)]
    (when (.isFile (io/file path))
      (let [progress (benchmark-io/read-json-file path)
            events (vec (:events progress))
            completed (filter #(= "completed" (:status %)) events)
            failed (filter #(= "failed" (:status %)) events)
            last-event (last events)
            running? (= "started" (:status last-event))
            active-elapsed-ms (when running?
                                (benchmark-progress/elapsed-between-ms (:at last-event)
                                                                       (:now opts)))
            stage-rows (cond-> (->> events
                                    (keep (fn [{:keys [stage status elapsedMs]}]
                                            (when elapsedMs
                                              {:stage stage
                                               :status status
                                               :elapsedMs elapsedMs})))
                                    vec)
                         active-elapsed-ms
                         (conj {:stage (:stage last-event)
                                :status "running"
                                :elapsedMs active-elapsed-ms
                                :active? true}))
            stage-elapsed (->> stage-rows
                               (group-by :stage)
                               (map (fn [[stage rows]]
                                      {:stage stage
                                       :elapsedMs (reduce + (map :elapsedMs rows))}))
                               (sort-by :stage)
                               vec)]
        (cond-> {:case-id (:id case)
                 :repo-id (:repo-id case)
                 :path (fs/canonical-path path)
                 :status (get {"started" "running"
                               "completed" "completed"
                               "failed" "failed"}
                              (:status last-event)
                              "unknown")
                 :events (count events)
                 :completedStages (count completed)
                 :failedStages (count failed)
                 :elapsedMs (reduce + (map :elapsedMs stage-rows))
                 :stages stage-rows
                 :stageElapsedMs stage-elapsed}
          running?
          (assoc :activeStage (:stage last-event)
                 :activeElapsedMs active-elapsed-ms)

          (seq failed)
          (assoc :failedStage (:stage (last failed))))))))
(defn aggregate-progress
  [summaries]
  (let [summaries (vec summaries)
        stage-elapsed (->> summaries
                           (mapcat :stageElapsedMs)
                           (group-by :stage)
                           (map (fn [[stage rows]]
                                  {:stage stage
                                   :elapsedMs (reduce + (map :elapsedMs rows))}))
                           (sort-by :stage)
                           vec)
        slowest (->> summaries
                     (sort-by (comp - :elapsedMs))
                     (take 10)
                     (mapv #(select-keys % [:case-id
                                            :repo-id
                                            :status
                                            :activeStage
                                            :activeElapsedMs
                                            :elapsedMs
                                            :failedStage])))]
    {:cases (count summaries)
     :runningCases (count (filter #(= "running" (:status %)) summaries))
     :failedCases (count (filter #(= "failed" (:status %)) summaries))
     :elapsedMs (reduce + (map :elapsedMs summaries))
     :stageElapsedMs stage-elapsed
     :slowestCases slowest}))
(defn- case-result-file
  [suite case opts]
  (let [file (benchmark-paths/result-path suite case opts)]
    (when (.isFile file)
      file)))
(defn case-result
  "Read one case result when it exists."
  [suite case opts]
  (some-> (case-result-file suite case opts) benchmark-io/read-json-file))
(defn show-case
  "Return one case result, or its prepared artifact when no result exists."
  [suite case-id opts]
  (let [case (first (benchmark-suite/selected-cases suite case-id))
        result (case-result suite case opts)
        prepared (benchmark-paths/prepared-path suite case opts)]
    (or result
        (when (.isFile prepared)
          (benchmark-io/read-json-file prepared))
        (throw (ex-info "Benchmark case has not been prepared or run."
                        {:suite-id (:id suite)
                         :case-id case-id})))))
