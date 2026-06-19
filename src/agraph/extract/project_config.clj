(ns agraph.extract.project-config
  (:require [agraph.extract.common :as common]
            [agraph.hash :as hash]
            [agraph.text :as text]
            [charred.api :as json]
            [clojure.string :as str]))

(defn- editor-json-scalar?
  [value]
  (or (string? value)
      (number? value)
      (boolean? value)))
(defn- editor-json-setting-facts
  [m]
  (->> m
       (keep (fn [[k v]]
               (when (editor-json-scalar? v)
                 {:kind :editor-setting
                  :label (str (name k) "=" (common/json-label v))
                  :source-line 1
                  :relation :defines})))
       vec))
(defn- editor-array-values
  [value]
  (cond
    (vector? value) value
    (string? value) [value]
    :else []))
(defn- editor-extension-facts
  [extensions]
  (let [extensions (if (map? extensions) extensions {})]
    (vec (concat
          (map (fn [extension]
                 {:kind :editor-extension
                  :label (common/json-label extension)
                  :source-line 1
                  :relation :references})
               (editor-array-values (:recommendations extensions)))
          (map (fn [extension]
                 {:kind :editor-extension-block
                  :label (common/json-label extension)
                  :source-line 1
                  :relation :references})
               (editor-array-values (:unwantedRecommendations extensions)))))))
(defn- editor-task-label
  [idx task]
  (let [label (:label task)]
    (if (and (string? label) (seq label))
      label
      (str "task-" (inc idx)))))
(defn- editor-task-command
  [task]
  (let [command (:command task)]
    (when (editor-json-scalar? command)
      (common/json-label command))))
(defn- editor-task-dependencies
  [task]
  (editor-array-values (:dependsOn task)))
(defn- editor-problem-matchers
  [task]
  (editor-array-values (:problemMatcher task)))
(defn- editor-task-facts
  [tasks]
  (->> tasks
       (keep-indexed
        (fn [idx task]
          (when (map? task)
            (let [label (editor-task-label idx task)
                  command (editor-task-command task)
                  type (:type task)]
              (concat
               [{:kind :editor-task
                 :label label
                 :source-line 1
                 :relation :defines}]
               (when command
                 [{:kind :editor-task-command
                   :label (str label ":" command)
                   :source-line 1
                   :relation :defines}])
               (when (editor-json-scalar? type)
                 [{:kind :editor-task-type
                   :label (str label ":" (common/json-label type))
                   :source-line 1
                   :relation :defines}])
               (map (fn [matcher]
                      {:kind :editor-problem-matcher
                       :label (str label ":" (common/json-label matcher))
                       :source-line 1
                       :relation :references})
                    (editor-problem-matchers task))
               (map (fn [dependency]
                      {:kind :editor-task
                       :label (common/json-label dependency)
                       :source-line 1
                       :relation :references})
                    (editor-task-dependencies task)))))))
       (mapcat identity)
       distinct
       vec))
