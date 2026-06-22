(ns ygg.extract.sbom
  (:require [ygg.extract.common :as common]
            [clojure.string :as str]))

(defn- sbom-clean-label
  [value]
  (let [label (some-> value str str/trim)]
    (when (seq label) label)))
(defn- sbom-package-label
  [package]
  (or (sbom-clean-label (:purl package))
      (sbom-clean-label (:bom-ref package))
      (when-let [name (sbom-clean-label (:name package))]
        (if-let [version (sbom-clean-label (or (:version package)
                                               (:versionInfo package)))]
          (str name "@" version)
          name))
      (sbom-clean-label (:SPDXID package))))
(defn- sbom-package-ref
  [package]
  (or (sbom-clean-label (:bom-ref package))
      (sbom-clean-label (:purl package))
      (sbom-clean-label (:SPDXID package))
      (sbom-package-label package)))
(defn- sbom-license-labels
  [package]
  (let [declared [(sbom-clean-label (:licenseConcluded package))
                  (sbom-clean-label (:licenseDeclared package))]
        cyclonedx (->> (:licenses package)
                       (mapcat (fn [entry]
                                 [(sbom-clean-label (get-in entry [:license :id]))
                                  (sbom-clean-label (get-in entry [:license :name]))
                                  (sbom-clean-label (:expression entry))])))]
    (->> (concat declared cyclonedx)
         (keep sbom-clean-label)
         (remove #(contains? #{"NOASSERTION" "NONE"} %))
         distinct
         vec)))
(defn- sbom-cyclonedx-packages
  [m]
  (let [metadata-component (get-in m [:metadata :component])]
    (vec (concat (when (map? metadata-component)
                   [metadata-component])
                 (filter map? (:components m))))))
(defn- sbom-document-label
  [m path]
  (or (sbom-clean-label (:SPDXID m))
      (sbom-clean-label (:serialNumber m))
      (sbom-clean-label (:name m))
      (some-> (get-in m [:metadata :component]) sbom-package-label)
      path))
(defn- sbom-kind
  [m]
  (cond
    (= "CycloneDX" (:bomFormat m)) :cyclonedx
    (or (:spdxVersion m) (:SPDXID m)) :spdx
    :else nil))
(defn- sbom-dependency-edges
  [kind m ref->label]
  (case kind
    :cyclonedx
    (->> (:dependencies m)
         (filter map?)
         (mapcat
          (fn [{:keys [ref dependsOn]}]
            (let [source (get ref->label (sbom-clean-label ref))]
              (when source
                (->> dependsOn
                     (keep #(when-let [target (get ref->label (sbom-clean-label %))]
                              {:source-label source
                               :target-label target})))))))
         distinct
         vec)

    :spdx
    (->> (:relationships m)
         (filter map?)
         (keep
          (fn [{:keys [spdxElementId relationshipType relatedSpdxElement]}]
            (when (= "DEPENDS_ON" relationshipType)
              (when-let [source (get ref->label (sbom-clean-label spdxElementId))]
                (when-let [target (get ref->label
                                       (sbom-clean-label relatedSpdxElement))]
                  {:source-label source
                   :target-label target})))))
         distinct
         vec)

    []))
(defn extract-sbom
  "Extract explicit SPDX/CycloneDX SBOM document, package, license, and dependency facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [m (common/read-json-map content)
        kind (sbom-kind m)
        packages (case kind
                   :cyclonedx (sbom-cyclonedx-packages m)
                   :spdx (vec (filter map? (:packages m)))
                   [])
        package-records (->> packages
                             (keep (fn [package]
                                     (when-let [label (sbom-package-label package)]
                                       {:package package
                                        :label label
                                        :ref (sbom-package-ref package)
                                        :licenses (sbom-license-labels package)})))
                             distinct
                             vec)
        ref->label (into {}
                         (keep (fn [{:keys [ref label]}]
                                 (when (seq ref)
                                   [ref label])))
                         package-records)
        document-label (when kind (sbom-document-label m path))
        document-node (when document-label
                        (common/generic-node run-id
                                             id-scope
                                             file-id
                                             path
                                             :sbom-document
                                             document-label
                                             1))
        package-nodes (->> package-records
                           (map (fn [{:keys [package label ref]}]
                                  (cond-> (common/generic-node run-id
                                                               id-scope
                                                               file-id
                                                               path
                                                               :sbom-package
                                                               label
                                                               1)
                                    (sbom-clean-label (:name package))
                                    (assoc :package-name
                                           (sbom-clean-label (:name package)))
                                    (sbom-clean-label (or (:version package)
                                                          (:versionInfo package)))
                                    (assoc :version
                                           (sbom-clean-label
                                            (or (:version package)
                                                (:versionInfo package))))
                                    (seq ref) (assoc :package-ref ref)
                                    (sbom-clean-label (:purl package))
                                    (assoc :purl (sbom-clean-label (:purl package))))))
                           vec)
        license-labels (->> package-records
                            (mapcat :licenses)
                            distinct
                            vec)
        license-nodes (mapv #(common/generic-node run-id
                                                  id-scope
                                                  file-id
                                                  path
                                                  :license-id
                                                  %
                                                  1)
                            license-labels)
        root-node (common/generic-node run-id id-scope file-id path :sbom-file path 1)
        root-id (:xt/id root-node)
        root->document (when document-node
                         [(common/edge-row run-id
                                           file-id
                                           path
                                           root-id
                                           (:xt/id document-node)
                                           :defines
                                           1.0
                                           1)])
        root->packages (mapv #(common/edge-row run-id
                                               file-id
                                               path
                                               root-id
                                               (:xt/id %)
                                               :defines
                                               1.0
                                               (:source-line %))
                             package-nodes)
        root->licenses (mapv #(common/edge-row run-id
                                               file-id
                                               path
                                               root-id
                                               (:xt/id %)
                                               :defines
                                               1.0
                                               (:source-line %))
                             license-nodes)
        package->licenses (->> package-records
                               (mapcat
                                (fn [{:keys [label licenses]}]
                                  (map (fn [license-label]
                                         (common/edge-row run-id
                                                          file-id
                                                          path
                                                          (common/node-id id-scope
                                                                          :sbom-package
                                                                          label)
                                                          (common/node-id id-scope
                                                                          :license-id
                                                                          license-label)
                                                          :licenses
                                                          1.0
                                                          1))
                                       licenses)))
                               distinct
                               vec)
        dependency-edges (->> (sbom-dependency-edges kind m ref->label)
                              (mapv (fn [{:keys [source-label target-label]}]
                                      (common/edge-row run-id
                                                       file-id
                                                       path
                                                       (common/node-id id-scope
                                                                       :sbom-package
                                                                       source-label)
                                                       (common/node-id id-scope
                                                                       :sbom-package
                                                                       target-label)
                                                       :depends-on
                                                       1.0
                                                       1))))
        chunk-result (common/extract-text-source run-id file :sbom-file)]
    {:nodes (vec (distinct (concat [root-node]
                                   (when document-node [document-node])
                                   package-nodes
                                   license-nodes)))
     :edges (vec (distinct (concat root->document
                                   root->packages
                                   root->licenses
                                   package->licenses
                                   dependency-edges)))
     :chunks (:chunks chunk-result)
     :diagnostics []}))
