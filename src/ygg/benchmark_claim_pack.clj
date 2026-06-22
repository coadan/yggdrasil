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

(defn- report-lane-summary
  [report]
  {:schema (:schema report)
   :suiteId (:suite-id report)
   :cases (:cases report)
   :completed (:completed report)
   :runs (:runs report)
   :missing (:missing report)
   :claimReadiness (:claimReadiness report)
   :scores (:scores report)
   :tokenTelemetry (token-telemetry-status report)
   :contextArtifactTelemetry (get-in report
                                     [:agentDiagnostics
                                      :contextArtifactTelemetry])})

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
  [comparison system-improvement]
  {:verdict (get-in comparison [:compactSummary :verdict])
   :status (:status comparison)
   :claimReadiness (get-in comparison [:claimReadiness :status])
   :broadEfficiencyClaimSupported (get-in comparison
                                          [:claimReadiness
                                           :broadEfficiencyClaimSupported])
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
                                 (:lanes system-improvement))})

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
        claim-pack {:schema claim-pack-schema
                    :suiteId (:id suite)
                    :inputs {:shellReport shell-report-path
                             :yggReport ygg-report-path}
                    :artifacts artifacts
                    :summary (claim-pack-summary comparison
                                                 system-improvement)
                    :lanes {:shellOnly (report-lane-summary shell-report)
                            :ygg (report-lane-summary ygg-report)}
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
