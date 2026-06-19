(ns agraph.extract.data-science
  (:require [agraph.extract.common :as common]
            [agraph.extract.doc :as extract.doc]
            [agraph.fs :as fs]
            [clojure.string :as str]))

(defn- data-science-block-list-values
  [block key-name]
  (let [list-entry-value (fn [value]
                           (let [value (common/strip-yaml-scalar value)]
                             (or (some->> (re-matches #"^(?:path|name):\s*(.+?)\s*$"
                                                      value)
                                          second
                                          common/strip-yaml-scalar)
                                 value)))]
    (loop [remaining (:lines block)
           list-indent nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [entry (common/yaml-key-line idx line)
              indent (common/leading-spaces line)
              inline-values (when (and entry
                                       (= key-name (:key entry))
                                       (seq (:value entry)))
                              (map (fn [value]
                                     {:value value
                                      :source-line (:source-line entry)})
                                   (common/yaml-scalar-list-values (:value entry))))
              list-indent* (cond
                             (and entry
                                  (= key-name (:key entry))
                                  (str/blank? (:value entry)))
                             (:indent entry)

                             (and list-indent
                                  entry
                                  (<= (:indent entry) list-indent))
                             nil

                             :else list-indent)
              list-value (when (and list-indent*
                                    (> indent list-indent*))
                           (some->> (re-matches #"^\s*-\s+(.+?)\s*$" line)
                                    second
                                    list-entry-value))
              map-value (when (and list-indent*
                                   entry
                                   (> (:indent entry) list-indent*))
                          (if (and (= "path" (:key entry)) (seq (:value entry)))
                            (common/strip-yaml-scalar (:value entry))
                            (:key entry)))]
          (recur (rest remaining)
                 list-indent*
                 (cond-> out
                   inline-values (into inline-values)
                   list-value (conj {:value list-value
                                     :source-line (inc idx)})
                   map-value (conj {:value map-value
                                    :source-line (:source-line entry)}))))
        (vec (distinct out))))))
(defn- data-science-block-scalar
  [block key-name]
  (some (fn [[idx line]]
          (when-let [{:keys [key value source-line]} (common/yaml-key-line idx line)]
            (when (and (= key-name key) (seq value))
              {:value (common/strip-yaml-scalar value)
               :source-line source-line})))
        (:lines block)))
(defn- dvc-stage-facts
  [content]
  (->> (common/yaml-top-section-blocks (str/split-lines content) "stages")
       (mapcat
        (fn [{:keys [label source-line] :as block}]
          (concat
           [{:kind :ml-pipeline-stage
             :label label
             :source-line source-line
             :relation :defines}]
           (when-let [{cmd :value cmd-line :source-line}
                      (data-science-block-scalar block "cmd")]
             [{:kind :pipeline-command
               :label (str label ":" cmd)
               :source-line cmd-line
               :relation :uses
               :source-kind :ml-pipeline-stage
               :source label}])
           (->> (data-science-block-list-values block "deps")
                (map (fn [{:keys [value source-line]}]
                       {:kind :data-artifact
                        :label value
                        :source-line source-line
                        :relation :uses
                        :source-kind :ml-pipeline-stage
                        :source label})))
           (->> (data-science-block-list-values block "outs")
                (map (fn [{:keys [value source-line]}]
                       {:kind :data-artifact
                        :label value
                        :source-line source-line
                        :relation :produces
                        :source-kind :ml-pipeline-stage
                        :source label})))
           (->> (data-science-block-list-values block "metrics")
                (map (fn [{:keys [value source-line]}]
                       {:kind :ml-metric
                        :label value
                        :source-line source-line
                        :relation :produces
                        :source-kind :ml-pipeline-stage
                        :source label})))
           (->> (data-science-block-list-values block "params")
                (map (fn [{:keys [value source-line]}]
                       {:kind :ml-parameter
                        :label value
                        :source-line source-line
                        :relation :uses
                        :source-kind :ml-pipeline-stage
                        :source label}))))))
       distinct
       vec))
(defn- dvc-file-facts
  [content path]
  (let [stage-facts (dvc-stage-facts content)
        outs (data-science-block-list-values {:lines (map-indexed vector
                                                                  (str/split-lines content))}
                                             "outs")]
    (vec
     (concat
      [{:kind :data-science-framework
        :label "dvc"
        :source-line 1
        :relation :uses}]
      (if (seq stage-facts)
        stage-facts
        (concat
         [{:kind :data-version-file
           :label path
           :source-line 1
           :relation :defines}]
         (map (fn [{:keys [value source-line]}]
                {:kind :data-artifact
                 :label value
                 :source-line source-line
                 :relation :tracks
                 :source-kind :data-version-file
                 :source path})
              outs)))))))
