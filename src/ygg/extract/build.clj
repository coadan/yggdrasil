(ns ygg.extract.build
  (:require [ygg.extract.common :as common]
            [clojure.string :as str]))

(defn- make-targets
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target deps]
                          (re-matches #"^([A-Za-z0-9_.%/@-]+)\s*:\s*([^=].*)?$"
                                      line)]
                 (when-not (str/starts-with? target ".")
                   {:kind :build-target
                    :label target
                    :source-line (inc idx)
                    :deps (->> (str/split (or deps "") #"\s+")
                               (map str/trim)
                               (remove str/blank?)
                               vec)}))))
       vec))
(defn- cmake-targets
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (or (when-let [[_ target deps]
                              (re-matches #"(?i)^\s*add_(?:executable|library)\s*\(\s*([A-Za-z0-9_.@+-]+)\s*(.*?)\)\s*$"
                                          line)]
                     {:kind :build-target
                      :label target
                      :source-line (inc idx)
                      :deps (->> (str/split (or deps "") #"\s+")
                                 (remove str/blank?)
                                 vec)})
                   (when-let [[_ target deps]
                              (re-matches #"(?i)^\s*target_link_libraries\s*\(\s*([A-Za-z0-9_.@+-]+)\s+(.*?)\)\s*$"
                                          line)]
                     {:kind :build-target-ref
                      :label target
                      :source-line (inc idx)
                      :deps (->> (str/split (or deps "") #"\s+")
                                 (remove str/blank?)
                                 vec)}))))
       vec))
