(ns agraph.extract.workflow
  (:require [agraph.extract.common :as common]
            [agraph.extract.infra :as extract.infra]
            [agraph.extract.source-js :as extract.source-js]
            [agraph.extract.source-python :as extract.source-python]
            [agraph.fs :as fs]
            [clojure.string :as str]))

(defn- workflow-source-import-targets
  [path content]
  (let [lines (str/split-lines content)
        js? (contains? #{".js" ".jsx" ".mjs" ".cjs" ".ts" ".tsx" ".mts" ".cts"}
                       (fs/extension path))]
    (if js?
      (->> lines
           (map-indexed #(common/js-import-targets %1 path %2))
           (mapcat identity)
           vec)
      (->> lines
           (map-indexed vector)
           (mapcat
            (fn [[idx line]]
              (concat
               (when-let [[_ target]
                          (re-matches #"^\s*import\s+([A-Za-z_][A-Za-z0-9_.]*).*$"
                                      line)]
                 [{:target target
                   :source-line (inc idx)}])
               (when-let [[_ target]
                          (re-matches #"^\s*from\s+([A-Za-z_][A-Za-z0-9_.]*)\s+import\s+.+$"
                                      line)]
                 [{:target target
                   :source-line (inc idx)}]))))
           distinct
           vec))))
(defn- workflow-framework-for-import
  [target]
  (let [target (str target)]
    (cond
      (or (= "airflow" target) (str/starts-with? target "airflow."))
      "airflow"

      (or (= "dagster" target) (str/starts-with? target "dagster."))
      "dagster"

      (or (= "prefect" target) (str/starts-with? target "prefect."))
      "prefect"

      (or (= "temporalio" target)
          (str/starts-with? target "temporalio.")
          (str/starts-with? target "@temporalio/"))
      "temporal"

      :else nil)))
(defn- workflow-import-facts
  [path content]
  (let [imports (workflow-source-import-targets path content)]
    (vec
     (concat
      (->> imports
           (keep (fn [{:keys [target source-line]}]
                   (when-let [framework (workflow-framework-for-import target)]
                     {:kind :workflow-framework
                      :label framework
                      :source-line source-line
                      :relation :uses}))))
      (->> imports
           (filter #(common/package-reference? (:target %)))
           (map (fn [{:keys [target source-line]}]
                  {:kind :workflow-package
                   :label target
                   :source-line source-line
                   :relation :imports})))))))
(defn- workflow-py-assignment-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (let [source-line (inc idx)]
            (concat
             (when-let [[_ value]
                        (or (re-find #"\bDAG\s*\(\s*['\"]([^'\"]+)['\"]" line)
                            (re-find #"\bdag_id\s*=\s*['\"]([^'\"]+)['\"]" line))]
               [{:kind :workflow-dag
                 :label value
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ value]
                        (re-find #"\btask_id\s*=\s*['\"]([^'\"]+)['\"]" line)]
               [{:kind :workflow-task
                 :label value
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ key value]
                        (re-find #"\b(schedule_interval|schedule)\s*=\s*['\"]([^'\"]+)['\"]"
                                 line)]
               [{:kind :workflow-schedule
                 :label (str key ":" value)
                 :source-line source-line
                 :relation :uses}])))))
       distinct
       vec))
(defn- workflow-decorated-python-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         pending nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [decorator (cond
                        (re-find #"^\s*@dagster\.asset\b|^\s*@asset\b" line)
                        {:kind :workflow-asset :source-line (inc idx)}

                        (re-find #"^\s*@dagster\.op\b|^\s*@op\b" line)
                        {:kind :workflow-task :source-line (inc idx)}

                        (re-find #"^\s*@dagster\.job\b|^\s*@job\b" line)
                        {:kind :workflow-job :source-line (inc idx)}

                        (re-find #"^\s*@dagster\.schedule\b|^\s*@schedule\b" line)
                        {:kind :workflow-schedule :source-line (inc idx)}

                        (re-find #"^\s*@dagster\.sensor\b|^\s*@sensor\b" line)
                        {:kind :workflow-sensor :source-line (inc idx)}

                        (re-find #"^\s*@prefect\.flow\b|^\s*@flow\b" line)
                        {:kind :workflow :source-line (inc idx)}

                        (re-find #"^\s*@prefect\.task\b|^\s*@task\b" line)
                        {:kind :workflow-task :source-line (inc idx)}

                        (re-find #"^\s*@workflow\.defn\b" line)
                        {:kind :workflow :source-line (inc idx)}

                        (re-find #"^\s*@activity\.defn\b" line)
                        {:kind :workflow-activity :source-line (inc idx)}

                        :else nil)
            definition (or (some->> (re-matches #"^\s*(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                                                line)
                                    second)
                           (some->> (re-matches #"^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                                                line)
                                    second))]
        (cond
          decorator
          (recur (rest remaining) decorator out)

          (and pending definition)
          (recur (rest remaining)
                 nil
                 (conj out (assoc pending
                                  :label definition
                                  :relation :defines)))

          :else
          (recur (rest remaining) pending out)))
      (vec (distinct out)))))
(defn- workflow-js-facts
  [content]
  (let [temporal-workflow? (re-find #"['\"]@temporalio/workflow['\"]" content)]
    (when temporal-workflow?
      (->> (str/split-lines content)
           (map-indexed vector)
           (mapcat
            (fn [[idx line]]
              (let [source-line (inc idx)]
                (concat
                 (when-let [[_ name]
                            (re-matches #"^\s*(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_$][A-Za-z0-9_$]*)\b.*"
                                        line)]
                   [{:kind :workflow
                     :label name
                     :source-line source-line
                     :relation :defines}])
                 (when (re-find #"\bproxyActivities\s*<|\bproxyActivities\s*\(" line)
                   [{:kind :workflow-activity
                     :label (str "proxyActivities@" source-line)
                     :source-line source-line
                     :relation :references}])))))
           distinct
           vec))))
(defn- workflow-source-dependencies
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (concat
           (map (fn [[_ source target]]
                  {:source source
                   :target target
                   :source-line (inc idx)
                   :relation :precedes})
                (re-seq #"\b([A-Za-z_][A-Za-z0-9_]*)\s*>>\s*([A-Za-z_][A-Za-z0-9_]*)\b"
                        line))
           (map (fn [[_ source target]]
                  {:source target
                   :target source
                   :source-line (inc idx)
                   :relation :precedes})
                (re-seq #"\b([A-Za-z_][A-Za-z0-9_]*)\s*<<\s*([A-Za-z_][A-Za-z0-9_]*)\b"
                        line)))))
       distinct
       vec))
(defn- workflow-python-facts
  [content]
  (vec (distinct (concat (workflow-py-assignment-facts content)
                         (workflow-decorated-python-facts content)))))
(defn- workflow-doc-framework
  [doc]
  (let [api-version (some-> (extract.infra/yaml-doc-value doc "apiVersion") :value)
        kind (some-> (extract.infra/yaml-doc-value doc "kind") :value)]
    (cond
      (and (some-> api-version (str/includes? "argoproj.io"))
           (contains? #{"Workflow" "CronWorkflow" "WorkflowTemplate"} kind))
      "argo-workflows"

      (and (some-> api-version (str/includes? "tekton.dev"))
           (contains? #{"Pipeline" "Task" "PipelineRun"} kind))
      "tekton"

      :else nil)))
(defn- workflow-doc-resource-label
  [doc]
  (let [kind (some-> (extract.infra/yaml-doc-value doc "kind") :value)
        name (some-> (extract.infra/yaml-doc-metadata-name doc) :value)]
    (when (and kind name)
      (str kind "/" name))))
(defn- workflow-yaml-name-blocks
  [doc section-name]
  (loop [remaining doc
         in-section? false
         section-indent nil
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)
            top-exit? (and in-section?
                           entry
                           (<= (:indent entry) section-indent)
                           (not= section-name (:key entry)))
            section-start? (and entry (= section-name (:key entry)))
            name-start (when (and in-section?
                                  (re-matches #"^\s*-\s*name:\s*.+$" line))
                         {:label (common/strip-yaml-scalar
                                  (second (re-matches #"^\s*-\s*name:\s*(.+?)\s*$" line)))
                          :source-line (inc idx)
                          :lines [[idx line]]})]
        (cond
          section-start?
          (recur (rest remaining) true (:indent entry) nil out)

          top-exit?
          (recur (rest remaining) false nil nil (cond-> out current (conj current)))

          name-start
          (recur (rest remaining)
                 true
                 section-indent
                 name-start
                 (cond-> out current (conj current)))

          (and in-section? current)
          (recur (rest remaining)
                 true
                 section-indent
                 (update current :lines conj [idx line])
                 out)

          :else
          (recur (rest remaining) in-section? section-indent current out)))
      (cond-> out current (conj current)))))
(defn- workflow-block-list-values
  [block key-name]
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
                                  common/strip-yaml-scalar))]
        (recur (rest remaining)
               list-indent*
               (cond-> out
                 inline-values (into inline-values)
                 list-value (conj {:value list-value
                                   :source-line (inc idx)}))))
      (vec (distinct out)))))
(defn- workflow-block-scalar
  [block key-name]
  (some (fn [[idx line]]
          (when-let [{:keys [key value source-line]} (common/yaml-key-line idx line)]
            (when (and (= key-name key) (seq value))
              {:value (common/strip-yaml-scalar value)
               :source-line source-line})))
        (:lines block)))
(defn- workflow-block-nested-scalar
  [block parent-key child-key]
  (loop [remaining (:lines block)
         parent-indent nil]
    (when-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)
            parent-indent* (cond
                             (and entry
                                  (= parent-key (:key entry))
                                  (str/blank? (:value entry)))
                             (:indent entry)

                             (and parent-indent
                                  entry
                                  (<= (:indent entry) parent-indent))
                             nil

                             :else parent-indent)]
        (if (and parent-indent*
                 entry
                 (> (:indent entry) parent-indent*)
                 (= child-key (:key entry))
                 (seq (:value entry)))
          {:value (common/strip-yaml-scalar (:value entry))
           :source-line (:source-line entry)}
          (recur (rest remaining) parent-indent*))))))
(defn- workflow-doc-task-facts
  [framework resource-label doc]
  (let [section-name (case framework
                       "argo-workflows" "templates"
                       "tekton" "tasks"
                       nil)]
    (when section-name
      (->> (workflow-yaml-name-blocks doc section-name)
           (mapcat
            (fn [{:keys [label source-line] :as block}]
              (concat
               [{:kind :workflow-task
                 :label label
                 :source-line source-line
                 :relation :defines}]
               (when-let [{task-ref :value ref-line :source-line}
                          (or (workflow-block-scalar block "taskRef")
                              (workflow-block-nested-scalar block "taskRef" "name"))]
                 [{:kind :workflow-template
                   :label (str label ":" task-ref)
                   :source-line ref-line
                   :relation :references}])
               (->> (workflow-block-list-values block "dependencies")
                    (map (fn [{:keys [value source-line]}]
                           {:kind :workflow-task-dependency
                            :label (str label ":" value)
                            :source-line source-line
                            :source label
                            :target value
                            :relation :requires})))
               (->> (workflow-block-list-values block "runAfter")
                    (map (fn [{:keys [value source-line]}]
                           {:kind :workflow-task-dependency
                            :label (str label ":" value)
                            :source-line source-line
                            :source label
                            :target value
                            :relation :requires})))
               (when-let [{image :value image-line :source-line}
                          (workflow-block-scalar block "image")]
                 [{:kind :container-image
                   :label image
                   :source-line image-line
                   :relation :uses
                   :source label}]))))
           (map #(cond-> %
                   resource-label (assoc :resource-label resource-label)))
           distinct
           vec))))
(defn- workflow-manifest-facts
  [content]
  (->> (extract.infra/yaml-documents content)
       (mapcat
        (fn [doc]
          (let [framework (workflow-doc-framework doc)
                resource-label (workflow-doc-resource-label doc)
                kind-value (some-> (extract.infra/yaml-doc-value doc "kind") :value)
                kind-line (or (some-> (extract.infra/yaml-doc-value doc "kind") :source-line) 1)]
            (when framework
              (concat
               [{:kind :workflow-framework
                 :label framework
                 :source-line 1
                 :relation :uses}]
               (when resource-label
                 [{:kind (if (= "CronWorkflow" kind-value)
                           :workflow-schedule
                           :workflow)
                   :label resource-label
                   :source-line kind-line
                   :relation :defines}])
               (workflow-doc-task-facts framework resource-label doc))))))
       distinct
       vec))
(defn- workflow-prefect-yaml-facts
  [content]
  (let [project-name (common/yaml-top-level-value content "name")
        deployments (common/yaml-top-section-blocks (str/split-lines content)
                                                    "deployments")]
    (vec
     (concat
      [{:kind :workflow-framework
        :label "prefect"
        :source-line 1
        :relation :uses}]
      (when project-name
        [{:kind :workflow
          :label project-name
          :source-line 1
          :relation :defines}])
      (mapcat
       (fn [{:keys [label source-line] :as block}]
         (concat
          [{:kind :workflow-deployment
            :label label
            :source-line source-line
            :relation :defines}]
          (when-let [{entrypoint :value entrypoint-line :source-line}
                     (workflow-block-scalar block "entrypoint")]
            [{:kind :workflow-entrypoint
              :label (str label ":" entrypoint)
              :source-line entrypoint-line
              :relation :references}])
          (when-let [{cron :value cron-line :source-line}
                     (workflow-block-scalar block "cron")]
            [{:kind :workflow-schedule
              :label (str label ":" cron)
              :source-line cron-line
              :relation :uses}])
          (when-let [{pool :value pool-line :source-line}
                     (workflow-block-scalar block "work_pool")]
            [{:kind :workflow-worker-pool
              :label (str label ":" pool)
              :source-line pool-line
              :relation :uses}])))
       deployments)))))
(defn- workflow-dagster-yaml-facts
  [content]
  (vec
   (concat
    [{:kind :workflow-framework
      :label "dagster"
      :source-line 1
      :relation :uses}]
    (when-let [module-name (common/yaml-top-level-value content "module_name")]
      [{:kind :workflow-module
        :label module-name
        :source-line 1
        :relation :references}])
    (when-let [package-name (common/yaml-top-level-value content "package_name")]
      [{:kind :workflow-package
        :label package-name
        :source-line 1
        :relation :references}]))))
(defn- workflow-config-facts
  [{:keys [path content]}]
  (let [filename (common/manifest-name path)]
    (vec
     (distinct
      (concat
       (case filename
         ("prefect.yaml" "prefect.yml") (workflow-prefect-yaml-facts content)
         ("dagster.yaml" "dagster.yml") (workflow-dagster-yaml-facts content)
         [])
       (workflow-manifest-facts content))))))
(defn- workflow-source-facts
  [{:keys [path content]}]
  (vec
   (distinct
    (concat
     (workflow-import-facts path content)
     (case (fs/extension path)
       ".py" (workflow-python-facts content)
       (".js" ".jsx" ".mjs" ".cjs" ".ts" ".tsx" ".mts" ".cts") (workflow-js-facts content)
       [])))))
(defn- workflow-facts
  [{:keys [path] :as file}]
  (if (contains? #{".py" ".js" ".jsx" ".mjs" ".cjs" ".ts" ".tsx" ".mts" ".cts"}
                 (fs/extension path))
    (workflow-source-facts file)
    (workflow-config-facts file)))
(defn- workflow-base-result
  [run-id {:keys [path] :as file}]
  (case (fs/extension path)
    ".py" (extract.source-python/extract-python run-id (assoc file :kind :python))
    (".ts" ".tsx" ".mts" ".cts") (extract.source-js/extract-js-family run-id (assoc file :kind :typescript))
    (".js" ".jsx" ".mjs" ".cjs") (extract.source-js/extract-js-family run-id (assoc file :kind :javascript))
    nil))
(defn- workflow-dependency-edges
  [run-id id-scope file-id path facts]
  (->> facts
       (keep (fn [{:keys [source target source-line relation]}]
               (when (and (seq source) (seq target))
                 (common/edge-row run-id
                                  file-id
                                  path
                                  (common/node-id id-scope :workflow-task source)
                                  (common/node-id id-scope :workflow-task target)
                                  (or relation :requires)
                                  :extracted
                                  source-line))))
       distinct
       vec))
(defn extract-workflow-orchestration
  "Extract bounded workflow orchestration declarations and dependencies."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [facts (workflow-facts file)
        workflow-result (common/extract-format-facts run-id
                                                     file
                                                     :workflow-file
                                                     :workflow-file
                                                     facts)
        source-dependencies (when (= ".py" (fs/extension path))
                              (workflow-source-dependencies content))
        dependency-edges (workflow-dependency-edges run-id
                                                    id-scope
                                                    file-id
                                                    path
                                                    (concat facts
                                                            source-dependencies))
        base-result (workflow-base-result run-id file)]
    (if base-result
      {:nodes (vec (distinct (concat (:nodes base-result)
                                     (:nodes workflow-result))))
       :edges (vec (distinct (concat (:edges base-result)
                                     (:edges workflow-result)
                                     dependency-edges)))
       :chunks (vec (distinct (concat (:chunks base-result)
                                      (:chunks workflow-result))))
       :diagnostics (vec (concat (:diagnostics base-result)
                                 (:diagnostics workflow-result)))}
      (update workflow-result :edges #(vec (distinct (concat % dependency-edges)))))))
