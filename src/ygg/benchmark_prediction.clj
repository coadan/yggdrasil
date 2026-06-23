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
(def ^:private rank-score-long-identity-compound-span-min
  4)
(def ^:private rank-score-identity-compound-span-cap
  2)
(def ^:private rank-score-identity-compound-span-weight
  0.8)
(def ^:private rank-score-retrieved-long-identity-compound-span-weight
  2.25)
(def ^:private rank-score-retrieved-early-long-identity-compound-span-window
  5)
(def ^:private rank-score-retrieved-early-long-identity-compound-span-weight
  2.0)
(def ^:private rank-score-candidate-only-graph-weight
  3.0)
(def ^:private rank-score-doc-supported-graph-weight
  0.5)
(def ^:private rank-score-candidate-support-label-cap
  4)
(def ^:private rank-score-candidate-support-label-weight
  0.06)
(def ^:private rank-score-direct-file-identity-support-cap
  4)
(def ^:private rank-score-direct-file-identity-support-weight
  2.0)
(def ^:private rank-score-retrieved-support-label-cap
  2)
(def ^:private rank-score-retrieved-support-label-weight
  2.4)
(def ^:private rank-score-doc-supported-source-graph-head-window
  3)
(def ^:private rank-score-doc-supported-source-graph-head-max
  3.0)
(def ^:private rank-score-doc-supported-source-graph-head-step
  0.5)
(def ^:private rank-score-decision-candidate-count-cap
  2)
(def ^:private rank-score-decision-candidate-path-weight
  1.75)
(def ^:private rank-score-doc-supported-candidate-evidence-weight
  0.8)
(def ^:private rank-score-doc-supported-candidate-evidence-cap
  1.2)
(def ^:private rank-score-architecture-support-weight
  0.45)
(def ^:private rank-score-architecture-support-cap
  1.0)
(def ^:private rank-score-query-supported-architecture-only-weight
  0.45)
(def ^:private rank-score-query-supported-architecture-only-cap
  2.0)
(def ^:private rank-score-candidate-only-architecture-support-weight
  2.4)
(def ^:private rank-score-candidate-only-architecture-support-cap
  3.8)
(def ^:private rank-score-candidate-lexical-component-weight
  0.15)
(def ^:private rank-score-candidate-lexical-component-cap
  0.2)
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
(def ^:private inspection-direct-file-quota
  4)
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
(defn- result-scope-name
  [value]
  (some-> value field-name))
