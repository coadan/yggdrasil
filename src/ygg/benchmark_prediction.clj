(ns ygg.benchmark-prediction
  (:require [ygg.benchmark-prepare :as benchmark-prepare]
            [ygg.benchmark-util :as benchmark-util]
            [ygg.context :as context]
            [ygg.text :as text]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def agent-result-schema
  "ygg.benchmark.agent-result/v2")

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
(def ^:private rank-score-doc-compound-pair-weight
  0.12)
(def ^:private rank-score-identity-compound-pair-weight
  1.00)
(def ^:private rank-score-identity-compound-span-min
  3)
(def ^:private rank-score-identity-compound-span-cap
  2)
(def ^:private rank-score-identity-compound-span-weight
  0.8)
(def ^:private rank-score-candidate-only-graph-weight
  3.0)
(def ^:private rank-score-doc-supported-graph-weight
  0.5)
(def ^:private rank-score-decision-candidate-count-cap
  2)
(def ^:private rank-score-decision-candidate-path-weight
  1.75)
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
  4.1)
(def ^:private retrieved-source-rank-bonus-step
  0.10)
(def ^:private candidate-source-rank-bonus-window
  20)
(def ^:private candidate-source-rank-bonus-max
  0.2)
(def ^:private candidate-source-rank-bonus-step
  0.01)
(def ^:private candidate-only-source-rank-bonus-max
  5.8)
(def ^:private candidate-only-source-rank-bonus-step
  0.18)
(def ^:private robust-candidate-only-support-min
  2)
(def ^:private robust-candidate-only-graph-min
  0.5)
(def ^:private robust-candidate-only-source-rank-window
  3)
(def ^:private robust-candidate-only-boost
  3.5)
