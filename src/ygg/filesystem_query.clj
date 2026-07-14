(ns ygg.filesystem-query
  "Degraded, graph-independent query retrieval over repository files.

  This lane is a bounded availability fallback. It emits transient mechanical
  evidence and never writes graph or query-run facts."
  (:require [ygg.ripgrep :as ripgrep]
            [clojure.string :as str])
  (:import [java.util.concurrent Callable ExecutorService Executors Future]))

(def schema
  "ygg.query/v2")

(def degradation-schema
  "ygg.context.degradation/v1")

(def default-limit
  10)

(def default-pattern-limit
  6)

(def default-timeout-ms
  1500)

(def default-max-stdout-bytes
  200000)

(def default-max-parallel-repos
  4)

(def ^:private ignored-directories
  [".git" ".dev" ".ygg" ".cpcache" ".clj-kondo" "target"
   "node_modules" ".shadow-cljs" ".calva" ".idea" "ygg-out"])

(def ^:private ignore-globs
  (vec (mapcat (fn [dir]
                 [(str dir "/**")
                  (str "**/" dir "/**")])
               ignored-directories)))

(defn- now-ns
  []
  (System/nanoTime))

(defn- elapsed-ms
  [started-ns]
  (long (/ (- (now-ns) started-ns) 1000000)))

(defn- normalize-pattern
  [value]
  (some-> value str str/trim not-empty))

