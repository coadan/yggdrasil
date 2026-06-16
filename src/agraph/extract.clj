(ns agraph.extract
  "Deterministic extraction from Clojure, EDN, and Markdown files."
  (:require [agraph.hash :as hash]
            [agraph.text :as text]
            [clojure.string :as str]
            [rewrite-clj.parser :as parser]
            [rewrite-clj.zip :as z]))

(def definition-symbols
  '#{def defonce defn defn- defmacro defmulti defmethod defprotocol defrecord
     deftype deftest})

(def public-definition-symbols
  '#{def defonce defn defmacro defmulti defmethod defprotocol defrecord deftype})

(defn node-id
  "Return stable node id for kind/name."
  ([kind value] (node-id nil kind value))
  ([id-scope kind value]
   (str (when (seq id-scope) (str id-scope ":"))
        "node:" (name kind) ":" value)))

(defn edge-id
  "Return stable edge id."
  [source-id target-id relation _path _source-line]
  (str "edge:" (hash/short-hash [source-id relation target-id])))

(defn chunk-id
  "Return stable chunk id."
  ([path label source-line] (chunk-id nil path label source-line))
  ([id-scope path label source-line]
   (str "chunk:" (hash/short-hash [id-scope path label source-line]))))

(defn- line
  [loc]
  (some-> loc z/position first int))

(defn- sexpr-safe
  [loc]
  (try
    (z/sexpr loc)
    (catch Exception _ nil)))

(defn- list-locs
  [root]
  (loop [loc root
         out []]
    (if (z/end? loc)
      out
      (recur (z/next loc)
             (if (= :list (z/tag loc))
               (conj out loc)
               out)))))

(defn- first-symbol
  [loc]
  (let [down (z/down loc)]
    (when (= :token (some-> down z/tag))
      (let [value (sexpr-safe down)]
        (when (symbol? value)
          value)))))

(defn- second-symbol
  [loc]
  (let [down (z/down loc)
        second-loc (some-> down z/right)]
    (when (= :token (some-> second-loc z/tag))
      (let [value (sexpr-safe second-loc)]
        (when (symbol? value)
          value)))))

(defn- ns-form?
  [loc]
  (= 'ns (first-symbol loc)))

(defn- definition-form?
  [loc]
  (contains? definition-symbols (first-symbol loc)))

(defn- file-ns-name
  [forms]
  (some (fn [loc]
          (when (ns-form? loc)
            (some-> (second-symbol loc) str)))
        forms))

(defn- requires-from-ns-form
  [loc]
  (let [form (sexpr-safe loc)
        require-clauses (filter #(and (seq? %)
                                      (= :require (first %)))
                                form)]
    (->> require-clauses
         (mapcat rest)
         (filter vector?)
         (keep (fn [spec]
                 (let [target (first spec)
                       alias-idx (.indexOf spec :as)
                       alias (when (<= 0 alias-idx)
                               (nth spec (inc alias-idx) nil))]
                   (when (symbol? target)
                     {:target (str target)
                      :alias (some-> alias str)}))))
         vec)))

(defn- definition-kind
  [sym]
  (case sym
    defn :var
    defn- :var
    defmacro :macro
    defmulti :multimethod
    defmethod :method
    defprotocol :protocol
    defrecord :record
    deftype :type
    deftest :test
    :var))

(defn- definition-public?
  [sym]
  (contains? public-definition-symbols sym))

(defn- definition-node
  [run-id id-scope file-id path ns-name loc]
  (let [def-sym (first-symbol loc)
        name-sym (second-symbol loc)
        name (str name-sym)
        full-name (if ns-name (str ns-name "/" name) name)
        id (node-id id-scope :symbol full-name)]
    {:xt/id id
     :label full-name
     :kind (definition-kind def-sym)
     :file-id file-id
     :path path
     :namespace ns-name
     :name name
     :public? (definition-public? def-sym)
     :source-line (or (line loc) 1)
     :tokens (text/tokenize full-name)
     :active? true
     :run-id run-id}))

(defn- namespace-node
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

(defn- edge-row
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

(defn- all-symbols-in
  [loc]
  (letfn [(symbols [value]
            (cond
              (symbol? value) [value]
              (coll? value) (mapcat symbols value)
              :else []))]
    (vec (symbols (sexpr-safe loc)))))

(defn- call-edges
  [run-id id-scope file-id path ns-name alias->namespace definition-nodes loc]
  (let [source (some-> (definition-node run-id id-scope file-id path ns-name loc) :xt/id)
        local-defs (into {}
                         (map (fn [node] [(:name node) (:xt/id node)]))
                         definition-nodes)]
    (->> (all-symbols-in loc)
         (keep (fn [sym]
                 (let [sym-ns (namespace sym)
                       sym-name (name sym)
                       target-id (cond
                                   (and sym-ns (get alias->namespace sym-ns))
                                   (node-id id-scope
                                            :symbol
                                            (str (get alias->namespace sym-ns) "/" sym-name))

                                   (nil? sym-ns)
                                   (get local-defs sym-name))]
                   (when (and source target-id (not= source target-id))
                     (edge-row run-id file-id path source target-id :calls :inferred (line loc))))))
         distinct
         vec)))

(defn extract-code
  "Extract graph rows from a Clojure source file record."
  [run-id {:keys [id-scope file-id path content]}]
  (try
    (let [root (z/of-node (parser/parse-string-all content) {:track-position? true})
          forms (list-locs root)
          ns-name (or (file-ns-name forms) (str/replace path #"\.(clj|cljc|cljs)$" ""))
          ns-node (namespace-node run-id id-scope file-id path ns-name)
          require-specs (mapcat requires-from-ns-form (filter ns-form? forms))
          alias->namespace (into {} (keep (fn [{:keys [alias target]}]
                                            (when alias [alias target])))
                                 require-specs)
          def-forms (filter definition-form? forms)
          defs (mapv #(definition-node run-id id-scope file-id path ns-name %) def-forms)
          contains-edges (mapv #(edge-row run-id file-id path
                                          (:xt/id ns-node) (:xt/id %)
                                          :defines :extracted (:source-line %))
                               defs)
          require-edges (mapv #(edge-row run-id file-id path
                                         (:xt/id ns-node)
                                         (node-id id-scope :namespace (:target %))
                                         :requires :extracted 1)
                              require-specs)
          calls (mapcat #(call-edges run-id id-scope file-id path ns-name alias->namespace defs %)
                        def-forms)
          chunk {:xt/id (chunk-id id-scope path ns-name 1)
                 :file-id file-id
                 :path path
                 :kind :code-file
                 :label ns-name
                 :text (str/join "\n" (take 80 (str/split-lines content)))
                 :tokens (text/tokenize (str ns-name " " content))
                 :source-line 1
                 :active? true
                 :run-id run-id}]
      {:nodes (into [ns-node] defs)
       :edges (vec (concat contains-edges require-edges calls))
       :chunks [chunk]
       :diagnostics []})
    (catch Exception e
      {:nodes []
       :edges []
       :chunks []
       :diagnostics [{:xt/id (str "diagnostic:" (hash/short-hash [file-id :parse (ex-message e)]))
                      :file-id file-id
                      :path path
                      :stage :parse
                      :message (str (ex-message e)
                                    " at "
                                    (some-> e .getStackTrace first str))
                      :active? true
                      :run-id run-id}]})))

(defn extract-doc
  "Extract Markdown chunks."
  [run-id {:keys [id-scope file-id path content]}]
  (let [lines (str/split-lines content)
        total-lines (count lines)
        close-chunk (fn [out current end-line]
                      (cond-> out
                        (seq (:lines current))
                        (conj (assoc current :end-line end-line))))
        chunks (loop [remaining (map-indexed vector lines)
                      heading-stack []
                      current {:label path
                               :start-line 1
                               :heading-path []
                               :lines []}
                      out []]
                 (if-let [[idx line] (first remaining)]
                   (if-let [[_ marker heading] (re-matches #"^(#{1,6})\s+(.+?)\s*$" line)]
                     (let [level (count marker)
                           stack (->> heading-stack
                                      (remove #(<= level (:level %)))
                                      vec)
                           stack (conj stack {:level level :label heading})]
                       (recur (rest remaining)
                              stack
                              {:label heading
                               :start-line (inc idx)
                               :heading-path (mapv :label stack)
                               :lines [line]}
                              (close-chunk out current idx)))
                     (recur (rest remaining)
                            heading-stack
                            (update current :lines conj line)
                            out))
                   (close-chunk out current total-lines)))]
    {:nodes []
     :edges []
     :chunks (mapv (fn [{:keys [label start-line lines] :as chunk}]
                     (let [text (str/join "\n" lines)]
                       {:xt/id (chunk-id id-scope path label start-line)
                        :file-id file-id
                        :path path
                        :kind :markdown
                        :label label
                        :text text
                        :tokens (text/tokenize (str label "\n" text))
                        :heading-path (:heading-path chunk)
                        :content-sha (hash/sha256-hex text)
                        :source-line start-line
                        :end-line (:end-line chunk)
                        :active? true
                        :run-id run-id}))
                   chunks)
     :diagnostics []}))

(defn extract-edn
  "Extract EDN as a searchable chunk."
  [run-id {:keys [id-scope file-id path content kind]}]
  {:nodes []
   :edges []
   :chunks [{:xt/id (chunk-id id-scope path path 1)
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

(defn- rust-module-name
  [path]
  (-> path
      (str/replace #"\.rs$" "")
      (str/replace #"/src/(lib|main)$" "")
      (str/replace #"/src/" "::")
      (str/replace #"/" "::")
      (str/replace #"-" "_")))

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
    (edge-row run-id file-id path
              ns-id
              (node-id id-scope :namespace (str (rust-module-name path) "::" module-name))
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
      (edge-row run-id file-id path
                ns-id
                (node-id id-scope :namespace clean-target)
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
                               (edge-row run-id file-id path
                                         (:xt/id source)
                                         target-id
                                         :calls
                                         :inferred
                                         (:source-line source))))))))))
         distinct
         vec)))

(defn extract-rust
  "Extract graph rows from a Rust source file record."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (rust-module-name path)
        ns-node (namespace-node run-id id-scope file-id path module-name)
        lines (str/split-lines content)
        defs (->> lines
                  (map-indexed rust-definition-line)
                  (keep identity)
                  (mapv (fn [{:keys [kind name public? source-line]}]
                          (let [label (str module-name "/" name)]
                            {:xt/id (node-id id-scope :symbol label)
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
                             :run-id run-id}))))
        define-edges (mapv #(edge-row run-id file-id path
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
        chunk {:xt/id (chunk-id id-scope path module-name 1)
               :file-id file-id
               :path path
               :kind :rust-file
               :label module-name
               :text (str/join "\n" (take 80 lines))
               :tokens (text/tokenize (str module-name " " content))
               :source-line 1
               :active? true
               :run-id run-id}]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges module-edges use-edges call-edges))
     :chunks [chunk]
     :diagnostics []}))

(defn extract-file
  "Extract graph rows from a file record."
  [run-id file]
  (case (:kind file)
    :code (extract-code run-id file)
    :rust (extract-rust run-id file)
    :doc (extract-doc run-id file)
    :edn (extract-edn run-id file)
    :config (extract-edn run-id file)
    {:nodes [] :edges [] :chunks [] :diagnostics []}))
