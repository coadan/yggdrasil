(ns ygg.extract.avro
  (:require [ygg.extract.common :as common]
            [clojure.string :as str]))

(def avro-primitive-types
  #{"boolean" "bytes" "double" "float" "int" "long" "null" "string"})
(def avro-named-types
  #{"enum" "fixed" "record"})
(defn- avro-full-name
  [m]
  (let [name (:name m)
        namespace (:namespace m)]
    (when (string? name)
      (if (and (string? namespace)
               (not (str/includes? name ".")))
        (str namespace "." name)
        name))))
(defn- avro-schema-kind
  [schema-type]
  (case schema-type
    "record" :avro-record
    "enum" :avro-enum
    "fixed" :avro-fixed
    :avro-schema))
(defn- avro-named-schemas
  [value]
  (cond
    (map? value)
    (let [schema-type (:type value)
          named-schema (when (and (contains? avro-named-types schema-type)
                                  (avro-full-name value))
                         {:kind (avro-schema-kind schema-type)
                          :label (avro-full-name value)
                          :schema value
                          :source-line 1})]
      (cond-> (mapcat avro-named-schemas (vals value))
        named-schema (conj named-schema)))

    (vector? value)
    (mapcat avro-named-schemas value)

    :else
    []))
(defn- avro-record-fields
  [schemas]
  (->> schemas
       (filter #(= :avro-record (:kind %)))
       (mapcat
        (fn [{:keys [label schema]}]
          (->> (:fields schema)
               (keep (fn [field]
                       (when-let [field-name (:name field)]
                         {:label (str label "." field-name)
                          :record-label label
                          :type (:type field)
                          :source-line 1}))))))))
(defn- avro-type-references
  [type-value]
  (cond
    (string? type-value)
    (when-not (or (contains? avro-primitive-types type-value)
                  (contains? avro-named-types type-value))
      [type-value])

    (map? type-value)
    (avro-type-references (:type type-value))

    (vector? type-value)
    (mapcat avro-type-references type-value)

    :else
    []))
(defn- avro-json-facts
  [content]
  (when-let [value (common/read-json-value content)]
    (let [schemas (->> (avro-named-schemas value)
                       distinct
                       vec)
          fields (vec (avro-record-fields schemas))
          references (->> fields
                          (mapcat (fn [{:keys [label type source-line]}]
                                    (map (fn [target]
                                           {:source-label label
                                            :target-label target
                                            :source-line source-line})
                                         (avro-type-references type))))
                          distinct
                          vec)]
      {:schemas schemas
       :fields fields
       :references references})))
(defn- avro-idl-facts
  [content]
  (let [lines (str/split-lines content)
        protocol (->> lines
                      (map-indexed vector)
                      (some (fn [[idx line]]
                              (when-let [[_ name]
                                         (re-matches
                                          #"^\s*protocol\s+([A-Za-z_][A-Za-z0-9_.]*)\s*\{?.*"
                                          line)]
                                {:kind :avro-protocol
                                 :label name
                                 :source-line (inc idx)}))))
        schemas (->> lines
                     (map-indexed vector)
                     (keep (fn [[idx line]]
                             (when-let [[_ type-name name]
                                        (re-matches
                                         #"^\s*(record|enum|fixed)\s+([A-Za-z_][A-Za-z0-9_.]*)\b.*"
                                         line)]
                               {:kind (avro-schema-kind type-name)
                                :label name
                                :source-line (inc idx)})))
                     vec)]
    (when (or protocol (seq schemas))
      {:protocol protocol
       :schemas schemas
       :fields []
       :references []})))
(defn- avro-facts
  [content]
  (or (avro-json-facts content)
      (avro-idl-facts content)
      {:schemas []
       :fields []
       :references []
       :diagnostics [{:stage :parse
                      :line 1
                      :message "Avro extractor did not find JSON schema or IDL declarations."}]}))
(defn extract-avro
  "Extract declared Avro schemas, fields, and explicit type references."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [facts (avro-facts content)
        root-node (or (when-let [{:keys [kind label source-line]} (:protocol facts)]
                        (common/generic-node run-id id-scope file-id path kind label source-line))
                      (common/generic-node run-id id-scope file-id path :avro-file path 1))
        schema-nodes (mapv (fn [{:keys [kind label source-line]}]
                             (common/generic-node run-id id-scope file-id path
                                                  kind
                                                  label
                                                  source-line))
                           (:schemas facts))
        field-nodes (mapv (fn [{:keys [label source-line]}]
                            (common/generic-node run-id id-scope file-id path
                                                 :avro-field
                                                 label
                                                 source-line))
                          (:fields facts))
        reference-nodes (->> (:references facts)
                             (map :target-label)
                             distinct
                             (mapv #(common/generic-node run-id id-scope file-id path
                                                         :avro-reference
                                                         %
                                                         1)))
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id root-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           schema-nodes)
        schema-id-by-label (into {} (map (juxt :label :xt/id)) schema-nodes)
        field-edges (mapv (fn [{:keys [record-label label source-line]}]
                            (common/edge-row run-id
                                             file-id
                                             path
                                             (get schema-id-by-label record-label)
                                             (common/node-id id-scope :avro-field label)
                                             :defines
                                             :extracted
                                             source-line))
                          (:fields facts))
        reference-edges (mapv (fn [{:keys [source-label target-label source-line]}]
                                (common/edge-row run-id
                                                 file-id
                                                 path
                                                 (common/node-id id-scope :avro-field source-label)
                                                 (common/node-id id-scope
                                                                 :avro-reference
                                                                 target-label)
                                                 :references
                                                 :extracted
                                                 source-line))
                              (:references facts))
        chunk-result (common/extract-text-source run-id file :avro-file)
        diagnostics (mapv #(common/diagnostic-row run-id
                                                  file-id
                                                  path
                                                  (:stage %)
                                                  (:line %)
                                                  (:message %))
                          (:diagnostics facts))]
    {:nodes (vec (concat [root-node] schema-nodes field-nodes reference-nodes))
     :edges (vec (concat define-edges field-edges reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics diagnostics}))
