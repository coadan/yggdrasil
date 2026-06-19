(ns agraph.benchmark-report
  (:require [agraph.benchmark-classes :as benchmark-classes]
            [agraph.benchmark-command-telemetry :as benchmark-command-telemetry]
            [agraph.benchmark-io :as benchmark-io]
            [agraph.benchmark-paths :as benchmark-paths]
            [agraph.benchmark-prepare :as benchmark-prepare]
            [agraph.benchmark-results :as benchmark-results]
            [agraph.benchmark-score :as benchmark-score]
            [agraph.benchmark-score-artifacts :as benchmark-score-artifacts]
            [agraph.benchmark-suite :as benchmark-suite]
            [clojure.set :as set]
            [clojure.string :as str]))

(def agent-result-schema
  "agraph.benchmark.agent-result/v2")

(def agent-score-schema
  "agraph.benchmark.agent-score/v3")

(def agent-report-schema
  "agraph.benchmark.agent-report/v1")

(def ^:private problem-class-minimum-cases
  2)

(def ^:private rank-blocker-limit
  5)

(def ^:private aggregate-rank-blocker-limit
  20)

(def ^:private aggregate-ranked-file-diagnostic-limit
  20)

(defn- blankish?
  [value]
  (str/blank? (str value)))

(defn- average
  [values]
  (if (seq values)
    (/ (double (reduce + values)) (double (count values)))
    0.0))

