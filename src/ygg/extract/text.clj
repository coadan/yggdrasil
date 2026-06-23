(ns ygg.extract.text
  (:require [ygg.extract.common :as common]
            [ygg.hash :as hash]
            [ygg.text :as text]
            [clojure.string :as str]))

(defn extract-text-source
  "Extract a supported text source file as one searchable chunk."
  [run-id file chunk-kind]
  (common/extract-text-source run-id file chunk-kind))

(defn extract-edn
  "Extract EDN as a searchable chunk."
  [run-id {:keys [id-scope file-id path content kind]}]
  {:nodes []
   :edges []
   :chunks [{:xt/id (common/chunk-id id-scope path path 1)
             :file-id file-id
             :path path
             :kind (or kind :edn)
             :label path
             :text content
             :tokens (text/tokenize content)
             :source-line 1
             :active? true
             :run-id run-id}]
   :diagnostics []})

(def ^:private source-map-source-limit
  256)

(defn- json-array-text
  [content key-name]
  (when-let [key-idx (str/index-of content (str "\"" key-name "\""))]
    (when-let [colon-idx (str/index-of content ":" key-idx)]
      (let [length (count content)]
        (loop [idx (inc colon-idx)]
          (when (< idx length)
            (let [ch (.charAt ^String content idx)]
              (cond
                (Character/isWhitespace ch) (recur (inc idx))
                (= \[ ch) (let [start-idx idx]
                            (loop [idx idx
                                   depth 0
                                   in-string? false
                                   escaped? false]
                              (when (< idx length)
                                (let [ch (.charAt ^String content idx)
                                      escaped-next? (and in-string? (not escaped?) (= \\ ch))
                                      in-string-next? (if (or escaped? escaped-next?)
                                                        in-string?
                                                        (if (= \" ch)
                                                          (not in-string?)
                                                          in-string?))
                                      depth-next (cond
                                                   in-string-next? depth
                                                   (= \[ ch) (inc depth)
                                                   (= \] ch) (dec depth)
                                                   :else depth)]
                                  (if (and (not in-string-next?)
                                           (zero? depth-next)
                                           (pos? depth))
                                    (subs content start-idx (inc idx))
                                    (recur (inc idx)
                                           depth-next
                                           in-string-next?
                                           escaped-next?))))))
                :else nil))))))))

(defn- json-field-value
  [content key-name]
  (when-let [[_ value] (re-find (re-pattern (str "\"" key-name "\"\\s*:\\s*"
                                                 "(\"(?:\\\\.|[^\"\\\\])*\"|-?\\d+(?:\\.\\d+)?)"))
                                content)]
    (common/read-json-value value)))

(defn- source-map-sources
  [content]
  (->> (some-> (json-array-text content "sources")
               common/read-json-value)
       (filter string?)
       (remove str/blank?)
       distinct
       vec))

(defn- source-map-limit-diagnostic
  [run-id file-id path source-count]
  (when (< source-map-source-limit source-count)
    (common/diagnostic-row
     run-id
     file-id
     path
     "source-map-source-limit"
     1
     (str "Source map extraction retained "
          source-map-source-limit
          " of "
          source-count
          " sources; omitted "
          (- source-count source-map-source-limit)
          " to bound indexing fanout."))))

(defn- source-map-summary-text
  [path source-map sources]
  (str/join "\n"
            (remove str/blank?
                    (concat [path
                             "source-map"
                             (:file source-map)
                             (:source-root source-map)
                             (some-> (:version source-map) str)]
                            sources))))

(defn extract-source-map
  "Extract bounded source-map references without indexing source content blobs."
  [run-id {:keys [id-scope file-id path content kind]}]
  (let [source-map {:file (json-field-value content "file")
                    :source-root (json-field-value content "sourceRoot")
                    :version (json-field-value content "version")}
        sources (source-map-sources content)
        retained-sources (vec (take source-map-source-limit sources))
        root-node (common/generic-node run-id id-scope file-id path :source-map-file path 1)
        source-facts (mapv (fn [source]
                             {:kind :source-map-source
                              :label source
                              :source-line 1
                              :relation :references})
                           retained-sources)
        source-nodes (mapv #(common/fact-node run-id id-scope file-id path %)
                           source-facts)
        source-edges (mapv #(common/fact-edge-row run-id
                                                  file-id
                                                  path
                                                  (:xt/id root-node)
                                                  id-scope
                                                  %)
                           source-facts)
        summary-text (source-map-summary-text path source-map retained-sources)
        limit-diagnostic (source-map-limit-diagnostic run-id
                                                      file-id
                                                      path
                                                      (count sources))
        diagnostics (cond-> []
                      (nil? (json-array-text content "sources"))
                      (conj (common/diagnostic-row
                             run-id
                             file-id
                             path
                             "parse"
                             1
                             "Source map extractor could not parse sources array."))
                      limit-diagnostic (conj limit-diagnostic))]
    {:nodes (into [root-node] source-nodes)
     :edges source-edges
     :chunks [{:xt/id (common/chunk-id id-scope path path 1)
               :file-id file-id
               :path path
               :kind :source-map-file
               :file-kind kind
               :label path
               :text summary-text
               :tokens (text/tokenize summary-text)
               :source-line 1
               :active? true
               :run-id run-id}]
     :diagnostics diagnostics}))

(defn- env-var-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [trimmed (str/trim line)]
                 (when-not (or (str/blank? trimmed)
                               (str/starts-with? trimmed "#"))
                   (when-let [[_ key]
                              (re-matches #"^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*(?:=|:).*$"
                                          trimmed)]
                     {:kind :env-var
                      :label key
                      :source-line (inc idx)
                      :relation :defines})))))
       distinct
       vec))
