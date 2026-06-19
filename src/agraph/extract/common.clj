(ns agraph.extract.common
  "Shared mechanical row helpers for extractor implementation namespaces."
  (:require [agraph.hash :as hash]
            [agraph.text :as text]
            [charred.api :as json]
            [clojure.string :as str]))

(def ^:private source-definition-chunk-lines 120)

(defn node-id
  "Return stable node id for kind/name."
  ([kind value] (node-id nil kind value))
  ([id-scope kind value]
   (str (when (seq id-scope) (str id-scope ":"))
        "node:" (name kind) ":" value)))

(defn generic-node
  [run-id id-scope file-id path kind label source-line]
  {:xt/id (node-id id-scope kind label)
   :label label
   :kind kind
   :file-id file-id
   :path path
   :name label
   :source-line (or source-line 1)
   :tokens (text/tokenize label)
   :active? true
   :run-id run-id})

(defn namespace-node
  [run-id id-scope file-id path ns-name]
  {:xt/id (node-id id-scope :namespace ns-name)
   :label ns-name
   :kind :namespace
   :file-id file-id
   :path path
   :namespace ns-name
   :name ns-name
   :public? true
   :source-line 1
   :tokens (text/tokenize ns-name)
   :active? true
   :run-id run-id})

(defn edge-id
  "Return stable edge id."
  [source-id target-id relation _path _source-line]
  (str "edge:" (hash/short-hash [source-id relation target-id])))

(defn edge-row
  [run-id file-id path source-id target-id relation confidence source-line]
  {:xt/id (edge-id source-id target-id relation path source-line)
   :source-id source-id
   :target-id target-id
   :relation relation
   :confidence confidence
   :file-id file-id
   :path path
   :source-line (or source-line 1)
   :active? true
   :run-id run-id})

(defn chunk-id
  "Return stable chunk id."
  ([path label source-line] (chunk-id nil path label source-line))
  ([id-scope path label source-line]
   (str "chunk:" (hash/short-hash [id-scope path label source-line]))))

(defn extract-text-source
  "Extract a supported text source file as one searchable chunk."
  [run-id {:keys [id-scope file-id path content kind]} chunk-kind]
  {:nodes []
   :edges []
   :chunks [{:xt/id (chunk-id id-scope path path 1)
             :file-id file-id
             :path path
             :kind chunk-kind
             :file-kind kind
             :label path
             :text content
             :tokens (text/tokenize content)
             :source-line 1
             :active? true
             :run-id run-id}]
   :diagnostics []})

(defn extract-format-facts
  [run-id {:keys [id-scope file-id path] :as file} root-kind chunk-kind facts]
  (let [root-node (generic-node run-id id-scope file-id path root-kind path 1)
        facts (vec (distinct facts))
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (generic-node run-id id-scope file-id path kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (edge-row run-id
                                     file-id
                                     path
                                     (:xt/id root-node)
                                     (node-id id-scope kind label)
                                     (or relation :defines)
                                     :extracted
                                     source-line))
                         facts)
        chunk-result (extract-text-source run-id file chunk-kind)]
    {:nodes (into [root-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))

(defn read-json-map
  [content]
  (try
    (let [parsed (json/read-json content :key-fn keyword)]
      (when (map? parsed)
        parsed))
    (catch Exception _
      nil)))

(defn read-json-value
  [content]
  (try
    (json/read-json content :key-fn keyword)
    (catch Exception _
      nil)))

(def json-ref-key
  (keyword "$ref"))

(def json-id-key
  (keyword "$id"))

(def json-defs-key
  (keyword "$defs"))

(defn json-ref-values
  [value]
  (cond
    (map? value)
    (let [self-ref (get value json-ref-key)]
      (cond-> (mapcat json-ref-values (vals value))
        (string? self-ref) (conj self-ref)))

    (vector? value)
    (mapcat json-ref-values value)

    :else
    []))

(defn json-ref-tail
  [prefix ref]
  (when (and (string? ref) (str/starts-with? ref prefix))
    (subs ref (count prefix))))

(defn json-key-label
  [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)]
                   (str ns "/" (name k))
                   (name k))
    (string? k) k
    :else (str k)))

(defn json-label
  [value]
  (cond
    (keyword? value) (json-key-label value)
    (string? value) value
    :else (str value)))

