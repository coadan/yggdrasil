(ns agraph.benchmark-prediction
  (:require [agraph.benchmark-prepare :as benchmark-prepare]
            [agraph.text :as text]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def agent-result-schema
  "agraph.benchmark.agent-result/v2")

(def default-agent-baseline-candidate-file-only-quota
  5)
(def ^:private rank-score-token-cap
  5)
(def ^:private rank-score-ordered-pair-cap
  2)
(def ^:private rank-score-ordered-pair-weight
  0.45)
(def ^:private rank-score-compound-pair-weight
  0.35)
(def ^:private rank-score-identity-compound-pair-weight
  1.00)
(def ^:private rank-score-candidate-only-graph-weight
  3.0)
(def ^:private rank-score-graph-support-min
  0.5)
(def ^:private rank-score-graph-lexical-support-min
  0.4)
(def ^:private rank-score-support-count-cap
  2)
(def ^:private rank-score-retrieved-source-count-cap
  2)
(def ^:private retrieved-source-rank-bonus-window
  20)
(def ^:private retrieved-source-rank-bonus-max
  0.55)
(def ^:private retrieved-source-rank-bonus-step
  0.025)
(defn- blankish?
  [value]
  (str/blank? (str value)))
(defn- parse-double-safe
  [value]
  (try
    (when-not (blankish? value)
      (Double/parseDouble (str value)))
    (catch Exception _
      nil)))
(defn- normalize-source-kind
  [value]
  (benchmark-prepare/normalize-source-kind value))
(defn- path-source-kind
  ([path]
   (benchmark-prepare/path-source-kind path))
  ([kind-by-path path]
   (benchmark-prepare/path-source-kind kind-by-path path)))
(defn- scanned-path-kinds
  [root]
  (benchmark-prepare/scanned-path-kinds root))
(defn- existing-file-path?
  [root path]
  (and (not (blankish? path))
       (or (nil? root)
           (.isFile (io/file root path)))))
(defn- bounded-confidence
  [value]
  (if-let [score (parse-double-safe value)]
    (max 0.0 (min 1.0 score))
    0.0))
(defn- line-label
  [source]
  (when-let [lines (seq (:lines source))]
    (str " lines " (str/join "-" lines))))
(defn- evidence-text
  [doc]
  (str/join "\n"
            (remove blankish?
                    [(get-in doc [:source :path])
                     (get-in doc [:source :heading])
                     (:snippet doc)])))
(defn- token-matches
  [query-tokens text]
  (let [query-token-set (set query-tokens)
        evidence-token-set (set (text/tokenize text))]
    (set/intersection query-token-set evidence-token-set)))
(defn- ordered-token-pairs
  [tokens]
  (set (map vector tokens (rest tokens))))
(defn- compact-token-pair-matches
  [query-tokens text]
  (let [query-pairs (ordered-token-pairs query-tokens)
        evidence-pairs (ordered-token-pairs (text/tokenize text))]
    (set/intersection query-pairs evidence-pairs)))
(defn- compact-compound-token-pair-matches
  [query-tokens text]
  (let [query-pairs (ordered-token-pairs query-tokens)
        evidence-pairs (set (text/compound-token-pairs text))]
    (set/intersection query-pairs evidence-pairs)))
(defn- identity-text
  [& values]
  (str/join "\n" (remove blankish? values)))
(defn- identity-compound-token-pair-matches
  [query-tokens & values]
  (compact-compound-token-pair-matches query-tokens (apply identity-text values)))
(defn- retrieved-source-rank-score
  [retrieved-source-count first-source-rank]
  (if (and (pos? retrieved-source-count)
           (pos? (long (or first-source-rank 0)))
           (<= first-source-rank retrieved-source-rank-bonus-window))
    (max 0.0
         (- retrieved-source-rank-bonus-max
            (* retrieved-source-rank-bonus-step
               (dec first-source-rank))))
    0.0))
(defn- candidate-only-graph-boost
  [graph-score evidence-score]
  (* rank-score-candidate-only-graph-weight
     (double graph-score)
     (min 1.0 (double evidence-score))))
(defn- doc-prediction
  [root query-tokens idx doc]
  (let [source (:source doc)
        path (:path source)]
    (when (existing-file-path? root path)
      {:path path
       :source-rank (inc idx)
       :confidence (bounded-confidence (:score doc))
       :evidence-score (double (or (parse-double-safe (:score doc)) 0.0))
       :evidence-kind :doc
       :retrieved-source? (boolean (:retrievedSource doc))
       :exact-path-source? (boolean (:exactPathSource doc))
       :definition-kind (some-> (:definitionKind source) name)
       :matched-tokens (token-matches query-tokens (evidence-text doc))
       :matched-compound-token-pairs (compact-compound-token-pair-matches
                                      query-tokens
                                      (evidence-text doc))
       :evidence [(str "context-doc:"
                       path
                       (line-label source)
                       " provenance="
                       (or (:provenance doc) "unknown"))]
       :reason (str "AGraph context doc"
                    (when-let [heading (:heading source)]
                      (str " " (pr-str heading)))
                    " from " path
                    (line-label source)
                    " with provenance "
                    (or (:provenance doc) "unknown")
                    ".")})))
(defn- entity-prediction
  [root query-tokens idx entity]
  (let [path (:path entity)]
    (when (existing-file-path? root path)
      {:path path
       :source-rank (+ 1000 (inc idx))
       :confidence (bounded-confidence (:score entity))
       :evidence-score (double (or (parse-double-safe (:score entity)) 0.0))
       :evidence-kind :entity
       :retrieved-source? false
       :exact-path-source? false
       :definition-kind (some-> (:kind entity) name)
       :matched-tokens (token-matches query-tokens
                                      (str (:path entity)
                                           "\n"
                                           (:label entity)))
       :matched-token-pairs (compact-token-pair-matches
                             query-tokens
                             (str (:path entity)
                                  "\n"
                                  (:label entity)))
       :matched-compound-token-pairs (compact-compound-token-pair-matches
                                      query-tokens
                                      (str (:path entity)
                                           "\n"
                                           (:label entity)))
       :matched-identity-compound-token-pairs (identity-compound-token-pair-matches
                                               query-tokens
                                               (:path entity)
                                               (:label entity))
       :evidence [(str "graph-entity:"
                       (or (:label entity) path)
                       " path="
                       path)]
       :reason (str "AGraph graph entity "
                    (pr-str (:label entity))
                    " references "
                    path
                    ".")})))
(defn- score-component-evidence
  [score-components]
  (when (seq score-components)
    (str " components="
         (str/join ","
                   (->> score-components
                        (map (fn [[k v]]
                               [(if (keyword? k) (name k) (str k)) v]))
                        (sort-by first)
                        (map (fn [[k v]]
                               (str k ":" v))))))))
(defn- candidate-file-evidence
  [candidate score-components path]
  (str "candidate-file:"
       path
       " rank="
       (:rank candidate)
       (when-let [target-kind (some-> (:targetKind candidate) name)]
         (str " targetKind=" target-kind))
       (when-let [label (not-empty (str (:label candidate)))]
         (str " label=" (pr-str label)))
       (when-some [score (:score candidate)]
         (str " score=" score))
       (score-component-evidence score-components)))
(defn- candidate-file-prediction
  [root query-tokens idx candidate]
  (let [path (:path candidate)]
    (when (existing-file-path? root path)
      (let [evidence-text (str (:path candidate)
                               "\n"
                               (:label candidate))
            matched-tokens (token-matches query-tokens evidence-text)
            matched-token-pairs (compact-token-pair-matches query-tokens evidence-text)
            matched-compound-token-pairs (compact-compound-token-pair-matches
                                          query-tokens
                                          evidence-text)
            matched-identity-compound-token-pairs (identity-compound-token-pair-matches
                                                   query-tokens
                                                   (:path candidate)
                                                   (:label candidate))
            score-components (or (:scoreComponents candidate)
                                 (:score-components candidate))
            graph-score (double (or (parse-double-safe (:graph score-components))
                                    0.0))
            lexical-score (double (or (parse-double-safe (:lexical score-components))
                                      0.0))
            graph-score-supported? (or (>= (count matched-tokens) 2)
                                       (seq matched-token-pairs)
                                       (and (>= graph-score
                                                rank-score-graph-support-min)
                                            (>= lexical-score
                                                rank-score-graph-lexical-support-min)))]
        (cond-> {:path path
                 :source-rank (+ 500 (inc idx))
                 :confidence (bounded-confidence (:score candidate))
                 :evidence-score (* 0.6 (double (or (parse-double-safe (:score candidate))
                                                    0.0)))
                 :evidence-kind :candidate-file
                 :retrieved-source? false
                 :exact-path-source? false
                 :definition-kind (some-> (:targetKind candidate) name)
                 :matched-tokens matched-tokens
                 :matched-token-pairs matched-token-pairs
                 :matched-compound-token-pairs matched-compound-token-pairs
                 :matched-identity-compound-token-pairs matched-identity-compound-token-pairs
                 :evidence [(candidate-file-evidence candidate
                                                     score-components
                                                     path)]
                 :reason (str "AGraph retrieved candidate file "
                              path
                              " from result rank "
                              (:rank candidate)
                              ".")}
          (and (pos? graph-score)
               graph-score-supported?)
          (assoc :graph-neighbor-score graph-score))))))
(defn- architecture-evidence-text
  [row]
  (str/join "\n"
            (remove blankish?
                    [(:path row)
                     (:kind row)
                     (:fileKind row)
                     (:relation row)
                     (:label row)
                     (:normalizedValue row)
                     (:source row)
                     (:target row)])))
(defn- architecture-file-evidence
  [section row path]
  (str "architecture-evidence:"
       section
       ":"
       path
       (when-let [kind (not-empty (str (:kind row)))]
         (str " kind=" kind))
       (when-let [file-kind (not-empty (str (or (:fileKind row)
                                                (:file-kind row)
                                                (:sourceKind row)
                                                (:source-kind row))))]
         (str " fileKind=" file-kind))
       (when-let [relation (not-empty (str (:relation row)))]
         (str " relation=" relation))
       (when-let [label (not-empty (str (:label row)))]
         (str " label=" (pr-str label)))
       (when-some [score (:score row)]
         (str " score=" score))))
(defn- architecture-file-prediction
  [root query-tokens idx section row]
  (let [path (:path row)]
    (when (existing-file-path? root path)
      (let [evidence-text (architecture-evidence-text row)]
        {:path path
         :source-rank (+ 700 (inc idx))
         :confidence (bounded-confidence (:score row))
         :evidence-score (* 0.7 (double (or (parse-double-safe (:score row)) 0.0)))
         :evidence-kind :candidate-file
         :retrieved-source? false
         :exact-path-source? false
         :definition-kind (some-> (:kind row) str)
         :matched-tokens (token-matches query-tokens evidence-text)
         :matched-token-pairs (compact-token-pair-matches query-tokens evidence-text)
         :matched-compound-token-pairs (compact-compound-token-pair-matches
                                        query-tokens
                                        evidence-text)
         :matched-identity-compound-token-pairs (identity-compound-token-pair-matches
                                                 query-tokens
                                                 (:path row)
                                                 (:label row)
                                                 (:kind row)
                                                 (:relation row))
         :evidence [(architecture-file-evidence section row path)]
         :reason (str "AGraph architecture "
                      section
                      " row "
                      (or (:id row) path)
                      " references "
                      path
                      ".")}))))
(defn- architecture-file-rows
  [root query-tokens packet]
  (vec
   (concat
    (keep-indexed #(architecture-file-prediction root
                                                 query-tokens
                                                 %1
                                                 "runtimeEvidence"
                                                 %2)
                  (get-in packet [:architecture :runtimeEvidence]))
    (keep-indexed #(architecture-file-prediction root
                                                 query-tokens
                                                 %1
                                                 "boundaryEvidence"
                                                 %2)
                  (get-in packet [:architecture :boundaryEvidence]))
    (keep-indexed #(architecture-file-prediction root
                                                 query-tokens
                                                 %1
                                                 "dependencyEvidence"
                                                 %2)
                  (get-in packet [:architecture :dependencyEvidence])))))
