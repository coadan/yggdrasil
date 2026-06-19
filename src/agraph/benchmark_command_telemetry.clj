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
      (and (= "bb" cmd) (#{"ask" "explore" "view" "sync"} arg)) :agraph
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
(defn command-telemetry
  [commands]
  (let [kinds (map command-kind commands)
        counts (frequencies kinds)
        command-count (count commands)]
    {:commandCount command-count
     :agraphCommandCount (long (get counts :agraph 0))
     :searchCommandCount (long (get counts :search 0))
     :fileReadCommandCount (long (get counts :file-read 0))
     :shellCommandCount (long (get counts :shell 0))
     :commandless (zero? command-count)}))
(defn aggregate-command-telemetry
  [diagnostics]
  (let [telemetry (map :commandTelemetry diagnostics)]
    {:commandCount (reduce + 0 (map :commandCount telemetry))
     :agraphCommandCount (reduce + 0 (map :agraphCommandCount telemetry))
     :searchCommandCount (reduce + 0 (map :searchCommandCount telemetry))
     :fileReadCommandCount (reduce + 0 (map :fileReadCommandCount telemetry))
     :shellCommandCount (reduce + 0 (map :shellCommandCount telemetry))}))
