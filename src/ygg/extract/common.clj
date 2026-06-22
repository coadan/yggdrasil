(ns ygg.extract.common
  "Shared mechanical row helpers for extractor implementation namespaces."
  (:require [ygg.hash :as hash]
            [ygg.text :as text]
            [charred.api :as json]
            [clojure.string :as str]))

(def ^:private source-definition-chunk-lines 120)

(def source-file-chunk-lines 100)
(def source-family-definition-chunk-lines 120)

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

(defn package-label
  [ecosystem package-name]
  (str (name ecosystem) ":" package-name))

(defn package-reference?
  [value]
  (and (string? value)
       (not (str/starts-with? value "."))
       (not (str/starts-with? value "/"))))

(defn package-fact
  [{:keys [ecosystem package-name version-range dependency-scope import-names
           source-line relation]
    :or {relation :requires}}]
  (when (and ecosystem (seq package-name))
    (cond-> {:kind :external-package
             :label (package-label ecosystem package-name)
             :ecosystem ecosystem
             :package-name package-name
             :source-line (or source-line 1)
             :relation relation}
      version-range (assoc :version-range version-range)
      dependency-scope (assoc :dependency-scope dependency-scope)
      (seq import-names) (assoc :import-names (vec import-names)))))

(defn package-version-fact
  [{:keys [ecosystem package-name resolved-version source-line relation]
    :or {relation :resolves}}]
  (when (and ecosystem (seq package-name) (seq resolved-version))
    {:kind :external-package-version
     :label (str (package-label ecosystem package-name) "@" resolved-version)
     :ecosystem ecosystem
     :package-name package-name
     :resolved-version resolved-version
     :source-line (or source-line 1)
     :relation relation}))

(defn fact-node
  [run-id id-scope file-id path {:keys [kind label source-line] :as fact}]
  (cond-> (generic-node run-id id-scope file-id path kind label source-line)
    (:ecosystem fact) (assoc :ecosystem (:ecosystem fact))
    (:package-name fact) (assoc :package-name (:package-name fact))
    (:version-range fact) (assoc :version-range (:version-range fact))
    (:resolved-version fact) (assoc :resolved-version (:resolved-version fact))
    (:dependency-scope fact) (assoc :dependency-scope (:dependency-scope fact))
    (seq (:import-names fact)) (assoc :import-names (vec (:import-names fact)))))

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

(defn fact-edge-row
  [run-id file-id path source-id id-scope {:keys [kind label source-line relation] :as fact}]
  (cond-> (edge-row run-id
                    file-id
                    path
                    source-id
                    (node-id id-scope kind label)
                    relation
                    :extracted
                    source-line)
    (:ecosystem fact) (assoc :ecosystem (:ecosystem fact))
    (:package-name fact) (assoc :package-name (:package-name fact))
    (:version-range fact) (assoc :version-range (:version-range fact))
    (:resolved-version fact) (assoc :resolved-version (:resolved-version fact))
    (:dependency-scope fact) (assoc :dependency-scope (:dependency-scope fact))
    (seq (:import-names fact)) (assoc :import-names (vec (:import-names fact)))))

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

(def ^:private sensitive-assignment-key-pattern
  "(?i)[A-Za-z_][A-Za-z0-9_.-]*(?:secret|token|password|api[-_]?key|private[-_]?key|credential|credentials|creds|service[-_]?account|service[-_]?acct|svc[-_]?account|svc[-_]?acct|acct)[A-Za-z0-9_.-]*")

(def ^:private sensitive-assignment-pattern
  (re-pattern (str "\\b(" sensitive-assignment-key-pattern ")\\s*=\\s*"
                   "(\"[^\"]*\"|'[^']*'|[^\\s#]+)")))

(def ^:private sensitive-yaml-assignment-pattern
  (re-pattern (str "(?m)^(\\s*[\"']?(" sensitive-assignment-key-pattern ")[\"']?\\s*:\\s*)"
                   "(\"[^\"]*\"|'[^']*'|[^\\s#]+)")))

(defn redact-sensitive-values
  "Redact values from mechanical runtime/config assignment text.

  The key name remains searchable; the assigned value is intentionally not
  stored in graph chunks or runtime-command labels."
  [text]
  (-> (str text)
      (str/replace sensitive-assignment-pattern "$1=<redacted>")
      (str/replace sensitive-yaml-assignment-pattern "$1<redacted>")))

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