(defn manifest-name
  [path]
  (str/lower-case (last (str/split path #"/"))))

(defn toml-array-strings
  [value]
  (->> (re-seq #"\"([^\"]+)\"" (str value))
       (map second)
       (remove str/blank?)
       distinct
       vec))

(defn leading-spaces
  [line]
  (count (take-while #(= \space %) line)))

(defn strip-yaml-scalar
  [value]
  (-> (str value)
      (str/replace #"^\s*['\"]|['\"]\s*$" "")
      str/trim))

(defn yaml-key-line
  [idx line]
  (when-let [[_ indent key value]
             (re-matches #"^(\s*)(?:-\s*)?([A-Za-z0-9_.-]+):(?:\s*(.*))?$" line)]
    {:indent (count indent)
     :key key
     :value (str/trim (or value ""))
     :source-line (inc idx)}))

(defn yaml-top-level-value
  [content key-name]
  (some-> (re-find (re-pattern (str "(?m)^" key-name ":\\s*([^#\\n]+)\\s*$"))
                   content)
          second
          str/trim
          (str/replace #"^['\"]|['\"]$" "")))

(defn yaml-scalar-list-values
  [value]
  (let [value (str/trim (or value ""))]
    (cond
      (str/blank? value) []
      (and (str/starts-with? value "[")
           (str/ends-with? value "]"))
      (->> (subs value 1 (dec (count value)))
           (#(str/split % #","))
           (map strip-yaml-scalar)
           (remove str/blank?)
           vec)

      :else
      [(strip-yaml-scalar value)])))

(defn yaml-section-items
  [content section-names]
  (let [sections (set section-names)]
    (loop [remaining (map-indexed vector (str/split-lines content))
           section nil
           out []]
      (if-let [[idx line] (first remaining)]
        (cond
          (re-matches #"^[A-Za-z_][A-Za-z0-9_-]*:\s*.*" line)
          (let [[_ next-section inline-value]
                (re-matches #"^([A-Za-z_][A-Za-z0-9_-]*):\s*(.*)" line)
                inline-values (yaml-scalar-list-values inline-value)]
            (recur (rest remaining)
                   (when (contains? sections next-section) next-section)
                   (into out
                         (map (fn [value]
                                {:section next-section
                                 :value value
                                 :source-line (inc idx)}))
                         (when (contains? sections next-section)
                           inline-values))))

          (and section
               (re-matches #"^\s*-\s+name:\s+(.+?)\s*$" line))
          (let [[_ value] (re-matches #"^\s*-\s+name:\s+(.+?)\s*$" line)]
            (recur (rest remaining)
                   section
                   (conj out {:section section
                              :value (strip-yaml-scalar value)
                              :source-line (inc idx)})))

          (and section
               (re-matches #"^\s*-\s+(.+?)\s*$" line))
          (let [[_ value] (re-matches #"^\s*-\s+(.+?)\s*$" line)]
            (recur (rest remaining)
                   section
                   (conj out {:section section
                              :value (strip-yaml-scalar value)
                              :source-line (inc idx)})))

          (and section
               (re-matches #"^\s+name:\s+(.+?)\s*$" line))
          (let [[_ value] (re-matches #"^\s+name:\s+(.+?)\s*$" line)]
            (recur (rest remaining)
                   section
                   (conj out {:section section
                              :value (strip-yaml-scalar value)
                              :source-line (inc idx)})))

          (and section
               (not (str/blank? (str/trim line)))
               (zero? (leading-spaces line)))
          (recur (rest remaining) nil out)

          :else
          (recur (rest remaining) section out))
        out))))

(defn yaml-top-section-blocks
  [lines section-name]
  (loop [remaining (map-indexed vector lines)
         in-section? false
         section-indent nil
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (yaml-key-line idx line)]
        (cond
          (and entry (= section-name (:key entry)))
          (recur (rest remaining) true (:indent entry) nil out)

          (and in-section?
               entry
               (<= (:indent entry) section-indent)
               (not= section-name (:key entry)))
          (recur (rest remaining) false nil nil (cond-> out current (conj current)))

          (and in-section?
               entry
               (= (:indent entry) (+ section-indent 2)))
          (recur (rest remaining)
                 true
                 section-indent
                 {:label (:key entry)
                  :source-line (:source-line entry)
                  :lines [[idx line]]}
                 (cond-> out current (conj current)))

          (and in-section? current)
          (recur (rest remaining)
                 true
                 section-indent
                 (update current :lines conj [idx line])
                 out)

          :else
          (recur (rest remaining) in-section? section-indent current out)))
      (cond-> out current (conj current)))))

(defn block-key-values
  [block]
  (->> (:lines block)
       (keep (fn [[_idx line]]
               (when-let [{:keys [key value]} (yaml-key-line 0 line)]
                 (when (seq value)
                   [key (strip-yaml-scalar value)]))))
       (into {})))

(defn bounded-lines
  [text line-limit]
  (str/join "\n" (take line-limit (str/split-lines (or text "")))))

(defn bounded-message
  [message]
  (let [message (str (or message ""))]
    (subs message 0 (min 500 (count message)))))

(defn diagnostic-row
  [run-id file-id path stage line message]
  {:xt/id (str "diagnostic:"
               (hash/short-hash [file-id stage line message]))
   :file-id file-id
   :path path
   :stage (keyword (str (or stage "extract")))
   :message (bounded-message message)
   :source-line (or line 1)
   :active? true
   :run-id run-id})

(defn source-text-chunk
  [run-id id-scope file-id path chunk-kind label content line-limit]
  (let [chunk-text (bounded-lines content line-limit)]
    {:xt/id (chunk-id id-scope path label 1)
     :file-id file-id
     :path path
     :kind chunk-kind
     :label label
     :text chunk-text
     :tokens (text/tokenize (str label "\n" chunk-text))
     :source-line 1
     :active? true
     :run-id run-id}))

(defn source-module-name
  [path]
  (-> path
      (str/replace #"\.d\.(?:ts|mts|cts)$" "")
      (str/replace #"\.rb\.template$" "")
      (str/replace #"\.(astro|c|cc|cpp|cxx|dart|erl|ex|exs|groovy|h|hh|hpp|hrl|hs|html|hxx|jl|kt|kts|lua|m|ml|mli|mm|mts|cts|mjs|cjs|jsx|js|odin|pl|pm|r|R|rake|rb|scala|tsx|ts|php|scss|css|sql|svelte|swift|svg|vue|zig)$" "")
      (str/replace #"/" ".")
      (str/replace #"-" "_")))

(defn line-start-offsets
  [content]
  (loop [idx 0
         starts [0]]
    (if-let [newline-idx (str/index-of content "\n" idx)]
      (recur (inc newline-idx) (conj starts (inc newline-idx)))
      starts)))

(defn balanced-curly-block
  [^String content ^long start]
  (let [content (or content "")
        length (count content)
        open-idx (str/index-of content "{" start)]
    (when open-idx
      (loop [idx open-idx
             depth 0
             in-string? false
             string-delim nil
             escaped? false]
        (when (< idx length)
          (let [ch (.charAt content (int idx))
                escaped-next? (and in-string?
                                   (not escaped?)
                                   (= \\ ch)
                                   (not= \` string-delim))
                closing-string? (and in-string?
                                     (not escaped?)
                                     (not escaped-next?)
                                     (= ch string-delim))
                opening-string? (and (not in-string?)
                                     (or (= \" ch) (= \' ch) (= \` ch)))
                in-string-next? (cond
                                  closing-string? false
                                  escaped-next? true
                                  opening-string? true
                                  :else in-string?)
                string-delim-next (cond
                                    closing-string? nil
                                    opening-string? ch
                                    :else string-delim)
                depth-next (cond
                             in-string-next? depth
                             (= \{ ch) (inc depth)
                             (= \} ch) (dec depth)
                             :else depth)]
            (if (and (not in-string-next?) (zero? depth-next) (pos? depth))
              (subs content start (inc idx))
              (recur (inc idx)
                     depth-next
                     in-string-next?
                     string-delim-next
                     escaped-next?))))))))

(defn source-definition-chunk
  [run-id id-scope file-id path label definition-kind source-line text]
  (let [chunk-text (bounded-lines text source-definition-chunk-lines)
        line-count (count (str/split-lines chunk-text))]
    (cond-> {:xt/id (chunk-id id-scope path label source-line)
             :file-id file-id
             :path path
             :kind :code-definition
             :definition-kind definition-kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line (or source-line 1)
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ (or source-line 1) line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))

(defn curly-depth-delta
  [line]
  (- (count (re-seq #"\{" line))
     (count (re-seq #"\}" line))))

(defn curly-balance-diagnostics
  [run-id file-id path content language]
  (let [balance (reduce + (map curly-depth-delta (str/split-lines content)))]
    (if (zero? balance)
      []
      [(diagnostic-row run-id
                       file-id
                       path
                       "parse"
                       1
                       (str language
                            " extractor found unbalanced curly braces."))])))

(defn xml-attr-value
  [element attr]
  (or (second (re-find (re-pattern (str "(?i)\\b" attr "\\s*=\\s*\"([^\"]+)\""))
                       element))
      (second (re-find (re-pattern (str "(?i)\\b" attr "\\s*=\\s*'([^']+)'"))
                       element))))
