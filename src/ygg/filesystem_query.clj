(ns ygg.filesystem-query
  "Degraded, graph-independent query retrieval over repository files.

  This lane is a bounded availability fallback. It emits transient mechanical
  evidence and never writes graph or query-run facts."
  (:require [ygg.ripgrep :as ripgrep]
            [clojure.string :as str])
  (:import [java.util.concurrent Callable CancellationException ExecutionException
            ExecutorService Executors Future TimeUnit]))

(def schema
  "ygg.query/v2")

(def degradation-schema
  "ygg.context.degradation/v1")

(def default-limit
  10)

(def default-pattern-limit
  6)

(def default-query-scan-character-limit
  4096)

(def default-pattern-character-limit
  1024)

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

(def ^:private incomplete-diagnostic-kinds
  #{:invalid-count-line
    :project-timeout
    :pattern-too-long
    :query-truncated
    :ripgrep-error
    :search-error
    :stdout-truncated
    :timeout
    :unavailable})

(def ^:private incomplete-warning
  "Filesystem fallback reached a search bound or tool failure; results may be incomplete.")

(defn- now-ns
  []
  (System/nanoTime))

(defn- elapsed-ms
  [started-ns]
  (long (/ (- (now-ns) started-ns) 1000000)))

(defn- env-long
  [name fallback]
  (if-let [raw (some-> (System/getenv name) str/trim not-empty)]
    (try
      (Long/parseLong raw)
      (catch NumberFormatException _
        fallback))
    fallback))

(defn- effective-search-opts
  [opts]
  (assoc opts
         :rg-bin (or (:rg-bin opts)
                     (some-> (System/getenv "YGG_RG_BIN") str/trim not-empty)
                     ripgrep/default-bin)
         :timeout-ms (max 0 (long (or (:timeout-ms opts)
                                      (env-long "YGG_FILESYSTEM_QUERY_TIMEOUT_MS"
                                                default-timeout-ms))))
         :max-stdout-bytes
         (max 0 (long (or (:max-stdout-bytes opts)
                          (env-long "YGG_FILESYSTEM_QUERY_MAX_STDOUT_BYTES"
                                    default-max-stdout-bytes))))))

(defn- normalize-pattern
  [value]
  (some-> value str str/trim not-empty))

