(ns agraph.extract.package-js
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(defn package-json-project-label
  [content path]
  (or (:name (common/read-json-map content))
      path))
(defn- dependency-scope
  [k]
  (case k
    :dependencies "runtime"
    :devDependencies "development"
    :peerDependencies "peer"
    :optionalDependencies "optional"
    nil))
(defn- dependency-map-facts
  [m keys]
  (->> keys
       (mapcat (fn [k]
                 (let [deps (get m k)]
                   (when (map? deps)
                     (map (fn [[dep-name version]]
                            (common/package-fact {:ecosystem :npm
                                                  :package-name (common/json-key-label dep-name)
                                                  :version-range (when (string? version) version)
                                                  :dependency-scope (dependency-scope k)
                                                  :source-line 1}))
                          deps)))))
       (remove nil?)
       distinct
       vec))
(defn- package-script-facts
  [m]
  (let [scripts (:scripts m)]
    (if-not (map? scripts)
      []
      (->> scripts
           keys
           (map (fn [script-name]
                  {:kind :package-script
                   :label (common/json-key-label script-name)
                   :source-line 1
                   :relation :defines}))
           distinct
           vec))))
(defn- package-script-command-facts
  [m]
  (let [scripts (:scripts m)]
    (if-not (map? scripts)
      []
      (->> scripts
           (keep (fn [[script-name command]]
                   (when (string? command)
                     {:kind :package-script-command
                      :label (str (common/json-key-label script-name) "=" command)
                      :source-line 1
                      :relation :defines})))
           distinct
           vec))))
(defn- package-json-metadata-facts
  [m]
  (vec
   (concat
    (when (string? (:version m))
      [{:kind :package-version
        :label (:version m)
        :source-line 1
        :relation :defines}])
    (when (string? (:type m))
      [{:kind :package-type
        :label (:type m)
        :source-line 1
        :relation :defines}])
    (when (contains? m :private)
      [{:kind :package-private
        :label (str "private=" (:private m))
        :source-line 1
        :relation :defines}])
    (let [bin (:bin m)]
      (cond
        (string? bin)
        [{:kind :package-bin
          :label bin
          :source-line 1
          :relation :defines}]

        (map? bin)
        (map (fn [[name target]]
               {:kind :package-bin
                :label (str (common/json-key-label name) "=" target)
                :source-line 1
                :relation :defines})
             bin)

        :else []))
    (let [exports (:exports m)]
      (cond
        (string? exports)
        [{:kind :package-export
          :label exports
          :source-line 1
          :relation :defines}]

        (map? exports)
        (map (fn [[name target]]
               {:kind :package-export
                :label (str (common/json-key-label name)
                            "="
                            (if (string? target) target (pr-str target)))
                :source-line 1
                :relation :defines})
             exports)

        :else []))
    (when (map? (:engines m))
      (map (fn [[name value]]
             {:kind :package-engine
              :label (str (common/json-key-label name) "=" value)
              :source-line 1
              :relation :defines})
           (:engines m))))))
(defn- workspace-patterns
  [value]
  (cond
    (vector? value) (filterv string? value)
    (map? value) (filterv string? (:packages value))
    :else []))
(defn- package-workspace-facts
  [m]
  (->> (workspace-patterns (:workspaces m))
       (map (fn [pattern]
              {:kind :workspace-pattern
               :label pattern
               :source-line 1
               :relation :references}))
       distinct
       vec))
(defn- workspace-protocol-dependency-facts
  [m]
  (->> [:dependencies :devDependencies :peerDependencies :optionalDependencies]
       (mapcat (fn [k]
                 (let [deps (get m k)]
                   (when (map? deps)
                     (keep (fn [[dep-name version]]
                             (when (and (string? version)
                                        (str/starts-with? version "workspace:"))
                               {:kind :workspace-dependency
                                :label (str (common/json-key-label dep-name) "=" version)
                                :source-line 1
                                :relation :references}))
                           deps)))))
       (remove nil?)
       distinct
       vec))
(defn- package-manager-fact
  [m]
  (when-let [package-manager (or (:packageManager m)
                                 (:npmClient m)
                                 (:pnpmVersion m)
                                 (:yarnVersion m))]
    [{:kind :package-manager
      :label package-manager
      :source-line 1
      :relation :uses}]))
(defn package-json-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (vec (concat (dependency-map-facts m
                                       [:dependencies
                                        :devDependencies
                                        :peerDependencies
                                        :optionalDependencies])
                 (package-script-facts m)
                 (package-script-command-facts m)
                 (package-json-metadata-facts m)
                 (package-workspace-facts m)
                 (workspace-protocol-dependency-facts m)
                 (package-manager-fact m)))
    []))
