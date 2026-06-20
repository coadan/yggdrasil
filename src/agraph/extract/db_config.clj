(ns agraph.extract.db-config
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(defn- comma-separated-values
  [value]
  (->> (str/split (str value) #",")
       (map str/trim)
       (remove str/blank?)
       vec))
(defn- flyway-config-facts
  [content]
  (->> (common/properties-assignment-lines content)
       (mapcat
        (fn [{:keys [key value source-line]}]
          (cond
            (= "flyway.locations" key)
            (map (fn [entry]
                   {:kind :flyway-location
                    :label entry
                    :source-line source-line
                    :relation :references})
                 (comma-separated-values value))

            (= "flyway.schemas" key)
            (map (fn [entry]
                   {:kind :db-schema
                    :label entry
                    :source-line source-line
                    :relation :defines})
                 (comma-separated-values value))

            (str/starts-with? key "flyway.placeholders.")
            [{:kind :flyway-placeholder
              :label (subs key (count "flyway.placeholders."))
              :source-line source-line
              :relation :defines}]

            (contains? #{"flyway.baselineOnMigrate"
                         "flyway.baselineVersion"
                         "flyway.baselineDescription"}
                       key)
            [{:kind :flyway-baseline-flag
              :label (str key "=" value)
              :source-line source-line
              :relation :defines}]

            :else [])))
       distinct
       vec))
(defn- db-config-facts
  [{:keys [path content] :as file}]
  (let [filename (common/manifest-name path)]
    (vec (concat (common/config-facts file)
                 (case filename
                   "flyway.conf" (flyway-config-facts content)
                   [])))))
(defn extract-db-config
  "Extract bounded database migration/tool configuration facts."
  [run-id file]
  (let [facts (db-config-facts file)
        config-node (common/generic-node run-id
                                         (:id-scope file)
                                         (:file-id file)
                                         (:path file)
                                         :db-config
                                         (:path file)
                                         1)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (common/generic-node run-id
                                                (:id-scope file)
                                                (:file-id file)
                                                (:path file)
                                                kind
                                                label
                                                source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (common/edge-row run-id
                                            (:file-id file)
                                            (:path file)
                                            (:xt/id config-node)
                                            (common/node-id (:id-scope file) kind label)
                                            relation
                                            :extracted
                                            source-line))
                         facts)
        chunk-result (common/extract-text-source run-id file :db-config-file)]
    {:nodes (into [config-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))