(defn- ranked-file-predictions
  [rows]
  (let [combine-rows (fn [path grouped-rows]
                       (let [ordered (sort-by :source-rank grouped-rows)
                             best-row (first ordered)
                             support-count (count ordered)
                             extra-count (dec support-count)
                             confidence (bounded-confidence
                                         (apply max
                                                (map :confidence ordered)))
                             matched-tokens (->> ordered
                                                 (mapcat :matched-tokens)
                                                 set)
                             matched-token-pairs (->> ordered
                                                      (mapcat :matched-token-pairs)
                                                      set)
                             matched-compound-token-pairs (->> ordered
                                                               (mapcat :matched-compound-token-pairs)
                                                               set)
                             matched-identity-compound-token-pairs (->> ordered
                                                                        (mapcat :matched-identity-compound-token-pairs)
                                                                        set)
                             evidence (->> ordered
                                           (mapcat :evidence)
                                           (remove blankish?)
                                           distinct
                                           vec)
                             doc-count (count (filter #(= :doc (:evidence-kind %))
                                                      ordered))
                             entity-count (count (filter #(= :entity (:evidence-kind %))
                                                         ordered))
                             candidate-count (count (filter #(= :candidate-file (:evidence-kind %))
                                                            ordered))
                             graph-neighbor-score (apply max
                                                         0.0
                                                         (keep :graph-neighbor-score
                                                               ordered))
                             candidate-only-graph-score (if (zero? doc-count)
                                                          graph-neighbor-score
                                                          0.0)
                             candidate-only-compound-pair-count (if (zero? doc-count)
                                                                  (count matched-compound-token-pairs)
                                                                  0)
                             retrieved-source-count (count (filter :retrieved-source?
                                                                   ordered))
                             exact-path-source-count (count (filter :exact-path-source?
                                                                    ordered))
                             max-evidence-score (apply max
                                                       0.0
                                                       (map :evidence-score ordered))
                             source-rank-score (retrieved-source-rank-score
                                                retrieved-source-count
                                                (:source-rank best-row))
                             graph-neighbor-boost (candidate-only-graph-boost
                                                   candidate-only-graph-score
                                                   max-evidence-score)
                             rank-score (+ max-evidence-score
                                           source-rank-score
                                           (* 0.22 (min rank-score-token-cap
                                                        (count matched-tokens)))
                                           (* rank-score-ordered-pair-weight
                                              (min rank-score-ordered-pair-cap
                                                   (count matched-token-pairs)))
                                           (* rank-score-compound-pair-weight
                                              (min rank-score-ordered-pair-cap
                                                   candidate-only-compound-pair-count))
                                           (* rank-score-identity-compound-pair-weight
                                              (min rank-score-ordered-pair-cap
                                                   (count matched-identity-compound-token-pairs)))
                                           (* 0.08 (min rank-score-support-count-cap
                                                        support-count))
                                           (* 0.08 (min rank-score-retrieved-source-count-cap
                                                        retrieved-source-count))
                                           (* 0.12 exact-path-source-count)
                                           (* 0.04 candidate-count)
                                           (* 0.03 entity-count)
                                           graph-neighbor-boost)
                             metrics (cond-> {:firstSourceRank (:source-rank best-row)
                                              :supportCount support-count
                                              :docCount doc-count
                                              :entityCount entity-count
                                              :candidateFileCount candidate-count
                                              :retrievedSourceCount retrieved-source-count
                                              :exactPathSourceCount exact-path-source-count
                                              :maxConfidence confidence
                                              :rankScore rank-score
                                              :matchedTokenCount (count matched-tokens)
                                              :definitionKinds (->> ordered
                                                                    (keep :definition-kind)
                                                                    distinct
                                                                    vec)}
                                       (seq matched-token-pairs)
                                       (assoc :matchedTokenPairCount
                                              (count matched-token-pairs))
                                       (seq matched-compound-token-pairs)
                                       (assoc :matchedCompoundTokenPairCount
                                              (count matched-compound-token-pairs))
                                       (seq matched-identity-compound-token-pairs)
                                       (assoc :matchedIdentityCompoundTokenPairCount
                                              (count matched-identity-compound-token-pairs))
                                       (pos? source-rank-score)
                                       (assoc :sourceRankScore source-rank-score)
                                       (pos? graph-neighbor-score)
                                       (assoc :graphNeighborScore graph-neighbor-score)
                                       (pos? graph-neighbor-boost)
                                       (assoc :graphNeighborBoost graph-neighbor-boost))]
                         (cond-> (assoc best-row
                                        :path path
                                        :confidence confidence
                                        :rank-score rank-score
                                        :evidence evidence
                                        :metrics metrics)
                           (pos? extra-count)
                           (update :reason
                                   str
                                   " Additional AGraph evidence: "
                                   extra-count
                                   " more matching "
                                   (if (= 1 extra-count) "row" "rows")
                                   "."))))]
    (->> rows
         (group-by :path)
         (map (fn [[path grouped-rows]]
                (combine-rows path grouped-rows)))
         (sort-by (juxt (comp - :rank-score)
                        :source-rank
                        :path))
         (map-indexed (fn [idx row]
                        (-> row
                            (dissoc :source-rank
                                    :rank-score
                                    :evidence-score
                                    :evidence-kind
                                    :graph-neighbor-score
                                    :retrieved-source?
                                    :exact-path-source?
                                    :matched-tokens
                                    :matched-token-pairs
                                    :matched-compound-token-pairs
                                    :matched-identity-compound-token-pairs
                                    :definition-kind)
                            (assoc :rank (inc idx)))))
         vec)))
