(ns agraph.benchmark-system-improvement
  "Dev-time system improvement analysis for benchmark diagnostics."
  (:require [agraph.benchmark-classes :as benchmark-classes]
            [agraph.benchmark-io :as benchmark-io]
            [agraph.benchmark-paths :as benchmark-paths]
            [clojure.set :as set]))

(def system-improvement-report-schema
  "agraph.benchmark.system-improvement-report/v1")

(def system-improvement-lanes
  {"maintenance-emitter-gap"
   {:ownerArea "maintenance"
    :rootCauseCategory "maintenance-emitter-gap"
    :recommendedSystemChange "Add or improve regular maintenance emitters so repeated graph validation gaps become project maintenance work outside benchmark runs."}
   "indexing-gap"
   {:ownerArea "indexing"
    :rootCauseCategory "indexing-gap"
    :recommendedSystemChange "Improve canonical graph row representation, graph expectation coverage, or indexing summaries so present project facts are durable and queryable."}
   "extractor-gap"
   {:ownerArea "extraction"
    :rootCauseCategory "extractor-gap"
    :recommendedSystemChange "Add or improve extractor support and diagnostics for skipped or unsupported files before treating missing facts as absent."}
   "retrieval-gap"
   {:ownerArea "retrieval"
    :rootCauseCategory "retrieval-gap"
    :recommendedSystemChange "Improve retrieval ranking, context selection, or context budget allocation for facts already present in benchmark context."}
   "decision-quality-gap"
   {:ownerArea "decision-quality"
    :rootCauseCategory "decision-quality-gap"
    :recommendedSystemChange "Improve decision-candidate prompts, evidence context, and scoring feedback so agents make auditable include/exclude/defer choices on complex-system tasks."}
   "benchmark-suite-gap"
   {:ownerArea "benchmark"
    :rootCauseCategory "benchmark-suite-gap"
    :recommendedSystemChange "Tighten benchmark declarations, ground truth, class tags, source coverage, or suite breadth so measurements describe the intended problem class."}
   "agent-protocol-gap"
   {:ownerArea "agent-protocol"
    :rootCauseCategory "agent-protocol-gap"
    :recommendedSystemChange "Tighten agent result, command telemetry, score freshness, and citation contracts so benchmark outcomes remain replayable and measurable."}})

(def ^:private signal-evidence-limit
  12)

(defn- long-value
  [value]
  (long (or value 0)))

(defn- sorted-vec
  [values]
  (->> values
       (remove nil?)
       distinct
       sort
       vec))

(defn- signal-confidence
  [runs case-ids]
  (cond
    (>= (count (set case-ids)) 2) "high"
    (>= (long-value runs) 2) "high"
    :else "medium"))

(defn- signal
  [{:keys [kind lane runs case-ids evidence message rationale confidence]}]
  (let [runs (long-value runs)
        case-ids (sorted-vec case-ids)
        evidence (vec (remove nil? evidence))
        lane-info (get system-improvement-lanes lane)]
    (when (pos? runs)
      (cond-> {:kind kind
               :lane lane
               :runs runs
               :caseIds case-ids
               :ownerArea (:ownerArea lane-info)
               :rootCauseCategory (:rootCauseCategory lane-info)
               :recommendedSystemChange (:recommendedSystemChange lane-info)
               :confidence (or confidence (signal-confidence runs case-ids))
               :rationale rationale}
        message (assoc :message message)
        (seq evidence) (assoc :evidence evidence)))))

(defn- case-ids-from-report
  [report]
  (sorted-vec (map :case-id (:results report))))

(defn- class-case-index
  [report pred]
  (->> (:results report)
       (mapcat (fn [result]
                 (map (fn [tag] [tag (:case-id result)])
                      (filter pred (:tags result)))))
       (group-by first)
       (map (fn [[tag rows]]
              [tag (set (map second rows))]))
       (into {})))

(defn- class-rollups-for-cases
  [class-index case-ids]
  (let [affected (set case-ids)]
    (->> class-index
         (keep (fn [[tag class-cases]]
                 (let [matched (set/intersection affected class-cases)]
                   (when (seq matched)
                     {:class tag
                      :cases (count matched)
                      :caseIds (sorted-vec matched)}))))
         (sort-by :class)
         vec)))

(defn- hint-detail
  [report kind]
  (first (filter #(= kind (:kind %))
                 (get-in report [:agentDiagnostics :hintDiagnosticsByKind]))))

(defn- blocking-hint-detail
  [report kind]
  (first (filter #(= kind (:kind %))
                 (get-in report
                         [:agentDiagnostics :blockingHintDiagnosticsByKind]))))