(defn- alnum-count
  [value]
  (count (re-seq #"[A-Za-z0-9]" (str value))))

(defn- shaped-pattern?
  [value]
  (boolean (re-find #"[._/:-]" (str value))))

(defn- query-token-candidates
  [query-text]
  (->> (str/split (str query-text) #"[^A-Za-z0-9_./:+?!<>=-]+")
       (keep-indexed (fn [index value]
                       (when-let [pattern (normalize-pattern value)]
                         (when (<= 3 (alnum-count pattern))
                           {:index index
                            :pattern pattern}))))
       (sort-by (fn [{:keys [index pattern]}]
                  [(if (shaped-pattern? pattern) 0 1)
                   (- (alnum-count pattern))
                   index
                   (str/lower-case pattern)]))))

(defn query-patterns
  "Return bounded fixed-string patterns from explicit inputs and query shape.

  Selection uses only explicit values, character shape, length, and position."
  [query-text {:keys [literals symbols pattern-limit]
               :or {pattern-limit default-pattern-limit}}]
  (let [explicit (keep normalize-pattern (concat literals symbols))
        candidates (concat explicit (map :pattern (query-token-candidates query-text)))
        patterns (->> candidates
                      (reduce (fn [{:keys [seen] :as state} pattern]
                                (let [key (str/lower-case pattern)]
                                  (if (contains? seen key)
                                    state
                                    (-> state
                                        (update :seen conj key)
                                        (update :patterns conj pattern)))))
                              {:seen #{}
                               :patterns []})
                      :patterns
                      (take (max 1 (long pattern-limit)))
                      vec)]
    (if (seq patterns)
      patterns
      [(str/trim (str query-text))])))

(defn- selected-repos
  [project repo-id]
  (->> (:repos project)
       (filter (fn [repo]
                 (or (str/blank? (str repo-id))
                     (= (str repo-id) (str (:id repo))))))
       (filter (fn [{:keys [id root]}]
                 (and (not (str/blank? (str id)))
                      (not (str/blank? (str root))))))
       (sort-by (comp str :id))
       vec))

(defn- search-repo
  [{:keys [id root]} patterns opts]
  (assoc (ripgrep/search-counts-many
          root
          patterns
          []
          {:timeout-ms (long (or (:timeout-ms opts) default-timeout-ms))
           :max-stdout-bytes (long (or (:max-stdout-bytes opts)
                                       default-max-stdout-bytes))
           :max-stderr-bytes 20000
           :hidden? true
           :ignore-case? true
           :ignore-globs ignore-globs})
         :repo-id (str id)))

(defn- search-repos
  [repos patterns opts]
  (if (< (count repos) 2)
    (mapv #(search-repo % patterns opts) repos)
    (let [parallelism (min (count repos)
                           (max 1 (long (or (:max-parallel-repos opts)
                                            default-max-parallel-repos))))
          ^ExecutorService executor (Executors/newFixedThreadPool parallelism)
          tasks (mapv (fn [repo]
                        (reify Callable
                          (call [_]
                            (search-repo repo patterns opts))))
                      repos)]
      (try
        (->> (.invokeAll executor tasks)
             (mapv #(.get ^Future %)))
        (finally
          (.shutdownNow executor))))))

(defn- diagnostic-kind-counts
  [searches]
  (->> searches
       (mapcat :diagnostics)
       (keep :kind)
       frequencies
       (into (sorted-map))))

(defn- path-pattern-count
  [patterns path]
  (let [path (str/lower-case (str path))]
    (count (filter #(str/includes? path (str/lower-case (str %))) patterns))))

(defn- ranked-matches
  [searches patterns limit]
  (let [matches (->> searches
                     (mapcat (fn [{:keys [repo-id matches]}]
                               (map #(assoc % :repo-id repo-id) matches)))
                     (sort-by (fn [{:keys [repo-id path count]}]
                                [(- (path-pattern-count patterns path))
                                 (- (long (or count 0)))
                                 (clojure.core/count (str path))
                                 (str repo-id)
                                 (str path)]))
                     (take limit)
                     vec)
        max-count (reduce max 1 (map #(long (or (:count %) 0)) matches))
        scores (mapv (fn [{:keys [path count]}]
                       (+ (path-pattern-count patterns path)
                          (/ (double (or count 0)) max-count)))
                     matches)
        max-score (reduce max 1.0 scores)]
    (mapv (fn [index {:keys [repo-id path count]} score]
            {:repo-id repo-id
             :path path
             :rank (inc index)
             :score (/ score max-score)
             :count (long (or count 0))})
          (range)
          matches
          scores)))

(defn- result-row
  [path-id {:keys [repo-id path rank score count]}]
  {:path path-id
   :resolvedPath path
   :repo repo-id
   :rank rank
   :score score
   :kind :file
   :why [:grep]
   :reason (str "filesystem fixed-string match (" count
                (if (= 1 count) " match)" " matches)"))})

(defn- evidence-row
  [{:keys [path resolvedPath repo]}]
  {:kind :candidate
   :path path
   :resolvedPath resolvedPath
   :repo repo
   :why [:grep]})

(defn- degradation
  [{:keys [reason message operation]}]
  (cond-> {:schema degradation-schema
           :status :degraded
           :reason (or reason :enrichment-unavailable)
           :fallback :filesystem
           :message message}
    (seq operation)
    (assoc :operation operation)))

(defn search-project
  "Search project repository roots without reading or writing graph state.

  Returns an internal `:rows` collection for plain rendering and a canonical
  degraded `:packet` for JSON consumers."
  [project query-text {:keys [repo-id retriever query-input limit reason message operation]
                       :as opts}]
  (let [started (now-ns)
        patterns (query-patterns query-text opts)
        repos (selected-repos project repo-id)
        searches (search-repos repos patterns opts)
        rows (ranked-matches searches patterns (long (or limit default-limit)))
        path-rows (map-indexed (fn [index row]
                                 [(str "p" (inc index)) row])
                               rows)
        paths (into (sorted-map)
                    (map (fn [[path-id {:keys [path]}]]
                           [path-id path]))
                    path-rows)
        results (mapv (fn [[path-id row]]
                        (result-row path-id row))
                      path-rows)
        diagnostic-counts (diagnostic-kind-counts searches)
        file-count (reduce + 0 (map :file-count searches))
        match-count (reduce + 0 (map :match-count searches))
        warning (or message
                    "Graph enrichment is unavailable; results use bounded filesystem search.")
        packet {:schema schema
                :query query-text
                :input (or query-input {})
                :basis {:status :limited
                        :degraded true}
                :paths paths
                :lanes {:requested (or retriever :auto)
                        :mode :filesystem
                        :used (if (seq results) [:grep] [])}
                :retrieval {:requested (or retriever :auto)
                            :effective :filesystem
                            :fallback? true
                            :reason (or reason :enrichment-unavailable)}
                :results results
                :evidence (mapv evidence-row results)
                :warnings [warning]
                :degradation (degradation {:reason reason
                                           :message warning
                                           :operation operation})
                :search {:instrumentation
                         {:filesystem-processes (count searches)
                          :filesystem-search-ms (reduce + 0 (map :elapsed-ms searches))
                          :filesystem-slowest-repo-ms (reduce max 0 (map :elapsed-ms searches))
                          :filesystem-total-ms (elapsed-ms started)
                          :filesystem-patterns patterns
                          :filesystem-match-count match-count
                          :filesystem-file-count file-count
                          :filesystem-returned-count (count results)
                          :filesystem-diagnostic-kinds diagnostic-counts
                          :filesystem-timeout-ms (long (or (:timeout-ms opts)
                                                           default-timeout-ms))}}}]
    {:rows rows
     :packet packet}))
