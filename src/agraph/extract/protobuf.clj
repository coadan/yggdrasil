(ns agraph.extract.protobuf
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(defn- protobuf-package-name
  [path lines]
  (or (some (fn [line]
              (second (re-matches #"^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;\s*$"
                                  line)))
            lines)
      (common/source-module-name path)))
(defn- protobuf-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*import\s+(?:public\s+|weak\s+)?\"([^\"]+)\"\s*;\s*$"
                                      line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))
(defn- protobuf-definition-kind
  [kind]
  (case kind
    "message" :protobuf-message
    "enum" :protobuf-enum
    "service" :protobuf-service
    "rpc" :protobuf-rpc
    :protobuf-definition))
(defn- protobuf-top-level-line
  [idx line]
  (when-let [[_ kind name]
             (re-matches #"^\s*(message|enum|service)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\{.*"
                         line)]
    {:kind (protobuf-definition-kind kind)
     :name name
     :source-line (inc idx)}))
(defn- protobuf-definition-spans
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         depth 0
         out []]
    (if-let [[idx line] (first remaining)]
      (let [definition (when-not current
                         (protobuf-top-level-line idx line))
            current* (or current
                         (when definition
                           (assoc definition :lines [])))
            current** (cond-> current*
                        current* (update :lines conj [idx line]))
            depth* (if current* (+ depth (common/curly-depth-delta line)) depth)]
        (cond
          (and current** (<= depth* 0) (pos? depth))
          (recur (rest remaining) nil 0 (conj out current**))

          current**
          (recur (rest remaining) current** depth* out)

          :else
          (recur (rest remaining) nil 0 out)))
      (cond-> out current (conj current)))))
(defn- protobuf-rpcs
  [service]
  (->> (:lines service)
       (mapcat
        (fn [[idx line]]
          (map (fn [[_ name request response]]
                 {:kind :protobuf-rpc
                  :name (str (:name service) "." name)
                  :request request
                  :response response
                  :source-line (inc idx)})
               (re-seq #"\brpc\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\)\s+returns\s+\(\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\)"
                       line))))
       vec))
(def protobuf-scalar-types
  #{"bool" "bytes" "double" "fixed32" "fixed64" "float" "int32" "int64"
    "sfixed32" "sfixed64" "sint32" "sint64" "string" "uint32" "uint64"})
