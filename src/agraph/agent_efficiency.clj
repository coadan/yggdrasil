(ns agraph.agent-efficiency
  "Compare shell-only and AGraph-assisted agent benchmark reports."
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema "agraph.agent-efficiency/v1")

(def ^:private metric-specs
  [{:key :fileRecallAt5
    :label "fileRecallAt5"
    :category :localization
    :path [:scores :fileRecallAt5]
    :direction :higher}
   {:key :fileRecallAt10
    :label "fileRecallAt10"
    :category :localization
    :path [:scores :fileRecallAt10]
    :direction :higher}
   {:key :fileRecallAt20
    :label "fileRecallAt20"
    :category :localization
    :path [:scores :fileRecallAt20]
    :direction :higher}
   {:key :meanReciprocalRankFile
    :label "meanReciprocalRankFile"
    :category :localization
    :path [:scores :meanReciprocalRankFile]
    :direction :higher}
   {:key :missedRuns
    :label "missedRuns"
    :category :localization
    :path [:localizationDiagnostics :missedRuns]
    :direction :lower}
   {:key :rankedOutsideTop5Runs
    :label "rankedOutsideTop5Runs"
    :category :localization
    :path [:localizationDiagnostics :rankedOutsideTop5Runs]
    :direction :lower}
   {:key :rankedOutsideTop10Runs
    :label "rankedOutsideTop10Runs"
    :category :localization
    :path [:localizationDiagnostics :rankedOutsideTop10Runs]
    :direction :lower}
   {:key :noiseRatioAt20
    :label "noiseRatioAt20"
    :category :noise
    :path [:scores :noiseRatioAt20]
    :direction :lower}
   {:key :missingPredictedFileRuns
    :label "missingPredictedFileRuns"
    :category :noise
    :path [:agentDiagnostics :missingPredictedFileRuns]
    :direction :lower}
   {:key :evidenceCitationRate
    :label "evidenceCitationRate"
    :category :evidence
    :path [:scores :evidenceCitationRate]
    :direction :higher}
   {:key :pathEvidenceCitationRate
    :label "pathEvidenceCitationRate"
    :category :evidence
    :path [:scores :pathEvidenceCitationRate]
    :direction :higher}
   {:key :emptyResultRuns
    :label "emptyResultRuns"
    :category :result-health
    :path [:agentDiagnostics :emptyResultRuns]
    :direction :lower}
   {:key :commandlessRuns
    :label "commandlessRuns"
    :category :result-health
    :path [:agentDiagnostics :commandlessRuns]
    :direction :lower}
   {:key :warningRuns
    :label "warningRuns"
    :category :result-health
    :path [:agentDiagnostics :warningRuns]
    :direction :lower}
   {:key :elapsedMs
    :label "elapsedMs"
    :category :timing
    :path [:timings :elapsedMs]
    :direction :lower
    :tolerance 50.0}
   {:key :failedCases
    :label "failedCases"
    :category :timing
    :path [:timings :failedCases]
    :direction :lower}
   {:key :runningCases
    :label "runningCases"
    :category :timing
    :path [:timings :runningCases]
    :direction :lower}])

(def ^:private case-score-specs
  (->> metric-specs
       (filter #(= :scores (first (:path %))))
       vec))

(def ^:private group-metric-specs
  (->> metric-specs
       (remove #(= :timing (:category %)))
       vec))

(defn- read-json-file
  [path]
  (json/read-json (slurp (io/file path)) :key-fn keyword))

(defn- write-json-file!
  [path value]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file (str (json/write-json-str value {:indent-str "  "}) "\n"))
    (.getPath file)))

(defn- metric-value
  [m path]
  (let [value (get-in m path)]
    (when (number? value)
      (double value))))

(defn- result-label
  [effect]
  (cond
    (pos? effect) "improved"
    (neg? effect) "regressed"
    :else "unchanged"))

(defn- effective-effect
  [effect tolerance]
  (if (<= (abs effect) (double (or tolerance 0.0)))
    0.0
    effect))

(defn- metric-delta
  [shell-report agraph-report {:keys [category direction key label path tolerance]}]
  (let [shell-value (metric-value shell-report path)
        agraph-value (metric-value agraph-report path)
        available? (and (some? shell-value)
                        (some? agraph-value))]
    (if-not available?
      {:key key
       :metric label
       :category (name category)
       :direction (name direction)
       :shellOnly shell-value
       :agraph agraph-value
       :available false
       :result "unavailable"}
      (let [delta (- agraph-value shell-value)
            effect (case direction
                     :higher delta
                     :lower (- delta))
            effective (effective-effect effect tolerance)]
        (cond-> {:key key
                 :metric label
                 :category (name category)
                 :direction (name direction)
                 :shellOnly shell-value
                 :agraph agraph-value
                 :available true
                 :delta delta
                 :effect effective
                 :result (result-label effective)}
          tolerance (assoc :rawEffect effect
                           :tolerance tolerance))))))

