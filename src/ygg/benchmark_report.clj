(ns ygg.benchmark-report
  (:require [ygg.benchmark-audit-scope :as benchmark-audit-scope]
            [ygg.benchmark-classes :as benchmark-classes]
            [ygg.benchmark-command-telemetry :as benchmark-command-telemetry]
            [ygg.benchmark-agent-score :as benchmark-agent-score]
            [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-preflight :as benchmark-preflight]
            [ygg.benchmark-prepare :as benchmark-prepare]
            [ygg.benchmark-results :as benchmark-results]
            [ygg.benchmark-score :as benchmark-score]
            [ygg.benchmark-score-artifacts :as benchmark-score-artifacts]
            [ygg.benchmark-context-artifacts :as benchmark-context-artifacts]
            [ygg.benchmark-suite :as benchmark-suite]
            [ygg.benchmark-system-improvement :as benchmark-system-improvement]
            [ygg.benchmark-util :as benchmark-util]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def agent-result-schema
  "ygg.benchmark.agent-result/v2")

(def agent-score-schema
  "ygg.benchmark.agent-score/v3")

(def agent-report-schema
  "ygg.benchmark.agent-report/v1")

(def ^:private problem-class-minimum-cases
  2)

(def ^:private rank-blocker-limit
  5)

(def ^:private aggregate-rank-blocker-limit
  20)

(def ^:private aggregate-ranked-file-diagnostic-limit
  20)

(def ^:private aggregate-score-keys
  [:fileRecallAt5
   :fileRecallAt10
   :fileRecallAt20
   :meanReciprocalRankFile
   :noiseRatioAt20
   :evidenceCitationRate
   :pathEvidenceCitationRate
   :expectedEvidenceCitationRate])

(def ^:private aggregate-decision-score-keys
  [:decisionRecall
   :decisionPrecision
   :decisionF1
   :decisionEvidenceCitationRate])

(defn- average
  [values]
  (if (seq values)
    (/ (double (reduce + values)) (double (count values)))
    0.0))

(defn aggregate-scores
  [results]
  (let [score-keys (cond-> aggregate-score-keys
                     (some #(some (fn [k]
                                    (number? (get-in % [:scores k])))
                                  aggregate-decision-score-keys)
                           results)
                     (into aggregate-decision-score-keys))]
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

(defn- aggregate-token-telemetry
  [diagnostics]
  (benchmark-command-telemetry/aggregate-token-telemetry diagnostics))

(defn- aggregate-context-artifact-telemetry
  [results]
  (benchmark-context-artifacts/aggregate-context-artifact-telemetry results))

(defn agent-output-diagnostic
  [result]
  (let [top-files (get-in result [:agent :topFiles])
        warnings (vec (get-in result [:agent :warnings]))
        hint-diagnostics (vec (get-in result [:yggHints :diagnostics]))
        identity-warnings (filterv #(or (str/starts-with? % "agent result schema ")
                                        (str/starts-with? % "agent result caseId ")
                                        (str/starts-with? % "agent result caseFingerprint ")
                                        (str/starts-with? % "agent result agentInputFingerprint "))
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
        filtered-count (long (or (:coverageFilteredCandidateFiles selection) 0))
        token-usage (get-in result [:agent :tokenUsage])]
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
      (assoc :coverageFilteredCandidateFiles filtered-count)

      token-usage
      (assoc :tokenUsage token-usage))))

(defn- blocking-hint-diagnostic?
  [row]
  (not= "info" (str (:severity row))))