(defn extract-env
  "Extract dotenv-style files without storing assigned values as searchable text."
  [run-id {:keys [id-scope file-id path content kind]}]
  (let [root-node (common/generic-node run-id id-scope file-id path :env-file path 1)
        facts (env-var-facts content)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (common/generic-node run-id id-scope file-id path kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (common/edge-row run-id
                                            file-id
                                            path
                                            (:xt/id root-node)
                                            (common/node-id id-scope kind label)
                                            relation
                                            :extracted
                                            source-line))
                         facts)
        sanitized-text (str/join "\n" (cons path (map :label facts)))]
    {:nodes (into [root-node] fact-nodes)
     :edges fact-edges
     :chunks [{:xt/id (common/chunk-id id-scope path path 1)
               :file-id file-id
               :path path
               :kind :env-file
               :file-kind kind
               :label path
               :text sanitized-text
               :tokens (text/tokenize sanitized-text)
               :content-sha (hash/sha256-hex sanitized-text)
               :source-line 1
               :active? true
               :run-id run-id}]
     :diagnostics []}))

(def ^:private shell-function-name-pattern
  "[A-Za-z_][A-Za-z0-9_:-]*")
(def ^:private shell-function-with-parens-pattern
  (re-pattern (str "^\\s*(" shell-function-name-pattern ")\\s*\\(\\s*\\)\\s*(?:\\{.*)?$")))
(def ^:private shell-function-keyword-pattern
  (re-pattern (str "^\\s*function\\s+(" shell-function-name-pattern ")"
                   "(?:\\s*\\(\\s*\\))?\\s*(?:\\{.*)?$")))
(defn- shell-function-name
  [line]
  (or (some-> (re-matches shell-function-with-parens-pattern line) second)
      (some-> (re-matches shell-function-keyword-pattern line) second)))
(defn- next-nonblank-line
  [lines idx]
  (->> lines
       (drop (inc idx))
       (drop-while str/blank?)
       first))
(defn- shell-function-start?
  [lines idx line]
  (and (shell-function-name line)
       (or (str/includes? line "{")
           (= "{" (some-> (next-nonblank-line lines idx) str/trim)))))
(defn- shell-function-facts
  [content]
  (let [lines (vec (str/split-lines (or content "")))
        offsets (vec (common/line-start-offsets (or content "")))]
    (->> lines
         (map-indexed vector)
         (keep (fn [[idx line]]
                 (when (shell-function-start? lines idx line)
                   (let [source-line (inc idx)
                         text (or (common/balanced-curly-block content (nth offsets idx))
                                  line)
                         line-count (count (str/split-lines text))]
                     {:name (shell-function-name line)
                      :source-line source-line
                      :end-line (+ source-line line-count -1)
                      :text text}))))
         vec)))
(def ^:private shell-command-pattern
  (re-pattern (str "(?:^|[;&|({]|\\$\\()\\s*(" shell-function-name-pattern ")\\b")))
(def ^:private shell-syntax-commands
  #{"case" "do" "done" "echo" "elif" "else" "esac" "exit" "export" "fi" "for" "function" "if" "in"
    "return" "set" "then" "unset" "until" "while"})
(defn- shell-command-names
  [line]
  (when-not (str/starts-with? (str/trim (or line "")) "#")
    (->> (re-seq shell-command-pattern (or line ""))
         (map second)
         (remove shell-syntax-commands)
         distinct
         vec)))
