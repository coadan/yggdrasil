(ns ygg.benchmark-claim-pack
  "Write a replayable benchmark claim pack from two agent report lanes."
  (:require [ygg.agent-efficiency :as agent-efficiency]
            [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-system-improvement :as benchmark-system-improvement]
            [ygg.fs :as fs]
            [clojure.string :as str]))

(def claim-pack-schema
  "ygg.benchmark.claim-pack/v1")

(defn- required-path
  [opts key flag]
  (or (some-> (get opts key) str not-empty)
      (throw (ex-info (str "Missing " flag ".") {:option flag}))))

(defn- read-agent-report
  [path]
  (benchmark-io/read-json-file path))

(defn- token-usage
  [result]
  (or (get-in result [:agent :tokenUsage])
      (get-in result [:agentOutput :tokenUsage])))

(defn- token-telemetry-status
  [report]
  (let [results (vec (:results report))
        token-results (filterv token-usage results)
        missing-results (remove token-usage results)]
    {:telemetryPresent (boolean (get-in report
                                        [:agentDiagnostics :tokenTelemetry]))
     :telemetry (get-in report [:agentDiagnostics :tokenTelemetry])
     :tokenUsageRuns (count token-results)
     :missingTokenUsageRuns (count missing-results)
     :missingTokenUsageCaseIds (->> missing-results
                                    (map :case-id)
                                    (remove nil?)
                                    distinct
                                    sort
                                    vec)}))

(defn- report-source-kind-quality
  [report]
  (or (:sourceKindQuality report)
      (get-in report [:claimReadiness :sourceKindQuality])))

(defn- report-lane-summary
  [report]
  (cond-> {:schema (:schema report)
           :suiteId (:suite-id report)
           :cases (:cases report)
           :completed (:completed report)
           :runs (:runs report)
           :missing (:missing report)
           :claimReadiness (:claimReadiness report)
           :docsClaimReadiness (:docsClaimReadiness report)
           :scores (:scores report)
           :tokenTelemetry (token-telemetry-status report)
           :contextArtifactTelemetry (get-in report
                                             [:agentDiagnostics
                                              :contextArtifactTelemetry])}
    (:sourceKindScores report)
    (assoc :sourceKindScores (:sourceKindScores report))

    (report-source-kind-quality report)
    (assoc :sourceKindQuality (report-source-kind-quality report))))

(defn- artifact-paths
  [suite opts]
  {:claimPackPath (fs/canonical-path (benchmark-paths/claim-pack-path
                                      suite
                                      opts))
   :claimPackMarkdownPath (fs/canonical-path
                           (benchmark-paths/claim-pack-markdown-path
                            suite
                            opts))
   :efficiencySummaryPath (fs/canonical-path
                           (benchmark-paths/efficiency-summary-path
                            suite
                            opts))
   :efficiencyMarkdownPath (fs/canonical-path
                            (benchmark-paths/efficiency-markdown-path
                             suite
                             opts))
   :systemImprovementReportPath (fs/canonical-path
                                 (benchmark-paths/system-improvement-report-path
                                  suite
                                  opts))})