(defn- hint-diagnostics-by-kind
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

(defn- hint-diagnostic-case-ids
  [rows]
  (->> rows
       (map :case-id)
       distinct
       sort
       vec))

(defn- aggregate-hint-diagnostics
  [result-pairs]
  (let [rows (->> result-pairs
                  (mapcat (fn [[result diagnostic]]
                            (map #(assoc % :case-id (:case-id result))
                                 (:hintDiagnostics diagnostic)))))
        blocking-rows (filterv blocking-hint-diagnostic? rows)]
    {:hintDiagnosticRows (count rows)
     :hintDiagnosticRuns (count (filter (comp :hasHintDiagnostics second)
                                        result-pairs))
     :hintDiagnosticCaseIds (->> result-pairs
                                 (filter (comp :hasHintDiagnostics second))
                                 (map (comp :case-id first))
                                 distinct
                                 sort
                                 vec)
     :hintDiagnosticsByKind (hint-diagnostics-by-kind rows)
     :blockingHintDiagnosticRows (count blocking-rows)
     :blockingHintDiagnosticRuns (count (hint-diagnostic-case-ids blocking-rows))
     :blockingHintDiagnosticCaseIds (hint-diagnostic-case-ids blocking-rows)
     :blockingHintDiagnosticsByKind (hint-diagnostics-by-kind blocking-rows)}))
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
                                          result-pairs)
        token-usage-results (filter (fn [[_ diagnostic]]
                                      (:tokenUsage diagnostic))
                                    result-pairs)
        missing-token-usage-results (remove (fn [[_ diagnostic]]
                                              (:tokenUsage diagnostic))
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
      :tokenUsageRuns (count token-usage-results)
      :tokenUsageCaseIds (->> token-usage-results
                              (map (comp :case-id first))
                              distinct
                              sort
                              vec)
      :missingTokenUsageRuns (count missing-token-usage-results)
      :missingTokenUsageCaseIds (->> missing-token-usage-results
                                     (map (comp :case-id first))
                                     distinct
                                     sort
                                     vec)
      :warnings (reduce + 0
                        (map (comp count :warnings second)
                             warning-results))}
     (when-let [token-telemetry (aggregate-token-telemetry diagnostics)]
       {:tokenTelemetry token-telemetry})
     (when-let [context-artifact-telemetry (aggregate-context-artifact-telemetry
                                            results)]
       {:contextArtifactTelemetry context-artifact-telemetry})
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
(defn- agent-report-context
  [suite cases]
  {:expected-fingerprints (into {}
                                (map (fn [case]
                                       [(:id case)
                                        (benchmark-prepare/case-fingerprint suite case)]))
                                cases)
   :expected-agent-input-fingerprints (into {}
                                            (map (fn [case]
                                                   [(:id case)
                                                    (benchmark-prepare/agent-input-fingerprint
                                                     suite
                                                     case)]))
                                            cases)})

(defn- artifact-diagnostic
  [{:keys [expected-fingerprints expected-agent-input-fingerprints]} result]
  (let [expected (get expected-fingerprints (:case-id result))
        expected-agent-input (get expected-agent-input-fingerprints (:case-id result))
        actual (:caseFingerprint result)
        actual-schema (:schema result)
        schema-current? (= agent-score-schema actual-schema)
        agent-result-schema-value (get-in result [:agent :schema])
        agent-result-schema-current? (= agent-result-schema
                                        agent-result-schema-value)
        contract-version (:agentResultContractVersion result)
        contract-current? (= benchmark-agent-score/agent-result-contract-version
                             contract-version)
        agent-input-fingerprint (get-in result [:agent :agentInputFingerprint])
        agent-input-status (cond
                             (not contract-current?) "legacy"
                             (benchmark-util/blankish? agent-input-fingerprint) "stale"
                             (= expected-agent-input agent-input-fingerprint) "current"
                             :else "stale")
        status (cond
                 (not schema-current?) "legacy"
                 (not agent-result-schema-current?) "legacy"
                 (not contract-current?) "legacy"
                 (benchmark-util/blankish? actual) "legacy"
                 (not= "current" agent-input-status) "stale"
                 (= actual expected) "current"
                 :else "stale")]
    (cond-> {:fingerprintStatus status
             :scoreSchemaStatus (if schema-current? "current" "legacy")
             :expectedScoreSchema agent-score-schema
             :agentResultSchemaStatus (if agent-result-schema-current?
                                        "current"
                                        "legacy")
             :expectedAgentResultSchema agent-result-schema
             :agentResultContractStatus (if contract-current? "current" "legacy")
             :expectedAgentResultContractVersion
             benchmark-agent-score/agent-result-contract-version
             :agentInputFingerprintStatus agent-input-status}
      actual-schema (assoc :scoreSchema actual-schema)
      agent-result-schema-value (assoc :agentResultSchema agent-result-schema-value)
      contract-version (assoc :agentResultContractVersion contract-version)
      agent-input-fingerprint (assoc :agentInputFingerprint agent-input-fingerprint)
      expected-agent-input (assoc :expectedAgentInputFingerprint expected-agent-input)
      actual (assoc :caseFingerprint actual)
      expected (assoc :expectedCaseFingerprint expected))))