(defn- shell-call-facts
  [content]
  (let [lines (vec (str/split-lines (or content "")))]
    (->> lines
         (map-indexed vector)
         (mapcat (fn [[idx line]]
                   (when-not (shell-function-start? lines idx line)
                     (map (fn [name]
                            {:name name
                             :source-line (inc idx)})
                          (shell-command-names line)))))
         distinct
         vec)))
(defn extract-shell
  "Extract shell scripts as file chunks plus function definitions."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [file-node (common/generic-node run-id id-scope file-id path :shell-file path 1)
        file-chunk-result (extract-text-source run-id file :shell-file)
        functions (shell-function-facts content)
        calls (shell-call-facts content)
        function-label (fn [{:keys [name]}]
                         (str path "/" name))
        function-nodes (mapv (fn [{:keys [source-line] :as function}]
                               (common/generic-node run-id
                                                    id-scope
                                                    file-id
                                                    path
                                                    :shell-function
                                                    (function-label function)
                                                    source-line))
                             functions)
        function-edges (mapv (fn [{:keys [source-line] :as function}]
                               (common/edge-row run-id
                                                file-id
                                                path
                                                (:xt/id file-node)
                                                (common/node-id id-scope
                                                                :shell-function
                                                                (function-label function))
                                                :defines
                                                :extracted
                                                source-line))
                             functions)
        call-edges (mapv (fn [{:keys [name source-line]}]
                           (common/edge-row run-id
                                            file-id
                                            path
                                            (:xt/id file-node)
                                            (common/node-id id-scope :shell-function name)
                                            :calls
                                            :extracted
                                            source-line))
                         calls)
        function-chunks (mapv (fn [{:keys [source-line text] :as function}]
                                (common/source-definition-chunk run-id
                                                                id-scope
                                                                file-id
                                                                path
                                                                (function-label function)
                                                                :function
                                                                source-line
                                                                text))
                              functions)]
    {:nodes (into [file-node] function-nodes)
     :edges (vec (concat function-edges call-edges))
     :chunks (vec (concat (:chunks file-chunk-result) function-chunks))
     :diagnostics []}))

(def ^:private style-section-chunk-lines
  120)
(def ^:private style-rule-range-limit
  128)
(def ^:private style-file-chunk-char-limit
  50000)

(defn- bounded-edge-text
  [content limit]
  (let [content (str (or content ""))
        limit (long limit)]
    (if (or (not (pos? limit))
            (<= (count content) limit))
      content
      (let [edge-size (max 1 (quot limit 2))
            tail-start (max edge-size (- (count content) edge-size))]
        (str (subs content 0 edge-size)
             "\n"
             (subs content tail-start))))))

(defn- style-file-chunk
  [run-id id-scope file-id path content kind]
  (let [chunk-text (bounded-edge-text content style-file-chunk-char-limit)]
    {:xt/id (common/chunk-id id-scope path path 1)
     :file-id file-id
     :path path
     :kind :style-file
     :file-kind kind
     :label path
     :text chunk-text
     :tokens (text/tokenize chunk-text)
     :source-line 1
     :active? true
     :run-id run-id}))
(defn- style-variable-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ name]
                          (re-matches #"^\s*(\$[A-Za-z0-9_-]+)\s*:.*"
                                      line)]
                 {:kind :style-variable
                  :label name
                  :source-line (inc idx)
                  :line line})))
       vec))
