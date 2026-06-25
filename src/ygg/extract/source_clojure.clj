(ns ygg.extract.source-clojure
  (:require [ygg.extract.common :as common]
            [ygg.hash :as hash]
            [ygg.text :as text]
            [clojure.string :as str]))

(def definition-symbols
  '#{def defonce defn defn- defmacro defmulti defmethod defprotocol defrecord
     deftype deftest})
(def public-definition-symbols
  '#{def defonce defn defmacro defmulti defmethod defprotocol defrecord deftype})
(def clojure-symbol-pattern
  "[A-Za-z0-9_.*+!?<>=/$%&-]+")
(def clojure-symbol-segment-pattern
  "[A-Za-z0-9_.*+!?<>=$%&-]+")
(def clojure-metadata-prefix-pattern
  "(?:\\^(?:\\{[^}]*\\}|\\S+)\\s+)*")
(def ^:private code-file-chunk-lines 80)
(def ^:private code-definition-chunk-lines 120)
(def ^:private code-definition-token-chars 50000)
(def ^:private code-definition-fragment-token-chars 20000)
(def ^:private max-code-definition-fragments 24)
(def ^:private max-code-definition-fragments-per-file 72)
(def ^:private max-definition-scan-line-chars 4000)
(def ^:private definition-symbol-names
  (set (map name (conj definition-symbols 'defc))))

(defn- tokenize-definition-text
  [value max-chars]
  (binding [text/*max-tokenize-chars* (min (long text/*max-tokenize-chars*)
                                           (long max-chars))]
    (text/tokenize value)))

(defn- bounded-definition-fragments
  [lines]
  (let [fragments (->> lines
                       (drop code-definition-chunk-lines)
                       (partition-all code-definition-chunk-lines)
                       (map-indexed vector)
                       vec)]
    (if (<= (count fragments) max-code-definition-fragments)
      fragments
      (let [head-count (quot max-code-definition-fragments 2)
            tail-count (- max-code-definition-fragments head-count)]
        (vec (concat (take head-count fragments)
                     (take-last tail-count fragments)))))))

(defn- leading-form-symbol
  [line]
  (let [line (str line)
        length (count line)]
    (when (<= length max-definition-scan-line-chars)
      (loop [idx 0]
        (when (< idx length)
          (let [ch (.charAt line idx)]
            (cond
              (Character/isWhitespace ch)
              (recur (inc idx))

              (= \( ch)
              (let [start (inc idx)
                    end (loop [idx start]
                          (if (or (>= idx length)
                                  (Character/isWhitespace (.charAt line idx)))
                            idx
                            (recur (inc idx))))]
                (when (< start end)
                  (subs line start end)))

              :else
              nil)))))))

(defn- definition-line?
  [line]
  (when-let [form-symbol (leading-form-symbol line)]
    (let [slash-idx (str/last-index-of form-symbol "/")
          base (if slash-idx
                 (subs form-symbol (inc slash-idx))
                 form-symbol)]
      (contains? definition-symbol-names base))))

(defn- ns-form-text
  [content]
  (when-let [match (re-find #"(?m)\(ns\s+" content)]
    (let [start (str/index-of content match)]
      (common/balanced-form content start))))
(defn- file-ns-name
  [ns-text]
  (when ns-text
    (some-> (re-find (re-pattern (str "\\(ns\\s+(" clojure-symbol-pattern ")")) ns-text)
            second)))
(defn- require-clause-text
  [ns-text]
  (when-let [match (re-find #"\(:require\b" ns-text)]
    (let [start (str/index-of ns-text match)]
      (common/balanced-form ns-text start))))
(defn- requires-from-ns-form
  [ns-text]
  (let [clause (or (some-> ns-text require-clause-text) "")]
    (->> (re-seq (re-pattern (str "\\[(" clojure-symbol-pattern ")"
                                  "(?:[^\\]]*?:as\\s+(" clojure-symbol-pattern "))?"
                                  "[^\\]]*\\]"))
                 clause)
         (keep (fn [[_ target alias]]
                 (when-not (str/starts-with? target ":")
                   {:target target
                    :alias alias})))
         vec)))
(defn- definition-kind
  [sym]
  (case (symbol (name sym))
    defn :var
    defn- :var
    defc :component
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
  (contains? public-definition-symbols (symbol (name sym))))
(defn- definition-node
  [run-id id-scope file-id path ns-name {:keys [def-sym name line private?]}]
  (let [def-sym (symbol def-sym)
        full-name (if ns-name (str ns-name "/" name) name)
        id (common/node-id id-scope :symbol full-name)]
    {:xt/id id
     :label full-name
     :kind (definition-kind def-sym)
     :file-id file-id
     :path path
     :namespace ns-name
     :name name
     :public? (and (not private?) (definition-public? def-sym))
     :source-line (or line 1)
     :tokens (text/tokenize full-name)
     :active? true
     :run-id run-id}))
(defn- definition-forms
  [content]
  (let [definition-names (->> (conj definition-symbols 'defc)
                              (map name)
                              (sort-by count >)
                              (str/join "|"))
        pattern (re-pattern (str "^\\s*\\("
                                 "(?:" clojure-symbol-segment-pattern "\\/)?"
                                 "(" definition-names ")\\s+("
                                 clojure-metadata-prefix-pattern
                                 ")("
                                 clojure-symbol-pattern
                                 ")"))
        line-starts (common/line-start-offsets content)]
    (->> (str/split-lines content)
         (map-indexed vector)
         (keep (fn [[idx line]]
                 (when-let [[_ def-sym metadata-prefixes name] (when (definition-line? line)
                                                                 (re-find pattern line))]
                   (let [form-start (+ (get line-starts idx 0)
                                       (or (str/index-of line "(") 0))
                         form-text (common/balanced-form content form-start)]
                     {:def-sym def-sym
                      :name name
                      :line (inc idx)
                      :private? (str/includes? metadata-prefixes ":private")
                      :text form-text}))))
         vec)))
(defn- code-definition-chunk
  [run-id id-scope file-id path ns-name {:keys [def-sym name line text]}]
  (let [full-name (if ns-name (str ns-name "/" name) name)
        def-sym (symbol def-sym)
        chunk-text (common/bounded-lines text code-definition-chunk-lines)
        line-count (count (str/split-lines chunk-text))]
    (cond-> {:xt/id (common/chunk-id id-scope path full-name line)
             :file-id file-id
             :path path
             :kind :code-definition
             :definition-kind (definition-kind def-sym)
             :label full-name
             :text chunk-text
             :tokens (tokenize-definition-text (str full-name "\n" chunk-text)
                                               code-definition-token-chars)
             :source-line (or line 1)
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ (or line 1) line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))
(defn- code-definition-fragment-chunks
  [run-id id-scope file-id path ns-name {:keys [def-sym name line text]}]
  (let [full-name (if ns-name (str ns-name "/" name) name)
        def-sym (symbol def-sym)
        source-line (or line 1)
        lines (vec (str/split-lines (or text "")))]
    (mapv
     (fn [[idx part]]
       (let [offset (* (inc idx) code-definition-chunk-lines)
             part (vec part)
             fragment-line (+ source-line offset)
             end-line (+ fragment-line (count part) -1)
             label (str full-name "#lines-" fragment-line "-" end-line)
             chunk-text (str/join "\n" part)]
         (cond-> {:xt/id (common/chunk-id id-scope path label fragment-line)
                  :file-id file-id
                  :path path
                  :kind :code-definition-fragment
                  :definition-kind (definition-kind def-sym)
                  :label label
                  :text chunk-text
                  :tokens (tokenize-definition-text
                           (str full-name "\n" label "\n" chunk-text)
                           code-definition-fragment-token-chars)
                  :source-line fragment-line
                  :end-line end-line
                  :active? true
                  :run-id run-id}
           (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))
     (bounded-definition-fragments lines))))
(defn extract-code
  "Extract graph rows from a Clojure source file record."
  [run-id {:keys [id-scope file-id path content]}]
  (try
    (let [ns-text (ns-form-text content)
          ns-name (or (file-ns-name ns-text) (str/replace path #"\.(clj|cljc|cljs)$" ""))
          ns-node (common/namespace-node run-id id-scope file-id path ns-name)
          require-specs (requires-from-ns-form ns-text)
          def-forms (definition-forms content)
          defs (mapv #(definition-node run-id id-scope file-id path ns-name %) def-forms)
          contains-edges (mapv #(common/edge-row run-id file-id path
                                                 (:xt/id ns-node) (:xt/id %)
                                                 :defines :extracted (:source-line %))
                               defs)
          require-edges (mapv #(common/edge-row run-id file-id path
                                                (:xt/id ns-node)
                                                (common/node-id id-scope :namespace (:target %))
                                                :requires :extracted 1)
                              require-specs)
          chunk-text (common/bounded-lines content code-file-chunk-lines)
          chunk {:xt/id (common/chunk-id id-scope path ns-name 1)
                 :file-id file-id
                 :path path
                 :kind :code-file
                 :label ns-name
                 :text chunk-text
                 :tokens (text/tokenize (str ns-name "\n" chunk-text))
                 :source-line 1
                 :active? true
                 :run-id run-id}
          definition-chunks (mapv #(code-definition-chunk run-id
                                                          id-scope
                                                          file-id
                                                          path
                                                          ns-name
                                                          %)
                                  def-forms)
          definition-fragment-chunks (into []
                                           (comp
                                            (mapcat #(code-definition-fragment-chunks run-id
                                                                                      id-scope
                                                                                      file-id
                                                                                      path
                                                                                      ns-name
                                                                                      %))
                                            (take max-code-definition-fragments-per-file))
                                           def-forms)]
      {:nodes (into [ns-node] defs)
       :edges (vec (concat contains-edges require-edges))
       :chunks (vec (concat [chunk] definition-chunks definition-fragment-chunks))
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