(defn- inspection-files-scope?
  [result-scope]
  (= "inspection-files" (result-scope-name result-scope)))
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
(defn- compact-identity
  [value]
  (some-> value
          str
          str/lower-case
          (str/replace #"[^a-z0-9]+" "")
          not-empty))
(defn- file-stem-identity
  [path]
  (compact-identity (identity-tail path)))
(defn- file-identity-support-label-count
  [path support-labels]
  (let [stem (file-stem-identity path)]
    (if (and stem (<= 4 (count stem)))
      (->> support-labels
           (keep compact-identity)
           distinct
           (filter #(str/includes? % stem))
           count)
      0)))
(defn- retrieved-doc-identities
  [docs]
  (->> docs
       (mapcat (fn [doc]
                 [(get-in doc [:source :path])
                  (get-in doc [:source :heading])]))
       (keep compact-identity)
       distinct
       set))
(defn- retrieved-support-label-count
  [path label support-labels retrieved-identities]
  (let [path-identity (compact-identity path)]
    (->> (concat [label] support-labels)
         (keep compact-identity)
         distinct
         (remove #(= path-identity %))
         (filter (fn [support-identity]
                   (some #(or (str/includes? % support-identity)
                              (str/includes? support-identity %))
                         retrieved-identities)))
         count)))
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
(defn- retrieved-long-identity-compound-span-score
  [retrieved-source-count span-length]
  (if (and (pos? (long (or retrieved-source-count 0)))
           (<= rank-score-long-identity-compound-span-min
               (long (or span-length 0))))
    rank-score-retrieved-long-identity-compound-span-weight
    0.0))
(defn- retrieved-early-long-identity-compound-span-score
  [retrieved-source-count first-source-rank span-length]
  (let [first-source-rank (long (or first-source-rank 0))
        span-length (long (or span-length 0))]
    (if (and (pos? (long (or retrieved-source-count 0)))
             (pos? first-source-rank)
             (<= first-source-rank
                 rank-score-retrieved-early-long-identity-compound-span-window)
             (<= rank-score-long-identity-compound-span-min span-length))
      (* rank-score-retrieved-early-long-identity-compound-span-weight
         (/ (double (inc (- rank-score-retrieved-early-long-identity-compound-span-window
                            first-source-rank)))
            (double rank-score-retrieved-early-long-identity-compound-span-window)))
      0.0)))
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
(defn- doc-supported-candidate-evidence-boost
  [doc-count source-graph-candidate-evidence-score]
  (if (and (pos? (long doc-count))
           (pos? (double source-graph-candidate-evidence-score)))
    (min rank-score-doc-supported-candidate-evidence-cap
         (* rank-score-doc-supported-candidate-evidence-weight
            (double source-graph-candidate-evidence-score)))
    0.0))
(defn- architecture-support-boost
  [support-count architecture-evidence-count architecture-evidence-score]
  (if (and (pos? (long architecture-evidence-count))
           (< (long architecture-evidence-count) (long support-count))
           (pos? (double architecture-evidence-score)))
    (min rank-score-architecture-support-cap
         (* rank-score-architecture-support-weight
            (double architecture-evidence-score)))
    0.0))
(defn- query-supported-architecture-only-boost
  [support-count
   architecture-evidence-count
   query-supported-architecture-evidence-count
   architecture-evidence-score]
  (if (and (pos? (long architecture-evidence-count))
           (= (long architecture-evidence-count) (long support-count))
           (pos? (long query-supported-architecture-evidence-count))
           (pos? (double architecture-evidence-score)))
    (min rank-score-query-supported-architecture-only-cap
         (* rank-score-query-supported-architecture-only-weight
            (double architecture-evidence-score)))
    0.0))
(defn- candidate-only-architecture-support-boost
  [doc-count
   support-count
   architecture-evidence-count
   query-supported-architecture-evidence-count
   architecture-evidence-score
   matched-token-count]
  (if (and (zero? (long doc-count))
           (pos? (long architecture-evidence-count))
           (< (long architecture-evidence-count) (long support-count))
           (pos? (long query-supported-architecture-evidence-count))
           (<= rank-score-token-cap (long matched-token-count))
           (pos? (double architecture-evidence-score)))
    (min rank-score-candidate-only-architecture-support-cap
         (* rank-score-candidate-only-architecture-support-weight
            (double architecture-evidence-score)))
    0.0))
(defn- candidate-lexical-component-boost
  [candidate-lexical-score]
  (if (pos? (double candidate-lexical-score))
    (min rank-score-candidate-lexical-component-cap
         (* rank-score-candidate-lexical-component-weight
            (double candidate-lexical-score)))
    0.0))
(defn- direct-file-identity-support-boost
  [doc-count
   direct-file-candidate-count
   identity-support-label-count
   graph-neighbor-score]
  (if (and (pos? (long doc-count))
           (pos? (long direct-file-candidate-count))
           (pos? (long identity-support-label-count))
           (pos? (double graph-neighbor-score)))
    (* rank-score-direct-file-identity-support-weight
       (min rank-score-direct-file-identity-support-cap
            (long identity-support-label-count)))
    0.0))
(defn- retrieved-support-label-boost
  [doc-count retrieved-support-label-count]
  (if (and (zero? (long doc-count))
           (pos? (long retrieved-support-label-count)))
    (* rank-score-retrieved-support-label-weight
       (min rank-score-retrieved-support-label-cap
            (long retrieved-support-label-count)))
    0.0))
(defn- doc-supported-source-graph-head-boost
  [doc-count
   candidate-count
   candidate-source-rank
   matched-identity-compound-token-pair-count
   retrieved-support-label-count]
  (if (and (pos? (long doc-count))
           (pos? (long candidate-count))
           (pos? (long (or candidate-source-rank 0)))
           (<= (long candidate-source-rank)
               rank-score-doc-supported-source-graph-head-window)
           (pos? (long matched-identity-compound-token-pair-count))
           (pos? (long retrieved-support-label-count)))
    (max 0.0
         (- rank-score-doc-supported-source-graph-head-max
            (* rank-score-doc-supported-source-graph-head-step
               (dec (long candidate-source-rank)))))
    0.0))
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
  [root roots query-tokens retrieved-identities idx candidate]
  (let [path (:path candidate)
        source-repo-id (:repo candidate)
        repo-id (prediction-repo-id roots source-repo-id)
        file-root (row-root root roots {:repo-id source-repo-id :path path})]
    (when (existing-file-path? file-root path)
      (let [support-labels (vec (:supportLabels candidate))
            target-kind (field-name (or (:targetKind candidate)
                                        (:target-kind candidate)
                                        (:resultKind candidate)
                                        (:result-kind candidate)))
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
            file-identity-support-label-count (file-identity-support-label-count
                                               path
                                               support-labels)
            retrieved-support-label-count (retrieved-support-label-count
                                           path
                                           (:label candidate)
                                           support-labels
                                           retrieved-identities)
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
                 :definition-kind target-kind
                 :matched-tokens matched-tokens
                 :matched-token-pairs matched-token-pairs
                 :matched-compound-token-pairs matched-compound-token-pairs
                 :matched-identity-compound-token-pairs matched-identity-compound-token-pairs
                 :file-identity-support-label-count file-identity-support-label-count
                 :retrieved-support-label-count retrieved-support-label-count
                 :candidate-support-label-count (count support-labels)
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

          (pos? lexical-score)
          (assoc :candidate-lexical-score lexical-score)

          (= "file" target-kind)
          (assoc :direct-file-candidate? true)

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

(defn- architecture-query-supported?
  [query-tokens row]
  (let [evidence-text (architecture-evidence-text row)]
    (or (<= 2 (count (token-matches query-tokens evidence-text)))
        (seq (compact-token-pair-matches query-tokens evidence-text))
        (seq (compact-compound-token-pair-matches query-tokens evidence-text)))))

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

(defn- architecture-support-score
  [query-tokens section row]
  (let [raw-score (double (or (parse-double-safe (:score row)) 0.0))]
    (if (and (= "runtimeEvidence" section)
             (= "env-var" (field-name (:kind row)))
             (architecture-query-supported? query-tokens row))
      (min (double (get architecture-evidence-score-caps section 1.6))
           (* (double (get architecture-evidence-score-weights section 0.7))
              raw-score))
      (architecture-evidence-score query-tokens section row))))

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
                 :architecture-support-score (architecture-support-score query-tokens
                                                                         section
                                                                         row)
                 :query-supported-architecture-evidence?
                 (architecture-query-supported? query-tokens row)
                 :evidence-kind :candidate-file
                 :architecture-evidence? true
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
                             direct-file-candidate-count (count (filter :direct-file-candidate?
                                                                        ordered))
                             file-identity-support-label-count
                             (apply max
                                    0
                                    (keep :file-identity-support-label-count
                                          ordered))
                             retrieved-support-label-count
                             (apply max
                                    0
                                    (keep :retrieved-support-label-count
                                          ordered))
                             decision-candidate-count (count (filter #(= :decision-candidate
                                                                         (:evidence-kind %))
                                                                     ordered))
                             architecture-evidence-count (count (filter :architecture-evidence?
                                                                        ordered))
                             query-supported-architecture-evidence-count
                             (count (filter :query-supported-architecture-evidence?
                                            ordered))
                             graph-neighbor-score (apply max
                                                         0.0
                                                         (keep :graph-neighbor-score
                                                               ordered))
                             source-graph-candidate-evidence-score
                             (apply max
                                    0.0
                                    (keep #(when (:candidate-source-rank %)
                                             (:evidence-score %))
                                          ordered))
                             candidate-lexical-score (apply max
                                                            0.0
                                                            (keep :candidate-lexical-score
                                                                  ordered))
                             architecture-evidence-score (apply max
                                                                0.0
                                                                (keep #(when (:architecture-evidence? %)
                                                                         (:evidence-score %))
                                                                      ordered))
                             architecture-support-evidence-score (apply max
                                                                        0.0
                                                                        (keep #(when (:architecture-evidence? %)
                                                                                 (:architecture-support-score %))
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
                             candidate-support-label-count (apply max
                                                                  0
                                                                  (keep :candidate-support-label-count
                                                                        ordered))
                             candidate-support-label-score (* rank-score-candidate-support-label-weight
                                                              (min rank-score-candidate-support-label-cap
                                                                   candidate-support-label-count))
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
                             doc-supported-source-graph-head-boost
                             (doc-supported-source-graph-head-boost
                              doc-count
                              candidate-count
                              candidate-source-rank
                              (count matched-identity-compound-token-pairs)
                              retrieved-support-label-count)
                             max-evidence-score (apply max
                                                       0.0
                                                       (map :evidence-score ordered))
                             source-rank-score (retrieved-source-rank-score
                                                retrieved-source-count
                                                (:source-rank best-row))
                             retrieved-long-identity-compound-span-score
                             (retrieved-long-identity-compound-span-score
                              retrieved-source-count
                              matched-identity-compound-token-span-length)
                             retrieved-early-long-identity-compound-span-score
                             (retrieved-early-long-identity-compound-span-score
                              retrieved-source-count
                              (:source-rank best-row)
                              matched-identity-compound-token-span-length)
                             graph-neighbor-boost (graph-neighbor-boost
                                                   doc-count
                                                   graph-neighbor-score
                                                   max-evidence-score)
                             doc-supported-candidate-evidence-boost
                             (doc-supported-candidate-evidence-boost
                              doc-count
                              source-graph-candidate-evidence-score)
                             architecture-support-boost (architecture-support-boost
                                                         support-count
                                                         architecture-evidence-count
                                                         architecture-support-evidence-score)
                             query-supported-architecture-only-boost
                             (query-supported-architecture-only-boost
                              support-count
                              architecture-evidence-count
                              query-supported-architecture-evidence-count
                              architecture-evidence-score)
                             candidate-only-architecture-support-boost
                             (candidate-only-architecture-support-boost
                              doc-count
                              support-count
                              architecture-evidence-count
                              query-supported-architecture-evidence-count
                              architecture-support-evidence-score
                              (count matched-tokens))
                             architecture-rank-boost (+ architecture-support-boost
                                                        query-supported-architecture-only-boost
                                                        candidate-only-architecture-support-boost)
                             candidate-lexical-component-boost
                             (candidate-lexical-component-boost
                              candidate-lexical-score)
                             direct-file-identity-support-boost
                             (direct-file-identity-support-boost
                              doc-count
                              direct-file-candidate-count
                              file-identity-support-label-count
                              graph-neighbor-score)
                             retrieved-support-label-boost
                             (retrieved-support-label-boost
                              doc-count
                              retrieved-support-label-count)
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
                                           retrieved-long-identity-compound-span-score
                                           retrieved-early-long-identity-compound-span-score
                                           candidate-support-label-score
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
                                           doc-supported-source-graph-head-boost
                                           graph-neighbor-boost
                                           doc-supported-candidate-evidence-boost
                                           architecture-rank-boost
                                           candidate-lexical-component-boost
                                           direct-file-identity-support-boost
                                           retrieved-support-label-boost)
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
                                       (pos? retrieved-long-identity-compound-span-score)
                                       (assoc :retrievedLongIdentityCompoundTokenSpanScore
                                              retrieved-long-identity-compound-span-score)
                                       (pos? retrieved-early-long-identity-compound-span-score)
                                       (assoc :retrievedEarlyLongIdentityCompoundTokenSpanScore
                                              retrieved-early-long-identity-compound-span-score)
                                       (pos? candidate-support-label-count)
                                       (assoc :candidateSupportLabelCount
                                              candidate-support-label-count)
                                       (pos? candidate-support-label-score)
                                       (assoc :candidateSupportLabelScore
                                              candidate-support-label-score)
                                       (pos? decision-candidate-count)
                                       (assoc :decisionCandidateCount
                                              decision-candidate-count)
                                       (pos? source-rank-score)
                                       (assoc :sourceRankScore source-rank-score)
                                       (pos? (long (or candidate-source-rank 0)))
                                       (assoc :candidateSourceRank candidate-source-rank)
                                       (pos? candidate-source-rank-score)
                                       (assoc :candidateSourceRankScore candidate-source-rank-score)
                                       (pos? doc-supported-source-graph-head-boost)
                                       (assoc :docSupportedSourceGraphHeadBoost
                                              doc-supported-source-graph-head-boost)
                                       (pos? direct-file-candidate-count)
                                       (assoc :directFileCandidateCount
                                              direct-file-candidate-count)
                                       (pos? file-identity-support-label-count)
                                       (assoc :fileIdentitySupportLabelCount
                                              file-identity-support-label-count)
                                       (pos? direct-file-identity-support-boost)
                                       (assoc :directFileIdentitySupportBoost
                                              direct-file-identity-support-boost)
                                       (pos? retrieved-support-label-count)
                                       (assoc :retrievedSupportLabelCount
                                              retrieved-support-label-count)
                                       (pos? retrieved-support-label-boost)
                                       (assoc :retrievedSupportLabelBoost
                                              retrieved-support-label-boost)
                                       (pos? candidate-only-robust-boost)
                                       (assoc :robustCandidateOnlyBoost candidate-only-robust-boost)
                                       (pos? graph-neighbor-score)
                                       (assoc :graphNeighborScore graph-neighbor-score)
                                       (pos? graph-neighbor-boost)
                                       (assoc :graphNeighborBoost graph-neighbor-boost)
                                       (pos? doc-supported-candidate-evidence-boost)
                                       (assoc :docSupportedCandidateEvidenceBoost
                                              doc-supported-candidate-evidence-boost)
                                       (pos? architecture-rank-boost)
                                       (assoc :architectureSupportBoost
                                              architecture-rank-boost)
                                       (pos? candidate-lexical-component-boost)
                                       (assoc :candidateLexicalComponentBoost
                                              candidate-lexical-component-boost))]
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
                                    :architecture-support-score
                                    :evidence-kind
                                    :graph-neighbor-score
                                    :retrieved-source?
                                    :exact-path-source?
                                    :matched-tokens
                                    :matched-token-pairs
                                    :matched-compound-token-pairs
                                    :matched-identity-compound-token-pairs
                                    :file-identity-support-label-count
                                    :retrieved-support-label-count
                                    :matched-identity-compound-token-span-length
                                    :definition-kind
                                    :architecture-evidence?
                                    :query-supported-architecture-evidence?
                                    :direct-file-candidate?
                                    :candidate-source-rank
                                    :candidate-lexical-score
                                    :candidate-support-label-count)
                            (assoc :rank (inc idx)))))
         vec)))