(defn- claim-pack-summary
  [comparison system-improvement lanes]
  (let [claim-readiness (:claimReadiness comparison)
        measured-slice-claim (:measuredSliceClaim comparison)]
    {:verdict (get-in comparison [:compactSummary :verdict])
     :status (:status comparison)
     :claimReadiness (:status claim-readiness)
     :broadEfficiencyClaimSupported
     (:broadEfficiencyClaimSupported claim-readiness)
     :claimReadinessDetails (select-keys
                             claim-readiness
                             [:status
                              :broadEfficiencyClaimSupported
                              :sharedCases
                              :minSharedCases
                              :requirements
                              :laneClaimReadiness
                              :warnings
                              :notes])
     :sourceKindQualityByLane (not-empty
                               (into {}
                                     (keep (fn [[lane summary]]
                                             (when-let [quality
                                                        (:sourceKindQuality
                                                         summary)]
                                               [lane quality])))
                                     lanes))
     :docsClaimReadinessByLane (not-empty
                                (into {}
                                      (keep (fn [[lane summary]]
                                              (when-let [readiness
                                                         (:docsClaimReadiness
                                                          summary)]
                                                [lane readiness])))
                                      lanes))
     :measuredSliceClaim (select-keys
                          measured-slice-claim
                          [:status
                           :measuredSliceClaimSupported
                           :scope
                           :requirements
                           :failedRequirements
                           :notes])
     :qualityCostTradeoff (:qualityCostTradeoff comparison)
     :contextArtifacts (:contextArtifacts comparison)
     :failedRequirements (get-in comparison
                                 [:headlineSummary :failedRequirements])
     :systemImprovementLanes (mapv #(select-keys % [:lane
                                                    :suggestedOwnerArea
                                                    :affectedCases
                                                    :runs
                                                    :confidence
                                                    :signalKinds])
                                   (:lanes system-improvement))}))

(defn- format-token-status
  [status]
  (str (if (:telemetryPresent status) "present" "missing")
       " (runs with tokenUsage: " (:tokenUsageRuns status)
       ", missing: " (:missingTokenUsageRuns status)
       ")"))

(defn- format-context-artifact-status
  [telemetry]
  (if telemetry
    (str "present (runs: " (:runs telemetry)
         ", compact hints bytes: "
         (get-in telemetry [:totals :compactHintsBytes])
         ", hint savings bytes: "
         (get-in telemetry [:totals :hintSavingsBytes])
         ")")
    "missing"))

(defn- artifact-line
  [[label path]]
  (str "- " (name label) ": " path))

(defn- list-part
  [label values]
  (str label " " (if (seq values)
                   (str/join "," values)
                   "none")))

(defn- source-kind-quality-line
  [label quality]
  (str "- " label " source-kind quality: "
       "min-cases " (:minimumCasesForSourceKindQuality quality)
       ", min-measured "
       (:minimumMeasuredSourceKindQualityGroupsForBroadClaim quality)
       ", "
       (list-part "measured" (:measuredSourceKindKeys quality))
       ", "
       (list-part "underpowered" (:underpoweredSourceKindKeys quality))
       ", "
       (list-part "below-floor" (:lowQualitySourceKindKeys quality))))

(defn- docs-claim-readiness-line
  [label readiness]
  (str "- " label " docs claim readiness: " (:status readiness)
       ", supported " (:docsHandlingClaimSupported readiness)
       ", doc cases " (:docSourceKindCases readiness)
       ", source kinds "
       (if (seq (:sourceKindKeys readiness))
         (str/join "," (:sourceKindKeys readiness))
         "none")))

