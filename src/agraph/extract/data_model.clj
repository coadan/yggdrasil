(ns agraph.extract.data-model
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(defn- prisma-block-line
  [idx line]
  (when-let [[_ kind name]
             (re-matches #"^\s*(datasource|generator|model|enum)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\{.*"
                         line)]
    {:kind (case kind
             "datasource" :prisma-datasource
             "generator" :prisma-generator
             "model" :prisma-model
             "enum" :prisma-enum)
     :name name
     :source-line (inc idx)}))
(defn- prisma-blocks
  [content]
  (let [lines (vec (str/split-lines content))]
    (loop [remaining (map-indexed vector lines)
           current nil
           depth 0
           out []]
      (if-let [[idx line] (first remaining)]
        (let [block (when-not current
                      (prisma-block-line idx line))
              current* (or current
                           (when block
                             (assoc block :lines [])))
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
        (cond-> out current (conj current))))))
(def prisma-scalar-types
  #{"BigInt" "Boolean" "Bytes" "DateTime" "Decimal" "Float" "Int" "Json"
    "String" "Unsupported"})
(defn- prisma-field-references
  [block]
  (when (= :prisma-model (:kind block))
    (->> (:lines block)
         (keep (fn [[idx line]]
                 (when-let [[_ _field field-type]
                            (when-not (str/includes? line "{")
                              (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\[\])?(?:\?|)\b.*"
                                          line))]
                   (when-not (contains? prisma-scalar-types field-type)
                     {:target field-type
                      :source-line (inc idx)}))))
         distinct
         vec)))
(defn- prisma-assignment-facts
  [block]
  (let [block-name (:name block)]
    (->> (:lines block)
         (mapcat
          (fn [[idx line]]
            (let [source-line (inc idx)]
              (concat
               (when-let [[_ value]
                          (re-matches #"^\s*provider\s*=\s*\"([^\"]+)\".*$"
                                      line)]
                 [{:kind (case (:kind block)
                           :prisma-datasource :prisma-datasource-provider
                           :prisma-generator :prisma-generator-provider
                           :prisma-config-provider)
                   :label (str block-name ":" value)
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ key]
                          (re-matches #"^\s*url\s*=\s*env\(\"([^\"]+)\"\).*$"
                                      line)]
                 [{:kind :prisma-env-key
                   :label (str block-name ":" key)
                   :source-line source-line
                   :relation :references}])
               (when-let [[_ value]
                          (re-matches #"^\s*output\s*=\s*\"([^\"]+)\".*$"
                                      line)]
                 [{:kind :prisma-generator-output
                   :label (str block-name ":" value)
                   :source-line source-line
                   :relation :references}])))))
         distinct
         vec)))
(defn- prisma-model-field-facts
  [block]
  (when (= :prisma-model (:kind block))
    (->> (:lines block)
         (mapcat
          (fn [[idx line]]
            (when-let [[_ field-name field-type _array _optional attributes]
                       (when-not (str/includes? line "{")
                         (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s+([A-Za-z_][A-Za-z0-9_]*)(\[\])?(\?)?\s*(.*?)\s*$"
                                     line))]
              (let [source-line (inc idx)
                    label-prefix (str (:name block) "." field-name)
                    relation-field? (not (contains? prisma-scalar-types field-type))]
                (remove nil?
                        [{:kind (if relation-field?
                                  :prisma-relation-field
                                  :prisma-field)
                          :label (str label-prefix ":" field-type)
                          :source-line source-line
                          :relation :defines}
                         (when (str/includes? (str attributes) "@id")
                           {:kind :prisma-id-field
                            :label label-prefix
                            :source-line source-line
                            :relation :defines})
                         (when-let [[_ mapped]
                                    (re-find #"@map\(\"([^\"]+)\"\)" (str attributes))]
                           {:kind :prisma-column-map
                            :label (str label-prefix "=" mapped)
                            :source-line source-line
                            :relation :defines})])))))
         distinct
         vec)))
(defn- prisma-model-attribute-facts
  [block]
  (when (= :prisma-model (:kind block))
    (->> (:lines block)
         (mapcat
          (fn [[idx line]]
            (let [source-line (inc idx)]
              (concat
               (when-let [[_ mapped]
                          (re-matches #"^\s*@@map\(\"([^\"]+)\"\).*$" line)]
                 [{:kind :prisma-table-map
                   :label (str (:name block) "=" mapped)
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ fields]
                          (re-matches #"^\s*@@index\(\[([^\]]+)\].*$" line)]
                 [{:kind :prisma-index
                   :label (str (:name block) ":"
                               (str/replace fields #"\s+" ""))
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ fields]
                          (re-matches #"^\s*@@unique\(\[([^\]]+)\].*$" line)]
                 [{:kind :prisma-unique
                   :label (str (:name block) ":"
                               (str/replace fields #"\s+" ""))
                   :source-line source-line
                   :relation :defines}])))))
         distinct
         vec)))
(defn- prisma-block-facts
  [blocks]
  (->> blocks
       (mapcat (fn [block]
                 (map #(assoc % :block-kind (:kind block) :block-name (:name block))
                      (concat (prisma-assignment-facts block)
                              (prisma-model-field-facts block)
                              (prisma-model-attribute-facts block)))))
       distinct
       vec))
(defn- prisma-reference-edges
  [run-id id-scope file-id path blocks]
  (let [block-kind-by-label (into {} (map (juxt :name :kind)) blocks)]
    (->> blocks
         (mapcat
          (fn [{:keys [name kind] :as block}]
            (map (fn [{:keys [target source-line]}]
                   (common/edge-row run-id
                                    file-id
                                    path
                                    (common/node-id id-scope kind name)
                                    (common/node-id id-scope
                                                    (get block-kind-by-label target :prisma-reference)
                                                    target)
                                    :references
                                    :extracted
                                    source-line))
                 (prisma-field-references block))))
         distinct
         vec)))
(defn extract-prisma
  "Extract bounded Prisma datasource, generator, model, enum, and relation facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [schema-node (common/generic-node run-id id-scope file-id path :prisma-file path 1)
        blocks (prisma-blocks content)
        block-nodes (mapv (fn [{:keys [kind name source-line]}]
                            (common/generic-node run-id id-scope file-id path kind name source-line))
                          blocks)
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id schema-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           block-nodes)
        fact-rows (prisma-block-facts blocks)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (common/generic-node run-id id-scope file-id path
                                                kind label source-line))
                         fact-rows)
        fact-edges (mapv (fn [{:keys [block-kind block-name kind label
                                      source-line relation]}]
                           (common/edge-row run-id
                                            file-id
                                            path
                                            (common/node-id id-scope block-kind block-name)
                                            (common/node-id id-scope kind label)
                                            (or relation :defines)
                                            :extracted
                                            source-line))
                         fact-rows)
        reference-edges (prisma-reference-edges run-id id-scope file-id path blocks)
        chunk-result (common/extract-text-source run-id file :prisma-file)
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
                                blocks)
        diagnostics (common/curly-balance-diagnostics run-id file-id path content "Prisma")]
    {:nodes (vec (concat [schema-node] block-nodes fact-nodes))
     :edges (vec (concat define-edges fact-edges reference-edges))
     :chunks (vec (concat (:chunks chunk-result) definition-chunks))
     :diagnostics diagnostics}))
(def dbt-path-sections
  #{"analysis-paths" "macro-paths" "model-paths" "seed-paths"
    "snapshot-paths" "test-paths"})

(defn- dbt-project-facts
  [content path]
  (let [project-name (common/yaml-top-level-value content "name")
        profile-name (common/yaml-top-level-value content "profile")
        version (common/yaml-top-level-value content "version")
        config-version (common/yaml-top-level-value content "config-version")
        path-facts (->> (common/yaml-list-values content dbt-path-sections)
                        (mapv (fn [{:keys [section value source-line]}]
                                {:kind :dbt-path
                                 :label (str section "=" value)
                                 :source-line source-line
                                 :relation :defines})))]
    {:project-label (or project-name path)
     :facts (vec (concat
                  (cond-> []
                    project-name (conj {:kind :dbt-project-name
                                        :label project-name
                                        :source-line 1
                                        :relation :defines})
                    profile-name (conj {:kind :dbt-profile
                                        :label profile-name
                                        :source-line 1
                                        :relation :references})
                    version (conj {:kind :dbt-config
                                   :label (str "version=" version)
                                   :source-line 1
                                   :relation :defines})
                    config-version (conj {:kind :dbt-config
                                          :label (str "config-version="
                                                      config-version)
                                          :source-line 1
                                          :relation :defines}))
                  path-facts))
     :diagnostics (when-not project-name
                    [{:stage "parse"
                      :line 1
                      :message "dbt project file did not declare a top-level name."}])}))
(defn- dbt-package-facts
  [content]
  (let [lines (vec (str/split-lines content))]
    (->> lines
         (map-indexed vector)
         (keep (fn [[idx line]]
                 (when-let [[_ package-name]
                            (or (re-matches #"^\s*-\s+package:\s+(.+?)\s*$" line)
                                (re-matches #"^\s*-\s+git:\s+(.+?)\s*$" line))]
                   (common/package-fact {:ecosystem :dbt
                                         :package-name (common/strip-yaml-scalar package-name)
                                         :source-line (inc idx)}))))
         distinct
         vec)))
(defn- dbt-profile-facts
  [content path]
  (let [lines (vec (str/split-lines content))
        reserved #{"config" "target" "outputs"}
        profiles (->> lines
                      (map-indexed vector)
                      (keep (fn [[idx line]]
                              (when-let [[_ key]
                                         (re-matches #"^([A-Za-z0-9_-]+):\s*$" line)]
                                (when-not (contains? reserved key)
                                  {:kind :dbt-profile
                                   :label key
                                   :source-line (inc idx)
                                   :relation :defines}))))
                      distinct)
        targets (->> lines
                     (map-indexed vector)
                     (keep (fn [[idx line]]
                             (when-let [[_ target]
                                        (re-matches #"^\s*target:\s+(.+?)\s*$" line)]
                               {:kind :dbt-target
                                :label (common/strip-yaml-scalar target)
                                :source-line (inc idx)
                                :relation :references})))
                     distinct)
        outputs (->> lines
                     (map-indexed vector)
                     (keep (fn [[idx line]]
                             (when-let [[_ output]
                                        (re-matches #"^\s{4}([A-Za-z0-9_-]+):\s*$" line)]
                               {:kind :dbt-output
                                :label output
                                :source-line (inc idx)
                                :relation :defines})))
                     distinct)
        adapters (->> lines
                      (map-indexed vector)
                      (keep (fn [[idx line]]
                              (when-let [[_ adapter]
                                         (re-matches #"^\s*type:\s+(.+?)\s*$" line)]
                                {:kind :dbt-adapter
                                 :label (common/strip-yaml-scalar adapter)
                                 :source-line (inc idx)
                                 :relation :uses})))
                      distinct)]
    {:project-label path
     :facts (vec (concat profiles targets outputs adapters))
     :diagnostics []}))
(def dbt-schema-sections
  {"models" :dbt-model
   "sources" :dbt-source
   "exposures" :dbt-exposure
   "metrics" :dbt-metric
   "semantic_models" :dbt-semantic-model
   "tests" :dbt-test})
(defn- dbt-schema-facts
  [content path]
  (let [lines (vec (str/split-lines content))]
    {:project-label path
     :facts
     (loop [remaining (map-indexed vector lines)
            section nil
            out []]
       (if-let [[idx line] (first remaining)]
         (cond
           (when-let [[_ key] (re-matches #"^([A-Za-z_][A-Za-z0-9_-]*):\s*$" line)]
             (contains? dbt-schema-sections key))
           (let [[_ key] (re-matches #"^([A-Za-z_][A-Za-z0-9_-]*):\s*$" line)]
             (recur (rest remaining) key out))

           (and section
                (re-matches #"^\s*-\s+name:\s+(.+?)\s*$" line))
           (let [[_ value] (re-matches #"^\s*-\s+name:\s+(.+?)\s*$" line)
                 kind (get dbt-schema-sections section)]
             (recur (rest remaining)
                    section
                    (conj out {:kind kind
                               :label (common/strip-yaml-scalar value)
                               :source-line (inc idx)
                               :relation :defines})))

           (and section
                (= "tests" section)
                (re-matches #"^\s*-\s+([A-Za-z0-9_.-]+)(?:\s*:.*)?\s*$" line))
           (let [[_ value] (re-matches #"^\s*-\s+([A-Za-z0-9_.-]+)(?:\s*:.*)?\s*$" line)]
             (recur (rest remaining)
                    section
                    (conj out {:kind :dbt-test
                               :label value
                               :source-line (inc idx)
                               :relation :defines})))

           (and (not (str/blank? (str/trim line)))
                (zero? (common/leading-spaces line)))
           (recur (rest remaining) nil out)

           :else
           (recur (rest remaining) section out))
         (vec (distinct out))))
     :diagnostics []}))
(defn- dbt-config-kind
  [path content]
  (let [filename (common/manifest-name path)]
    (cond
      (contains? #{"dbt_project.yml" "dbt_project.yaml"} filename) :project
      (contains? #{"profiles.yml" "profiles.yaml"} filename) :profile
      (contains? #{"packages.yml" "packages.yaml"} filename) :packages
      (re-find #"(?m)^(?:models|sources|exposures|metrics|semantic_models|tests):\s*$"
               content) :schema
      :else :project)))
(defn extract-dbt
  "Extract bounded dbt project configuration facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [{:keys [project-label facts diagnostics]}
        (case (dbt-config-kind path content)
          :profile (dbt-profile-facts content path)
          :packages {:project-label path
                     :facts (dbt-package-facts content)
                     :diagnostics []}
          :schema (dbt-schema-facts content path)
          (dbt-project-facts content path))
        project-node (common/generic-node run-id id-scope file-id path
                                          :dbt-project project-label 1)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (common/generic-node run-id id-scope file-id path
                                                kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (common/edge-row run-id
                                            file-id
                                            path
                                            (:xt/id project-node)
                                            (common/node-id id-scope kind label)
                                            relation
                                            :extracted
                                            source-line))
                         facts)
        chunk-result (common/extract-text-source run-id file :dbt-file)
        diagnostic-rows (mapv #(common/diagnostic-row run-id
                                                      file-id
                                                      path
                                                      (:stage %)
                                                      (:line %)
                                                      (:message %))
                              diagnostics)]
    {:nodes (into [project-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics diagnostic-rows}))