(defn- alnum-count
  [value]
  (count (re-seq #"[A-Za-z0-9]" (str value))))

(defn- shaped-pattern?
  [value]
  (boolean (re-find #"[._/:-]" (str value))))

(def ^:private token-characters
  (set "_./:+?!<>=-"))

(defn- token-character?
  [character]
  (or (<= (int \A) (int character) (int \Z))
      (<= (int \a) (int character) (int \z))
      (<= (int \0) (int character) (int \9))
      (contains? token-characters character)))

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

(defn- pattern-within-limit?
  [pattern]
  (<= (count pattern) default-pattern-character-limit))

(defn query-pattern-selection
  "Return bounded fixed-string patterns plus mechanical truncation diagnostics."
  [query-text {:keys [literals symbols pattern-limit]
               :or {pattern-limit default-pattern-limit}}]
  (let [query-text (str query-text)
        scan-limit default-query-scan-character-limit
        scanned-query (subs query-text 0 (min scan-limit (count query-text)))
        query-truncated? (< (count scanned-query) (count query-text))
        query-cut-token? (and query-truncated?
                              (seq scanned-query)
                              (token-character? (nth scanned-query
                                                     (dec (count scanned-query))))
                              (token-character? (nth query-text
                                                     (count scanned-query))))
        candidate-query (if query-cut-token?
                          (str/replace scanned-query
                                       #"[A-Za-z0-9_./:+?!<>=-]+$"
                                       "")
                          scanned-query)
        explicit (vec (keep normalize-pattern (concat literals symbols)))
        token-candidates (vec (query-token-candidates candidate-query))
        oversized-pattern-count (count (remove pattern-within-limit?
                                               (concat explicit
                                                       (map :pattern token-candidates))))
        candidates (concat (filter pattern-within-limit? explicit)
                           (->> token-candidates
                                (map :pattern)
                                (filter pattern-within-limit?)))
        effective-pattern-limit (-> pattern-limit
                                    long
                                    (max 1)
                                    (min default-pattern-limit))
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
                      (take effective-pattern-limit)
                      vec)
        fallback (some-> scanned-query normalize-pattern)
        fallback-truncated? (and (empty? patterns)
                                 (< default-pattern-character-limit
                                    (count (or fallback ""))))
        patterns (if (seq patterns)
                   patterns
                   [(subs (or fallback "")
                          0
                          (min default-pattern-character-limit
                               (count (or fallback ""))))])
        dropped-pattern-count (+ oversized-pattern-count
                                 (if fallback-truncated? 1 0))
        diagnostics (cond-> []
                      query-truncated?
                      (conj {:kind :query-truncated
                             :limitCharacters scan-limit})

                      (pos? dropped-pattern-count)
                      (conj {:kind :pattern-too-long
                             :count dropped-pattern-count
                             :maxCharacters default-pattern-character-limit}))]
    {:query scanned-query
     :patterns patterns
     :diagnostics diagnostics
     :query-truncated? query-truncated?
     :dropped-pattern-count dropped-pattern-count}))

(defn query-patterns
  "Return bounded fixed-string patterns from explicit inputs and query shape.

  Selection uses only explicit values, character shape, length, and position."
  [query-text opts]
  (:patterns (query-pattern-selection query-text opts)))

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
  (let [search (ripgrep/search-counts-many
                root
                patterns
                []
                {:bin (:rg-bin opts)
                 :timeout-ms (:timeout-ms opts)
                 :max-stdout-bytes (:max-stdout-bytes opts)
                 :max-stderr-bytes 20000
                 :hidden? true
                 :ignore-case? true
                 :ignore-globs ignore-globs})]
    (assoc search
           :repo-id (str id)
           :process-attempted? (not-any? #(= :unavailable (:kind %))
                                         (:diagnostics search)))))

(defn- empty-search
  [repo kind process-attempted? elapsed-ms message]
  {:repo-id (str (:id repo))
   :elapsed-ms elapsed-ms
   :matches []
   :match-count 0
   :file-count 0
   :process-attempted? process-attempted?
   :diagnostics [(cond-> {:kind kind}
                   (seq message) (assoc :message message))]})

(defn- task-elapsed-ms
  [{:keys [search-started-ns]}]
  (if-let [started @search-started-ns]
    (elapsed-ms started)
    0))

(defn- future->search
  [{:keys [repo process-attempted?] :as task} ^Future future]
  (if (.isCancelled future)
    (empty-search repo
                  :project-timeout
                  @process-attempted?
                  (task-elapsed-ms task)
                  nil)
    (try
      (.get future)
      (catch CancellationException _
        (empty-search repo
                      :project-timeout
                      @process-attempted?
                      (task-elapsed-ms task)
                      nil))
      (catch ExecutionException e
        (empty-search repo
                      :search-error
                      @process-attempted?
                      (task-elapsed-ms task)
                      (some-> e .getCause .getMessage))))))

(defn- search-repos
  [repos patterns opts]
  (if (< (count repos) 2)
    (mapv #(search-repo % patterns opts) repos)
    (let [parallelism (min (count repos)
                           (max 1 (long (or (:max-parallel-repos opts)
                                            default-max-parallel-repos))))
          timeout-ms (max 0 (long (or (:timeout-ms opts) default-timeout-ms)))
          ^ExecutorService executor (Executors/newFixedThreadPool parallelism)
          tasks (mapv (fn [repo]
                        (let [process-attempted? (atom false)
                              search-started-ns (atom nil)]
                          {:repo repo
                           :process-attempted? process-attempted?
                           :search-started-ns search-started-ns
                           :callable
                           (reify Callable
                             (call [_]
                               (reset! search-started-ns (now-ns))
                               (reset! process-attempted? true)
                               (search-repo repo patterns opts)))}))
                      repos)
          callables (mapv :callable tasks)]
      (try
        (let [futures (.invokeAll executor
                                  ^java.util.Collection callables
                                  timeout-ms
                                  TimeUnit/MILLISECONDS)]
          (mapv future->search tasks futures))
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))
          (mapv (fn [{:keys [repo process-attempted?] :as task}]
                  (empty-search repo
                                :project-timeout
                                @process-attempted?
                                (task-elapsed-ms task)
                                "Filesystem fallback was interrupted."))
                tasks))
        (finally
          (.shutdownNow executor))))))

(defn- diagnostic-kind-counts
  [searches diagnostics]
  (->> (concat diagnostics (mapcat :diagnostics searches))
       (keep :kind)
       frequencies
       (into (sorted-map))))

(defn- incomplete-search?
  [diagnostic-counts]
  (boolean (some incomplete-diagnostic-kinds (keys diagnostic-counts))))

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
  (let [opts (effective-search-opts opts)
        started (now-ns)
        pattern-selection (query-pattern-selection query-text opts)
        patterns (:patterns pattern-selection)
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
        diagnostic-counts (diagnostic-kind-counts searches
                                                  (:diagnostics pattern-selection))
        incomplete? (incomplete-search? diagnostic-counts)
        file-count (reduce + 0 (map :file-count searches))
        match-count (reduce + 0 (map :match-count searches))
        warning (or message
                    "Graph enrichment is unavailable; results use bounded filesystem search.")
        warnings (cond-> [warning]
                   incomplete? (conj incomplete-warning))
        packet {:schema schema
                :query (:query pattern-selection)
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
                :warnings warnings
                :degradation (degradation {:reason reason
                                           :message warning
                                           :operation operation})
                :search {:instrumentation
                         {:filesystem-processes (count (filter :process-attempted? searches))
                          :filesystem-repos (count searches)
                          :filesystem-search-ms (reduce + 0 (map :elapsed-ms searches))
                          :filesystem-slowest-repo-ms (reduce max 0 (map :elapsed-ms searches))
                          :filesystem-total-ms (elapsed-ms started)
                          :filesystem-patterns patterns
                          :filesystem-query-scan-limit-characters
                          default-query-scan-character-limit
                          :filesystem-query-truncated?
                          (:query-truncated? pattern-selection)
                          :filesystem-pattern-character-limit
                          default-pattern-character-limit
                          :filesystem-dropped-pattern-count
                          (:dropped-pattern-count pattern-selection)
                          :filesystem-match-count match-count
                          :filesystem-file-count file-count
                          :filesystem-returned-count (count results)
                          :filesystem-diagnostic-kinds diagnostic-counts
                          :filesystem-incomplete? incomplete?
                          :filesystem-timeout-ms (:timeout-ms opts)}}}]
    {:rows rows
     :packet packet}))