(defn- aggregate-artifact-diagnostics
  [report-context results]
  (let [result-pairs (map (fn [result]
                            [result (artifact-diagnostic report-context result)])
                          results)
        by-status (group-by (comp :fingerprintStatus second) result-pairs)
        by-schema-status (group-by (comp :scoreSchemaStatus second) result-pairs)
        by-agent-result-schema-status (group-by (comp :agentResultSchemaStatus second)
                                                result-pairs)
        by-contract-status (group-by (comp :agentResultContractStatus second)
                                     result-pairs)
        by-agent-input-status (group-by (comp :agentInputFingerprintStatus second)
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
        contract-case-ids (fn [status]
                            (->> (get by-contract-status status)
                                 (map (comp :case-id first))
                                 distinct
                                 sort
                                 vec))
        agent-input-case-ids (fn [status]
                               (->> (get by-agent-input-status status)
                                    (map (comp :case-id first))
                                    distinct
                                    sort
                                    vec))
        agent-result-schemas (->> (get by-agent-result-schema-status "legacy")
                                  (map (comp :agentResultSchema second))
                                  (filter some?)
                                  distinct
                                  sort
                                  vec)
        agent-result-contracts (->> (get by-contract-status "legacy")
                                    (map (comp :agentResultContractVersion second))
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
     :obsoleteAgentResultContractRuns (count (get by-contract-status "legacy"))
     :obsoleteAgentResultContractCaseIds (contract-case-ids "legacy")
     :obsoleteAgentResultContractVersions agent-result-contracts
     :expectedAgentResultContractVersion
     benchmark-agent-score/agent-result-contract-version
     :staleAgentInputRuns (count (get by-agent-input-status "stale"))
     :staleAgentInputCaseIds (agent-input-case-ids "stale")
     :staleScoreRuns (count (get by-status "stale"))
     :staleScoreCaseIds (case-ids "stale")
     :unverifiedScoreRuns (+ (count (get by-status "legacy"))
                             (count (get by-status "stale")))
     :unverifiedScoreCaseIds (vec (sort (set/union
                                         (set (case-ids "legacy"))
                                         (set (case-ids "stale")))))}))
(defn- current-score-artifact?
  [report-context result]
  (= "current"
     (:fingerprintStatus (artifact-diagnostic report-context result))))
(defn- ranked-outside-blockers
  [ranks blockers-before n]
  (->> ranks
       (filter #(and (:found? %)
                     (> (long (:rank %)) n)))
       (mapv (fn [ranked-file]
               (let [blocking-files (blockers-before (:rank ranked-file))]
                 (cond-> {:path (:path ranked-file)
                          :rank (:rank ranked-file)
                          :blockingFileCount (count blocking-files)
                          :blockingFiles blocking-files}
                   (:repo-id ranked-file)
                   (assoc :repo-id (:repo-id ranked-file))))))))
(defn- artifact-policy
  [report-context raw-results included-results allow-unverified?]
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
                                              report-context
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

(def ^:private preflight-checks
  [:index :infer :graphExpectations :hintDiagnostics :syncCheck])

(defn- ygg-result?
  [result]
  (= "ygg" (str (get-in result [:agent :mode]))))

(defn- read-json-artifact
  [file]
  (when (and file (.isFile (io/file file)))
    (try
      (benchmark-io/read-json-file file)
      (catch Exception _
        nil))))

(defn- case-artifact-dir
  [result]
  (when-let [result-path (some-> (:agentResultPath result) io/file)]
    (some-> result-path .getParentFile .getParentFile)))

(defn- sibling-artifact
  [result dir suffix]
  (when-let [case-dir (case-artifact-dir result)]
    (io/file case-dir
             dir
             (str (benchmark-paths/safe-id (or (get-in result [:agent :agentId])
                                               "agent"))
                  suffix))))

(defn- sibling-agent-hints
  [result]
  (read-json-artifact (sibling-artifact result
                                        "agent-contexts"
                                        ".ygg-hints.json")))

(defn- sibling-agent-run
  [result]
  (read-json-artifact (sibling-artifact result "agent-runs" ".json")))

(defn- result-hints
  [result]
  (or (sibling-agent-hints result)
      (:yggHints result)))

(defn- result-maintenance-preflight
  [result]
  (or (:maintenancePreflight result)
      (when (ygg-result? result)
        (let [agent-run (sibling-agent-run result)]
          (benchmark-preflight/maintenance-preflight
           {:index-summary (or (get-in result [:ygg :indexSummary])
                               (get-in agent-run [:ygg :indexSummary]))
            :system-summary (or (get-in result [:ygg :systemSummary])
                                (get-in agent-run [:ygg :systemSummary]))
            :graph-expectations (:graphExpectations result)
            :expectations (:expectations result)
            :hints (result-hints result)
            :sync-inspect (or (:syncInspect result)
                              (:syncInspect agent-run)
                              (get-in agent-run [:ygg :syncInspect]))})))))

(defn- preflight-status
  [preflight]
  (str (or (:status preflight) "not-run")))

(defn- preflight-pass?
  [preflight]
  (= "passed" (preflight-status preflight)))

(defn- preflight-case-ids
  [pairs status]
  (->> pairs
       (filter #(= status (preflight-status (second %))))
       (map (comp :case-id first))
       distinct
       sort
       vec))

(defn- check-case-ids
  [pairs check-key pred]
  (->> pairs
       (filter (fn [[_ preflight]]
                 (pred (get-in preflight [:checks check-key]))))
       (map (comp :case-id first))
       distinct
       sort
       vec))

(defn- reason-name
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) "validation-gap"
    :else (str value)))

(defn- sync-check-gap-diagnostic-rows
  [pairs]
  (->> pairs
       (mapcat
        (fn [[result preflight]]
          (let [case-id (:case-id result)]
            (mapcat
             (fn [gap]
               (let [diagnostics (seq (filter #(not= false (:blocking %))
                                              (:diagnostics gap)))
                     base {:caseId case-id
                           :plane (str (:plane gap))
                           :status (str (:status gap))}]
                 (if diagnostics
                   (map (fn [diagnostic]
                          (cond-> (assoc base
                                         :reason (reason-name
                                                  (:reason diagnostic)))
                            (:message diagnostic)
                            (assoc :message (:message diagnostic))
                            (number? (:count diagnostic))
                            (assoc :count (:count diagnostic))))
                        diagnostics)
                   [(assoc base :reason "validation-gap")])))
             (get-in preflight
                     [:checks :syncCheck :blockingValidationGaps])))))
       vec))

(defn- sync-check-blocking-reasons
  [pairs]
  (->> (sync-check-gap-diagnostic-rows pairs)
       (group-by (juxt :plane :status :reason :message))
       (mapv
        (fn [[[plane status reason message] rows]]
          (let [case-ids (->> rows (map :caseId) distinct sort vec)
                total-count (reduce + 0 (keep :count rows))]
            (cond-> {:plane plane
                     :status status
                     :reason reason
                     :runs (count case-ids)
                     :caseIds case-ids}
              message (assoc :message message)
              (pos? total-count) (assoc :count total-count)))))
       (sort-by (juxt :plane :status :reason))
       vec))

(defn- check-status-summary
  [pairs check-key]
  (let [status (fn [preflight]
                 (str (or (get-in preflight [:checks check-key :status])
                          "not-run")))
        by-status (group-by (comp status second) pairs)
        passed? (fn [check]
                  (benchmark-preflight/check-passed? check))
        failed? (fn [check]
                  (= "failed" (str (:status check))))
        not-run? (fn [check]
                   (= "not-run" (str (:status check))))]
    (cond-> {:check (name check-key)
             :status (cond
                       (seq (get by-status "failed")) "failed"
                       (seq (get by-status "not-run")) "not-run"
                       :else "passed")
             :passedRuns (count (filter (comp passed?
                                              #(get-in % [1 :checks check-key]))
                                        pairs))
             :failedRuns (count (get by-status "failed"))
             :failedCaseIds (check-case-ids pairs check-key failed?)
             :notRunRuns (count (get by-status "not-run"))
             :notRunCaseIds (check-case-ids pairs check-key not-run?)}
      (= :syncCheck check-key)
      (assoc :blockingReasons (sync-check-blocking-reasons pairs)))))

(defn- aggregate-maintenance-preflight
  [results]
  (let [pairs (->> results
                   (filter ygg-result?)
                   (map (fn [result]
                          [result (result-maintenance-preflight result)]))
                   vec)
        by-status (group-by (comp preflight-status second) pairs)
        required? (seq pairs)
        status (cond
                 (not required?) "not-applicable"
                 (seq (get by-status "failed")) "failed"
                 (seq (get by-status "not-run")) "not-run"
                 :else "passed")
        blocked-pairs (remove (comp preflight-pass? second) pairs)]
    {:status status
     :requiredForClaim (boolean required?)
     :runs (count pairs)
     :passedRuns (count (get by-status "passed"))
     :passedCaseIds (preflight-case-ids pairs "passed")
     :failedRuns (count (get by-status "failed"))
     :failedCaseIds (preflight-case-ids pairs "failed")
     :notRunRuns (count (get by-status "not-run"))
     :notRunCaseIds (preflight-case-ids pairs "not-run")
     :blockedRuns (count blocked-pairs)
     :blockedCaseIds (->> blocked-pairs
                          (map (comp :case-id first))
                          distinct
                          sort
                          vec)
     :checks (mapv #(check-status-summary pairs %) preflight-checks)}))

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

(defn- decision-diagnostic
  [result]
  (when-let [decision (:decisionScoring result)]
    (select-keys decision
                 [:configured
                  :kind
                  :candidateIds
                  :requiredChoiceIds
                  :forbiddenChoiceIds
                  :includedChoiceIds
                  :excludedChoiceIds
                  :deferredChoiceIds
                  :unknownChoiceIds
                  :matchedRequiredChoiceIds
                  :missedChoiceIds
                  :wrongIncludedChoiceIds
                  :deferredRequiredChoiceIds
                  :uncitedChoiceIds
                  :missingDecision])))

(defn- decision-gap?
  [diagnostic]
  (or (:missingDecision diagnostic)
      (seq (:missedChoiceIds diagnostic))
      (seq (:wrongIncludedChoiceIds diagnostic))
      (seq (:uncitedChoiceIds diagnostic))
      (seq (:unknownChoiceIds diagnostic))))

(defn- decision-choice-gap-rows
  [result-pairs key kind]
  (->> result-pairs
       (mapcat (fn [[result diagnostic]]
                 (map (fn [id]
                        {:id id
                         :kind kind
                         :case-id (:case-id result)})
                      (get diagnostic key))))
       (group-by (juxt :kind :id))
       (mapv (fn [[[kind id] rows]]
               {:kind kind
                :id id
                :runs (count rows)
                :caseIds (->> rows
                              (map :case-id)
                              distinct
                              sort
                              vec)}))
       (sort-by (juxt :kind :id))))

(defn- aggregate-decision-diagnostics
  [results]
  (let [result-pairs (->> results
                          (keep (fn [result]
                                  (when-let [diagnostic (decision-diagnostic result)]
                                    [result diagnostic])))
                          vec)
        case-ids (fn [pairs]
                   (->> pairs
                        (map (comp :case-id first))
                        distinct
                        sort
                        vec))
        missing (filter (comp :missingDecision second) result-pairs)
        missed (filter (comp seq :missedChoiceIds second) result-pairs)
        wrong (filter (comp seq :wrongIncludedChoiceIds second) result-pairs)
        uncited (filter (comp seq :uncitedChoiceIds second) result-pairs)
        unknown (filter (comp seq :unknownChoiceIds second) result-pairs)
        gaps (filter (comp decision-gap? second) result-pairs)]
    {:configuredRuns (count result-pairs)
     :configuredCaseIds (case-ids result-pairs)
     :gapRuns (count gaps)
     :gapCaseIds (case-ids gaps)
     :missingDecisionRuns (count missing)
     :missingDecisionCaseIds (case-ids missing)
     :missedDecisionRuns (count missed)
     :missedDecisionCaseIds (case-ids missed)
     :wrongDecisionRuns (count wrong)
     :wrongDecisionCaseIds (case-ids wrong)
     :uncitedDecisionRuns (count uncited)
     :uncitedDecisionCaseIds (case-ids uncited)
     :unknownDecisionChoiceRuns (count unknown)
     :unknownDecisionChoiceCaseIds (case-ids unknown)
     :choiceGaps (vec
                  (concat
                   (decision-choice-gap-rows result-pairs :missedChoiceIds "missed")
                   (decision-choice-gap-rows result-pairs
                                             :wrongIncludedChoiceIds
                                             "wrong-included")
                   (decision-choice-gap-rows result-pairs :uncitedChoiceIds "uncited")
                   (decision-choice-gap-rows result-pairs :unknownChoiceIds "unknown")))}))

(declare aggregate-localization-diagnostics
         aggregate-coverage-diagnostics
         report-improvement-summary)

(defn- group-agent-scores
  [report-context results key-path]
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
                         :maintenancePreflightDiagnostics (aggregate-maintenance-preflight
                                                           rows)
                         :decisionDiagnostics (aggregate-decision-diagnostics rows)
                         :localizationDiagnostics (aggregate-localization-diagnostics rows)
                         :coverageDiagnostics (aggregate-coverage-diagnostics rows)
                         :artifactDiagnostics (aggregate-artifact-diagnostics
                                               report-context
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
  [report-context results include-tag?]
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
                         :maintenancePreflightDiagnostics (aggregate-maintenance-preflight
                                                           rows)
                         :decisionDiagnostics (aggregate-decision-diagnostics rows)
                         :localizationDiagnostics (aggregate-localization-diagnostics rows)
                         :coverageDiagnostics (aggregate-coverage-diagnostics rows)
                         :artifactDiagnostics (aggregate-artifact-diagnostics
                                               report-context
                                               rows)}]
                (assoc row :improvementSummary (report-improvement-summary row)))))
       (sort-by :key)
       vec))
(defn- group-agent-scores-by-tag
  [report-context results]
  (group-agent-scores-by-filtered-tag report-context
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
  [report-context results]
  {:minimumCasesForClassClaim problem-class-minimum-cases
   :classes (mapv add-problem-class-claim-status
                  (group-agent-scores-by-filtered-tag report-context
                                                      results
                                                      benchmark-classes/problem-class-tag?))
   :architectureClasses (mapv add-problem-class-claim-status
                              (group-agent-scores-by-filtered-tag
                               report-context
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
        decision-configured? (pos?
                              (long
                               (get-in report
                                       [:decisionDiagnostics :configuredRuns]
                                       0)))
        decision-metrics? (or (not decision-configured?)
                              (and (number? (get-in report [:scores :decisionF1]))
                                   (zero?
                                    (long
                                     (get-in report
                                             [:decisionDiagnostics
                                              :missingDecisionRuns]
                                             0)))))
        maintenance-preflight (:maintenancePreflightDiagnostics report)
        maintenance-preflight? (or (not (:requiredForClaim maintenance-preflight))
                                   (= "passed" (:status maintenance-preflight)))
        requirements {:completedCases completed?
                      :hasRuns has-runs?
                      :measuredProblemClasses (boolean (seq measured-problem-tags))
                      :measuredArchitectureClasses (boolean
                                                    (seq measured-architecture-tags))
                      :evidenceCitationMetrics evidence-metrics?
                      :expectedEvidenceCitationMetrics expected-evidence-metrics?
                      :decisionQualityMetrics decision-metrics?
                      :commandTelemetry command-telemetry?
                      :maintenancePreflight maintenance-preflight?}
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

                 (not decision-metrics?)
                 (conj "Decision-quality metrics are configured but incomplete; complex-system decision claims are unproven.")

                 (not command-telemetry?)
                 (conj "Command telemetry is unavailable; shell/search/read-loop costs are unproven.")

                 (not maintenance-preflight?)
                 (conj "Yggdrasil maintenance preflight did not pass; index, inference, graph expectations, hint diagnostics, and sync/check-equivalent status must pass before making maintained-graph claims."))}))
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
(defn- blocking-hint-diagnostic-details
  [agent-diagnostics]
  (->> (:blockingHintDiagnosticsByKind agent-diagnostics)
       (mapv #(select-keys % [:kind :runs :cases :caseIds]))))
(defn- hint-diagnostic-detail
  [hint-details kind]
  (first (filter #(= kind (:kind %)) hint-details)))

(defn- blocking-preflight-checks
  [maintenance-preflight]
  (->> (:checks maintenance-preflight)
       (filter (fn [{:keys [failedRuns notRunRuns]}]
                 (pos? (+ (long (or failedRuns 0))
                          (long (or notRunRuns 0))))))
       (mapv #(select-keys % [:check
                              :status
                              :failedRuns
                              :failedCaseIds
                              :notRunRuns
                              :notRunCaseIds]))))

(defn- preflight-check
  [maintenance-preflight check-name]
  (first (filter #(= check-name (:check %))
                 (:checks maintenance-preflight))))

(defn- report-improvement-summary
  [report]
  (let [agent-diagnostics (:agentDiagnostics report)
        expectation-diagnostics (:expectationDiagnostics report)
        decision-diagnostics (:decisionDiagnostics report)
        localization (:localizationDiagnostics report)
        coverage (:coverageDiagnostics report)
        graph-expectations (:graphExpectationDiagnostics report)
        maintenance-preflight (:maintenancePreflightDiagnostics report)
        sync-check (preflight-check maintenance-preflight "syncCheck")
        artifacts (:artifactDiagnostics report)
        hint-details (hint-diagnostic-details agent-diagnostics)
        blocking-hint-details (blocking-hint-diagnostic-details agent-diagnostics)
        audit-scope-trust-boundary (hint-diagnostic-detail
                                    hint-details
                                    "audit-scope-trust-boundary")
        source-skipped-files (hint-diagnostic-detail hint-details
                                                     "source-skipped-files")]
    (vec
     (keep identity
           [(improvement-row
             {:kind "missed-files-absent-from-context"
              :area "extraction-or-retrieval"
              :runs (:missedAndAbsentFromContextRuns localization)
              :case-ids (:missedAndAbsentFromContextCaseIds localization)
              :message "Scoreable files were missed and were not present in the Yggdrasil context ranks."})
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
              :message "Yggdrasil-mode score artifacts did not include context ground-truth ranks, so benchmark attribution is weaker."})
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
             {:kind "decision-quality-gaps"
              :area "agent-decision-quality"
              :runs (:gapRuns decision-diagnostics)
              :case-ids (:gapCaseIds decision-diagnostics)
              :message "Decision benchmark cases had missing, wrong, unknown, or uncited candidate choices."
              :details (:choiceGaps decision-diagnostics)})
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
              :runs (:blockingHintDiagnosticRuns agent-diagnostics)
              :case-ids (:blockingHintDiagnosticCaseIds agent-diagnostics)
              :message "Yggdrasil hints contained warning/error diagnostics that should be inspected before trusting benchmark outcomes."
              :details blocking-hint-details})
            (improvement-row
             {:kind "audit-scope-trust-boundary"
              :area "audit-scope-quality"
              :runs (:runs audit-scope-trust-boundary)
              :case-ids (:caseIds audit-scope-trust-boundary)
              :message "Audit-scope hints reported skipped files, extractor diagnostics, or unclassified rows that weaken source-family claims."
              :details (when audit-scope-trust-boundary
                         [audit-scope-trust-boundary])})
            (improvement-row
             {:kind "source-skipped-files"
              :area "source-coverage-quality"
              :runs (:runs source-skipped-files)
              :case-ids (:caseIds source-skipped-files)
              :message "Source coverage hints reported skipped files that should be reviewed before treating missing facts as absent."
              :details (when source-skipped-files
                         [source-skipped-files])})
            (improvement-row
             {:kind "graph-expectation-failures"
              :area "graph-fact-quality"
              :runs (:failedRuns graph-expectations)
              :case-ids (:failedCaseIds graph-expectations)
              :message "Configured graph expectations failed for these benchmark runs."})
            (improvement-row
             {:kind "maintenance-preflight"
              :area "graph-maintenance"
              :runs (:blockedRuns maintenance-preflight)
              :case-ids (:blockedCaseIds maintenance-preflight)
              :message "Yggdrasil maintenance preflight did not pass for these benchmark runs."
              :details (blocking-preflight-checks maintenance-preflight)})
            (improvement-row
             {:kind "sync-check-gaps"
              :area "graph-maintenance"
              :runs (:failedRuns sync-check)
              :case-ids (:failedCaseIds sync-check)
              :message "Yggdrasil sync/check-equivalent validation gaps block maintained-graph claims."
              :details (when sync-check
                         [(select-keys sync-check
                                       [:check
                                        :status
                                        :failedRuns
                                        :failedCaseIds
                                        :blockingReasons])])})
            (improvement-row
             {:kind "commandless-runs"
              :area "agent-use-and-citation"
              :runs (:commandlessRuns agent-diagnostics)
              :case-ids (:commandlessCaseIds agent-diagnostics)
              :message "Agent results did not record any commands, making replay quality weaker."})
            (improvement-row
             {:kind "missing-token-usage"
              :area "benchmark-token-telemetry"
              :runs (:missingTokenUsageRuns agent-diagnostics)
              :case-ids (:missingTokenUsageCaseIds agent-diagnostics)
              :message "Agent results did not record token usage, so token and cost claims are not measurable for those runs."})
            (improvement-row
             {:kind "warning-runs"
              :area "agent-result-shape"
              :runs (:warningRuns agent-diagnostics)
              :case-ids (:warningCaseIds agent-diagnostics)
              :message "Agent result validation produced warnings."})
            (improvement-row
             {:kind "obsolete-agent-result-contract"
              :area "agent-protocol"
              :runs (:obsoleteAgentResultContractRuns artifacts)
              :case-ids (:obsoleteAgentResultContractCaseIds artifacts)
              :message "Score artifacts were produced before the current agent-result contract; use `bench agent-rerun` to rerun and rescore affected cases before making benchmark claims."
              :details [(select-keys artifacts
                                     [:expectedAgentResultContractVersion
                                      :obsoleteAgentResultContractVersions])]})
            (improvement-row
             {:kind "stale-agent-input-fingerprints"
              :area "agent-protocol"
              :runs (:staleAgentInputRuns artifacts)
              :case-ids (:staleAgentInputCaseIds artifacts)
              :message "Score artifacts do not match the current agent input fingerprints; use `bench agent-rerun` to rerun affected cases before making benchmark claims."})
            (improvement-row
             {:kind "unverified-score-artifacts"
              :area "benchmark-hygiene"
              :runs (:unverifiedScoreRuns artifacts)
              :case-ids (:unverifiedScoreCaseIds artifacts)
              :message "Score artifacts were stale or legacy relative to current benchmark fingerprints."})]))))
(defn- group-agent-scores-by-parser-worker
  [report-context results]
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
                               :maintenancePreflightDiagnostics (aggregate-maintenance-preflight
                                                                 rows)
                               :decisionDiagnostics (aggregate-decision-diagnostics rows)
                               :localizationDiagnostics (aggregate-localization-diagnostics rows)
                               :coverageDiagnostics (aggregate-coverage-diagnostics rows)
                               :artifactDiagnostics (aggregate-artifact-diagnostics
                                                     report-context
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
        context-rank-by-file (into {} (map (juxt benchmark-score/file-key identity)) context-ranks)
        top-files (get-in result [:agent :topFiles])
        scoreable-file-set (set (map benchmark-score/file-key
                                     (benchmark-score/scoreable-changed-files ground-truth)))
        uncited-ranked-files (->> top-files
                                  (remove benchmark-score/evidence-cited?)
                                  (mapv #(select-keys % [:repo-id :path :rank])))
        path-uncited-ranked-files (->> top-files
                                       (remove benchmark-score/path-evidence-cited?)
                                       (mapv #(select-keys % [:repo-id :path :rank])))
        missed (->> ranks
                    (remove :found?)
                    (mapv #(select-keys % [:repo-id :path])))
        missed-present-in-context (->> missed
                                       (keep (fn [file]
                                               (when-let [row (get context-rank-by-file
                                                                   (benchmark-score/file-key file))]
                                                 (when (:found? row)
                                                   (select-keys row [:repo-id :path :rank])))))
                                       vec)
        missed-absent-from-context (when (seq context-ranks)
                                     (->> missed
                                          (remove #(get-in context-rank-by-file
                                                           [(benchmark-score/file-key %) :found?]))
                                          (mapv #(select-keys % [:repo-id :path]))))
        blocker-summary (fn [row]
                          (select-keys row
                                       [:repo-id
                                        :path
                                        :rank
                                        :confidence
                                        :metrics]))
        blockers-before (fn [rank]
                          (->> top-files
                               (filter #(and (:rank %)
                                             (< (long (:rank %)) (long rank))))
                               (remove #(contains? scoreable-file-set
                                                   (benchmark-score/file-key %)))
                               (sort-by (juxt :rank :repo-id :path))
                               (take rank-blocker-limit)
                               (mapv blocker-summary)))
        ranked-outside (fn [n]
                         (->> ranks
                              (filter #(and (:found? %)
                                            (> (long (:rank %)) n)))
                              (mapv #(select-keys % [:repo-id :path :rank]))))
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
         (group-by benchmark-score/file-key)
         (map (fn [[[_repo-id path] path-rows]]
                (cond-> {:path path
                         :occurrences (count path-rows)
                         :runs (count (set (map :case-id path-rows)))
                         :bestRank (apply min (map :rank path-rows))}
                  (:repo-id (first path-rows))
                  (assoc :repo-id (:repo-id (first path-rows)))

                  (:metrics (first path-rows))
                  (assoc :metrics (:metrics (first path-rows))))))
         (sort-by (juxt (comp - :occurrences)
                        :bestRank
                        :repo-id
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
         (group-by benchmark-score/file-key)
         (map (fn [[[_repo-id path] path-rows]]
                (let [ranks (keep :rank path-rows)]
                  (cond-> {:path path
                           :occurrences (count path-rows)
                           :runs (count (set (map :case-id path-rows)))
                           :caseIds (->> path-rows
                                         (map :case-id)
                                         set
                                         sort
                                         vec)}
                    (:repo-id (first path-rows))
                    (assoc :repo-id (:repo-id (first path-rows)))

                    (seq ranks) (assoc :bestRank (apply min ranks))))))
         (sort-by (juxt (comp - :occurrences)
                        #(or (:bestRank %) Long/MAX_VALUE)
                        :repo-id
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
                                        (and (= "ygg" (str (get-in result
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
        report-context (agent-report-context suite cases)
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
                  (filter #(current-score-artifact? report-context %)
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
                     :maintenancePreflightDiagnostics (aggregate-maintenance-preflight
                                                       results)
                     :decisionDiagnostics (aggregate-decision-diagnostics results)
                     :localizationDiagnostics (aggregate-localization-diagnostics results)
                     :coverageDiagnostics (aggregate-coverage-diagnostics results)
                     :artifactDiagnostics (aggregate-artifact-diagnostics
                                           report-context
                                           raw-results)
                     :artifactPolicy (artifact-policy report-context
                                                      raw-results
                                                      results
                                                      allow-unverified?)
                     :coverage (aggregate-coverage results)
                     :tags (aggregate-case-tags cases)
                     :problemClasses (problem-class-summary report-context results)
                     :timings (benchmark-results/aggregate-progress progress)
                     :caseProgress progress
                     :byMode (group-agent-scores report-context
                                                 results
                                                 [:agent :mode])
                     :byAgent (group-agent-scores report-context
                                                  results
                                                  [:agent :agentId])
                     :byParserWorker (group-agent-scores-by-parser-worker
                                      report-context
                                      results)
                     :byTag (group-agent-scores-by-tag report-context results)
                     :results (mapv #(assoc (select-keys % [:case-id
                                                            :repo-id
                                                            :baseSha
                                                            :fixSha
                                                            :caseFingerprint
                                                            :tags
                                                            :expectations
                                                            :maintenancePreflight
                                                            :graphExpectations
                                                            :decisionScoring
                                                            :inputHints
                                                            :coverage
                                                            :contextArtifacts
                                                            :agentResultPath
                                                            :parserWorker
                                                            :agent
                                                            :progress
                                                            :scores])
                                            :localization
                                            (localization-diagnostic %)
                                            :agentOutput
                                            (agent-output-diagnostic %)
                                            :maintenancePreflight
                                            (result-maintenance-preflight %)
                                            :decision
                                            (decision-diagnostic %)
                                            :artifact
                                            (artifact-diagnostic report-context %)
                                            :auditScope
                                            (benchmark-audit-scope/case-audit-scope %))
                                    results)}
        report-base (assoc report-base
                           :improvementSummary
                           (report-improvement-summary report-base))
        report-base (assoc report-base
                           :systemImprovementSignals
                           (benchmark-system-improvement/report->system-improvement-signals
                            report-base))
        report (assoc report-base
                      :claimReadiness (report-claim-readiness report-base))]
    (benchmark-io/write-json-file! (benchmark-paths/agent-report-path suite opts) report)
    report))
