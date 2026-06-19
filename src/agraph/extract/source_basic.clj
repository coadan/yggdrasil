(ns agraph.extract.source-basic
  (:require [agraph.extract.common :as common]
            [agraph.hash :as hash]
            [agraph.text :as text]
            [clojure.string :as str]))

(def ^:private rust-file-chunk-lines 80)
(def ^:private rust-definition-chunk-lines 120)
(def ^:private go-file-chunk-lines 100)
(def ^:private go-definition-chunk-lines 120)
(defn- rust-module-name
  [path]
  (-> path
      (str/replace #"\.rs$" "")
      (str/replace #"/src/(lib|main)$" "")
      (str/replace #"/src/" "::")
      (str/replace #"/" "::")
      (str/replace #"-" "_")))
(defn- rust-declared-module-name
  [path module-name]
  (let [current (rust-module-name path)
        segments (str/split current #"::")
        file-module (last segments)
        parent (if (#{"lib" "main" "mod"} file-module)
                 (butlast segments)
                 segments)]
    (str/join "::" (concat parent [module-name]))))
(defn- rust-definition-kind
  [kind]
  (case kind
    "fn" :function
    "struct" :struct
    "enum" :enum
    "trait" :trait
    "impl" :impl
    :rust-symbol))
(defn- rust-definition-line
  [idx line]
  (or (when-let [[_ public? async? kind name]
                 (re-matches #"^\s*(pub(?:\([^)]*\))?\s+)?(async\s+)?(fn|struct|enum|trait)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*" line)]
        {:kind (rust-definition-kind kind)
         :name name
         :public? (boolean public?)
         :source-line (inc idx)
         :async? (boolean async?)})
      (when-let [[_ target]
                 (re-matches #"^\s*impl(?:<[^>]+>)?\s+([A-Za-z_][A-Za-z0-9_:<>]*)\b.*" line)]
        {:kind :impl
         :name (str "impl:" target)
         :public? true
         :source-line (inc idx)})))
(defn- rust-module-edge
  [run-id id-scope file-id path ns-id idx line]
  (when-let [[_ module-name]
             (re-matches #"^\s*(?:pub\s+)?mod\s+([A-Za-z_][A-Za-z0-9_]*)\s*;.*" line)]
    (common/edge-row run-id file-id path
              ns-id
              (common/node-id id-scope :namespace (rust-declared-module-name path module-name))
              :declares-module
              :extracted
              (inc idx))))
(defn- rust-use-edge
  [run-id id-scope file-id path ns-id idx line]
  (when-let [[_ target]
             (re-matches #"^\s*use\s+([^;]+);.*" line)]
    (let [clean-target (-> target
                           (str/replace #"\s+" "")
                           (str/replace #"\{.*" ""))]
      (common/edge-row run-id file-id path
                ns-id
                (common/node-id id-scope :namespace clean-target)
                :uses
                :extracted
                (inc idx)))))
(defn- rust-call-edges
  [run-id file-id path defs content]
  (let [defs-by-name (into {} (map (fn [node] [(:name node) (:xt/id node)])) defs)
        function-defs (filter #(= :function (:kind %)) defs)]
    (->> function-defs
         (mapcat
          (fn [source]
            (let [line-text (->> (str/split-lines content)
                                 (drop (dec (:source-line source)))
                                 (take 80)
                                 (str/join "\n"))]
              (->> (re-seq #"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(" line-text)
                   (map second)
                   (keep (fn [callee]
                           (when-let [target-id (get defs-by-name callee)]
                             (when (not= target-id (:xt/id source))
                               (common/edge-row run-id file-id path
                                         (:xt/id source)
                                         target-id
                                         :calls
                                         :inferred
                                         (:source-line source))))))))))
         distinct
         vec)))
(defn- rust-definition-text
  [lines current-def next-def]
  (let [source-line (:source-line current-def)
        end-line (or (some-> next-def :source-line dec)
                     (count lines))]
    (->> lines
         (drop (dec source-line))
         (take (inc (- end-line source-line)))
         (take rust-definition-chunk-lines)
         (str/join "\n"))))
(defn- rust-definition-chunk
  [run-id id-scope file-id path module-name {:keys [kind name source-line text]}]
  (let [label (str module-name "/" name)
        chunk-text (common/bounded-lines text rust-definition-chunk-lines)
        line-count (count (str/split-lines chunk-text))]
    (cond-> {:xt/id (common/chunk-id id-scope path label source-line)
             :file-id file-id
             :path path
             :kind :rust-definition
             :definition-kind kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line source-line
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ source-line line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))
(defn extract-rust
  "Extract graph rows from a Rust source file record."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (rust-module-name path)
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (str/split-lines content)
        raw-defs (->> lines
                      (map-indexed rust-definition-line)
                      (keep identity)
                      vec)
        defs-with-text (mapv (fn [current next-def]
                               (assoc current :text (rust-definition-text lines
                                                                          current
                                                                          next-def)))
                             raw-defs
                             (concat (rest raw-defs) [nil]))
        defs (mapv (fn [{:keys [kind name public? source-line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (common/node-id id-scope :symbol label)
                        :label label
                        :kind kind
                        :file-id file-id
                        :path path
                        :namespace module-name
                        :name name
                        :public? public?
                        :source-line source-line
                        :tokens (text/tokenize label)
                        :active? true
                        :run-id run-id}))
                   defs-with-text)
        define-edges (mapv #(common/edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        module-edges (keep-indexed #(rust-module-edge run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      (:xt/id ns-node)
                                                      %1
                                                      %2)
                                   lines)
        use-edges (keep-indexed #(rust-use-edge run-id
                                                id-scope
                                                file-id
                                                path
                                                (:xt/id ns-node)
                                                %1
                                                %2)
                                lines)
        call-edges (rust-call-edges run-id file-id path defs content)
        chunk-text (str/join "\n" (take rust-file-chunk-lines lines))
        chunk {:xt/id (common/chunk-id id-scope path module-name 1)
               :file-id file-id
               :path path
               :kind :rust-file
               :label module-name
               :text chunk-text
               :tokens (text/tokenize (str module-name "\n" chunk-text))
               :source-line 1
               :active? true
               :run-id run-id}
        definition-chunks (mapv #(rust-definition-chunk run-id
                                                        id-scope
                                                        file-id
                                                        path
                                                        module-name
                                                        %)
                                defs-with-text)]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges module-edges use-edges call-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics []}))
(defn- go-namespace-name
  [path _content]
  (str/replace path #"\.go$" ""))
(defn- go-public?
  [name]
  (boolean (re-matches #"[A-Z].*" (str name))))
(defn- go-receiver-type
  [receiver]
  (some-> receiver
          str/trim
          (str/split #"\s+")
          last
          (str/replace #"^\*" "")
          (str/replace #"\[.*\]$" "")))
(defn- go-definition-kind
  [path kind name receiver-type]
  (cond
    receiver-type :method
    (= "func" kind) (if (and (str/ends-with? path "_test.go")
                             (re-matches #"(Test|Benchmark|Fuzz).+" name))
                      :test
                      :function)
    (= "struct" kind) :struct
    (= "interface" kind) :interface
    (= "const" kind) :constant
    (= "var" kind) :var
    :else :type))
(defn- go-definition-line
  [path content line-starts idx line]
  (or (when-let [[_ receiver name]
                 (re-matches #"^\s*func\s+(?:\(([^)]*)\)\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(.*"
                             line)]
        (let [receiver-type (go-receiver-type receiver)
              start (+ (get line-starts idx 0)
                       (or (str/index-of line "func") 0))]
          {:kind (go-definition-kind path "func" name receiver-type)
           :name (if receiver-type
                   (str receiver-type "." name)
                   name)
           :public? (go-public? name)
           :source-line (inc idx)
           :text (or (common/balanced-curly-block content start) line)}))
      (when-let [[_ name kind]
                 (re-matches #"^\s*type\s+([A-Za-z_][A-Za-z0-9_]*)\s+([A-Za-z_][A-Za-z0-9_]*)?.*"
                             line)]
        (let [start (+ (get line-starts idx 0)
                       (or (str/index-of line "type") 0))]
          {:kind (go-definition-kind path kind name nil)
           :name name
           :public? (go-public? name)
           :source-line (inc idx)
           :text (or (common/balanced-curly-block content start) line)}))
      (when-let [[_ kind name]
                 (re-matches #"^\s*(const|var)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                             line)]
        {:kind (go-definition-kind path kind name nil)
         :name name
         :public? (go-public? name)
         :source-line (inc idx)
         :text line})))
(defn- go-definition-chunk
  [run-id id-scope file-id path namespace-name {:keys [kind name source-line text]}]
  (let [label (str namespace-name "/" name)
        chunk-text (common/bounded-lines text go-definition-chunk-lines)
        line-count (count (str/split-lines chunk-text))]
    (cond-> {:xt/id (common/chunk-id id-scope path label source-line)
             :file-id file-id
             :path path
             :kind :code-definition
             :definition-kind kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line (or source-line 1)
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ (or source-line 1) line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))
(defn- go-import-target
  [line prefixed?]
  (let [pattern (if prefixed?
                  #"^\s*import\s+(?:(?:[A-Za-z_][A-Za-z0-9_]*|\.)\s+)?\"([^\"]+)\".*"
                  #"^\s*(?:(?:[A-Za-z_][A-Za-z0-9_]*|\.)\s+)?\"([^\"]+)\".*")]
    (some-> (re-matches pattern line) second)))
(defn- go-imports
  [lines]
  (loop [remaining (map-indexed vector lines)
         in-block? false
         out []]
    (if-let [[idx line] (first remaining)]
      (cond
        in-block?
        (if (re-matches #"^\s*\)\s*$" line)
          (recur (rest remaining) false out)
          (recur (rest remaining)
                 true
                 (cond-> out
                   (go-import-target line false)
                   (conj {:target (go-import-target line false)
                          :source-line (inc idx)}))))

        (re-matches #"^\s*import\s+\(\s*$" line)
        (recur (rest remaining) true out)

        (go-import-target line true)
        (recur (rest remaining)
               false
               (conj out {:target (go-import-target line true)
                          :source-line (inc idx)}))

        :else
        (recur (rest remaining) false out))
      out)))
(def go-call-exclusions
  #{"append" "cap" "close" "complex" "copy" "delete" "defer" "func" "go"
    "if" "imag" "len" "make" "new" "panic" "print" "println" "real"
    "recover" "return" "select" "switch"})
(defn- go-call-name-keys
  [node]
  (let [node-name (:name node)]
    (cond-> [node-name]
      (str/includes? node-name ".")
      (conj (last (str/split node-name #"\."))))))
(defn- go-call-edges
  [run-id file-id path defs content]
  (let [defs-by-name (into {}
                           (mapcat (fn [node]
                                     (map (fn [name] [name (:xt/id node)])
                                          (go-call-name-keys node))))
                           defs)
        callable-defs (filter #(contains? #{:function :method :test} (:kind %))
                              defs)
        lines (vec (str/split-lines content))]
    (->> callable-defs
         (mapcat
          (fn [source]
            (let [line-text (->> lines
                                 (drop (dec (:source-line source)))
                                 (take 120)
                                 (str/join "\n"))]
              (->> (re-seq #"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(" line-text)
                   (map second)
                   (remove go-call-exclusions)
                   (keep (fn [callee]
                           (when-let [target-id (get defs-by-name callee)]
                             (when (not= target-id (:xt/id source))
                               (common/edge-row run-id file-id path
                                         (:xt/id source)
                                         target-id
                                         :calls
                                         :inferred
                                         (:source-line source))))))))))
         distinct
         vec)))
(defn extract-go
  "Extract graph rows from a Go source file record."
  [run-id {:keys [id-scope file-id path content]}]
  (let [namespace-name (go-namespace-name path content)
        ns-node (common/namespace-node run-id id-scope file-id path namespace-name)
        lines (str/split-lines content)
        line-starts (common/line-start-offsets content)
        def-forms (->> lines
                       (map-indexed #(go-definition-line path content line-starts %1 %2))
                       (keep identity)
                       vec)
        defs (->> def-forms
                  (mapv (fn [{:keys [kind name public? source-line]}]
                          (let [label (str namespace-name "/" name)]
                            {:xt/id (common/node-id id-scope :symbol label)
                             :label label
                             :kind kind
                             :file-id file-id
                             :path path
                             :namespace namespace-name
                             :name name
                             :public? public?
                             :source-line source-line
                             :tokens (text/tokenize label)
                             :active? true
                             :run-id run-id}))))
        define-edges (mapv #(common/edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           defs)
        import-edges (mapv #(common/edge-row run-id file-id path
                                      (:xt/id ns-node)
                                      (common/node-id id-scope :namespace (:target %))
                                      :imports
                                      :extracted
                                      (:source-line %))
                           (go-imports lines))
        call-edges (go-call-edges run-id file-id path defs content)
        chunk-text (str/join "\n" (take go-file-chunk-lines lines))
        chunk {:xt/id (common/chunk-id id-scope path namespace-name 1)
               :file-id file-id
               :path path
               :kind :go-file
               :label namespace-name
               :text chunk-text
               :tokens (text/tokenize (str namespace-name "\n" chunk-text))
               :source-line 1
               :active? true
               :run-id run-id}
        definition-chunks (mapv #(go-definition-chunk run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      namespace-name
                                                      %)
                                def-forms)]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges call-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics []}))