(defn deno-json-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (let [imports (:imports m)
          tasks (:tasks m)]
      (vec (concat
            (when (map? imports)
              (map (fn [[alias target]]
                     (common/package-fact {:ecosystem :deno
                                           :package-name (common/json-key-label alias)
                                           :version-range (str target)
                                           :source-line 1}))
                   imports))
            (when (map? tasks)
              (map (fn [[task _command]]
                     {:kind :package-script
                      :label (common/json-key-label task)
                      :source-line 1
                      :relation :defines})
                   tasks)))))
    []))
(defn pnpm-workspace-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         section nil
         catalog nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"packages:\s*" trimmed)
          (recur (rest remaining) :packages nil out)

          (re-matches #"catalog:\s*" trimmed)
          (recur (rest remaining) :catalog nil out)

          (re-matches #"catalogs:\s*" trimmed)
          (recur (rest remaining) :catalogs nil out)

          (re-matches #"onlyBuiltDependencies:\s*" trimmed)
          (recur (rest remaining) :built-dependencies nil out)

          (and (= :packages section) (str/starts-with? trimmed "-"))
          (let [pattern (-> trimmed
                            (str/replace #"^-\s*" "")
                            (str/replace #"^['\"]|['\"]$" "")
                            str/trim)]
            (recur (rest remaining)
                   section
                   catalog
                   (cond-> out
                     (seq pattern)
                     (conj {:kind :workspace-pattern
                            :label pattern
                            :source-line (inc idx)
                            :relation :references}))))

          (and (= :built-dependencies section) (str/starts-with? trimmed "-"))
          (let [dependency (-> trimmed
                               (str/replace #"^-\s*" "")
                               (str/replace #"^['\"]|['\"]$" "")
                               str/trim)]
            (recur (rest remaining)
                   section
                   catalog
                   (cond-> out
                     (seq dependency)
                     (conj {:kind :pnpm-built-dependency
                            :label dependency
                            :source-line (inc idx)
                            :relation :uses}))))

          (and (= :catalog section)
               (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                           trimmed))
          (let [[_ package-name version]
                (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                            trimmed)]
            (recur (rest remaining)
                   section
                   catalog
                   (conj out {:kind :pnpm-catalog-package
                              :label (str package-name "="
                                          (str/replace version #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :defines})))

          (and (= :catalogs section)
               (re-matches #"^([A-Za-z0-9_.-]+):\s*$" trimmed))
          (let [[_ catalog-name] (re-matches #"^([A-Za-z0-9_.-]+):\s*$" trimmed)]
            (recur (rest remaining)
                   section
                   catalog-name
                   (conj out {:kind :pnpm-catalog
                              :label catalog-name
                              :source-line (inc idx)
                              :relation :defines})))

          (and (= :catalogs section)
               catalog
               (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                           trimmed))
          (let [[_ package-name version]
                (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                            trimmed)]
            (recur (rest remaining)
                   section
                   catalog
                   (conj out {:kind :pnpm-catalog-package
                              :label (str catalog ":" package-name "="
                                          (str/replace version #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :defines})))

          (and section (not (str/blank? trimmed)))
          (recur (rest remaining) nil nil out)

          :else
          (recur (rest remaining) section catalog out)))
      out)))
(def yarnrc-setting-keys
  #{"nodeLinker" "yarnPath" "npmRegistryServer" "checksumBehavior"
    "enableGlobalCache" "enableImmutableInstalls" "compressionLevel"})
(defn- yarnrc-setting-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (when-let [[_ key value]
                     (re-matches #"^([A-Za-z][A-Za-z0-9_-]*):\s*(.+?)\s*$"
                                 line)]
            (let [value (str/replace value #"^['\"]|['\"]$" "")
                  source-line (inc idx)]
              (when (contains? yarnrc-setting-keys key)
                (concat
                 [{:kind :yarn-setting
                   :label (str key "=" value)
                   :source-line source-line
                   :relation :defines}]
                 (case key
                   "nodeLinker" [{:kind :yarn-node-linker
                                  :label value
                                  :source-line source-line
                                  :relation :defines}]
                   "yarnPath" [{:kind :yarn-path
                                :label value
                                :source-line source-line
                                :relation :references}]
                   "npmRegistryServer" [{:kind :yarn-registry
                                         :label value
                                         :source-line source-line
                                         :relation :uses}]
                   [])))))))
       (remove nil?)
       distinct
       vec))
(defn- yarnrc-plugin-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-plugins? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"plugins:\s*$" trimmed)
          (recur (rest remaining) true out)

          (and in-plugins?
               (re-matches #"^[A-Za-z0-9_.-]+:\s*.*$" line))
          (recur (rest remaining) false out)

          (and in-plugins?
               (re-matches #"^-\s*(path|spec):\s*(.+?)\s*$" trimmed))
          (let [[_ key value] (re-matches #"^-\s*(path|spec):\s*(.+?)\s*$"
                                          trimmed)]
            (recur (rest remaining)
                   true
                   (conj out {:kind :yarn-plugin
                              :label (str key "="
                                          (str/replace value #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :references})))

          (and in-plugins?
               (re-matches #"^(path|spec):\s*(.+?)\s*$" trimmed))
          (let [[_ key value] (re-matches #"^(path|spec):\s*(.+?)\s*$"
                                          trimmed)]
            (recur (rest remaining)
                   true
                   (conj out {:kind :yarn-plugin
                              :label (str key "="
                                          (str/replace value #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :references})))

          :else
          (recur (rest remaining) in-plugins? out)))
      (vec (distinct out)))))
(defn- yarnrc-npm-scope-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-scopes? false
         current-scope nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"npmScopes:\s*$" trimmed)
          (recur (rest remaining) true nil out)

          (and in-scopes?
               (re-matches #"^[A-Za-z0-9_.-]+:\s*.*$" line))
          (recur (rest remaining) false nil out)

          (and in-scopes?
               (re-matches #"^([A-Za-z0-9_.-]+):\s*$" trimmed))
          (let [[_ scope] (re-matches #"^([A-Za-z0-9_.-]+):\s*$" trimmed)]
            (recur (rest remaining)
                   true
                   scope
                   (conj out {:kind :yarn-npm-scope
                              :label scope
                              :source-line (inc idx)
                              :relation :defines})))

          (and in-scopes?
               current-scope
               (re-matches #"^npmRegistryServer:\s*(.+?)\s*$" trimmed))
          (let [[_ registry] (re-matches #"^npmRegistryServer:\s*(.+?)\s*$"
                                         trimmed)]
            (recur (rest remaining)
                   true
                   current-scope
                   (conj out {:kind :yarn-scope-registry
                              :label (str current-scope "="
                                          (str/replace registry #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :uses})))

          (and in-scopes?
               current-scope
               (re-matches #"^npmAuth(?:Token|Ident):\s*(.+?)\s*$" trimmed))
          (let [[_ auth-key _] (re-matches #"^(npmAuth(?:Token|Ident)):\s*(.+?)\s*$"
                                           trimmed)]
            (recur (rest remaining)
                   true
                   current-scope
                   (conj out {:kind :yarn-auth-key
                              :label (str current-scope ":" auth-key)
                              :source-line (inc idx)
                              :relation :defines})))

          :else
          (recur (rest remaining) in-scopes? current-scope out)))
      (vec (distinct out)))))
(defn- yarnrc-package-extension-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-extensions? false
         current-extension nil
         in-deps? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (re-matches #"packageExtensions:\s*$" trimmed)
          (recur (rest remaining) true nil false out)

          (and in-extensions?
               (re-matches #"^[A-Za-z0-9_.-]+:\s*.*$" line))
          (recur (rest remaining) false nil false out)

          (and in-extensions?
               (re-matches #"^['\"]?([^'\"]+@[^'\"]+)['\"]?:\s*$" trimmed))
          (let [[_ extension] (re-matches #"^['\"]?([^'\"]+@[^'\"]+)['\"]?:\s*$"
                                          trimmed)]
            (recur (rest remaining)
                   true
                   extension
                   false
                   (conj out {:kind :yarn-package-extension
                              :label extension
                              :source-line (inc idx)
                              :relation :defines})))

          (and in-extensions?
               current-extension
               (contains? #{"dependencies:" "peerDependencies:"} trimmed))
          (recur (rest remaining) true current-extension true out)

          (and in-extensions?
               current-extension
               in-deps?
               (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                           trimmed))
          (let [[_ package-name version]
                (re-matches #"^([A-Za-z0-9@/_-][A-Za-z0-9@/_.-]*):\s*(.+?)\s*$"
                            trimmed)]
            (recur (rest remaining)
                   true
                   current-extension
                   true
                   (conj out {:kind :yarn-extension-dependency
                              :label (str current-extension
                                          ":"
                                          package-name
                                          "="
                                          (str/replace version #"^['\"]|['\"]$" ""))
                              :source-line (inc idx)
                              :relation :references})))

          :else
          (recur (rest remaining) in-extensions? current-extension in-deps? out)))
      (vec (distinct out)))))
(defn yarnrc-facts
  [content]
  (vec (concat (yarnrc-setting-facts content)
               (yarnrc-plugin-facts content)
               (yarnrc-npm-scope-facts content)
               (yarnrc-package-extension-facts content))))
(defn- json-key-facts
  [m key kind relation]
  (let [value (get m key)]
    (cond
      (map? value)
      (->> value
           keys
           (map (fn [entry]
                  {:kind kind
                   :label (common/json-key-label entry)
                   :source-line 1
                   :relation relation}))
           distinct
           vec)

      (vector? value)
      (->> value
           (keep (fn [entry]
                   (cond
                     (string? entry) entry
                     (map? entry) (or (:packageName entry)
                                      (:projectFolder entry)
                                      (:name entry))
                     :else nil)))
           (map (fn [entry]
                  {:kind kind
                   :label entry
                   :source-line 1
                   :relation relation}))
           distinct
           vec)

      :else [])))
(defn workspace-json-facts
  [content filename]
  (if-let [m (common/read-json-map content)]
    (case filename
      "turbo.json"
      (json-key-facts m :tasks :workspace-task :defines)

      "nx.json"
      (vec (concat (json-key-facts m :targetDefaults :workspace-task :defines)
                   (json-key-facts m :projects :workspace-project :references)))

      "workspace.json"
      (vec (concat (json-key-facts m :projects :workspace-project :references)
                   (json-key-facts m :targets :workspace-task :defines)))

      "lerna.json"
      (vec (concat (->> (workspace-patterns (:packages m))
                        (map (fn [pattern]
                               {:kind :workspace-pattern
                                :label pattern
                                :source-line 1
                                :relation :references})))
                   (package-manager-fact m)))

      "rush.json"
      (vec (concat (json-key-facts m :projects :workspace-project :references)
                   (package-manager-fact m)))

      [])
    []))