(defn- protobuf-normalize-reference-target
  [target]
  (str/replace target #"^\." ""))
(defn- protobuf-field-facts
  [definitions]
  (->> definitions
       (filter #(= :protobuf-message (:kind %)))
       (mapcat
        (fn [{message-name :name lines :lines}]
          (keep (fn [[idx line]]
                  (or
                   (when-let [[_ key-type value-type field-name]
                              (re-matches
                               #"^\s*map\s*<\s*([A-Za-z_][A-Za-z0-9_.]*)\s*,\s*(\.?[A-Za-z_][A-Za-z0-9_.]*)\s*>\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*[0-9]+.*"
                               line)]
                     (let [references (->> [key-type value-type]
                                           (remove #(contains?
                                                     protobuf-scalar-types
                                                     %))
                                           (map protobuf-normalize-reference-target)
                                           distinct
                                           vec)]
                       {:kind :protobuf-field
                        :label (str message-name "." field-name)
                        :parent-label message-name
                        :field-type (str "map<" key-type "," value-type ">")
                        :source-line (inc idx)
                        :references references}))
                   (when-let [[_ field-type field-name]
                              (re-matches
                               #"^\s*(?:(?:repeated|optional|required)\s+)?(\.?[A-Za-z_][A-Za-z0-9_.]*)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*[0-9]+.*"
                               line)]
                     (let [target (protobuf-normalize-reference-target field-type)]
                       {:kind :protobuf-field
                        :label (str message-name "." field-name)
                        :parent-label message-name
                        :field-type field-type
                        :source-line (inc idx)
                        :references (if (contains? protobuf-scalar-types target)
                                      []
                                      [target])}))))
                lines)))
       distinct
       vec))
(defn- protobuf-enum-value-facts
  [definitions]
  (->> definitions
       (filter #(= :protobuf-enum (:kind %)))
       (mapcat
        (fn [{enum-name :name lines :lines}]
          (keep (fn [[idx line]]
                  (when-let [[_ value-name]
                             (re-matches
                              #"^\s*([A-Z][A-Z0-9_]*)\s*=\s*[0-9]+\s*;.*"
                              line)]
                    {:kind :protobuf-enum-value
                     :label (str enum-name "." value-name)
                     :parent-label enum-name
                     :source-line (inc idx)}))
                lines)))
       distinct
       vec))
(defn- protobuf-field-reference-targets
  [definition]
  (->> (:lines definition)
       (mapcat
        (fn [[idx line]]
          (map (fn [[_ _label field-type]]
                 {:target field-type
                  :source-line (inc idx)})
               (re-seq #"^\s*(?:(repeated|optional|required)\s+)?([A-Za-z_][A-Za-z0-9_.]*)\s+[A-Za-z_][A-Za-z0-9_]*\s*=\s*[0-9]+"
                       line))))
       (remove #(contains? protobuf-scalar-types (:target %)))
       distinct
       vec))
(defn- protobuf-field-reference-edges
  [run-id id-scope file-id path package-name field-facts]
  (->> field-facts
       (mapcat
        (fn [{:keys [label source-line references]}]
          (map (fn [target]
                 (common/edge-row run-id
                                  file-id
                                  path
                                  (common/node-id id-scope
                                                  :protobuf-field
                                                  (str package-name "/" label))
                                  (common/node-id id-scope :protobuf-reference target)
                                  :references
                                  :extracted
                                  source-line))
               references)))
       distinct
       vec))
(defn- protobuf-reference-edges
  [run-id id-scope file-id path package-name definitions rpcs]
  (let [field-edges (->> definitions
                         (mapcat
                          (fn [{:keys [kind name] :as definition}]
                            (let [source-id (common/node-id id-scope
                                                            kind
                                                            (str package-name "/" name))]
                              (map (fn [{:keys [target source-line]}]
                                     (common/edge-row run-id
                                                      file-id
                                                      path
                                                      source-id
                                                      (common/node-id id-scope :protobuf-reference target)
                                                      :references
                                                      :extracted
                                                      source-line))
                                   (protobuf-field-reference-targets definition))))))
        rpc-edges (->> rpcs
                       (mapcat
                        (fn [{:keys [name request response source-line]}]
                          (let [source-id (common/node-id id-scope
                                                          :protobuf-rpc
                                                          (str package-name "/" name))]
                            [(common/edge-row run-id
                                              file-id
                                              path
                                              source-id
                                              (common/node-id id-scope :protobuf-reference request)
                                              :references
                                              :extracted
                                              source-line)
                             (common/edge-row run-id
                                              file-id
                                              path
                                              source-id
                                              (common/node-id id-scope :protobuf-reference response)
                                              :references
                                              :extracted
                                              source-line)]))))]
    (->> (concat field-edges rpc-edges)
         distinct
         vec)))
(defn extract-protobuf
  "Extract declared Protobuf packages, messages, services, RPCs, and references."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [lines (vec (str/split-lines content))
        package-name (protobuf-package-name path lines)
        package-node (common/namespace-node run-id id-scope file-id path package-name)
        definitions (protobuf-definition-spans lines)
        rpcs (->> definitions
                  (filter #(= :protobuf-service (:kind %)))
                  (mapcat protobuf-rpcs)
                  vec)
        field-facts (protobuf-field-facts definitions)
        enum-value-facts (protobuf-enum-value-facts definitions)
        definition-nodes (mapv (fn [{:keys [kind name source-line]}]
                                 (common/generic-node run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      kind
                                                      (str package-name "/" name)
                                                      source-line))
                               definitions)
        rpc-nodes (mapv (fn [{:keys [kind name source-line]}]
                          (common/generic-node run-id
                                               id-scope
                                               file-id
                                               path
                                               kind
                                               (str package-name "/" name)
                                               source-line))
                        rpcs)
        field-nodes (mapv (fn [{:keys [label source-line]}]
                            (common/generic-node run-id
                                                 id-scope
                                                 file-id
                                                 path
                                                 :protobuf-field
                                                 (str package-name "/" label)
                                                 source-line))
                          field-facts)
        enum-value-nodes (mapv (fn [{:keys [label source-line]}]
                                 (common/generic-node run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      :protobuf-enum-value
                                                      (str package-name "/" label)
                                                      source-line))
                               enum-value-facts)
        reference-nodes (->> (concat (mapcat :references field-facts)
                                     (mapcat (fn [{:keys [request response]}]
                                               [request response])
                                             rpcs)
                                     (mapcat (fn [definition]
                                               (map :target
                                                    (protobuf-field-reference-targets
                                                     definition)))
                                             definitions))
                             (remove str/blank?)
                             (map protobuf-normalize-reference-target)
                             distinct
                             (mapv #(common/generic-node run-id
                                                         id-scope
                                                         file-id
                                                         path
                                                         :protobuf-reference
                                                         %
                                                         1)))
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id package-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           (concat definition-nodes rpc-nodes))
        definition-id-by-label (into {} (map (juxt :label :xt/id)) definition-nodes)
        field-define-edges (mapv (fn [{:keys [parent-label label source-line]}]
                                   (common/edge-row run-id
                                                    file-id
                                                    path
                                                    (get definition-id-by-label
                                                         (str package-name "/" parent-label))
                                                    (common/node-id id-scope
                                                                    :protobuf-field
                                                                    (str package-name "/" label))
                                                    :defines
                                                    :extracted
                                                    source-line))
                                 field-facts)
        enum-value-define-edges (mapv (fn [{:keys [parent-label label source-line]}]
                                        (common/edge-row run-id
                                                         file-id
                                                         path
                                                         (get definition-id-by-label
                                                              (str package-name "/"
                                                                   parent-label))
                                                         (common/node-id id-scope
                                                                         :protobuf-enum-value
                                                                         (str package-name "/"
                                                                              label))
                                                         :defines
                                                         :extracted
                                                         source-line))
                                      enum-value-facts)
        import-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id package-node)
                                             (common/node-id id-scope :namespace (:target %))
                                             :imports
                                             :extracted
                                             (:source-line %))
                           (protobuf-imports lines))
        reference-edges (protobuf-reference-edges run-id
                                                  id-scope
                                                  file-id
                                                  path
                                                  package-name
                                                  definitions
                                                  rpcs)
        field-reference-edges (protobuf-field-reference-edges run-id
                                                              id-scope
                                                              file-id
                                                              path
                                                              package-name
                                                              field-facts)
        chunk-result (common/extract-text-source run-id file :protobuf-file)
        definition-chunks (mapv (fn [{:keys [kind name source-line lines]}]
                                  (common/source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str package-name "/" name)
                                   kind
                                   source-line
                                   (str/join "\n" (map second lines))))
                                definitions)
        diagnostics (common/curly-balance-diagnostics run-id
                                                      file-id
                                                      path
                                                      content
                                                      "Protobuf")]
    {:nodes (vec (concat [package-node]
                         definition-nodes
                         rpc-nodes
                         field-nodes
                         enum-value-nodes
                         reference-nodes))
     :edges (vec (concat define-edges
                         field-define-edges
                         enum-value-define-edges
                         import-edges
                         reference-edges
                         field-reference-edges))
     :chunks (vec (concat (:chunks chunk-result) definition-chunks))
     :diagnostics diagnostics}))
