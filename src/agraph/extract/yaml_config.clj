(ns agraph.extract.yaml-config
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(def kustomize-reference-sections
  #{"resources" "bases" "components" "patches" "patchesStrategicMerge"
    "configurations"})
(def kustomize-generator-sections
  #{"configMapGenerator" "secretGenerator"})
(defn- kustomize-facts
  [content]
  (let [items (common/yaml-section-items content
                                         (into kustomize-reference-sections
                                               (conj kustomize-generator-sections
                                                     "images")))]
    (->> items
         (keep (fn [{:keys [section value source-line]}]
                 (when (seq value)
                   (cond
                     (contains? kustomize-reference-sections section)
                     {:kind :kustomize-reference
                      :label (str section "=" value)
                      :source-line source-line
                      :relation :references}

                     (= "images" section)
                     {:kind :container-image
                      :label value
                      :source-line source-line
                      :relation :references}

                     (contains? kustomize-generator-sections section)
                     {:kind :kustomize-generator
                      :label (str section "=" value)
                      :source-line source-line
                      :relation :defines}))))
         distinct
         vec)))
(defn extract-kustomize
  "Extract bounded Kustomize resources, patches, images, and generators."
  [run-id file]
  (common/extract-format-facts run-id
                               file
                               :kustomize-file
                               :kustomize-file
                               (kustomize-facts (:content file))))
(defn- pre-commit-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (cond
                 (re-matches #"^\s*-\s+repo:\s+(.+?)\s*$" line)
                 (let [[_ repo] (re-matches #"^\s*-\s+repo:\s+(.+?)\s*$" line)]
                   {:kind :pre-commit-repo
                    :label (common/strip-yaml-scalar repo)
                    :source-line (inc idx)
                    :relation :references})

                 (re-matches #"^\s*rev:\s+(.+?)\s*$" line)
                 (let [[_ rev] (re-matches #"^\s*rev:\s+(.+?)\s*$" line)]
                   {:kind :pre-commit-rev
                    :label (common/strip-yaml-scalar rev)
                    :source-line (inc idx)
                    :relation :references})

                 (re-matches #"^\s*-\s+id:\s+(.+?)\s*$" line)
                 (let [[_ hook] (re-matches #"^\s*-\s+id:\s+(.+?)\s*$" line)]
                   {:kind :pre-commit-hook
                    :label (common/strip-yaml-scalar hook)
                    :source-line (inc idx)
                    :relation :defines}))))
       distinct
       vec))
(defn extract-pre-commit-config
  "Extract bounded pre-commit repository, revision, and hook facts."
  [run-id file]
  (common/extract-format-facts run-id
                               file
                               :pre-commit-config-file
                               :pre-commit-config-file
                               (pre-commit-facts (:content file))))