(defn- extractor-related-diagnostics?
  [report]
  (boolean
   (seq (keep #(or (hint-detail report %)
                   (blocking-hint-detail report %))
              ["source-extraction-diagnostics"
               "source-skipped-files"
               "audit-scope-trust-boundary"]))))

(defn- detail-evidence
  [detail]
  (when detail
    [(select-keys detail [:kind :runs :cases :caseIds])]))

(defn report->system-improvement-signals
  "Classify benchmark diagnostics into dev-time system improvement signals.

  This classifier consumes only benchmark diagnostics, benchmark-declared class
  tags, and score metadata. It does not derive project architecture meaning from
  path names, hosts, prose, or substring lists."
  [report]
  (let [agent (:agentDiagnostics report)
        artifacts (:artifactDiagnostics report)
        coverage (:coverageDiagnostics report)
        graph (:graphExpectationDiagnostics report)
        localization (:localizationDiagnostics report)
        maintenance (:maintenancePreflightDiagnostics report)
        decision (:decisionDiagnostics report)
        expectation (:expectationDiagnostics report)
        source-extraction (hint-detail report "source-extraction-diagnostics")
        source-skipped (hint-detail report "source-skipped-files")
        audit-boundary (hint-detail report "audit-scope-trust-boundary")
        missing-kind-lane (if (extractor-related-diagnostics? report)
                            "extractor-gap"
                            "benchmark-suite-gap")]
    (vec
     (keep
      identity
      [(signal
        {:kind "graph-expectation-failures"
         :lane "indexing-gap"
         :runs (:failedRuns graph)
         :case-ids (:failedCaseIds graph)
         :evidence [(select-keys graph [:configuredRuns :failedRuns :failedCaseIds])]
         :rationale "Configured graph expectations failed, so benchmark evidence points at graph fact or row-shape quality rather than a one-off benchmark repair."})
       (signal
        {:kind "maintenance-preflight-gaps"
         :lane "maintenance-emitter-gap"
         :runs (:blockedRuns maintenance)
         :case-ids (:blockedCaseIds maintenance)
         :evidence (:checks maintenance)
         :rationale "Maintenance-equivalent benchmark preflight did not pass; repeated failures should become regular maintenance emitters or validations."})
       (signal
        {:kind "missed-files-absent-from-context"
         :lane "indexing-gap"
         :runs (:missedAndAbsentFromContextRuns localization)
         :case-ids (:missedAndAbsentFromContextCaseIds localization)
         :rationale "Scoreable files were absent from AGraph context ranks, indicating missing indexed facts, weak row representation, or extraction coverage."})
       (signal
        {:kind "missed-files-present-in-context"
         :lane "retrieval-gap"
         :runs (:missedButPresentInContextRuns localization)
         :case-ids (:missedButPresentInContextCaseIds localization)
         :evidence (:rankedOutsideTop5BlockingFiles localization)
         :rationale "Ground-truth files were present in context but did not make the final selection, so ranking or context selection is the likely improvement surface."})
       (signal
        {:kind "ranked-outside-top5"
         :lane "retrieval-gap"
         :runs (:rankedOutsideTop5Runs localization)
         :case-ids (:rankedOutsideTop5CaseIds localization)
         :evidence (:rankedOutsideTop5BlockingFiles localization)
         :rationale "Ground-truth files were ranked below the top-five shortlist despite being found."})
       (signal
        {:kind "missing-context-ranks"
         :lane "agent-protocol-gap"
         :runs (:contextRankMissingRuns localization)
         :case-ids (:contextRankMissingCaseIds localization)
         :rationale "AGraph-mode score artifacts lacked context ground-truth ranks, weakening attribution for retrieval versus agent selection."})
       (signal
        {:kind "decision-quality-gaps"
         :lane "decision-quality-gap"
         :runs (:gapRuns decision)
         :case-ids (:gapCaseIds decision)
         :evidence (:choiceGaps decision)
         :rationale "Decision benchmark cases had missing, wrong, unknown, or uncited candidate choices, so complex-system decision support needs focused improvement."})
       (signal
        {:kind "source-extraction-diagnostics"
         :lane "extractor-gap"
         :runs (:runs source-extraction)
         :case-ids (:caseIds source-extraction)
         :evidence (detail-evidence source-extraction)
         :rationale "Source coverage emitted extraction diagnostics during benchmark indexing."})
       (signal
        {:kind "source-skipped-files"
         :lane "extractor-gap"
         :runs (:runs source-skipped)
         :case-ids (:caseIds source-skipped)
         :evidence (detail-evidence source-skipped)
         :rationale "Source coverage reported skipped files; extractor coverage should improve before interpreting missing graph facts as absent."})
       (signal
        {:kind "audit-scope-trust-boundary"
         :lane "extractor-gap"
         :runs (:runs audit-boundary)
         :case-ids (:caseIds audit-boundary)
         :evidence (detail-evidence audit-boundary)
         :rationale "Audit-scope diagnostics reported skipped files, extractor diagnostics, or unclassified extractor rows."})
       (signal
        {:kind "missing-declared-source-kinds"
         :lane missing-kind-lane
         :runs (:missingDeclaredSourceKindRuns coverage)
         :case-ids (:missingDeclaredSourceKindCaseIds coverage)
         :evidence (:missingDeclaredSourceKinds coverage)
         :rationale (if (= "extractor-gap" missing-kind-lane)
                      "Benchmark-declared source kinds were missing while extraction diagnostics were present."
                      "Benchmark-declared source kinds had no scoreable indexed files without extractor diagnostics, so suite declarations or coverage tags likely need tightening.")})
       (signal
        {:kind "coverage-excluded-ground-truth"
         :lane "benchmark-suite-gap"
         :runs (:coverageExcludedGroundTruthRuns coverage)
         :case-ids (:coverageExcludedGroundTruthCaseIds coverage)
         :rationale "Ground-truth files were excluded by benchmark source coverage declarations."})
       (signal
        {:kind "unsupported-ground-truth-files"
         :lane "extractor-gap"
         :runs (:unsupportedGroundTruthRuns coverage)
         :case-ids (:unsupportedGroundTruthCaseIds coverage)
         :rationale "Ground-truth files were unsupported by current source coverage."})
       (signal
        {:kind "expected-evidence-citation-metric-gaps"
         :lane "agent-protocol-gap"
         :runs (:missingExpectedEvidenceCitationMetricRuns expectation)
         :case-ids (:missingExpectedEvidenceCitationMetricCaseIds expectation)
         :rationale "Expected evidence was declared but score artifacts lacked expected-evidence citation metrics."})
       (signal
        {:kind "path-citation-gaps"
         :lane "agent-protocol-gap"
         :runs (:pathUncitedRuns localization)
         :case-ids (:pathUncitedCaseIds localization)
         :evidence (:pathUncitedRankedFiles localization)
         :rationale "Ranked files lacked path-level evidence citations, weakening replayable measurement."})
       (signal
        {:kind "commandless-runs"
         :lane "agent-protocol-gap"
         :runs (:commandlessRuns agent)
         :case-ids (:commandlessCaseIds agent)
         :evidence [(get agent :commandTelemetry)]
         :rationale "Agent result artifacts did not include command telemetry."})
       (signal
        {:kind "agent-result-warning-runs"
         :lane "agent-protocol-gap"
         :runs (:warningRuns agent)
         :case-ids (:warningCaseIds agent)
         :rationale "Agent result validation produced warnings, weakening result shape reliability."})
       (signal
        {:kind "obsolete-agent-result-contract"
         :lane "agent-protocol-gap"
         :runs (:obsoleteAgentResultContractRuns artifacts)
         :case-ids (:obsoleteAgentResultContractCaseIds artifacts)
         :evidence [(select-keys artifacts
                                 [:expectedAgentResultContractVersion
                                  :obsoleteAgentResultContractVersions])]
         :rationale "Score artifacts were produced under an obsolete or missing agent-result contract; rerun and rescore the affected agent lane under the current contract before claiming benchmark results."})
       (signal
        {:kind "stale-agent-input-fingerprints"
         :lane "agent-protocol-gap"
         :runs (:staleAgentInputRuns artifacts)
         :case-ids (:staleAgentInputCaseIds artifacts)
         :evidence [(select-keys artifacts
                                 [:staleAgentInputRuns
                                  :staleAgentInputCaseIds])]
         :rationale "Score artifacts do not match current agent input fingerprints, so benchmark results are not replayable evidence for the current packet contract."})
       (signal
        {:kind "unverified-score-artifacts"
         :lane "agent-protocol-gap"
         :runs (:unverifiedScoreRuns artifacts)
         :case-ids (:unverifiedScoreCaseIds artifacts)
         :evidence [(select-keys artifacts
                                 [:legacyScoreRuns
                                  :staleScoreRuns
                                  :obsoleteScoreSchemaRuns
                                  :obsoleteAgentResultSchemaRuns
                                  :obsoleteAgentResultContractRuns
                                  :staleAgentInputRuns])]
         :rationale "Score artifacts were stale or legacy relative to current benchmark fingerprints."})
       (signal
        {:kind "weak-problem-class-coverage"
         :lane "benchmark-suite-gap"
         :runs (count (filter #(= "insufficient-cases" (:claimStatus %))
                              (get-in report [:problemClasses :classes])))
         :case-ids (case-ids-from-report report)
         :evidence (get-in report [:problemClasses :classes])
         :confidence "medium"
         :rationale "At least one problem class is below the minimum case count for class-level claims."})
       (signal
        {:kind "weak-architecture-class-coverage"
         :lane "benchmark-suite-gap"
         :runs (count (filter #(= "insufficient-cases" (:claimStatus %))
                              (get-in report
                                      [:problemClasses :architectureClasses])))
         :case-ids (case-ids-from-report report)
         :evidence (get-in report [:problemClasses :architectureClasses])
         :confidence "medium"
         :rationale "At least one architecture class is below the minimum case count for class-level claims."})]))))

