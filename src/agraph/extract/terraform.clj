(ns agraph.extract.terraform
  (:require [agraph.extract.common :as common]
            [agraph.text :as text]
            [clojure.string :as str]))

(defn- block-start
  [idx line]
  (or (when-let [[_ resource-type name]
                 (re-matches #"^\s*resource\s+\"([^\"]+)\"\s+\"([^\"]+)\"\s*\{\s*$"
                             line)]
        {:block-type "resource"
         :kind :terraform-resource
         :name (str resource-type "." name)
         :provider resource-type
         :source-line (inc idx)})
      (when-let [[_ resource-type name]
                 (re-matches #"^\s*data\s+\"([^\"]+)\"\s+\"([^\"]+)\"\s*\{\s*$"
                             line)]
        {:block-type "data"
         :kind :terraform-data-source
         :name (str "data." resource-type "." name)
         :provider resource-type
         :source-line (inc idx)})
      (when-let [[_ name]
                 (re-matches #"^\s*module\s+\"([^\"]+)\"\s*\{\s*$" line)]
        {:block-type "module"
         :kind :terraform-module
         :name (str "module." name)
         :source-line (inc idx)})
      (when-let [[_ name]
                 (re-matches #"^\s*provider\s+\"([^\"]+)\"\s*\{\s*$" line)]
        {:block-type "provider"
         :kind :terraform-provider
         :name (str "provider." name)
         :provider name
         :source-line (inc idx)})))
(defn- hcl-blocks
  ([lines] (hcl-blocks lines block-start))
  ([lines start-fn]
   (loop [remaining (map-indexed vector lines)
          current nil
          depth 0
          out []]
     (if-let [[idx line] (first remaining)]
       (if current
         (let [opens (count (re-seq #"\{" line))
               closes (count (re-seq #"\}" line))
               depth* (+ depth opens (- closes))
               current* (-> current
                            (update :lines conj [idx line])
                            (assoc :end-line (inc idx)))]
           (if (<= depth* 0)
             (recur (rest remaining) nil 0 (conj out current*))
             (recur (rest remaining) current* depth* out)))
         (if-let [start (start-fn idx line)]
           (let [opens (count (re-seq #"\{" line))
                 closes (count (re-seq #"\}" line))]
             (recur (rest remaining)
                    (assoc start
                           :lines [[idx line]]
                           :end-line (inc idx))
                    (+ opens (- closes))
                    out))
           (recur (rest remaining) nil 0 out)))
       out))))
(defn- hcl-reference-targets
  [lines]
  (->> lines
       (mapcat (fn [[idx line]]
                 (map (fn [[_ target]]
                        {:target target
                         :source-line (inc idx)})
                      (re-seq #"\b([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_-]*)+)\b"
                              line))))
       distinct
       vec))
(defn- hcl-reference-prefixes
  [target]
  (let [parts (str/split target #"\.")]
    (->> (range (count parts) 1 -1)
         (map #(str/join "." (take % parts))))))
(defn- hcl-reference-target-id
  [node-by-name target]
  (some #(get node-by-name %) (hcl-reference-prefixes target)))
(defn- hcl-string-attr
  [lines attr]
  (some (fn [[idx line]]
          (when-let [[_ value]
                     (re-matches (re-pattern (str "^\\s*" attr "\\s*=\\s*\"([^\"]+)\"\\s*$"))
                                 line)]
            {:value value
             :source-line (inc idx)}))
        lines))
(defn- hcl-ref-attr
  [lines attr]
  (some (fn [[idx line]]
          (when-let [[_ value]
                     (re-matches (re-pattern (str "^\\s*" attr "\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_-]*)+)\\s*$"))
                                 line)]
            {:value value
             :source-line (inc idx)}))
        lines))
(defn- terraform-chunks
  [run-id id-scope file-id path blocks]
  (->> blocks
       (filter #(contains? #{"variable" "output"} (:block-type %)))
       (mapv (fn [{:keys [block-type name source-line lines]}]
               (let [label (if (contains? #{"variable" "output"} block-type)
                             name
                             (str block-type "." name))
                     text (->> lines (map second) (str/join "\n"))]
                 {:xt/id (common/chunk-id id-scope path label source-line)
                  :file-id file-id
                  :path path
                  :kind :terraform-block
                  :label label
                  :text text
                  :tokens (text/tokenize (str label "\n" text))
                  :source-line source-line
                  :active? true
                  :run-id run-id})))))
(defn- terraform-extra-block-start
  [idx line]
  (or (when-let [[_ block-type name]
                 (re-matches #"^\s*(variable|output)\s+\"([^\"]+)\"\s*\{\s*$" line)]
        {:block-type block-type
         :kind (case block-type
                 "variable" :terraform-variable
                 "output" :terraform-output)
         :name (case block-type
                 "variable" (str "var." name)
                 "output" (str "output." name))
         :source-line (inc idx)})
      (block-start idx line)))
(defn- terraform-blocks
  [lines]
  (hcl-blocks lines terraform-extra-block-start))
(defn- terraform-block-facts
  [blocks]
  (->> blocks
       (mapcat
        (fn [{:keys [kind name lines]}]
          (let [module-source (when (= :terraform-module kind)
                                (hcl-string-attr lines "source"))
                provider-alias (when (= :terraform-provider kind)
                                 (hcl-string-attr lines "alias"))
                provider-use (when (contains? #{:terraform-resource :terraform-data-source} kind)
                               (hcl-ref-attr lines "provider"))]
            (cond-> []
              module-source
              (conj {:source-kind kind
                     :source-name name
                     :target-kind :terraform-module-source
                     :target-label (:value module-source)
                     :relation :uses
                     :source-line (:source-line module-source)})

              provider-alias
              (conj {:source-kind kind
                     :source-name name
                     :target-kind :terraform-provider-alias
                     :target-label (str name "." (:value provider-alias))
                     :relation :defines
                     :source-line (:source-line provider-alias)})

              provider-use
              (conj {:source-kind kind
                     :source-name name
                     :target-kind :terraform-provider-alias
                     :target-label (str "provider." (:value provider-use))
                     :relation :uses
                     :source-line (:source-line provider-use)})))))
       distinct
       vec))
(defn extract-terraform
  "Extract declared Terraform/HCL blocks and explicit references."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [lines (vec (str/split-lines content))
        file-node (common/generic-node run-id id-scope file-id path :terraform-file path 1)
        blocks (terraform-blocks lines)
        graph-blocks (filter :kind blocks)
        block-facts (terraform-block-facts graph-blocks)
        nodes (into [file-node]
                    (map (fn [{:keys [kind name source-line]}]
                           (common/generic-node run-id id-scope file-id path kind name source-line)))
                    graph-blocks)
        fact-nodes (mapv (fn [{:keys [target-kind target-label source-line]}]
                           (common/generic-node run-id id-scope file-id path
                                         target-kind target-label source-line))
                         block-facts)
        all-nodes (->> (concat nodes fact-nodes)
                       (reduce (fn [acc node]
                                 (assoc acc (:xt/id node) node))
                               {})
                       vals
                       vec)
        node-by-name (into {} (map (juxt :label :xt/id)) all-nodes)
        define-edges (mapv (fn [{:keys [kind name source-line]}]
                             (common/edge-row run-id
                                       file-id
                                       path
                                       (:xt/id file-node)
                                       (common/node-id id-scope kind name)
                                       :defines
                                       :extracted
                                       source-line))
                           graph-blocks)
        reference-edges (->> graph-blocks
                             (mapcat (fn [{:keys [kind name lines]}]
                                       (let [source-id (common/node-id id-scope kind name)]
                                         (keep (fn [{:keys [target source-line]}]
                                                 (when-let [target-id (hcl-reference-target-id node-by-name target)]
                                                   (when (not= source-id target-id)
                                                     (common/edge-row run-id
                                                               file-id
                                                               path
                                                               source-id
                                                               target-id
                                                               :references
                                                               :extracted
                                                               source-line))))
                                               (hcl-reference-targets lines)))))
                             distinct
                             vec)
        fact-edges (mapv (fn [{:keys [source-kind source-name target-kind target-label
                                      relation source-line]}]
                           (common/edge-row run-id
                                     file-id
                                     path
                                     (common/node-id id-scope source-kind source-name)
                                     (common/node-id id-scope target-kind target-label)
                                     relation
                                     :extracted
                                     source-line))
                         block-facts)
        chunk-result (common/extract-text-source run-id file :terraform-file)
        chunks (vec (concat (:chunks chunk-result)
                            (terraform-chunks run-id id-scope file-id path blocks)))]
    {:nodes all-nodes
     :edges (vec (concat define-edges reference-edges fact-edges))
     :chunks chunks
     :diagnostics []}))
