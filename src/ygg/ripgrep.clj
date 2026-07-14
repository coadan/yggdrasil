(ns ygg.ripgrep
  "Bounded ripgrep process boundary.

  This namespace returns transient query evidence only. It does not classify
  project semantics or produce durable graph facts."
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream IOException InputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Path]
           [java.util.concurrent TimeUnit]))

(def default-bin "rg")
(def default-timeout-ms 5000)
(def default-max-stdout-bytes 1000000)
(def default-max-stderr-bytes 100000)

(defn- now-ns
  []
  (System/nanoTime))

(defn- elapsed-ms
  [started-ns]
  (long (/ (- (now-ns) started-ns) 1000000)))

(defn- root-file
  [repo-root]
  (io/file repo-root))

(defn- normalize-separator
  [path]
  (str/replace (str path) "\\" "/"))

(defn- normalize-relative-prefix
  [path]
  (str/replace (str path) #"^\./" ""))

(defn- repo-relative-path
  [repo-root path]
  (let [text (normalize-separator path)
        file (io/file text)]
    (if (.isAbsolute file)
      (try
        (let [root-path (.normalize (.toAbsolutePath (.toPath (root-file repo-root))))
              path-value (.normalize (.toAbsolutePath (.toPath file)))]
          (if (.startsWith ^Path path-value root-path)
            (normalize-relative-prefix
             (normalize-separator (str (.relativize root-path path-value))))
            text))
        (catch Exception _
          text))
      (normalize-relative-prefix text))))

(defn- stream->bounded-string
  [^InputStream stream max-bytes]
  (let [buffer (byte-array 8192)
        out (ByteArrayOutputStream.)
        max-bytes (long max-bytes)]
    (loop [truncated? false]
      (let [n (.read stream buffer)]
        (if (neg? n)
          {:text (String. (.toByteArray out) StandardCharsets/UTF_8)
           :truncated? truncated?}
          (let [available (max 0 (- max-bytes (.size out)))
                keep-bytes (min n available)]
            (when (pos? keep-bytes)
              (.write out buffer 0 keep-bytes))
            (recur (or truncated? (< keep-bytes n)))))))))

(defn- read-stream-async
  [stream max-bytes]
  (future
    (stream->bounded-string stream max-bytes)))

(defn- process-env!
  [^ProcessBuilder builder env]
  (doseq [[k v] env]
    (.put (.environment builder) (str k) (str v))))

(defn- terminate-process!
  [^Process process]
  (.destroyForcibly process)
  (.waitFor process 1000 TimeUnit/MILLISECONDS))

(defn- run-argv
  [repo-root argv {:keys [timeout-ms max-stdout-bytes max-stderr-bytes env]
                   :or {timeout-ms default-timeout-ms
                        max-stdout-bytes default-max-stdout-bytes
                        max-stderr-bytes default-max-stderr-bytes
                        env {}}}]
  (let [started (now-ns)]
    (try
      (let [builder (ProcessBuilder. ^java.util.List (vec argv))]
        (.directory builder (root-file repo-root))
        (process-env! builder env)
        (let [process (.start builder)
              _ (.close (.getOutputStream process))
              out-future (read-stream-async (.getInputStream process) max-stdout-bytes)
              err-future (read-stream-async (.getErrorStream process) max-stderr-bytes)]
          (try
            (if (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
              (let [out @out-future
                    err @err-future]
                {:argv (vec argv)
                 :exit (.exitValue process)
                 :elapsed-ms (elapsed-ms started)
                 :stdout (:text out)
                 :stderr (:text err)
                 :stdout-truncated? (:truncated? out)
                 :stderr-truncated? (:truncated? err)})
              (do
                (terminate-process! process)
                {:argv (vec argv)
                 :exit nil
                 :elapsed-ms (elapsed-ms started)
                 :stdout ""
                 :stderr ""
                 :timeout? true
                 :stdout-truncated? false
                 :stderr-truncated? false}))
            (catch InterruptedException _
              (try
                (terminate-process! process)
                (finally
                  (.interrupt (Thread/currentThread))))
              {:argv (vec argv)
               :exit nil
               :elapsed-ms (elapsed-ms started)
               :stdout ""
               :stderr ""
               :timeout? true
               :stdout-truncated? false
               :stderr-truncated? false}))))
      (catch IOException e
        {:argv (vec argv)
         :exit nil
         :elapsed-ms (elapsed-ms started)
         :stdout ""
         :stderr (.getMessage e)
         :unavailable? true
         :stdout-truncated? false
         :stderr-truncated? false}))))

(defn- diagnostic-rows
  [{:keys [exit stderr timeout? unavailable? stdout-truncated? stderr-truncated?]}]
  (cond-> []
    timeout?
    (conj {:kind :timeout})

    unavailable?
    (conj {:kind :unavailable
           :message stderr})

    (= 2 exit)
    (conj {:kind :ripgrep-error
           :message (str/trim stderr)})

    (and (seq (str/trim stderr))
         (not= 2 exit)
         (not unavailable?))
    (conj {:kind :stderr
           :message (str/trim stderr)})

    stdout-truncated?
    (conj {:kind :stdout-truncated})

    stderr-truncated?
    (conj {:kind :stderr-truncated})))

(defn available?
  "Return true when the ripgrep executable is available."
  ([] (available? {}))
  ([{:keys [bin repo-root timeout-ms] :or {bin default-bin
                                           repo-root "."
                                           timeout-ms 1000}}]
   (let [result (run-argv repo-root [bin "--version"] {:timeout-ms timeout-ms
                                                       :max-stdout-bytes 2000
                                                       :max-stderr-bytes 2000})]
     (and (not (:timeout? result))
          (not (:unavailable? result))
          (zero? (:exit result))))))

(defn- glob-args
  [ignore-globs]
  (mapcat (fn [glob] ["--glob" (str "!" glob)]) ignore-globs))

(defn- file-argv
  [{:keys [bin hidden? ignore-globs]
    :or {bin default-bin
         hidden? true
         ignore-globs []}}]
  (vec (concat [bin "--files"]
               (when hidden? ["--hidden"])
               (glob-args ignore-globs))))

(defn files
  "Return candidate repo-relative file paths from `rg --files`.

  Yggdrasil callers still own final filtering, file kind detection, and durable
  file records."
  ([repo-root] (files repo-root {}))
  ([repo-root opts]
   (let [argv (file-argv opts)
         result (run-argv repo-root argv opts)
         paths (if (#{0 1} (:exit result))
                 (->> (str/split-lines (:stdout result))
                      (remove str/blank?)
                      (mapv #(repo-relative-path repo-root %)))
                 [])]
     {:argv (:argv result)
      :exit (:exit result)
      :elapsed-ms (:elapsed-ms result)
      :paths paths
      :path-count (count paths)
      :truncated? (:stdout-truncated? result)
      :diagnostics (diagnostic-rows result)})))

(defn- search-argv
  [pattern paths {:keys [bin hidden? ignore-case? ignore-globs]
                  :or {bin default-bin
                       hidden? true
                       ignore-globs []}}]
  (let [paths (if (seq paths) paths ["."])]
    (vec (concat [bin "--json" "-n" "--line-number" "--column" "--fixed-strings"]
                 (when hidden? ["--hidden"])
                 (glob-args ignore-globs)
                 (when ignore-case? ["--ignore-case"])
                 [pattern]
                 (when (seq paths)
                   (cons "--" paths))))))

(defn- pattern-args
  [patterns]
  (mapcat (fn [pattern] ["-e" (str pattern)]) patterns))

(defn- count-argv
  [patterns paths {:keys [bin hidden? ignore-case? ignore-globs]
                   :or {bin default-bin
                        hidden? true
                        ignore-globs []}}]
  (let [paths (if (seq paths) paths ["."])]
    (vec (concat [bin "--count-matches" "-H" "--fixed-strings"]
                 (when hidden? ["--hidden"])
                 (glob-args ignore-globs)
                 (when ignore-case? ["--ignore-case"])
                 (pattern-args patterns)
                 (when (seq paths)
                   (cons "--" paths))))))

(defn- path-text
  [row]
  (or (get-in row [:data :path :text])
      (get-in row [:data :path :bytes])
      ""))

(defn- submatch->row
  [submatch]
  {:text (get-in submatch [:match :text])
   :start (:start submatch)
   :end (:end submatch)})

(defn- match-row
  [repo-root row]
  (let [data (:data row)]
    {:path (repo-relative-path repo-root (path-text row))
     :line (:line_number data)
     :absolute-offset (:absolute_offset data)
     :text (get-in data [:lines :text])
     :submatches (mapv submatch->row (:submatches data))}))

(defn- parse-json-line
  [line]
  (json/read-json line :key-fn keyword))

(defn- parse-search-output
  [repo-root stdout]
  (reduce
   (fn [state line]
     (if (str/blank? line)
       state
       (try
         (let [row (parse-json-line line)]
           (case (:type row)
             "match" (update state :matches conj (match-row repo-root row))
             "summary" (assoc state :summary (:data row))
             state))
         (catch Exception e
           (-> state
               (assoc :invalid-json? true)
               (update :diagnostics conj {:kind :invalid-json
                                          :message (.getMessage e)}))))))
   {:matches []
    :summary nil
    :diagnostics []
    :invalid-json? false}
   (str/split-lines stdout)))

(defn- parse-long-safe
  [value]
  (try
    (Long/parseLong (str value))
    (catch Exception _
      nil)))

(defn- parse-count-line
  [repo-root line]
  (when-let [[_ path count-text] (re-matches #"(?s)^(.+):([0-9]+)$" line)]
    (when-let [n (parse-long-safe count-text)]
      {:path (repo-relative-path repo-root path)
       :count n})))

(defn- parse-count-output
  [repo-root stdout]
  (reduce
   (fn [state line]
     (if (str/blank? line)
       state
       (if-let [row (parse-count-line repo-root line)]
         (update state :matches conj row)
         (update state :diagnostics conj {:kind :invalid-count-line
                                          :message line}))))
   {:matches []
    :diagnostics []}
   (str/split-lines stdout)))

(defn search-json
  "Search for a literal pattern with `rg --json`.

  Returns transient match rows mapped to repo-relative paths. Exit code 1 is
  zero matches, not a failure."
  ([repo-root pattern paths] (search-json repo-root pattern paths {}))
  ([repo-root pattern paths opts]
   (let [paths (mapv normalize-separator paths)
         argv (search-argv pattern paths opts)
         result (run-argv repo-root argv opts)
         parsed (if (#{0 1} (:exit result))
                  (parse-search-output repo-root (:stdout result))
                  {:matches []
                   :summary nil
                   :diagnostics []
                   :invalid-json? false})
         invalid-json? (:invalid-json? parsed)]
     {:argv (:argv result)
      :exit (:exit result)
      :elapsed-ms (:elapsed-ms result)
      :matches (if invalid-json? [] (:matches parsed))
      :match-count (if invalid-json? 0 (count (:matches parsed)))
      :summary (:summary parsed)
      :truncated? (:stdout-truncated? result)
      :diagnostics (into (diagnostic-rows result) (:diagnostics parsed))})))

(defn search-counts-many
  "Search for literal patterns with compact per-file match counts.

  This is the preferred hot-path API for ranking because it avoids retaining
  match-line JSON when callers only need file-level scores. Multiple patterns
  run in one ripgrep process and count all fixed-string matches per file."
  ([repo-root patterns paths] (search-counts-many repo-root patterns paths {}))
  ([repo-root patterns paths opts]
   (let [patterns (mapv str patterns)
         paths (mapv normalize-separator paths)
         argv (count-argv patterns paths opts)
         result (run-argv repo-root argv opts)
         parsed (if (#{0 1} (:exit result))
                  (parse-count-output repo-root (:stdout result))
                  {:matches []
                   :diagnostics []})]
     {:argv (:argv result)
      :exit (:exit result)
      :elapsed-ms (:elapsed-ms result)
      :matches (:matches parsed)
      :match-count (reduce + 0 (map :count (:matches parsed)))
      :file-count (count (:matches parsed))
      :truncated? (:stdout-truncated? result)
      :diagnostics (into (diagnostic-rows result) (:diagnostics parsed))})))

(defn search-counts
  "Search for one literal pattern with compact per-file match counts."
  ([repo-root pattern paths] (search-counts repo-root pattern paths {}))
  ([repo-root pattern paths opts]
   (search-counts-many repo-root [pattern] paths opts)))

(defn search-lines
  "Human-readable ripgrep search output for explicit proof/debug paths."
  ([repo-root pattern paths] (search-lines repo-root pattern paths {}))
  ([repo-root pattern paths opts]
   (let [paths (mapv normalize-separator paths)
         paths (if (seq paths) paths ["."])
         argv (vec (concat [(or (:bin opts) default-bin)
                            "-n"
                            "--line-number"
                            "--column"
                            "--fixed-strings"]
                           (when (not= false (:hidden? opts)) ["--hidden"])
                           (when (:ignore-case? opts) ["--ignore-case"])
                           [pattern]
                           (cons "--" paths)))
         result (run-argv repo-root argv opts)]
     {:argv (:argv result)
      :exit (:exit result)
      :elapsed-ms (:elapsed-ms result)
      :lines (if (#{0 1} (:exit result))
               (str/split-lines (:stdout result))
               [])
      :truncated? (:stdout-truncated? result)
      :diagnostics (diagnostic-rows result)})))