(defn- editorconfig-facts
  [content]
  (let [lines (vec (str/split-lines content))]
    (loop [remaining (map-indexed vector lines)
           section nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [trimmed (str/trim line)
              source-line (inc idx)]
          (cond
            (or (str/blank? trimmed)
                (str/starts-with? trimmed "#")
                (str/starts-with? trimmed ";"))
            (recur (rest remaining) section out)

            (re-matches #"^\[.+\]$" trimmed)
            (let [section* (subs trimmed 1 (dec (count trimmed)))]
              (recur (rest remaining)
                     section*
                     (conj out {:kind :editor-profile
                                :label section*
                                :source-line source-line
                                :relation :defines})))

            :else
            (if-let [[_ key value] (re-matches #"^\s*([^:=]+?)\s*[=:]\s*(.*?)\s*$" line)]
              (let [key (str/trim key)
                    value (str/trim value)]
                (recur (rest remaining)
                       section
                       (conj out {:kind :editor-setting
                                  :label (if (seq section)
                                           (str section ":" key "=" value)
                                           (str key "=" value))
                                  :source-line source-line
                                  :relation :defines})))
              (recur (rest remaining) section out))))
        (vec (distinct out))))))
(defn- vscode-settings-facts
  [content]
  (if-let [settings (common/read-json-map content)]
    (editor-json-setting-facts settings)
    []))
(defn- vscode-extensions-facts
  [content]
  (if-let [extensions (common/read-json-map content)]
    (editor-extension-facts extensions)
    []))
(defn- vscode-tasks
  [content]
  (let [parsed (common/read-json-map content)]
    (if (vector? (:tasks parsed)) (:tasks parsed) [])))
(defn- vscode-tasks-facts
  [content]
  (editor-task-facts (vscode-tasks content)))
(defn- workspace-facts
  [content]
  (if-let [workspace (common/read-json-map content)]
    (vec (concat
          (map (fn [folder]
                 {:kind :workspace-folder
                  :label (common/json-label (or (:name folder) (:path folder)))
                  :source-line 1
                  :relation :references})
               (filter #(or (:name %) (:path %))
                       (filter map? (editor-array-values (:folders workspace)))))
          (when (map? (:settings workspace))
            (editor-json-setting-facts (:settings workspace)))
          (when (map? (:extensions workspace))
            (editor-extension-facts (:extensions workspace)))
          (when (vector? (:tasks workspace))
            (editor-task-facts (:tasks workspace)))))
    []))
(defn- editor-config-facts
  [{:keys [path content]}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (common/manifest-name path)]
    (cond
      (= ".editorconfig" filename) (editorconfig-facts content)
      (re-find #"(^|/)\.vscode/settings\.json$" path-lower) (vscode-settings-facts content)
      (re-find #"(^|/)\.vscode/extensions\.json$" path-lower) (vscode-extensions-facts content)
      (re-find #"(^|/)\.vscode/tasks\.json$" path-lower) (vscode-tasks-facts content)
      (str/ends-with? filename ".code-workspace") (workspace-facts content)
      :else [])))
(defn- editor-task-chunks
  [run-id {:keys [id-scope file-id path content]}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (common/manifest-name path)
        parsed (when (or (re-find #"(^|/)\.vscode/tasks\.json$" path-lower)
                         (str/ends-with? filename ".code-workspace"))
                 (common/read-json-map content))
        tasks (cond
                (re-find #"(^|/)\.vscode/tasks\.json$" path-lower)
                (if (vector? (:tasks parsed)) (:tasks parsed) [])

                (str/ends-with? filename ".code-workspace")
                (if (vector? (:tasks parsed)) (:tasks parsed) [])

                :else [])]
    (->> tasks
         (keep-indexed
          (fn [idx task]
            (when (map? task)
              (let [label (editor-task-label idx task)
                    text (or (editor-task-command task)
                             (json/write-json-str task))]
                {:xt/id (common/chunk-id id-scope path label 1)
                 :file-id file-id
                 :path path
                 :kind :editor-task
                 :label label
                 :text text
                 :tokens (text/tokenize (str label "\n" text))
                 :source-line 1
                 :content-sha (hash/sha256-hex text)
                 :active? true
                 :run-id run-id}))))
         vec)))
(defn- editor-task-dependency-edges
  [run-id {:keys [id-scope file-id path content]}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (common/manifest-name path)
        parsed (when (or (re-find #"(^|/)\.vscode/tasks\.json$" path-lower)
                         (str/ends-with? filename ".code-workspace"))
                 (common/read-json-map content))
        tasks (cond
                (re-find #"(^|/)\.vscode/tasks\.json$" path-lower)
                (if (vector? (:tasks parsed)) (:tasks parsed) [])

                (str/ends-with? filename ".code-workspace")
                (if (vector? (:tasks parsed)) (:tasks parsed) [])

                :else [])]
    (->> tasks
         (map-indexed vector)
         (mapcat
          (fn [[idx task]]
            (when (map? task)
              (let [task-label (editor-task-label idx task)]
                (map (fn [dependency]
                       (common/edge-row run-id
                                 file-id
                                 path
                                 (common/node-id id-scope :editor-task task-label)
                                 (common/node-id id-scope :editor-task (common/json-label dependency))
                                 :depends-on
                                 :extracted
                                 1))
                     (editor-task-dependencies task))))))
         (remove nil?)
         distinct
         vec)))
(defn extract-editor-config
  "Extract deterministic editor and local development environment facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [root-node (common/generic-node run-id id-scope file-id path :editor-config-file path 1)
        facts (editor-config-facts file)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (common/generic-node run-id id-scope file-id path kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (common/edge-row run-id
                                     file-id
                                     path
                                     (:xt/id root-node)
                                     (common/node-id id-scope kind label)
                                     (or relation :defines)
                                     :extracted
                                     source-line))
                         facts)
        chunk-result (common/extract-text-source run-id file :editor-config-file)]
    {:nodes (vec (distinct (into [root-node] fact-nodes)))
     :edges (vec (distinct (concat fact-edges
                                   (editor-task-dependency-edges run-id file))))
     :chunks (vec (distinct (concat (:chunks chunk-result)
                                    (editor-task-chunks run-id file))))
     :diagnostics []}))
(defn- release-json-scalar-map-facts
  [m kind relation]
  (->> m
       (keep (fn [[k v]]
               (when (editor-json-scalar? v)
                 {:kind kind
                  :label (str (common/json-key-label k) "=" (common/json-label v))
                  :source-line 1
                  :relation relation})))
       vec))
(defn- release-array-values
  [value]
  (cond
    (vector? value) value
    (string? value) [value]
    :else []))
(defn- release-branch-facts
  [branches]
  (->> (release-array-values branches)
       (keep (fn [branch]
               (let [label (cond
                             (string? branch) branch
                             (map? branch) (:name branch)
                             :else nil)]
                 (when (seq label)
                   {:kind :release-branch
                    :label (common/json-label label)
                    :source-line 1
                    :relation :references}))))
       distinct
       vec))
(defn- release-plugin-label
  [plugin]
  (cond
    (string? plugin) plugin
    (vector? plugin) (first plugin)
    :else nil))
(defn- release-plugin-facts
  [plugins]
  (->> (release-array-values plugins)
       (keep release-plugin-label)
       (map (fn [plugin]
              {:kind :release-plugin
               :label (common/json-label plugin)
               :source-line 1
               :relation :uses}))
       distinct
       vec))
(defn- release-please-package-facts
  [packages]
  (->> packages
       (mapcat
        (fn [[package-path cfg]]
          (let [path-label (common/json-key-label package-path)
                package-name (when (map? cfg) (:package-name cfg))
                release-type (when (map? cfg) (:release-type cfg))
                changelog-path (when (map? cfg) (:changelog-path cfg))]
            (concat
             [{:kind :release-package
               :label path-label
               :source-line 1
               :relation :references}]
             (when (seq package-name)
               [{:kind :release-package-name
                 :label (str path-label ":" package-name)
                 :source-line 1
                 :relation :defines}])
             (when (seq release-type)
               [{:kind :release-type
                 :label (str path-label ":" release-type)
                 :source-line 1
                 :relation :defines}])
             (when (seq changelog-path)
               [{:kind :release-changelog
                 :label (str path-label ":" changelog-path)
                 :source-line 1
                 :relation :references}])))))
       distinct
       vec))
(defn- release-please-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (vec (concat
          (when (map? (:packages m))
            (release-please-package-facts (:packages m)))
          (release-json-scalar-map-facts (select-keys m [:release-type :changelog-path])
                                         :release-setting
                                         :defines)))
    []))
(defn- release-manifest-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (release-json-scalar-map-facts m :release-version :defines)
    []))
(defn- semantic-release-text-facts
  [content]
  (let [plugins (map second
                     (re-seq #"['\"](@semantic-release/[^'\"]+|semantic-release-[^'\"]+)['\"]"
                             content))
        branches (if-let [[_ body] (re-find #"(?s)\bbranches\s*:\s*\[(.*?)\]" content)]
                   (map second (re-seq #"['\"]([^'\"]+)['\"]" body))
                   [])]
    (vec (concat
          (map (fn [plugin]
                 {:kind :release-plugin
                  :label plugin
                  :source-line 1
                  :relation :uses})
               plugins)
          (map (fn [branch]
                 {:kind :release-branch
                  :label branch
                  :source-line 1
                  :relation :references})
               branches)))))
(defn- release-yaml-list-value-label
  [value map-key]
  (let [value (-> value
                  common/strip-yaml-scalar
                  (str/replace #"^-\s+" "")
                  common/strip-yaml-scalar)]
    (if-let [[_ label] (and map-key
                            (re-matches (re-pattern (str "^" map-key ":\\s*(.+?)\\s*$"))
                                        value))]
      (common/strip-yaml-scalar label)
      value)))
(defn- semantic-release-yaml-facts
  [content]
  (let [items (common/yaml-list-values content ["branches" "plugins"])]
    (vec
     (concat
      (->> items
           (filter #(= "branches" (:section %)))
           (keep (fn [{:keys [value source-line]}]
                   (let [label (release-yaml-list-value-label value "name")]
                     (when (seq label)
                       {:kind :release-branch
                        :label label
                        :source-line source-line
                        :relation :references}))))
           distinct)
      (->> items
           (filter #(= "plugins" (:section %)))
           (keep (fn [{:keys [value source-line]}]
                   (let [label (release-yaml-list-value-label value nil)]
                     (when (seq label)
                       {:kind :release-plugin
                        :label label
                        :source-line source-line
                        :relation :uses}))))
           distinct)))))
(defn- semantic-release-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (vec (concat (release-branch-facts (:branches m))
                 (release-plugin-facts (:plugins m))))
    (semantic-release-text-facts content)))
(defn- standard-version-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (vec (concat
          (when (string? (:tagPrefix m))
            [{:kind :release-tag-pattern
              :label (:tagPrefix m)
              :source-line 1
              :relation :defines}])
          (mapcat (fn [type]
                    (when (map? type)
                      (concat
                       (when (string? (:type type))
                         [{:kind :release-type
                           :label (:type type)
                           :source-line 1
                           :relation :defines}])
                       (when (string? (:section type))
                         [{:kind :changelog-section
                           :label (:section type)
                           :source-line 1
                           :relation :defines}]))))
                  (release-array-values (:types m)))))
    []))
(defn- standard-version-yaml-facts
  [content]
  (vec
   (concat
    (when-let [tag-prefix (common/yaml-top-level-value content "tagPrefix")]
      [{:kind :release-tag-pattern
        :label tag-prefix
        :source-line 1
        :relation :defines}])
    (mapcat
     (fn [block]
       (let [values (common/block-key-values block)]
         (concat
          (when-let [type (get values "type")]
            [{:kind :release-type
              :label type
              :source-line (:source-line block)
              :relation :defines}])
          (when-let [section (get values "section")]
            [{:kind :changelog-section
              :label section
              :source-line (:source-line block)
              :relation :defines}]))))
     (common/yaml-top-section-blocks (str/split-lines content) "types")))))
(defn- changeset-front-matter
  [content]
  (let [lines (vec (str/split-lines content))]
    (when (= "---" (first lines))
      (let [end-idx (->> (map-indexed vector (rest lines))
                         (some (fn [[idx line]]
                                 (when (= "---" line)
                                   (inc idx)))))]
        (when end-idx
          {:lines (subvec lines 1 end-idx)
           :body (str/join "\n" (subvec lines (inc end-idx)))})))))
(defn- changeset-facts
  [content]
  (if-let [{:keys [lines]} (changeset-front-matter content)]
    (->> lines
         (map-indexed vector)
         (mapcat
          (fn [[idx line]]
            (when-let [[_ package bump]
                       (re-matches #"^\s*['\"]?([^'\"]+?)['\"]?\s*:\s*(major|minor|patch|none)\s*$"
                                   line)]
              [{:kind :release-package
                :label package
                :source-line (+ 2 idx)
                :relation :references}
               {:kind :release-version-change
                :label (str package ":" bump)
                :source-line (+ 2 idx)
                :relation :defines}])))
         (remove nil?)
         distinct
         vec)
    []))
(defn- changeset-config-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (vec (concat
          (when (string? (:baseBranch m))
            [{:kind :release-branch
              :label (:baseBranch m)
              :source-line 1
              :relation :references}])
          (when (string? (:changelog m))
            [{:kind :release-changelog
              :label (:changelog m)
              :source-line 1
              :relation :references}])
          (release-json-scalar-map-facts (select-keys m [:commit :fixed :linked])
                                         :release-setting
                                         :defines)))
    []))
(defn- changelog-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ heading] (re-matches #"^#{1,3}\s+(.+?)\s*$" line)]
                 {:kind :changelog-section
                  :label heading
                  :source-line (inc idx)
                  :relation :defines})))
       distinct
       vec))
(defn- release-config-facts
  [{:keys [path content]}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (common/manifest-name path)]
    (cond
      (re-find #"(^|/)\.changeset/config\.json$" path-lower) (changeset-config-facts content)
      (re-find #"(^|/)\.changeset/[^/]+\.md$" path-lower) (changeset-facts content)
      (= "release-please-config.json" filename) (release-please-facts content)
      (= ".release-please-manifest.json" filename) (release-manifest-facts content)
      (contains? #{".releaserc" ".releaserc.json" ".releaserc.yaml" ".releaserc.yml"
                   "semantic-release.config.js" "semantic-release.config.cjs"
                   "semantic-release.config.mjs" "semantic-release.config.ts"}
                 filename) (if (contains? #{".releaserc.yaml" ".releaserc.yml"} filename)
                             (semantic-release-yaml-facts content)
                             (semantic-release-facts content))
      (contains? #{"standard-version.json" ".versionrc" ".versionrc.json"
                   ".versionrc.yaml" ".versionrc.yml"}
                 filename) (if (contains? #{".versionrc.yaml" ".versionrc.yml"} filename)
                             (standard-version-yaml-facts content)
                             (standard-version-facts content))
      (contains? #{"changelog.md" "changes.md"} filename) (changelog-facts content)
      :else [])))
(defn- release-chunks
  [run-id {:keys [id-scope file-id path content]}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (common/manifest-name path)]
    (cond
      (re-find #"(^|/)\.changeset/[^/]+\.md$" path-lower)
      (let [body (or (:body (changeset-front-matter content)) content)
            text (str/trim body)]
        (when (seq text)
          [{:xt/id (common/chunk-id id-scope path path 1)
            :file-id file-id
            :path path
            :kind :release-change
            :label path
            :text text
            :tokens (text/tokenize text)
            :source-line 1
            :content-sha (hash/sha256-hex text)
            :active? true
            :run-id run-id}]))

      (contains? #{"changelog.md" "changes.md"} filename)
      (let [lines (vec (str/split-lines content))]
        (->> lines
             (map-indexed vector)
             (keep (fn [[idx line]]
                     (when-let [[_ heading] (re-matches #"^#{1,3}\s+(.+?)\s*$" line)]
                       {:label heading
                        :source-line (inc idx)})))
             (map (fn [{:keys [label source-line]}]
                    {:xt/id (common/chunk-id id-scope path label source-line)
                     :file-id file-id
                     :path path
                     :kind :changelog-section
                     :label label
                     :text label
                     :tokens (text/tokenize label)
                     :source-line source-line
                     :content-sha (hash/sha256-hex label)
                     :active? true
                     :run-id run-id}))
             vec))

      :else [])))
(defn extract-release-config
  "Extract deterministic release/versioning/changelog facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [root-node (common/generic-node run-id id-scope file-id path :release-config-file path 1)
        facts (release-config-facts file)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (common/generic-node run-id id-scope file-id path kind label source-line))
                         facts)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (common/edge-row run-id
                                     file-id
                                     path
                                     (:xt/id root-node)
                                     (common/node-id id-scope kind label)
                                     (or relation :defines)
                                     :extracted
                                     source-line))
                         facts)
        chunk-result (common/extract-text-source run-id file :release-config-file)]
    {:nodes (vec (distinct (into [root-node] fact-nodes)))
     :edges (vec (distinct fact-edges))
     :chunks (vec (distinct (concat (:chunks chunk-result)
                                    (release-chunks run-id file))))
     :diagnostics []}))