(defn- mlproject-entry-point-facts
  [content]
  (->> (common/yaml-top-section-blocks (str/split-lines content) "entry_points")
       (mapcat
        (fn [{:keys [label source-line] :as block}]
          (concat
           [{:kind :mlflow-entry-point
             :label label
             :source-line source-line
             :relation :defines}]
           (when-let [{command :value command-line :source-line}
                      (data-science-block-scalar block "command")]
             [{:kind :pipeline-command
               :label (str label ":" command)
               :source-line command-line
               :relation :uses
               :source-kind :mlflow-entry-point
               :source label}])
           (->> (data-science-block-list-values block "parameters")
                (map (fn [{:keys [value source-line]}]
                       {:kind :ml-parameter
                        :label (str label ":" value)
                        :source-line source-line
                        :relation :uses
                        :source-kind :mlflow-entry-point
                        :source label}))))))
       distinct
       vec))
(defn- mlproject-facts
  [content]
  (vec
   (concat
    [{:kind :data-science-framework
      :label "mlflow"
      :source-line 1
      :relation :uses}]
    (when-let [project-name (common/yaml-top-level-value content "name")]
      [{:kind :mlflow-project
        :label project-name
        :source-line 1
        :relation :defines}])
    (when-let [conda-env (common/yaml-top-level-value content "conda_env")]
      [{:kind :ml-environment
        :label conda-env
        :source-line 1
        :relation :uses}])
    (when-let [docker-image (common/yaml-top-level-value content "image")]
      [{:kind :container-image
        :label docker-image
        :source-line 1
        :relation :uses}])
    (mlproject-entry-point-facts content))))
(defn- data-science-top-level-scalar
  [content key-name]
  (some (fn [[idx line]]
          (when-let [{:keys [indent key value source-line]} (common/yaml-key-line idx line)]
            (when (and (zero? indent) (= key-name key) (seq value))
              {:value (common/strip-yaml-scalar value)
               :source-line source-line})))
        (map-indexed vector (str/split-lines content))))
(defn- data-science-top-list-values
  [content key-name]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-section? false
         section-indent nil
         nested-list-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)
            indent (common/leading-spaces line)
            section-start? (and entry
                                (zero? (:indent entry))
                                (= key-name (:key entry)))
            section-end? (and in-section?
                              entry
                              (<= (:indent entry) section-indent)
                              (not section-start?))
            inline-values (when (and section-start? (seq (:value entry)))
                            (map (fn [value]
                                   {:value value
                                    :source-line (:source-line entry)})
                                 (common/yaml-scalar-list-values (:value entry))))
            pip-section? (and in-section?
                              (re-matches #"^\s*-\s+pip:\s*$" line))
            list-entry (when (and in-section?
                                  (or (= indent (+ section-indent 2))
                                      (and nested-list-indent
                                           (> indent nested-list-indent))))
                         (some->> (re-matches #"^\s*-\s+(.+?)\s*$" line)
                                  second
                                  common/strip-yaml-scalar))]
        (cond
          section-start?
          (recur (rest remaining)
                 true
                 (:indent entry)
                 nil
                 (into out inline-values))

          section-end?
          (recur remaining false nil nil out)

          pip-section?
          (recur (rest remaining) true section-indent indent out)

          (and list-entry (not (str/ends-with? list-entry ":")))
          (recur (rest remaining)
                 true
                 section-indent
                 nested-list-indent
                 (conj out {:value list-entry
                            :source-line (inc idx)}))

          :else
          (recur (rest remaining) in-section? section-indent nested-list-indent out)))
      (vec (distinct out)))))
(defn- conda-environment-facts
  [content path]
  (let [environment (or (:value (data-science-top-level-scalar content "name"))
                        path)
        environment-line (or (:source-line (data-science-top-level-scalar content "name"))
                             1)]
    (vec
     (concat
      [{:kind :ml-environment
        :label environment
        :source-line environment-line
        :relation :defines}]
      (->> (data-science-top-list-values content "channels")
           (map (fn [{:keys [value source-line]}]
                  {:kind :environment-channel
                   :label value
                   :source-line source-line
                   :relation :uses
                   :source-kind :ml-environment
                   :source environment})))
      (->> (data-science-top-list-values content "dependencies")
           (map (fn [{:keys [value source-line]}]
                  {:kind :environment-dependency
                   :label value
                   :source-line source-line
                   :relation :uses
                   :source-kind :ml-environment
                   :source environment})))))))
(defn- data-science-front-matter
  [content]
  (let [lines (vec (str/split-lines content))]
    (when (= "---" (first lines))
      (let [end-idx (->> (map-indexed vector (rest lines))
                         (some (fn [[idx line]]
                                 (when (= "---" line)
                                   (inc idx)))))]
        (when end-idx
          {:lines (subvec lines 1 end-idx)
           :content (str/join "\n" (subvec lines 1 end-idx))})))))