(defn- coverage-source-kinds
  [coverage]
  (->> (or (:declaredSourceKinds coverage)
           (:declared-source-kinds coverage)
           (:sourceKinds coverage)
           (:source-kinds coverage))
       (keep normalize-source-kind)
       set))
(defn- keep-coverage-source-kind?
  [source-kinds kind-by-path row]
  (or (empty? source-kinds)
      (contains? source-kinds (path-source-kind kind-by-path (:path row)))))
(defn- renumber-file-ranks
  [rows]
  (mapv (fn [idx row]
          (assoc row :rank (inc idx)))
        (range)
        rows))
(defn- candidate-file-only-row?
  [row]
  (let [metrics (:metrics row)]
    (and (pos? (long (or (:candidateFileCount metrics) 0)))
         (zero? (long (or (:docCount metrics) 0)))
         (zero? (long (or (:entityCount metrics) 0))))))
(defn- selected-source-kind-counts
  [kind-by-path rows]
  (frequencies
   (keep #(path-source-kind kind-by-path (:path %)) rows)))
(defn- source-kind-diversity-replacement-index
  [kind-by-path rows]
  (let [counts (selected-source-kind-counts kind-by-path rows)]
    (->> rows
         (map-indexed vector)
         reverse
         (some (fn [[idx row]]
                 (let [kind (path-source-kind kind-by-path (:path row))]
                   (when (< 1 (long (or (get counts kind) 0)))
                     idx)))))))
(defn- best-source-kind-candidate
  [kind-by-path selected-paths candidate-files source-kind]
  (->> candidate-files
       (filter #(and (= source-kind
                        (path-source-kind kind-by-path (:path %)))
                     (not (contains? selected-paths (:path %)))))
       first))
(defn- preserve-source-kind-diversity
  [candidate-files selected source-kinds kind-by-path]
  (if (or (empty? source-kinds)
          (empty? kind-by-path))
    selected
    (loop [selected (vec selected)
           missing-kinds (->> source-kinds
                              sort
                              (remove (set (keys (selected-source-kind-counts
                                                  kind-by-path
                                                  selected))))
                              vec)]
      (if-let [source-kind (first missing-kinds)]
        (let [selected-paths (set (map :path selected))
              candidate (best-source-kind-candidate kind-by-path
                                                    selected-paths
                                                    candidate-files
                                                    source-kind)
              replace-idx (source-kind-diversity-replacement-index kind-by-path
                                                                   selected)]
          (recur (if (and candidate replace-idx)
                   (assoc selected replace-idx candidate)
                   selected)
                 (subvec missing-kinds 1)))
        selected))))
(defn- select-limited-suspected-files
  ([candidate-files limit]
   (select-limited-suspected-files candidate-files limit {}))
  ([candidate-files limit {:keys [source-kinds kind-by-path]}]
   (if-not limit
     {:files (vec candidate-files)}
     (let [limit (long limit)
           quota (min default-agent-baseline-candidate-file-only-quota limit)
           candidate-file-only (take quota
                                     (filter candidate-file-only-row?
                                             candidate-files))
           quota-paths (set (map :path candidate-file-only))
           remaining-limit (max 0 (- limit (count candidate-file-only)))
           primary (->> candidate-files
                        (remove #(contains? quota-paths (:path %)))
                        (take remaining-limit))
           selected (->> (preserve-source-kind-diversity
                          candidate-files
                          (sort-by :rank (concat primary candidate-file-only))
                          source-kinds
                          kind-by-path)
                         (sort-by :rank)
                         renumber-file-ranks)]
       (cond-> {:files selected}
         (seq candidate-file-only)
         (assoc :candidateFileOnlyQuota quota
                :candidateFileOnlySelected (count candidate-file-only)))))))
(defn- context-symbols
  [packet]
  (->> (:docs packet)
       (map-indexed
        (fn [idx doc]
          (let [source (:source doc)
                path (:path source)]
            (when (and (:path source) (:heading source))
              {:name (:heading source)
               :path path
               :rank (inc idx)
               :kind (or (some-> (:definitionKind source) name)
                         "unknown")
               :confidence (bounded-confidence (:score doc))
               :reason (str "AGraph context doc "
                            (pr-str (:heading source))
                            " references "
                            path
                            (line-label source)
                            ".")
               :evidence [(str "context-doc:"
                               path
                               (line-label source)
                               " provenance="
                               (or (:provenance doc) "unknown"))]}))))
       (keep identity)
       (reduce (fn [best row]
                 (let [k [(:path row) (:name row)]]
                   (if (contains? best k)
                     best
                     (assoc best k row))))
               {})
       vals
       (sort-by (juxt :rank :path :name))
       vec))
(defn- next-action-commands
  [actions]
  (keep (fn [action]
          (let [command (:command action)]
            (when-not (blankish? command)
              command)))
        actions))
(defn- packet-next-action-commands
  [packet]
  (let [architecture (:architecture packet)]
    (->> (concat (:nextActions packet)
                 (get-in packet [:answerability :nextActions])
                 (get-in packet [:freshness :nextActions])
                 (get-in packet [:sourceCoverage :nextActions])
                 (get-in packet [:evidence :nextActions])
                 (:nextActions architecture)
                 (mapcat :nextActions (:validationGaps architecture)))
         next-action-commands)))
(defn- drilldown-command
  [drilldown]
  (cond
    (map? drilldown) (:command drilldown)
    :else drilldown))
(defn packet-commands
  [packet]
  (->> (concat (map drilldown-command (:drilldowns packet))
               (get-in packet [:answerability :next])
               (packet-next-action-commands packet))
       (remove blankish?)
       distinct
       vec))
(defn context-packet->agent-result
  "Convert one AGraph context packet into the benchmark agent-result contract.

  This is a deterministic agent-help baseline: it ranks files from the same
  docs/entities packet an agent would receive, without reading hidden ground
  truth or fix artifacts."
  ([packet]
   (context-packet->agent-result packet {}))
  ([packet {:keys [agent-id mode case-id caseFingerprint root limit coverage]}]
   (let [query-tokens (text/tokenize-all (:query packet))
         source-kinds (coverage-source-kinds coverage)
         kind-by-path (if (or (empty? source-kinds)
                              (str/blank? (str root)))
                        {}
                        (scanned-path-kinds root))
         doc-rows (keep-indexed #(doc-prediction root query-tokens %1 %2) (:docs packet))
         entity-rows (keep-indexed #(entity-prediction root query-tokens %1 %2) (:entities packet))
         candidate-file-rows (keep-indexed #(candidate-file-prediction root query-tokens %1 %2)
                                           (:candidateFiles packet))
         architecture-rows (architecture-file-rows root query-tokens packet)
         raw-candidate-files (ranked-file-predictions (concat doc-rows
                                                              entity-rows
                                                              candidate-file-rows
                                                              architecture-rows))
         candidate-files (->> raw-candidate-files
                              (filter #(keep-coverage-source-kind? source-kinds
                                                                   kind-by-path
                                                                   %))
                              renumber-file-ranks
                              vec)
         filtered-files (- (count raw-candidate-files)
                           (count candidate-files))
         selected-files (select-limited-suspected-files
                         candidate-files
                         limit
                         {:source-kinds source-kinds
                          :kind-by-path kind-by-path})
         selection (cond-> {:rawCandidateFiles (count raw-candidate-files)
                            :candidateFiles (count candidate-files)
                            :coverageFilteredCandidateFiles filtered-files
                            :limit limit
                            :coverageSourceKinds (vec (sort source-kinds))}
                     (:candidateFileOnlyQuota selected-files)
                     (assoc :candidateFileOnlyQuota (:candidateFileOnlyQuota selected-files)
                            :candidateFileOnlySelected (:candidateFileOnlySelected selected-files)))
         suspected-files (:files selected-files)]
     (cond-> {:schema agent-result-schema
              :caseId case-id
              :agentId (or agent-id "agraph-baseline")
              :mode (or mode "agraph")
              :suspectedFiles suspected-files
              :suspectedSymbols (context-symbols packet)
              :commands (packet-commands packet)
              :warnings (vec (or (:warnings packet) []))
              :selection selection
              :summary (str "Deterministic AGraph baseline ranked "
                            (count suspected-files)
                            " suspected files from "
                            (count candidate-files)
                            " context packet file candidates.")}
       caseFingerprint (assoc :caseFingerprint caseFingerprint)))))
