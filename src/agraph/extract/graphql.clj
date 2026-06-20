(ns agraph.extract.graphql
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(def graphql-definition-kinds
  #{"directive" "enum" "fragment" "input" "interface" "mutation" "query"
    "scalar" "schema" "subscription" "type" "union"})
(defn- graphql-definition-kind
  [kind]
  (case kind
    "type" :graphql-type
    "interface" :graphql-interface
    "input" :graphql-input
    "enum" :graphql-enum
    "union" :graphql-union
    "scalar" :graphql-scalar
    "directive" :graphql-directive
    "schema" :graphql-schema
    ("query" "mutation" "subscription") :graphql-operation
    "fragment" :graphql-fragment
    :graphql-definition))
(defn- graphql-definition-name
  [kind name]
  (case kind
    "directive" (str "@" name)
    "schema" "schema"
    name))
(defn- graphql-definition-line
  [idx line]
  (or (when-let [[_ kind name]
                 (re-matches #"^\s*(type|interface|input|enum|union|scalar)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                             line)]
        {:kind (graphql-definition-kind kind)
         :name (graphql-definition-name kind name)
         :source-line (inc idx)})
      (when-let [[_ name]
                 (re-matches #"^\s*directive\s+@([A-Za-z_][A-Za-z0-9_]*)\b.*"
                             line)]
        {:kind :graphql-directive
         :name (str "@" name)
         :source-line (inc idx)})
      (when-let [[_ kind name]
                 (re-matches #"^\s*(query|mutation|subscription)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                             line)]
        {:kind :graphql-operation
         :operation-kind (keyword kind)
         :name name
         :source-line (inc idx)})
      (when-let [[_ name target]
                 (re-matches #"^\s*fragment\s+([A-Za-z_][A-Za-z0-9_]*)\s+on\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                             line)]
        {:kind :graphql-fragment
         :name name
         :target target
         :source-line (inc idx)})
      (when (re-matches #"^\s*schema\s*\{.*" line)
        {:kind :graphql-schema
         :name "schema"
         :source-line (inc idx)})))
(def graphql-reference-exclusions
  #{"Boolean" "Float" "ID" "Int" "String"
    "enum" "extends" "fragment" "implements" "input" "interface" "mutation"
    "on" "query" "scalar" "schema" "subscription" "type" "union"})
(defn- graphql-reference-targets
  [line]
  (let [type-refs (->> (re-seq #"[!\[\]:={|]\s*([A-Z][A-Za-z0-9_]*)\b" line)
                       (map second))
        implements-refs (->> (re-seq #"\bimplements\s+([A-Z][A-Za-z0-9_&\s]*)" line)
                             (map second)
                             (mapcat #(str/split % #"&"))
                             (map str/trim))
        fragment-refs (->> (re-seq #"\.\.\.([A-Za-z_][A-Za-z0-9_]*)" line)
                           (map second))]
    (->> (concat type-refs implements-refs fragment-refs)
         (remove str/blank?)
         (remove graphql-reference-exclusions)
         distinct
         vec)))
(defn- graphql-definition-spans
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         depth 0
         out []]
    (if-let [[idx line] (first remaining)]
      (let [definition (when-not current
                         (graphql-definition-line idx line))
            current* (or current
                         (when definition
                           (assoc definition :lines [])))
            depth* (if current*
                     (+ depth (common/curly-depth-delta line))
                     depth)
            current** (cond-> current*
                        current* (update :lines conj [idx line]))]
        (cond
          (and current** (zero? depth*) (not (str/includes? line "{")))
          (recur (rest remaining) nil 0 (conj out current**))

          (and current** (<= depth* 0) (pos? depth))
          (recur (rest remaining) nil 0 (conj out current**))

          current**
          (recur (rest remaining) current** depth* out)

          :else
          (recur (rest remaining) nil 0 out)))
      (cond-> out current (conj current)))))
(defn- graphql-reference-edges
  [run-id id-scope file-id path definitions]
  (->> definitions
       (mapcat
        (fn [{:keys [kind name target source-line lines]}]
          (let [source-id (common/node-id id-scope kind name)
                inline-targets (cond-> []
                                 target (conj target))
                line-targets (->> lines
                                  (mapcat (comp graphql-reference-targets second)))]
            (map (fn [target]
                   (common/edge-row run-id
                                    file-id
                                    path
                                    source-id
                                    (common/node-id id-scope :graphql-reference target)
                                    :references
                                    :extracted
                                    source-line))
                 (distinct (concat inline-targets line-targets))))))
       distinct
       vec))
(def graphql-field-parent-kinds
  #{:graphql-type :graphql-interface :graphql-input})
(defn- graphql-field-facts
  [definitions]
  (->> definitions
       (filter #(contains? graphql-field-parent-kinds (:kind %)))
       (mapcat
        (fn [{:keys [kind name lines]}]
          (keep (fn [[idx line]]
                  (when-let [[_ field-name]
                             (re-matches
                              #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:\([^)]*\))?\s*:\s*.+"
                              line)]
                    {:kind :graphql-field
                     :label (str name "." field-name)
                     :parent-kind kind
                     :parent-label name
                     :source-line (inc idx)
                     :line line
                     :references (graphql-reference-targets line)}))
                lines)))
       distinct
       vec))
(defn- graphql-enum-value-facts
  [definitions]
  (->> definitions
       (filter #(= :graphql-enum (:kind %)))
       (mapcat
        (fn [{:keys [name lines]}]
          (keep (fn [[idx line]]
                  (let [trimmed (str/trim line)]
                    (when (and (not (str/blank? trimmed))
                               (not (str/starts-with? trimmed "#"))
                               (not (str/includes? trimmed "{"))
                               (not (str/includes? trimmed "}")))
                      (when-let [[_ value-name]
                                 (re-matches
                                  #"^([A-Za-z_][A-Za-z0-9_]*)(?:\s+@.*)?$"
                                  trimmed)]
                        {:kind :graphql-enum-value
                         :label (str name "." value-name)
                         :parent-label name
                         :source-line (inc idx)}))))
                lines)))
       distinct
       vec))
(defn- graphql-field-reference-edges
  [run-id id-scope file-id path field-facts]
  (->> field-facts
       (mapcat
        (fn [{:keys [label source-line references]}]
          (map (fn [target]
                 (common/edge-row run-id
                                  file-id
                                  path
                                  (common/node-id id-scope :graphql-field label)
                                  (common/node-id id-scope :graphql-reference target)
                                  :references
                                  :extracted
                                  source-line))
               references)))
       distinct
       vec))
(defn extract-graphql
  "Extract declared GraphQL schema and operation facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [lines (vec (str/split-lines content))
        spec-node (common/generic-node run-id id-scope file-id path :graphql-file path 1)
        definitions (graphql-definition-spans lines)
        field-facts (graphql-field-facts definitions)
        enum-value-facts (graphql-enum-value-facts definitions)
        definition-nodes (mapv (fn [{:keys [kind name source-line]}]
                                 (common/generic-node run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      kind
                                                      name
                                                      source-line))
                               definitions)
        field-nodes (mapv (fn [{:keys [label source-line]}]
                            (common/generic-node run-id
                                                 id-scope
                                                 file-id
                                                 path
                                                 :graphql-field
                                                 label
                                                 source-line))
                          field-facts)
        enum-value-nodes (mapv (fn [{:keys [label source-line]}]
                                 (common/generic-node run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      :graphql-enum-value
                                                      label
                                                      source-line))
                               enum-value-facts)
        reference-nodes (->> (concat (mapcat :references field-facts)
                                     (mapcat (fn [{:keys [target lines]}]
                                               (distinct
                                                (concat (when target [target])
                                                        (mapcat (comp graphql-reference-targets second)
                                                                lines))))
                                             definitions))
                             distinct
                             (mapv #(common/generic-node run-id
                                                         id-scope
                                                         file-id
                                                         path
                                                         :graphql-reference
                                                         %
                                                         1)))
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id spec-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           definition-nodes)
        definition-id-by-label (into {} (map (juxt :label :xt/id)) definition-nodes)
        field-define-edges (mapv (fn [{:keys [parent-label label source-line]}]
                                   (common/edge-row run-id
                                                    file-id
                                                    path
                                                    (get definition-id-by-label parent-label)
                                                    (common/node-id id-scope :graphql-field label)
                                                    :defines
                                                    :extracted
                                                    source-line))
                                 field-facts)
        enum-value-define-edges (mapv (fn [{:keys [parent-label label source-line]}]
                                        (common/edge-row run-id
                                                         file-id
                                                         path
                                                         (get definition-id-by-label parent-label)
                                                         (common/node-id id-scope
                                                                         :graphql-enum-value
                                                                         label)
                                                         :defines
                                                         :extracted
                                                         source-line))
                                      enum-value-facts)
        reference-edges (graphql-reference-edges run-id id-scope file-id path definitions)
        field-reference-edges (graphql-field-reference-edges run-id
                                                             id-scope
                                                             file-id
                                                             path
                                                             field-facts)
        chunk-result (common/extract-text-source run-id file :graphql-file)
        definition-chunks (mapv (fn [{:keys [kind name source-line lines]}]
                                  (common/source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   name
                                   kind
                                   source-line
                                   (str/join "\n" (map second lines))))
                                definitions)
        diagnostics (common/curly-balance-diagnostics run-id
                                                      file-id
                                                      path
                                                      content
                                                      "GraphQL")]
    {:nodes (vec (concat [spec-node]
                         definition-nodes
                         field-nodes
                         enum-value-nodes
                         reference-nodes))
     :edges (vec (concat define-edges
                         field-define-edges
                         enum-value-define-edges
                         reference-edges
                         field-reference-edges))
     :chunks (vec (concat (:chunks chunk-result) definition-chunks))
     :diagnostics diagnostics}))