(defn- data-card-kind
  [front-matter-content]
  (cond
    (or (data-science-top-level-scalar front-matter-content "model_name")
        (data-science-top-level-scalar front-matter-content "model-name")
        (data-science-top-level-scalar front-matter-content "model_id")
        (data-science-top-level-scalar front-matter-content "model-id")
        (re-find #"(?m)^model[_-](?:index|details):\s*$" front-matter-content))
    :model-card

    (or (data-science-top-level-scalar front-matter-content "dataset_name")
        (data-science-top-level-scalar front-matter-content "dataset-name")
        (re-find #"(?m)^datasets?:\s*(?:.+)?$" front-matter-content))
    :data-card

    :else nil))
(defn- card-metadata-facts
  [card-kind card-label front-matter-content]
  (let [source-kind card-kind
        metadata-kind (case card-kind
                        :model-card :model-metadata
                        :data-card :data-metadata)]
    (->> (map-indexed vector (str/split-lines front-matter-content))
         (keep (fn [[idx line]]
                 (when-let [{:keys [indent key value]} (common/yaml-key-line idx line)]
                   (when (and (zero? indent) (seq value))
                     {:kind metadata-kind
                      :label (str key ":" (common/strip-yaml-scalar value))
                      :source-line (+ 2 idx)
                      :relation :defines
                      :source-kind source-kind
                      :source card-label}))))
         distinct
         vec)))
(defn- data-card-dataset-facts
  [card-kind card-label front-matter-content]
  (let [scalar-keys ["dataset" "datasets" "dataset_name" "dataset-name"]]
    (vec
     (distinct
      (concat
       (->> scalar-keys
            (keep #(data-science-top-level-scalar front-matter-content %))
            (map (fn [{:keys [value source-line]}]
                   {:kind :data-artifact
                    :label value
                    :source-line (inc source-line)
                    :relation :references
                    :source-kind card-kind
                    :source card-label})))
       (->> (data-science-top-list-values front-matter-content "datasets")
            (map (fn [{:keys [value source-line]}]
                   {:kind :data-artifact
                    :label value
                    :source-line (inc source-line)
                    :relation :references
                    :source-kind card-kind
                    :source card-label}))))))))
(defn- data-card-facts
  [content path]
  (if-let [{front-matter-content :content} (data-science-front-matter content)]
    (if-let [card-kind (data-card-kind front-matter-content)]
      (let [model-entry (or (data-science-top-level-scalar front-matter-content "model_name")
                            (data-science-top-level-scalar front-matter-content "model-name")
                            (data-science-top-level-scalar front-matter-content "model_id")
                            (data-science-top-level-scalar front-matter-content "model-id"))
            model-name (:value model-entry)]
        (vec
         (concat
          [{:kind card-kind
            :label path
            :source-line 1
            :relation :defines}]
          (when (seq model-name)
            [{:kind :ml-model
              :label model-name
              :source-line (inc (:source-line model-entry))
              :relation :defines
              :source-kind card-kind
              :source path}])
          (data-card-dataset-facts card-kind path front-matter-content)
          (card-metadata-facts card-kind path front-matter-content))))
      [])
    []))
(defn- data-science-facts
  [{:keys [path content]}]
  (let [filename (common/manifest-name path)]
    (case filename
      ("dvc.yaml" "dvc.yml" "dvc.lock") (dvc-file-facts content path)
      "mlproject" (mlproject-facts content)
      (cond
        (= ".dvc" (fs/extension path))
        (dvc-file-facts content path)

        (and (re-find #"(?m)^channels:\s*$" content)
             (re-find #"(?m)^dependencies:\s*$" content))
        (conda-environment-facts content path)

        (= ".md" (fs/extension path))
        (data-card-facts content path)

        :else
        []))))
(defn- data-science-base-result
  [run-id {:keys [path] :as file}]
  (when (= ".md" (fs/extension path))
    (extract.doc/extract-doc run-id (assoc file :kind :doc))))
(defn- data-science-reference-edges
  [run-id id-scope file-id path facts]
  (->> facts
       (keep (fn [{:keys [source-kind source kind label relation source-line]}]
               (when (and source-kind (seq source) (seq label))
                 (common/edge-row run-id
                           file-id
                           path
                           (common/node-id id-scope source-kind source)
                           (common/node-id id-scope kind label)
                           (or relation :references)
                           :extracted
                           source-line))))
       distinct
       vec))
(defn extract-data-science
  "Extract bounded data-science project facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [facts (data-science-facts file)
        result (common/extract-format-facts run-id
                                     file
                                     :data-science-file
                                     :data-science-file
                                     facts)
        base-result (data-science-base-result run-id file)
        reference-edges (data-science-reference-edges run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      facts)]
    (if base-result
      {:nodes (vec (distinct (concat (:nodes base-result)
                                     (:nodes result))))
       :edges (vec (distinct (concat (:edges base-result)
                                     (:edges result)
                                     reference-edges)))
       :chunks (vec (distinct (concat (:chunks base-result)
                                      (:chunks result))))
       :diagnostics (vec (concat (:diagnostics base-result)
                                 (:diagnostics result)))}
      (update result :edges #(vec (distinct (concat % reference-edges)))))))
