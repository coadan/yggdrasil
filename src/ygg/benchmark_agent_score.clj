(ns ygg.benchmark-agent-score
  (:require [ygg.benchmark-score :as benchmark-score]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def agent-result-schema
  "ygg.benchmark.agent-result/v2")

(def agent-score-schema
  "ygg.benchmark.agent-score/v3")

(def agent-result-contract-version
  "ygg.benchmark.agent-result-contract/decision-quality-v1")

(def agent-result-modes
  ["ygg" "shell-only" "local-vector" "codebase-memory"])

(def decision-kinds
  ["architecture-choice"
   "change-plan"
   "maintenance-action"
   "audit-assessment"
   "plugin-fit"])

(def decision-statuses
  ["include" "exclude" "defer"])

(defn agent-result-file-metrics-json-schema
  []
  {"type" "object"
   "additionalProperties" false
   "properties" {"firstSourceRank" {"type" "integer"
                                    "minimum" 1}
                 "supportCount" {"type" "integer"
                                 "minimum" 0}
                 "docCount" {"type" "integer"
                             "minimum" 0}
                 "entityCount" {"type" "integer"
                                "minimum" 0}
                 "candidateFileCount" {"type" "integer"
                                       "minimum" 0}
                 "candidateSourceRank" {"type" "integer"
                                        "minimum" 1}
                 "candidateSourceRankScore" {"type" "number"}
                 "candidateSupportLabelCount" {"type" "integer"
                                               "minimum" 0}
                 "candidateSupportLabelScore" {"type" "number"}
                 "decisionCandidateCount" {"type" "integer"
                                           "minimum" 0}
                 "directFileCandidateCount" {"type" "integer"
                                             "minimum" 0}
                 "fileIdentitySupportLabelCount" {"type" "integer"
                                                  "minimum" 0}
                 "directFileIdentitySupportBoost" {"type" "number"}
                 "retrievedSupportLabelCount" {"type" "integer"
                                               "minimum" 0}
                 "retrievedSupportLabelBoost" {"type" "number"}
                 "retrievedSourceCount" {"type" "integer"
                                         "minimum" 0}
                 "exactPathSourceCount" {"type" "integer"
                                         "minimum" 0}
                 "maxConfidence" {"type" "number"
                                  "minimum" 0
                                  "maximum" 1}
                 "rankScore" {"type" "number"}
                 "matchedTokenCount" {"type" "integer"
                                      "minimum" 0}
                 "matchedTokenPairCount" {"type" "integer"
                                          "minimum" 0}
                 "matchedCompoundTokenPairCount" {"type" "integer"
                                                  "minimum" 0}
                 "matchedIdentityCompoundTokenPairCount" {"type" "integer"
                                                          "minimum" 0}
                 "matchedIdentityCompoundTokenSpanLength" {"type" "integer"
                                                           "minimum" 0}
                 "identityCompoundTokenSpanScore" {"type" "number"}
                 "retrievedLongIdentityCompoundTokenSpanScore" {"type" "number"}
                 "retrievedEarlyLongIdentityCompoundTokenSpanScore" {"type" "number"}
                 "definitionKinds" {"type" "array"
                                    "items" {"type" "string"}}
                 "sourceRankScore" {"type" "number"}
                 "graphNeighborScore" {"type" "number"}
                 "graphNeighborBoost" {"type" "number"}
                 "docSupportedCandidateEvidenceBoost" {"type" "number"}
                 "architectureSupportBoost" {"type" "number"}
                 "candidateLexicalComponentBoost" {"type" "number"}
                 "robustCandidateOnlyBoost" {"type" "number"}
                 "cosine" {"type" "number"
                           "minimum" -1
                           "maximum" 1}
                 "model" {"type" "string"}}})
(defn agent-result-selection-json-schema
  []
  {"type" "object"
   "additionalProperties" false
   "properties" {"rawCandidateFiles" {"type" "integer"
                                      "minimum" 0}
                 "candidateFiles" {"type" "integer"
                                   "minimum" 0}
                 "coverageFilteredCandidateFiles" {"type" "integer"
                                                   "minimum" 0}
                 "limit" {"type" ["integer" "null"]
                          "minimum" 0}
                 "coverageSourceKinds" {"type" "array"
                                        "items" {"type" "string"}}
                 "candidateFileOnlyQuota" {"type" "integer"
                                           "minimum" 0}
                 "candidateFileOnlySelected" {"type" "integer"
                                              "minimum" 0}
                 "inspectionDirectFileSelected" {"type" "integer"
                                                 "minimum" 0}
                 "inspectionRepoCandidateSelected" {"type" "integer"
                                                    "minimum" 0}
                 "inspectionCandidateFillSkipped" {"type" "integer"
                                                   "minimum" 0}
                 "decisionSupportedFileSelected" {"type" "integer"
                                                  "minimum" 0}}})
(defn agent-result-decision-choice-json-schema
  []
  {"type" "object"
   "additionalProperties" false
   "required" ["id"
               "status"
               "confidence"
               "reason"
               "evidence"]
   "properties" {"id" {"type" "string"}
                 "status" {"type" "string"
                           "enum" decision-statuses}
                 "confidence" {"type" "number"
                               "minimum" 0
                               "maximum" 1}
                 "reason" {"type" "string"}
                 "evidence" {"type" "array"
                             "description" "Evidence should cite exact repo-relative paths from the visible decision candidate when available."
                             "items" {"type" "string"}}}})
(defn agent-result-decision-json-schema
  []
  {"type" "object"
   "additionalProperties" false
   "required" ["kind" "choices"]
   "properties" {"kind" {"type" "string"
                         "enum" decision-kinds}
                 "choices" {"type" "array"
                            "items" (agent-result-decision-choice-json-schema)}
                 "risks" {"type" "array"
                          "items" {"type" "string"}}
                 "followups" {"type" "array"
                              "items" {"type" "string"}}}})
(defn agent-result-json-schema
  []
  {"$schema" "https://json-schema.org/draft/2020-12/schema"
   "title" "Yggdrasil benchmark agent result"
   "type" "object"
   "additionalProperties" false
   "required" ["schema"
               "caseId"
               "caseFingerprint"
               "agentId"
               "mode"
               "suspectedFiles"
               "suspectedSymbols"
               "commands"
               "warnings"
               "summary"]
   "properties" {"schema" {"type" "string"
                           "enum" [agent-result-schema]}
                 "caseId" {"type" "string"}
                 "caseFingerprint" {"type" "string"}
                 "agentInputFingerprint" {"type" "string"}
                 "agentId" {"type" "string"}
                 "mode" {"type" "string"
                         "enum" agent-result-modes}
                 "selection" (agent-result-selection-json-schema)
                 "parserWorker" {"type" "object"
                                 "additionalProperties" false
                                 "properties" {"mode" {"type" "string"}
                                               "source" {"type" "string"}}}
                 "suspectedFiles" {"type" "array"
                                   "items" {"type" "object"
                                            "additionalProperties" false
                                            "required" ["path"
                                                        "rank"
                                                        "confidence"
                                                        "reason"
                                                        "evidence"]
                                            "properties" {"path" {"type" "string"}
                                                          "repoId" {"type" "string"}
                                                          "rank" {"type" "integer"
                                                                  "minimum" 1}
                                                          "confidence" {"type" "number"
                                                                        "minimum" 0
                                                                        "maximum" 1}
                                                          "reason" {"type" "string"}
                                                          "evidence" {"type" "array"
                                                                      "description" "Each suspected file needs at least one evidence string containing its exact repo-relative path."
                                                                      "items" {"type" "string"}}
                                                          "metrics" (agent-result-file-metrics-json-schema)}}}
                 "suspectedSymbols" {"type" "array"
                                     "items" {"type" "object"
                                              "additionalProperties" false
                                              "required" ["name"
                                                          "path"
                                                          "kind"
                                                          "rank"
                                                          "confidence"
                                                          "reason"
                                                          "evidence"]
                                              "properties" {"name" {"type" "string"}
                                                            "path" {"type" "string"}
                                                            "kind" {"type" "string"}
                                                            "rank" {"type" "integer"
                                                                    "minimum" 1}
                                                            "confidence" {"type" "number"
                                                                          "minimum" 0
                                                                          "maximum" 1}
                                                            "reason" {"type" "string"}
                                                            "evidence" {"type" "array"
                                                                        "description" "Evidence should cite exact repo-relative paths when a path is available."
                                                                        "items" {"type" "string"}}}}}
                 "commands" {"type" "array"
                             "items" {"type" "string"}}
                 "warnings" {"type" "array"
                             "items" {"type" "string"}}
                 "summary" {"type" "string"}
                 "decision" (agent-result-decision-json-schema)
                 "tokenUsage" {"type" "object"
                               "additionalProperties" false
                               "properties" {"inputTokens" {"type" "integer"
                                                            "minimum" 0}
                                             "outputTokens" {"type" "integer"
                                                             "minimum" 0}
                                             "totalTokens" {"type" "integer"
                                                            "minimum" 0}
                                             "costUsd" {"type" "number"
                                                        "minimum" 0}
                                             "source" {"type" "string"}}}}})

(defn agent-result-output-selection-json-schema
  []
  {"type" "object"
   "additionalProperties" false
   "required" ["rawCandidateFiles"
               "candidateFiles"
               "coverageFilteredCandidateFiles"
               "limit"
               "coverageSourceKinds"]
   "properties" {"rawCandidateFiles" {"type" "integer"
                                      "minimum" 0}
                 "candidateFiles" {"type" "integer"
                                   "minimum" 0}
                 "coverageFilteredCandidateFiles" {"type" "integer"
                                                   "minimum" 0}
                 "limit" {"type" ["integer" "null"]
                          "minimum" 0}
                 "coverageSourceKinds" {"type" "array"
                                        "items" {"type" "string"}}}})

(defn agent-result-output-json-schema
  []
  (-> (agent-result-json-schema)
      (assoc "required" ["schema"
                         "caseId"
                         "caseFingerprint"
                         "agentInputFingerprint"
                         "agentId"
                         "mode"
                         "selection"
                         "parserWorker"
                         "suspectedFiles"
                         "suspectedSymbols"
                         "commands"
                         "warnings"
                         "summary"
                         "decision"
                         "tokenUsage"])
      (assoc-in ["properties" "selection"]
                (agent-result-output-selection-json-schema))
      (assoc-in ["properties" "parserWorker" "required"]
                ["mode" "source"])
      (assoc-in ["properties" "decision" "type"]
                ["object" "null"])
      (assoc-in ["properties" "decision" "required"]
                ["kind" "choices" "risks" "followups"])
      (assoc-in ["properties" "tokenUsage" "type"]
                ["object" "null"])
      (assoc-in ["properties" "tokenUsage" "required"]
                ["inputTokens" "outputTokens" "totalTokens" "costUsd" "source"])
      (update-in ["properties" "suspectedFiles" "items" "properties"]
                 dissoc
                 "metrics")
      (assoc-in ["properties" "suspectedFiles" "items" "required"]
                ["path" "repoId" "rank" "confidence" "reason" "evidence"])
      (assoc-in ["properties" "suspectedFiles" "items" "properties" "repoId" "type"]
                ["string" "null"])))

(defn- parse-long-safe
  [value]
  (cond
    (integer? value) (long value)
    (number? value) (long value)
    (str/blank? (str value)) nil
    :else (try
            (Long/parseLong (str value))
            (catch NumberFormatException _
              nil))))
(defn- parse-double-safe
  [value]
  (cond
    (number? value) (double value)
    (str/blank? (str value)) nil
    :else (try
            (Double/parseDouble (str value))
            (catch NumberFormatException _
              nil))))
(defn- roots-by-repo
  [prepared]
  (or (:worktreeRoots prepared)
      (when (and (:repo-id prepared) (:worktreeRoot prepared))
        {(:repo-id prepared) (:worktreeRoot prepared)})))
(defn- path-under-root
  [root file]
  (try
    (let [root-path (.toPath (.getCanonicalFile (io/file root)))
          file-path (.toPath (.getCanonicalFile file))]
      (when (.startsWith file-path root-path)
        (str (.relativize root-path file-path))))
    (catch Exception _
      nil)))
(defn- relativize-file
  [roots repo-id path]
  (let [path (str/trim (str path))
        file (io/file path)
        [repo-id path] (if (.isAbsolute file)
                         (or (some (fn [[candidate-repo-id root]]
                                     (when-let [relative (path-under-root root file)]
                                       [candidate-repo-id relative]))
                                   roots)
                             [repo-id path])
                         [repo-id path])]
    {:repo-id repo-id
     :path (str/replace (str/replace path #"^\./" "") #"\\" "/")}))
(defn- suspected-file-path
  [roots item]
  (cond
    (string? item) (relativize-file roots nil item)
    (map? item) (when-let [path (or (:path item)
                                    (:file item)
                                    (:filePath item)
                                    (:file-path item))]
                  (relativize-file roots
                                   (or (:repoId item)
                                       (:repo-id item)
                                       (:repo item))
                                   path))
    :else nil))
(defn- agent-file-predictions
  [prepared agent-result]
  (let [roots (roots-by-repo prepared)
        raw-files (or (:suspectedFiles agent-result)
                      (:suspected-files agent-result)
                      (:files agent-result))]
    (->> raw-files
         (map-indexed
          (fn [idx item]
            (when-let [{:keys [repo-id path]} (suspected-file-path roots item)]
              (let [rank (if (map? item)
                           (parse-long-safe (:rank item))
                           nil)]
                (cond-> {:path path
                         :rank (long (or rank (inc idx)))}
                  repo-id (assoc :repo-id repo-id)
                  (map? item) (assoc :confidence (parse-double-safe (:confidence item))
                                     :reason (:reason item)
                                     :evidence (:evidence item)
                                     :metrics (:metrics item)))))))
         (keep identity)
         (reduce (fn [best row]
                   (let [key (benchmark-score/file-key row)
                         existing (get best key)]
                     (if (or (nil? existing)
                             (< (:rank row) (:rank existing)))
                       (assoc best key row)
                       best)))
                 {})
         vals
         (sort-by (juxt :rank :repo-id :path))
         vec)))
(defn- raw-suspected-file-count
  [agent-result]
  (count (or (:suspectedFiles agent-result)
             (:suspected-files agent-result)
             (:files agent-result)
             [])))
(defn- field-name
  [k]
  (if (keyword? k)
    (name k)
    (str k)))
(defn- token-name
  [value]
  (when-not (str/blank? (str value))
    (let [value (if (keyword? value)
                  (name value)
                  (str value))]
      (if (str/starts-with? value ":")
        (subs value 1)
        value))))
(defn- schema-field-key
  [k]
  (if (keyword? k)
    k
    (keyword (str k))))
(defn- schema-property-fields
  [schema]
  (->> (get schema "properties")
       keys
       (map schema-field-key)
       set))
(defn- schema-required-fields
  [schema]
  (->> (get schema "required")
       (mapv schema-field-key)))
(def ^:private agent-result-contract-schema
  (agent-result-json-schema))
(def ^:private agent-result-required-fields
  (schema-required-fields agent-result-contract-schema))
(def ^:private agent-result-allowed-fields
  (schema-property-fields agent-result-contract-schema))
(def ^:private agent-result-selection-allowed-fields
  (schema-property-fields (agent-result-selection-json-schema)))
(def ^:private agent-result-parser-worker-allowed-fields
  (schema-property-fields (get-in agent-result-contract-schema
                                  ["properties" "parserWorker"])))
(def ^:private agent-result-suspected-file-allowed-fields
  (schema-property-fields (get-in agent-result-contract-schema
                                  ["properties" "suspectedFiles" "items"])))
(def ^:private agent-result-suspected-symbol-allowed-fields
  (schema-property-fields (get-in agent-result-contract-schema
                                  ["properties" "suspectedSymbols" "items"])))
(def ^:private agent-result-file-metrics-allowed-fields
  (schema-property-fields (agent-result-file-metrics-json-schema)))
(def ^:private agent-result-decision-allowed-fields
  (schema-property-fields (agent-result-decision-json-schema)))
(def ^:private agent-result-decision-choice-allowed-fields
  (schema-property-fields (agent-result-decision-choice-json-schema)))
(defn- required-field-missing?
  [m k]
  (or (not (contains? m k))
      (nil? (get m k))))
(defn- unknown-field-warnings
  [prefix allowed-fields m]
  (when (map? m)
    (->> (keys m)
         (remove allowed-fields)
         (sort-by field-name)
         (mapv #(str prefix " unknown field " (field-name %))))))
(defn- missing-required-field-warnings
  [agent-result]
  (->> agent-result-required-fields
       (filter #(required-field-missing? agent-result %))
       (mapv #(str "agent result missing required field " (name %)))))
(defn- row-label
  [idx item]
  (str "row " (inc idx)
       (when-let [path (and (map? item) (not-empty (str (:path item))))]
         (str " path " path))))
(defn- row-rank-warning
  [field idx item]
  (when (and (contains? item :rank)
             (not (pos? (long (or (parse-long-safe (:rank item)) 0)))))
    (str "agent result "
         (name field)
         " "
         (row-label idx item)
         " rank must be a positive integer")))
(defn- row-confidence-warning
  [field idx item]
  (when (and (contains? item :confidence)
             (let [value (parse-double-safe (:confidence item))]
               (not (and (some? value)
                         (<= 0.0 value)
                         (<= value 1.0)))))
    (str "agent result "
         (name field)
         " "
         (row-label idx item)
         " confidence must be between 0 and 1")))
(defn- rankable-row-value-warnings
  [field idx item]
  (vec
   (keep identity
         [(row-rank-warning field idx item)
          (row-confidence-warning field idx item)])))
(defn- duplicate-rank-warnings
  [field rows]
  (->> rows
       (map-indexed
        (fn [idx item]
          (when (map? item)
            (let [rank (parse-long-safe (:rank item))]
              (when (pos? (long (or rank 0)))
                {:idx idx
                 :rank rank
                 :label (row-label idx item)})))))
       (keep identity)
       (group-by :rank)
       (keep (fn [[rank ranked-rows]]
               (when (< 1 (count ranked-rows))
                 (str "agent result "
                      (name field)
                      " rank "
                      rank
                      " is duplicated by "
                      (str/join " and " (map :label ranked-rows))))))
       vec))
(defn- duplicate-file-path-warnings
  [field rows]
  (when (= :suspectedFiles field)
    (->> rows
         (map-indexed
          (fn [idx item]
            (when (map? item)
              (when-let [path (not-empty (str (:path item)))]
                {:idx idx
                 :repo-id (or (:repoId item)
                              (:repo-id item)
                              (:repo item))
                 :path path
                 :label (row-label idx item)}))))
         (keep identity)
         (group-by benchmark-score/file-key)
         (keep (fn [[[_repo-id path] path-rows]]
                 (when (< 1 (count path-rows))
                   (str "agent result "
                        (name field)
                        " path "
                        path
                        " is duplicated by "
                        (str/join " and " (map :label path-rows))))))
         vec)))
(defn- suspected-file-metrics-shape-warnings
  [field idx item]
  (when (and (= :suspectedFiles field)
             (map? item)
             (contains? item :metrics))
    (if (map? (:metrics item))
      (unknown-field-warnings
       (str "agent result "
            (name field)
            " "
            (row-label idx item)
            " metrics")
       agent-result-file-metrics-allowed-fields
       (:metrics item))
      [(str "agent result "
            (name field)
            " "
            (row-label idx item)
            " metrics must be an object")])))
(defn- rankable-row-shape-warnings
  [field rows required-fields allowed-fields]
  (vec
   (concat
    (->> rows
         (map-indexed
          (fn [idx item]
            (if-not (map? item)
              [(str "agent result " (name field) " " (row-label idx item) " is not an object")]
              (vec
               (concat
                (unknown-field-warnings
                 (str "agent result " (name field) " " (row-label idx item))
                 allowed-fields
                 item)
                (->> required-fields
                     (filter #(required-field-missing? item %))
                     (mapv #(str "agent result "
                                 (name field)
                                 " "
                                 (row-label idx item)
                                 " missing "
                                 (name %))))
                (rankable-row-value-warnings field idx item)
                (suspected-file-metrics-shape-warnings field idx item))))))
         (mapcat identity))
    (duplicate-rank-warnings field rows)
    (duplicate-file-path-warnings field rows))))
(defn- decision-choice-label
  [idx item]
  (str "choice " (inc idx)
       (when-let [id (and (map? item) (not-empty (str (:id item))))]
         (str " id " id))))
(defn- decision-choice-status-warning
  [idx item]
  (when (and (map? item)
             (contains? item :status)
             (not ((set decision-statuses) (token-name (:status item)))))
    (str "agent result decision "
         (decision-choice-label idx item)
         " status must be one of "
         (str/join ", " decision-statuses))))
(defn- decision-choice-confidence-warning
  [idx item]
  (when (and (map? item)
             (contains? item :confidence)
             (let [value (parse-double-safe (:confidence item))]
               (not (and (some? value)
                         (<= 0.0 value)
                         (<= value 1.0)))))
    (str "agent result decision "
         (decision-choice-label idx item)
         " confidence must be between 0 and 1")))
(defn- duplicate-decision-choice-warnings
  [choices]
  (->> choices
       (map-indexed
        (fn [idx item]
          (when (map? item)
            (when-let [id (not-empty (str (:id item)))]
              {:idx idx
               :id id
               :label (decision-choice-label idx item)}))))
       (keep identity)
       (group-by :id)
       (keep (fn [[id rows]]
               (when (< 1 (count rows))
                 (str "agent result decision id "
                      id
                      " is duplicated by "
                      (str/join " and " (map :label rows))))))
       vec))
(defn- decision-shape-warnings
  [agent-result]
  (when (some? (:decision agent-result))
    (let [decision (:decision agent-result)]
      (if-not (map? decision)
        ["agent result decision must be an object"]
        (let [choices (:choices decision)
              choice-rows (if (sequential? choices) choices [])]
          (vec
           (concat
            (unknown-field-warnings "agent result decision"
                                    agent-result-decision-allowed-fields
                                    decision)
            (->> [:kind :choices]
                 (filter #(required-field-missing? decision %))
                 (mapv #(str "agent result decision missing " (name %))))
            (when (and (contains? decision :kind)
                       (not ((set decision-kinds) (token-name (:kind decision)))))
              [(str "agent result decision kind must be one of "
                    (str/join ", " decision-kinds))])
            (when (and (contains? decision :choices)
                       (not (sequential? choices)))
              ["agent result decision choices must be an array"])
            (->> choice-rows
                 (map-indexed
                  (fn [idx item]
                    (if-not (map? item)
                      [(str "agent result decision " (decision-choice-label idx item) " is not an object")]
                      (vec
                       (concat
                        (unknown-field-warnings
                         (str "agent result decision " (decision-choice-label idx item))
                         agent-result-decision-choice-allowed-fields
                         item)
                        (->> [:id :status :confidence :reason :evidence]
                             (filter #(required-field-missing? item %))
                             (mapv #(str "agent result decision "
                                         (decision-choice-label idx item)
                                         " missing "
                                         (name %))))
                        (keep identity
                              [(decision-choice-status-warning idx item)
                               (decision-choice-confidence-warning idx item)]))))))
                 (mapcat identity))
            (duplicate-decision-choice-warnings choice-rows))))))))
(defn- nested-object-shape-warnings
  [agent-result field allowed-fields]
  (when (contains? agent-result field)
    (let [value (get agent-result field)]
      (if (map? value)
        (unknown-field-warnings
         (str "agent result " (name field))
         allowed-fields
         value)
        [(str "agent result " (name field) " must be an object")]))))
(defn- agent-result-shape-warnings
  [agent-result]
  (vec
   (concat
    (missing-required-field-warnings agent-result)
    (unknown-field-warnings "agent result" agent-result-allowed-fields agent-result)
    (nested-object-shape-warnings agent-result
                                  :selection
                                  agent-result-selection-allowed-fields)
    (nested-object-shape-warnings agent-result
                                  :parserWorker
                                  agent-result-parser-worker-allowed-fields)
    (rankable-row-shape-warnings :suspectedFiles
                                 (or (:suspectedFiles agent-result) [])
                                 [:path :rank :confidence :reason :evidence]
                                 agent-result-suspected-file-allowed-fields)
    (rankable-row-shape-warnings :suspectedSymbols
                                 (or (:suspectedSymbols agent-result) [])
                                 [:name :path :kind :rank :confidence :reason :evidence]
                                 agent-result-suspected-symbol-allowed-fields)
    (decision-shape-warnings agent-result))))
(defn- mismatched-field-warning
  [agent-result field expected label]
  (when (and (contains? agent-result field)
             (not= (str expected) (str (get agent-result field))))
    (str "agent result "
         (name field)
         " "
         (get agent-result field)
         " does not match expected "
         label
         " "
         expected)))
(defn- agent-result-identity-warnings
  [prepared agent-result]
  (vec
   (let [fingerprint-warning
         (if (contains? agent-result :agentInputFingerprint)
           (mismatched-field-warning agent-result
                                     :agentInputFingerprint
                                     (:agentInputFingerprint prepared)
                                     "agent input fingerprint")
           (mismatched-field-warning agent-result
                                     :caseFingerprint
                                     (:caseFingerprint prepared)
                                     "case fingerprint"))]
     (keep identity
           [(mismatched-field-warning agent-result
                                      :schema
                                      agent-result-schema
                                      "schema")
            (mismatched-field-warning agent-result
                                      :caseId
                                      (:case-id prepared)
                                      "case")
            fingerprint-warning]))))
(defn- missing-predicted-files
  [roots predictions]
  (->> predictions
       (keep (fn [{:keys [repo-id path] :as file}]
               (let [root (or (get roots repo-id)
                              (when (= 1 (count roots))
                                (val (first roots))))]
                 (when-not (and root (.isFile (io/file root path)))
                   (benchmark-score/file-display file)))))
       vec))
(defn- normalize-id
  [value]
  (when-not (str/blank? (str value))
    (str value)))
(defn- id-set
  [values]
  (->> values
       (map #(if (map? %) (:id %) %))
       (keep normalize-id)
       set))
(defn- decision-ground-truth
  [prepared]
  (:decisionGroundTruth prepared))
(defn- decision-candidates
  [prepared]
  (vec (:decisionCandidates prepared)))
(defn- decision-configured?
  [prepared]
  (boolean (and (seq (decision-candidates prepared))
                (decision-ground-truth prepared))))
(defn- candidate-paths
  [candidate]
  (->> (concat (:paths candidate)
               (:evidencePaths candidate)
               (:files candidate)
               (map :path (:evidence candidate)))
       (keep normalize-id)
       distinct
       vec))
(defn- choice-evidence
  [choice]
  (->> (:evidence choice)
       (remove #(str/blank? (str %)))
       (map str)
       vec))
(defn- choice-cites-candidate-path?
  [choice candidate]
  (let [evidence (choice-evidence choice)]
    (boolean
     (some (fn [path]
             (benchmark-score/path-evidence-cited?
              {:path path
               :evidence evidence}))
           (candidate-paths candidate)))))
(defn- unique-agent-decision-choices
  [agent-result]
  (->> (get-in agent-result [:decision :choices])
       (keep (fn [choice]
               (when (map? choice)
                 (when-let [id (normalize-id (:id choice))]
                   (assoc choice
                          :id id
                          :status (token-name (:status choice)))))))
       (reduce (fn [choices choice]
                 (if (contains? (:seen choices) (:id choice))
                   choices
                   (-> choices
                       (update :rows conj choice)
                       (update :seen conj (:id choice)))))
               {:rows []
                :seen #{}})
       :rows))
(defn- decision-scoring-warnings
  [prepared agent-result]
  (when (decision-configured? prepared)
    (let [candidate-ids (set (map :id (decision-candidates prepared)))
          choices (unique-agent-decision-choices agent-result)]
      (vec
       (concat
        (when-not (map? (:decision agent-result))
          ["agent result missing decision for decision benchmark case"])
        (when (and (map? (:decision agent-result))
                   (not= (token-name (get-in prepared [:decisionGroundTruth :kind]))
                         (token-name (get-in agent-result [:decision :kind]))))
          [(str "agent result decision kind "
                (get-in agent-result [:decision :kind])
                " does not match expected decision kind "
                (get-in prepared [:decisionGroundTruth :kind]))])
        (->> choices
             (keep (fn [{:keys [id]}]
                     (when-not (contains? candidate-ids id)
                       (str "agent result decision choice "
                            id
                            " is not a visible decision candidate"))))))))))
(defn- ratio
  [numerator denominator]
  (if (pos? (long denominator))
    (/ (double numerator) (double denominator))
    0.0))
(defn- f1
  [precision recall]
  (if (pos? (+ precision recall))
    (/ (* 2.0 precision recall)
       (+ precision recall))
    0.0))
(defn- score-decision
  [prepared agent-result]
  (when (decision-configured? prepared)
    (let [ground-truth (decision-ground-truth prepared)
          candidates (decision-candidates prepared)
          candidate-by-id (into {} (map (juxt :id identity)) candidates)
          candidate-ids (set (keys candidate-by-id))
          required (id-set (or (:required ground-truth)
                               (:include ground-truth)
                               (:expected ground-truth)))
          forbidden (id-set (or (:forbidden ground-truth)
                                (:exclude ground-truth)))
          choices (unique-agent-decision-choices agent-result)
          choices-by-status (group-by :status choices)
          included (set (map :id (get choices-by-status "include")))
          excluded (set (map :id (get choices-by-status "exclude")))
          deferred (set (map :id (get choices-by-status "defer")))
          unknown (set/difference (set (map :id choices)) candidate-ids)
          true-positive (set/intersection included required)
          wrong-included (set/union (set/intersection included forbidden)
                                    (set/difference included required forbidden))
          missed (set/difference required included)
          deferred-required (set/intersection deferred required)
          included-candidate-choices (filter #(and (= "include" (:status %))
                                                   (contains? candidate-by-id (:id %)))
                                             choices)
          citation-target-choices (filter #(seq (candidate-paths
                                                 (get candidate-by-id (:id %))))
                                          included-candidate-choices)
          cited-choices (filter #(choice-cites-candidate-path?
                                  %
                                  (get candidate-by-id (:id %)))
                                citation-target-choices)
          uncited-choice-ids (->> citation-target-choices
                                  (remove #(choice-cites-candidate-path?
                                            %
                                            (get candidate-by-id (:id %))))
                                  (map :id)
                                  sort
                                  vec)
          precision (ratio (count true-positive) (count included))
          recall (ratio (count true-positive) (count required))
          evidence-rate (ratio (count cited-choices) (count citation-target-choices))]
      {:configured true
       :kind (token-name (:kind ground-truth))
       :candidateIds (vec (sort candidate-ids))
       :requiredChoiceIds (vec (sort required))
       :forbiddenChoiceIds (vec (sort forbidden))
       :includedChoiceIds (vec (sort included))
       :excludedChoiceIds (vec (sort excluded))
       :deferredChoiceIds (vec (sort deferred))
       :unknownChoiceIds (vec (sort unknown))
       :matchedRequiredChoiceIds (vec (sort true-positive))
       :missedChoiceIds (vec (sort missed))
       :wrongIncludedChoiceIds (vec (sort wrong-included))
       :deferredRequiredChoiceIds (vec (sort deferred-required))
       :uncitedChoiceIds uncited-choice-ids
       :missingDecision (not (map? (:decision agent-result)))
       :scores {:decisionRecall recall
                :decisionPrecision precision
                :decisionF1 (f1 precision recall)
                :decisionEvidenceCitationRate evidence-rate
                :decisionRequiredChoices (count required)
                :decisionIncludedRequiredChoices (count true-positive)
                :decisionIncludedChoices (count included)
                :decisionWrongIncludedChoices (count wrong-included)
                :decisionEvidenceCitationTargets (count citation-target-choices)
                :decisionEvidenceCitations (count cited-choices)}})))
(defn score-agent-result
  "Score an agent localization result against a prepared case artifact."
  [prepared agent-result]
  (let [top-files (agent-file-predictions prepared agent-result)
        roots (roots-by-repo prepared)
        decision-scoring (score-decision prepared agent-result)
        result-shape {:groundTruth (:groundTruth prepared)
                      :expectations (:expectations prepared)
                      :ygg {:topFiles top-files}}
        warnings (cond-> (vec (distinct (concat (or (:warnings agent-result) [])
                                                (agent-result-shape-warnings agent-result)
                                                (decision-scoring-warnings prepared agent-result)
                                                (agent-result-identity-warnings prepared
                                                                                agent-result))))
                   (empty? top-files)
                   (conj "agent result did not contain suspected files")

                   (seq (missing-predicted-files roots top-files))
                   (conj "agent result referenced files missing from the base checkout"))
        result-with-ranks (assoc result-shape
                                 :groundTruthRanks
                                 {:files (benchmark-score/ground-truth-file-ranks
                                          (benchmark-score/scoreable-changed-files
                                           (get-in result-shape [:groundTruth]))
                                          top-files)})
        agent-score (cond-> {:schema (:schema agent-result)
                             :caseId (:caseId agent-result)
                             :caseFingerprint (:caseFingerprint agent-result)
                             :agentInputFingerprint (:agentInputFingerprint agent-result)
                             :agentId (:agentId agent-result)
                             :mode (:mode agent-result)
                             :topFiles top-files
                             :suspectedSymbols (or (:suspectedSymbols agent-result)
                                                   (:suspected-symbols agent-result))
                             :commands (:commands agent-result)
                             :summary (:summary agent-result)
                             :selection (:selection agent-result)
                             :rawSuspectedFileCount (raw-suspected-file-count agent-result)
                             :warnings warnings
                             :missingPredictedFiles (missing-predicted-files roots
                                                                             top-files)}
                      (:decision agent-result)
                      (assoc :decision (:decision agent-result))

                      (:tokenUsage agent-result)
                      (assoc :tokenUsage (:tokenUsage agent-result)))]
    (cond-> {:schema agent-score-schema
             :agentResultContractVersion agent-result-contract-version
             :suite-id (:suite-id prepared)
             :case-id (:case-id prepared)
             :repo-id (:repo-id prepared)
             :project-id (:project-id prepared)
             :caseFingerprint (:caseFingerprint prepared)
             :agentInputFingerprint (:agentInputFingerprint prepared)
             :tags (:tags prepared)
             :expectations (:expectations prepared)
             :baseSha (:baseSha prepared)
             :fixSha (:fixSha prepared)
             :input (:input prepared)
             :inputHints (:inputHints prepared)
             :coverage (:coverage prepared)
             :groundTruth (:groundTruth prepared)
             :agent agent-score
             :groundTruthRanks (:groundTruthRanks result-with-ranks)
             :scores (merge (benchmark-score/score-result result-with-ranks)
                            (:scores decision-scoring))}
      decision-scoring
      (assoc :decisionScoring (dissoc decision-scoring :scores)))))