(defn balanced-form
  [content start]
  (let [length (count content)]
    (loop [idx start
           depth 0
           in-string? false
           escaped? false]
      (when (< idx length)
        (let [ch (.charAt content idx)
              escaped-next? (and in-string? (not escaped?) (= \\ ch))
              in-string-next? (if (or escaped? escaped-next?)
                                in-string?
                                (if (= \" ch) (not in-string?) in-string?))
              depth-next (cond
                           in-string-next? depth
                           (= \( ch) (inc depth)
                           (= \) ch) (dec depth)
                           :else depth)]
          (if (and (not in-string-next?) (zero? depth-next) (pos? depth))
            (subs content start (inc idx))
            (recur (inc idx)
                   depth-next
                   in-string-next?
                   escaped-next?)))))))

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

(defn normalize-module-path-part
  [value]
  (str/replace value #"-" "_"))

(defn drop-source-extension
  [value]
  (-> value
      (str/replace #"\.d\.ts$" "")
      (str/replace #"\.(astro|mjs|cjs|jsx|js|tsx|ts|scss|css|json|svelte|vue)$" "")))

(defn- drop-resource-suffix
  [value]
  (str/replace (str value) #"[?#].*$" ""))

(defn resolve-relative-source-target
  [path target]
  (let [base (->> (str/split path #"/")
                  drop-last
                  vec)]
    (loop [parts (concat base (str/split (drop-resource-suffix target) #"/"))
           out []]
      (if-let [part (first parts)]
        (case part
          "." (recur (rest parts) out)
          "" (recur (rest parts) out)
          ".." (recur (rest parts) (vec (drop-last out)))
          (recur (rest parts) (conj out (normalize-module-path-part part))))
        (->> out
             (str/join ".")
             drop-source-extension)))))

(defn js-import-target
  [path target]
  (let [target (str target)]
    (if (str/starts-with? target ".")
      (resolve-relative-source-target path target)
      target)))

(defn js-import-targets
  [idx path line]
  (let [patterns [{:re #"^\s*import\s+type\s+(?:[^\"']+\s+from\s+)?[\"']([^\"']+)[\"'].*"
                   :import-kind :type}
                  {:re #"^\s*import\s+(?!type\b)(?:[^\"']+\s+from\s+)?[\"']([^\"']+)[\"'].*"}
                  {:re #"^\s*export\s+type\s+[^\"']+\s+from\s+[\"']([^\"']+)[\"'].*"
                   :import-kind :type}
                  {:re #"^\s*export\s+(?!type\b)[^\"']+\s+from\s+[\"']([^\"']+)[\"'].*"}
                  {:re #"\brequire\s*\(\s*[\"']([^\"']+)[\"']\s*\)"}
                  {:re #"\bimport\s*\(\s*[\"']([^\"']+)[\"']\s*\)"}]]
    (->> patterns
         (mapcat (fn [{:keys [re import-kind]}]
                   (map (fn [match]
                          {:target (second match)
                           :import-kind import-kind})
                        (re-seq re line))))
         (remove #(str/includes? (str (:target %)) "${"))
         (remove #(str/includes? (str (:target %)) "}"))
         (map #(update % :target (partial js-import-target path)))
         (remove #(str/blank? (:target %)))
         (map (fn [{:keys [target import-kind]}]
                (cond-> {:target target
                         :source-line (inc idx)}
                  import-kind (assoc :import-kind import-kind))))
         distinct)))

(defn js-identifier
  []
  "[A-Za-z_$][A-Za-z0-9_$]*")

(defn js-public-line?
  [line]
  (boolean (re-find #"^\s*export\b" line)))

(defn js-definition-line
  [idx line]
  (let [identifier (js-identifier)
        public? (js-public-line? line)]
    (or (when-let [[_ name]
                   (re-matches
                    (re-pattern
                     (str "^\\s*(?:export\\s+)?(?:default\\s+)?"
                          "(?:async\\s+)?function\\s+(" identifier ")\\b.*"))
                    line)]
          {:kind :function
           :name name
           :public? public?
           :source-line (inc idx)})
        (when-let [[_ name]
                   (re-matches
                    (re-pattern
                     (str "^\\s*(?:export\\s+)?(?:default\\s+)?class\\s+("
                          identifier
                          ")\\b.*"))
                    line)]
          {:kind :class
           :name name
           :public? public?
           :source-line (inc idx)})
        (when-let [[_ name]
                   (re-matches
                    (re-pattern
                     (str "^\\s*(?:export\\s+)?(?:const|let|var)\\s+("
                          identifier
                          ")\\s*(?::[^=]+)?=\\s*(?:async\\s*)?"
                          "(?:\\([^)]*\\)|" identifier ")\\s*=>.*"))
                    line)]
          {:kind :function
           :name name
           :public? public?
           :source-line (inc idx)})
        (when-let [[_ name]
                   (re-matches
                    (re-pattern
                     (str "^\\s*export\\s+(?:const|let|var)\\s+("
                          identifier
                          ")\\b.*"))
                    line)]
          {:kind :var
           :name name
           :public? true
           :source-line (inc idx)}))))

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

(defn properties-assignment-lines
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ key value]
                          (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*[=:]\s*(.*?)\s*$"
                                      line)]
                 (when-not (or (str/blank? key)
                               (str/starts-with? (str/trim line) "#"))
                   {:key key
                    :value (str/trim value)
                    :source-line (inc idx)}))))))

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

(defn yaml-list-values
  [content section-names]
  (let [sections (set section-names)]
    (loop [remaining (map-indexed vector (str/split-lines content))
           section nil
           out []]
      (if-let [[idx line] (first remaining)]
        (cond
          (str/blank? (str/trim line))
          (recur (rest remaining) section out)

          (re-matches #"^[A-Za-z_][A-Za-z0-9_-]*:\s*.*" line)
          (let [[_ next-section inline-value]
                (re-matches #"^([A-Za-z_][A-Za-z0-9_-]*):\s*(.*)" line)
                values (->> (re-seq #"['\"]?([^,'\"\[\]\s]+)['\"]?" inline-value)
                            (map second)
                            (remove str/blank?))]
            (recur (rest remaining)
                   (when (contains? sections next-section) next-section)
                   (into out
                         (map (fn [value]
                                {:section next-section
                                 :value value
                                 :source-line (inc idx)}))
                         (when (contains? sections next-section)
                           values))))

          (and section
               (re-matches #"^\s*-\s+(.+?)\s*$" line))
          (let [value (-> (second (re-matches #"^\s*-\s+(.+?)\s*$" line))
                          str/trim
                          (str/replace #"^['\"]|['\"]$" ""))]
            (recur (rest remaining)
                   section
                   (conj out {:section section
                              :value value
                              :source-line (inc idx)})))

          :else
          (recur (rest remaining) section out))
        out))))

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

(defn source-range-text
  [content source-line end-line]
  (let [lines (vec (str/split-lines (or content "")))
        source-line (max 1 (long (or source-line 1)))
        end-line (max source-line (long (or end-line source-line)))
        start-idx (dec source-line)
        end-idx (min (count lines) end-line)]
    (if (< start-idx end-idx)
      (str/join "\n" (subvec lines start-idx end-idx))
      "")))

(defn curly-depth-delta
  [line]
  (- (count (re-seq #"\{" line))
     (count (re-seq #"\}" line))))

(defn pop-closed-type-scopes
  [type-stack depth]
  (->> type-stack
       (remove #(> (:end-depth %) depth))
       vec))

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

(defn- config-json-facts
  [content]
  (try
    (let [parsed (json/read-json content :key-fn keyword)]
      (->> parsed
           (keep (fn [[k v]]
                   (when (or (string? v) (number? v) (boolean? v))
                     {:kind :config-setting
                      :label (str (name k) "=" v)
                      :source-line 1
                      :relation :defines})))
           vec))
    (catch Exception _
      [])))
(defn- config-assignment-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (or (when-let [[_ key]
                              (re-matches #"^\s*(?:-\s*)?([A-Za-z_][A-Za-z0-9_.-]*)\s*[:=]\s*[\{\[].*$"
                                          line)]
                     {:kind :config-section
                      :label key
                      :source-line (inc idx)
                      :relation :defines})
                   (when-let [[_ key value]
                              (re-matches #"^\s*(?:-\s*)?([A-Za-z_][A-Za-z0-9_.-]*)\s*[:=]\s*['\"]?([^,'\"#]+)['\"]?.*$"
                                          line)]
                     {:kind :config-setting
                      :label (str key "=" (str/trim value))
                      :source-line (inc idx)
                      :relation :defines})
                   (when-let [[_ key]
                              (re-matches #"^\s*(?:-\s*)?([A-Za-z_][A-Za-z0-9_.-]*)\s*:\s*$"
                                          line)]
                     {:kind :config-section
                      :label key
                      :source-line (inc idx)
                      :relation :defines}))))
       distinct
       vec))
(defn- config-import-facts
  [path content]
  (->> (str/split-lines content)
       (map-indexed #(js-import-targets %1 path %2))
       (mapcat identity)
       (map (fn [{:keys [target source-line]}]
              {:kind :config-reference
               :label target
               :source-line source-line
               :relation :references}))
       distinct
       vec))
(def config-reference-keys
  #{"extends" "plugins" "setupFiles" "setupFilesAfterEnv" "reporters"
    "projects" "presets"})
(defn- config-string-reference-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat (fn [[idx line]]
                 (when-let [[_ key rest]
                            (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*:\s*(\[.*\]).*$" line)]
                   (when (contains? config-reference-keys key)
                     (map (fn [[_ target]]
                            {:kind :config-reference
                             :label target
                             :source-line (inc idx)
                             :relation :references})
                          (re-seq #"['\"]([^'\"]+)['\"]" rest))))))
       (remove nil?)
       distinct
       vec))
(defn config-facts
  [{:keys [path content]}]
  (let [path-lower (str/lower-case (str path))
        filename (manifest-name path)
        json-facts (when (or (str/ends-with? path-lower ".json")
                             (str/ends-with? path-lower ".jsonc")
                             (contains? #{".prettierrc" ".eslintrc" ".stylelintrc"}
                                        filename))
                     (config-json-facts content))]
    (vec (concat (or json-facts [])
                 (config-assignment-facts content)
                 (config-import-facts path content)
                 (config-string-reference-facts content)))))