(defn- aggregate-evidence
  [signals]
  (->> signals
       (map #(select-keys % [:kind
                             :runs
                             :caseIds
                             :message
                             :rationale
                             :evidence]))
       (take signal-evidence-limit)
       vec))

(defn- aggregate-confidence
  [signals case-count]
  (cond
    (or (>= case-count 2)
        (some #(= "high" (:confidence %)) signals))
    "high"
    (seq signals)
    "medium"
    :else
    "low"))

(defn- lane-row
  [problem-index architecture-index [lane signals]]
  (let [lane-info (get system-improvement-lanes lane)
        case-ids (sorted-vec (mapcat :caseIds signals))
        runs (reduce + 0 (map :runs signals))
        signal-kinds (sorted-vec (map :kind signals))]
    {:lane lane
     :suggestedOwnerArea (:ownerArea lane-info)
     :likelyRootCauseCategory (:rootCauseCategory lane-info)
     :recommendedSystemChange (:recommendedSystemChange lane-info)
     :affectedCases (count case-ids)
     :affectedCaseIds case-ids
     :affectedProblemClasses (class-rollups-for-cases problem-index case-ids)
     :affectedArchitectureClasses (class-rollups-for-cases architecture-index
                                                           case-ids)
     :runs runs
     :signalKinds signal-kinds
     :evidence (aggregate-evidence signals)
     :confidence (aggregate-confidence signals (count case-ids))
     :rationale (str "Aggregated " (count signals)
                     " benchmark signal(s) across " (count case-ids)
                     " affected case(s).")}))

(defn- class-signal-rollups
  [class-index signals]
  (let [signals-by-class (reduce
                          (fn [acc signal-row]
                            (reduce
                             (fn [inner [class class-cases]]
                               (let [matched (set/intersection
                                              (set (:caseIds signal-row))
                                              class-cases)]
                                 (if (seq matched)
                                   (update inner
                                           class
                                           (fnil conj [])
                                           (assoc signal-row
                                                  :matchedCaseIds
                                                  (sorted-vec matched)))
                                   inner)))
                             acc
                             class-index))
                          {}
                          signals)]
    (->> signals-by-class
         (map (fn [[class rows]]
                (let [case-ids (sorted-vec (mapcat :matchedCaseIds rows))]
                  {:class class
                   :affectedCases (count case-ids)
                   :affectedCaseIds case-ids
                   :lanes (sorted-vec (map :lane rows))
                   :signalKinds (sorted-vec (map :kind rows))
                   :runs (reduce + 0 (map :runs rows))})))
         (sort-by :class)
         vec)))

(defn system-improvement-report
  "Return a dev-only report that groups benchmark diagnostics by system lane."
  [agent-report]
  (let [signals (vec (or (:systemImprovementSignals agent-report)
                         (report->system-improvement-signals agent-report)))
        problem-index (class-case-index agent-report
                                        benchmark-classes/problem-class-tag?)
        architecture-index (class-case-index
                            agent-report
                            benchmark-classes/architecture-class-tag?)
        lanes (->> signals
                   (group-by :lane)
                   (map (partial lane-row problem-index architecture-index))
                   (sort-by (juxt (comp - :affectedCases)
                                  (comp - :runs)
                                  :lane))
                   vec)]
    {:schema system-improvement-report-schema
     :suite-id (:suite-id agent-report)
     :sourceReport {:schema (:schema agent-report)
                    :cases (:cases agent-report)
                    :completed (:completed agent-report)
                    :runs (:runs agent-report)
                    :claimReadiness (:claimReadiness agent-report)}
     :claimReady? (= "supported" (get-in agent-report [:claimReadiness :status]))
     :systemImprovementSignals signals
     :lanes lanes
     :summary {:lanes (mapv #(select-keys % [:lane
                                             :suggestedOwnerArea
                                             :affectedCases
                                             :runs
                                             :confidence
                                             :signalKinds])
                            lanes)
               :problemClasses (class-signal-rollups problem-index signals)
               :architectureClasses (class-signal-rollups architecture-index
                                                          signals)}}))

(defn write-system-improvement-report!
  [suite opts agent-report]
  (let [report (system-improvement-report agent-report)]
    (benchmark-io/write-json-file!
     (benchmark-paths/system-improvement-report-path suite opts)
     report)
    report))