(defn- style-section-ranges
  [lines]
  (let [close-section (fn [out current end-line]
                        (if current
                          (conj out (assoc current :end-line end-line))
                          out))]
    (loop [remaining (map-indexed vector lines)
           current nil
           out []]
      (if-let [[idx line] (first remaining)]
        (if-let [[_ label]
                 (re-matches #"^\s*//\s*scss-docs-start\s+(\S+)\s*$"
                             line)]
          (recur (rest remaining)
                 {:kind :style-section
                  :label label
                  :source-line (inc idx)}
                 (close-section out current idx))
          (if (and current
                   (re-matches #"^\s*//\s*scss-docs-end\s+\S+\s*$" line))
            (recur (rest remaining)
                   nil
                   (close-section out current (inc idx)))
            (recur (rest remaining) current out)))
        (close-section out current (count lines))))))
(defn- style-selector-line
  [idx line]
  (let [trimmed (str/trim line)]
    (when (and (str/includes? trimmed "{")
               (not (str/starts-with? trimmed "@"))
               (not (str/starts-with? trimmed "}"))
               (not (str/starts-with? trimmed "//")))
      (let [selector (-> trimmed
                         (str/split #"\{" 2)
                         first
                         str/trim)]
        (when-not (str/blank? selector)
          {:kind :style-rule
           :label selector
           :source-line (inc idx)})))))
(defn- style-brace-delta
  [line]
  (- (count (filter #(= \{ %) line))
     (count (filter #(= \} %) line))))
(defn- style-rule-ranges
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         depth 0
         out []]
    (if-let [[idx line] (first remaining)]
      (if current
        (let [next-depth (+ depth (style-brace-delta line))]
          (if (not (pos? next-depth))
            (recur (rest remaining)
                   nil
                   0
                   (conj out (assoc current :end-line (inc idx))))
            (recur (rest remaining) current next-depth out)))
        (if-let [rule (style-selector-line idx line)]
          (let [next-depth (style-brace-delta line)]
            (if (pos? next-depth)
              (recur (rest remaining) rule next-depth out)
              (recur (rest remaining) nil 0 (conj out (assoc rule :end-line (inc idx))))))
          (recur (rest remaining) nil 0 out)))
      out)))
(defn- limited-style-rule-ranges
  [run-id file-id path rule-ranges]
  (let [rule-ranges (vec rule-ranges)
        rule-count (count rule-ranges)
        limit style-rule-range-limit]
    (if (<= rule-count limit)
      {:ranges rule-ranges
       :diagnostics []}
      (let [omitted (- rule-count limit)
            first-omitted (nth rule-ranges limit nil)]
        {:ranges (subvec rule-ranges 0 limit)
         :diagnostics [(common/diagnostic-row
                        run-id
                        file-id
                        path
                        "style-rule-limit"
                        (:source-line first-omitted)
                        (str "Style rule extraction retained "
                             limit
                             " of "
                             rule-count
                             " rules; omitted "
                             omitted
                             " to bound indexing fanout."))]}))))
(defn- style-section-chunk
  [kind run-id id-scope file-id path lines {:keys [label source-line end-line]}]
  (let [line-count (max 0 (inc (- (long end-line) (long source-line))))
        text-lines (->> lines
                        (drop (dec (long source-line)))
                        (take (min line-count style-section-chunk-lines)))
        chunk-text (str/join "\n" text-lines)
        actual-end-line (+ (long source-line) (count text-lines) -1)]
    (cond-> {:xt/id (common/chunk-id id-scope path label source-line)
             :file-id file-id
             :path path
             :kind kind
             :definition-kind kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line source-line
             :active? true
             :run-id run-id}
      (seq text-lines) (assoc :end-line actual-end-line)
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))
(defn- style-variable-chunk
  [run-id id-scope file-id path {:keys [label source-line line]}]
  (let [chunk-text (str line)]
    (cond-> {:xt/id (common/chunk-id id-scope path label source-line)
             :file-id file-id
             :path path
             :kind :style-variable
             :definition-kind :style-variable
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line source-line
             :end-line source-line
             :active? true
             :run-id run-id}
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))
(defn extract-style
  "Extract CSS/SCSS as searchable style chunks plus mechanical SCSS structure."
  [run-id {:keys [id-scope file-id path content kind]}]
  (let [lines (str/split-lines content)
        root-node (common/generic-node run-id id-scope file-id path :style-file path 1)
        variable-facts (style-variable-facts lines)
        section-ranges (style-section-ranges lines)
        raw-rule-ranges (style-rule-ranges lines)
        limited-rules (limited-style-rule-ranges run-id file-id path raw-rule-ranges)
        rule-ranges (:ranges limited-rules)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (common/generic-node run-id id-scope file-id path
                                                kind label source-line))
                         (concat section-ranges rule-ranges variable-facts))
        fact-edges (mapv (fn [{:keys [kind label source-line]}]
                           (common/edge-row run-id
                                            file-id
                                            path
                                            (:xt/id root-node)
                                            (common/node-id id-scope kind label)
                                            :defines
                                            :extracted
                                            source-line))
                         (concat section-ranges rule-ranges variable-facts))
        section-chunks (mapv #(style-section-chunk :style-section
                                                   run-id
                                                   id-scope
                                                   file-id
                                                   path
                                                   lines
                                                   %)
                             section-ranges)
        rule-chunks (mapv #(style-section-chunk :style-rule
                                                run-id
                                                id-scope
                                                file-id
                                                path
                                                lines
                                                %)
                          rule-ranges)
        variable-chunks (mapv #(style-variable-chunk run-id
                                                     id-scope
                                                     file-id
                                                     path
                                                     %)
                              variable-facts)]
    {:nodes (into [root-node] fact-nodes)
     :edges fact-edges
     :chunks (vec (concat [(style-file-chunk run-id id-scope file-id path content kind)]
                          section-chunks
                          rule-chunks
                          variable-chunks))
     :diagnostics (:diagnostics limited-rules)}))