(defn- parse-double-safe
  [value]
  (try
    (when-not (benchmark-util/blankish? value)
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
  (and (not (benchmark-util/blankish? path))
       (or (nil? root)
           (.isFile (io/file root path)))))
(defn- row-root
  [root roots row]
  (or (get roots (or (:repo-id row) (:repo row)))
      root))
(defn- multi-root-map?
  [roots]
  (and (map? roots)
       (< 1 (count roots))))
(defn- single-root-map-root
  [roots]
  (when (map? roots)
    (val (first roots))))
(defn- prediction-repo-id
  [roots repo-id]
  (when (multi-root-map? roots)
    repo-id))
(defn- file-row-key
  [row]
  [(or (:repo-id row) (:repo row)) (:path row)])
(defn- row-path-kind
  [kind-by-path row]
  (if (and (map? kind-by-path)
           (contains? kind-by-path (or (:repo-id row) (:repo row))))
    (path-source-kind (get kind-by-path (or (:repo-id row) (:repo row)))
                      (:path row))
    (path-source-kind kind-by-path (:path row))))
(defn- bounded-confidence
  [value]
  (if-let [score (parse-double-safe value)]
    (max 0.0 (min 1.0 score))
    0.0))
(defn- field-name
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))
(defn- decision-candidate-id
  [candidate]
  (some-> (:id candidate) str not-empty))
(defn- decision-kind-name
  [kind candidates]
  (or (some-> kind field-name not-empty)
      (some->> candidates
               (keep #(some-> (:kind %) field-name not-empty))
               first)))
(defn- decision-candidate-paths
  [candidate]
  (->> (concat (:paths candidate)
               (:evidencePaths candidate)
               (:evidence-paths candidate)
               (:files candidate)
               (map :path (:evidence candidate)))
       (keep #(some-> % str not-empty))
       distinct
       vec))
(defn- line-label
  [source]
  (when-let [lines (seq (:lines source))]
    (str " lines " (str/join "-" lines))))
(defn- evidence-text
  [doc]
  (str/join "\n"
            (remove benchmark-util/blankish?
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
  (str/join "\n" (remove benchmark-util/blankish? values)))
(defn- identity-tail
  [value]
  (when-not (benchmark-util/blankish? value)
    (let [value (str value)
          slash-tail (or (last (remove str/blank? (str/split value #"/")))
                         value)
          file-tail (str/replace slash-tail #"\.[A-Za-z0-9]+$" "")]
      file-tail)))
(defn- identity-tail-values
  [& values]
  (->> values
       flatten
       (keep identity-tail)
       (remove benchmark-util/blankish?)))
(defn- identity-compound-token-pair-matches
  [query-tokens & values]
  (compact-compound-token-pair-matches query-tokens (apply identity-text values)))
(defn- token-windows
  [n tokens]
  (let [tokens (vec tokens)
        n (long n)]
    (when (and (pos? n)
               (<= n (count tokens)))
      (map #(subvec tokens % (+ % n))
           (range (inc (- (count tokens) n)))))))
(defn- token-window-match?
  [tokens window]
  (some #(= window %) (token-windows (count window) tokens)))
(defn- identity-compound-token-span-length
  [query-tokens & values]
  (let [candidate-tokens (text/tokenize-all
                          (apply identity-text
                                 (apply identity-tail-values values)))
        max-window (min (count query-tokens) (count candidate-tokens))]
    (or (some (fn [n]
                (when (some #(token-window-match? candidate-tokens %)
                            (token-windows n query-tokens))
                  n))
              (range max-window
                     (dec rank-score-identity-compound-span-min)
                     -1))
        0)))
(defn- identity-compound-token-span-score
  [span-length]
  (* rank-score-identity-compound-span-weight
     (min rank-score-identity-compound-span-cap
          (max 0 (- (long (or span-length 0))
                    (dec rank-score-identity-compound-span-min))))))
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
(defn- robust-candidate-only?
  [candidate-source-rank doc-count support-count graph-neighbor-score]
  (and (zero? (long doc-count))
       (<= robust-candidate-only-support-min
           (long (or support-count 0)))
       (or (<= robust-candidate-only-graph-min
               (double (or graph-neighbor-score 0.0)))
           (and (pos? (long (or candidate-source-rank 0)))
                (<= (long candidate-source-rank)
                    robust-candidate-only-source-rank-window)))))
(defn- candidate-source-rank-score
  [candidate-source-rank doc-count support-count graph-neighbor-score]
  (if (and (pos? (long (or candidate-source-rank 0)))
           (<= candidate-source-rank candidate-source-rank-bonus-window))
    (let [[bonus-max bonus-step] (if (robust-candidate-only? candidate-source-rank
                                                             doc-count
                                                             support-count
                                                             graph-neighbor-score)
                                   [candidate-only-source-rank-bonus-max
                                    candidate-only-source-rank-bonus-step]
                                   [candidate-source-rank-bonus-max
                                    candidate-source-rank-bonus-step])]
      (max 0.0
           (- bonus-max
              (* bonus-step
                 (dec candidate-source-rank)))))
    0.0))
(defn- candidate-only-robust-boost
  [candidate-source-rank doc-count support-count graph-neighbor-score]
  (if (and (robust-candidate-only? candidate-source-rank
                                   doc-count
                                   support-count
                                   graph-neighbor-score)
           (or (not (pos? (long (or candidate-source-rank 0))))
               (< candidate-source-rank-bonus-window
                  (long candidate-source-rank))))
    robust-candidate-only-boost
    0.0))
(defn- graph-neighbor-boost
  [doc-count graph-score evidence-score]
  (* (if (zero? (long doc-count))
       rank-score-candidate-only-graph-weight
       rank-score-doc-supported-graph-weight)
     (double graph-score)
     (min 1.0 (double evidence-score))))
(defn- doc-prediction
  [root roots query-tokens idx doc]
  (let [source (:source doc)
        path (:path source)
        source-repo-id (:repo source)
        repo-id (prediction-repo-id roots source-repo-id)
        file-root (row-root root roots {:repo-id source-repo-id :path path})]
    (when (existing-file-path? file-root path)
      (cond-> {:path path
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
               :matched-identity-compound-token-pairs (identity-compound-token-pair-matches
                                                       query-tokens
                                                       path)
               :matched-identity-compound-token-span-length
               (identity-compound-token-span-length query-tokens path (:heading source))
               :evidence [(str "context-doc:"
                               path
                               (line-label source)
                               " provenance="
                               (or (:provenance doc) "unknown"))]
               :reason (str "Yggdrasil context doc"
                            (when-let [heading (:heading source)]
                              (str " " (pr-str heading)))
                            " from " path
                            (line-label source)
                            " with provenance "
                            (or (:provenance doc) "unknown")
                            ".")}
        repo-id (assoc :repo-id repo-id)))))
(defn- entity-prediction
  [root roots query-tokens idx entity]
  (let [path (:path entity)
        source-repo-id (:repo entity)
        repo-id (prediction-repo-id roots source-repo-id)
        file-root (row-root root roots {:repo-id source-repo-id :path path})]
    (when (existing-file-path? file-root path)
      (cond-> {:path path
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
               :matched-identity-compound-token-span-length
               (identity-compound-token-span-length query-tokens
                                                    (:path entity)
                                                    (:label entity))
               :evidence [(str "graph-entity:"
                               (or (:label entity) path)
                               " path="
                               path)]
               :reason (str "Yggdrasil graph entity "
                            (pr-str (:label entity))
                            " references "
                            path
                            ".")}
        repo-id (assoc :repo-id repo-id)))))
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
(defn- candidate-line-label
  [candidate]
  (let [source-line (or (:sourceLine candidate)
                        (:source-line candidate))
        end-line (or (:endLine candidate)
                     (:end-line candidate))]
    (when source-line
      (str " lines "
           source-line
           (when end-line
             (str "-" end-line))))))

(defn- candidate-file-evidence
  [candidate score-components path]
  (str "candidate-file:"
       path
       " rank="
       (:rank candidate)
       (candidate-line-label candidate)
       (when-let [target-kind (some-> (:targetKind candidate) name)]
         (str " targetKind=" target-kind))
       (when-let [label (not-empty (str (:label candidate)))]
         (str " label=" (pr-str label)))
       (when-let [support-labels (seq (:supportLabels candidate))]
         (str " supportLabels=" (pr-str (vec support-labels))))
       (when-some [score (:score candidate)]
         (str " score=" score))
       (score-component-evidence score-components)))
(defn- candidate-file-prediction
  [root roots query-tokens idx candidate]
  (let [path (:path candidate)
        source-repo-id (:repo candidate)
        repo-id (prediction-repo-id roots source-repo-id)
        file-root (row-root root roots {:repo-id source-repo-id :path path})]
    (when (existing-file-path? file-root path)
      (let [support-labels (vec (:supportLabels candidate))
            evidence-text (str/join "\n"
                                    (remove benchmark-util/blankish?
                                            (concat [(:path candidate)
                                                     (:label candidate)]
                                                    support-labels)))
            matched-tokens (token-matches query-tokens evidence-text)
            matched-token-pairs (compact-token-pair-matches query-tokens evidence-text)
            matched-compound-token-pairs (compact-compound-token-pair-matches
                                          query-tokens
                                          evidence-text)
            matched-identity-compound-token-pairs (apply
                                                   identity-compound-token-pair-matches
                                                   query-tokens
                                                   (:path candidate)
                                                   (:label candidate)
                                                   support-labels)
            score-components (or (:scoreComponents candidate)
                                 (:score-components candidate))
            candidate-rank (long (or (parse-double-safe (:rank candidate))
                                     (inc idx)))
            graph-score (double (or (parse-double-safe (:graph score-components))
                                    0.0))
            lexical-score (double (or (parse-double-safe (:lexical score-components))
                                      0.0))
            source-graph-score (double (or (parse-double-safe (:sourceGraph score-components))
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
                 :matched-identity-compound-token-span-length
                 (apply identity-compound-token-span-length
                        query-tokens
                        (:path candidate)
                        (:label candidate)
                        support-labels)
                 :evidence [(candidate-file-evidence candidate
                                                     score-components
                                                     path)]
                 :reason (str "Yggdrasil retrieved candidate file "
                              path
                              (candidate-line-label candidate)
                              " from result rank "
                              (:rank candidate)
                              ".")}
          repo-id
          (assoc :repo-id repo-id)

          (pos? source-graph-score)
          (assoc :candidate-source-rank candidate-rank)

          (and (pos? graph-score)
               graph-score-supported?)
          (assoc :graph-neighbor-score graph-score))))))
(defn- decision-candidate-file-evidence
  [candidate path]
  (str "decision-candidate:"
       path
       " candidate="
       (pr-str (decision-candidate-id candidate))
       (when-let [kind (some-> (:kind candidate) field-name not-empty)]
         (str " kind=" kind))
       (when-let [label (some-> (:label candidate) str not-empty)]
         (str " label=" (pr-str label)))))
(defn- decision-candidate-file-predictions
  [root roots query-tokens candidates]
  (->> candidates
       (map-indexed
        (fn [idx candidate]
          (when (decision-candidate-id candidate)
            (let [source-repo-id (or (:repo-id candidate) (:repo candidate))
                  repo-id (prediction-repo-id roots source-repo-id)]
              (->> (decision-candidate-paths candidate)
                   (keep-indexed
                    (fn [path-idx path]
                      (let [file-root (row-root root
                                                roots
                                                {:repo-id source-repo-id
                                                 :path path})]
                        (when (existing-file-path? file-root path)
                          (let [evidence-text (identity-text path
                                                             (:id candidate)
                                                             (:kind candidate)
                                                             (:label candidate)
                                                             (:summary candidate))]
                            (cond-> {:path path
                                     :source-rank (+ 450 (* idx 100) path-idx)
                                     :confidence 0.75
                                     :evidence-score 0.0
                                     :evidence-kind :decision-candidate
                                     :retrieved-source? false
                                     :exact-path-source? false
                                     :definition-kind "decision-candidate"
                                     :matched-tokens (token-matches query-tokens
                                                                    evidence-text)
                                     :matched-token-pairs
                                     (compact-token-pair-matches query-tokens
                                                                 evidence-text)
                                     :matched-compound-token-pairs
                                     (compact-compound-token-pair-matches
                                      query-tokens
                                      evidence-text)
                                     :matched-identity-compound-token-pairs
                                     (identity-compound-token-pair-matches
                                      query-tokens
                                      path
                                      (:label candidate))
                                     :matched-identity-compound-token-span-length
                                     (identity-compound-token-span-length
                                      query-tokens
                                      path
                                      (:label candidate))
                                     :evidence [(decision-candidate-file-evidence
                                                 candidate
                                                 path)]
                                     :reason (str "Visible decision candidate "
                                                  (pr-str (decision-candidate-id
                                                           candidate))
                                                  " references "
                                                  path
                                                  ".")}
                              repo-id (assoc :repo-id repo-id))))))))))))
       (mapcat identity)
       vec))
(defn- architecture-evidence-text
  [row]
  (str/join "\n"
            (remove benchmark-util/blankish?
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
(def ^:private architecture-evidence-score-weights
  {"runtimeEvidence" 0.7
   "boundaryEvidence" 0.7
   "dependencyEvidence" 0.9
   "deployEvidence" 0.7})

(def ^:private architecture-evidence-score-caps
  {"runtimeEvidence" 4.4
   "boundaryEvidence" 1.6
   "dependencyEvidence" 2.4
   "deployEvidence" 1.6})

(defn- runtime-evidence-low-signal?
  [row]
  (= "env-var" (field-name (:kind row))))

(defn- architecture-score-weight
  [section row]
  (if (and (= "runtimeEvidence" section)
           (runtime-evidence-low-signal? row))
    0.35
    (double (get architecture-evidence-score-weights section 0.7))))

(defn- architecture-score-cap
  [section row]
  (if (and (= "runtimeEvidence" section)
           (runtime-evidence-low-signal? row))
    1.0
    (double (get architecture-evidence-score-caps section 1.6))))

(defn- architecture-evidence-score
  [query-tokens section row]
  (let [raw-score (double (or (parse-double-safe (:score row)) 0.0))
        weight (architecture-score-weight section row)
        cap (architecture-score-cap section row)
        package-identity-token-count (if (and (= "dependencyEvidence" section)
                                              (= "package-import" (field-name (:kind row))))
                                       (count (token-matches
                                               query-tokens
                                               (identity-text (:label row)
                                                              (:package row)
                                                              (:import row)
                                                              (:normalizedValue row))))
                                       0)
        package-identity-boost (if (>= package-identity-token-count 2)
                                 0.35
                                 0.0)]
    (min cap (+ (* weight raw-score)
                package-identity-boost))))

(defn- architecture-file-prediction
  [root roots query-tokens idx section row]
  (let [path (:path row)
        source-repo-id (:repo row)
        repo-id (prediction-repo-id roots source-repo-id)
        file-root (row-root root roots {:repo-id source-repo-id :path path})]
    (when (existing-file-path? file-root path)
      (let [evidence-text (architecture-evidence-text row)]
        (cond-> {:path path
                 :source-rank (+ 700 (inc idx))
                 :confidence (bounded-confidence (:score row))
                 :evidence-score (architecture-evidence-score query-tokens section row)
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
                 :matched-identity-compound-token-span-length
                 (identity-compound-token-span-length query-tokens
                                                      (:label row)
                                                      (:kind row)
                                                      (:relation row))
                 :evidence [(architecture-file-evidence section row path)]
                 :reason (str "Yggdrasil architecture "
                              section
                              " row "
                              (or (:id row) path)
                              " references "
                              path
                              ".")}
          repo-id (assoc :repo-id repo-id))))))
(defn- architecture-file-rows
  [root roots query-tokens packet]
  (vec
   (concat
    (keep-indexed #(architecture-file-prediction root
                                                 roots
                                                 query-tokens
                                                 %1
                                                 "runtimeEvidence"
                                                 %2)
                  (get-in packet [:architecture :runtimeEvidence]))
    (keep-indexed #(architecture-file-prediction root
                                                 roots
                                                 query-tokens
                                                 %1
                                                 "boundaryEvidence"
                                                 %2)
                  (get-in packet [:architecture :boundaryEvidence]))
    (keep-indexed #(architecture-file-prediction root
                                                 roots
                                                 query-tokens
                                                 %1
                                                 "dependencyEvidence"
                                                 %2)
                  (get-in packet [:architecture :dependencyEvidence]))
    (keep-indexed #(architecture-file-prediction root
                                                 roots
                                                 query-tokens
                                                 %1
                                                 "deployEvidence"
                                                 %2)
                  (get-in packet [:architecture :deployEvidence])))))
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
                             matched-identity-compound-token-span-length
                             (apply max
                                    0
                                    (keep :matched-identity-compound-token-span-length
                                          ordered))
                             identity-compound-span-score
                             (identity-compound-token-span-score
                              matched-identity-compound-token-span-length)
                             evidence (->> ordered
                                           (mapcat :evidence)
                                           (remove benchmark-util/blankish?)
                                           distinct
                                           vec)
                             doc-count (count (filter #(= :doc (:evidence-kind %))
                                                      ordered))
                             entity-count (count (filter #(= :entity (:evidence-kind %))
                                                         ordered))
                             candidate-count (count (filter #(= :candidate-file (:evidence-kind %))
                                                            ordered))
                             decision-candidate-count (count (filter #(= :decision-candidate
                                                                         (:evidence-kind %))
                                                                     ordered))
                             graph-neighbor-score (apply max
                                                         0.0
                                                         (keep :graph-neighbor-score
                                                               ordered))
                             candidate-only-compound-pair-count (if (zero? doc-count)
                                                                  (count matched-compound-token-pairs)
                                                                  0)
                             doc-supported-compound-pair-count (if (pos? doc-count)
                                                                 (count matched-compound-token-pairs)
                                                                 0)
                             retrieved-source-count (count (filter :retrieved-source?
                                                                   ordered))
                             exact-path-source-count (count (filter :exact-path-source?
                                                                    ordered))
                             candidate-source-rank (some->> ordered
                                                            (keep :candidate-source-rank)
                                                            seq
                                                            (apply min))
                             candidate-source-rank-score (candidate-source-rank-score
                                                          candidate-source-rank
                                                          doc-count
                                                          support-count
                                                          graph-neighbor-score)
                             candidate-only-robust-boost (candidate-only-robust-boost
                                                          candidate-source-rank
                                                          doc-count
                                                          support-count
                                                          graph-neighbor-score)
                             max-evidence-score (apply max
                                                       0.0
                                                       (map :evidence-score ordered))
                             source-rank-score (retrieved-source-rank-score
                                                retrieved-source-count
                                                (:source-rank best-row))
                             graph-neighbor-boost (graph-neighbor-boost
                                                   doc-count
                                                   graph-neighbor-score
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
                                           (* rank-score-doc-compound-pair-weight
                                              (min rank-score-ordered-pair-cap
                                                   doc-supported-compound-pair-count))
                                           (* rank-score-identity-compound-pair-weight
                                              (min rank-score-ordered-pair-cap
                                                   (count matched-identity-compound-token-pairs)))
                                           identity-compound-span-score
                                           (* 0.08 (min rank-score-support-count-cap
                                                        support-count))
                                           (* 0.08 (min rank-score-retrieved-source-count-cap
                                                        retrieved-source-count))
                                           (* 0.12 exact-path-source-count)
                                           (* 0.04 candidate-count)
                                           (* 0.03 entity-count)
                                           (* rank-score-decision-candidate-path-weight
                                              (min rank-score-decision-candidate-count-cap
                                                   decision-candidate-count))
                                           candidate-source-rank-score
                                           candidate-only-robust-boost
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
                                       (pos? matched-identity-compound-token-span-length)
                                       (assoc :matchedIdentityCompoundTokenSpanLength
                                              matched-identity-compound-token-span-length)
                                       (pos? identity-compound-span-score)
                                       (assoc :identityCompoundTokenSpanScore
                                              identity-compound-span-score)
                                       (pos? decision-candidate-count)
                                       (assoc :decisionCandidateCount
                                              decision-candidate-count)
                                       (pos? source-rank-score)
                                       (assoc :sourceRankScore source-rank-score)
                                       (pos? candidate-source-rank-score)
                                       (assoc :candidateSourceRank candidate-source-rank
                                              :candidateSourceRankScore candidate-source-rank-score)
                                       (pos? candidate-only-robust-boost)
                                       (assoc :robustCandidateOnlyBoost candidate-only-robust-boost)
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
                                   " Additional Yggdrasil evidence: "
                                   extra-count
                                   " more matching "
                                   (if (= 1 extra-count) "row" "rows")
                                   "."))))]
    (->> rows
         (group-by file-row-key)
         (map (fn [[[_repo-id path] grouped-rows]]
                (combine-rows path grouped-rows)))
         (sort-by (juxt (comp - :rank-score)
                        :source-rank
                        :repo-id
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
                                    :matched-identity-compound-token-span-length
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
      (contains? source-kinds (row-path-kind kind-by-path row))))
(defn- renumber-file-ranks
  [rows]
  (mapv (fn [idx row]
          (assoc row :rank (inc idx)))
        (range)
        rows))
(defn- row-source-kind
  [kind-by-path row]
  (row-path-kind kind-by-path row))
(defn- first-row-by-source-kind
  [kind-by-path rows source-kind excluded-paths]
  (some #(when (and (= source-kind (row-source-kind kind-by-path %))
                    (not (contains? excluded-paths (file-row-key %))))
           %)
        rows))
(defn- prioritize-coverage-source-lanes
  [source-kinds kind-by-path candidate-files]
  (if (or (empty? source-kinds)
          (empty? kind-by-path)
          (empty? candidate-files))
    candidate-files
    (let [head-row (first candidate-files)
          head-kind (row-source-kind kind-by-path head-row)
          remaining-kinds (->> source-kinds
                               sort
                               (remove #{head-kind}))
          prioritized (loop [source-kinds (seq remaining-kinds)
                             selected [head-row]
                             selected-paths #{(file-row-key head-row)}]
                        (if-let [source-kind (first source-kinds)]
                          (if-let [row (first-row-by-source-kind
                                        kind-by-path
                                        candidate-files
                                        source-kind
                                        selected-paths)]
                            (recur (next source-kinds)
                                   (conj selected row)
                                   (conj selected-paths (file-row-key row)))
                            (recur (next source-kinds) selected selected-paths))
                          selected))
          prioritized-paths (set (map file-row-key prioritized))]
      (->> (concat prioritized
                   (remove #(contains? prioritized-paths (file-row-key %))
                           candidate-files))
           vec))))
(defn- candidate-file-only-row?
  [row]
  (let [metrics (:metrics row)]
    (and (pos? (long (or (:candidateFileCount metrics) 0)))
         (zero? (long (or (:docCount metrics) 0)))
         (zero? (long (or (:entityCount metrics) 0))))))
(defn- independently-supported-decision-file?
  [row]
  (let [metrics (:metrics row)
        support-count (long (or (:supportCount metrics) 0))
        decision-candidate-count (long (or (:decisionCandidateCount metrics) 0))]
    (and (pos? decision-candidate-count)
         (< decision-candidate-count support-count))))
(defn- fully-supported-decision-candidate?
  [supported-paths candidate]
  (let [paths (set (decision-candidate-paths candidate))]
    (and (< 1 (count paths))
         (set/subset? paths supported-paths))))
(defn- compact-supported-decision-selection
  [candidate-files limit decision-candidates]
  (let [supported (filter independently-supported-decision-file? candidate-files)
        supported-paths (set (map :path supported))]
    (when (and (seq supported)
               (some #(fully-supported-decision-candidate?
                       supported-paths
                       %)
                     decision-candidates))
      (let [files (cond->> supported
                    limit (take (long limit)))]
        {:files (renumber-file-ranks files)
         :decisionSupportedFileSelected (count files)}))))
(defn- selected-source-kind-counts
  [kind-by-path rows]
  (frequencies
   (keep #(row-path-kind kind-by-path %) rows)))
(defn- source-kind-diversity-replacement-index
  [kind-by-path rows]
  (let [counts (selected-source-kind-counts kind-by-path rows)]
    (->> rows
         (map-indexed vector)
         reverse
         (some (fn [[idx row]]
                 (let [kind (row-path-kind kind-by-path row)]
                   (when (< 1 (long (or (get counts kind) 0)))
                     idx)))))))
(defn- best-source-kind-candidate
  [kind-by-path selected-paths candidate-files source-kind]
  (->> candidate-files
       (filter #(and (= source-kind
                        (row-path-kind kind-by-path %))
                     (not (contains? selected-paths (file-row-key %)))))
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
        (let [selected-paths (set (map file-row-key selected))
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
  ([candidate-files limit {:keys [source-kinds kind-by-path decision-candidates]}]
   (or (compact-supported-decision-selection candidate-files
                                             limit
                                             decision-candidates)
       (if-not limit
         {:files (vec candidate-files)}
         (let [limit (long limit)
               quota (min default-agent-baseline-candidate-file-only-quota limit)
               candidate-file-only (take quota
                                         (filter candidate-file-only-row?
                                                 candidate-files))
               quota-paths (set (map file-row-key candidate-file-only))
               remaining-limit (max 0 (- limit (count candidate-file-only)))
               primary (->> candidate-files
                            (remove #(contains? quota-paths (file-row-key %)))
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
                    :candidateFileOnlySelected (count candidate-file-only))))))))
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
               :reason (str "Yggdrasil context doc "
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
            (when-not (benchmark-util/blankish? command)
              command)))
        actions))
(defn- packet-next-action-commands
  [packet]
  (let [architecture (:architecture packet)]
    (->> (concat (:nextActions packet)
                 (get-in packet [:evidence :nextActions])
                 (get-in packet [:freshness :nextActions])
                 (get-in packet [:sourceCoverage :nextActions])
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
               (packet-next-action-commands packet))
       (remove benchmark-util/blankish?)
       distinct
       vec))
(defn- result-surface-token-usage
  [result-surface]
  (let [input-tokens (context/estimate-tokens result-surface)]
    {:inputTokens input-tokens
     :outputTokens 0
     :totalTokens input-tokens
     :costUsd 0.0
     :source "ygg-baseline-compact-surface-estimate"}))
(defn- decision-file-by-path
  [files]
  (->> files
       (sort-by :rank)
       (reduce (fn [idx file]
                 (update idx (:path file) #(or % file)))
               {})))
(defn- decision-supportable-file?
  [file]
  (let [metrics (:metrics file)
        support-count (long (or (:supportCount metrics) 0))
        decision-candidate-count (long (or (:decisionCandidateCount metrics) 0))]
    (< decision-candidate-count support-count)))
(defn- decision-support
  [file-by-path candidate]
  (let [paths (decision-candidate-paths candidate)
        matched (->> paths
                     (keep (fn [path]
                             (when-let [file (get file-by-path path)]
                               (when (decision-supportable-file? file)
                                 {:path path
                                  :file file}))))
                     vec)
        matched-paths (set (map :path matched))
        rank-score (reduce + 0.0 (map #(/ 1.0 (double (:rank (:file %)))) matched))]
    {:candidate candidate
     :id (decision-candidate-id candidate)
     :paths paths
     :pathSet (set paths)
     :matched matched
     :matchedPathSet matched-paths
     :matchedCount (count matched)
     :coverageRatio (if (seq paths)
                      (/ (double (count matched)) (double (count paths)))
                      0.0)
     :bestRank (some->> matched (map (comp :rank :file)) seq (apply min))
     :supportScore (+ (count matched) rank-score)}))
(defn- supported-decision?
  [support]
  (pos? (long (:matchedCount support))))
(defn- dominates-decision?
  [left right]
  (and (not= (:id left) (:id right))
       (supported-decision? left)
       (supported-decision? right)
       (set/subset? (:pathSet right) (:pathSet left))
       (< (count (:pathSet right)) (count (:pathSet left)))
       (<= (double (:supportScore right))
           (double (:supportScore left)))))
(defn- dominated-decision?
  [supports support]
  (boolean (some #(dominates-decision? % support) supports)))
(defn- decision-choice-evidence
  [support]
  (->> (:matched support)
       (mapv (fn [{:keys [path file]}]
               (str "Ygg baseline ranked file "
                    path
                    " at rank "
                    (:rank file)
                    ".")))))
(defn- decision-choice
  [supports support]
  (let [dominated? (dominated-decision? supports support)
        supported? (supported-decision? support)
        status (cond
                 (and supported? (not dominated?)) "include"
                 dominated? "exclude"
                 :else "defer")
        confidence (case status
                     "include" (min 1.0
                                    (+ 0.45
                                       (* 0.2 (:matchedCount support))
                                       (* 0.25 (:coverageRatio support))))
                     "exclude" 0.7
                     0.35)
        reason (case status
                 "include"
                 (str "Visible candidate paths overlap Ygg-ranked files"
                      (when-let [rank (:bestRank support)]
                        (str "; best rank " rank))
                      ".")
                 "exclude"
                 "A broader visible candidate covers the same ranked path evidence."
                 "Visible candidate paths were not represented in Ygg-ranked files.")]
    {:id (:id support)
     :status status
     :confidence confidence
     :reason reason
     :evidence (decision-choice-evidence support)}))
(defn- baseline-decision
  [decision-kind decision-candidates suspected-files]
  (let [candidates (filterv decision-candidate-id decision-candidates)
        kind (decision-kind-name decision-kind candidates)]
    (when (and kind (seq candidates))
      (let [file-by-path (decision-file-by-path suspected-files)
            supports (mapv #(decision-support file-by-path %) candidates)]
        {:kind kind
         :choices (mapv #(decision-choice supports %) supports)
         :risks []
         :followups []}))))
(defn context-packet->agent-result
  "Convert one Yggdrasil context packet into the benchmark agent-result contract.

  This is a deterministic agent-help baseline: it ranks files from the same
  docs/entities packet an agent would receive, without reading hidden ground
  truth or fix artifacts."
  ([packet]
   (context-packet->agent-result packet {}))
  ([packet {:keys [agent-id mode case-id caseFingerprint agentInputFingerprint root roots limit coverage decision-candidates decision-kind]}]
   (let [query-tokens (text/tokenize-all (:query packet))
         source-kinds (coverage-source-kinds coverage)
         kind-by-path (if (or (empty? source-kinds)
                              (and (str/blank? (str root))
                                   (empty? roots)))
                        {}
                        (if (multi-root-map? roots)
                          (->> roots
                               (map (fn [[repo-id repo-root]]
                                      [repo-id (scanned-path-kinds repo-root)]))
                               (into {}))
                          (scanned-path-kinds (or root
                                                  (single-root-map-root roots)))))
         doc-rows (keep-indexed #(doc-prediction root roots query-tokens %1 %2) (:docs packet))
         entity-rows (keep-indexed #(entity-prediction root roots query-tokens %1 %2) (:entities packet))
         candidate-file-rows (keep-indexed #(candidate-file-prediction root roots query-tokens %1 %2)
                                           (:candidateFiles packet))
         decision-candidate-rows (decision-candidate-file-predictions root
                                                                      roots
                                                                      query-tokens
                                                                      decision-candidates)
         architecture-rows (architecture-file-rows root roots query-tokens packet)
         raw-candidate-files (ranked-file-predictions (concat doc-rows
                                                              entity-rows
                                                              candidate-file-rows
                                                              decision-candidate-rows
                                                              architecture-rows))
         candidate-files (->> raw-candidate-files
                              (filter #(keep-coverage-source-kind? source-kinds
                                                                   kind-by-path
                                                                   %))
                              (prioritize-coverage-source-lanes source-kinds
                                                                kind-by-path)
                              renumber-file-ranks
                              vec)
         filtered-files (- (count raw-candidate-files)
                           (count candidate-files))
         selected-files (select-limited-suspected-files
                         candidate-files
                         limit
                         {:source-kinds source-kinds
                          :kind-by-path kind-by-path
                          :decision-candidates decision-candidates})
         selection (cond-> {:rawCandidateFiles (count raw-candidate-files)
                            :candidateFiles (count candidate-files)
                            :coverageFilteredCandidateFiles filtered-files
                            :limit limit
                            :coverageSourceKinds (vec (sort source-kinds))}
                     (:candidateFileOnlyQuota selected-files)
                     (assoc :candidateFileOnlyQuota (:candidateFileOnlyQuota selected-files)
                            :candidateFileOnlySelected (:candidateFileOnlySelected selected-files)))
         selection (cond-> selection
                     (:decisionSupportedFileSelected selected-files)
                     (assoc :decisionSupportedFileSelected
                            (:decisionSupportedFileSelected selected-files)))
         suspected-files (:files selected-files)
         suspected-symbols (context-symbols packet)
         commands (packet-commands packet)
         warnings (vec (or (:warnings packet) []))
         decision (baseline-decision decision-kind
                                     decision-candidates
                                     suspected-files)
         summary (str "Deterministic Yggdrasil baseline ranked "
                      (count suspected-files)
                      " suspected files from "
                      (count candidate-files)
                      " context packet file candidates.")
         result-surface (cond-> {:schema agent-result-schema
                                 :caseId case-id
                                 :agentId (or agent-id "ygg-baseline")
                                 :mode (or mode "ygg")
                                 :suspectedFiles suspected-files
                                 :suspectedSymbols suspected-symbols
                                 :commands commands
                                 :warnings warnings
                                 :selection selection
                                 :summary summary}
                          caseFingerprint (assoc :caseFingerprint caseFingerprint)
                          agentInputFingerprint (assoc :agentInputFingerprint
                                                       agentInputFingerprint)
                          decision (assoc :decision decision))]
     (assoc result-surface
            :tokenUsage (result-surface-token-usage result-surface)))))
