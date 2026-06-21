(ns agraph.benchmark-command-telemetry
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
(defn- shell-assignment-token?
  [token]
  (boolean (re-matches #"[A-Za-z_][A-Za-z0-9_]*=.*" token)))
(defn- leading-command-tokens
  [segment]
  (let [tokens (str/split (str/trim segment) #"\s+")
        tokens (if (= "env" (first tokens))
                 (rest tokens)
                 tokens)]
    (vec (drop-while shell-assignment-token? tokens))))
(defn- segment-command-kind
  [segment]
  (let [[cmd arg] (leading-command-tokens segment)]
    (cond
      (nil? cmd) nil
      (= "agraph" cmd) :agraph
      (and (= "bb" cmd) (#{"ask" "explore" "view" "sync" "packages"} arg)) :agraph
      (and (= "git" cmd) (= "grep" arg)) :search
      (contains? search-command-names cmd) :search
      (contains? file-read-command-names cmd) :file-read
      :else :shell)))
(defn- command-kind
  [command]
  (let [kinds (keep segment-command-kind (command-segments command))]
    (cond
      (some #{:agraph} kinds) :agraph
      (some #{:search} kinds) :search
      (some #{:file-read} kinds) :file-read
      (seq kinds) :shell
      :else :shell)))

(defn- command-segment-kinds
  [command]
  (vec (keep segment-command-kind (command-segments command))))

(defn- segment-telemetry-diff?
  [command-count counts segment-count segment-counts]
  (or (not= command-count segment-count)
      (not= (long (get counts :agraph 0))
            (long (get segment-counts :agraph 0)))
      (not= (long (get counts :search 0))
            (long (get segment-counts :search 0)))
      (not= (long (get counts :file-read 0))
            (long (get segment-counts :file-read 0)))
      (not= (long (get counts :shell 0))
            (long (get segment-counts :shell 0)))))

(defn command-telemetry
  [commands]
  (let [kinds (map command-kind commands)
        counts (frequencies kinds)
        command-count (count commands)
        segment-kinds (mapcat command-segment-kinds commands)
        segment-count (count segment-kinds)
        segment-counts (frequencies segment-kinds)]
    (cond-> {:commandCount command-count
             :agraphCommandCount (long (get counts :agraph 0))
             :searchCommandCount (long (get counts :search 0))
             :fileReadCommandCount (long (get counts :file-read 0))
             :shellCommandCount (long (get counts :shell 0))
             :commandless (zero? command-count)}
      (segment-telemetry-diff? command-count counts segment-count segment-counts)
      (assoc :segmentCount (long segment-count)
             :agraphSegmentCount (long (get segment-counts :agraph 0))
             :searchSegmentCount (long (get segment-counts :search 0))
             :fileReadSegmentCount (long (get segment-counts :file-read 0))
             :shellSegmentCount (long (get segment-counts :shell 0))))))

(defn- aggregate-optional-segment-telemetry
  [summary telemetry]
  (if (some :segmentCount telemetry)
    (assoc summary
           :segmentCount (reduce + 0 (map #(long (or (:segmentCount %)
                                                     (:commandCount %)
                                                     0))
                                          telemetry))
           :agraphSegmentCount (reduce + 0 (map #(long (or (:agraphSegmentCount %)
                                                           (:agraphCommandCount %)
                                                           0))
                                                telemetry))
           :searchSegmentCount (reduce + 0 (map #(long (or (:searchSegmentCount %)
                                                           (:searchCommandCount %)
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

(defn aggregate-command-telemetry
  [diagnostics]
  (let [telemetry (map :commandTelemetry diagnostics)]
    (aggregate-optional-segment-telemetry
     {:commandCount (reduce + 0 (map :commandCount telemetry))
      :agraphCommandCount (reduce + 0 (map :agraphCommandCount telemetry))
      :searchCommandCount (reduce + 0 (map :searchCommandCount telemetry))
      :fileReadCommandCount (reduce + 0 (map :fileReadCommandCount telemetry))
      :shellCommandCount (reduce + 0 (map :shellCommandCount telemetry))}
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
