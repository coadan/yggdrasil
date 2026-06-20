(ns agraph.benchmark-agent-score
  (:require [agraph.benchmark-score :as benchmark-score]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def agent-result-schema
  "agraph.benchmark.agent-result/v2")

(def agent-score-schema
  "agraph.benchmark.agent-score/v3")

(def agent-result-modes
  ["agraph" "shell-only" "local-vector"])

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
                 "definitionKinds" {"type" "array"
                                    "items" {"type" "string"}}
                 "sourceRankScore" {"type" "number"}
                 "graphNeighborScore" {"type" "number"}
                 "graphNeighborBoost" {"type" "number"}
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
                                              "minimum" 0}}})
(defn agent-result-json-schema
  []
  {"$schema" "https://json-schema.org/draft/2020-12/schema"
   "title" "AGraph benchmark agent result"
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
                                                          "rank" {"type" "integer"
                                                                  "minimum" 1}
                                                          "confidence" {"type" "number"
                                                                        "minimum" 0
                                                                        "maximum" 1}
                                                          "reason" {"type" "string"}
                                                          "evidence" {"type" "array"
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
                                                                        "items" {"type" "string"}}}}}
                 "commands" {"type" "array"
                             "items" {"type" "string"}}
                 "warnings" {"type" "array"
                             "items" {"type" "string"}}
                 "summary" {"type" "string"}}})

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
                         "agentId"
                         "mode"
                         "selection"
                         "parserWorker"
                         "suspectedFiles"
                         "suspectedSymbols"
                         "commands"
                         "warnings"
                         "summary"])
      (assoc-in ["properties" "selection"]
                (agent-result-output-selection-json-schema))
      (assoc-in ["properties" "parserWorker" "required"]
                ["mode" "source"])
      (update-in ["properties" "suspectedFiles" "items" "properties"]
                 dissoc
                 "metrics")))

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
(defn- relativize-path
  [root path]
  (let [path (str/trim (str path))
        file (io/file path)]
    (str/replace
     (str/replace
      (if (.isAbsolute file)
        (try
          (let [root-path (.toPath (.getCanonicalFile (io/file root)))
                file-path (.toPath (.getCanonicalFile file))]
            (str (.relativize root-path file-path)))
          (catch Exception _
            path))
        path)
      #"^\./"
      "")
     #"\\" "/")))
(defn- suspected-file-path
  [root item]
  (cond
    (string? item) (relativize-path root item)
    (map? item) (some->> (or (:path item)
                             (:file item)
                             (:filePath item)
                             (:file-path item))
                         (relativize-path root))
    :else nil))
(defn- agent-file-predictions
  [prepared agent-result]
  (let [root (:worktreeRoot prepared)
        raw-files (or (:suspectedFiles agent-result)
                      (:suspected-files agent-result)
                      (:files agent-result))]
    (->> raw-files
         (map-indexed
          (fn [idx item]
            (when-let [path (some-> (suspected-file-path root item) not-empty)]
              (let [rank (if (map? item)
                           (parse-long-safe (:rank item))
                           nil)]
                (cond-> {:path path
                         :rank (long (or rank (inc idx)))}
                  (map? item) (assoc :confidence (parse-double-safe (:confidence item))
                                     :reason (:reason item)
                                     :evidence (:evidence item)
                                     :metrics (:metrics item)))))))
         (keep identity)
         (reduce (fn [best row]
                   (let [existing (get best (:path row))]
                     (if (or (nil? existing)
                             (< (:rank row) (:rank existing)))
                       (assoc best (:path row) row)
                       best)))
                 {})
         vals
         (sort-by (juxt :rank :path))
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
                 :path path
                 :label (row-label idx item)}))))
         (keep identity)
         (group-by :path)
         (keep (fn [[path path-rows]]
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
                                 agent-result-suspected-symbol-allowed-fields))))
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
   (keep identity
         [(mismatched-field-warning agent-result
                                    :schema
                                    agent-result-schema
                                    "schema")
          (mismatched-field-warning agent-result
                                    :caseId
                                    (:case-id prepared)
                                    "case")
          (mismatched-field-warning agent-result
                                    :caseFingerprint
                                    (:caseFingerprint prepared)
                                    "case fingerprint")])))
(defn- missing-predicted-files
  [root predictions]
  (->> predictions
       (keep (fn [{:keys [path]}]
               (when-not (.isFile (io/file root path))
                 path)))
       vec))
(defn score-agent-result
  "Score an agent localization result against a prepared case artifact."
  [prepared agent-result]
  (let [top-files (agent-file-predictions prepared agent-result)
        result-shape {:groundTruth (:groundTruth prepared)
                      :expectations (:expectations prepared)
                      :agraph {:topFiles top-files}}
        warnings (cond-> (vec (distinct (concat (or (:warnings agent-result) [])
                                                (agent-result-shape-warnings agent-result)
                                                (agent-result-identity-warnings prepared
                                                                                agent-result))))
                   (empty? top-files)
                   (conj "agent result did not contain suspected files")

                   (seq (missing-predicted-files (:worktreeRoot prepared) top-files))
                   (conj "agent result referenced files missing from the base checkout"))
        result-with-ranks (assoc result-shape
                                 :groundTruthRanks
                                 {:files (benchmark-score/ground-truth-file-ranks
                                          (benchmark-score/scoreable-changed-files
                                           (get-in result-shape [:groundTruth]))
                                          top-files)})]
    {:schema agent-score-schema
     :suite-id (:suite-id prepared)
     :case-id (:case-id prepared)
     :repo-id (:repo-id prepared)
     :project-id (:project-id prepared)
     :caseFingerprint (:caseFingerprint prepared)
     :tags (:tags prepared)
     :expectations (:expectations prepared)
     :baseSha (:baseSha prepared)
     :fixSha (:fixSha prepared)
     :input (:input prepared)
     :inputHints (:inputHints prepared)
     :coverage (:coverage prepared)
     :groundTruth (:groundTruth prepared)
     :agent {:schema (:schema agent-result)
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
             :missingPredictedFiles (missing-predicted-files (:worktreeRoot prepared)
                                                             top-files)}
     :groundTruthRanks (:groundTruthRanks result-with-ranks)
     :scores (benchmark-score/score-result result-with-ranks)}))
