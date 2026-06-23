(ns ygg.benchmark-results
  (:require [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-progress :as benchmark-progress]
            [ygg.benchmark-suite :as benchmark-suite]
            [ygg.fs :as fs]
            [clojure.java.io :as io]))

(def ^:private graph-setup-stages
  #{"index-project"
    "infer-project"
    "prepare-graph-index"})

(def ^:private case-setup-stages
  #{"prepare-worktree"
    "prepare-ground-truth"
    "write-prepared-case"})

(def ^:private agent-preparation-stages
  #{"context-packet"
    "context-related-files"
    "reuse-agent-artifacts"
    "write-agent-artifacts"
    "write-agent-project"})

(def ^:private agent-execution-stages
  #{"agent-result"})

(def ^:private scoring-stages
  #{"score-agent-result"})

(defn- stage-class
  [stage]
  (cond
    (contains? graph-setup-stages stage) "graph-setup"
    (contains? case-setup-stages stage) "case-setup"
    (contains? agent-preparation-stages stage) "agent-preparation"
    (contains? agent-execution-stages stage) "agent-execution"
    (contains? scoring-stages stage) "scoring"
    :else "other"))

(defn- stage-elapsed-total
  [rows pred]
  (reduce + 0 (keep (fn [{:keys [stage elapsedMs]}]
                      (when (pred stage)
                        elapsedMs))
                    rows)))

(defn- timing-breakdown
  [stage-elapsed]
  (let [stage-elapsed (vec stage-elapsed)
        elapsed-ms (reduce + 0 (map :elapsedMs stage-elapsed))
        graph-setup-ms (stage-elapsed-total stage-elapsed
                                            #(contains? graph-setup-stages %))
        case-setup-ms (stage-elapsed-total stage-elapsed
                                           #(contains? case-setup-stages %))
        agent-preparation-ms (stage-elapsed-total
                              stage-elapsed
                              #(contains? agent-preparation-stages %))
        amortized-setup-ms (+ graph-setup-ms agent-preparation-ms)
        agent-ready-ms (stage-elapsed-total
                        stage-elapsed
                        #(contains? agent-execution-stages %))
        scoring-ms (stage-elapsed-total stage-elapsed
                                        #(contains? scoring-stages %))]
    {:elapsedMs elapsed-ms
     :warmElapsedMs (- elapsed-ms amortized-setup-ms)
     :agentReadyElapsedMs agent-ready-ms
     :amortizedSetupElapsedMs amortized-setup-ms
     :caseSetupElapsedMs case-setup-ms
     :agentPreparationElapsedMs agent-preparation-ms
     :scoringElapsedMs scoring-ms
     :stageTiming {:basis "warmElapsedMs assumes a prepared-agent run: the Yggdrasil graph DB and agent context are already prepared, so graph setup and agent preparation are reported as amortized setup instead of counted in the primary elapsed metric."
                   :primaryElapsedMetric "warmElapsedMs"
                   :agentReadyElapsedMetric "agentReadyElapsedMs"
                   :amortizedStageClasses ["graph-setup" "agent-preparation"]
                   :classes (->> stage-elapsed
                                 (map (fn [{:keys [stage elapsedMs]}]
                                        {:stage stage
                                         :class (stage-class stage)
                                         :elapsedMs elapsedMs}))
                                 vec)}}))

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
                 :stages stage-rows
                 :stageElapsedMs stage-elapsed}
          true
          (merge (timing-breakdown stage-elapsed))

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
                                            :warmElapsedMs
                                            :agentReadyElapsedMs
                                            :amortizedSetupElapsedMs
                                            :elapsedMs
                                            :failedStage])))]
    (merge
     {:cases (count summaries)
      :runningCases (count (filter #(= "running" (:status %)) summaries))
      :failedCases (count (filter #(= "failed" (:status %)) summaries))
      :stageElapsedMs stage-elapsed
      :slowestCases slowest}
     (timing-breakdown stage-elapsed))))
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