(defn- coverage-source-kind-order
  [coverage]
  (->> (or (:declaredSourceKinds coverage)
           (:declared-source-kinds coverage)
           (:sourceKinds coverage)
           (:source-kinds coverage))
       (keep normalize-source-kind)
       distinct
       vec))
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

(defn- prediction-path-root
  [row]
  (let [path (str (:path row))]
    (or (first (remove str/blank? (str/split path #"/")))
        path)))

(defn- prediction-primary-definition-kind
  [row]
  (or (first (get-in row [:metrics :definitionKinds]))
      :unknown))

(defn- prediction-diversity-key
  [row]
  (let [definition-kind (prediction-primary-definition-kind row)]
    (when (and (or (pos? (long (or (get-in row [:metrics :docCount]) 0)))
                   (pos? (double (or (get-in row [:metrics :architectureSupportBoost])
                                     0.0))))
               (not= :unknown definition-kind))
      [(or (:repo-id row) :unknown-repo)
       (prediction-path-root row)
       definition-kind])))

(defn- diversify-ranked-file-predictions
  [rows]
  (loop [remaining (vec rows)
         seen #{}
         out []]
    (if (empty? remaining)
      (renumber-file-ranks out)
      (let [[idx row] (or (->> remaining
                               (map-indexed vector)
                               (some (fn [[idx row]]
                                       (when-let [k (prediction-diversity-key
                                                     row)]
                                         (when-not (contains? seen k)
                                           [idx row])))))
                          [0 (first remaining)])]
        (recur (vec (concat (subvec remaining 0 idx)
                            (subvec remaining (inc idx))))
               (cond-> seen
                 (prediction-diversity-key row)
                 (conj (prediction-diversity-key row)))
               (conj out row))))))

(defn- row-source-kind
  [kind-by-path row]
  (row-path-kind kind-by-path row))
(defn- first-row-by-source-kind
  [kind-by-path rows source-kind excluded-paths]
  (some #(when (and (= source-kind (row-source-kind kind-by-path %))
                    (not (contains? excluded-paths (file-row-key %))))
           %)
        rows))
(defn- preserve-ranked-head-for-source-lanes?
  [kind-by-path source-kind-order row]
  (or (empty? source-kind-order)
      (= (first source-kind-order) (row-source-kind kind-by-path row))
      (pos? (double (or (get-in row [:metrics :directFileIdentitySupportBoost])
                        0.0)))))
(defn- prioritize-coverage-source-lanes
  [source-kind-order kind-by-path candidate-files]
  (if (or (empty? source-kind-order)
          (empty? kind-by-path)
          (empty? candidate-files))
    candidate-files
    (let [head-row (first candidate-files)
          preserve-head? (preserve-ranked-head-for-source-lanes?
                          kind-by-path
                          source-kind-order
                          head-row)
          ordered-kinds (if preserve-head?
                          (remove #{(row-source-kind kind-by-path head-row)}
                                  source-kind-order)
                          source-kind-order)
          initial (if preserve-head?
                    [head-row]
                    [])
          initial-paths (set (map file-row-key initial))
          prioritized (loop [source-kinds (seq ordered-kinds)
                             selected initial
                             selected-paths initial-paths]
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
(defn- positive-metric
  [row k]
  (long (or (get-in row [:metrics k]) 0)))
(defn- source-graph-candidate-row?
  [row]
  (pos? (positive-metric row :candidateFileCount)))
(defn- direct-file-candidate-row?
  [row]
  (pos? (positive-metric row :directFileCandidateCount)))
(defn- row-candidate-source-rank
  [row]
  (long (or (get-in row [:metrics :candidateSourceRank])
            Long/MAX_VALUE)))
(defn- row-source-order-key
  [row]
  [(row-candidate-source-rank row)
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- reserve-repo-direct-file-candidates
  [rows quota]
  (let [repos (->> rows
                   (keep :repo-id)
                   distinct
                   vec)]
    (if (< (count repos) 2)
      (take quota rows)
      (let [repo-set (set repos)]
        (loop [remaining (seq rows)
               selected []
               selected-repos #{}]
          (if (or (nil? remaining)
                  (<= quota (count selected)))
            selected
            (let [row (first remaining)
                  repo-id (:repo-id row)
                  missing-repos (set/difference repo-set selected-repos)
                  remaining-slots (- quota (count selected))
                  reserve-missing-repo? (and (contains? selected-repos repo-id)
                                             (seq missing-repos)
                                             (<= remaining-slots
                                                 (count missing-repos)))]
              (recur (next remaining)
                     (if reserve-missing-repo?
                       selected
                       (conj selected row))
                     (cond-> selected-repos
                       repo-id (conj repo-id))))))))))

(defn- inspection-direct-file-candidates
  [candidate-files selection-limit]
  (let [quota (min inspection-direct-file-quota selection-limit)]
    (->> candidate-files
         (filter direct-file-candidate-row?)
         (sort-by row-source-order-key)
         (#(reserve-repo-direct-file-candidates % quota))
         vec)))
(defn- row-repo-counts
  [rows]
  (frequencies (keep :repo-id rows)))
(defn- multi-repo-selection?
  [candidate-files]
  (< 1 (count (set (keep :repo-id candidate-files)))))
(defn- best-source-candidate-by-repo
  [candidate-files selected-paths]
  (->> candidate-files
       (filter #(and (:repo-id %)
                     (source-graph-candidate-row? %)
                     (not (contains? selected-paths (file-row-key %)))))
       (group-by :repo-id)
       (map (fn [[repo-id rows]]
              [repo-id (first (sort-by row-source-order-key rows))]))
       vec))
(defn- inspection-repo-candidates
  [candidate-files selected selection-limit]
  (if (or (not (multi-repo-selection? candidate-files))
          (<= selection-limit (count selected)))
    []
    (let [selected-paths (set (map file-row-key selected))
          selected-counts (row-repo-counts selected)
          remaining (- selection-limit (count selected))]
      (->> (best-source-candidate-by-repo candidate-files selected-paths)
           (sort-by (fn [[repo-id row]]
                      [(long (or (get selected-counts repo-id) 0))
                       (row-candidate-source-rank row)
                       (:rank row)
                       repo-id
                       (:path row)]))
           (map second)
           (take remaining)
           vec))))
(defn- inspection-file-selection
  [candidate-files limit]
  (let [selection-limit (long (or limit (count candidate-files)))]
    (when (and (pos? selection-limit) (seq candidate-files))
      (let [direct (inspection-direct-file-candidates candidate-files selection-limit)
            repo-candidates (inspection-repo-candidates candidate-files
                                                        direct
                                                        selection-limit)
            frontloaded (vec (concat direct repo-candidates))]
        (when (seq frontloaded)
          (let [selected (->> frontloaded
                              (take selection-limit)
                              vec)
                skipped (- (count candidate-files) (count selected))]
            (cond-> {:files (renumber-file-ranks selected)
                     :inspectionDirectFileSelected (count direct)}
              (seq repo-candidates)
              (assoc :inspectionRepoCandidateSelected
                     (count repo-candidates))
              (pos? skipped)
              (assoc :inspectionCandidateFillSkipped skipped))))))))
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
  ([candidate-files limit {:keys [source-kinds kind-by-path result-scope]}]
   (or (when (inspection-files-scope? result-scope)
         (inspection-file-selection candidate-files limit))
       (if-not limit
         {:files (vec candidate-files)}
         (let [limit (long limit)
               quota (min default-agent-baseline-candidate-file-only-quota limit)
               candidate-file-only (take quota
                                         (sort-by row-source-order-key
                                                  (filter candidate-file-only-row?
                                                          candidate-files)))
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

(defn- agent-result-warning?
  [warning]
  (not (str/starts-with? (str warning) "candidate files trimmed to ")))

(defn- agent-result-warnings
  [packet]
  (->> (:warnings packet)
       (filter agent-result-warning?)
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
  ([packet {:keys [agent-id mode case-id caseFingerprint agentInputFingerprint root roots limit coverage decision-candidates decision-kind result-scope]}]
   (let [query-tokens (text/tokenize-all (:query packet))
         source-kind-order (coverage-source-kind-order coverage)
         source-kinds (set source-kind-order)
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
         retrieved-identities (retrieved-doc-identities (:docs packet))
         candidate-file-rows (keep-indexed #(candidate-file-prediction root
                                                                       roots
                                                                       query-tokens
                                                                       retrieved-identities
                                                                       %1
                                                                       %2)
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
                              (prioritize-coverage-source-lanes source-kind-order
                                                                kind-by-path)
                              diversify-ranked-file-predictions
                              renumber-file-ranks
                              vec)
         filtered-files (- (count raw-candidate-files)
                           (count candidate-files))
         selected-files (select-limited-suspected-files
                         candidate-files
                         limit
                         {:source-kinds source-kind-order
                          :kind-by-path kind-by-path
                          :result-scope result-scope})
         selection (cond-> {:rawCandidateFiles (count raw-candidate-files)
                            :candidateFiles (count candidate-files)
                            :coverageFilteredCandidateFiles filtered-files
                            :limit limit
                            :coverageSourceKinds (vec (sort source-kinds))}
                     (:candidateFileOnlyQuota selected-files)
                     (assoc :candidateFileOnlyQuota (:candidateFileOnlyQuota selected-files)
                            :candidateFileOnlySelected (:candidateFileOnlySelected selected-files)))
         selection (cond-> selection
                     (:inspectionDirectFileSelected selected-files)
                     (assoc :inspectionDirectFileSelected
                            (:inspectionDirectFileSelected selected-files))
                     (:inspectionRepoCandidateSelected selected-files)
                     (assoc :inspectionRepoCandidateSelected
                            (:inspectionRepoCandidateSelected selected-files))
                     (:inspectionCandidateFillSkipped selected-files)
                     (assoc :inspectionCandidateFillSkipped
                            (:inspectionCandidateFillSkipped selected-files)))
         suspected-files (:files selected-files)
         suspected-symbols (context-symbols packet)
         commands (packet-commands packet)
         warnings (agent-result-warnings packet)
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
