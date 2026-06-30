(ns ygg.benchmark-prediction
  (:require [ygg.benchmark-prepare :as benchmark-prepare]
            [ygg.benchmark-util :as benchmark-util]
            [ygg.context :as context]
            [ygg.extract.common :as extract-common]
            [ygg.text :as text]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def agent-result-schema
  "ygg.benchmark.agent-result/v2")

(def default-agent-baseline-candidate-file-only-quota
  5)
(def ^:private candidate-file-only-identity-reserve-limit
  2)
(def ^:private candidate-file-only-query-evidence-reserve-limit
  2)
(def ^:private candidate-file-only-path-self-identity-reserve-limit
  1)
(def ^:private candidate-file-only-query-evidence-token-min
  2)
(def ^:private candidate-file-only-query-evidence-score-min
  0.4)
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
(def ^:private rank-score-rare-query-token-cap
  3.2)
(def ^:private rank-score-rare-query-token-weight
  2.4)
(def ^:private rank-score-rare-query-token-min-matched
  3)
(def ^:private support-owner-primary-label-specific-token-min-length
  7)
(def ^:private rank-score-direct-file-identity-support-cap
  4)
(def ^:private rank-score-direct-file-identity-support-weight
  2.0)
(def ^:private rank-score-direct-file-identity-support-rank-window
  20)
(def ^:private rank-score-direct-file-late-identity-support-min
  4)
(def ^:private rank-score-candidate-file-identity-support-rank-window
  20)
(def ^:private rank-score-candidate-file-identity-support-min
  3)
(def ^:private rank-score-candidate-file-identity-support-without-rank-min
  4)
(def ^:private rank-score-candidate-file-identity-support-without-rank-token-min
  4)
(def ^:private rank-score-candidate-file-identity-support-cap
  4)
(def ^:private rank-score-candidate-file-identity-support-weight
  1.75)
(def ^:private rank-score-retrieved-support-label-cap
  2)
(def ^:private rank-score-retrieved-support-label-weight
  1.2)
(def ^:private rank-score-retrieved-support-label-rank-window
  20)
(def ^:private rank-score-doc-supported-source-graph-head-window
  3)
(def ^:private rank-score-doc-supported-source-graph-head-max
  3.0)
(def ^:private rank-score-doc-supported-source-graph-head-step
  0.5)
(def ^:private rank-score-doc-supported-direct-file-compound-rank-window
  5)
(def ^:private rank-score-doc-supported-direct-file-compound-token-min
  5)
(def ^:private rank-score-doc-supported-direct-file-compound-boost
  1.5)
(def ^:private rank-score-doc-supported-direct-file-compound-extra-token-weight
  1.25)
(def ^:private rank-score-doc-supported-direct-file-compound-extra-token-cap
  2)
(def ^:private rank-score-decision-candidate-count-cap
  2)
(def ^:private rank-score-decision-candidate-path-weight
  1.75)
(def ^:private rank-score-doc-supported-candidate-evidence-weight
  0.8)
(def ^:private rank-score-doc-supported-candidate-evidence-cap
  1.2)
(def ^:private rank-score-source-graph-query-evidence-token-min
  6)
(def ^:private rank-score-source-graph-query-evidence-pair-min
  3)
(def ^:private rank-score-source-graph-query-evidence-identity-min
  2)
(def ^:private rank-score-source-graph-query-evidence-stem-part-identity-min
  1)
(def ^:private rank-score-source-graph-query-evidence-stem-part-min
  1)
(def ^:private rank-score-source-graph-query-evidence-path-token-min
  2)
(def ^:private rank-score-source-graph-query-evidence-dense-candidate-min
  20)
(def ^:private rank-score-source-graph-query-evidence-dense-identity-min
  3)
(def ^:private rank-score-source-graph-query-evidence-score-min
  0.5)
(def ^:private rank-score-source-graph-query-evidence-rank-window
  20)
(def ^:private rank-score-source-graph-query-evidence-boost
  9.0)
(def ^:private rank-score-doc-supported-source-graph-query-boost
  2.0)
(def ^:private rank-score-architecture-support-weight
  0.45)
(def ^:private rank-score-architecture-support-cap
  1.25)
(def ^:private rank-score-query-supported-architecture-only-weight
  0.45)
(def ^:private rank-score-query-supported-architecture-only-cap
  2.0)
(def ^:private rank-score-doc-architecture-token-min
  5)
(def ^:private rank-score-doc-architecture-token-weight
  0.8)
(def ^:private rank-score-doc-architecture-token-cap
  4.0)
(def ^:private rank-score-candidate-only-architecture-support-weight
  2.4)
(def ^:private rank-score-candidate-only-architecture-support-cap
  3.8)
(def ^:private rank-score-direct-file-architecture-graph-token-min
  3)
(def ^:private rank-score-direct-file-architecture-graph-base
  2.2)
(def ^:private rank-score-direct-file-architecture-graph-token-weight
  0.6)
(def ^:private rank-score-direct-file-architecture-graph-pair-weight
  0.4)
(def ^:private rank-score-direct-file-architecture-graph-cap
  4.5)
(def ^:private rank-score-candidate-lexical-component-weight
  0.15)
(def ^:private rank-score-candidate-lexical-component-cap
  0.2)
(def ^:private rank-score-candidate-grep-component-weight
  2.0)
(def ^:private rank-score-candidate-grep-component-cap
  1.4)
(def ^:private rank-score-candidate-grep-component-token-min
  2)
(def ^:private local-importer-candidate-limit
  80)
(def ^:private local-importer-row-limit
  120)
(def ^:private local-importer-max-file-bytes
  262144)
(def ^:private rank-score-graph-support-min
  0.5)
(def ^:private rank-score-graph-lexical-support-min
  0.4)
(def ^:private rank-score-support-count-cap
  2)
(def ^:private rank-score-retrieved-source-count-cap
  2)
(def ^:private rank-score-retrieved-source-count-weight
  0.25)
(def ^:private rank-score-repeated-retrieved-source-token-min
  2)
(def ^:private rank-score-repeated-retrieved-source-base
  2.4)
(def ^:private rank-score-repeated-retrieved-source-extra-token-weight
  0.25)
(def ^:private rank-score-repeated-retrieved-source-cap
  3.2)
(def ^:private rank-score-retrieved-path-self-identity-token-min
  3)
(def ^:private rank-score-retrieved-path-self-identity-boost
  8.0)
(def ^:private rank-score-retrieved-query-file-identity-min-length
  8)
(def ^:private rank-score-retrieved-query-file-identity-protected-min-length
  12)
(def ^:private rank-score-retrieved-query-file-identity-boost
  12.0)
(def ^:private rank-score-retrieved-path-query-token-min
  2)
(def ^:private rank-score-retrieved-path-query-token-boost
  6.0)
(def ^:private rank-score-retrieved-path-query-token-extra-weight
  1.25)
(def ^:private rank-score-retrieved-path-query-token-extra-cap
  2.5)
(def ^:private rank-score-retrieved-path-grep-token-min
  3)
(def ^:private rank-score-retrieved-path-grep-score-min
  0.7)
(def ^:private rank-score-retrieved-path-grep-source-score-min
  0.5)
(def ^:private rank-score-retrieved-path-grep-boost
  4.0)
(def ^:private rank-score-candidate-path-self-identity-token-min
  2)
(def ^:private rank-score-candidate-path-self-identity-rank-window
  15)
(def ^:private rank-score-candidate-path-self-identity-boost
  12.0)
(def ^:private rank-score-directory-evidence-doc-min
  2)
(def ^:private rank-score-directory-root-evidence-doc-min
  1)
(def ^:private rank-score-directory-evidence-token-min
  1)
(def ^:private rank-score-directory-evidence-candidate-source-rank-window
  25)
(def ^:private rank-score-directory-evidence-base
  2.0)
(def ^:private rank-score-directory-root-evidence-base
  6.0)
(def ^:private rank-score-directory-evidence-doc-weight
  1.5)
(def ^:private rank-score-directory-evidence-score-weight
  0.12)
(def ^:private rank-score-directory-evidence-cap
  8.0)
(def ^:private rank-score-doc-directory-cluster-doc-min
  3)
(def ^:private rank-score-doc-directory-cluster-token-min
  2)
(def ^:private rank-score-doc-directory-cluster-source-score-min
  0.45)
(def ^:private rank-score-doc-directory-cluster-candidate-rank-window
  20)
(def ^:private rank-score-doc-directory-cluster-base
  5.5)
(def ^:private rank-score-doc-directory-cluster-doc-weight
  0.5)
(def ^:private rank-score-doc-directory-cluster-score-weight
  0.05)
(def ^:private rank-score-doc-directory-cluster-cap
  8.0)
(def ^:private file-identity-part-min-length
  5)
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
(def ^:private candidate-only-source-graph-head-rank-window
  4)
(def ^:private candidate-only-source-graph-head-token-min
  5)
(def ^:private candidate-only-source-graph-head-graph-min
  0.5)
(def ^:private candidate-only-source-graph-head-max
  9.0)
(def ^:private candidate-only-source-graph-head-step
  1.0)
(def ^:private candidate-only-exported-support-rank-window
  25)
(def ^:private candidate-only-exported-support-token-min
  2)
(def ^:private candidate-only-exported-support-graph-min
  0.3)
(def ^:private candidate-only-exported-support-boost-value
  12.0)
(def ^:private doc-supported-exported-support-token-min
  5)
(def ^:private doc-supported-exported-support-path-token-min
  2)
(def ^:private doc-supported-exported-support-graph-min
  0.5)
(def ^:private doc-supported-exported-support-boost-value
  6.0)
(def ^:private unsaturated-decision-tail-min-files
  3)
(def ^:private unsaturated-decision-tail-score-ratio
  0.5)
(def ^:private score-elbow-tail-min-files
  6)
(def ^:private score-elbow-tail-prefix-min-files
  2)
(def ^:private score-elbow-tail-prefix-max-files
  4)
(def ^:private score-elbow-tail-score-ratio
  0.65)
(def ^:private compact-thin-candidate-output-limit
  3)
(def ^:private compact-thin-candidate-output-ratio
  2)
(def ^:private compact-output-preserved-head-count
  4)
(def ^:private compact-output-score-tail-min-files
  4)
(def ^:private compact-output-score-tail-prefix-min-files
  2)
(def ^:private compact-output-score-tail-prefix-max-files
  5)
(def ^:private compact-output-score-tail-score-ratio
  0.8)
(def ^:private compact-output-score-tail-max-limit
  7)
(def ^:private compact-output-candidate-only-max-files
  6)
(def ^:private compact-output-early-source-graph-rank-window
  25)
(def ^:private compact-output-early-source-graph-token-min
  1)
(def ^:private compact-output-doc-source-graph-grep-rank-window
  8)
(def ^:private compact-output-doc-source-graph-grep-token-min
  6)
(def ^:private compact-output-doc-source-graph-grep-source-score-min
  0.5)
(def ^:private compact-output-doc-source-graph-grep-grep-score-min
  0.5)
(def ^:private compact-output-candidate-source-graph-head-source-score-min
  0.5)
(def ^:private compact-output-identity-support-min
  4)
(def ^:private compact-output-architecture-token-min
  5)
(def ^:private compact-output-architecture-rare-token-min
  1.0)
(def ^:private compact-output-doc-supported-sort-rank
  1.5)
(def ^:private compact-output-strong-doc-only-supported-sort-rank
  0.35)
(def ^:private compact-output-strong-doc-supported-sort-rank
  0.45)
(def ^:private compact-output-strong-doc-supported-token-min
  7)
(def ^:private compact-output-strong-doc-supported-token-pair-min
  2)
(def ^:private compact-output-strong-doc-supported-path-query-token-min
  3)
(def ^:private compact-output-doc-directory-evidence-sort-rank
  0.45)
(def ^:private compact-output-retrieved-path-grep-sort-rank
  0.48)
(def ^:private compact-output-doc-source-graph-grep-sort-rank
  0.405)
(def ^:private compact-output-support-owner-source-graph-sort-rank
  0.53)
(def ^:private compact-output-support-owner-source-graph-limit
  2)
(def ^:private compact-output-support-owner-source-graph-source-score-min
  0.5)
(def ^:private compact-output-candidate-source-graph-head-sort-rank
  0.56)
(def ^:private compact-output-after-preserved-head-sort-rank
  (+ (/ (double compact-output-preserved-head-count) 10.0) 0.01))
(def ^:private compact-output-early-query-evidence-source-sort-rank
  (- compact-output-after-preserved-head-sort-rank 0.005))
(def ^:private compact-output-retrieved-supported-sort-rank
  compact-output-early-query-evidence-source-sort-rank)
(def ^:private compact-output-strong-retrieved-label-doc-sort-rank
  0.37)
(def ^:private compact-output-retrieved-label-doc-sort-rank
  1.45)
(def ^:private compact-output-directory-evidence-candidate-sort-rank
  1.48)
(def ^:private compact-output-query-evidence-doc-sort-rank
  2.5)
(def ^:private compact-output-retrieved-path-self-identity-sort-rank
  0.34)
(def ^:private compact-output-retrieved-path-self-identity-sort-step
  0.005)
(def ^:private compact-output-retrieved-path-self-identity-limit
  2)
(def ^:private compact-output-retrieved-path-query-token-sort-rank
  2.75)
(def ^:private compact-output-retrieved-path-query-token-limit
  1)
(def ^:private compact-output-candidate-path-self-identity-sort-rank
  compact-output-strong-retrieved-label-doc-sort-rank)
(def ^:private compact-output-candidate-path-self-identity-limit
  2)
(def ^:private compact-output-candidate-path-self-identity-token-min-length
  8)
(def ^:private compact-output-query-matched-exported-support-sort-rank
  0.55)
(def ^:private compact-output-query-matched-exported-support-limit
  2)
(def ^:private compact-output-doc-supported-source-graph-query-sort-rank
  0.36)
(def ^:private compact-output-retrieved-label-source-sort-rank
  3.5)
(def ^:private compact-output-identity-compound-source-sort-rank
  2.25)
(def ^:private compact-output-source-graph-query-evidence-sort-rank
  0.38)
(def ^:private compact-result-command-limit
  5)
(def ^:private dependency-package-identity-query-token-min
  2)
(def ^:private diversity-bypass-candidate-source-rank-window
  2)
(def ^:private diversity-bypass-support-count-min
  2)
(def ^:private diversity-preserved-head-max-files
  5)
(def ^:private diversity-preserved-head-score-ratio
  0.55)
(def ^:private diversity-preserved-head-token-min
  5)
(def ^:private diversity-preserved-head-source-score-min
  0.4)
(def ^:private diversity-bypass-candidate-identity-source-rank-window
  20)
(def ^:private diversity-bypass-candidate-identity-support-min
  4)
(def ^:private compact-output-strong-retrieved-label-doc-support-min
  5)
(def ^:private compact-output-strong-retrieved-label-doc-token-min
  8)
(def ^:private source-kind-lane-support-score-ratio
  0.8)
(def ^:private inspection-direct-file-quota
  4)
(def ^:private inspection-query-identity-quota
  3)
(def ^:private inspection-missing-repo-source-candidate-reserve
  2)
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
(defn- repo-key-name
  [repo-key]
  (cond
    (nil? repo-key) nil
    (keyword? repo-key) (if-let [repo-namespace (namespace repo-key)]
                          (str repo-namespace "/" (name repo-key))
                          (name repo-key))
    :else (str repo-key)))
(defn- repo-map-get
  [repo-map repo-id]
  (when (map? repo-map)
    (let [target (repo-key-name repo-id)]
      (or (get repo-map repo-id)
          (when target
            (some (fn [[key value]]
                    (when (= target (repo-key-name key))
                      value))
                  repo-map))))))
(defn- row-root
  [root roots row]
  (or (repo-map-get roots (or (:repo-id row) (:repo row)))
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
  (if-let [repo-kinds (let [candidate (repo-map-get kind-by-path
                                                    (or (:repo-id row)
                                                        (:repo row)))]
                        (when (map? candidate)
                          candidate))]
    (path-source-kind repo-kinds (:path row))
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
(defn- token-and-pair-matches
  [query-tokens text]
  (let [query-token-set (set query-tokens)
        query-pairs (ordered-token-pairs query-tokens)
        evidence-tokens (text/tokenize text)]
    {:matched-tokens (set/intersection query-token-set
                                       (set evidence-tokens))
     :matched-token-pairs (set/intersection query-pairs
                                            (ordered-token-pairs evidence-tokens))}))
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
(defn- file-stem-identity-parts
  [path]
  (->> (str/split (str (identity-tail path)) #"[^A-Za-z0-9]+")
       (keep compact-identity)
       (filter #(<= file-identity-part-min-length (count %)))
       distinct
       vec))
(defn- support-label-match-count
  [identities support-labels]
  (if (seq identities)
    (->> support-labels
         (keep compact-identity)
         distinct
         (filter (fn [support-label]
                   (some #(str/includes? support-label %) identities)))
         count)
    0))
(defn- file-identity-support-label-count
  [path support-labels]
  (let [stem (file-stem-identity path)
        stem-identities (when (and stem (<= 4 (count stem)))
                          [stem])
        part-identities (file-stem-identity-parts path)]
    (max (support-label-match-count stem-identities support-labels)
         (support-label-match-count part-identities support-labels))))
(defn- support-label-terminal-identifier
  [value]
  (some-> value
          str
          (str/split #"/")
          last
          (str/split #"\.")
          first
          not-empty))
(defn- exported-support-label?
  [value]
  (when-let [identifier (support-label-terminal-identifier value)]
    (when-let [ch (first identifier)]
      (Character/isUpperCase (char ch)))))
(defn- exported-support-label-count
  [support-labels]
  (count (filter exported-support-label? support-labels)))
(defn- qualified-query-token-pairs
  [query-tokens]
  (->> query-tokens
       (keep (fn [token]
               (let [parts (->> (str/split (str token) #"\.")
                                (keep compact-identity)
                                vec)]
                 (when (<= 2 (count parts))
                   (subvec parts 0 2)))))
       set))
(defn- support-label-segment-pairs
  [value]
  (->> (str/split (str value) #"[/.]")
       (keep compact-identity)
       ordered-token-pairs))
(defn- query-matched-exported-support-label-count
  [query-tokens support-labels]
  (let [query-pairs (qualified-query-token-pairs query-tokens)]
    (->> support-labels
         (filter exported-support-label?)
         (mapcat #(set/intersection query-pairs (support-label-segment-pairs %)))
         distinct
         count)))
(defn- normalized-support-label
  [value]
  (some-> value str str/trim str/lower-case not-empty))
(defn- exported-support-label-signature
  [support-labels]
  (->> support-labels
       (filter exported-support-label?)
       (keep normalized-support-label)
       distinct
       sort
       vec))
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
(defn- path-file-stem
  [file-name]
  (let [file-name (str file-name)
        idx (.lastIndexOf file-name ".")]
    (if (pos? idx)
      (subs file-name 0 idx)
      file-name)))
(defn- path-self-identity-token
  [path]
  (let [parts (->> (str/split (str path) #"/")
                   (remove str/blank?)
                   vec)]
    (when (<= 2 (count parts))
      (let [parent (str/lower-case (nth parts (- (count parts) 2)))
            stem (str/lower-case (path-file-stem (peek parts)))]
        (when (and (not (str/blank? parent))
                   (= parent stem))
          stem)))))
(defn- path-without-extension
  [path]
  (str/replace (str path) #"\.[^./]+$" ""))
(defn- normalized-path-match-token
  [token]
  (let [token (str token)]
    (if (and (< 3 (count token))
             (str/ends-with? token "s"))
      (subs token 0 (dec (count token)))
      token)))
(defn- path-match-token-set
  [value]
  (->> (text/tokenize value)
       (remove #(or (str/includes? % "/")
                    (str/includes? % ".")))
       (map normalized-path-match-token)
       set))
(defn- query-matched-path-token-count
  [query-tokens path]
  (count (set/intersection (set (map normalized-path-match-token query-tokens))
                           (path-match-token-set
                            (path-without-extension path)))))
(defn- query-matched-path-self-identity?
  [query-tokens path]
  (when-let [token (path-self-identity-token path)]
    (contains? (set query-tokens) token)))
(defn- query-file-identity-matches
  [query-tokens & values]
  (let [query-identities (->> query-tokens
                              (keep compact-identity)
                              (filter #(<= rank-score-retrieved-query-file-identity-min-length
                                           (count %)))
                              set)
        file-identities (->> (apply identity-tail-values values)
                             (keep compact-identity)
                             (filter #(<= rank-score-retrieved-query-file-identity-min-length
                                          (count %)))
                             set)]
    (set/intersection query-identities file-identities)))

(defn- query-file-stem-part-matches
  [query-tokens path]
  (let [query-identities (->> query-tokens
                              (keep compact-identity)
                              set)
        file-part-identities (set (file-stem-identity-parts path))]
    (set/intersection query-identities file-part-identities)))

(defn- query-file-identity-match-max-length
  [matches]
  (apply max 0 (map count matches)))
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
(defn- repeated-retrieved-source-boost
  [doc-count retrieved-source-count matched-token-count definition-kinds]
  (if (and (<= 2 (long doc-count))
           (<= 2 (long retrieved-source-count))
           (seq definition-kinds)
           (<= rank-score-repeated-retrieved-source-token-min
               (long matched-token-count)))
    (min rank-score-repeated-retrieved-source-cap
         (+ rank-score-repeated-retrieved-source-base
            (* rank-score-repeated-retrieved-source-extra-token-weight
               (max 0
                    (- (long matched-token-count)
                       rank-score-repeated-retrieved-source-token-min)))))
    0.0))
(defn- retrieved-path-self-identity-boost
  [doc-count
   retrieved-source-count
   matched-token-count
   query-matched-path-self-identity?]
  (if (and (pos? (long doc-count))
           (pos? (long retrieved-source-count))
           query-matched-path-self-identity?
           (<= rank-score-retrieved-path-self-identity-token-min
               (long matched-token-count)))
    rank-score-retrieved-path-self-identity-boost
    0.0))
(defn- retrieved-query-file-identity-boost
  [doc-count
   retrieved-source-count
   query-matched-file-identity-count
   query-matched-file-identity-max-length]
  (if (and (pos? (long doc-count))
           (pos? (long retrieved-source-count))
           (or (< 1 (long query-matched-file-identity-count))
               (<= rank-score-retrieved-query-file-identity-protected-min-length
                   (long query-matched-file-identity-max-length))))
    rank-score-retrieved-query-file-identity-boost
    0.0))
(defn- retrieved-path-query-token-boost
  [doc-count retrieved-source-count matched-path-query-token-count]
  (let [matched-path-query-token-count (long matched-path-query-token-count)]
    (if (and (pos? (long doc-count))
             (pos? (long retrieved-source-count))
             (<= rank-score-retrieved-path-query-token-min
                 matched-path-query-token-count))
      (+ rank-score-retrieved-path-query-token-boost
         (min rank-score-retrieved-path-query-token-extra-cap
              (* rank-score-retrieved-path-query-token-extra-weight
                 (max 0
                      (- matched-path-query-token-count
                         rank-score-retrieved-path-query-token-min)))))
      0.0)))
(defn- retrieved-path-grep-evidence-boost
  [doc-count
   retrieved-source-count
   candidate-count
   matched-path-query-token-count
   candidate-grep-score
   source-graph-candidate-evidence-score]
  (if (and (pos? (long doc-count))
           (pos? (long retrieved-source-count))
           (pos? (long candidate-count))
           (<= rank-score-retrieved-path-grep-token-min
               (long matched-path-query-token-count))
           (<= rank-score-retrieved-path-grep-score-min
               (double candidate-grep-score))
           (<= rank-score-retrieved-path-grep-source-score-min
               (double source-graph-candidate-evidence-score)))
    rank-score-retrieved-path-grep-boost
    0.0))
(defn- candidate-path-self-identity-boost
  [doc-count
   candidate-count
   candidate-source-rank
   matched-token-count
   query-matched-path-self-identity?]
  (if (and (zero? (long doc-count))
           (pos? (long candidate-count))
           query-matched-path-self-identity?
           (pos? (long (or candidate-source-rank 0)))
           (<= (long candidate-source-rank)
               rank-score-candidate-path-self-identity-rank-window)
           (<= rank-score-candidate-path-self-identity-token-min
               (long matched-token-count)))
    rank-score-candidate-path-self-identity-boost
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
(defn- candidate-only-source-graph-head-boost
  [candidate-source-rank doc-count graph-neighbor-score matched-token-count]
  (if (and (zero? (long doc-count))
           (pos? (long (or candidate-source-rank 0)))
           (<= (long candidate-source-rank)
               candidate-only-source-graph-head-rank-window)
           (<= candidate-only-source-graph-head-graph-min
               (double (or graph-neighbor-score 0.0)))
           (<= candidate-only-source-graph-head-token-min
               (long matched-token-count)))
    (max 0.0
         (- candidate-only-source-graph-head-max
            (* candidate-only-source-graph-head-step
               (dec (long candidate-source-rank)))))
    0.0))
(defn- candidate-only-exported-support-boost
  [doc-count
   candidate-count
   candidate-source-rank
   source-graph-candidate-evidence-score
   file-identity-support-label-count
   query-matched-exported-support-label-count
   matched-token-count]
  (if (and (zero? (long doc-count))
           (pos? (long candidate-count))
           (pos? (long (or candidate-source-rank 0)))
           (<= (long candidate-source-rank)
               candidate-only-exported-support-rank-window)
           (<= candidate-only-exported-support-graph-min
               (double source-graph-candidate-evidence-score))
           (pos? (long file-identity-support-label-count))
           (pos? (long query-matched-exported-support-label-count))
           (<= candidate-only-exported-support-token-min
               (long matched-token-count)))
    candidate-only-exported-support-boost-value
    0.0))
(defn- doc-supported-exported-support-boost
  [doc-count
   candidate-count
   source-graph-candidate-evidence-score
   query-matched-exported-support-label-count
   matched-token-count
   matched-path-query-token-count]
  (if (and (pos? (long doc-count))
           (pos? (long candidate-count))
           (<= doc-supported-exported-support-graph-min
               (double source-graph-candidate-evidence-score))
           (pos? (long query-matched-exported-support-label-count))
           (<= doc-supported-exported-support-token-min
               (long matched-token-count))
           (<= doc-supported-exported-support-path-token-min
               (long matched-path-query-token-count)))
    doc-supported-exported-support-boost-value
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
(defn- source-graph-query-evidence-boost
  [candidate-count
   candidate-source-rank
   source-graph-candidate-evidence-score
   file-identity-support-label-count
   query-matched-file-stem-part-count
   matched-token-count
   matched-token-pair-count
   matched-path-query-token-count]
  (if (and (pos? (long candidate-count))
           (pos? (long (or candidate-source-rank 0)))
           (<= (long candidate-source-rank)
               rank-score-source-graph-query-evidence-rank-window)
           (<= rank-score-source-graph-query-evidence-score-min
               (double source-graph-candidate-evidence-score))
           (or (and (<= rank-score-source-graph-query-evidence-token-min
                        (long matched-token-count))
                    (<= rank-score-source-graph-query-evidence-identity-min
                        (long file-identity-support-label-count))
                    (<= rank-score-source-graph-query-evidence-pair-min
                        (long matched-token-pair-count)))
               (and (<= rank-score-source-graph-query-evidence-token-min
                        (long matched-token-count))
                    (<= rank-score-source-graph-query-evidence-path-token-min
                        (long matched-path-query-token-count))
                    (<= rank-score-source-graph-query-evidence-stem-part-min
                        (long query-matched-file-stem-part-count))
                    (<= rank-score-source-graph-query-evidence-stem-part-identity-min
                        (long file-identity-support-label-count)))
               (and (<= rank-score-source-graph-query-evidence-dense-candidate-min
                        (long candidate-count))
                    (<= rank-score-source-graph-query-evidence-dense-identity-min
                        (long file-identity-support-label-count))
                    (<= rank-score-source-graph-query-evidence-path-token-min
                        (long matched-path-query-token-count)))))
    rank-score-source-graph-query-evidence-boost
    0.0))
(defn- doc-supported-source-graph-query-boost
  [doc-count source-graph-query-evidence-boost]
  (if (and (pos? (long doc-count))
           (pos? (double source-graph-query-evidence-boost)))
    rank-score-doc-supported-source-graph-query-boost
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
(defn- doc-supported-architecture-token-boost
  [doc-count
   direct-file-candidate-count
   architecture-evidence-count
   matched-token-count]
  (if (and (pos? (long doc-count))
           (zero? (long direct-file-candidate-count))
           (pos? (long architecture-evidence-count))
           (<= rank-score-doc-architecture-token-min
               (long matched-token-count)))
    (min rank-score-doc-architecture-token-cap
         (* rank-score-doc-architecture-token-weight
            (double (min rank-score-token-cap matched-token-count))))
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
(defn- direct-file-architecture-graph-boost
  [doc-count
   direct-file-candidate-count
   architecture-evidence-count
   query-supported-architecture-evidence-count
   graph-neighbor-score
   matched-token-count
   matched-token-pair-count]
  (if (and (zero? (long doc-count))
           (pos? (long direct-file-candidate-count))
           (pos? (long architecture-evidence-count))
           (pos? (long query-supported-architecture-evidence-count))
           (pos? (double graph-neighbor-score))
           (<= rank-score-direct-file-architecture-graph-token-min
               (long matched-token-count)))
    (min rank-score-direct-file-architecture-graph-cap
         (+ rank-score-direct-file-architecture-graph-base
            (* rank-score-direct-file-architecture-graph-token-weight
               (double (min rank-score-token-cap matched-token-count)))
            (* rank-score-direct-file-architecture-graph-pair-weight
               (double (min rank-score-ordered-pair-cap
                            matched-token-pair-count)))))
    0.0))
(defn- candidate-lexical-component-boost
  [candidate-lexical-score]
  (if (pos? (double candidate-lexical-score))
    (min rank-score-candidate-lexical-component-cap
         (* rank-score-candidate-lexical-component-weight
            (double candidate-lexical-score)))
    0.0))
(defn- candidate-grep-component-boost
  [candidate-grep-score matched-token-count]
  (if (and (pos? (double candidate-grep-score))
           (<= rank-score-candidate-grep-component-token-min
               (long matched-token-count)))
    (min rank-score-candidate-grep-component-cap
         (* rank-score-candidate-grep-component-weight
            (double candidate-grep-score)))
    0.0))
(defn- rare-query-token-score
  [doc-count graph-neighbor-score token-path-counts matched-tokens]
  (if (and (zero? (long doc-count))
           (zero? (double graph-neighbor-score))
           (<= rank-score-rare-query-token-min-matched
               (count matched-tokens)))
    (let [token-score (fn [token]
                        (/ 1.0
                           (double (max 1
                                        (long (or (get token-path-counts token)
                                                  1))))))
          score (reduce + 0.0 (map token-score (seq (set matched-tokens))))]
      (min rank-score-rare-query-token-cap
           (* rank-score-rare-query-token-weight score)))
    0.0))
(defn- direct-file-identity-support-boost
  [doc-count
   direct-file-candidate-count
   identity-support-label-count
   graph-neighbor-score
   candidate-source-rank]
  (if (and (pos? (long direct-file-candidate-count))
           (pos? (long identity-support-label-count))
           (pos? (double graph-neighbor-score))
           (pos? (long doc-count))
           (or (and (pos? (long (or candidate-source-rank 0)))
                    (<= (long candidate-source-rank)
                        rank-score-direct-file-identity-support-rank-window))
               (<= rank-score-direct-file-late-identity-support-min
                   (long identity-support-label-count))))
    (* rank-score-direct-file-identity-support-weight
       (min rank-score-direct-file-identity-support-cap
            (long identity-support-label-count)))
    0.0))
(defn- candidate-file-identity-support-boost
  [doc-count
   candidate-count
   direct-file-candidate-count
   identity-support-label-count
   candidate-source-rank
   matched-token-count]
  (if (and (zero? (long doc-count))
           (pos? (long candidate-count))
           (zero? (long direct-file-candidate-count))
           (<= rank-score-candidate-file-identity-support-min
               (long identity-support-label-count))
           (or (and (pos? (long (or candidate-source-rank 0)))
                    (<= (long candidate-source-rank)
                        rank-score-candidate-file-identity-support-rank-window))
               (and (nil? candidate-source-rank)
                    (<= rank-score-candidate-file-identity-support-without-rank-min
                        (long identity-support-label-count))
                    (<= rank-score-candidate-file-identity-support-without-rank-token-min
                        (long matched-token-count)))))
    (* rank-score-candidate-file-identity-support-weight
       (min rank-score-candidate-file-identity-support-cap
            (long identity-support-label-count)))
    0.0))
(defn- retrieved-support-label-boost
  [doc-count retrieved-support-label-count candidate-source-rank]
  (if (and (zero? (long doc-count))
           (pos? (long retrieved-support-label-count))
           (pos? (long (or candidate-source-rank 0)))
           (<= (long candidate-source-rank)
               rank-score-retrieved-support-label-rank-window))
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
(defn- doc-supported-direct-file-compound-boost
  [doc-count
   direct-file-candidate-count
   first-source-rank
   matched-compound-token-pair-count
   matched-token-count]
  (if (and (pos? (long doc-count))
           (pos? (long direct-file-candidate-count))
           (pos? (long (or first-source-rank 0)))
           (<= (long first-source-rank)
               rank-score-doc-supported-direct-file-compound-rank-window)
           (pos? (long matched-compound-token-pair-count))
           (<= rank-score-doc-supported-direct-file-compound-token-min
               (long matched-token-count)))
    (+ rank-score-doc-supported-direct-file-compound-boost
       (* rank-score-doc-supported-direct-file-compound-extra-token-weight
          (min rank-score-doc-supported-direct-file-compound-extra-token-cap
               (max 0
                    (- (long matched-token-count)
                       rank-score-doc-supported-direct-file-compound-token-min)))))
    0.0))
(defn- doc-prediction
  [root roots query-tokens idx doc]
  (let [source (:source doc)
        path (:path source)
        source-repo-id (:repo source)
        repo-id (prediction-repo-id roots source-repo-id)
        file-root (row-root root roots {:repo-id source-repo-id :path path})]
    (when (existing-file-path? file-root path)
      (let [evidence-text (evidence-text doc)
            query-file-identity-matches (query-file-identity-matches query-tokens
                                                                     path
                                                                     (:heading source))]
        (cond-> {:path path
                 :source-rank (inc idx)
                 :confidence (bounded-confidence (:score doc))
                 :evidence-score (double (or (parse-double-safe (:score doc)) 0.0))
                 :evidence-kind :doc
                 :retrieved-source? (boolean (:retrievedSource doc))
                 :exact-path-source? (boolean (:exactPathSource doc))
                 :definition-kind (some-> (:definitionKind source) name)
                 :matched-tokens (token-matches query-tokens evidence-text)
                 :matched-compound-token-pairs (compact-compound-token-pair-matches
                                                query-tokens
                                                evidence-text)
                 :matched-identity-compound-token-pairs (identity-compound-token-pair-matches
                                                         query-tokens
                                                         path)
                 :matched-identity-compound-token-span-length
                 (identity-compound-token-span-length query-tokens path (:heading source))
                 :query-matched-path-self-identity?
                 (boolean (query-matched-path-self-identity? query-tokens path))
                 :query-matched-file-identity-count
                 (count query-file-identity-matches)
                 :query-matched-file-identity-max-length
                 (query-file-identity-match-max-length
                  query-file-identity-matches)
                 :matched-path-query-token-count
                 (query-matched-path-token-count query-tokens path)
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
          repo-id (assoc :repo-id repo-id))))))
(defn- entity-prediction
  [root roots query-tokens idx entity]
  (let [path (:path entity)
        source-repo-id (:repo entity)
        repo-id (prediction-repo-id roots source-repo-id)
        file-root (row-root root roots {:repo-id source-repo-id :path path})]
    (when (existing-file-path? file-root path)
      (let [evidence-text (identity-text (:path entity)
                                         (:label entity))
            {:keys [matched-tokens matched-token-pairs]}
            (token-and-pair-matches query-tokens evidence-text)]
        (cond-> {:path path
                 :source-rank (+ 1000 (inc idx))
                 :confidence (bounded-confidence (:score entity))
                 :evidence-score (double (or (parse-double-safe (:score entity)) 0.0))
                 :evidence-kind :entity
                 :retrieved-source? false
                 :exact-path-source? false
                 :definition-kind (some-> (:kind entity) name)
                 :matched-tokens matched-tokens
                 :matched-token-pairs matched-token-pairs
                 :matched-compound-token-pairs (compact-compound-token-pair-matches
                                                query-tokens
                                                evidence-text)
                 :matched-identity-compound-token-pairs (identity-compound-token-pair-matches
                                                         query-tokens
                                                         (:path entity)
                                                         (:label entity))
                 :matched-identity-compound-token-span-length
                 (identity-compound-token-span-length query-tokens
                                                      (:path entity)
                                                      (:label entity))
                 :matched-path-query-token-count
                 (query-matched-path-token-count query-tokens path)
                 :evidence [(str "graph-entity:"
                                 (or (:label entity) path)
                                 " path="
                                 path)]
                 :reason (str "Yggdrasil graph entity "
                              (pr-str (:label entity))
                              " references "
                              path
                              ".")}
          repo-id (assoc :repo-id repo-id))))))
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

(defn- candidate-path-directory
  [path]
  (when-let [idx (str/last-index-of (str path) "/")]
    (subs (str path) 0 idx)))

(defn- path-extension
  [path]
  (let [filename (or (last (str/split (str path) #"/"))
                     (str path))]
    (second (re-find #"(\.[^.]+)$" filename))))

(defn- path-in-directory
  [dir filename]
  (if (str/blank? (str dir))
    filename
    (str dir "/" filename)))

(defn- candidate-file-sibling-stem
  [path label]
  (let [candidate-stem (identity-tail path)
        label-stem (support-label-terminal-identifier label)]
    (when (and (not (benchmark-util/blankish? candidate-stem))
               (not (benchmark-util/blankish? label-stem))
               (str/starts-with? candidate-stem (str label-stem ".")))
      label-stem)))

(defn- candidate-file-sibling-paths
  [file-root candidate]
  (let [path (:path candidate)
        dir (candidate-path-directory path)
        ext (path-extension path)]
    (when (and path ext)
      (->> (concat [(:label candidate)] (:supportLabels candidate))
           (keep #(candidate-file-sibling-stem path %))
           distinct
           (map #(path-in-directory dir (str % ext)))
           (remove #{path})
           (filter #(existing-file-path? file-root %))
           vec))))

(defn- candidate-file-sibling-evidence
  [candidate score-components path sibling-path]
  (str "candidate-file-sibling:"
       sibling-path
       " from="
       path
       " rank="
       (:rank candidate)
       (when-let [label (not-empty (str (:label candidate)))]
         (str " label=" (pr-str label)))
       (when-let [support-labels (seq (:supportLabels candidate))]
         (str " supportLabels=" (pr-str (vec support-labels))))
       (when-some [score (:score candidate)]
         (str " score=" score))
       (score-component-evidence score-components)))

(def ^:private local-importer-source-extensions
  #{".astro" ".cjs" ".js" ".jsx" ".mjs" ".svelte" ".ts" ".tsx" ".vue"})

(defn- local-importer-source-file?
  [path]
  (contains? local-importer-source-extensions (path-extension path)))

(defn- module-path-suffixes
  [path]
  (let [path (extract-common/drop-source-extension (str path))
        parts (vec (remove str/blank? (str/split path #"/")))]
    (->> (range 0 (max 0 (dec (count parts))))
         (map #(subvec parts %))
         (filter #(<= 2 (count %)))
         (map #(str/join "/" %)))))

(defn- module-target-keys
  [value]
  (let [raw (extract-common/drop-source-extension (str value))
        slash-value (str/replace raw #"\\" "/")
        slash-parts (vec (remove str/blank? (str/split slash-value #"/")))
        alias-suffix (when (and (seq slash-parts)
                                (str/starts-with? (first slash-parts) "@")
                                (< 1 (count slash-parts)))
                       (str/join "/" (subvec slash-parts 1)))
        slash-suffixes (cond-> [slash-value]
                         alias-suffix (conj alias-suffix))
        dot-value (str/replace slash-value #"/" ".")
        alias-dot (some-> alias-suffix (str/replace #"/" "."))]
    (->> (concat slash-suffixes
                 [dot-value alias-dot])
         (remove benchmark-util/blankish?)
         distinct
         vec)))

(defn- candidate-module-target-keys
  [path]
  (let [suffixes (module-path-suffixes path)]
    (->> (concat suffixes
                 (map #(str/replace % #"/" ".") suffixes)
                 [(extract-common/source-module-name path)])
         (remove benchmark-util/blankish?)
         distinct
         vec)))

(defn- local-importer-candidate-index
  [roots candidates]
  (->> candidates
       (take local-importer-candidate-limit)
       (map-indexed vector)
       (mapcat (fn [[idx candidate]]
                 (let [path (:path candidate)
                       repo-key (when (multi-root-map? roots)
                                  (repo-key-name (:repo candidate)))]
                   (when (and (not (benchmark-util/blankish? path))
                              (local-importer-source-file? path))
                     (map (fn [target-key]
                            [[repo-key target-key]
                             (assoc candidate
                                    ::local-importer-candidate-index
                                    idx)])
                          (candidate-module-target-keys path))))))
       (remove nil?)
       (reduce (fn [idx [k candidate]]
                 (update idx k #(or % candidate)))
               {})))

(defn- local-import-targets
  [root path]
  (let [file (io/file root path)]
    (when (and (.isFile file)
               (<= (.length file) local-importer-max-file-bytes))
      (try
        (->> (str/split-lines (slurp file))
             (map-indexed #(extract-common/js-import-targets %1 path %2))
             (mapcat identity)
             (mapcat #(module-target-keys (:target %)))
             distinct
             vec)
        (catch Exception _
          [])))))

(defn- candidate-file-local-importer-evidence
  [candidate importer-path]
  (str "candidate-file-local-importer:"
       importer-path
       " imports="
       (:path candidate)
       " rank="
       (:rank candidate)
       (when-let [label (not-empty (str (:label candidate)))]
         (str " label=" (pr-str label)))
       (when-let [support-labels (seq (:supportLabels candidate))]
         (str " supportLabels=" (pr-str (vec support-labels))))
       (when-some [score (:score candidate)]
         (str " score=" score))))

(defn- local-importer-file-prediction
  [query-tokens repo-id importer-path candidate]
  (let [candidate-path (:path candidate)
        support-labels (vec (remove benchmark-util/blankish?
                                    (concat [(:label candidate)
                                             candidate-path]
                                            (:supportLabels candidate))))
        evidence-text (str/join "\n" (cons importer-path support-labels))
        {:keys [matched-tokens matched-token-pairs]}
        (token-and-pair-matches query-tokens evidence-text)
        score-components (or (:scoreComponents candidate)
                             (:score-components candidate))
        candidate-rank (long (or (parse-double-safe (:rank candidate))
                                 (inc (long (or (::local-importer-candidate-index
                                                 candidate)
                                                0)))))
        lexical-score (double (or (parse-double-safe (:lexical score-components))
                                  0.0))
        grep-score (double (or (parse-double-safe (:grep score-components))
                               0.0))
        source-graph-score (double (or (parse-double-safe (:sourceGraph
                                                           score-components))
                                       0.0))]
    (cond-> {:path importer-path
             :source-rank (+ 560 candidate-rank)
             :confidence (bounded-confidence (:score candidate))
             :evidence-score (* 0.55 (double (or (parse-double-safe
                                                  (:score candidate))
                                                 0.0)))
             :evidence-kind :candidate-file
             :retrieved-source? false
             :exact-path-source? false
             :definition-kind "local-importer"
             :matched-tokens matched-tokens
             :matched-token-pairs matched-token-pairs
             :matched-compound-token-pairs
             (compact-compound-token-pair-matches query-tokens evidence-text)
             :matched-identity-compound-token-pairs
             (apply identity-compound-token-pair-matches
                    query-tokens
                    importer-path
                    support-labels)
             :matched-identity-compound-token-span-length
             (apply identity-compound-token-span-length
                    query-tokens
                    importer-path
                    support-labels)
             :matched-path-query-token-count
             (query-matched-path-token-count query-tokens importer-path)
             :file-identity-support-label-count
             (file-identity-support-label-count importer-path support-labels)
             :candidate-support-label-signature
             (exported-support-label-signature support-labels)
             :candidate-support-label-count (count support-labels)
             :evidence [(candidate-file-local-importer-evidence candidate
                                                                importer-path)]
             :reason (str "Yggdrasil derived local importer file "
                          importer-path
                          " from retrieved candidate "
                          candidate-path
                          ".")}
      repo-id
      (assoc :repo-id repo-id)

      (pos? source-graph-score)
      (assoc :candidate-source-rank candidate-rank)

      (pos? lexical-score)
      (assoc :candidate-lexical-score lexical-score)

      (pos? grep-score)
      (assoc :candidate-grep-score grep-score))))

(defn- local-importer-root-entries
  [root roots]
  (if (map? roots)
    (mapv (fn [[repo-id repo-root]]
            {:source-repo-id repo-id
             :repo-id (prediction-repo-id roots repo-id)
             :root repo-root})
          roots)
    [{:source-repo-id nil
      :repo-id nil
      :root root}]))

(defn- local-importer-file-predictions
  [root roots kind-by-path query-tokens candidates]
  (let [candidate-index (local-importer-candidate-index roots candidates)]
    (if (empty? candidate-index)
      []
      (->> (for [{:keys [source-repo-id repo-id root]} (local-importer-root-entries
                                                        root
                                                        roots)
                 :let [path-kinds (if (map? kind-by-path)
                                    (or (repo-map-get kind-by-path source-repo-id)
                                        kind-by-path)
                                    {})]
                 [path _kind] path-kinds
                 :when (local-importer-source-file? path)
                 :let [repo-key (when (multi-root-map? roots)
                                  (repo-key-name source-repo-id))]
                 target-key (local-import-targets root path)
                 :let [candidate (get candidate-index [repo-key target-key])]
                 :when (and candidate
                            (not= path (:path candidate)))]
             (local-importer-file-prediction query-tokens
                                             repo-id
                                             path
                                             candidate))
           (take local-importer-row-limit)
           vec))))

(defn- candidate-file-prediction
  [root roots query-tokens retrieved-identities idx candidate]
  (let [path (:path candidate)
        source-repo-id (:repo candidate)
        repo-id (prediction-repo-id roots source-repo-id)
        file-root (row-root root roots {:repo-id source-repo-id :path path})]
    (when (existing-file-path? file-root path)
      (let [support-labels (vec (:supportLabels candidate))
            support-owner-evidence? (true? (:supportOwnerEvidence candidate))
            support-owner-primary-label-matched-tokens
            (if support-owner-evidence?
              (token-matches query-tokens (first support-labels))
              #{})
            support-owner-primary-label-query-token-count
            (count support-owner-primary-label-matched-tokens)
            support-owner-primary-label-specific-token-count
            (count (filter #(<= support-owner-primary-label-specific-token-min-length
                                (count %))
                           support-owner-primary-label-matched-tokens))
            target-kind (field-name (or (:targetKind candidate)
                                        (:target-kind candidate)
                                        (:resultKind candidate)
                                        (:result-kind candidate)))
            evidence-text (str/join "\n"
                                    (remove benchmark-util/blankish?
                                            (concat [(:path candidate)
                                                     (:label candidate)]
                                                    support-labels)))
            {:keys [matched-tokens matched-token-pairs]}
            (token-and-pair-matches query-tokens evidence-text)
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
            query-file-stem-part-matches (query-file-stem-part-matches
                                          query-tokens
                                          path)
            exported-support-label-count (exported-support-label-count
                                          support-labels)
            query-matched-exported-support-label-count
            (query-matched-exported-support-label-count query-tokens
                                                        support-labels)
            exported-support-label-signature (exported-support-label-signature
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
            grep-score (double (or (parse-double-safe (:grep score-components))
                                   0.0))
            source-graph-score (double (or (parse-double-safe (:sourceGraph score-components))
                                           0.0))
            architecture-evidence? (true? (:architectureEvidence candidate))
            architecture-query-supported? (and architecture-evidence?
                                               (or (<= 2 (count matched-tokens))
                                                   (seq matched-token-pairs)
                                                   (seq matched-compound-token-pairs)))
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
                 :definition-kind (if architecture-evidence?
                                    (or (:architectureKind candidate)
                                        target-kind)
                                    target-kind)
                 :matched-tokens matched-tokens
                 :matched-token-pairs matched-token-pairs
                 :matched-compound-token-pairs matched-compound-token-pairs
                 :matched-identity-compound-token-pairs matched-identity-compound-token-pairs
                 :query-matched-path-self-identity?
                 (boolean (query-matched-path-self-identity?
                           query-tokens
                           path))
                 :matched-path-query-token-count
                 (query-matched-path-token-count query-tokens path)
                 :file-identity-support-label-count file-identity-support-label-count
                 :query-matched-file-stem-part-count
                 (count query-file-stem-part-matches)
                 :candidate-support-label-signature exported-support-label-signature
                 :exported-support-label-count exported-support-label-count
                 :query-matched-exported-support-label-count
                 query-matched-exported-support-label-count
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

          architecture-evidence?
          (assoc :architecture-evidence? true
                 :architecture-support-score source-graph-score)

          architecture-query-supported?
          (assoc :query-supported-architecture-evidence? true)

          (pos? lexical-score)
          (assoc :candidate-lexical-score lexical-score)

          (pos? grep-score)
          (assoc :candidate-grep-score grep-score)

          (= "file" target-kind)
          (assoc :direct-file-candidate? true)

          support-owner-evidence?
          (assoc :support-owner-evidence? true
                 :support-owner-primary-label-query-token-count
                 support-owner-primary-label-query-token-count
                 :support-owner-primary-label-specific-token-count
                 support-owner-primary-label-specific-token-count)

          (and (pos? graph-score)
               graph-score-supported?)
          (assoc :graph-neighbor-score graph-score))))))

(defn- candidate-file-sibling-prediction
  [roots query-tokens retrieved-identities idx candidate sibling-path]
  (let [path (:path candidate)
        source-repo-id (:repo candidate)
        repo-id (prediction-repo-id roots source-repo-id)
        support-labels (vec (remove benchmark-util/blankish?
                                    (concat [(:label candidate)]
                                            (:supportLabels candidate))))
        target-kind (field-name (or (:targetKind candidate)
                                    (:target-kind candidate)
                                    (:resultKind candidate)
                                    (:result-kind candidate)))
        evidence-text (str/join "\n"
                                (remove benchmark-util/blankish?
                                        (concat [sibling-path
                                                 path
                                                 (:label candidate)]
                                                (:supportLabels candidate))))
        {:keys [matched-tokens matched-token-pairs]}
        (token-and-pair-matches query-tokens evidence-text)
        matched-compound-token-pairs (compact-compound-token-pair-matches
                                      query-tokens
                                      evidence-text)
        matched-identity-compound-token-pairs (apply
                                               identity-compound-token-pair-matches
                                               query-tokens
                                               sibling-path
                                               (:label candidate)
                                               support-labels)
        file-identity-support-label-count (file-identity-support-label-count
                                           sibling-path
                                           support-labels)
        exported-support-label-count (exported-support-label-count
                                      support-labels)
        query-matched-exported-support-label-count
        (query-matched-exported-support-label-count query-tokens
                                                    support-labels)
        exported-support-label-signature (exported-support-label-signature
                                          support-labels)
        retrieved-support-label-count (retrieved-support-label-count
                                       sibling-path
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
        grep-score (double (or (parse-double-safe (:grep score-components))
                               0.0))
        source-graph-score (double (or (parse-double-safe (:sourceGraph
                                                           score-components))
                                       0.0))
        graph-score-supported? (or (>= (count matched-tokens) 2)
                                   (seq matched-token-pairs)
                                   (and (>= graph-score
                                            rank-score-graph-support-min)
                                        (>= lexical-score
                                            rank-score-graph-lexical-support-min)))]
    (cond-> {:path sibling-path
             :source-rank (+ 540 (inc idx))
             :confidence (bounded-confidence (:score candidate))
             :evidence-score (* 0.6 (double (or (parse-double-safe
                                                 (:score candidate))
                                                0.0)))
             :evidence-kind :candidate-file
             :retrieved-source? false
             :exact-path-source? false
             :definition-kind (or target-kind "candidate-sibling")
             :matched-tokens matched-tokens
             :matched-token-pairs matched-token-pairs
             :matched-compound-token-pairs matched-compound-token-pairs
             :matched-identity-compound-token-pairs matched-identity-compound-token-pairs
             :query-matched-path-self-identity?
             (boolean (query-matched-path-self-identity?
                       query-tokens
                       sibling-path))
             :matched-path-query-token-count
             (query-matched-path-token-count query-tokens sibling-path)
             :file-identity-support-label-count file-identity-support-label-count
             :candidate-support-label-signature exported-support-label-signature
             :exported-support-label-count exported-support-label-count
             :query-matched-exported-support-label-count
             query-matched-exported-support-label-count
             :retrieved-support-label-count retrieved-support-label-count
             :candidate-support-label-count (count support-labels)
             :matched-identity-compound-token-span-length
             (apply identity-compound-token-span-length
                    query-tokens
                    sibling-path
                    (:label candidate)
                    support-labels)
             :evidence [(candidate-file-sibling-evidence candidate
                                                         score-components
                                                         path
                                                         sibling-path)]
             :reason (str "Yggdrasil derived existing sibling file "
                          sibling-path
                          " from candidate "
                          path
                          " with parser label "
                          (pr-str (:label candidate))
                          ".")}
      repo-id
      (assoc :repo-id repo-id)

      (pos? source-graph-score)
      (assoc :candidate-source-rank candidate-rank)

      (pos? lexical-score)
      (assoc :candidate-lexical-score lexical-score)

      (pos? grep-score)
      (assoc :candidate-grep-score grep-score)

      (and (pos? graph-score)
           graph-score-supported?)
      (assoc :graph-neighbor-score graph-score))))

(defn- candidate-file-predictions
  [root roots query-tokens retrieved-identities idx candidate]
  (let [path (:path candidate)
        source-repo-id (:repo candidate)
        file-root (row-root root roots {:repo-id source-repo-id :path path})]
    (when-let [row (candidate-file-prediction root
                                              roots
                                              query-tokens
                                              retrieved-identities
                                              idx
                                              candidate)]
      (into [row]
            (map #(candidate-file-sibling-prediction roots
                                                     query-tokens
                                                     retrieved-identities
                                                     idx
                                                     candidate
                                                     %))
            (candidate-file-sibling-paths file-root candidate)))))
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
                                                             (:summary candidate))
                                {:keys [matched-tokens matched-token-pairs]}
                                (token-and-pair-matches query-tokens evidence-text)]
                            (cond-> {:path path
                                     :source-rank (+ 450 (* idx 100) path-idx)
                                     :confidence 0.75
                                     :evidence-score 0.0
                                     :evidence-kind :decision-candidate
                                     :retrieved-source? false
                                     :exact-path-source? false
                                     :definition-kind "decision-candidate"
                                     :matched-tokens matched-tokens
                                     :matched-token-pairs matched-token-pairs
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
                                     :matched-path-query-token-count
                                     (query-matched-path-token-count
                                      query-tokens
                                      path)
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
                     (:package row)
                     (:import row)
                     (:importName row)
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
       (when-let [package (not-empty (str (:package row)))]
         (str " package=" (pr-str package)))
       (when-let [import-name (not-empty (str (or (:importName row)
                                                  (:import row))))]
         (str " import=" (pr-str import-name)))
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

(defn- dependency-package-import-row?
  [section row]
  (and (= "dependencyEvidence" section)
       (= "package-import" (field-name (:kind row)))))

(defn- dependency-package-identity-token-count
  [query-tokens row]
  (count (token-matches query-tokens
                        (identity-text (:label row)
                                       (:package row)
                                       (:import row)
                                       (:importName row)
                                       (:normalizedValue row)))))

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

(defn- architecture-row-features
  [query-tokens section row evidence-text]
  (let [{:keys [matched-tokens matched-token-pairs]}
        (token-and-pair-matches query-tokens evidence-text)
        matched-compound-token-pairs (compact-compound-token-pair-matches query-tokens
                                                                          evidence-text)
        package-import? (dependency-package-import-row? section row)
        package-identity-token-count (if package-import?
                                       (dependency-package-identity-token-count
                                        query-tokens
                                        row)
                                       0)
        query-supported? (if package-import?
                           (<= dependency-package-identity-query-token-min
                               package-identity-token-count)
                           (or (<= 2 (count matched-tokens))
                               (seq matched-token-pairs)
                               (seq matched-compound-token-pairs)))
        raw-score (double (or (parse-double-safe (:score row)) 0.0))
        weight (architecture-score-weight section row)
        cap (architecture-score-cap section row)
        package-identity-boost (if (<= dependency-package-identity-query-token-min
                                       package-identity-token-count)
                                 0.35
                                 0.0)
        evidence-score (min cap (+ (* weight raw-score)
                                   package-identity-boost))
        support-score (if (and (= "runtimeEvidence" section)
                               (= "env-var" (field-name (:kind row)))
                               query-supported?)
                        (min (double (get architecture-evidence-score-caps
                                          section
                                          1.6))
                             (* (double (get architecture-evidence-score-weights
                                             section
                                             0.7))
                                raw-score))
                        evidence-score)]
    {:matched-tokens matched-tokens
     :matched-token-pairs matched-token-pairs
     :matched-compound-token-pairs matched-compound-token-pairs
     :query-supported? query-supported?
     :evidence-score evidence-score
     :support-score support-score}))

(defn- architecture-file-prediction
  [root roots query-tokens idx section row]
  (let [path (:path row)
        source-repo-id (:repo row)
        repo-id (prediction-repo-id roots source-repo-id)
        file-root (row-root root roots {:repo-id source-repo-id :path path})]
    (when (existing-file-path? file-root path)
      (let [evidence-text (architecture-evidence-text row)
            {:keys [matched-tokens
                    matched-token-pairs
                    matched-compound-token-pairs
                    query-supported?
                    evidence-score
                    support-score]} (architecture-row-features query-tokens
                                                               section
                                                               row
                                                               evidence-text)]
        (cond-> {:path path
                 :source-rank (+ 700 (inc idx))
                 :confidence (bounded-confidence (:score row))
                 :evidence-score evidence-score
                 :architecture-support-score support-score
                 :query-supported-architecture-evidence? query-supported?
                 :evidence-kind :candidate-file
                 :architecture-evidence? true
                 :retrieved-source? false
                 :exact-path-source? false
                 :definition-kind (some-> (:kind row) str)
                 :matched-tokens matched-tokens
                 :matched-token-pairs matched-token-pairs
                 :matched-compound-token-pairs matched-compound-token-pairs
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
                 :matched-path-query-token-count
                 (query-matched-path-token-count query-tokens path)
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
(defn- matched-token-path-counts
  [grouped-rows-by-file]
  (->> grouped-rows-by-file
       vals
       (mapcat (fn [grouped-rows]
                 (->> grouped-rows
                      (mapcat :matched-tokens)
                      distinct)))
       frequencies))
(defn- directory-evidence-stats
  [rows]
  (reduce (fn [stats row]
            (if (pos? (long (or (get-in row [:metrics :docCount]) 0)))
              (let [dir (candidate-path-directory (:path row))]
                (-> stats
                    (update-in [dir :doc-count] (fnil inc 0))
                    (update-in [dir :rank-score-sum]
                               (fnil + 0.0)
                               (double (or (:rank-score row) 0.0)))))
              stats))
          {}
          rows))

(defn- multi-repo-evidence?
  [rows]
  (->> rows
       (keep #(repo-key-name (or (:repo-id %) (:repo %))))
       distinct
       (take 2)
       count
       (<= 2)))

(defn- directory-evidence-boost
  [stats multi-repo-evidence? row]
  (let [metrics (:metrics row)
        doc-count (long (or (:docCount metrics) 0))
        candidate-file-count (long (or (:candidateFileCount metrics) 0))
        direct-file-candidate-count (long (or (:directFileCandidateCount metrics) 0))
        candidate-source-rank (long (or (:candidateSourceRank metrics)
                                        Long/MAX_VALUE))
        matched-token-count (long (or (:matchedTokenCount metrics) 0))
        matched-path-token-count (long (or (:matchedPathQueryTokenCount metrics)
                                           0))
        source-graph-score (double (or (:sourceGraphCandidateEvidenceScore metrics)
                                       0.0))
        query-matched-path-self-identity?
        (true? (:queryMatchedPathSelfIdentity metrics))
        directory-stats (get stats (candidate-path-directory (:path row)))
        directory-doc-count (long (or (:doc-count directory-stats) 0))
        directory-rank-score-sum (double (or (:rank-score-sum directory-stats)
                                             0.0))
        direct-file-directory-evidence?
        (and (pos? direct-file-candidate-count)
             (<= rank-score-directory-evidence-doc-min directory-doc-count))
        directory-root-candidate-evidence?
        (and (pos? candidate-file-count)
             (path-self-identity-token (:path row))
             query-matched-path-self-identity?
             (<= rank-score-directory-root-evidence-doc-min directory-doc-count))
        doc-directory-cluster-evidence?
        (and multi-repo-evidence?
             (pos? doc-count)
             (pos? candidate-file-count)
             (<= rank-score-doc-directory-cluster-doc-min directory-doc-count)
             (<= rank-score-doc-directory-cluster-token-min matched-path-token-count)
             (<= rank-score-doc-directory-cluster-source-score-min
                 source-graph-score)
             (<= candidate-source-rank
                 rank-score-doc-directory-cluster-candidate-rank-window))
        boost-base (if directory-root-candidate-evidence?
                     rank-score-directory-root-evidence-base
                     rank-score-directory-evidence-base)]
    (cond
      (and (zero? doc-count)
           (or direct-file-directory-evidence?
               directory-root-candidate-evidence?)
           (<= candidate-source-rank
               rank-score-directory-evidence-candidate-source-rank-window)
           (<= rank-score-directory-evidence-token-min matched-token-count))
      (min rank-score-directory-evidence-cap
           (+ boost-base
              (* rank-score-directory-evidence-doc-weight
                 (dec directory-doc-count))
              (* rank-score-directory-evidence-score-weight
                 (min 40.0 directory-rank-score-sum))))

      doc-directory-cluster-evidence?
      (min rank-score-doc-directory-cluster-cap
           (+ rank-score-doc-directory-cluster-base
              (* rank-score-doc-directory-cluster-doc-weight
                 (dec directory-doc-count))
              (* rank-score-doc-directory-cluster-score-weight
                 (min 40.0 directory-rank-score-sum))))

      :else
      0.0)))

(defn- apply-directory-evidence-boost
  [rows]
  (let [stats (directory-evidence-stats rows)
        multi-repo? (multi-repo-evidence? rows)]
    (mapv (fn [row]
            (let [boost (directory-evidence-boost stats multi-repo? row)]
              (if (pos? boost)
                (-> row
                    (update :rank-score + boost)
                    (assoc-in [:metrics :directoryEvidenceBoost] boost)
                    (update-in [:metrics :rankScore] + boost))
                row)))
          rows)))

(defn- ranked-file-predictions
  [rows]
  (let [grouped-rows-by-file (group-by file-row-key rows)
        token-path-counts (matched-token-path-counts grouped-rows-by-file)
        combine-rows (fn [path grouped-rows]
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
                             matched-path-query-token-count
                             (apply max
                                    0
                                    (keep :matched-path-query-token-count
                                          ordered))
                             definition-kinds (->> ordered
                                                   (keep :definition-kind)
                                                   distinct
                                                   vec)
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
                             query-matched-file-stem-part-count
                             (apply max
                                    0
                                    (keep :query-matched-file-stem-part-count
                                          ordered))
                             exported-support-label-count
                             (apply max
                                    0
                                    (keep :exported-support-label-count
                                          ordered))
                             query-matched-exported-support-label-count
                             (apply max
                                    0
                                    (keep :query-matched-exported-support-label-count
                                          ordered))
                             retrieved-support-label-count
                             (apply max
                                    0
                                    (keep :retrieved-support-label-count
                                          ordered))
                             decision-candidate-count (count (filter #(= :decision-candidate
                                                                         (:evidence-kind %))
                                                                     ordered))
                             support-owner-evidence-count (count (filter :support-owner-evidence?
                                                                         ordered))
                             support-owner-evidence-score
                             (apply max
                                    0.0
                                    (keep #(when (:support-owner-evidence? %)
                                             (:evidence-score %))
                                          ordered))
                             support-owner-primary-label-query-token-count
                             (apply max
                                    0
                                    (keep :support-owner-primary-label-query-token-count
                                          ordered))
                             support-owner-primary-label-specific-token-count
                             (apply max
                                    0
                                    (keep :support-owner-primary-label-specific-token-count
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
                             candidate-grep-score (apply max
                                                         0.0
                                                         (keep :candidate-grep-score
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
                             candidate-support-label-signatures (->> ordered
                                                                     (keep :candidate-support-label-signature)
                                                                     (remove empty?)
                                                                     distinct
                                                                     vec)
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
                             candidate-only-head-graph-score
                             (max graph-neighbor-score
                                  source-graph-candidate-evidence-score)
                             candidate-only-source-graph-head-boost
                             (candidate-only-source-graph-head-boost
                              candidate-source-rank
                              doc-count
                              candidate-only-head-graph-score
                              (count matched-tokens))
                             candidate-only-exported-support-boost
                             (candidate-only-exported-support-boost
                              doc-count
                              candidate-count
                              candidate-source-rank
                              source-graph-candidate-evidence-score
                              file-identity-support-label-count
                              query-matched-exported-support-label-count
                              (count matched-tokens))
                             doc-supported-exported-support-boost
                             (doc-supported-exported-support-boost
                              doc-count
                              candidate-count
                              source-graph-candidate-evidence-score
                              query-matched-exported-support-label-count
                              (count matched-tokens)
                              matched-path-query-token-count)
                             doc-supported-source-graph-head-boost
                             (doc-supported-source-graph-head-boost
                              doc-count
                              candidate-count
                              candidate-source-rank
                              (count matched-identity-compound-token-pairs)
                              retrieved-support-label-count)
                             doc-supported-direct-file-compound-boost
                             (doc-supported-direct-file-compound-boost
                              doc-count
                              direct-file-candidate-count
                              (:source-rank best-row)
                              (count matched-compound-token-pairs)
                              (count matched-tokens))
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
                             repeated-retrieved-source-boost
                             (repeated-retrieved-source-boost
                              doc-count
                              retrieved-source-count
                              (count matched-tokens)
                              definition-kinds)
                             query-matched-path-self-identity?
                             (boolean (some :query-matched-path-self-identity?
                                            ordered))
                             query-matched-file-identity-count
                             (apply max
                                    0
                                    (keep :query-matched-file-identity-count
                                          ordered))
                             query-matched-file-identity-max-length
                             (apply max
                                    0
                                    (keep :query-matched-file-identity-max-length
                                          ordered))
                             retrieved-path-self-identity-boost
                             (retrieved-path-self-identity-boost
                              doc-count
                              retrieved-source-count
                              (count matched-tokens)
                              query-matched-path-self-identity?)
                             retrieved-query-file-identity-boost
                             (retrieved-query-file-identity-boost
                              doc-count
                              retrieved-source-count
                              query-matched-file-identity-count
                              query-matched-file-identity-max-length)
                             retrieved-path-query-token-boost
                             (retrieved-path-query-token-boost
                              doc-count
                              retrieved-source-count
                              matched-path-query-token-count)
                             retrieved-path-grep-evidence-boost
                             (retrieved-path-grep-evidence-boost
                              doc-count
                              retrieved-source-count
                              candidate-count
                              matched-path-query-token-count
                              candidate-grep-score
                              source-graph-candidate-evidence-score)
                             candidate-path-self-identity-boost
                             (candidate-path-self-identity-boost
                              doc-count
                              candidate-count
                              candidate-source-rank
                              (count matched-tokens)
                              query-matched-path-self-identity?)
                             graph-neighbor-boost (graph-neighbor-boost
                                                   doc-count
                                                   graph-neighbor-score
                                                   max-evidence-score)
                             doc-supported-candidate-evidence-boost
                             (doc-supported-candidate-evidence-boost
                              doc-count
                              source-graph-candidate-evidence-score)
                             source-graph-query-evidence-boost
                             (source-graph-query-evidence-boost
                              candidate-count
                              candidate-source-rank
                              source-graph-candidate-evidence-score
                              file-identity-support-label-count
                              query-matched-file-stem-part-count
                              (count matched-tokens)
                              (count matched-token-pairs)
                              matched-path-query-token-count)
                             doc-supported-source-graph-query-boost
                             (doc-supported-source-graph-query-boost
                              doc-count
                              source-graph-query-evidence-boost)
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
                             doc-supported-architecture-token-boost
                             (doc-supported-architecture-token-boost
                              doc-count
                              direct-file-candidate-count
                              architecture-evidence-count
                              (count matched-tokens))
                             direct-file-architecture-graph-boost
                             (direct-file-architecture-graph-boost
                              doc-count
                              direct-file-candidate-count
                              architecture-evidence-count
                              query-supported-architecture-evidence-count
                              graph-neighbor-score
                              (count matched-tokens)
                              (count matched-token-pairs))
                             architecture-rank-boost (+ architecture-support-boost
                                                        query-supported-architecture-only-boost
                                                        candidate-only-architecture-support-boost
                                                        doc-supported-architecture-token-boost
                                                        direct-file-architecture-graph-boost)
                             candidate-lexical-component-boost
                             (candidate-lexical-component-boost
                              candidate-lexical-score)
                             candidate-grep-component-boost
                             (candidate-grep-component-boost
                              candidate-grep-score
                              (count matched-tokens))
                             direct-file-identity-support-boost
                             (direct-file-identity-support-boost
                              doc-count
                              direct-file-candidate-count
                              file-identity-support-label-count
                              graph-neighbor-score
                              candidate-source-rank)
                             candidate-file-identity-support-boost
                             (candidate-file-identity-support-boost
                              doc-count
                              candidate-count
                              direct-file-candidate-count
                              file-identity-support-label-count
                              candidate-source-rank
                              (count matched-tokens))
                             retrieved-support-label-boost
                             (retrieved-support-label-boost
                              doc-count
                              retrieved-support-label-count
                              candidate-source-rank)
                             rare-query-token-score
                             (rare-query-token-score doc-count
                                                     graph-neighbor-score
                                                     token-path-counts
                                                     matched-tokens)
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
                                           repeated-retrieved-source-boost
                                           retrieved-path-self-identity-boost
                                           retrieved-query-file-identity-boost
                                           retrieved-path-query-token-boost
                                           retrieved-path-grep-evidence-boost
                                           candidate-path-self-identity-boost
                                           candidate-support-label-score
                                           (* 0.08 (min rank-score-support-count-cap
                                                        support-count))
                                           (* rank-score-retrieved-source-count-weight
                                              (min rank-score-retrieved-source-count-cap
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
                                           doc-supported-direct-file-compound-boost
                                           graph-neighbor-boost
                                           doc-supported-candidate-evidence-boost
                                           source-graph-query-evidence-boost
                                           doc-supported-source-graph-query-boost
                                           architecture-rank-boost
                                           candidate-lexical-component-boost
                                           candidate-grep-component-boost
                                           rare-query-token-score
                                           direct-file-identity-support-boost
                                           candidate-file-identity-support-boost
                                           retrieved-support-label-boost
                                           candidate-only-source-graph-head-boost
                                           candidate-only-exported-support-boost
                                           doc-supported-exported-support-boost)
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
                                              :definitionKinds definition-kinds}
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
                                       (pos? repeated-retrieved-source-boost)
                                       (assoc :repeatedRetrievedSourceBoost
                                              repeated-retrieved-source-boost)
                                       (pos? retrieved-path-self-identity-boost)
                                       (assoc :retrievedPathSelfIdentityBoost
                                              retrieved-path-self-identity-boost)
                                       (pos? query-matched-file-identity-count)
                                       (assoc :queryMatchedFileIdentityCount
                                              query-matched-file-identity-count)
                                       (pos? query-matched-file-identity-max-length)
                                       (assoc :queryMatchedFileIdentityMaxLength
                                              query-matched-file-identity-max-length)
                                       (pos? query-matched-file-stem-part-count)
                                       (assoc :queryMatchedFileStemPartCount
                                              query-matched-file-stem-part-count)
                                       (pos? retrieved-query-file-identity-boost)
                                       (assoc :retrievedQueryFileIdentityBoost
                                              retrieved-query-file-identity-boost)
                                       query-matched-path-self-identity?
                                       (assoc :queryMatchedPathSelfIdentity true)
                                       (pos? matched-path-query-token-count)
                                       (assoc :matchedPathQueryTokenCount
                                              matched-path-query-token-count)
                                       (pos? retrieved-path-query-token-boost)
                                       (assoc :retrievedPathQueryTokenBoost
                                              retrieved-path-query-token-boost)
                                       (pos? retrieved-path-grep-evidence-boost)
                                       (assoc :retrievedPathGrepEvidenceBoost
                                              retrieved-path-grep-evidence-boost)
                                       (pos? candidate-path-self-identity-boost)
                                       (assoc :candidatePathSelfIdentityBoost
                                              candidate-path-self-identity-boost)
                                       (pos? candidate-support-label-count)
                                       (assoc :candidateSupportLabelCount
                                              candidate-support-label-count)
                                       (pos? candidate-support-label-score)
                                       (assoc :candidateSupportLabelScore
                                              candidate-support-label-score)
                                       (seq candidate-support-label-signatures)
                                       (assoc :candidateSupportLabelSignatures
                                              candidate-support-label-signatures)
                                       (pos? decision-candidate-count)
                                       (assoc :decisionCandidateCount
                                              decision-candidate-count)
                                       (pos? support-owner-evidence-count)
                                       (assoc :supportOwnerEvidenceCount
                                              support-owner-evidence-count)
                                       (pos? support-owner-evidence-score)
                                       (assoc :supportOwnerEvidenceScore
                                              support-owner-evidence-score)
                                       (pos? support-owner-primary-label-query-token-count)
                                       (assoc :supportOwnerPrimaryLabelMatchedTokenCount
                                              support-owner-primary-label-query-token-count)
                                       (pos? support-owner-primary-label-specific-token-count)
                                       (assoc :supportOwnerPrimaryLabelSpecificTokenCount
                                              support-owner-primary-label-specific-token-count)
                                       (pos? source-rank-score)
                                       (assoc :sourceRankScore source-rank-score)
                                       (pos? (long (or candidate-source-rank 0)))
                                       (assoc :candidateSourceRank candidate-source-rank)
                                       (pos? candidate-source-rank-score)
                                       (assoc :candidateSourceRankScore candidate-source-rank-score)
                                       (pos? doc-supported-source-graph-head-boost)
                                       (assoc :docSupportedSourceGraphHeadBoost
                                              doc-supported-source-graph-head-boost)
                                       (pos? doc-supported-direct-file-compound-boost)
                                       (assoc :docSupportedDirectFileCompoundBoost
                                              doc-supported-direct-file-compound-boost)
                                       (pos? direct-file-candidate-count)
                                       (assoc :directFileCandidateCount
                                              direct-file-candidate-count)
                                       (pos? file-identity-support-label-count)
                                       (assoc :fileIdentitySupportLabelCount
                                              file-identity-support-label-count)
                                       (pos? exported-support-label-count)
                                       (assoc :candidateExportedSupportLabelCount
                                              exported-support-label-count)
                                       (pos? query-matched-exported-support-label-count)
                                       (assoc :queryMatchedExportedSupportLabelCount
                                              query-matched-exported-support-label-count)
                                       (pos? direct-file-identity-support-boost)
                                       (assoc :directFileIdentitySupportBoost
                                              direct-file-identity-support-boost)
                                       (pos? candidate-file-identity-support-boost)
                                       (assoc :candidateFileIdentitySupportBoost
                                              candidate-file-identity-support-boost)
                                       (pos? retrieved-support-label-count)
                                       (assoc :retrievedSupportLabelCount
                                              retrieved-support-label-count)
                                       (pos? retrieved-support-label-boost)
                                       (assoc :retrievedSupportLabelBoost
                                              retrieved-support-label-boost)
                                       (pos? candidate-only-robust-boost)
                                       (assoc :robustCandidateOnlyBoost candidate-only-robust-boost)
                                       (pos? candidate-only-source-graph-head-boost)
                                       (assoc :candidateOnlySourceGraphHeadBoost
                                              candidate-only-source-graph-head-boost)
                                       (pos? candidate-only-exported-support-boost)
                                       (assoc :candidateOnlyExportedSupportBoost
                                              candidate-only-exported-support-boost)
                                       (pos? doc-supported-exported-support-boost)
                                       (assoc :docSupportedExportedSupportBoost
                                              doc-supported-exported-support-boost)
                                       (pos? graph-neighbor-score)
                                       (assoc :graphNeighborScore graph-neighbor-score)
                                       (pos? graph-neighbor-boost)
                                       (assoc :graphNeighborBoost graph-neighbor-boost)
                                       (pos? doc-supported-candidate-evidence-boost)
                                       (assoc :docSupportedCandidateEvidenceBoost
                                              doc-supported-candidate-evidence-boost)
                                       (pos? source-graph-query-evidence-boost)
                                       (assoc :sourceGraphQueryEvidenceBoost
                                              source-graph-query-evidence-boost)
                                       (pos? doc-supported-source-graph-query-boost)
                                       (assoc :docSupportedSourceGraphQueryBoost
                                              doc-supported-source-graph-query-boost)
                                       (pos? source-graph-candidate-evidence-score)
                                       (assoc :sourceGraphCandidateEvidenceScore
                                              source-graph-candidate-evidence-score)
                                       (pos? architecture-rank-boost)
                                       (assoc :architectureSupportBoost
                                              architecture-rank-boost)
                                       (pos? doc-supported-architecture-token-boost)
                                       (assoc :docSupportedArchitectureTokenBoost
                                              doc-supported-architecture-token-boost)
                                       (pos? direct-file-architecture-graph-boost)
                                       (assoc :directFileArchitectureGraphBoost
                                              direct-file-architecture-graph-boost)
                                       (pos? candidate-lexical-component-boost)
                                       (assoc :candidateLexicalComponentBoost
                                              candidate-lexical-component-boost)
                                       (pos? candidate-grep-score)
                                       (assoc :candidateGrepScore
                                              candidate-grep-score)
                                       (pos? candidate-grep-component-boost)
                                       (assoc :candidateGrepComponentBoost
                                              candidate-grep-component-boost)
                                       (pos? rare-query-token-score)
                                       (assoc :rareQueryTokenScore
                                              rare-query-token-score))]
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
    (->> grouped-rows-by-file
         (map (fn [[[_repo-id path] grouped-rows]]
                (combine-rows path grouped-rows)))
         apply-directory-evidence-boost
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
                                    :query-matched-file-identity-count
                                    :query-matched-file-identity-max-length
                                    :matched-path-query-token-count
                                    :file-identity-support-label-count
                                    :retrieved-support-label-count
                                    :matched-identity-compound-token-span-length
                                    :definition-kind
                                    :query-matched-path-self-identity?
                                    :architecture-evidence?
                                    :query-supported-architecture-evidence?
                                    :direct-file-candidate?
                                    :candidate-source-rank
                                    :candidate-support-label-signature
                                    :candidate-lexical-score
                                    :candidate-grep-score
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
(defn- prediction-candidate-support-signature
  [row]
  (some-> (get-in row [:metrics :candidateSupportLabelSignatures])
          seq
          first
          seq
          vec))

(defn- prediction-candidate-support-diversity-key
  [row]
  (let [entity-count (long (or (get-in row [:metrics :entityCount]) 0))
        candidate-file-count (long (or (get-in row [:metrics :candidateFileCount])
                                       0))
        decision-candidate-count (long (or (get-in row
                                                   [:metrics
                                                    :decisionCandidateCount])
                                           0))]
    (when-let [signature (and (zero? entity-count)
                              (zero? decision-candidate-count)
                              (pos? candidate-file-count)
                              (prediction-candidate-support-signature row))]
      [(or (:repo-id row) :unknown-repo)
       :candidate-support
       signature])))

(defn- prediction-diversity-key
  [row]
  (let [definition-kind (prediction-primary-definition-kind row)
        doc-count (long (or (get-in row [:metrics :docCount]) 0))
        decision-candidate-count (long (or (get-in row [:metrics :decisionCandidateCount]) 0))
        architecture-support-boost (double (or (get-in row [:metrics :architectureSupportBoost])
                                               0.0))]
    (or (prediction-candidate-support-diversity-key row)
        (when (and (or (pos? doc-count)
                       (pos? decision-candidate-count)
                       (pos? architecture-support-boost))
                   (not= :unknown definition-kind))
          [(or (:repo-id row) :unknown-repo)
           (prediction-path-root row)
           definition-kind]))))

(defn- retrieved-query-file-identity-rank-protected?
  [row]
  (and (pos? (double (or (get-in row
                                 [:metrics :retrievedQueryFileIdentityBoost])
                         0.0)))
       (or (<= rank-score-retrieved-query-file-identity-protected-min-length
               (long (or (get-in row
                                 [:metrics :queryMatchedFileIdentityMaxLength])
                         0)))
           (<= rank-score-retrieved-path-query-token-min
               (long (or (get-in row [:metrics :matchedPathQueryTokenCount])
                         0)))
           (pos? (long (or (get-in row
                                   [:metrics :matchedIdentityCompoundTokenPairCount])
                           0)))
           (pos? (double (or (get-in row [:metrics :directoryEvidenceBoost])
                             0.0))))))

(defn- prediction-rank-protected?
  [row]
  (or (pos? (long (or (get-in row [:metrics :decisionCandidateCount]) 0)))
      (retrieved-query-file-identity-rank-protected? row)))

(defn- retrieved-path-evidence-row?
  [row]
  (and (pos? (long (or (get-in row [:metrics :docCount]) 0)))
       (pos? (long (or (get-in row [:metrics :retrievedSourceCount]) 0)))
       (or (true? (get-in row [:metrics :queryMatchedPathSelfIdentity]))
           (<= rank-score-retrieved-path-query-token-min
               (long (or (get-in row [:metrics :matchedPathQueryTokenCount])
                         0)))
           (pos? (long (or (get-in row
                                   [:metrics :matchedIdentityCompoundTokenPairCount])
                           0))))))

(defn- candidate-retrieved-support-identity-row?
  [row]
  (and (zero? (long (or (get-in row [:metrics :docCount]) 0)))
       (pos? (long (or (get-in row [:metrics :candidateFileCount]) 0)))
       (pos? (long (or (get-in row [:metrics :retrievedSupportLabelCount])
                       0)))
       (<= diversity-bypass-candidate-identity-support-min
           (long (or (get-in row [:metrics :fileIdentitySupportLabelCount])
                     0)))))

(defn- prediction-diversity-bypass?
  [row]
  (let [candidate-source-rank (long (or (get-in row [:metrics :candidateSourceRank])
                                        Long/MAX_VALUE))
        candidate-file-count (long (or (get-in row [:metrics :candidateFileCount])
                                       0))
        support-count (long (or (get-in row [:metrics :supportCount]) 0))
        doc-count (long (or (get-in row [:metrics :docCount]) 0))
        identity-support-count (long (or (get-in row
                                                 [:metrics :fileIdentitySupportLabelCount])
                                         0))
        identity-support-boost (double (or (get-in row
                                                   [:metrics
                                                    :candidateFileIdentitySupportBoost])
                                           0.0))]
    (and (pos? candidate-file-count)
         (or (and (<= candidate-source-rank
                      diversity-bypass-candidate-source-rank-window)
                  (<= diversity-bypass-support-count-min support-count))
             (and (zero? doc-count)
                  (zero? (long (or (get-in row
                                           [:metrics :retrievedSupportLabelCount])
                                   0)))
                  (or (pos? identity-support-boost)
                      (<= candidate-source-rank
                          diversity-bypass-candidate-identity-source-rank-window))
                  (<= diversity-bypass-candidate-identity-support-min
                      identity-support-count))))))

(defn- row-rank-score
  [row]
  (double (or (get-in row [:metrics :rankScore]) 0.0)))

(defn- prediction-diversity-preserved-head-row?
  [head-score row]
  (let [doc-count (long (or (get-in row [:metrics :docCount]) 0))
        candidate-file-count (long (or (get-in row [:metrics :candidateFileCount])
                                       0))
        matched-token-count (long (or (get-in row [:metrics :matchedTokenCount])
                                      0))
        source-score (double (or (get-in row
                                         [:metrics
                                          :sourceGraphCandidateEvidenceScore])
                                 0.0))
        rank-score (row-rank-score row)]
    (and (pos? doc-count)
         (pos? candidate-file-count)
         (<= diversity-preserved-head-token-min matched-token-count)
         (<= diversity-preserved-head-source-score-min source-score)
         (<= (* diversity-preserved-head-score-ratio head-score)
             rank-score))))

(defn- prediction-diversity-preserved-head
  [rows]
  (let [head-score (row-rank-score (first rows))]
    (->> rows
         (take-while #(prediction-diversity-preserved-head-row? head-score %))
         (take diversity-preserved-head-max-files)
         vec)))

(defn- first-rank-protected-index
  [rows]
  (->> rows
       (map-indexed vector)
       (some (fn [[idx row]]
               (when (prediction-rank-protected? row)
                 idx)))))

(defn- first-retrieved-path-evidence-index
  [rows]
  (->> rows
       (map-indexed vector)
       (some (fn [[idx row]]
               (when (retrieved-path-evidence-row? row)
                 idx)))))

(defn- diversify-ranked-file-predictions
  [rows]
  (if (seq rows)
    (let [rows (vec rows)
          head-idx (or (first-rank-protected-index rows)
                       (when (candidate-retrieved-support-identity-row?
                              (first rows))
                         (first-retrieved-path-evidence-index rows))
                       0)
          selected-head (if (zero? head-idx)
                          (let [preserved-head
                                (prediction-diversity-preserved-head rows)]
                            (if (seq preserved-head)
                              preserved-head
                              [(first rows)]))
                          [(nth rows head-idx)])
          selected-head-count (if (zero? head-idx)
                                (count selected-head)
                                1)
          remaining (if (zero? head-idx)
                      (subvec rows selected-head-count)
                      (vec (concat (subvec rows 0 head-idx)
                                   (subvec rows (inc head-idx)))))]
      (loop [remaining remaining
             seen (->> selected-head
                       (keep prediction-diversity-key)
                       set)
             out selected-head]
        (if (empty? remaining)
          (renumber-file-ranks out)
          (let [[idx row] (or (->> remaining
                                   (map-indexed vector)
                                   (some (fn [[idx row]]
                                           (when (prediction-rank-protected? row)
                                             [idx row]))))
                              (->> remaining
                                   (map-indexed vector)
                                   (some (fn [[idx row]]
                                           (when (prediction-diversity-bypass? row)
                                             [idx row]))))
                              (->> remaining
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
    []))

(defn- row-source-kind
  [kind-by-path row]
  (row-path-kind kind-by-path row))

(defn- source-kind-lane-positive-metric
  [row k]
  (long (or (get-in row [:metrics k]) 0)))

(defn- source-kind-lane-metric-double
  [row k]
  (double (or (get-in row [:metrics k]) 0.0)))

(defn- source-kind-lane-candidate-source-rank
  [row]
  (long (or (get-in row [:metrics :candidateSourceRank])
            Long/MAX_VALUE)))

(defn- source-kind-lane-direct-identity-supported?
  [row]
  (and (pos? (source-kind-lane-positive-metric row :directFileCandidateCount))
       (<= compact-output-identity-support-min
           (source-kind-lane-positive-metric
            row
            :fileIdentitySupportLabelCount))))

(defn- source-kind-lane-row-key
  [score-floor row]
  (let [support-eligible? (or (<= score-floor (row-rank-score row))
                              (source-kind-lane-direct-identity-supported?
                               row))]
    [(- (if support-eligible?
          (source-kind-lane-metric-double row :architectureSupportBoost)
          0.0))
     (- (if support-eligible?
          (source-kind-lane-metric-double row :directFileIdentitySupportBoost)
          0.0))
     (- (if support-eligible?
          (source-kind-lane-metric-double row :candidateFileIdentitySupportBoost)
          0.0))
     (- (if support-eligible?
          (source-kind-lane-positive-metric row :retrievedSourceCount)
          0))
     (- (if support-eligible?
          (source-kind-lane-metric-double row :repeatedRetrievedSourceBoost)
          0.0))
     (- (if support-eligible?
          (source-kind-lane-positive-metric row :candidateFileCount)
          0))
     (- (if support-eligible?
          (source-kind-lane-positive-metric row :docCount)
          0))
     (- (source-kind-lane-positive-metric row :matchedTokenCount))
     (- (row-rank-score row))
     (source-kind-lane-candidate-source-rank row)
     (:rank row)
     (:repo-id row)
     (:path row)]))

(defn- best-row-by-source-kind
  [kind-by-path rows source-kind excluded-paths]
  (let [candidates (->> rows
                        (filter #(and (= source-kind
                                         (row-source-kind kind-by-path %))
                                      (not (contains? excluded-paths
                                                      (file-row-key %)))))
                        vec)
        score-floor (if (seq candidates)
                      (* source-kind-lane-support-score-ratio
                         (apply max (map row-rank-score candidates)))
                      0.0)]
    (->> candidates
         (sort-by #(source-kind-lane-row-key score-floor %))
         first)))

(defn- prioritize-coverage-source-lanes
  [source-kind-order kind-by-path candidate-files]
  (if (or (empty? source-kind-order)
          (empty? kind-by-path)
          (empty? candidate-files))
    candidate-files
    (let [prioritized (loop [source-kinds (seq source-kind-order)
                             selected []
                             selected-paths #{}]
                        (if-let [source-kind (first source-kinds)]
                          (if-let [row (best-row-by-source-kind
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
(defn- row-metric-double
  [row k]
  (double (or (get-in row [:metrics k]) 0.0)))

(defn- source-graph-candidate-row?
  [row]
  (pos? (positive-metric row :candidateFileCount)))
(defn- direct-file-candidate-row?
  [row]
  (pos? (positive-metric row :directFileCandidateCount)))
(defn- inspection-query-identity-row?
  [row]
  (and (source-graph-candidate-row? row)
       (pos? (row-metric-double row :retrievedPathSelfIdentityBoost))))
(defn- inspection-query-identity-key
  [row]
  [(positive-metric row :firstSourceRank)
   (:rank row)
   (:repo-id row)
   (:path row)])
(defn- inspection-query-identity-candidates
  [candidate-files selection-limit]
  (let [quota (min inspection-query-identity-quota selection-limit)]
    (->> candidate-files
         (filter inspection-query-identity-row?)
         (sort-by inspection-query-identity-key)
         (take quota)
         vec)))
(defn- row-candidate-source-rank
  [row]
  (long (or (get-in row [:metrics :candidateSourceRank])
            Long/MAX_VALUE)))
(defn- row-candidate-exported-support-label-count
  [row]
  (long (or (get-in row [:metrics :candidateExportedSupportLabelCount])
            0)))
(defn- row-source-order-key
  [row]
  [(row-candidate-source-rank row)
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- ordered-distinct-file-rows
  [rows]
  (->> rows
       (reduce (fn [selected row]
                 (if (contains? (:keys selected) (file-row-key row))
                   selected
                   (-> selected
                       (update :keys conj (file-row-key row))
                       (update :rows conj row))))
               {:keys #{}
                :rows []})
       :rows
       vec))

(defn- dense-file-identity-candidate-row?
  [row]
  (and (candidate-file-only-row? row)
       (pos? (positive-metric row :directFileCandidateCount))
       (pos? (row-metric-double row :graphNeighborScore))
       (<= compact-output-identity-support-min
           (positive-metric row :fileIdentitySupportLabelCount))
       (<= rank-score-candidate-file-identity-support-without-rank-token-min
           (positive-metric row :matchedTokenCount))))

(defn- dense-file-identity-candidate-key
  [row]
  [(- (positive-metric row :fileIdentitySupportLabelCount))
   (- (positive-metric row :matchedTokenCount))
   (- (row-metric-double row :candidateLexicalComponentBoost))
   (row-candidate-source-rank row)
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- dense-file-identity-candidate-rows
  [candidate-file-only-rows quota]
  (->> candidate-file-only-rows
       (filter dense-file-identity-candidate-row?)
       (sort-by dense-file-identity-candidate-key)
       (take (min candidate-file-only-identity-reserve-limit quota))
       vec))

(defn- query-evidence-source-candidate-row?
  [row]
  (and (candidate-file-only-row? row)
       (<= candidate-file-only-query-evidence-token-min
           (positive-metric row :matchedTokenCount))
       (<= candidate-file-only-query-evidence-score-min
           (row-metric-double row :sourceGraphCandidateEvidenceScore))
       (or (pos? (row-metric-double row :candidateGrepScore))
           (pos? (row-metric-double row :candidateLexicalComponentBoost))
           (pos? (positive-metric row :matchedIdentityCompoundTokenPairCount)))))

(defn- query-evidence-source-candidate-key
  [row]
  [(- (positive-metric row :matchedIdentityCompoundTokenPairCount))
   (- (positive-metric row :matchedCompoundTokenPairCount))
   (- (positive-metric row :matchedTokenCount))
   (- (row-metric-double row :candidateGrepScore))
   (- (row-metric-double row :sourceGraphCandidateEvidenceScore))
   (- (row-metric-double row :candidateLexicalComponentBoost))
   (:rank row)
   (row-candidate-source-rank row)
   (:repo-id row)
   (:path row)])

(defn- query-evidence-source-candidate-rows
  [candidate-file-only-rows quota]
  (->> candidate-file-only-rows
       (filter query-evidence-source-candidate-row?)
       (sort-by query-evidence-source-candidate-key)
       (take (min candidate-file-only-query-evidence-reserve-limit quota))
       vec))

(defn- candidate-file-path-self-identity-row?
  [row]
  (and (candidate-file-only-row? row)
       (true? (get-in row [:metrics :queryMatchedPathSelfIdentity]))
       (<= rank-score-candidate-path-self-identity-token-min
           (positive-metric row :matchedTokenCount))
       (<= compact-output-candidate-path-self-identity-token-min-length
           (count (or (path-self-identity-token (:path row)) "")))))

(defn- candidate-file-path-self-identity-key
  [row]
  [(- (count (or (path-self-identity-token (:path row)) "")))
   (- (positive-metric row :matchedTokenCount))
   (- (row-rank-score row))
   (row-candidate-source-rank row)
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- candidate-file-path-self-identity-rows
  [candidate-file-only-rows quota]
  (->> candidate-file-only-rows
       (filter candidate-file-path-self-identity-row?)
       (sort-by candidate-file-path-self-identity-key)
       (take (min candidate-file-only-path-self-identity-reserve-limit quota))
       vec))

(defn- candidate-file-only-selection
  [candidate-files quota]
  (if (pos? (long quota))
    (let [rows (sort-by row-source-order-key
                        (filter candidate-file-only-row? candidate-files))
          source-head (take quota rows)
          identity-rows (dense-file-identity-candidate-rows rows quota)
          query-evidence-rows (query-evidence-source-candidate-rows rows quota)
          path-self-identity-rows (candidate-file-path-self-identity-rows rows
                                                                          quota)
          reserve-rows (->> (concat identity-rows
                                    query-evidence-rows
                                    path-self-identity-rows)
                            ordered-distinct-file-rows
                            (take quota)
                            vec)
          reserve-keys (set (map file-row-key reserve-rows))]
      (->> (concat reserve-rows
                   (remove #(contains? reserve-keys (file-row-key %))
                           source-head))
           ordered-distinct-file-rows
           (take quota)
           vec))
    []))

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
(defn- source-graph-repos
  [candidate-files]
  (->> candidate-files
       (filter source-graph-candidate-row?)
       (keep :repo-id)
       set))
(defn- selected-repos
  [rows]
  (set (keep :repo-id rows)))
(defn- reserve-missing-repo-source-candidate-slots
  [candidate-files direct selection-limit]
  (let [missing-repos (set/difference (source-graph-repos candidate-files)
                                      (selected-repos direct))
        missing-repo-candidate-count (->> candidate-files
                                          (filter #(and (source-graph-candidate-row? %)
                                                        (contains? missing-repos
                                                                   (:repo-id %))))
                                          count)
        reserve (min inspection-missing-repo-source-candidate-reserve
                     missing-repo-candidate-count
                     selection-limit)]
    (if (pos? reserve)
      (let [direct-limit (max 0 (- selection-limit reserve))]
        (if (< direct-limit (count direct))
          (vec (take direct-limit direct))
          direct))
      direct)))
(defn- row-repo-counts
  [rows]
  (frequencies (keep :repo-id rows)))
(defn- multi-repo-selection?
  [candidate-files]
  (< 1 (count (set (keep :repo-id candidate-files)))))
(defn- source-candidates-by-repo
  [candidate-files selected-paths]
  (->> candidate-files
       (filter #(and (:repo-id %)
                     (source-graph-candidate-row? %)
                     (not (contains? selected-paths (file-row-key %)))))
       (group-by :repo-id)
       (map (fn [[repo-id rows]]
              [repo-id (vec rows)]))
       (into {})))
(defn- inspection-repo-source-row-key
  [repo-selection-count row]
  (let [repo-selection-count (long repo-selection-count)
        exported-count (row-candidate-exported-support-label-count row)
        architecture-supported? (pos? (row-metric-double row
                                                         :architectureSupportBoost))
        support-count (positive-metric row :supportCount)
        candidate-file-count (positive-metric row :candidateFileCount)
        retrieved-support-count (positive-metric row :retrievedSupportLabelCount)]
    (if (pos? repo-selection-count)
      [(- exported-count)
       (if architecture-supported? 0 1)
       (if architecture-supported? (- support-count) 0)
       (if architecture-supported? (- candidate-file-count) 0)
       (if architecture-supported? (- retrieved-support-count) 0)
       (row-candidate-source-rank row)
       (:rank row)
       (:repo-id row)
       (:path row)]
      [(if architecture-supported? 0 1)
       (if architecture-supported? (- support-count) 0)
       (if architecture-supported? (- candidate-file-count) 0)
       (if architecture-supported? (- retrieved-support-count) 0)
       (row-candidate-source-rank row)
       (:rank row)
       (:repo-id row)
       (:path row)])))
(defn- best-inspection-repo-source-row
  [repo-selection-count rows]
  (first (sort-by #(inspection-repo-source-row-key repo-selection-count %)
                  rows)))
(defn- without-file-row
  [rows row]
  (let [row-key (file-row-key row)]
    (filterv #(not= row-key (file-row-key %)) rows)))
(defn- inspection-repo-candidates
  [candidate-files selected selection-limit]
  (if (or (not (multi-repo-selection? candidate-files))
          (<= selection-limit (count selected)))
    []
    (let [selected-paths (set (map file-row-key selected))
          remaining (- selection-limit (count selected))
          by-repo (source-candidates-by-repo candidate-files selected-paths)]
      (loop [selected-counts (row-repo-counts selected)
             by-repo by-repo
             remaining remaining
             acc []]
        (if (or (zero? remaining) (empty? by-repo))
          (vec acc)
          (let [[repo-id row] (->> by-repo
                                   (keep (fn [[repo-id rows]]
                                           (when-let [row (best-inspection-repo-source-row
                                                           (long (or (get selected-counts
                                                                          repo-id)
                                                                     0))
                                                           rows)]
                                             [repo-id row])))
                                   (sort-by (fn [[repo-id row]]
                                              (let [repo-selection-count
                                                    (long (or (get selected-counts repo-id)
                                                              0))]
                                                (into [repo-selection-count]
                                                      (inspection-repo-source-row-key
                                                       repo-selection-count
                                                       row)))))
                                   first)
                rows (without-file-row (get by-repo repo-id) row)]
            (recur (update selected-counts repo-id (fnil inc 0))
                   (if (seq rows)
                     (assoc by-repo repo-id (vec rows))
                     (dissoc by-repo repo-id))
                   (dec remaining)
                   (conj acc row))))))))
(defn- inspection-file-selection
  [candidate-files limit]
  (let [selection-limit (long (or limit (count candidate-files)))]
    (when (and (pos? selection-limit) (seq candidate-files))
      (let [identity (inspection-query-identity-candidates candidate-files
                                                           selection-limit)
            selected-identity-keys (set (map file-row-key identity))
            remaining-after-identity (max 0 (- selection-limit
                                               (count identity)))
            direct-candidates (inspection-direct-file-candidates
                               (remove #(contains? selected-identity-keys
                                                   (file-row-key %))
                                       candidate-files)
                               remaining-after-identity)
            direct (->> (reserve-missing-repo-source-candidate-slots
                         candidate-files
                         (vec (concat identity direct-candidates))
                         selection-limit)
                        (remove #(contains? selected-identity-keys
                                            (file-row-key %)))
                        vec)
            repo-candidates (inspection-repo-candidates candidate-files
                                                        (concat identity direct)
                                                        selection-limit)
            frontloaded (vec (concat identity direct repo-candidates))]
        (when (seq frontloaded)
          (let [selected (->> frontloaded
                              (take selection-limit)
                              vec)
                skipped (- (count candidate-files) (count selected))]
            (cond-> {:files (renumber-file-ranks selected)
                     :inspectionDirectFileSelected (count direct)}
              (seq identity)
              (assoc :inspectionIdentitySelected
                     (count identity))
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

(defn- ordered-row-union
  [& row-groups]
  (->> (apply concat row-groups)
       (reduce (fn [acc row]
                 (if (contains? (:seen acc) (file-row-key row))
                   acc
                   (-> acc
                       (update :seen conj (file-row-key row))
                       (update :rows conj row))))
               {:seen #{}
                :rows []})
       :rows
       (sort-by #(or (:rank %) Long/MAX_VALUE))
       vec))

(defn- spread-candidate-support-signature-duplicates
  [rows]
  (let [{:keys [kept deferred]}
        (reduce
         (fn [{:keys [seen] :as acc} row]
           (if-let [k (and (not (prediction-rank-protected? row))
                           (prediction-candidate-support-diversity-key row))]
             (if (contains? seen k)
               (update acc :deferred conj row)
               (-> acc
                   (update :seen conj k)
                   (update :kept conj row)))
             (update acc :kept conj row)))
         {:seen #{}
          :kept []
          :deferred []}
         rows)]
    (vec (concat kept deferred))))

(defn- covers-source-kinds?
  [source-kinds kind-by-path rows]
  (or (empty? source-kinds)
      (let [counts (selected-source-kind-counts kind-by-path rows)]
        (every? #(contains? counts %) source-kinds))))

(defn- compact-unsaturated-decision-tail
  [selected limit source-kinds kind-by-path]
  (let [selection-limit (when limit (long limit))
        selected (vec selected)
        protected (filterv prediction-rank-protected? selected)]
    (if (and selection-limit
             (< (count selected) selection-limit)
             (< unsaturated-decision-tail-min-files (count selected))
             (seq protected))
      (let [protected-score-floor (apply min (map row-rank-score protected))]
        (if (pos? protected-score-floor)
          (let [score-floor (* unsaturated-decision-tail-score-ratio
                               protected-score-floor)
                minimum (take unsaturated-decision-tail-min-files selected)
                scored (filterv #(or (prediction-rank-protected? %)
                                     (<= score-floor (row-rank-score %)))
                                selected)
                compacted (ordered-row-union minimum scored)
                pruned (- (count selected) (count compacted))]
            (if (covers-source-kinds? source-kinds kind-by-path compacted)
              (cond-> {:files (renumber-file-ranks compacted)}
                (pos? pruned)
                (assoc :unsaturatedDecisionTailPruned pruned
                       :unsaturatedDecisionTailScoreFloor score-floor))
              {:files selected}))
          {:files selected}))
      {:files selected})))

(defn- score-elbow-protected-keys
  [selected]
  (->> selected
       (filter candidate-file-only-row?)
       (sort-by row-source-order-key)
       (take default-agent-baseline-candidate-file-only-quota)
       (map file-row-key)
       set))

(defn- score-elbow-tail-cut
  [selected]
  (let [selected (vec selected)]
    (when (and (<= score-elbow-tail-min-files (count selected))
               (not-any? prediction-rank-protected? selected))
      (let [protected-keys (score-elbow-protected-keys selected)]
        (some (fn [prefix-size]
                (let [prefix (subvec selected 0 prefix-size)
                      prefix-keys (set (map file-row-key prefix))
                      next-row (nth selected prefix-size nil)
                      score-floor (apply min (map row-rank-score prefix))
                      next-score (row-rank-score next-row)]
                  (when (and next-row
                             (pos? score-floor)
                             (<= next-score
                                 (* score-elbow-tail-score-ratio
                                    score-floor))
                             (set/subset? protected-keys prefix-keys))
                    {:prefix-size prefix-size
                     :score-floor score-floor})))
              (range score-elbow-tail-prefix-min-files
                     (inc (min (max score-elbow-tail-prefix-max-files
                                    (count protected-keys))
                               (dec (count selected))))))))))

(defn- compact-score-elbow-tail
  [{:keys [files] :as selection} limit source-kinds kind-by-path]
  (let [selection-limit (when limit (long limit))
        selected (vec files)]
    (if-let [{:keys [prefix-size score-floor]}
             (when (and selection-limit
                        (< (count selected) selection-limit))
               (score-elbow-tail-cut selected))]
      (let [compacted (subvec selected 0 prefix-size)
            pruned (- (count selected) (count compacted))]
        (if (and (pos? pruned)
                 (covers-source-kinds? source-kinds kind-by-path compacted))
          (assoc selection
                 :files (renumber-file-ranks compacted)
                 :scoreElbowTailPruned pruned
                 :scoreElbowTailScoreFloor score-floor)
          selection))
      selection)))

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
               candidate-file-only (candidate-file-only-selection
                                    candidate-files
                                    quota)
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
                             spread-candidate-support-signature-duplicates
                             renumber-file-ranks)
               compacted (compact-unsaturated-decision-tail selected
                                                            limit
                                                            source-kinds
                                                            kind-by-path)
               compacted (compact-score-elbow-tail compacted
                                                   limit
                                                   source-kinds
                                                   kind-by-path)]
           (cond-> compacted
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

(defn- compact-result-commands
  [commands]
  (vec (take compact-result-command-limit commands)))

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

(defn- compact-suspected-file
  [file]
  (let [path (:path file)
        repo-id (or (:repoId file) (:repo-id file) (:repo file))]
    (cond-> {:path path
             :rank (:rank file)
             :confidence (bounded-confidence (:confidence file))
             :reason "Ranked by Yggdrasil graph evidence."
             :evidence [(str "ygg:path:" path)]}
      repo-id (assoc :repoId repo-id))))

(defn- compact-suspected-files
  [files]
  (mapv compact-suspected-file files))

(defn- compact-result-output-limit
  [files limit result-scope]
  (when limit
    (let [limit (long limit)
          file-count (count files)]
      (if (and (<= limit 5)
               (not (inspection-files-scope? result-scope))
               (< compact-thin-candidate-output-limit file-count)
               (< file-count
                  (* compact-thin-candidate-output-ratio limit)))
        (min limit compact-thin-candidate-output-limit)
        limit))))

(defn- compact-output-strong-doc-supported-row?
  [row]
  (or (and (<= compact-output-strong-doc-supported-token-pair-min
               (positive-metric row :matchedTokenPairCount))
           (<= compact-output-strong-doc-supported-path-query-token-min
               (positive-metric row :matchedPathQueryTokenCount)))
      (and (<= compact-output-strong-doc-supported-token-min
               (positive-metric row :matchedTokenCount))
           (or (<= compact-output-strong-doc-supported-token-pair-min
                   (positive-metric row :matchedTokenPairCount))
               (<= compact-output-strong-doc-supported-token-pair-min
                   (positive-metric row :matchedCompoundTokenPairCount))))))

(defn- compact-output-strong-doc-only-supported-row?
  [row]
  (and (compact-output-strong-doc-supported-row? row)
       (zero? (positive-metric row :candidateFileCount))
       (zero? (positive-metric row :retrievedSourceCount))))

(defn- compact-output-strong-retrieved-label-doc-row?
  [row]
  (and (pos? (positive-metric row :docCount))
       (<= compact-output-strong-retrieved-label-doc-support-min
           (positive-metric row :retrievedSupportLabelCount))
       (<= compact-output-strong-retrieved-label-doc-token-min
           (positive-metric row :matchedTokenCount))
       (<= candidate-file-only-query-evidence-score-min
           (row-metric-double row :sourceGraphCandidateEvidenceScore))))

(defn- compact-output-doc-supported-key
  [row]
  (let [strong-doc-supported? (compact-output-strong-doc-supported-row? row)]
    [(if strong-doc-supported? 0 1)
     (if strong-doc-supported?
       (- (positive-metric row :matchedTokenPairCount))
       0)
     (if strong-doc-supported?
       (- (positive-metric row :matchedPathQueryTokenCount))
       0)
     (- (positive-metric row :matchedTokenCount))
     (- (positive-metric row :matchedCompoundTokenPairCount))
     (:rank row)
     (:path row)]))

(defn- compact-output-retrieved-supported-key
  [row]
  [(- (positive-metric row :retrievedSourceCount))
   (- (row-metric-double row :repeatedRetrievedSourceBoost))
   (- (row-rank-score row))
   (:rank row)
   (:path row)])

(defn- compact-output-identity-supported-key
  [row]
  [(- (positive-metric row :fileIdentitySupportLabelCount))
   (- (positive-metric row :matchedTokenCount))
   (- (row-metric-double row :candidateLexicalComponentBoost))
   (- (row-metric-double row :graphNeighborScore))
   (:rank row)
   (:path row)])

(defn- compact-output-independent-identity-support?
  [row]
  (or (pos? (row-metric-double row :candidateLexicalComponentBoost))
      (pos? (row-metric-double row :candidateGrepScore))
      (pos? (row-metric-double row :candidateGrepComponentBoost))
      (pos? (row-metric-double row :graphNeighborScore))))

(defn- compact-output-architecture-supported-key
  [row]
  [(- (row-metric-double row :architectureSupportBoost))
   (- (positive-metric row :matchedTokenCount))
   (- (row-metric-double row :rareQueryTokenScore))
   (:rank row)
   (:path row)])

(defn- compact-output-architecture-supported-row
  [files selected-keys]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (zero? (positive-metric % :docCount))
                     (pos? (row-metric-double % :architectureSupportBoost))
                     (<= compact-output-architecture-rare-token-min
                         (row-metric-double % :rareQueryTokenScore))
                     (<= compact-output-architecture-token-min
                         (positive-metric % :matchedTokenCount))))
       (sort-by compact-output-architecture-supported-key)
       first))

(defn- compact-output-doc-supported-row
  [files selected-keys]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (pos? (positive-metric % :docCount))
                     (pos? (positive-metric % :matchedTokenCount))))
       (sort-by compact-output-doc-supported-key)
       first
       (#(when %
           (assoc % ::compact-output-sort-rank
                  (min (double (or (:rank %) Long/MAX_VALUE))
                       (cond
                         (compact-output-strong-retrieved-label-doc-row? %)
                         compact-output-strong-retrieved-label-doc-sort-rank

                         (compact-output-strong-doc-only-supported-row? %)
                         compact-output-strong-doc-only-supported-sort-rank

                         (compact-output-strong-doc-supported-row? %)
                         compact-output-strong-doc-supported-sort-rank

                         :else
                         compact-output-doc-supported-sort-rank)))))))

(defn- compact-output-retrieved-supported-row
  [files selected-keys]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (pos? (positive-metric % :docCount))
                     (or (< 1 (positive-metric % :retrievedSourceCount))
                         (pos? (row-metric-double
                                %
                                :repeatedRetrievedSourceBoost)))))
       (sort-by compact-output-retrieved-supported-key)
       first
       (#(when %
           (assoc % ::compact-output-sort-rank
                  compact-output-retrieved-supported-sort-rank)))))

(defn- compact-output-retrieved-label-doc-key
  [row]
  [(- (positive-metric row :matchedIdentityCompoundTokenPairCount))
   (- (positive-metric row :matchedCompoundTokenPairCount))
   (- (positive-metric row :retrievedSourceCount))
   (- (positive-metric row :fileIdentitySupportLabelCount))
   (- (positive-metric row :retrievedSupportLabelCount))
   (- (positive-metric row :matchedTokenCount))
   (- (row-metric-double row :candidateGrepScore))
   (- (row-metric-double row :sourceGraphCandidateEvidenceScore))
   (- (row-rank-score row))
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- compact-output-retrieved-label-doc-row?
  [row]
  (and (pos? (positive-metric row :docCount))
       (<= 2 (positive-metric row :retrievedSupportLabelCount))
       (<= candidate-file-only-query-evidence-token-min
           (positive-metric row :matchedTokenCount))
       (<= candidate-file-only-query-evidence-score-min
           (row-metric-double row :sourceGraphCandidateEvidenceScore))))

(defn- compact-output-retrieved-label-doc-row
  [files selected-keys]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (compact-output-retrieved-label-doc-row? %)))
       (sort-by compact-output-retrieved-label-doc-key)
       first
       (#(when %
           (assoc % ::compact-output-sort-rank
                  (min (double (or (:rank %) Long/MAX_VALUE))
                       (if (compact-output-strong-retrieved-label-doc-row? %)
                         compact-output-strong-retrieved-label-doc-sort-rank
                         compact-output-retrieved-label-doc-sort-rank)))))))

(defn- compact-output-query-evidence-doc-row?
  [row]
  (and (pos? (positive-metric row :docCount))
       (pos? (positive-metric row :candidateFileCount))
       (<= candidate-file-only-query-evidence-token-min
           (positive-metric row :matchedTokenCount))
       (<= candidate-file-only-query-evidence-score-min
           (row-metric-double row :sourceGraphCandidateEvidenceScore))
       (or (pos? (positive-metric row :matchedTokenPairCount))
           (pos? (positive-metric row :matchedCompoundTokenPairCount))
           (pos? (positive-metric row :matchedIdentityCompoundTokenPairCount))
           (pos? (row-metric-double row :candidateGrepScore))
           (pos? (row-metric-double row :candidateLexicalComponentBoost)))))

(defn- compact-output-query-evidence-doc-key
  [row]
  [(- (positive-metric row :matchedTokenPairCount))
   (- (positive-metric row :matchedCompoundTokenPairCount))
   (- (positive-metric row :matchedIdentityCompoundTokenPairCount))
   (- (positive-metric row :matchedTokenCount))
   (- (row-metric-double row :candidateGrepScore))
   (- (row-metric-double row :sourceGraphCandidateEvidenceScore))
   (- (row-rank-score row))
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- compact-output-query-evidence-doc-row
  [files selected-keys]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (compact-output-query-evidence-doc-row? %)))
       (sort-by compact-output-query-evidence-doc-key)
       first
       (#(when %
           (assoc % ::compact-output-sort-rank
                  (min (double (or (:rank %) Long/MAX_VALUE))
                       compact-output-query-evidence-doc-sort-rank))))))

(defn- compact-output-doc-source-graph-grep-row?
  [row]
  (and (pos? (positive-metric row :docCount))
       (pos? (positive-metric row :candidateFileCount))
       (pos? (positive-metric row :retrievedSourceCount))
       (<= (row-candidate-source-rank row)
           compact-output-doc-source-graph-grep-rank-window)
       (<= compact-output-doc-source-graph-grep-token-min
           (positive-metric row :matchedTokenCount))
       (<= compact-output-doc-source-graph-grep-source-score-min
           (row-metric-double row :sourceGraphCandidateEvidenceScore))
       (<= compact-output-doc-source-graph-grep-grep-score-min
           (row-metric-double row :candidateGrepScore))))

(defn- compact-output-doc-source-graph-grep-key
  [row]
  [(row-candidate-source-rank row)
   (- (positive-metric row :matchedTokenCount))
   (- (row-metric-double row :candidateGrepScore))
   (- (row-metric-double row :sourceGraphCandidateEvidenceScore))
   (- (row-rank-score row))
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- compact-output-doc-source-graph-grep-row
  [files selected-keys]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (compact-output-doc-source-graph-grep-row? %)))
       (sort-by compact-output-doc-source-graph-grep-key)
       first
       (#(when %
           (assoc % ::compact-output-sort-rank
                  (min (double (or (:rank %) Long/MAX_VALUE))
                       compact-output-doc-source-graph-grep-sort-rank))))))

(defn- row-query-matched-path-self-identity?
  [row]
  (true? (get-in row [:metrics :queryMatchedPathSelfIdentity])))

(defn- row-path-token-count
  [row]
  (count (path-match-token-set (path-without-extension (:path row)))))

(defn- compact-output-retrieved-path-self-identity-row?
  [row]
  (and (pos? (positive-metric row :docCount))
       (pos? (positive-metric row :retrievedSourceCount))
       (row-query-matched-path-self-identity? row)))

(defn- compact-output-retrieved-path-self-identity-key
  [row]
  [(positive-metric row :firstSourceRank)
   (- (positive-metric row :matchedTokenCount))
   (- (row-rank-score row))
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- compact-output-retrieved-path-self-identity-rows
  [files selected-keys]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (compact-output-retrieved-path-self-identity-row? %)))
       (sort-by compact-output-retrieved-path-self-identity-key)
       (take compact-output-retrieved-path-self-identity-limit)
       (map-indexed (fn [idx row]
                      (assoc row ::compact-output-sort-rank
                             (+ compact-output-retrieved-path-self-identity-sort-rank
                                (* idx
                                   compact-output-retrieved-path-self-identity-sort-step)))))))

(defn- compact-output-retrieved-path-query-token-row?
  [row]
  (and (pos? (positive-metric row :docCount))
       (pos? (positive-metric row :retrievedSourceCount))
       (not (row-query-matched-path-self-identity? row))
       (<= rank-score-retrieved-path-query-token-min
           (positive-metric row :matchedPathQueryTokenCount))))

(defn- compact-output-retrieved-path-query-token-key
  [row]
  [(row-path-token-count row)
   (- (positive-metric row :matchedPathQueryTokenCount))
   (positive-metric row :firstSourceRank)
   (- (positive-metric row :matchedTokenCount))
   (- (row-rank-score row))
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- compact-output-retrieved-path-query-token-rows
  [files selected-keys]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (compact-output-retrieved-path-query-token-row? %)))
       (sort-by compact-output-retrieved-path-query-token-key)
       (take compact-output-retrieved-path-query-token-limit)
       (map-indexed (fn [idx row]
                      (assoc row ::compact-output-sort-rank
                             (+ compact-output-retrieved-path-query-token-sort-rank
                                idx))))))

(defn- compact-output-candidate-path-self-identity-row?
  [row]
  (and (candidate-file-only-row? row)
       (or (pos? (row-metric-double row :candidatePathSelfIdentityBoost))
           (candidate-file-path-self-identity-row? row))))

(defn- compact-output-candidate-path-self-identity-key
  [row]
  [(row-candidate-source-rank row)
   (- (row-rank-score row))
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- compact-output-candidate-path-self-identity-rows
  [files selected-keys]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (compact-output-candidate-path-self-identity-row? %)))
       (sort-by compact-output-candidate-path-self-identity-key)
       (take compact-output-candidate-path-self-identity-limit)
       (map-indexed (fn [idx row]
                      (assoc row ::compact-output-sort-rank
                             (+ compact-output-candidate-path-self-identity-sort-rank
                                idx))))))

(defn- compact-output-query-matched-exported-support-row?
  [row]
  (and (candidate-file-only-row? row)
       (pos? (positive-metric row :queryMatchedExportedSupportLabelCount))
       (pos? (row-metric-double row :candidateOnlyExportedSupportBoost))))

(defn- compact-output-query-matched-exported-support-key
  [row]
  [(row-candidate-source-rank row)
   (- (row-metric-double row :candidateOnlyExportedSupportBoost))
   (- (row-rank-score row))
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- compact-output-query-matched-exported-support-rows
  [files selected-keys]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (compact-output-query-matched-exported-support-row? %)))
       (sort-by compact-output-query-matched-exported-support-key)
       (take compact-output-query-matched-exported-support-limit)
       (map-indexed (fn [idx row]
                      (assoc row ::compact-output-sort-rank
                             (+ compact-output-query-matched-exported-support-sort-rank
                                idx))))))

(defn- compact-output-support-owner-source-graph-row?
  [row]
  (and (candidate-file-only-row? row)
       (pos? (positive-metric row :supportOwnerEvidenceCount))
       (pos? (positive-metric row :directFileCandidateCount))
       (<= candidate-file-only-query-evidence-token-min
           (positive-metric row :matchedTokenCount))
       (<= compact-output-support-owner-source-graph-source-score-min
           (row-metric-double row :sourceGraphCandidateEvidenceScore))
       (<= 2 (positive-metric row :retrievedSupportLabelCount))))

(defn- compact-output-support-owner-source-graph-key
  [row]
  [(- (positive-metric row :supportOwnerPrimaryLabelSpecificTokenCount))
   (- (positive-metric row :supportOwnerPrimaryLabelMatchedTokenCount))
   (- (positive-metric row :matchedIdentityCompoundTokenPairCount))
   (- (positive-metric row :matchedCompoundTokenPairCount))
   (- (positive-metric row :matchedTokenCount))
   (- (positive-metric row :retrievedSupportLabelCount))
   (- (row-metric-double row :sourceGraphCandidateEvidenceScore))
   (row-candidate-source-rank row)
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- compact-output-support-owner-source-graph-rows
  [files selected-keys]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (compact-output-support-owner-source-graph-row? %)))
       (sort-by compact-output-support-owner-source-graph-key)
       (take compact-output-support-owner-source-graph-limit)
       (map-indexed (fn [idx row]
                      (assoc row ::compact-output-sort-rank
                             (+ compact-output-support-owner-source-graph-sort-rank
                                (* 0.01 idx)))))))

(defn- path-directory
  [path]
  (let [path (str path)
        idx (.lastIndexOf path "/")]
    (if (neg? idx)
      ""
      (subs path 0 idx))))

(defn- file-row-directory-key
  [row]
  [(repo-key-name (or (:repo-id row) (:repo row)))
   (path-directory (:path row))])

(defn- path-depth
  [dir]
  (if (str/blank? dir)
    0
    (count (remove str/blank? (str/split (str dir) #"/")))))

(defn- compact-output-after-preserved-head
  [sort-rank]
  (max compact-output-after-preserved-head-sort-rank
       (double sort-rank)))

(defn- compact-output-directory-anchor-row?
  [row]
  (or (pos? (positive-metric row :docCount))
      (pos? (positive-metric row :entityCount))
      (pos? (positive-metric row :retrievedSourceCount))
      (pos? (positive-metric row :exactPathSourceCount))
      (pos? (positive-metric row :matchedTokenCount))))

(defn- compact-output-selected-directory-sort-rank
  [selected-rows row]
  (let [dir-key (file-row-directory-key row)
        row-rank (or (:rank row) Long/MAX_VALUE)
        same-dir-anchors (->> selected-rows
                              (filter #(= dir-key (file-row-directory-key %)))
                              (filter compact-output-directory-anchor-row?)
                              (filter #(< (or (:rank %) Long/MAX_VALUE)
                                          row-rank))
                              seq)
        same-dir-sort-ranks (->> same-dir-anchors
                                 (keep ::compact-output-sort-rank)
                                 seq)
        same-dir-ranks (->> same-dir-anchors
                            (keep :rank)
                            seq)]
    (cond
      same-dir-sort-ranks
      (+ (double (apply max same-dir-sort-ranks)) 0.01)

      same-dir-ranks
      (/ (+ (double (apply max same-dir-ranks)) 0.5) 10.0)

      :else
      (double row-rank))))

(defn- compact-output-identity-supported-row
  [files selected-keys selected-rows]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (pos? (positive-metric % :directFileCandidateCount))
                     (<= compact-output-identity-support-min
                         (positive-metric % :fileIdentitySupportLabelCount))))
       (sort-by compact-output-identity-supported-key)
       first
       (#(when %
           (let [sort-rank (compact-output-selected-directory-sort-rank
                            selected-rows
                            %)]
             (assoc % ::compact-output-sort-rank
                    (if (compact-output-independent-identity-support? %)
                      sort-rank
                      (compact-output-after-preserved-head sort-rank))))))))

(defn- compact-output-query-evidence-source-row
  [files selected-keys selected-rows]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (query-evidence-source-candidate-row? %)))
       (sort-by query-evidence-source-candidate-key)
       first
       (#(when %
           (let [directory-sort-rank
                 (compact-output-selected-directory-sort-rank selected-rows %)]
             (assoc % ::compact-output-sort-rank
                    (if (<= (row-candidate-source-rank %)
                            compact-output-early-source-graph-rank-window)
                      (min compact-output-early-query-evidence-source-sort-rank
                           directory-sort-rank)
                      directory-sort-rank)))))))

(defn- compact-output-early-source-graph-row?
  [row]
  (and (candidate-file-only-row? row)
       (<= (row-candidate-source-rank row)
           compact-output-early-source-graph-rank-window)
       (<= compact-output-early-source-graph-token-min
           (positive-metric row :matchedTokenCount))
       (<= candidate-file-only-query-evidence-score-min
           (row-metric-double row :sourceGraphCandidateEvidenceScore))
       (pos? (row-metric-double row :candidateGrepScore))))

(defn- compact-output-early-source-graph-key
  [row]
  [(row-candidate-source-rank row)
   (- (row-metric-double row :sourceGraphCandidateEvidenceScore))
   (- (row-metric-double row :candidateGrepScore))
   (- (positive-metric row :matchedTokenCount))
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- compact-output-early-source-graph-row
  [files selected-keys selected-rows]
  (let [path-self-identity-candidate?
        (or (some candidate-file-path-self-identity-row? selected-rows)
            (some #(and (not (contains? selected-keys (file-row-key %)))
                        (candidate-file-path-self-identity-row? %))
                  files))]
    (->> files
         (filter #(and (not (contains? selected-keys (file-row-key %)))
                       (compact-output-early-source-graph-row? %)))
         (sort-by compact-output-early-source-graph-key)
         first
         (#(when %
             (let [sort-rank (compact-output-selected-directory-sort-rank
                              selected-rows
                              %)]
               (assoc % ::compact-output-sort-rank
                      (if path-self-identity-candidate?
                        (compact-output-after-preserved-head sort-rank)
                        sort-rank))))))))

(defn- compact-output-retrieved-label-source-key
  [row]
  [(- (positive-metric row :retrievedSupportLabelCount))
   (- (row-metric-double row :retrievedSupportLabelBoost))
   (- (positive-metric row :matchedIdentityCompoundTokenPairCount))
   (- (positive-metric row :matchedCompoundTokenPairCount))
   (- (positive-metric row :matchedTokenCount))
   (- (row-metric-double row :sourceGraphCandidateEvidenceScore))
   (row-candidate-source-rank row)
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- compact-output-retrieved-label-source-row?
  [row]
  (and (candidate-file-only-row? row)
       (<= 2 (positive-metric row :retrievedSupportLabelCount))
       (<= candidate-file-only-query-evidence-token-min
           (positive-metric row :matchedTokenCount))
       (<= candidate-file-only-query-evidence-score-min
           (row-metric-double row :sourceGraphCandidateEvidenceScore))))

(defn- compact-output-retrieved-label-source-row
  [files selected-keys]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (compact-output-retrieved-label-source-row? %)))
       (sort-by compact-output-retrieved-label-source-key)
       first
       (#(when %
           (assoc % ::compact-output-sort-rank
                  (double (min (long (or (:rank %) Long/MAX_VALUE))
                               (row-candidate-source-rank %)
                               compact-output-retrieved-label-source-sort-rank)))))))

(defn- compact-output-directory-counts
  [rows]
  (frequencies (map file-row-directory-key rows)))

(defn- compact-output-co-located-candidate-key
  [directory-counts row]
  (let [dir-key (file-row-directory-key row)
        dir (second dir-key)]
    [(- (long (or (get directory-counts dir-key) 0)))
     (path-depth dir)
     (row-candidate-source-rank row)
     (:rank row)
     (:repo-id row)
     (:path row)]))

(defn- compact-output-co-located-sort-rank
  [files selected-rows row]
  (let [dir-key (file-row-directory-key row)
        row-rank (or (:rank row) Long/MAX_VALUE)
        same-dir-selected-ranks (->> selected-rows
                                     (filter #(= dir-key
                                                 (file-row-directory-key %)))
                                     (filter #(pos? (positive-metric
                                                     %
                                                     :docCount)))
                                     (filter #(< (or (:rank %) Long/MAX_VALUE)
                                                 row-rank))
                                     (keep ::compact-output-sort-rank)
                                     seq)
        same-dir-ranks (->> (concat selected-rows files)
                            (filter #(= dir-key (file-row-directory-key %)))
                            (filter #(pos? (positive-metric % :docCount)))
                            (filter #(< (or (:rank %) Long/MAX_VALUE)
                                        row-rank))
                            (keep :rank)
                            seq)]
    (cond
      same-dir-selected-ranks
      (+ (double (apply min same-dir-selected-ranks))
         (* 0.01 (count same-dir-selected-ranks)))

      same-dir-ranks
      (/ (+ (double (apply max same-dir-ranks)) 0.5) 10.0)

      :else
      (double (or (:rank row) Long/MAX_VALUE)))))

(defn- compact-output-co-located-candidate-row
  [files selected-keys selected-rows]
  (let [directory-counts (compact-output-directory-counts selected-rows)]
    (->> files
         (filter #(let [dir-key (file-row-directory-key %)]
                    (and (not (contains? selected-keys (file-row-key %)))
                         (pos? (long (or (get directory-counts dir-key) 0)))
                         (direct-file-candidate-row? %)
                         (pos? (row-metric-double
                                %
                                :sourceGraphCandidateEvidenceScore))
                         (<= candidate-file-only-query-evidence-token-min
                             (positive-metric % :matchedTokenCount)))))
         (sort-by #(compact-output-co-located-candidate-key directory-counts %))
         first
         (#(when %
             (assoc % ::compact-output-sort-rank
                    (compact-output-co-located-sort-rank files
                                                         selected-rows
                                                         %)))))))

(defn- compact-output-source-kind-sibling-key
  [kind-by-path selected-kind-counts directory-counts row]
  (let [kind (row-path-kind kind-by-path row)
        dir-key (file-row-directory-key row)]
    [(long (or (get selected-kind-counts kind) 0))
     (- (long (or (get directory-counts dir-key) 0)))
     (- (positive-metric row :matchedTokenCount))
     (- (row-metric-double row :sourceGraphCandidateEvidenceScore))
     (- (row-metric-double row :candidateGrepScore))
     (- (row-metric-double row :candidateLexicalComponentBoost))
     (- (row-rank-score row))
     (row-candidate-source-rank row)
     (:rank row)
     (:repo-id row)
     (:path row)]))

(defn- compact-output-source-kind-sibling-evidence-row?
  [row]
  (and (pos? (positive-metric row :candidateFileCount))
       (pos? (positive-metric row :matchedTokenCount))
       (<= candidate-file-only-query-evidence-score-min
           (row-metric-double row :sourceGraphCandidateEvidenceScore))
       (or (pos? (row-metric-double row :candidateGrepScore))
           (pos? (row-metric-double row :candidateLexicalComponentBoost))
           (pos? (positive-metric row :matchedIdentityCompoundTokenPairCount)))))

(defn- compact-output-source-kind-sibling-sort-rank
  [selected-rows row]
  (let [dir-key (file-row-directory-key row)
        row-rank (or (:rank row) Long/MAX_VALUE)
        same-dir-rows (->> selected-rows
                           (filter #(= dir-key (file-row-directory-key %)))
                           (filter #(< (or (:rank %) Long/MAX_VALUE)
                                       row-rank))
                           seq)
        same-dir-sort-ranks (->> same-dir-rows
                                 (keep ::compact-output-sort-rank)
                                 seq)
        same-dir-ranks (->> same-dir-rows
                            (keep :rank)
                            seq)]
    (cond
      same-dir-sort-ranks
      (+ (double (apply min same-dir-sort-ranks))
         (* 0.01 (count same-dir-sort-ranks)))

      same-dir-ranks
      (+ (double (apply min same-dir-ranks))
         (* 0.5 (count same-dir-ranks))))))

(defn- compact-output-source-kind-sibling-row
  [files selected-keys selected-rows source-kinds kind-by-path]
  (let [source-kinds (set source-kinds)
        directory-counts (compact-output-directory-counts selected-rows)
        selected-kind-counts (selected-source-kind-counts kind-by-path selected-rows)]
    (when (and (< 1 (count source-kinds))
               (seq kind-by-path))
      (->> files
           (filter #(let [dir-key (file-row-directory-key %)
                          kind (row-path-kind kind-by-path %)]
                      (and (not (contains? selected-keys (file-row-key %)))
                           (contains? source-kinds kind)
                           (pos? (long (or (get directory-counts dir-key) 0)))
                           (compact-output-source-kind-sibling-evidence-row? %))))
           (sort-by #(compact-output-source-kind-sibling-key
                      kind-by-path
                      selected-kind-counts
                      directory-counts
                      %))
           first
           (#(when %
               (assoc % ::compact-output-sort-rank
                      (compact-output-source-kind-sibling-sort-rank selected-rows
                                                                    %))))))))

(defn- compact-output-source-kind-sibling-row?
  [source-kinds kind-by-path selected-rows row]
  (and (< 1 (count source-kinds))
       (seq kind-by-path)
       (contains? source-kinds (row-path-kind kind-by-path row))
       (compact-output-source-kind-sibling-evidence-row? row)
       (some? (compact-output-source-kind-sibling-sort-rank selected-rows
                                                            row))))

(defn- compact-output-annotate-source-kind-siblings
  [selected source-kinds kind-by-path]
  (let [source-kinds (set source-kinds)
        selected-rows (:rows selected)]
    (if (and (< 1 (count source-kinds))
             (seq kind-by-path)
             (seq selected-rows))
      (update selected
              :rows
              (fn [rows]
                (mapv (fn [row]
                        (if (compact-output-source-kind-sibling-row?
                             source-kinds
                             kind-by-path
                             selected-rows
                             row)
                          (let [sort-rank (compact-output-source-kind-sibling-sort-rank
                                           selected-rows
                                           row)]
                            (update row
                                    ::compact-output-sort-rank
                                    #(if %
                                       (min (double %) sort-rank)
                                       sort-rank)))
                          row))
                      rows)))
      selected)))

(defn- compact-output-directory-evidence-candidate-key
  [files selected-rows row]
  [(compact-output-co-located-sort-rank files selected-rows row)
   (- (row-metric-double row :directoryEvidenceBoost))
   (:rank row)
   (:repo-id row)
   (:path row)])

(defn- compact-output-directory-evidence-candidate-row
  [files selected-keys selected-rows]
  (->> files
       (filter #(and (not (contains? selected-keys (file-row-key %)))
                     (candidate-file-only-row? %)
                     (pos? (row-metric-double %
                                              :directoryEvidenceBoost))))
       (sort-by #(compact-output-directory-evidence-candidate-key
                  files
                  selected-rows
                  %))
       first
       (#(when %
           (assoc % ::compact-output-sort-rank
                  (min compact-output-directory-evidence-candidate-sort-rank
                       (compact-output-co-located-sort-rank files
                                                            selected-rows
                                                            %)))))))

(defn- add-compact-output-row
  [selected row]
  (if row
    (let [row-key (file-row-key row)]
      (if (contains? (:keys selected) row-key)
        (if-let [sort-rank (::compact-output-sort-rank row)]
          (update selected
                  :rows
                  (fn [rows]
                    (mapv (fn [selected-row]
                            (if (= row-key (file-row-key selected-row))
                              (update selected-row
                                      ::compact-output-sort-rank
                                      #(if %
                                         (min (double %) (double sort-rank))
                                         sort-rank))
                              selected-row))
                          rows)))
          selected)
        (-> selected
            (update :rows conj row)
            (update :keys conj row-key))))
    selected))

(defn- fill-compact-output-selection
  [selected files limit]
  (let [remaining-limit (max 0 (- (long limit) (count (:rows selected))))
        remaining (remove #(contains? (:keys selected) (file-row-key %))
                          files)]
    (reduce add-compact-output-row
            selected
            (take remaining-limit remaining))))

(defn- compact-output-doc-identity-row?
  [row]
  (and (pos? (positive-metric row :docCount))
       (pos? (positive-metric row :candidateFileCount))
       (<= compact-output-identity-support-min
           (positive-metric row :fileIdentitySupportLabelCount))
       (<= candidate-file-only-query-evidence-token-min
           (positive-metric row :matchedTokenCount))))

(defn- compact-output-doc-directory-evidence-row?
  [row]
  (and (pos? (positive-metric row :docCount))
       (pos? (row-metric-double row :directoryEvidenceBoost))))

(defn- compact-output-retrieved-path-grep-evidence-row?
  [row]
  (pos? (row-metric-double row :retrievedPathGrepEvidenceBoost)))

(defn- compact-output-source-graph-query-evidence-row?
  [row]
  (and (pos? (row-metric-double row :sourceGraphQueryEvidenceBoost))
       (<= candidate-file-only-query-evidence-token-min
           (positive-metric row :matchedTokenCount))
       (<= candidate-file-only-query-evidence-score-min
           (row-metric-double row :sourceGraphCandidateEvidenceScore))
       (or (pos? (positive-metric row :matchedTokenPairCount))
           (pos? (positive-metric row :matchedCompoundTokenPairCount))
           (pos? (positive-metric row :matchedIdentityCompoundTokenPairCount))
           (<= rank-score-source-graph-query-evidence-path-token-min
               (positive-metric row :matchedPathQueryTokenCount)))))

(defn- compact-output-doc-supported-source-graph-query-row?
  [row]
  (pos? (row-metric-double row :docSupportedSourceGraphQueryBoost)))

(defn- compact-output-candidate-source-graph-head-row?
  [row]
  (and (pos? (row-metric-double row :candidateOnlySourceGraphHeadBoost))
       (<= compact-output-candidate-source-graph-head-source-score-min
           (row-metric-double row :sourceGraphCandidateEvidenceScore))))

(defn- compact-output-supported-source-graph-query-evidence-row?
  [row]
  (and (compact-output-source-graph-query-evidence-row? row)
       (or (pos? (positive-metric row :docCount))
           (< 1 (positive-metric row :candidateFileCount))
           (pos? (positive-metric row :retrievedSourceCount))
           (query-evidence-source-candidate-row? row)
           (compact-output-early-source-graph-row? row)
           (compact-output-retrieved-label-source-row? row))))

(defn- compact-output-prune-protected-row?
  [row]
  (or (compact-output-retrieved-label-doc-row? row)
      (compact-output-query-evidence-doc-row? row)
      (compact-output-retrieved-path-self-identity-row? row)
      (compact-output-retrieved-path-query-token-row? row)
      (compact-output-candidate-path-self-identity-row? row)
      (compact-output-query-matched-exported-support-row? row)
      (compact-output-retrieved-label-source-row? row)
      (compact-output-doc-identity-row? row)
      (compact-output-early-source-graph-row? row)
      (compact-output-doc-directory-evidence-row? row)
      (compact-output-retrieved-path-grep-evidence-row? row)
      (compact-output-doc-source-graph-grep-row? row)
      (compact-output-support-owner-source-graph-row? row)
      (compact-output-candidate-source-graph-head-row? row)
      (compact-output-source-graph-query-evidence-row? row)
      (query-evidence-source-candidate-row? row)
      (candidate-file-only-row? row)))

(defn- compact-output-score-tail-cut
  [selected source-kinds kind-by-path]
  (let [selected (vec selected)
        protected-keys (->> selected
                            (filter compact-output-prune-protected-row?)
                            (map file-row-key)
                            set)]
    (when (and (<= compact-output-score-tail-min-files (count selected))
               (not-any? prediction-rank-protected? selected))
      (some (fn [prefix-size]
              (let [prefix (subvec selected 0 prefix-size)
                    prefix-keys (set (map file-row-key prefix))
                    next-row (nth selected prefix-size nil)
                    score-floor (apply min (map row-rank-score prefix))
                    next-score (row-rank-score next-row)]
                (when (and next-row
                           (pos? score-floor)
                           (<= next-score
                               (* compact-output-score-tail-score-ratio
                                  score-floor))
                           (set/subset? protected-keys prefix-keys)
                           (covers-source-kinds?
                            source-kinds
                            kind-by-path
                            (subvec selected 0 prefix-size)))
                  prefix-size)))
            (range compact-output-score-tail-prefix-min-files
                   (inc (min compact-output-score-tail-prefix-max-files
                             (dec (count selected)))))))))

(defn- compact-output-direct-identity-row?
  [row]
  (and (pos? (positive-metric row :directFileCandidateCount))
       (pos? (row-metric-double row :architectureSupportBoost))
       (<= compact-output-identity-support-min
           (positive-metric row :fileIdentitySupportLabelCount))))

(defn- compact-output-repeated-retrieved-row?
  [row]
  (and (pos? (positive-metric row :docCount))
       (or (< 1 (positive-metric row :retrievedSourceCount))
           (pos? (row-metric-double row :repeatedRetrievedSourceBoost)))))

(defn- compact-output-identity-compound-source-row?
  [row]
  (and (candidate-file-only-row? row)
       (pos? (positive-metric row :directFileCandidateCount))
       (pos? (positive-metric row :matchedIdentityCompoundTokenPairCount))
       (<= candidate-file-only-query-evidence-token-min
           (positive-metric row :matchedTokenCount))
       (<= candidate-file-only-query-evidence-score-min
           (row-metric-double row :sourceGraphCandidateEvidenceScore))
       (<= (row-candidate-source-rank row)
           compact-output-early-source-graph-rank-window)))

(defn- compact-output-derived-sort-rank
  [row]
  (when-let [sort-ranks (->> [(when (compact-output-doc-directory-evidence-row? row)
                                compact-output-doc-directory-evidence-sort-rank)
                              (when (compact-output-retrieved-path-grep-evidence-row? row)
                                compact-output-retrieved-path-grep-sort-rank)
                              (when (compact-output-doc-source-graph-grep-row? row)
                                compact-output-doc-source-graph-grep-sort-rank)
                              (when (compact-output-candidate-source-graph-head-row? row)
                                compact-output-candidate-source-graph-head-sort-rank)
                              (when (compact-output-retrieved-path-self-identity-row?
                                     row)
                                compact-output-retrieved-path-self-identity-sort-rank)
                              (when (compact-output-doc-supported-source-graph-query-row?
                                     row)
                                compact-output-doc-supported-source-graph-query-sort-rank)
                              (when (compact-output-repeated-retrieved-row? row)
                                compact-output-retrieved-supported-sort-rank)
                              (when (compact-output-supported-source-graph-query-evidence-row?
                                     row)
                                compact-output-source-graph-query-evidence-sort-rank)
                              (when (compact-output-identity-compound-source-row? row)
                                compact-output-identity-compound-source-sort-rank)]
                             (keep identity)
                             seq)]
    (apply min sort-ranks)))

(defn- compact-output-sort-key
  [row]
  (let [sort-rank (->> [(::compact-output-sort-rank row)
                        (compact-output-derived-sort-rank row)
                        (:rank row)]
                       (keep identity)
                       (map double)
                       seq)]
    [(double (if sort-rank
               (apply min sort-rank)
               Long/MAX_VALUE))
     (long (or (:rank row) Long/MAX_VALUE))
     (:repo-id row)
     (:path row)]))

(defn- compact-output-sort-and-renumber
  [rows]
  (->> rows
       (sort-by compact-output-sort-key)
       spread-candidate-support-signature-duplicates
       (map #(dissoc % ::compact-output-sort-rank))
       renumber-file-ranks))

(defn- compact-output-core-evidence-rows
  [selected source-kinds kind-by-path]
  (let [base-core (ordered-row-union
                   (filter compact-output-direct-identity-row? selected)
                   (filter compact-output-doc-identity-row? selected)
                   (filter compact-output-repeated-retrieved-row? selected))
        core (ordered-row-union
              base-core
              (filter query-evidence-source-candidate-row? selected)
              (filter compact-output-retrieved-label-source-row? selected))]
    (when (and (< 1 (count base-core))
               (< 1 (count core))
               (< (count core) (count selected))
               (covers-source-kinds? source-kinds kind-by-path core))
      (compact-output-sort-and-renumber core))))

(defn- compact-output-candidate-only-rows
  [selected limit source-kinds kind-by-path]
  (let [selected (vec selected)
        max-files (if (<= (long limit) compact-output-score-tail-max-limit)
                    compact-output-preserved-head-count
                    compact-output-candidate-only-max-files)]
    (when (and (< max-files (count selected))
               (every? candidate-file-only-row? selected))
      (let [compacted (subvec selected
                              0
                              max-files)]
        (when (covers-source-kinds? source-kinds kind-by-path compacted)
          (renumber-file-ranks compacted))))))

(defn- compact-output-aggressive-pruning?
  [limit selected]
  (or (<= (long limit) compact-output-score-tail-max-limit)
      (< (count selected) (long limit))))

(defn- compact-output-prune-score-tail
  [selected limit result-scope source-kinds kind-by-path]
  (let [limit (long limit)
        selected (vec selected)]
    (or (when (and (compact-output-aggressive-pruning? limit selected)
                   (not (inspection-files-scope? result-scope)))
          (compact-output-core-evidence-rows selected source-kinds kind-by-path))
        (when (and (compact-output-aggressive-pruning? limit selected)
                   (not (inspection-files-scope? result-scope)))
          (compact-output-candidate-only-rows selected
                                              limit
                                              source-kinds
                                              kind-by-path))
        (if-let [prefix-size (when (and (<= limit compact-output-score-tail-max-limit)
                                        (not (inspection-files-scope? result-scope)))
                               (compact-output-score-tail-cut selected
                                                              source-kinds
                                                              kind-by-path))]
          (let [compacted (subvec selected 0 prefix-size)]
            (renumber-file-ranks compacted))
          selected))))

(defn- compact-output-selected-files
  ([files limit result-scope]
   (compact-output-selected-files files limit result-scope [] {}))
  ([files limit result-scope source-kinds kind-by-path]
   (let [limit (when limit (long limit))]
     (cond
       (nil? limit)
       files

       (or (inspection-files-scope? result-scope)
           (< limit 5))
       (vec (take limit files))

       :else
       (let [head-count (min compact-output-preserved-head-count limit)
             head-rows (map-indexed (fn [idx row]
                                      (assoc row
                                             ::compact-output-sort-rank
                                             (/ (inc idx) 10.0)))
                                    (take head-count files))
             empty-selection {:rows []
                              :keys #{}}
             head-selection (reduce add-compact-output-row
                                    empty-selection
                                    head-rows)
             selected (add-compact-output-row
                       head-selection
                       (compact-output-doc-supported-row files
                                                         (:keys head-selection)))
             selected (add-compact-output-row
                       selected
                       (compact-output-identity-supported-row files
                                                              (:keys selected)
                                                              (:rows selected)))
             selected (add-compact-output-row
                       selected
                       (compact-output-architecture-supported-row files
                                                                  (:keys selected)))
             selected (add-compact-output-row
                       selected
                       (compact-output-retrieved-label-doc-row
                        files
                        (:keys selected)))
             selected (add-compact-output-row
                       selected
                       (compact-output-retrieved-supported-row
                        files
                        (:keys selected)))
             selected (add-compact-output-row
                       selected
                       (compact-output-query-evidence-doc-row
                        files
                        (:keys selected)))
             selected (add-compact-output-row
                       selected
                       (compact-output-doc-source-graph-grep-row
                        files
                        (:keys selected)))
             selected (reduce add-compact-output-row
                              selected
                              (compact-output-retrieved-path-self-identity-rows
                               files
                               (:keys selected)))
             selected (reduce add-compact-output-row
                              selected
                              (compact-output-retrieved-path-query-token-rows
                               files
                               (:keys selected)))
             selected (reduce add-compact-output-row
                              selected
                              (compact-output-candidate-path-self-identity-rows
                               files
                               (:keys selected)))
             selected (reduce add-compact-output-row
                              selected
                              (compact-output-query-matched-exported-support-rows
                               files
                               (:keys selected)))
             selected (reduce add-compact-output-row
                              selected
                              (compact-output-support-owner-source-graph-rows
                               files
                               (:keys selected)))
             selected (add-compact-output-row
                       selected
                       (compact-output-query-evidence-source-row
                        files
                        (:keys selected)
                        (:rows selected)))
             selected (add-compact-output-row
                       selected
                       (compact-output-early-source-graph-row
                        files
                        (:keys selected)
                        (:rows selected)))
             selected (add-compact-output-row
                       selected
                       (compact-output-retrieved-label-source-row
                        files
                        (:keys selected)))
             selected (add-compact-output-row
                       selected
                       (compact-output-co-located-candidate-row
                        files
                        (:keys selected)
                        (:rows selected)))
             selected (add-compact-output-row
                       selected
                       (compact-output-source-kind-sibling-row
                        files
                        (:keys selected)
                        (:rows selected)
                        source-kinds
                        kind-by-path))
             selected (add-compact-output-row
                       selected
                       (compact-output-directory-evidence-candidate-row
                        files
                        (:keys selected)
                        (:rows selected)))
             minimum-count (min limit 2)
             selected (if (< (count (:rows selected)) minimum-count)
                        (reduce add-compact-output-row
                                selected
                                (take (- minimum-count (count (:rows selected)))
                                      (remove #(contains? (:keys selected)
                                                          (file-row-key %))
                                              files)))
                        selected)
             selected (fill-compact-output-selection selected files limit)
             selected (compact-output-annotate-source-kind-siblings
                       selected
                       source-kinds
                       kind-by-path)
             selected-files (vec (take limit
                                       (compact-output-sort-and-renumber
                                        (:rows selected))))]
         (compact-output-prune-score-tail selected-files
                                          limit
                                          result-scope
                                          source-kinds
                                          kind-by-path))))))

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
        support-count (long (or (:supportCount metrics) 0))]
    (pos? support-count)))
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
(defn- decision-compatible-ids
  [candidate]
  (->> (concat (:coexists-with candidate)
               (:coexistsWith candidate)
               (:compatible-with candidate)
               (:compatibleWith candidate))
       (keep #(some-> % str not-empty))
       set))
(defn- compatible-decision-supports?
  [left right]
  (let [left-id (:id left)
        right-id (:id right)
        left-compatible (decision-compatible-ids (:candidate left))
        right-compatible (decision-compatible-ids (:candidate right))]
    (or (contains? left-compatible right-id)
        (contains? right-compatible left-id))))
(defn- dominates-decision?
  [left right]
  (and (not= (:id left) (:id right))
       (not (compatible-decision-supports? left right))
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

(defn- included-decision-choice-ids
  [decision]
  (->> (:choices decision)
       (filter #(= "include" (:status %)))
       (keep #(some-> (:id %) str not-empty))
       set))

(defn- included-decision-candidate-paths
  [decision-candidates decision]
  (let [included-ids (included-decision-choice-ids decision)]
    (->> decision-candidates
         (filter #(contains? included-ids (decision-candidate-id %)))
         (mapcat decision-candidate-paths)
         distinct
         vec)))

(defn- compact-output-decision-selected-files
  [files limit decision-candidates decision]
  (let [limit (when limit (long limit))]
    (when (and (pos? (long (or limit 0)))
               (seq decision-candidates)
               (seq (:choices decision)))
      (let [file-by-path (decision-file-by-path files)
            selection (reduce add-compact-output-row
                              {:rows []
                               :keys #{}}
                              (keep file-by-path
                                    (included-decision-candidate-paths
                                     decision-candidates
                                     decision)))]
        (when (seq (:rows selection))
          (->> (:rows selection)
               (take limit)
               renumber-file-ranks))))))
(defn context-packet->agent-result
  "Convert one Yggdrasil context packet into the benchmark agent-result contract.

  This is a deterministic agent-help baseline: it ranks files from the same
  docs/entities packet an agent would receive, without reading hidden ground
  truth or fix artifacts."
  ([packet]
   (context-packet->agent-result packet {}))
  ([packet {:keys [agent-id mode case-id caseFingerprint agentInputFingerprint root roots limit coverage decision-candidates decision-kind result-scope compact-result? compact-result-limit]}]
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
                                      [(repo-key-name repo-id)
                                       (scanned-path-kinds repo-root)]))
                               (into {}))
                          (scanned-path-kinds (or root
                                                  (single-root-map-root roots)))))
         doc-rows (keep-indexed #(doc-prediction root roots query-tokens %1 %2) (:docs packet))
         entity-rows (keep-indexed #(entity-prediction root roots query-tokens %1 %2) (:entities packet))
         retrieved-identities (retrieved-doc-identities (:docs packet))
         candidate-file-rows (->> (:candidateFiles packet)
                                  (map-indexed #(candidate-file-predictions
                                                 root
                                                 roots
                                                 query-tokens
                                                 retrieved-identities
                                                 %1
                                                 %2))
                                  (mapcat identity))
         local-importer-rows (local-importer-file-predictions root
                                                              roots
                                                              kind-by-path
                                                              query-tokens
                                                              (:candidateFiles
                                                               packet))
         decision-candidate-rows (decision-candidate-file-predictions root
                                                                      roots
                                                                      query-tokens
                                                                      decision-candidates)
         architecture-rows (architecture-file-rows root roots query-tokens packet)
         raw-candidate-files (ranked-file-predictions (concat doc-rows
                                                              entity-rows
                                                              candidate-file-rows
                                                              local-importer-rows
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
         effective-selection-limit (if (and compact-result?
                                            (inspection-files-scope? result-scope)
                                            compact-result-limit)
                                     compact-result-limit
                                     limit)
         selected-files (select-limited-suspected-files
                         candidate-files
                         effective-selection-limit
                         {:source-kinds source-kind-order
                          :kind-by-path kind-by-path
                          :result-scope result-scope})
         selection (cond-> {:rawCandidateFiles (count raw-candidate-files)
                            :candidateFiles (count candidate-files)
                            :coverageFilteredCandidateFiles filtered-files
                            :limit effective-selection-limit
                            :coverageSourceKinds (vec (sort source-kinds))}
                     (:candidateFileOnlyQuota selected-files)
                     (assoc :candidateFileOnlyQuota (:candidateFileOnlyQuota selected-files)
                            :candidateFileOnlySelected (:candidateFileOnlySelected selected-files)))
         selection (cond-> selection
                     (:unsaturatedDecisionTailPruned selected-files)
                     (assoc :unsaturatedDecisionTailPruned
                            (:unsaturatedDecisionTailPruned selected-files)
                            :unsaturatedDecisionTailScoreFloor
                            (:unsaturatedDecisionTailScoreFloor selected-files)))
         selection (cond-> selection
                     (:scoreElbowTailPruned selected-files)
                     (assoc :scoreElbowTailPruned
                            (:scoreElbowTailPruned selected-files)
                            :scoreElbowTailScoreFloor
                            (:scoreElbowTailScoreFloor selected-files)))
         selection (cond-> selection
                     (:inspectionIdentitySelected selected-files)
                     (assoc :inspectionIdentitySelected
                            (:inspectionIdentitySelected selected-files))
                     (:inspectionDirectFileSelected selected-files)
                     (assoc :inspectionDirectFileSelected
                            (:inspectionDirectFileSelected selected-files))
                     (:inspectionRepoCandidateSelected selected-files)
                     (assoc :inspectionRepoCandidateSelected
                            (:inspectionRepoCandidateSelected selected-files))
                     (:inspectionCandidateFillSkipped selected-files)
                     (assoc :inspectionCandidateFillSkipped
                            (:inspectionCandidateFillSkipped selected-files)))
         rich-suspected-files (:files selected-files)
         compact-output-limit (when compact-result?
                                (compact-result-output-limit rich-suspected-files
                                                             compact-result-limit
                                                             result-scope))
         decision (baseline-decision decision-kind
                                     decision-candidates
                                     rich-suspected-files)
         output-rich-suspected-files (if compact-output-limit
                                       (or (compact-output-decision-selected-files
                                            rich-suspected-files
                                            compact-output-limit
                                            decision-candidates
                                            decision)
                                           (compact-output-selected-files
                                            rich-suspected-files
                                            compact-output-limit
                                            result-scope
                                            source-kind-order
                                            kind-by-path))
                                       rich-suspected-files)
         suspected-files (if compact-result?
                           (compact-suspected-files output-rich-suspected-files)
                           output-rich-suspected-files)
         suspected-symbols (if compact-result?
                             []
                             (context-symbols packet))
         commands (cond-> (packet-commands packet)
                    compact-result? compact-result-commands)
         warnings (agent-result-warnings packet)
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
                                 :selection (cond-> selection
                                              compact-result?
                                              (assoc :compactResultSurface true)
                                              compact-output-limit
                                              (assoc :compactResultLimit
                                                     compact-output-limit
                                                     :compactResultFiles
                                                     (count suspected-files)))
                                 :summary summary}
                          caseFingerprint (assoc :caseFingerprint caseFingerprint)
                          agentInputFingerprint (assoc :agentInputFingerprint
                                                       agentInputFingerprint)
                          decision (assoc :decision decision))]
     (assoc result-surface
            :tokenUsage (result-surface-token-usage result-surface)))))
