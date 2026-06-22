(ns ygg.extract.task-config
  (:require [ygg.extract.common :as common]
            [clojure.string :as str]))

(defn- taskfile-task-facts
  [content]
  (->> (common/yaml-top-section-blocks (str/split-lines content) "tasks")
       (mapcat (fn [{:keys [label source-line lines]}]
                 (let [body (map second lines)
                       commands (->> body
                                     (map-indexed vector)
                                     (keep (fn [[offset line]]
                                             (or
                                              (when-let [[_ command]
                                                         (re-matches #"^\s*-\s+(.+?)\s*$" line)]
                                                {:kind :task-command
                                                 :label (str label ":" command)
                                                 :source-line (+ source-line offset)
                                                 :relation :defines})
                                              (when-let [[_ command]
                                                         (re-matches #"^\s*cmd:\s+(.+?)\s*$" line)]
                                                {:kind :task-command
                                                 :label (str label ":" command)
                                                 :source-line (+ source-line offset)
                                                 :relation :defines})))))
                       deps (->> body
                                 (map-indexed vector)
                                 (mapcat (fn [[offset line]]
                                           (or
                                            (when-let [[_ dep]
                                                       (re-matches #"^\s*-\s+([A-Za-z0-9_.-]+)\s*$" line)]
                                              [{:kind :task-dependency
                                                :label (str label "->" dep)
                                                :source-line (+ source-line offset)
                                                :relation :references}])
                                            (when-let [[_ deps]
                                                       (re-matches #"^\s*deps:\s+\[(.+?)\]\s*$" line)]
                                              (map (fn [dep]
                                                     {:kind :task-dependency
                                                      :label (str label "->"
                                                                  (common/strip-yaml-scalar dep))
                                                      :source-line (+ source-line offset)
                                                      :relation :references})
                                                   (str/split deps #",")))
                                            []))))]
                   (concat [{:kind :task
                             :label label
                             :source-line source-line
                             :relation :defines}]
                           commands
                           deps))))
       (remove nil?)
       distinct
       vec))
(defn- justfile-facts
  [content]
  (let [lines (vec (str/split-lines content))]
    (loop [remaining (map-indexed vector lines)
           current nil
           out []]
      (if-let [[idx line] (first remaining)]
        (cond
          (or (str/blank? (str/trim line))
              (str/starts-with? (str/trim line) "#"))
          (recur (rest remaining) current out)

          (and current
               (re-matches #"^\s+(.+?)\s*$" line))
          (let [[_ command] (re-matches #"^\s+(.+?)\s*$" line)]
            (recur (rest remaining)
                   current
                   (conj out {:kind :task-command
                              :label (str current ":" command)
                              :source-line (inc idx)
                              :relation :defines})))

          (and (not (str/includes? line ":="))
               (re-matches #"^([A-Za-z0-9_.-]+)(?:\s+[^:]*)?:\s*(.*?)\s*$" line))
          (let [[_ recipe deps] (re-matches #"^([A-Za-z0-9_.-]+)(?:\s+[^:]*)?:\s*(.*?)\s*$" line)
                dep-facts (->> (str/split (or deps "") #"\s+")
                               (remove str/blank?)
                               (map (fn [dep]
                                      {:kind :task-dependency
                                       :label (str recipe "->" dep)
                                       :source-line (inc idx)
                                       :relation :references})))]
            (recur (rest remaining)
                   recipe
                   (into (conj out {:kind :task
                                    :label recipe
                                    :source-line (inc idx)
                                    :relation :defines})
                         dep-facts)))

          :else
          (recur (rest remaining) nil out))
        (vec (distinct out))))))
(defn- task-runner-facts
  [{:keys [path content]}]
  (let [filename (common/manifest-name path)]
    (if (contains? #{"justfile" ".justfile"} filename)
      (justfile-facts content)
      (taskfile-task-facts content))))
(defn extract-task-runner
  "Extract bounded task runner task, recipe, dependency, and command facts."
  [run-id file]
  (common/extract-format-facts run-id
                               file
                               :task-runner-file
                               :task-runner-file
                               (task-runner-facts file)))
(defn- version-file-tool
  [filename]
  (case filename
    ".node-version" "node"
    ".python-version" "python"
    ".ruby-version" "ruby"
    nil))
(defn- plain-tool-version-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [trimmed (str/trim line)]
                 (when (and (seq trimmed)
                            (not (str/starts-with? trimmed "#")))
                   (let [[tool & versions] (str/split trimmed #"\s+")]
                     (when (and (seq tool) (seq versions))
                       {:kind :tool-version
                        :label (str tool "@" (str/join "," versions))
                        :source-line (inc idx)
                        :relation :defines}))))))
       distinct
       vec))
(defn- single-version-file-facts
  [tool content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [version (str/trim line)]
                 (when (and (seq version)
                            (not (str/starts-with? version "#")))
                   {:kind :tool-version
                    :label (str tool "@" version)
                    :source-line (inc idx)
                    :relation :defines}))))
       distinct
       vec))
(defn- unquote-toml-value
  [value]
  (-> value
      str/trim
      (str/replace #"^['\"]|['\"]$" "")
      str/trim))
(defn- mise-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         section nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (or (str/blank? trimmed)
              (str/starts-with? trimmed "#"))
          (recur (rest remaining) section out)

          (re-matches #"^\[(.+)\]$" trimmed)
          (let [[_ next-section] (re-matches #"^\[(.+)\]$" trimmed)]
            (recur (rest remaining) next-section out))

          (and (= "tools" section)
               (re-matches #"^([A-Za-z0-9_.-]+)\s*=\s*(.+?)\s*$" trimmed))
          (let [[_ tool version] (re-matches #"^([A-Za-z0-9_.-]+)\s*=\s*(.+?)\s*$" trimmed)]
            (recur (rest remaining)
                   section
                   (conj out {:kind :tool-version
                              :label (str tool "@"
                                          (unquote-toml-value version))
                              :source-line (inc idx)
                              :relation :defines})))

          (and (= "env" section)
               (re-matches #"^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+?)\s*$" trimmed))
          (let [[_ k v] (re-matches #"^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+?)\s*$" trimmed)]
            (recur (rest remaining)
                   section
                   (conj out {:kind :tool-env
                              :label (str k "=" (unquote-toml-value v))
                              :source-line (inc idx)
                              :relation :defines})))

          (and section
               (str/starts-with? section "tasks."))
          (let [task-name (subs section (count "tasks."))]
            (recur (rest remaining)
                   section
                   (conj out {:kind :task
                              :label (unquote-toml-value task-name)
                              :source-line (inc idx)
                              :relation :defines})))

          :else
          (recur (rest remaining) section out)))
      (vec (distinct out)))))
(defn- tool-version-facts
  [{:keys [path content]}]
  (let [filename (common/manifest-name path)]
    (cond
      (= ".tool-versions" filename) (plain-tool-version-facts content)
      (version-file-tool filename) (single-version-file-facts
                                    (version-file-tool filename)
                                    content)
      (contains? #{"mise.toml" ".mise.toml"} filename) (mise-facts content)
      :else [])))
(defn extract-tool-version-config
  "Extract bounded tool and version declarations."
  [run-id file]
  (common/extract-format-facts run-id
                               file
                               :tool-version-config-file
                               :tool-version-config-file
                               (tool-version-facts file)))