(defn aggregate-scores
  [results]
  (let [score-keys [:fileRecallAt5
                    :fileRecallAt10
                    :fileRecallAt20
                    :meanReciprocalRankFile
                    :noiseRatioAt20
                    :evidenceCitationRate
                    :pathEvidenceCitationRate
                    :expectedEvidenceCitationRate]]
    (into {}
          (map (fn [k]
                 [k (average (keep #(get-in % [:scores k]) results))]))
          score-keys)))

(defn- sum-score
  [results k]
  (reduce + 0 (keep #(get-in % [:scores k]) results)))

(defn- aggregate-agent-scores
  [results]
  (assoc (aggregate-scores results)
         :changedFiles (sum-score results :changedFiles)
         :scoreableChangedFiles (sum-score results :scoreableChangedFiles)
         :unsupportedGroundTruthFiles (sum-score results :unsupportedGroundTruthFiles)
         :coverageExcludedGroundTruthFiles (sum-score results
                                                      :coverageExcludedGroundTruthFiles)))

(defn- input-hint-summary
  [results]
  (let [hinted (filter #(get-in % [:inputHints :hinted]) results)
        hinted-cases (->> hinted
                          (map :case-id)
                          set
                          sort
                          vec)]
    {:inputHintedRuns (count hinted)
     :inputHintedCases (count hinted-cases)
     :inputHintedCaseIds hinted-cases}))

(defn- command-telemetry
  [commands]
  (benchmark-command-telemetry/command-telemetry commands))

(defn- aggregate-command-telemetry
  [diagnostics]
  (benchmark-command-telemetry/aggregate-command-telemetry diagnostics))

(defn agent-output-diagnostic
  [result]
  (let [top-files (get-in result [:agent :topFiles])
        warnings (vec (get-in result [:agent :warnings]))
        hint-diagnostics (vec (get-in result [:agraphHints :diagnostics]))
        identity-warnings (filterv #(or (str/starts-with? % "agent result schema ")
                                        (str/starts-with? % "agent result caseId ")
                                        (str/starts-with? % "agent result caseFingerprint "))
                                   warnings)
        missing-files (vec (get-in result [:agent :missingPredictedFiles]))
        commands (vec (get-in result [:agent :commands]))
        selection (get-in result [:agent :selection])
        raw-count (long (or (get-in result [:agent :rawSuspectedFileCount])
                            (count top-files)))
        ranked-count (long (count top-files))
        command-telemetry (command-telemetry commands)
        command-count (:commandCount command-telemetry)
        candidate-count (:candidateFiles selection)
        filtered-count (long (or (:coverageFilteredCandidateFiles selection) 0))]
    (cond-> {:rawSuspectedFiles raw-count
             :rankedFiles ranked-count
             :commandCount command-count
             :commandTelemetry command-telemetry
             :missingPredictedFiles missing-files
             :warnings warnings
             :warningCount (count warnings)
             :hasWarnings (boolean (seq warnings))
             :hintDiagnostics hint-diagnostics
             :hintDiagnosticCount (count hint-diagnostics)
             :hasHintDiagnostics (boolean (seq hint-diagnostics))
             :identityWarnings identity-warnings
             :hasIdentityMismatch (boolean (seq identity-warnings))
             :emptyResult (zero? ranked-count)
             :commandless (:commandless command-telemetry)
             :noRawSuspectedFiles (zero? raw-count)}
      selection
      (assoc :selection selection)

      (some? candidate-count)
      (assoc :zeroCandidateFiles (zero? (long candidate-count)))

      (pos? filtered-count)
      (assoc :coverageFilteredCandidateFiles filtered-count))))

(defn- aggregate-hint-diagnostics
  [result-pairs]
  (let [rows (->> result-pairs
                  (mapcat (fn [[result diagnostic]]
                            (map #(assoc % :case-id (:case-id result))
                                 (:hintDiagnostics diagnostic)))))]
    {:hintDiagnosticRows (count rows)
     :hintDiagnosticRuns (count (filter (comp :hasHintDiagnostics second)
                                        result-pairs))
     :hintDiagnosticCaseIds (->> result-pairs
                                 (filter (comp :hasHintDiagnostics second))
                                 (map (comp :case-id first))
                                 distinct
                                 sort
                                 vec)
     :hintDiagnosticsByKind (->> rows
                                 (group-by :kind)
                                 (map (fn [[kind kind-rows]]
                                        {:kind kind
                                         :runs (count kind-rows)
                                         :cases (count (set (map :case-id
                                                                 kind-rows)))
                                         :caseIds (->> kind-rows
                                                       (map :case-id)
                                                       distinct
                                                       sort
                                                       vec)}))
                                 (sort-by :kind)
                                 vec)}))
(defn- aggregate-agent-diagnostics
  [results]
  (let [diagnostics (map agent-output-diagnostic results)
        result-pairs (map vector results diagnostics)
        empty-results (filter (fn [[_ diagnostic]]
                                (:emptyResult diagnostic))
                              result-pairs)
        zero-candidates (filter (fn [[_ diagnostic]]
                                  (:zeroCandidateFiles diagnostic))
                                result-pairs)
        coverage-filtered (filter (fn [[_ diagnostic]]
                                    (pos? (long (or (:coverageFilteredCandidateFiles diagnostic)
                                                    0))))
                                  result-pairs)
        missing-predicted (filter (fn [[_ diagnostic]]
                                    (seq (:missingPredictedFiles diagnostic)))
                                  result-pairs)
        commandless-results (filter (fn [[_ diagnostic]]
                                      (:commandless diagnostic))
                                    result-pairs)
        warning-results (filter (fn [[_ diagnostic]]
                                  (:hasWarnings diagnostic))
                                result-pairs)
        identity-mismatch-results (filter (fn [[_ diagnostic]]
                                            (:hasIdentityMismatch diagnostic))
                                          result-pairs)]
    (merge
     {:emptyResultRuns (count empty-results)
      :emptyResultCaseIds (->> empty-results
                               (map (comp :case-id first))
                               distinct
                               sort
                               vec)
      :zeroCandidateRuns (count zero-candidates)
      :zeroCandidateCaseIds (->> zero-candidates
                                 (map (comp :case-id first))
                                 distinct
                                 sort
                                 vec)
      :coverageFilteredRuns (count coverage-filtered)
      :coverageFilteredCaseIds (->> coverage-filtered
                                    (map (comp :case-id first))
                                    distinct
                                    sort
                                    vec)
      :coverageFilteredCandidateFiles (reduce + 0
                                              (map (comp #(long (or % 0))
                                                         :coverageFilteredCandidateFiles
                                                         second)
                                                   coverage-filtered))
      :missingPredictedFileRuns (count missing-predicted)
      :missingPredictedFileCaseIds (->> missing-predicted
                                        (map (comp :case-id first))
                                        distinct
                                        sort
                                        vec)
      :missingPredictedFiles (reduce + 0
                                     (map (comp count
                                                :missingPredictedFiles
                                                second)
                                          missing-predicted))
      :commandlessRuns (count commandless-results)
      :commandlessCaseIds (->> commandless-results
                               (map (comp :case-id first))
                               distinct
                               sort
                               vec)
      :warningRuns (count warning-results)
      :warningCaseIds (->> warning-results
                           (map (comp :case-id first))
                           distinct
                           sort
                           vec)
      :identityMismatchRuns (count identity-mismatch-results)
      :identityMismatchCaseIds (->> identity-mismatch-results
                                    (map (comp :case-id first))
                                    distinct
                                    sort
                                    vec)
      :identityMismatches (reduce + 0
                                  (map (comp count :identityWarnings second)
                                       identity-mismatch-results))
      :commandTelemetry (aggregate-command-telemetry diagnostics)
      :warnings (reduce + 0
                        (map (comp count :warnings second)
                             warning-results))}
     (aggregate-hint-diagnostics result-pairs))))
(defn- parser-worker-result-profile
  [result]
  (let [profile (:parserWorker result)
        mode (some-> (:mode profile) str not-empty)
        source (some-> (:source profile) str not-empty)]
    {:mode (or mode "unknown")
     :source (or source (if mode "unknown" "missing"))}))
(defn- parser-worker-profile-key
  [profile]
  [(:mode profile) (:source profile)])
(defn aggregate-parser-worker-profiles
  [results]
  (->> results
       (group-by (comp parser-worker-profile-key parser-worker-result-profile))
       (map (fn [[[_mode _source] rows]]
              (let [profile (parser-worker-result-profile (first rows))]
                (assoc profile
                       :runs (count rows)
                       :cases (count (set (map :case-id rows)))
                       :caseIds (->> rows
                                     (map :case-id)
                                     distinct
                                     sort
                                     vec)))))
       (sort-by (juxt :mode :source))
       vec))
(defn- artifact-diagnostic
  [expected-fingerprints result]
  (let [expected (get expected-fingerprints (:case-id result))
        actual (:caseFingerprint result)
        actual-schema (:schema result)
        schema-current? (= agent-score-schema actual-schema)
        agent-result-schema-value (get-in result [:agent :schema])
        agent-result-schema-current? (= agent-result-schema
                                        agent-result-schema-value)
        status (cond
                 (not schema-current?) "legacy"
                 (not agent-result-schema-current?) "legacy"
                 (blankish? actual) "legacy"
                 (= actual expected) "current"
                 :else "stale")]
    (cond-> {:fingerprintStatus status
             :scoreSchemaStatus (if schema-current? "current" "legacy")
             :expectedScoreSchema agent-score-schema
             :agentResultSchemaStatus (if agent-result-schema-current?
                                        "current"
                                        "legacy")
             :expectedAgentResultSchema agent-result-schema}
      actual-schema (assoc :scoreSchema actual-schema)
      agent-result-schema-value (assoc :agentResultSchema agent-result-schema-value)
      actual (assoc :caseFingerprint actual)
      expected (assoc :expectedCaseFingerprint expected))))
(defn- aggregate-artifact-diagnostics
  [expected-fingerprints results]
  (let [result-pairs (map (fn [result]
                            [result (artifact-diagnostic expected-fingerprints result)])
                          results)
        by-status (group-by (comp :fingerprintStatus second) result-pairs)
        by-schema-status (group-by (comp :scoreSchemaStatus second) result-pairs)
        by-agent-result-schema-status (group-by (comp :agentResultSchemaStatus second)
                                                result-pairs)
        case-ids (fn [status]
                   (->> (get by-status status)
                        (map (comp :case-id first))
                        distinct
                        sort
                        vec))
        schema-case-ids (fn [status]
                          (->> (get by-schema-status status)
                               (map (comp :case-id first))
                               distinct
                               sort
                               vec))
        schemas (->> (get by-schema-status "legacy")
                     (map (comp :scoreSchema second))
                     (filter some?)
                     distinct
                     sort
                     vec)
        agent-result-schema-case-ids (fn [status]
                                       (->> (get by-agent-result-schema-status status)
                                            (map (comp :case-id first))
                                            distinct
                                            sort
                                            vec))
        agent-result-schemas (->> (get by-agent-result-schema-status "legacy")
                                  (map (comp :agentResultSchema second))
                                  (filter some?)
                                  distinct
                                  sort
                                  vec)]
    {:currentScoreRuns (count (get by-status "current"))
     :legacyScoreRuns (count (get by-status "legacy"))
     :legacyScoreCaseIds (case-ids "legacy")
     :obsoleteScoreSchemaRuns (count (get by-schema-status "legacy"))
     :obsoleteScoreSchemaCaseIds (schema-case-ids "legacy")
     :obsoleteScoreSchemas schemas
     :expectedScoreSchema agent-score-schema
     :obsoleteAgentResultSchemaRuns (count (get by-agent-result-schema-status
                                                "legacy"))
     :obsoleteAgentResultSchemaCaseIds (agent-result-schema-case-ids "legacy")
     :obsoleteAgentResultSchemas agent-result-schemas
     :expectedAgentResultSchema agent-result-schema
     :staleScoreRuns (count (get by-status "stale"))
     :staleScoreCaseIds (case-ids "stale")
     :unverifiedScoreRuns (+ (count (get by-status "legacy"))
                             (count (get by-status "stale")))
     :unverifiedScoreCaseIds (vec (sort (set/union
                                         (set (case-ids "legacy"))
                                         (set (case-ids "stale")))))}))
(defn- current-score-artifact?
  [expected-fingerprints result]
  (= "current" (:fingerprintStatus (artifact-diagnostic expected-fingerprints result))))
(defn- ranked-outside-blockers
  [ranks blockers-before n]
  (->> ranks
       (filter #(and (:found? %)
                     (> (long (:rank %)) n)))
       (mapv (fn [ranked-file]
               (let [blocking-files (blockers-before (:rank ranked-file))]
                 {:path (:path ranked-file)
                  :rank (:rank ranked-file)
                  :blockingFileCount (count blocking-files)
                  :blockingFiles blocking-files})))))
(defn- artifact-policy
  [expected-fingerprints raw-results included-results allow-unverified?]
  (let [included (set included-results)
        excluded (remove included raw-results)]
    {:allowUnverifiedScores (boolean allow-unverified?)
     :matchedRuns (count raw-results)
     :includedRuns (count included-results)
     :excludedRuns (count excluded)
     :excludedCaseIds (->> excluded
                           (map :case-id)
                           distinct
                           sort
                           vec)
     :excludedUnverifiedRuns (count (remove #(current-score-artifact?
                                              expected-fingerprints
                                              %)
                                            raw-results))}))
(defn- expectation-configured?
  [result]
  (let [expectations (:expectations result)]
    (or (seq (:evidence expectations))
        (seq (:nodes expectations))
        (seq (:chunks expectations))
        (seq (:edges expectations))
        (seq (:forbidden-nodes expectations))
        (seq (:forbidden-chunks expectations))
        (seq (:forbidden-edges expectations)))))
(defn- graph-expectation-diagnostic
  [result]
  (let [graph-expectations (:graphExpectations result)]
    (cond
      graph-expectations
      {:status (:status graph-expectations)
       :summary (:summary graph-expectations)}

      (expectation-configured? result)
      {:status "not-run"
       :summary {:expectedEvidence (count (get-in result [:expectations :evidence]))
                 :expectedNodes (count (get-in result [:expectations :nodes]))
                 :expectedChunks (count (get-in result [:expectations :chunks]))
                 :expectedEdges (count (get-in result [:expectations :edges]))
                 :forbiddenNodes (count (get-in result [:expectations :forbidden-nodes]))
                 :forbiddenChunks (count (get-in result [:expectations :forbidden-chunks]))
                 :forbiddenEdges (count (get-in result [:expectations :forbidden-edges]))}}

      :else nil)))
(defn- aggregate-graph-expectation-diagnostics
  [results]
  (let [pairs (->> results
                   (keep (fn [result]
                           (when-let [diagnostic (graph-expectation-diagnostic result)]
                             [result diagnostic]))))
        by-status (group-by (comp :status second) pairs)
        case-ids (fn [status]
                   (->> (get by-status status)
                        (map (comp :case-id first))
                        distinct
                        sort
                        vec))]
    {:configuredRuns (count pairs)
     :passedRuns (count (get by-status "passed"))
     :passedCaseIds (case-ids "passed")
     :failedRuns (count (get by-status "failed"))
     :failedCaseIds (case-ids "failed")
     :notRunRuns (count (get by-status "not-run"))
     :notRunCaseIds (case-ids "not-run")}))

(defn- expected-evidence-count
  [result]
  (count (get-in result [:expectations :evidence])))

(defn- expected-evidence-citation-metric?
  [result]
  (number? (get-in result [:scores :expectedEvidenceCitationRate])))

(defn- aggregate-expectation-diagnostics
  [results]
  (let [expected-evidence-results (filter #(pos? (expected-evidence-count %))
                                          results)
        metric-results (filter expected-evidence-citation-metric?
                               expected-evidence-results)
        missing-metric-results (remove expected-evidence-citation-metric?
                                       expected-evidence-results)]
    {:runs (count results)
     :expectedEvidenceRuns (count expected-evidence-results)
     :expectedEvidenceCaseIds (->> expected-evidence-results
                                   (map :case-id)
                                   distinct
                                   sort
                                   vec)
     :expectedEvidenceTargets (reduce + 0
                                      (map expected-evidence-count
                                           expected-evidence-results))
     :expectedEvidenceCitationMetricRuns (count metric-results)
     :expectedEvidenceCitationMetricCaseIds (->> metric-results
                                                 (map :case-id)
                                                 distinct
                                                 sort
                                                 vec)
     :missingExpectedEvidenceCitationMetricRuns (count missing-metric-results)
     :missingExpectedEvidenceCitationMetricCaseIds (->> missing-metric-results
                                                        (map :case-id)
                                                        distinct
                                                        sort
                                                        vec)}))

(declare aggregate-localization-diagnostics
         aggregate-coverage-diagnostics
         report-improvement-summary)

(defn- group-agent-scores
  [expected-fingerprints results key-path]
  (->> results
       (group-by #(or (get-in % key-path) "unknown"))
       (map (fn [[k rows]]
              (let [row {:key k
                         :runs (count rows)
                         :scores (aggregate-agent-scores rows)
                         :inputHints (input-hint-summary rows)
                         :agentDiagnostics (aggregate-agent-diagnostics rows)
                         :expectationDiagnostics (aggregate-expectation-diagnostics
                                                  rows)
                         :graphExpectationDiagnostics (aggregate-graph-expectation-diagnostics rows)
                         :localizationDiagnostics (aggregate-localization-diagnostics rows)
                         :coverageDiagnostics (aggregate-coverage-diagnostics rows)
                         :artifactDiagnostics (aggregate-artifact-diagnostics
                                               expected-fingerprints
                                               rows)}]
                (assoc row :improvementSummary (report-improvement-summary row)))))
       (sort-by :key)
       vec))
(defn- result-tags
  [result]
  (->> (:tags result)
       (keep benchmark-suite/normalize-case-tag)
       distinct
       sort
       vec))
(defn- group-agent-scores-by-filtered-tag
  [expected-fingerprints results include-tag?]
  (->> results
       (mapcat (fn [result]
                 (map (fn [tag] [tag result])
                      (filter include-tag? (result-tags result)))))
       (group-by first)
       (map (fn [[tag pairs]]
              (let [rows (mapv second pairs)
                    row {:key tag
                         :cases (count (set (map :case-id rows)))
                         :runs (count rows)
                         :scores (aggregate-agent-scores rows)
                         :inputHints (input-hint-summary rows)
                         :agentDiagnostics (aggregate-agent-diagnostics rows)
                         :expectationDiagnostics (aggregate-expectation-diagnostics
                                                  rows)
                         :graphExpectationDiagnostics (aggregate-graph-expectation-diagnostics rows)
                         :localizationDiagnostics (aggregate-localization-diagnostics rows)
                         :coverageDiagnostics (aggregate-coverage-diagnostics rows)
                         :artifactDiagnostics (aggregate-artifact-diagnostics
                                               expected-fingerprints
                                               rows)}]
                (assoc row :improvementSummary (report-improvement-summary row)))))
       (sort-by :key)
       vec))
(defn- group-agent-scores-by-tag
  [expected-fingerprints results]
  (group-agent-scores-by-filtered-tag expected-fingerprints
                                      results
                                      (constantly true)))
(defn- add-problem-class-claim-status
  [row]
  (assoc row
         :minimumCases problem-class-minimum-cases
         :claimStatus (if (<= problem-class-minimum-cases (:cases row))
                        "measured"
                        "insufficient-cases")))
(defn- problem-class-summary
  [expected-fingerprints results]
  {:minimumCasesForClassClaim problem-class-minimum-cases
   :classes (mapv add-problem-class-claim-status
                  (group-agent-scores-by-filtered-tag expected-fingerprints
                                                      results
                                                      benchmark-classes/problem-class-tag?))
   :architectureClasses (mapv add-problem-class-claim-status
                              (group-agent-scores-by-filtered-tag
                               expected-fingerprints
                               results
                               benchmark-classes/architecture-class-tag?))})
(defn- measured-class-tags
  [problem-classes class-key]
  (->> (get problem-classes class-key)
       (filter #(= "measured" (:claimStatus %)))
       (keep #(some-> (:key %) str))
       sort
       vec))
(defn- report-claim-readiness
  [report]
  (let [problem-classes (:problemClasses report)
        measured-problem-tags (measured-class-tags problem-classes :classes)
        measured-architecture-tags (measured-class-tags problem-classes
                                                        :architectureClasses)
        completed? (and (pos? (long (:cases report)))
                        (= (long (:cases report))
                           (long (:completed report))))
        has-runs? (pos? (long (:runs report)))
        evidence-metrics? (and has-runs?
                               (number? (get-in report
                                                [:scores
                                                 :evidenceCitationRate]))
                               (number? (get-in report
                                                [:scores
                                                 :pathEvidenceCitationRate])))
        expected-evidence-metrics? (and has-runs?
                                        (pos?
                                         (long
                                          (get-in
                                           report
                                           [:expectationDiagnostics
                                            :expectedEvidenceCitationMetricRuns]
                                           0)))
                                        (zero?
                                         (long
                                          (get-in
                                           report
                                           [:expectationDiagnostics
                                            :missingExpectedEvidenceCitationMetricRuns]
                                           0))))
        command-telemetry? (and has-runs?
                                (map? (get-in report
                                              [:agentDiagnostics
                                               :commandTelemetry])))
        requirements {:completedCases completed?
                      :hasRuns has-runs?
                      :measuredProblemClasses (boolean (seq measured-problem-tags))
                      :measuredArchitectureClasses (boolean
                                                    (seq measured-architecture-tags))
                      :evidenceCitationMetrics evidence-metrics?
                      :expectedEvidenceCitationMetrics expected-evidence-metrics?
                      :commandTelemetry command-telemetry?}
        supported? (every? true? (vals requirements))]
    {:status (if supported? "supported" "not-supported")
     :broadArchitectureClaimSupported supported?
     :measuredProblemClassTags measured-problem-tags
     :measuredArchitectureClassTags measured-architecture-tags
     :requirements requirements
     :warnings (cond-> []
                 (not completed?)
                 (conj "Not all selected cases completed; do not use this report for broad benchmark claims.")

                 (not has-runs?)
                 (conj "No agent score runs are included in this report.")

                 (empty? measured-problem-tags)
                 (conj "No measured problem-class groups; include enough cases per class before claiming representative gains.")

                 (empty? measured-architecture-tags)
                 (conj "No measured architecture-class groups; architecture tags are present only below the class-claim threshold or absent.")

                 (not evidence-metrics?)
                 (conj "Evidence citation metrics are unavailable; citation quality is unproven.")

                 (not expected-evidence-metrics?)
                 (conj "Expected-evidence citation metrics are unavailable or incomplete; non-code help quality is unproven.")

                 (not command-telemetry?)
                 (conj "Command telemetry is unavailable; shell/search/read-loop costs are unproven."))}))
(defn- improvement-row
  [{:keys [kind area runs case-ids message details]}]
  (when (pos? (long (or runs 0)))
    (cond-> {:kind kind
             :area area
             :runs runs
             :caseIds (vec case-ids)
             :message message}
      (seq details) (assoc :details details))))
(defn- hint-diagnostic-details
  [agent-diagnostics]
  (->> (:hintDiagnosticsByKind agent-diagnostics)
       (mapv #(select-keys % [:kind :runs :cases :caseIds]))))
(defn- hint-diagnostic-detail
  [hint-details kind]
  (first (filter #(= kind (:kind %)) hint-details)))
(defn- report-improvement-summary
  [report]
  (let [agent-diagnostics (:agentDiagnostics report)
        expectation-diagnostics (:expectationDiagnostics report)
        localization (:localizationDiagnostics report)
        coverage (:coverageDiagnostics report)
        graph-expectations (:graphExpectationDiagnostics report)
        artifacts (:artifactDiagnostics report)
        hint-details (hint-diagnostic-details agent-diagnostics)
        audit-scope-trust-boundary (hint-diagnostic-detail
                                    hint-details
                                    "audit-scope-trust-boundary")]
    (vec
     (keep identity
           [(improvement-row
             {:kind "missed-files-absent-from-context"
              :area "extraction-or-retrieval"
              :runs (:missedAndAbsentFromContextRuns localization)
              :case-ids (:missedAndAbsentFromContextCaseIds localization)
              :message "Scoreable files were missed and were not present in the AGraph context ranks."})
            (improvement-row
             {:kind "missed-files-present-in-context"
              :area "ranking-or-context-budget"
              :runs (:missedButPresentInContextRuns localization)
              :case-ids (:missedButPresentInContextCaseIds localization)
              :message "Scoreable files were available in context but not selected by the agent result."})
            (improvement-row
             {:kind "missing-context-ranks"
              :area "benchmark-hygiene"
              :runs (:contextRankMissingRuns localization)
              :case-ids (:contextRankMissingCaseIds localization)
              :message "AGraph-mode score artifacts did not include context ground-truth ranks, so benchmark attribution is weaker."})
            (improvement-row
             {:kind "ranked-outside-top5"
              :area "ranking-or-context-budget"
              :runs (:rankedOutsideTop5Runs localization)
              :case-ids (:rankedOutsideTop5CaseIds localization)
              :message "Scoreable files were ranked below the top five suspected files."})
            (improvement-row
             {:kind "path-citation-gaps"
              :area "agent-use-and-citation"
              :runs (:pathUncitedRuns localization)
              :case-ids (:pathUncitedCaseIds localization)
              :message "Ranked files lacked path-level evidence citations."})
            (improvement-row
             {:kind "expected-evidence-citation-metric-gaps"
              :area "benchmark-hygiene"
              :runs (:missingExpectedEvidenceCitationMetricRuns
                     expectation-diagnostics)
              :case-ids (:missingExpectedEvidenceCitationMetricCaseIds
                         expectation-diagnostics)
              :message "Benchmark cases declared expected evidence without scored expected-evidence citation metrics."})
            (improvement-row
             {:kind "missing-declared-source-kinds"
              :area "coverage-declarations"
              :runs (:missingDeclaredSourceKindRuns coverage)
              :case-ids (:missingDeclaredSourceKindCaseIds coverage)
              :message "Benchmark cases declared source kinds with no scoreable indexed files."
              :details (:missingDeclaredSourceKinds coverage)})
            (improvement-row
             {:kind "coverage-excluded-ground-truth"
              :area "coverage-declarations"
              :runs (:coverageExcludedGroundTruthRuns coverage)
              :case-ids (:coverageExcludedGroundTruthCaseIds coverage)
              :message "Ground-truth files were excluded by benchmark coverage declarations."})
            (improvement-row
             {:kind "hint-diagnostics"
              :area "agent-context-quality"
              :runs (:hintDiagnosticRuns agent-diagnostics)
              :case-ids (:hintDiagnosticCaseIds agent-diagnostics)
              :message "AGraph hints contained diagnostics that should be inspected before trusting benchmark outcomes."
              :details hint-details})
            (improvement-row
             {:kind "audit-scope-trust-boundary"
              :area "audit-scope-quality"
              :runs (:runs audit-scope-trust-boundary)
              :case-ids (:caseIds audit-scope-trust-boundary)
              :message "Audit-scope hints reported skipped files, extractor diagnostics, or unclassified rows that weaken source-family claims."
              :details (when audit-scope-trust-boundary
                         [audit-scope-trust-boundary])})
            (improvement-row
             {:kind "coverage-filtered-candidates"
              :area "agent-context-quality"
              :runs (:coverageFilteredRuns agent-diagnostics)
              :case-ids (:coverageFilteredCaseIds agent-diagnostics)
              :message "Coverage declarations filtered candidate files out of the agent shortlist."})
            (improvement-row
             {:kind "graph-expectation-failures"
              :area "graph-fact-quality"
              :runs (:failedRuns graph-expectations)
              :case-ids (:failedCaseIds graph-expectations)
              :message "Configured graph expectations failed for these benchmark runs."})
            (improvement-row
             {:kind "commandless-runs"
              :area "agent-use-and-citation"
              :runs (:commandlessRuns agent-diagnostics)
              :case-ids (:commandlessCaseIds agent-diagnostics)
              :message "Agent results did not record any commands, making replay quality weaker."})
            (improvement-row
             {:kind "warning-runs"
              :area "agent-result-shape"
              :runs (:warningRuns agent-diagnostics)
              :case-ids (:warningCaseIds agent-diagnostics)
              :message "Agent result validation produced warnings."})
            (improvement-row
             {:kind "unverified-score-artifacts"
              :area "benchmark-hygiene"
              :runs (:unverifiedScoreRuns artifacts)
              :case-ids (:unverifiedScoreCaseIds artifacts)
              :message "Score artifacts were stale or legacy relative to current benchmark fingerprints."})]))))
(defn- group-agent-scores-by-parser-worker
  [expected-fingerprints results]
  (->> results
       (group-by (comp parser-worker-profile-key parser-worker-result-profile))
       (map (fn [[[_mode _source] rows]]
              (let [profile (parser-worker-result-profile (first rows))
                    row (assoc profile
                               :key (str (:mode profile) "/" (:source profile))
                               :runs (count rows)
                               :cases (count (set (map :case-id rows)))
                               :scores (aggregate-agent-scores rows)
                               :inputHints (input-hint-summary rows)
                               :agentDiagnostics (aggregate-agent-diagnostics rows)
                               :expectationDiagnostics (aggregate-expectation-diagnostics
                                                        rows)
                               :graphExpectationDiagnostics (aggregate-graph-expectation-diagnostics
                                                             rows)
                               :localizationDiagnostics (aggregate-localization-diagnostics rows)
                               :coverageDiagnostics (aggregate-coverage-diagnostics rows)
                               :artifactDiagnostics (aggregate-artifact-diagnostics
                                                     expected-fingerprints
                                                     rows))]
                (assoc row :improvementSummary (report-improvement-summary row)))))
       (sort-by (juxt :mode :source))
       vec))
(defn- aggregate-case-tags
  [cases]
  (let [pairs (mapcat (fn [case]
                        (map (fn [tag] [tag (:id case)])
                             (benchmark-suite/case-tags case)))
                      cases)]
    {:tags (->> pairs (map first) distinct sort vec)
     :casesByTag (->> pairs
                      (group-by first)
                      (map (fn [[tag rows]]
                             {:tag tag
                              :cases (count (set (map second rows)))
                              :caseIds (->> rows
                                            (map second)
                                            distinct
                                            sort
                                            vec)}))
                      (sort-by :tag)
                      vec)}))
(defn- coverage-cases
  [results]
  (->> results
       (group-by :case-id)
       vals
       (keep first)))
(defn- aggregate-coverage
  [results]
  (let [cases (coverage-cases results)
        source-rows (mapcat #(get-in % [:coverage :scoreableFilesByKind]) cases)
        declared (->> cases
                      (mapcat #(get-in % [:coverage :declaredSourceKinds]))
                      distinct
                      sort
                      vec)
        missing (->> cases
                     (mapcat (fn [case]
                               (map (fn [kind]
                                      {:case-id (:case-id case)
                                       :kind kind})
                                    (get-in case
                                            [:coverage :missingDeclaredSourceKinds]))))
                     (sort-by (juxt :kind :case-id))
                     vec)
        case-kinds (->> cases
                        (mapcat (fn [case]
                                  (map (fn [kind]
                                         [kind (:case-id case)])
                                       (get-in case [:coverage :scoreableSourceKinds]))))
                        (group-by first))
        files-by-kind (->> source-rows
                           (group-by :kind)
                           (map (fn [[kind rows]]
                                  {:kind kind
                                   :cases (count (set (map second
                                                           (get case-kinds kind))))
                                   :scoreableFiles (reduce + (map :files rows))}))
                           (sort-by :kind)
                           vec)]
    {:declaredSourceKinds declared
     :scoreableSourceKinds (vec (sort (map :kind files-by-kind)))
     :scoreableFilesByKind files-by-kind
     :missingDeclaredSourceKinds missing}))
(defn- aggregate-kind-case-rows
  [rows]
  (->> rows
       (group-by :kind)
       (map (fn [[kind kind-rows]]
              {:kind kind
               :runs (count kind-rows)
               :cases (count (set (map :case-id kind-rows)))
               :caseIds (->> kind-rows
                             (map :case-id)
                             distinct
                             sort
                             vec)}))
       (sort-by :kind)
       vec))
(defn- aggregate-coverage-diagnostics
  [results]
  (let [missing-source-kind-rows (->> results
                                      (mapcat
                                       (fn [result]
                                         (map (fn [kind]
                                                {:case-id (:case-id result)
                                                 :kind kind})
                                              (get-in result
                                                      [:coverage :missingDeclaredSourceKinds])))))
        coverage-excluded (filter #(seq (get-in % [:groundTruth :coverageExcludedFiles]))
                                  results)
        unsupported (filter #(seq (get-in % [:groundTruth :unsupportedGroundTruthFiles]))
                            results)]
    {:runs (count results)
     :missingDeclaredSourceKindRuns (count missing-source-kind-rows)
     :missingDeclaredSourceKindCaseIds (->> missing-source-kind-rows
                                            (map :case-id)
                                            distinct
                                            sort
                                            vec)
     :missingDeclaredSourceKinds (aggregate-kind-case-rows
                                  missing-source-kind-rows)
     :coverageExcludedGroundTruthRuns (count coverage-excluded)
     :coverageExcludedGroundTruthCaseIds (->> coverage-excluded
                                              (map :case-id)
                                              distinct
                                              sort
                                              vec)
     :coverageExcludedGroundTruthFiles (reduce + 0
                                               (map #(count (get-in %
                                                                    [:groundTruth
                                                                     :coverageExcludedFiles]))
                                                    coverage-excluded))
     :unsupportedGroundTruthRuns (count unsupported)
     :unsupportedGroundTruthCaseIds (->> unsupported
                                         (map :case-id)
                                         distinct
                                         sort
                                         vec)
     :unsupportedGroundTruthFiles (reduce + 0
                                          (map #(count (get-in %
                                                               [:groundTruth
                                                                :unsupportedGroundTruthFiles]))
                                               unsupported))}))
(defn- localization-diagnostic
  [result]
  (let [ground-truth (:groundTruth result)
        ranks (get-in result [:groundTruthRanks :files])
        context-ranks (get-in result [:contextGroundTruthRanks :files])
        context-rank-by-path (into {} (map (juxt :path identity)) context-ranks)
        top-files (get-in result [:agent :topFiles])
        scoreable-file-set (set (benchmark-score/scoreable-changed-files ground-truth))
        uncited-ranked-files (->> top-files
                                  (remove benchmark-score/evidence-cited?)
                                  (mapv #(select-keys % [:path :rank])))
        path-uncited-ranked-files (->> top-files
                                       (remove benchmark-score/path-evidence-cited?)
                                       (mapv #(select-keys % [:path :rank])))
        missed (->> ranks
                    (remove :found?)
                    (mapv #(select-keys % [:path])))
        missed-present-in-context (->> missed
                                       (keep (fn [{:keys [path]}]
                                               (when-let [row (get context-rank-by-path path)]
                                                 (when (:found? row)
                                                   (select-keys row [:path :rank])))))
                                       vec)
        missed-absent-from-context (when (seq context-ranks)
                                     (->> missed
                                          (remove #(get-in context-rank-by-path
                                                           [(:path %) :found?]))
                                          (mapv #(select-keys % [:path]))))
        blocker-summary (fn [row]
                          (select-keys row
                                       [:path
                                        :rank
                                        :confidence
                                        :metrics]))
        blockers-before (fn [rank]
                          (->> top-files
                               (filter #(and (:rank %)
                                             (< (long (:rank %)) (long rank))))
                               (remove #(contains? scoreable-file-set (:path %)))
                               (sort-by (juxt :rank :path))
                               (take rank-blocker-limit)
                               (mapv blocker-summary)))
        ranked-outside (fn [n]
                         (->> ranks
                              (filter #(and (:found? %)
                                            (> (long (:rank %)) n)))
                              (mapv #(select-keys % [:path :rank]))))
        diagnostic {:scoreableFiles (benchmark-score/scoreable-changed-files ground-truth)
                    :coverageExcludedFiles (vec (:coverageExcludedFiles ground-truth))
                    :unsupportedGroundTruthFiles (vec (:unsupportedGroundTruthFiles ground-truth))
                    :ranks ranks
                    :uncitedRankedFiles uncited-ranked-files
                    :pathUncitedRankedFiles path-uncited-ranked-files
                    :missedFiles missed
                    :rankedOutsideTop5 (ranked-outside 5)
                    :rankedOutsideTop10 (ranked-outside 10)
                    :rankedOutsideTop20 (ranked-outside 20)
                    :rankedOutsideTop5Blockers (ranked-outside-blockers
                                                ranks
                                                blockers-before
                                                5)
                    :rankedOutsideTop10Blockers (ranked-outside-blockers
                                                 ranks
                                                 blockers-before
                                                 10)
                    :rankedOutsideTop20Blockers (ranked-outside-blockers
                                                 ranks
                                                 blockers-before
                                                 20)}]
    (if (seq context-ranks)
      (assoc diagnostic
             :contextRanks context-ranks
             :missedFilesPresentInContext missed-present-in-context
             :missedFilesAbsentFromContext missed-absent-from-context)
      diagnostic)))
(defn- aggregate-rank-blockers
  [result-pairs blocker-key]
  (let [rows (mapcat
              (fn [[result diagnostic]]
                (map (fn [blocking-file]
                       (assoc blocking-file :case-id (:case-id result)))
                     (mapcat :blockingFiles (get diagnostic blocker-key))))
              result-pairs)]
    (->> rows
         (group-by :path)
         (map (fn [[path path-rows]]
                (cond-> {:path path
                         :occurrences (count path-rows)
                         :runs (count (set (map :case-id path-rows)))
                         :bestRank (apply min (map :rank path-rows))}
                  (:metrics (first path-rows))
                  (assoc :metrics (:metrics (first path-rows))))))
         (sort-by (juxt (comp - :occurrences)
                        :bestRank
                        :path))
         (take aggregate-rank-blocker-limit)
         vec)))
(defn- aggregate-ranked-file-diagnostics
  [result-pairs diagnostic-key]
  (let [rows (mapcat
              (fn [[result diagnostic]]
                (map (fn [ranked-file]
                       (assoc ranked-file :case-id (:case-id result)))
                     (get diagnostic diagnostic-key)))
              result-pairs)]
    (->> rows
         (group-by :path)
         (map (fn [[path path-rows]]
                (let [ranks (keep :rank path-rows)]
                  (cond-> {:path path
                           :occurrences (count path-rows)
                           :runs (count (set (map :case-id path-rows)))
                           :caseIds (->> path-rows
                                         (map :case-id)
                                         set
                                         sort
                                         vec)}
                    (seq ranks) (assoc :bestRank (apply min ranks))))))
         (sort-by (juxt (comp - :occurrences)
                        #(or (:bestRank %) Long/MAX_VALUE)
                        :path))
         (take aggregate-ranked-file-diagnostic-limit)
         vec)))
(defn- aggregate-localization-diagnostics
  [results]
  (let [diagnostics (map localization-diagnostic results)
        result-pairs (map vector results diagnostics)
        case-ids (fn [pairs]
                   (->> pairs
                        (map (comp :case-id first))
                        distinct
                        sort
                        vec))
        all-found (filter (fn [[_ diagnostic]]
                            (empty? (:missedFiles diagnostic)))
                          result-pairs)
        missed (filter (fn [[_ diagnostic]]
                         (seq (:missedFiles diagnostic)))
                       result-pairs)
        outside-top5 (filter (fn [[_ diagnostic]]
                               (seq (:rankedOutsideTop5 diagnostic)))
                             result-pairs)
        outside-top10 (filter (fn [[_ diagnostic]]
                                (seq (:rankedOutsideTop10 diagnostic)))
                              result-pairs)
        outside-top20 (filter (fn [[_ diagnostic]]
                                (seq (:rankedOutsideTop20 diagnostic)))
                              result-pairs)
        path-uncited (filter (fn [[_ diagnostic]]
                               (seq (:pathUncitedRankedFiles diagnostic)))
                             result-pairs)
        missing-context-ranks (filter (fn [[result diagnostic]]
                                        (and (= "agraph" (str (get-in result
                                                                      [:agent :mode])))
                                             (not (seq (:contextRanks diagnostic)))))
                                      result-pairs)
        missed-present-in-context (filter (fn [[_ diagnostic]]
                                            (seq (:missedFilesPresentInContext diagnostic)))
                                          result-pairs)
        missed-absent-from-context (filter (fn [[_ diagnostic]]
                                             (seq (:missedFilesAbsentFromContext diagnostic)))
                                           result-pairs)]
    {:runs (count results)
     :allScoreableFoundRuns (count all-found)
     :allScoreableFoundCaseIds (case-ids all-found)
     :missedRuns (count missed)
     :missedCaseIds (case-ids missed)
     :contextRankMissingRuns (count missing-context-ranks)
     :contextRankMissingCaseIds (case-ids missing-context-ranks)
     :missedButPresentInContextRuns (count missed-present-in-context)
     :missedButPresentInContextCaseIds (case-ids missed-present-in-context)
     :missedAndAbsentFromContextRuns (count missed-absent-from-context)
     :missedAndAbsentFromContextCaseIds (case-ids missed-absent-from-context)
     :rankedOutsideTop5Runs (count outside-top5)
     :rankedOutsideTop5CaseIds (case-ids outside-top5)
     :rankedOutsideTop10Runs (count outside-top10)
     :rankedOutsideTop10CaseIds (case-ids outside-top10)
     :rankedOutsideTop20Runs (count outside-top20)
     :rankedOutsideTop20CaseIds (case-ids outside-top20)
     :pathUncitedRuns (count path-uncited)
     :pathUncitedCaseIds (case-ids path-uncited)
     :pathUncitedRankedFiles (aggregate-ranked-file-diagnostics
                              result-pairs
                              :pathUncitedRankedFiles)
     :rankedOutsideTop5BlockingFiles (aggregate-rank-blockers
                                      result-pairs
                                      :rankedOutsideTop5Blockers)
     :rankedOutsideTop10BlockingFiles (aggregate-rank-blockers
                                       result-pairs
                                       :rankedOutsideTop10Blockers)
     :rankedOutsideTop20BlockingFiles (aggregate-rank-blockers
                                       result-pairs
                                       :rankedOutsideTop20Blockers)}))
(defn report-agent-suite
  "Aggregate existing agent score artifacts."
  [suite opts]
  (let [cases (benchmark-suite/selected-cases suite (benchmark-suite/case-selector opts))
        expected-fingerprints (into {}
                                    (map (fn [case]
                                           [(:id case) (benchmark-prepare/case-fingerprint suite case)]))
                                    cases)
        progress (->> cases
                      (keep #(benchmark-results/progress-summary suite % opts))
                      vec)
        progress-by-case (into {} (map (juxt :case-id identity)) progress)
        raw-results (vec
                     (mapcat (fn [case]
                               (let [case-progress (get progress-by-case (:id case))]
                                 (map #(cond-> %
                                         case-progress (assoc :progress case-progress))
                                      (benchmark-score-artifacts/agent-score-results suite case opts))))
                             cases))
        allow-unverified? (:allow-unverified-scores? opts)
        results (if allow-unverified?
                  raw-results
                  (filter #(current-score-artifact? expected-fingerprints %)
                          raw-results))
        completed-cases (set (map :case-id results))
        missing (->> cases
                     (remove #(contains? completed-cases (:id %)))
                     (mapv :id))
        report-base {:schema agent-report-schema
                     :suite-id (:id suite)
                     :cases (count cases)
                     :completed (count completed-cases)
                     :runs (count results)
                     :missing missing
                     :scores (aggregate-agent-scores results)
                     :parserWorkers (aggregate-parser-worker-profiles results)
                     :inputHints (input-hint-summary results)
                     :agentDiagnostics (aggregate-agent-diagnostics results)
                     :expectationDiagnostics (aggregate-expectation-diagnostics
                                              results)
                     :graphExpectationDiagnostics (aggregate-graph-expectation-diagnostics
                                                   results)
                     :localizationDiagnostics (aggregate-localization-diagnostics results)
                     :coverageDiagnostics (aggregate-coverage-diagnostics results)
                     :artifactDiagnostics (aggregate-artifact-diagnostics
                                           expected-fingerprints
                                           raw-results)
                     :artifactPolicy (artifact-policy expected-fingerprints
                                                      raw-results
                                                      results
                                                      allow-unverified?)
                     :coverage (aggregate-coverage results)
                     :tags (aggregate-case-tags cases)
                     :problemClasses (problem-class-summary expected-fingerprints
                                                            results)
                     :timings (benchmark-results/aggregate-progress progress)
                     :caseProgress progress
                     :byMode (group-agent-scores expected-fingerprints
                                                 results
                                                 [:agent :mode])
                     :byAgent (group-agent-scores expected-fingerprints
                                                  results
                                                  [:agent :agentId])
                     :byParserWorker (group-agent-scores-by-parser-worker
                                      expected-fingerprints
                                      results)
                     :byTag (group-agent-scores-by-tag expected-fingerprints
                                                       results)
                     :results (mapv #(assoc (select-keys % [:case-id
                                                            :repo-id
                                                            :baseSha
                                                            :fixSha
                                                            :caseFingerprint
                                                            :tags
                                                            :expectations
                                                            :graphExpectations
                                                            :inputHints
                                                            :coverage
                                                            :agentResultPath
                                                            :parserWorker
                                                            :agent
                                                            :progress
                                                            :scores])
                                            :localization
                                            (localization-diagnostic %)
                                            :agentOutput
                                            (agent-output-diagnostic %)
                                            :artifact
                                            (artifact-diagnostic expected-fingerprints %))
                                    results)}
        report-base (assoc report-base
                           :improvementSummary
                           (report-improvement-summary report-base))
        report (assoc report-base
                      :claimReadiness (report-claim-readiness report-base))]
    (benchmark-io/write-json-file! (benchmark-paths/agent-report-path suite opts) report)
    report))