(defn- bazel-targets
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (if current
        (let [attr (or (some-> (re-find #"\b(deps|dependencies|srcs|sources|data|outs|visibility)\s*=\s*\[" line)
                               second
                               {"deps" :deps
                                "dependencies" :deps
                                "srcs" :srcs
                                "sources" :srcs
                                "data" :data
                                "outs" :outs
                                "visibility" :visibility})
                       (:attr current))
              values (mapv second (re-seq #"\"([^\"\s]+)\"" line))
              attr* (when-not (re-find #"\]" line) attr)
              name (or (:label current)
                       (some-> (re-find #"\bname\s*=\s*\"([^\"]+)\"" line)
                               second))
              current* (cond-> (assoc current :label name :attr attr*)
                         (= :deps attr) (update :deps into values)
                         (= :srcs attr) (update :srcs into values)
                         (= :data attr) (update :data into values)
                         (= :outs attr) (update :outs into values)
                         (= :visibility attr) (update :visibility into values))]
          (if (re-find #"\)" line)
            (recur (rest remaining)
                   nil
                   (cond-> out
                     (:label current*)
                     (conj (dissoc current* :attr))))
            (recur (rest remaining) current* out)))
        (if-let [[_ rule] (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*$" line)]
          (recur (rest remaining)
                 {:kind :build-target
                  :rule rule
                  :label nil
                  :source-line (inc idx)
                  :deps []
                  :srcs []
                  :data []
                  :outs []
                  :visibility []}
                 out)
          (recur (rest remaining) nil out)))
      out)))
(defn- bazel-dep-label
  [dep]
  (str/replace dep #"^:" ""))
(defn- pants-toml-scalar-value
  [value]
  (let [trimmed (-> value
                    (str/replace #"\s+#.*$" "")
                    str/trim)]
    (or (second (re-matches #"\"([^\"]*)\"" trimmed))
        (second (re-matches #"'([^']*)'" trimmed))
        (when-not (str/blank? trimmed)
          trimmed))))
(defn- pants-toml-entries
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         section nil
         pending nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          pending
          (let [pending* (update pending :value str "\n" line)]
            (if (str/includes? line "]")
              (recur (rest remaining)
                     section
                     nil
                     (conj out pending*))
              (recur (rest remaining) section pending* out)))

          (or (str/blank? trimmed)
              (str/starts-with? trimmed "#"))
          (recur (rest remaining) section nil out)

          (re-matches #"\[[^\]]+\]" trimmed)
          (recur (rest remaining)
                 (subs trimmed 1 (dec (count trimmed)))
                 nil
                 out)

          :else
          (if-let [[_ key value]
                   (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*=\s*(.*?)\s*$"
                               line)]
            (let [entry {:section section
                         :key key
                         :value value
                         :source-line (inc idx)}]
              (if (and (str/starts-with? (str/trim value) "[")
                       (not (str/includes? value "]")))
                (recur (rest remaining) section entry out)
                (recur (rest remaining) section nil (conj out entry))))
            (recur (rest remaining) section nil out))))
      out)))
(defn- pants-toml-facts
  [content]
  (->> (pants-toml-entries content)
       (mapcat
        (fn [{:keys [section key value source-line]}]
          (let [values (if (str/starts-with? (str/trim value) "[")
                         (common/toml-array-strings value)
                         (some-> (pants-toml-scalar-value value) vector))]
            (case key
              "backend_packages"
              (map (fn [entry]
                     {:kind :build-plugin
                      :label entry
                      :source-line source-line
                      :relation :uses})
                   values)

              "root_patterns"
              (map (fn [entry]
                     {:kind :build-source-root
                      :label entry
                      :source-line source-line
                      :relation :references})
                   values)

              (map (fn [entry]
                     {:kind :build-setting
                      :label (str section "." key "=" entry)
                      :source-line source-line
                      :relation :defines})
                   values)))))
       (remove nil?)
       distinct
       vec))
(defn- build-targets
  [path lines]
  (let [filename (common/manifest-name path)]
    (cond
      (contains? #{"makefile" "gnumakefile"} filename)
      (make-targets lines)

      (or (= "cmakelists.txt" filename)
          (str/ends-with? filename ".cmake"))
      (cmake-targets lines)

      (contains? #{"build" "build.bazel" "workspace" "module.bazel" "buck"} filename)
      (bazel-targets lines)

      :else [])))
(defn- build-reference-edges
  [run-id id-scope file-id path targets]
  (let [target-labels (set (keep #(when (= :build-target (:kind %)) (:label %))
                                 targets))]
    (->> targets
         (mapcat
          (fn [{:keys [label deps source-line]}]
            (when (and (seq deps) label)
              (map (fn [dep]
                     (let [dep-label (bazel-dep-label dep)]
                       (common/edge-row run-id
                                        file-id
                                        path
                                        (common/node-id id-scope :build-target label)
                                        (common/node-id id-scope
                                                        (if (contains? target-labels dep-label)
                                                          :build-target
                                                          :build-reference)
                                                        dep-label)
                                        :requires
                                        :extracted
                                        source-line)))
                   (remove #{label} deps)))))
         distinct
         vec)))
(defn- build-target-facts
  [targets]
  (->> targets
       (mapcat
        (fn [{:keys [label rule source-line srcs data outs visibility]}]
          (when label
            (concat
             (when rule
               [{:source label
                 :kind :build-rule
                 :label (str label ":" rule)
                 :source-line source-line
                 :relation :uses}])
             (map (fn [source]
                    {:source label
                     :kind :build-source
                     :label source
                     :source-line source-line
                     :relation :references})
                  srcs)
             (map (fn [entry]
                    {:source label
                     :kind :build-data
                     :label entry
                     :source-line source-line
                     :relation :references})
                  data)
             (map (fn [entry]
                    {:source label
                     :kind :build-output
                     :label entry
                     :source-line source-line
                     :relation :builds})
                  outs)
             (map (fn [entry]
                    {:source label
                     :kind :build-visibility
                     :label entry
                     :source-line source-line
                     :relation :uses})
                  visibility)))))
       (remove nil?)
       distinct
       vec))
(defn- build-file-facts
  [path content]
  (let [filename (common/manifest-name path)]
    (cond
      (= "pants.toml" filename) (pants-toml-facts content)
      :else [])))
(defn- build-reference-nodes
  [run-id id-scope file-id path targets]
  (let [target-labels (set (keep #(when (= :build-target (:kind %)) (:label %))
                                 targets))]
    (->> targets
         (mapcat :deps)
         (map bazel-dep-label)
         (remove target-labels)
         (remove str/blank?)
         distinct
         (mapv #(common/generic-node run-id id-scope file-id path
                                     :build-reference % 1)))))
(defn extract-build
  "Extract declared build targets and explicit target dependencies."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [lines (vec (str/split-lines content))
        build-node (common/generic-node run-id id-scope file-id path :build-file path 1)
        targets (build-targets path lines)
        target-facts (build-target-facts targets)
        file-facts (build-file-facts path content)
        target-nodes (->> targets
                          (filter #(= :build-target (:kind %)))
                          (mapv (fn [{:keys [label source-line]}]
                                  (common/generic-node run-id
                                                       id-scope
                                                       file-id
                                                       path
                                                       :build-target
                                                       label
                                                       source-line))))
        target-fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                                  (common/generic-node run-id
                                                       id-scope
                                                       file-id
                                                       path
                                                       kind
                                                       label
                                                       source-line))
                                target-facts)
        file-fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                                (common/generic-node run-id
                                                     id-scope
                                                     file-id
                                                     path
                                                     kind
                                                     label
                                                     source-line))
                              file-facts)
        reference-nodes (build-reference-nodes run-id id-scope file-id path targets)
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id build-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           target-nodes)
        target-fact-edges (mapv (fn [{:keys [source kind label source-line relation]}]
                                  (common/edge-row run-id
                                                   file-id
                                                   path
                                                   (common/node-id id-scope :build-target source)
                                                   (common/node-id id-scope kind label)
                                                   relation
                                                   :extracted
                                                   source-line))
                                target-facts)
        file-fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                                (common/edge-row run-id
                                                 file-id
                                                 path
                                                 (:xt/id build-node)
                                                 (common/node-id id-scope kind label)
                                                 relation
                                                 :extracted
                                                 source-line))
                              file-facts)
        reference-edges (build-reference-edges run-id id-scope file-id path targets)
        chunk-result (common/extract-text-source run-id file :build-file)]
    {:nodes (vec (concat [build-node]
                         target-nodes
                         target-fact-nodes
                         file-fact-nodes
                         reference-nodes))
     :edges (vec (concat define-edges
                         target-fact-edges
                         file-fact-edges
                         reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics []}))