(defn- result-by-case
  [report]
  (->> (:results report)
       (map (juxt :case-id identity))
       (into {})))

(defn- sorted-case-ids
  [report]
  (->> (:results report)
       (map :case-id)
       set
       sort
       vec))

(defn- count-results
  [deltas result]
  (count (filter #(= result (:result %)) deltas)))

(defn- report-modes
  [report]
  (->> (:results report)
       (map #(get-in % [:agent :mode]))
       (remove nil?)
       frequencies
       (into (sorted-map))))

(defn- report-agents
  [report]
  (->> (:results report)
       (map #(get-in % [:agent :agentId]))
       (remove nil?)
       frequencies
       (into (sorted-map))))

(defn- report-summary
  [report]
  {:schema (:schema report)
   :suiteId (:suite-id report)
   :cases (:cases report)
   :completed (:completed report)
   :runs (:runs report)
   :missing (:missing report)
   :modes (report-modes report)
   :agents (report-agents report)
   :scores (:scores report)
   :localizationDiagnostics (:localizationDiagnostics report)
   :agentDiagnostics (:agentDiagnostics report)
   :tags (:tags report)
   :timings (:timings report)})

(defn- rows-by-key
  [rows]
  (->> rows
       (keep (fn [row]
               (when-let [key (some-> (:key row) str not-empty)]
                 [key row])))
       (into {})))

(defn- comparability
  [shell-report agraph-report]
  (let [shell-cases (set (sorted-case-ids shell-report))
        agraph-cases (set (sorted-case-ids agraph-report))
        shell-only-cases (sort (remove agraph-cases shell-cases))
        agraph-only-cases (sort (remove shell-cases agraph-cases))
        shared-cases (sort (filter agraph-cases shell-cases))
        same-suite? (= (:suite-id shell-report) (:suite-id agraph-report))
        same-cases? (and (empty? shell-only-cases)
                         (empty? agraph-only-cases))]
    {:sameSuite same-suite?
     :sameCases same-cases?
     :sharedCases (count shared-cases)
     :sharedCaseIds (vec shared-cases)
     :shellOnlyCaseIds (vec shell-only-cases)
     :agraphOnlyCaseIds (vec agraph-only-cases)
     :warnings (cond-> []
                 (not same-suite?)
                 (conj "Reports use different suite ids.")

                 (not same-cases?)
                 (conj "Reports do not contain the same completed case ids.")

                 (empty? shared-cases)
                 (conj "Reports have no shared completed cases."))}))

(defn- aggregate-summary
  [deltas comparable]
  (let [improved (count-results deltas "improved")
        regressed (count-results deltas "regressed")
        unchanged (count-results deltas "unchanged")
        unavailable (count-results deltas "unavailable")
        signal (cond
                 (zero? (:sharedCases comparable)) "not-comparable"
                 (and (pos? improved) (zero? regressed)) "agraph-improved"
                 (and (zero? improved) (pos? regressed)) "agraph-regressed"
                 (and (pos? improved) (pos? regressed)) "mixed"
                 :else "unchanged")]
    {:signal signal
     :improvedMetrics improved
     :regressedMetrics regressed
     :unchangedMetrics unchanged
     :unavailableMetrics unavailable}))

(defn- tag-comparability
  [shell-report agraph-report]
  (let [shell-tags (set (keys (rows-by-key (:byTag shell-report))))
        agraph-tags (set (keys (rows-by-key (:byTag agraph-report))))
        shell-only-tags (sort (remove agraph-tags shell-tags))
        agraph-only-tags (sort (remove shell-tags agraph-tags))
        shared-tags (sort (filter agraph-tags shell-tags))
        same-tags? (and (empty? shell-only-tags)
                        (empty? agraph-only-tags))]
    {:sameTags same-tags?
     :sharedTags (count shared-tags)
     :sharedTagKeys (vec shared-tags)
     :shellOnlyTagKeys (vec shell-only-tags)
     :agraphOnlyTagKeys (vec agraph-only-tags)
     :warnings (cond-> []
                 (not same-tags?)
                 (conj "Reports do not contain the same completed tag groups.")

                 (empty? shared-tags)
                 (conj "Reports have no shared completed tag groups."))}))

(defn- tag-deltas
  [shell-report agraph-report]
  (let [shell-by-tag (rows-by-key (:byTag shell-report))
        agraph-by-tag (rows-by-key (:byTag agraph-report))
        shared-tags (->> (keys shell-by-tag)
                         (filter #(contains? agraph-by-tag %))
                         sort)]
    (mapv (fn [tag]
            (let [shell-row (get shell-by-tag tag)
                  agraph-row (get agraph-by-tag tag)
                  deltas (mapv #(metric-delta shell-row agraph-row %)
                               group-metric-specs)]
              {:tag tag
               :shellOnly {:cases (long (or (:cases shell-row) 0))
                           :runs (long (or (:runs shell-row) 0))}
               :agraph {:cases (long (or (:cases agraph-row) 0))
                        :runs (long (or (:runs agraph-row) 0))}
               :summary (aggregate-summary deltas {:sharedCases 1})
               :deltas deltas}))
          shared-tags)))

(defn- by-tag-comparison
  [shell-report agraph-report]
  {:comparability (tag-comparability shell-report agraph-report)
   :groups (tag-deltas shell-report agraph-report)})

(defn- case-deltas
  [shell-report agraph-report]
  (let [shell-by-case (result-by-case shell-report)
        agraph-by-case (result-by-case agraph-report)
        shared-case-ids (->> (keys shell-by-case)
                             (filter #(contains? agraph-by-case %))
                             sort)]
    (mapv (fn [case-id]
            (let [deltas (mapv #(metric-delta (get shell-by-case case-id)
                                              (get agraph-by-case case-id)
                                              %)
                               case-score-specs)]
              {:caseId case-id
               :summary (aggregate-summary deltas {:sharedCases 1})
               :deltas deltas}))
          shared-case-ids)))

(defn compare-reports
  "Return a shell-only vs AGraph efficiency comparison from two agent reports."
  [shell-report agraph-report]
  (let [comparable (comparability shell-report agraph-report)
        deltas (mapv #(metric-delta shell-report agraph-report %) metric-specs)
        summary (aggregate-summary deltas comparable)]
    {:schema schema
     :status (:signal summary)
     :suiteId (or (:suite-id agraph-report)
                  (:suite-id shell-report))
     :summary summary
     :comparability comparable
     :shellOnly (report-summary shell-report)
     :agraph (report-summary agraph-report)
     :deltas deltas
     :byTag (by-tag-comparison shell-report agraph-report)
     :caseDeltas (case-deltas shell-report agraph-report)}))

(defn compare-report-files!
  "Read two agent-report JSON files and optionally write the comparison."
  [shell-report-path agraph-report-path opts]
  (let [comparison (-> (read-json-file shell-report-path)
                       (compare-reports (read-json-file agraph-report-path))
                       (assoc-in [:inputs :shellReport] shell-report-path)
                       (assoc-in [:inputs :agraphReport] agraph-report-path))]
    (when-let [out (:out opts)]
      (write-json-file! out comparison))
    comparison))

(defn- option-value
  [args flag]
  (let [idx (.indexOf args flag)]
    (when-not (neg? idx)
      (nth args (inc idx)))))

(defn- flag?
  [args flag]
  (some #{flag} args))

(defn- positional-args
  [args]
  (vec (take-while #(not (str/starts-with? % "--")) args)))

(defn- usage
  []
  (str "Usage: bb efficiency <shell-agent-report.json> <agraph-agent-report.json>"
       " [--out report.json] [--json]"))

(defn -main
  [& args]
  (let [positions (positional-args args)
        shell-report-path (first positions)
        agraph-report-path (second positions)]
    (when-not (and shell-report-path agraph-report-path)
      (throw (ex-info "Missing report paths." {:usage (usage)})))
    (let [comparison (compare-report-files!
                      shell-report-path
                      agraph-report-path
                      {:out (option-value args "--out")})]
      (if (flag? args "--json")
        (println (json/write-json-str comparison {:indent-str "  "}))
        (do
          (println (str "Agent efficiency: " (:status comparison)))
          (println (str "Improved metrics: "
                        (get-in comparison [:summary :improvedMetrics])
                        ", regressed metrics: "
                        (get-in comparison [:summary :regressedMetrics])
                        ", unchanged metrics: "
                        (get-in comparison [:summary :unchangedMetrics])
                        ", unavailable metrics: "
                        (get-in comparison [:summary :unavailableMetrics])))
          (println (str "Shared tag groups: "
                        (get-in comparison [:byTag :comparability :sharedTags])))
          (when-let [out (option-value args "--out")]
            (println (str "Wrote " out))))))))
