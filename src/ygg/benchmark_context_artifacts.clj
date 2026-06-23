(ns ygg.benchmark-context-artifacts
  "Byte telemetry for benchmark context artifacts used by external agents."
  (:require [charred.api :as json]
            [clojure.java.io :as io]))

(def schema
  "ygg.benchmark.context-artifacts/v1")

(def artifact-keys
  [:prompt :yggHints :yggFullHints :yggContext])

(def byte-keys
  [:promptBytes
   :compactHintsBytes
   :fullHintsBytes
   :contextBytes
   :frontloadBytes
   :expansionBytes
   :fullAvailableBytes
   :hintSavingsBytes])

(defn- canonical-path
  [path]
  (try
    (.getCanonicalPath (io/file path))
    (catch Exception _
      (str path))))

(defn- artifact-entry
  [path]
  (when path
    (let [file (io/file path)
          present? (.isFile file)]
      (cond-> {:path (canonical-path path)
               :present present?}
        present?
        (assoc :bytes (.length file))))))

(defn- byte-count
  [artifacts artifact-key]
  (long (or (get-in artifacts [artifact-key :bytes]) 0)))

(defn- ratio
  [numerator denominator]
  (when (pos? (long denominator))
    (/ (double numerator) (double denominator))))

(defn- utf8-byte-count
  [value]
  (alength (.getBytes (str value) "UTF-8")))

(defn- read-json-file
  [path]
  (try
    (json/read-json (slurp path) :key-fn keyword)
    (catch Exception _
      nil)))

(defn- read-plan-telemetry
  [artifacts]
  (when-let [hints-path (get-in artifacts [:yggHints :path])]
    (when (get-in artifacts [:yggHints :present])
      (let [snippets (vec (get-in (read-json-file hints-path)
                                  [:readPlan :snippets]))]
        (when (seq snippets)
          {:readPlanSnippetCount (count snippets)
           :readPlanSnippetBytes (reduce + 0
                                         (map #(utf8-byte-count (:snippet %))
                                              snippets))})))))

(defn context-artifact-telemetry
  "Return byte telemetry for prompt and Yggdrasil context artifacts.

  `:yggHints` is the compact first-pass artifact. `:yggFullHints` and
  `:yggContext` are expansion artifacts available when the agent needs more
  detail.
  "
  [paths]
  (let [artifacts (->> artifact-keys
                       (keep (fn [artifact-key]
                               (when-let [entry (artifact-entry
                                                 (get paths artifact-key))]
                                 [artifact-key entry])))
                       (into {}))]
    (when (seq artifacts)
      (let [prompt-bytes (byte-count artifacts :prompt)
            compact-hints-bytes (byte-count artifacts :yggHints)
            full-hints-bytes (byte-count artifacts :yggFullHints)
            context-bytes (byte-count artifacts :yggContext)
            frontload-bytes (+ prompt-bytes compact-hints-bytes)
            expansion-bytes (+ full-hints-bytes context-bytes)
            hint-savings-bytes (max 0 (- full-hints-bytes compact-hints-bytes))
            read-plan (read-plan-telemetry artifacts)]
        (cond-> (cond-> {:schema schema
                         :artifacts artifacts
                         :promptBytes prompt-bytes
                         :compactHintsBytes compact-hints-bytes
                         :fullHintsBytes full-hints-bytes
                         :contextBytes context-bytes
                         :frontloadBytes frontload-bytes
                         :expansionBytes expansion-bytes
                         :fullAvailableBytes (+ frontload-bytes
                                                expansion-bytes)
                         :hintSavingsBytes hint-savings-bytes}
                  read-plan
                  (merge read-plan))
          (pos? full-hints-bytes)
          (assoc :hintSavingsRatio (ratio hint-savings-bytes full-hints-bytes))
          (pos? expansion-bytes)
          (assoc :frontloadToExpansionRatio (ratio frontload-bytes
                                                   expansion-bytes)))))))

(defn- telemetry-row
  [result]
  (when-let [telemetry (:contextArtifacts result)]
    (assoc telemetry :case-id (:case-id result))))

(defn- aggregate-byte-field
  [rows byte-key]
  (let [values (keep byte-key rows)
        total (reduce + 0 values)]
    {:total total
     :average (if (seq values)
                (/ (double total) (double (count values)))
                0.0)}))

(defn aggregate-context-artifact-telemetry
  "Aggregate `:contextArtifacts` rows from agent score results."
  [results]
  (let [rows (keep telemetry-row results)]
    (when (seq rows)
      (let [totals (into {}
                         (map (fn [byte-key]
                                [byte-key (reduce + 0 (keep byte-key rows))]))
                         byte-keys)
            full-hints-bytes (long (or (:fullHintsBytes totals) 0))
            hint-savings-bytes (long (or (:hintSavingsBytes totals) 0))
            expansion-bytes (long (or (:expansionBytes totals) 0))
            frontload-bytes (long (or (:frontloadBytes totals) 0))
            read-plan-snippet-counts (vec (keep :readPlanSnippetCount rows))
            read-plan-snippet-bytes (vec (keep :readPlanSnippetBytes rows))
            read-plan-present? (seq read-plan-snippet-counts)
            read-plan-snippet-count (reduce + 0 read-plan-snippet-counts)
            read-plan-snippet-byte-total (reduce + 0 read-plan-snippet-bytes)]
        (cond-> {:runs (count rows)
                 :caseIds (->> rows
                               (map :case-id)
                               distinct
                               sort
                               vec)
                 :totals totals
                 :averages (into {}
                                 (map (fn [byte-key]
                                        [byte-key (aggregate-byte-field rows
                                                                        byte-key)]))
                                 byte-keys)}
          read-plan-present?
          (assoc-in [:totals :readPlanSnippetCount] read-plan-snippet-count)
          read-plan-present?
          (assoc-in [:totals :readPlanSnippetBytes]
                    read-plan-snippet-byte-total)
          read-plan-present?
          (assoc-in [:averages :readPlanSnippetCount]
                    (aggregate-byte-field rows :readPlanSnippetCount))
          read-plan-present?
          (assoc-in [:averages :readPlanSnippetBytes]
                    (aggregate-byte-field rows :readPlanSnippetBytes))
          (pos? full-hints-bytes)
          (assoc :hintSavingsRatio (ratio hint-savings-bytes full-hints-bytes))
          (pos? expansion-bytes)
          (assoc :frontloadToExpansionRatio (ratio frontload-bytes
                                                   expansion-bytes)))))))
