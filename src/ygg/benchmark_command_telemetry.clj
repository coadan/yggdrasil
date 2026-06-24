(ns ygg.benchmark-command-telemetry
  (:require [clojure.string :as str]))

(def ^:private search-command-names
  #{"rg" "grep" "fd" "find"})
(def ^:private file-read-command-names
  #{"awk" "bat" "cat" "head" "less" "more" "nl" "sed" "tail"})
(defn- command-segments
  [command]
  (->> (str/split (str command) #"\s*(?:&&|\|\||;|\|)\s*")
       (map str/trim)
       (remove str/blank?)))
(defn- shell-tokens
  [segment]
  (let [length (count segment)]
    (loop [idx 0
           quote nil
           escaped? false
           token []
           tokens []]
      (if (= idx length)
        (cond-> tokens
          (seq token) (conj (apply str token)))
        (let [ch (.charAt ^String segment idx)]
          (cond
            escaped?
            (recur (inc idx) quote false (conj token ch) tokens)

            (= \\ ch)
            (recur (inc idx) quote true token tokens)

            (= quote ch)
            (recur (inc idx) nil false token tokens)

            quote
            (recur (inc idx) quote false (conj token ch) tokens)

            (or (= \' ch) (= \" ch))
            (recur (inc idx) ch false token tokens)

            (Character/isWhitespace ch)
            (recur (inc idx)
                   nil
                   false
                   []
                   (cond-> tokens
                     (seq token) (conj (apply str token))))

            :else
            (recur (inc idx) nil false (conj token ch) tokens)))))))

(defn- shell-assignment-token?
  [token]
  (boolean (re-matches #"[A-Za-z_][A-Za-z0-9_]*=.*" token)))
(defn- leading-command-tokens
  [segment]
  (let [tokens (shell-tokens (str/trim segment))
        tokens (if (= "env" (first tokens))
                 (rest tokens)
                 tokens)]
    (vec (drop-while shell-assignment-token? tokens))))

(def ^:private valued-options
  #{"-e" "--regexp" "-g" "--glob" "-t" "--type" "-T" "--type-not"
    "-m" "--max-count" "-A" "--after-context" "-B" "--before-context"
    "-C" "--context" "--color" "--colors" "--encoding" "--sort"
    "-f" "--file" "--iglob" "--type-add" "--type-clear"})

(defn- valued-option?
  [token]
  (contains? valued-options token))

(defn- inline-regexp-option?
  [token]
  (or (str/starts-with? token "--regexp=")
      (and (str/starts-with? token "-e")
           (< 2 (count token)))))

(defn- inline-valued-option?
  [token]
  (and (str/starts-with? token "--")
       (str/includes? token "=")))

(defn- comparable-path
  [path]
  (-> (str path)
      (str/replace "\\" "/")
      (str/replace #"^\./" "")))

(defn- root-search-path?
  [token]
  (#{"." "./" "/" ""} token))

(defn- glob-path?
  [token]
  (boolean (re-find #"[*?\[\]{}]" token)))

(defn- file-like-path?
  [token]
  (let [name (last (str/split (str token) #"/"))]
    (boolean (and (not (str/blank? name))
                  (str/includes? name ".")))))

(defn- consume-search-options
  [tokens]
  (loop [remaining tokens
         positional []
         explicit-pattern? false]
    (if-let [token (first remaining)]
      (cond
        (= "--" token)
        {:positional (vec (concat positional (rest remaining)))
         :explicit-pattern? explicit-pattern?}

        (#{"-e" "--regexp"} token)
        (recur (nnext remaining) positional true)

        (inline-regexp-option? token)
        (recur (next remaining) positional true)

        (valued-option? token)
        (recur (nnext remaining) positional explicit-pattern?)

        (inline-valued-option? token)
        (recur (next remaining) positional explicit-pattern?)

        (str/starts-with? token "-")
        (recur (next remaining) positional explicit-pattern?)

        :else
        (recur (next remaining)
               (conj positional token)
               explicit-pattern?))
      {:positional (vec positional)
       :explicit-pattern? explicit-pattern?})))

(defn- rg-like-paths
  [tokens]
  (let [{:keys [positional explicit-pattern?]} (consume-search-options tokens)]
    (if explicit-pattern?
      positional
      (vec (rest positional)))))

(defn- git-grep-paths
  [tokens]
  (let [pathspecs (->> (partition-by #{"--"} tokens)
                       rest
                       second
                       vec)]
    (if (seq pathspecs)
      pathspecs
      [])))

(defn- bounded-pathspec?
  [path]
  (and (not (root-search-path? path))
       (not (glob-path? path))))

(defn- exact-file-path?
  [candidate-paths path]
  (or (contains? candidate-paths (comparable-path path))
      (file-like-path? path)))

(defn- search-path-scope
  [candidate-paths paths]
  (cond
    (empty? paths) :broad
    (some root-search-path? paths) :broad
    (every? #(exact-file-path? candidate-paths %) paths) :exact-file
    (every? bounded-pathspec? paths) :scoped
    :else :broad))

(defn- literal-search-path-scope
  [candidate-paths paths]
  (cond
    (empty? paths) :broad
    (some root-search-path? paths) :broad
    (some glob-path? paths) :broad
    (every? #(exact-file-path? candidate-paths %) paths) :exact-file
    :else :broad))

(defn- segment-search-scope
  [candidate-paths segment]
  (let [[cmd arg & more] (leading-command-tokens segment)]
    (cond
      (nil? cmd) nil
      (and (= "git" cmd) (= "grep" arg))
      (search-path-scope candidate-paths (git-grep-paths more))
      (#{"rg" "grep"} cmd)
      (literal-search-path-scope candidate-paths (rg-like-paths (cons arg more)))
      (= "fd" cmd)
      (literal-search-path-scope candidate-paths (rg-like-paths (cons arg more)))
      (= "find" cmd)
      :broad
      :else nil)))

(defn- segment-command-kind
  [segment]
  (let [[cmd arg] (leading-command-tokens segment)]
    (cond
      (nil? cmd) nil
      (= "ygg" cmd) :ygg
      (and (= "bb" cmd) (#{"query" "view" "sync" "packages"} arg)) :ygg
      (and (= "git" cmd) (= "grep" arg)) :search
      (contains? search-command-names cmd) :search
      (contains? file-read-command-names cmd) :file-read
      :else :shell)))
(defn- command-kind
  [command]
  (let [kinds (keep segment-command-kind (command-segments command))]
    (cond
      (some #{:ygg} kinds) :ygg
      (some #{:search} kinds) :search
      (some #{:file-read} kinds) :file-read
      (seq kinds) :shell
      :else :shell)))

(defn- command-segment-kinds
  [command]
  (vec (keep segment-command-kind (command-segments command))))

(defn- command-search-scopes
  [candidate-paths command]
  (vec (keep #(segment-search-scope candidate-paths %)
             (command-segments command))))

(defn- command-search-scope
  [candidate-paths command]
  (let [scopes (command-search-scopes candidate-paths command)]
    (cond
      (some #{:broad} scopes) :broad
      (some #{:exact-file} scopes) :exact-file
      (some #{:scoped} scopes) :scoped
      :else nil)))

(defn- segment-telemetry-diff?
  [command-count counts segment-count segment-counts]
  (or (not= command-count segment-count)
      (not= (long (get counts :ygg 0))
            (long (get segment-counts :ygg 0)))
      (not= (long (get counts :search 0))
            (long (get segment-counts :search 0)))
      (not= (long (get counts :file-read 0))
            (long (get segment-counts :file-read 0)))
      (not= (long (get counts :shell 0))
            (long (get segment-counts :shell 0)))))

(defn command-telemetry
  ([commands]
   (command-telemetry commands {}))
  ([commands {:keys [candidate-paths]}]
   (let [kinds (map command-kind commands)
         counts (frequencies kinds)
         command-count (count commands)
         segment-kinds (mapcat command-segment-kinds commands)
         segment-count (count segment-kinds)
         segment-counts (frequencies segment-kinds)
         candidate-paths (set (map comparable-path (keep identity candidate-paths)))
         search-scopes (keep #(command-search-scope candidate-paths %) commands)
         search-scope-counts (frequencies search-scopes)
         segment-search-scopes (mapcat #(command-search-scopes candidate-paths %)
                                       commands)
         segment-search-scope-counts (frequencies segment-search-scopes)]
     (cond-> {:commandCount command-count
              :yggCommandCount (long (get counts :ygg 0))
              :searchCommandCount (long (get counts :search 0))
              :broadSearchCommandCount (long (get search-scope-counts :broad 0))
              :scopedSearchCommandCount (long (+ (get search-scope-counts :scoped 0)
                                                 (get search-scope-counts :exact-file 0)))
              :exactFileSearchCommandCount (long (get search-scope-counts :exact-file 0))
              :fileReadCommandCount (long (get counts :file-read 0))
              :shellCommandCount (long (get counts :shell 0))
              :commandless (zero? command-count)}
       (segment-telemetry-diff? command-count counts segment-count segment-counts)
       (assoc :segmentCount (long segment-count)
              :yggSegmentCount (long (get segment-counts :ygg 0))
              :searchSegmentCount (long (get segment-counts :search 0))
              :broadSearchSegmentCount (long (get segment-search-scope-counts :broad 0))
              :scopedSearchSegmentCount (long (+ (get segment-search-scope-counts :scoped 0)
                                                 (get segment-search-scope-counts :exact-file 0)))
              :exactFileSearchSegmentCount (long (get segment-search-scope-counts :exact-file 0))
              :fileReadSegmentCount (long (get segment-counts :file-read 0))
              :shellSegmentCount (long (get segment-counts :shell 0)))))))

(defn- aggregate-optional-segment-telemetry
  [summary telemetry]
  (if (some :segmentCount telemetry)
    (assoc summary
           :segmentCount (reduce + 0 (map #(long (or (:segmentCount %)
                                                     (:commandCount %)
                                                     0))
                                          telemetry))
           :yggSegmentCount (reduce + 0 (map #(long (or (:yggSegmentCount %)
                                                        (:yggCommandCount %)
                                                        0))
                                             telemetry))
           :searchSegmentCount (reduce + 0 (map #(long (or (:searchSegmentCount %)
                                                           (:searchCommandCount %)
                                                           0))
                                                telemetry))
           :broadSearchSegmentCount (reduce + 0
                                            (map #(long (or (:broadSearchSegmentCount %)
                                                            (:broadSearchCommandCount %)
                                                            0))
                                                 telemetry))
           :scopedSearchSegmentCount (reduce + 0
                                             (map #(long (or (:scopedSearchSegmentCount %)
                                                             (:scopedSearchCommandCount %)
                                                             0))
                                                  telemetry))
           :exactFileSearchSegmentCount (reduce + 0
                                                (map #(long (or (:exactFileSearchSegmentCount %)
                                                                (:exactFileSearchCommandCount %)
                                                                0))
                                                     telemetry))
           :fileReadSegmentCount (reduce + 0 (map #(long (or (:fileReadSegmentCount %)
                                                             (:fileReadCommandCount %)
                                                             0))
                                                  telemetry))
           :shellSegmentCount (reduce + 0 (map #(long (or (:shellSegmentCount %)
                                                          (:shellCommandCount %)
                                                          0))
                                               telemetry)))
    summary))

(defn- sum-telemetry-key
  [telemetry k]
  (reduce + 0 (map #(long (or (get % k) 0)) telemetry)))

(defn aggregate-command-telemetry
  [diagnostics]
  (let [telemetry (map :commandTelemetry diagnostics)]
    (aggregate-optional-segment-telemetry
     {:commandCount (sum-telemetry-key telemetry :commandCount)
      :yggCommandCount (sum-telemetry-key telemetry :yggCommandCount)
      :searchCommandCount (sum-telemetry-key telemetry :searchCommandCount)
      :broadSearchCommandCount (sum-telemetry-key telemetry :broadSearchCommandCount)
      :scopedSearchCommandCount (sum-telemetry-key telemetry :scopedSearchCommandCount)
      :exactFileSearchCommandCount (sum-telemetry-key telemetry :exactFileSearchCommandCount)
      :fileReadCommandCount (sum-telemetry-key telemetry :fileReadCommandCount)
      :shellCommandCount (sum-telemetry-key telemetry :shellCommandCount)}
     telemetry)))

(defn- long-or-zero
  [value]
  (long (or value 0)))

(defn- double-or-zero
  [value]
  (double (or value 0)))

(defn aggregate-token-telemetry
  "Sum token/cost telemetry across per-result diagnostics.
  Returns nil when no diagnostic has :tokenUsage, so callers can omit
  :tokenTelemetry entirely when the data is absent."
  [diagnostics]
  (let [usages (keep :tokenUsage diagnostics)]
    (when (seq usages)
      {:inputTokens (reduce + 0 (map #(long-or-zero (:inputTokens %)) usages))
       :outputTokens (reduce + 0 (map #(long-or-zero (:outputTokens %)) usages))
       :totalTokens (reduce + 0 (map #(long-or-zero (:totalTokens %)) usages))
       :costUsd (reduce + 0.0 (map #(double-or-zero (:costUsd %)) usages))})))
