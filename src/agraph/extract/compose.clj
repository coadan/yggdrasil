(ns agraph.extract.compose
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(defn- compose-section-values
  [block section-name value-mode]
  (let [service-indent (some-> (:lines block) first second common/leading-spaces)]
    (loop [remaining (:lines block)
           section-indent nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [entry (common/yaml-key-line idx line)
              indent (common/leading-spaces line)
              section-indent* (cond
                                (and entry
                                     (= (+ service-indent 2) (:indent entry))
                                     (= section-name (:key entry))
                                     (str/blank? (:value entry)))
                                (:indent entry)

                                (and section-indent
                                     entry
                                     (<= (:indent entry) section-indent))
                                nil

                                :else section-indent)
              inline-values (when (and entry
                                       (= (+ service-indent 2) (:indent entry))
                                       (= section-name (:key entry))
                                       (seq (:value entry)))
                              (map (fn [value]
                                     {:value value
                                      :source-line (:source-line entry)})
                                   (common/yaml-scalar-list-values (:value entry))))
              list-value (when (and section-indent*
                                    (= indent (+ section-indent* 2)))
                           (when-let [[_ value]
                                      (re-matches #"^\s*-\s+(.+?)\s*$" line)]
                             (let [value (common/strip-yaml-scalar value)]
                               {:value (case value-mode
                                         :env-key (or (second (re-matches #"([^=\s]+)=.*" value))
                                                      value)
                                         value)
                                :source-line (inc idx)})))
              map-value (when (and section-indent*
                                   (= indent (+ section-indent* 2))
                                   entry)
                          (case value-mode
                            :env-key {:value (:key entry)
                                      :source-line (:source-line entry)}
                            :map-key {:value (:key entry)
                                      :source-line (:source-line entry)}
                            (when (seq (:value entry))
                              {:value (common/strip-yaml-scalar (:value entry))
                               :source-line (:source-line entry)})))]
          (recur (rest remaining)
                 section-indent*
                 (cond-> out
                   inline-values (into inline-values)
                   list-value (conj list-value)
                   map-value (conj map-value))))
        (vec (distinct out))))))
(defn- compose-services
  [lines]
  (->> (common/yaml-top-section-blocks lines "services")
       (mapv (fn [{:keys [label source-line] :as block}]
               (let [values (common/block-key-values block)]
                 {:label label
                  :source-line source-line
                  :image (get values "image")
                  :build (get values "build")
                  :container-name (get values "container_name")
                  :depends-on (compose-section-values block "depends_on" :map-key)
                  :ports (compose-section-values block "ports" :scalar)
                  :volumes (compose-section-values block "volumes" :scalar)
                  :networks (compose-section-values block "networks" :map-key)
                  :environment (compose-section-values block
                                                       "environment"
                                                       :env-key)})))))
(defn- compose-service-references
  [services]
  (let [service-labels (set (map :label services))]
    (->> services
         (mapcat (fn [{:keys [label source-line] :as service}]
                   (concat
                    (keep (fn [[kind target]]
                            (when (seq target)
                              {:source label
                               :target target
                               :kind kind
                               :source-line source-line
                               :relation (if (contains? service-labels target)
                                           :requires
                                           :uses)}))
                          [[:container-image (:image service)]
                           [:build-reference (:build service)]])
                    (map (fn [{:keys [value source-line]}]
                           {:source label
                            :target value
                            :kind :compose-service
                            :source-line source-line
                            :relation :requires})
                         (:depends-on service))
                    (map (fn [{:keys [value source-line]}]
                           {:source label
                            :target value
                            :kind :container-port
                            :source-line source-line
                            :relation :defines})
                         (:ports service))
                    (map (fn [{:keys [value source-line]}]
                           {:source label
                            :target value
                            :kind :runtime-volume
                            :source-line source-line
                            :relation :references})
                         (:volumes service))
                    (map (fn [{:keys [value source-line]}]
                           {:source label
                            :target value
                            :kind :compose-network
                            :source-line source-line
                            :relation :uses})
                         (:networks service))
                    (map (fn [{:keys [value source-line]}]
                           {:source label
                            :target (str label ":" value)
                            :kind :runtime-env-var
                            :source-line source-line
                            :relation :defines})
                         (:environment service)))))
         distinct
         vec)))
(defn extract-compose
  "Extract bounded Docker Compose service, image, and build facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [lines (vec (str/split-lines content))
        compose-node (common/generic-node run-id id-scope file-id path :compose-file path 1)
        services (compose-services lines)
        service-nodes (mapv (fn [{:keys [label source-line]}]
                              (common/generic-node run-id id-scope file-id path :compose-service label source-line))
                            services)
        reference-facts (compose-service-references services)
        service-labels (set (map :label services))
        reference-node-facts (->> reference-facts
                                  (remove #(and (= :compose-service (:kind %))
                                                (contains? service-labels
                                                           (:target %))))
                                  (reduce (fn [acc {:keys [kind target] :as fact}]
                                            (if (contains? acc [kind target])
                                              acc
                                              (assoc acc [kind target] fact)))
                                          {})
                                  vals)
        reference-nodes (mapv (fn [{:keys [kind target source-line]}]
                                (common/generic-node run-id id-scope file-id path kind target source-line))
                              reference-node-facts)
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id compose-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           service-nodes)
        reference-edges (mapv (fn [{:keys [source target kind source-line relation]}]
                                (common/edge-row run-id
                                                 file-id
                                                 path
                                                 (common/node-id id-scope :compose-service source)
                                                 (common/node-id id-scope kind target)
                                                 relation
                                                 :extracted
                                                 source-line))
                              reference-facts)
        chunk-result (common/extract-text-source run-id file :compose-file)]
    {:nodes (vec (concat [compose-node] service-nodes reference-nodes))
     :edges (vec (concat define-edges reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics []}))