(defn markdown-report
  "Return a compact Markdown summary for a benchmark claim pack."
  [claim-pack]
  (let [summary (:summary claim-pack)
        shell-token (get-in claim-pack [:lanes :shellOnly :tokenTelemetry])
        ygg-token (get-in claim-pack [:lanes :ygg :tokenTelemetry])
        tradeoff (:qualityCostTradeoff summary)
        artifacts (:artifacts claim-pack)]
    (str/join
     "\n"
     (concat
      ["# Yggdrasil Benchmark Claim Pack"
       ""
       (str "- Suite: " (:suiteId claim-pack))
       (str "- Verdict: " (:verdict summary))
       (str "- Status: " (:status summary))
       (str "- Claim readiness: " (:claimReadiness summary))
       (str "- Broad efficiency claim supported: "
            (:broadEfficiencyClaimSupported summary))
       (str "- Quality/token tradeoff: "
            (or (:status tradeoff) "unavailable"))
       (str "- Shell-only token telemetry: " (format-token-status shell-token))
       (str "- Yggdrasil token telemetry: " (format-token-status ygg-token))
       (str "- Shell-only context artifact telemetry: "
            (format-context-artifact-status
             (get-in claim-pack
                     [:lanes :shellOnly :contextArtifactTelemetry])))
       (str "- Yggdrasil context artifact telemetry: "
            (format-context-artifact-status
             (get-in claim-pack
                     [:lanes :ygg :contextArtifactTelemetry])))]
      (when-let [failed (seq (:failedRequirements summary))]
        [""
         "## Failed Requirements"
         ""
         (str "- " (str/join ", " (map name failed)))])
      (when (seq (:sourceKindQualityByLane summary))
        (concat
         [""
          "## Source-Kind Quality"
          ""]
         (map (fn [[lane quality]]
                (source-kind-quality-line (name lane) quality))
              (:sourceKindQualityByLane summary))))
      (when (seq (:docsClaimReadinessByLane summary))
        (concat
         [""
          "## Docs Claim Readiness"
          ""]
         (map (fn [[lane readiness]]
                (docs-claim-readiness-line (name lane) readiness))
              (:docsClaimReadinessByLane summary))))
      (when-let [warnings (seq (get-in summary
                                       [:claimReadinessDetails :warnings]))]
        (concat
         [""
          "## Claim Readiness Warnings"
          ""]
         (map #(str "- " %) warnings)))
      (when-let [notes (seq (get-in summary [:measuredSliceClaim :notes]))]
        (concat
         [""
          "## Measured Slice Notes"
          ""]
         (map #(str "- " %) notes)))
      (when (seq (:systemImprovementLanes summary))
        (concat
         [""
          "## System Improvement Lanes"
          ""]
         (map (fn [{:keys [lane suggestedOwnerArea affectedCases runs confidence]}]
                (str "- " lane
                     " owner " suggestedOwnerArea
                     ", cases " affectedCases
                     ", runs " runs
                     ", confidence " confidence))
              (:systemImprovementLanes summary))))
      [""
       "## Artifacts"
       ""]
      (map artifact-line artifacts)))))

(defn write-claim-pack!
  "Read shell-only and Yggdrasil agent reports, then write claim-pack artifacts."
  [suite opts]
  (let [shell-report-path (required-path opts :shell-report "--shell-report")
        ygg-report-path (required-path opts :ygg-report "--ygg-report")
        shell-report (read-agent-report shell-report-path)
        ygg-report (read-agent-report ygg-report-path)
        comparison (-> (agent-efficiency/compare-reports
                        shell-report
                        ygg-report
                        {:min-shared-cases (:min-shared-cases opts)})
                       (assoc-in [:inputs :shellReport] shell-report-path)
                       (assoc-in [:inputs :yggReport] ygg-report-path))
        system-improvement (benchmark-system-improvement/system-improvement-report
                            ygg-report)
        artifacts (artifact-paths suite opts)
        lanes {:shellOnly (report-lane-summary shell-report)
               :ygg (report-lane-summary ygg-report)}
        claim-pack {:schema claim-pack-schema
                    :suiteId (:id suite)
                    :inputs {:shellReport shell-report-path
                             :yggReport ygg-report-path}
                    :artifacts artifacts
                    :summary (claim-pack-summary comparison
                                                 system-improvement
                                                 lanes)
                    :lanes lanes
                    :efficiency comparison
                    :systemImprovement system-improvement}]
    (benchmark-io/write-json-file! (:efficiencySummaryPath artifacts)
                                   comparison)
    (benchmark-io/write-text-file! (:efficiencyMarkdownPath artifacts)
                                   (agent-efficiency/markdown-report comparison))
    (benchmark-io/write-json-file! (:systemImprovementReportPath artifacts)
                                   system-improvement)
    (benchmark-io/write-json-file! (:claimPackPath artifacts) claim-pack)
    (benchmark-io/write-text-file! (:claimPackMarkdownPath artifacts)
                                   (markdown-report claim-pack))
    claim-pack))
