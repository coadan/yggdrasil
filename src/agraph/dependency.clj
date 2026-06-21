(ns agraph.dependency
  "Derived external package dependency resolution."
  (:require [agraph.command :as command]
            [agraph.hash :as hash]
            [agraph.xtdb :as store]
            [clojure.string :as str]))

(defn- active-scope-rows
  [xtdb table {:keys [project-id repo-id]}]
  (cond->> (cond
             repo-id (store/rows-by-field xtdb table :repo-id repo-id)
             project-id (store/rows-by-field xtdb table :project-id project-id)
             :else (store/all-rows xtdb table))
    true (filter :active?)
    project-id (filter #(= project-id (:project-id %)))
    repo-id (filter #(= repo-id (:repo-id %)))
    true vec))

(defn- relation-scope-query
  [table scope-field]
  (list 'fn
        ['scope-value 'relation-value]
        (list 'from table [{scope-field 'scope-value
                            :relation 'relation-value}
                           '*])))

(defn- active-scope-edge-rows
  [xtdb {:keys [project-id repo-id] :as scope} relations]
  (->> relations
       (mapcat (fn [relation]
                 (cond
                   repo-id
                   (store/q xtdb
                            [(relation-scope-query (:edges store/tables) :repo-id)
                             repo-id
                             relation])

                   project-id
                   (store/q xtdb
                            [(relation-scope-query (:edges store/tables) :project-id)
                             project-id
                             relation])

                   :else
                   (active-scope-rows xtdb (:edges store/tables) scope))))
       (filter :active?)
       (filter #(or (nil? project-id) (= project-id (:project-id %))))
       (filter #(or (nil? repo-id) (= repo-id (:repo-id %))))
       vec))

(defn- package-node?
  [node]
  (= :external-package (:kind node)))

(defn- namespace-target
  [target-id]
  (some-> (re-find #"node:namespace:(.+)$" (str target-id))
          second))

(defn- dirname
  [path]
  (let [idx (.lastIndexOf (str path) "/")]
    (when (pos? idx)
      (subs path 0 idx))))

(defn- filename
  [path]
  (last (str/split (str path) #"/")))

(defn- extension
  [path]
  (some-> (re-find #"\.([A-Za-z0-9]+)$" (str path))
          second
          str/lower-case))

(def ^:private js-local-source-kinds
  #{:javascript :typescript :astro :vue :svelte})

(def ^:private js-local-file-extensions
  ["" ".js" ".jsx" ".ts" ".tsx" ".mjs" ".cjs" ".mts" ".cts"])

(defn- strip-resource-suffix
  [target]
  (str/replace (str target) #"[?#].*$" ""))

(defn- js-local-file-candidates
  [path]
  (let [path (strip-resource-suffix path)]
    (if (extension path)
      [path]
      (vec (concat
            (map #(str path %) js-local-file-extensions)
            (map #(str path "/index" %) js-local-file-extensions))))))

(defn- candidate-local-import-paths
  [source-path target]
  (let [target (strip-resource-suffix target)
        source-dir (dirname source-path)
        target-parts (vec (remove str/blank? (str/split target #"/")))
        target-suffix (when (< 1 (count target-parts))
                        (str/join "/" (rest target-parts)))]
    (->> (cond-> []
           (seq target)
           (conj target)

           (and (seq source-dir) (seq target))
           (conj (str source-dir "/" target))

           (and (seq source-dir) (seq target-suffix))
           (conj (str source-dir "/" target-suffix)))
         (mapcat js-local-file-candidates)
         distinct
         vec)))

(defn- ancestor-paths
  [path filename]
  (loop [dir (dirname path)
         out []]
    (if (seq dir)
      (recur (dirname dir) (conj out (str dir "/" filename)))
      (conj out filename))))

(defn- nearest-manifest
  [manifest-paths source-path filename]
  (some #(when (contains? manifest-paths %) %) (ancestor-paths source-path filename)))

(defn- ancestor-manifest-paths
  [manifest-paths source-path filename]
  (filterv #(contains? manifest-paths %) (ancestor-paths source-path filename)))

(defn- ancestor-dir?
  [ancestor path]
  (let [ancestor (or ancestor "")
        dir (or (dirname path) "")]
    (or (str/blank? ancestor)
        (= ancestor dir)
        (str/starts-with? dir (str ancestor "/")))))

(defn- js-package-name
  [target]
  (when-not (str/starts-with? (str target) ".")
    (let [parts (str/split (str target) #"/")]
      (if (str/starts-with? (str target) "@")
        (when (<= 2 (count parts))
          (str (first parts) "/" (second parts)))
        (first parts)))))

(declare package-by-name package-result)

(defn- rust-import-root
  [target]
  (first (str/split (str target) #"::")))

(defn- rust-package
  [packages target]
  (let [root (rust-import-root target)]
    (or (package-by-name packages root)
        (package-by-name packages (some-> root (str/replace "_" "-"))))))

(defn- go-package-name
  [packages target]
  (->> packages
       (filter #(or (= (:package-name %) target)
                    (str/starts-with? target (str (:package-name %) "/"))))
       (sort-by (comp - count :package-name))
       first
       :package-name))

(def ^:private dotnet-manifest-extensions
  #{"csproj" "fsproj" "vbproj" "props" "targets"})

(def ^:private dotnet-manifest-filenames
  #{"packages.config" "nuget.config"})

(defn- dotnet-manifest?
  [path]
  (let [name (str/lower-case (filename path))]
    (or (contains? dotnet-manifest-extensions (extension path))
        (contains? dotnet-manifest-filenames name))))

(def ^:private dotnet-dependency-ecosystems
  #{:nuget :dotnet-assembly})

(defn- dotnet-packages-for-source
  [packages-by-manifest source-path]
  (->> packages-by-manifest
       (filter (fn [[manifest-path _packages]]
                 (and (dotnet-manifest? manifest-path)
                      (ancestor-dir? (dirname manifest-path) source-path))))
       (mapcat val)
       (filter #(contains? dotnet-dependency-ecosystems (:ecosystem %)))
       vec))

(def ^:private package-evidence-source-kinds
  #{:manifest :doc-file})

(def ^:private npm-lock-filenames
  ["package-lock.json" "pnpm-lock.yaml" "yarn.lock" "bun.lock"])

(defn- package-source-entries
  [nodes-by-id edges]
  (->> edges
       (filter #(= :requires (:relation %)))
       (keep (fn [edge]
               (let [source (get nodes-by-id (:source-id edge))
                     target (get nodes-by-id (:target-id edge))]
                 (when (and (contains? package-evidence-source-kinds
                                       (:kind source))
                            (package-node? target))
                   [(:path source) target]))))))

(defn- lock-package-source-entries
  [nodes-by-id edges]
  (let [version-package-edges (group-by :source-id
                                        (filter #(= :version-of (:relation %))
                                                edges))]
    (->> edges
         (filter #(= :resolves (:relation %)))
         (mapcat
          (fn [edge]
            (let [source (get nodes-by-id (:source-id edge))]
              (when (= :dependency-lock (:kind source))
                (keep (fn [version-edge]
                        (let [package (get nodes-by-id (:target-id version-edge))]
                          (when (package-node? package)
                            [(:path source) package])))
                      (get version-package-edges (:target-id edge))))))))))

(defn- package-by-source
  [nodes edges]
  (let [nodes-by-id (into {} (map (juxt :xt/id identity)) nodes)]
    (->> (concat (package-source-entries nodes-by-id edges)
                 (lock-package-source-entries nodes-by-id edges))
         (reduce (fn [out [source-path package]]
                   (update out source-path (fnil conj []) package))
                 {}))))

(defn- packages-for
  [packages-by-source source-path ecosystem]
  (->> (get packages-by-source source-path)
       (filter #(= ecosystem (:ecosystem %)))
       vec))

(defn- distinct-packages
  [packages]
  (->> packages
       (map (juxt :xt/id identity))
       (into {})
       vals
       vec))

(defn- ancestor-source-packages
  [packages-by-source source-path ecosystem]
  (->> packages-by-source
       (filter (fn [[package-source-path _packages]]
                 (ancestor-dir? (dirname package-source-path) source-path)))
       (mapcat val)
       (filter #(= ecosystem (:ecosystem %)))
       distinct-packages))

(defn- package-by-name
  [packages package-name]
  (let [matches (->> packages
                     (filter #(= package-name (:package-name %)))
                     distinct-packages)]
    (when (= 1 (count matches))
      (first matches))))

(defn- package-by-ancestor-manifest
  [packages-by-manifest manifest-paths ecosystem package-name]
  (some (fn [manifest-path]
          (package-by-name
           (packages-for packages-by-manifest manifest-path ecosystem)
           package-name))
        manifest-paths))

(defn- package-by-ancestor-npm-lock
  [packages-by-manifest manifest-paths source-path package-name]
  (some (fn [filename]
          (package-by-ancestor-manifest
           packages-by-manifest
           (ancestor-manifest-paths manifest-paths source-path filename)
           :npm
           package-name))
        npm-lock-filenames))

(defn- segment-prefix?
  [prefix value]
  (or (= prefix value)
      (str/starts-with? value (str prefix "."))))

(defn- normalized-dotnet-name
  [value]
  (str/replace (str/lower-case (str value)) #"[^a-z0-9]" ""))

(defn- normalized-prefix?
  [prefix value]
  (let [prefix (normalized-dotnet-name prefix)
        value (normalized-dotnet-name value)]
    (and (seq prefix)
         (or (= prefix value)
             (str/starts-with? value prefix)))))

(defn- dotnet-name-root
  [value]
  (first (str/split (str value) #"\.")))

(defn- normalized-root-match?
  [target package-name]
  (let [target-root (normalized-dotnet-name (dotnet-name-root target))
        package-root (normalized-dotnet-name (dotnet-name-root package-name))]
    (and (seq target-root)
         (= target-root package-root))))

(defn- dotnet-package-match-score
  [target package-name]
  (let [target (str/lower-case (str target))
        package-name (str/lower-case (str package-name))]
    (cond
      (= target package-name) 3
      (segment-prefix? package-name target) 2
      (segment-prefix? target package-name) 1
      (normalized-prefix? package-name target) 1
      (normalized-prefix? target package-name) 1
      (normalized-root-match? target package-name) 0.5
      :else nil)))

(defn- dotnet-package
  [packages target]
  (let [matches (->> packages
                     (keep (fn [package]
                             (when-let [score (dotnet-package-match-score
                                               target
                                               (:package-name package))]
                               (assoc package :match-score score))))
                     (sort-by (juxt (comp - :match-score)
                                    (comp - count :package-name)))
                     vec)
        best-score (:match-score (first matches))
        best (filter #(= best-score (:match-score %)) matches)]
    (when (= 1 (count best))
      (dissoc (first best) :match-score))))

(defn- module-path-alias-node?
  [node]
  (= :module-path-alias (:kind node)))

(defn- alias-pattern-prefix
  [pattern]
  (let [pattern (str pattern)]
    (if-let [idx (str/index-of pattern "*")]
      (subs pattern 0 idx)
      pattern)))

(defn- alias-pattern-match?
  [pattern target]
  (let [prefix (alias-pattern-prefix pattern)
        target (str target)]
    (if (str/includes? (str pattern) "*")
      (str/starts-with? target prefix)
      (or (= target prefix)
          (str/starts-with? target (str prefix "/"))
          (str/starts-with? target (str prefix "."))))))

(defn- module-path-alias-match?
  [alias-node edge target]
  (when-let [[_ alias-pattern]
             (re-matches #"^(.+?)=.*$" (str (:label alias-node)))]
    (and (ancestor-dir? (dirname (:path alias-node)) (:path edge))
         (alias-pattern-match? alias-pattern target))))

(defn- import-prefix-match?
  [target import-name]
  (let [target (str target)
        import-name (str import-name)]
    (and (seq target)
         (seq import-name)
         (or (= target import-name)
             (str/starts-with? target (str import-name "."))
             (str/starts-with? target (str import-name "/"))))))

(defn- package-by-import-name
  [packages target]
  (let [matches (->> packages
                     (keep (fn [package]
                             (or (when-let [import-name
                                            (->> (:import-names package)
                                                 (filter #(import-prefix-match? target %))
                                                 (sort-by count >)
                                                 first)]
                                   {:package package
                                    :import-name import-name
                                    :resolution-source :manifest-import-name})
                                 (when (import-prefix-match? target (:package-name package))
                                   {:package package
                                    :import-name (:package-name package)
                                    :resolution-source :package-name})))))
        packages (distinct-packages (map :package matches))]
    (when (= 1 (count packages))
      (first matches))))

(defn- maven-coordinate
  [package-name]
  (let [[group-id artifact-id] (str/split (str package-name) #":" 2)]
    (when (and (seq group-id) (seq artifact-id))
      {:group-id group-id
       :artifact-id artifact-id})))

(defn- maven-artifact-prefixes
  [group-id artifact-id]
  (let [parts (vec (remove str/blank? (str/split (str artifact-id) #"-")))]
    (->> (range (count parts))
         (map #(subvec parts %))
         (remove empty?)
         (map #(str group-id "." (str/join "." %)))
         distinct
         vec)))

(defn- maven-import-prefixes
  [{:keys [package-name]}]
  (when-let [{:keys [group-id artifact-id]} (maven-coordinate package-name)]
    (->> (cond-> [group-id]
           (str/includes? group-id ".")
           (into (maven-artifact-prefixes group-id artifact-id)))
         distinct
         vec)))

(defn- maven-coordinate-match
  [target package]
  (->> (maven-import-prefixes package)
       (keep (fn [prefix]
               (when (import-prefix-match? target prefix)
                 {:package package
                  :import-name prefix
                  :resolution-source :maven-coordinate-prefix
                  :match-score (if (str/includes? prefix ".") 2 1)})))
       (sort-by (juxt (comp - :match-score)
                      (comp - count :import-name)))))

(defn- unique-best-package-match
  [matches]
  (let [matches (vec matches)
        best-score (:match-score (first matches))
        best-prefix-len (some-> (first matches) :import-name count)
        best (filter #(and (= best-score (:match-score %))
                           (= best-prefix-len (count (:import-name %))))
                     matches)
        packages (distinct-packages (map :package best))]
    (when (= 1 (count packages))
      (dissoc (first best) :match-score))))

(defn- maven-package-result
  [packages target]
  (or (package-by-import-name packages target)
      (unique-best-package-match
       (mapcat #(maven-coordinate-match target %) packages))))

(defn- all-source-packages
  [packages-by-source ecosystem]
  (->> packages-by-source
       vals
       (mapcat identity)
       (filter #(= ecosystem (:ecosystem %)))
       distinct-packages))

(defn- java-package-result
  [packages-by-source source-path target]
  (let [ancestor-packages (ancestor-source-packages packages-by-source
                                                    source-path
                                                    :maven)
        all-packages (all-source-packages packages-by-source :maven)]
    (or (maven-package-result ancestor-packages target)
        (maven-package-result all-packages target))))

(defn- npm-types-package-name
  [package-name]
  (when (and (seq package-name)
             (not (str/starts-with? package-name "@")))
    (str "@types/" package-name)))

(defn- type-import?
  [edge]
  (= :type (:import-kind edge)))

(defn- js-package-result
  [packages-by-source manifest-paths source-path target edge]
  (let [package-name (js-package-name target)
        types-package-name (npm-types-package-name package-name)]
    (or (package-result
         (package-by-ancestor-manifest
          packages-by-source
          (ancestor-manifest-paths manifest-paths source-path "deno.json")
          :deno
          package-name)
         :deno-import-map)
        (package-result
         (package-by-ancestor-manifest
          packages-by-source
          (ancestor-manifest-paths manifest-paths source-path "package.json")
          :npm
          package-name)
         :declared)
        (package-result
         (package-by-ancestor-npm-lock
          packages-by-source
          manifest-paths
          source-path
          package-name)
         :dependency-lock)
        (when (type-import? edge)
          (or (package-result
               (package-by-ancestor-manifest
                packages-by-source
                (ancestor-manifest-paths manifest-paths source-path "package.json")
                :npm
                types-package-name)
               :type-declaration
               package-name)
              (package-result
               (package-by-ancestor-npm-lock
                packages-by-source
                manifest-paths
                source-path
                types-package-name)
               :type-dependency-lock
               package-name))))))

(defn- source-kind
  [files-by-path path]
  (:kind (get files-by-path path)))

(def ^:private package-import-source-kinds
  #{:javascript :typescript :astro :vue :svelte
    :rust :go :python :java :dotnet})

(def ^:private rust-builtin-roots
  #{"alloc" "core" "crate" "self" "std" "super"})

(def ^:private js-runtime-builtin-roots
  #{"assert" "buffer" "child_process" "cluster" "console" "crypto" "dgram" "dns"
    "domain" "events" "fs" "http" "http2" "https" "module" "net" "os" "path"
    "perf_hooks" "process" "querystring" "readline" "stream" "string_decoder"
    "timers" "tls" "tty" "url" "util" "v8" "vm" "worker_threads" "zlib"})

(def ^:private js-runtime-virtual-prefixes
  #{"astro:" "bun:" "node:"})

(def ^:private python-stdlib-roots
  #{"argparse" "asyncio" "base64" "collections" "contextlib" "csv" "dataclasses"
    "datetime" "decimal" "enum" "functools" "gzip" "hashlib" "http" "importlib"
    "inspect" "itertools" "json" "logging" "math" "os" "pathlib" "re" "socket"
    "sqlite3" "statistics" "string" "subprocess" "sys" "tempfile" "time" "typing"
    "unittest" "urllib" "uuid" "xml"})

(def ^:private dotnet-builtin-roots
  #{"System"})

(def ^:private java-builtin-roots
  #{"java" "javax"})

(defn- dotted-import-root
  [target]
  (first (str/split (str target) #"\.")))

(defn- slash-import-root
  [target]
  (first (str/split (str target) #"/")))

(defn- js-runtime-import?
  [target]
  (or (contains? js-runtime-builtin-roots (slash-import-root target))
      (some #(str/starts-with? target %) js-runtime-virtual-prefixes)))

(defn- dotnet-runtime-import?
  [target]
  (contains? dotnet-builtin-roots (dotted-import-root target)))

(defn- local-namespace-import?
  [nodes-by-id edge]
  (let [target (get nodes-by-id (:target-id edge))]
    (and (= :namespace (:kind target))
         (seq (:path target)))))

(defn- dotted-symbol-labels
  [target]
  (let [parts (vec (remove str/blank? (str/split (str target) #"\.")))]
    (->> (range 1 (count parts))
         (map (fn [idx]
                (str (str/join "." (subvec parts 0 idx))
                     "/"
                     (str/join "." (subvec parts idx)))))
         distinct
         vec)))

(defn- scoped-symbol-id
  [target-id symbol-label]
  (when-let [[_ scope] (re-matches #"^(.*)node:namespace:.+$"
                                   (str target-id))]
    (str scope "node:symbol:" symbol-label)))

(defn- local-symbol-import?
  [nodes-by-id edge kind]
  (and (= :java kind)
       (some (fn [symbol-label]
               (when-let [node (get nodes-by-id
                                    (scoped-symbol-id (:target-id edge)
                                                      symbol-label))]
                 (seq (:path node))))
             (dotted-symbol-labels (namespace-target (:target-id edge))))))

(defn- local-path-alias-import?
  [alias-nodes edge target]
  (some #(module-path-alias-match? % edge target)
        alias-nodes))

(defn- local-file-import?
  [files-by-path edge target kind]
  (and (contains? js-local-source-kinds kind)
       (not (str/starts-with? target "."))
       (not (str/starts-with? target "@"))
       (str/includes? target "/")
       (some #(contains? files-by-path %)
             (candidate-local-import-paths (:path edge) target))))

(defn- package-import-candidate?
  [files-by-path alias-nodes nodes-by-id edge]
  (let [target (namespace-target (:target-id edge))
        kind (source-kind files-by-path (:path edge))]
    (and target
         (not (local-namespace-import? nodes-by-id edge))
         (not (local-symbol-import? nodes-by-id edge kind))
         (not (local-path-alias-import? alias-nodes edge target))
         (not (local-file-import? files-by-path edge target kind))
         (contains? package-import-source-kinds kind)
         (case kind
           (:javascript :typescript :astro :vue :svelte)
           (and (not (str/starts-with? target "."))
                (not (js-runtime-import? target)))

           :rust
           (let [root (rust-import-root target)]
             (and (seq root)
                  (not (contains? rust-builtin-roots root))))

           :python
           (not (contains? python-stdlib-roots (dotted-import-root target)))

           :dotnet
           (not (dotnet-runtime-import? target))

           :java
           (not (contains? java-builtin-roots (dotted-import-root target)))

           true))))

(defn- mapping-entries
  [map-overlay]
  (->> (concat (:packageImports map-overlay) (:package-imports map-overlay))
       (filter #(not= "rejected" (str (:status %))))
       vec))

(def ^:private directly-resolvable-import-ecosystems
  #{:npm :cargo :go :pypi :maven :nuget :deno :dotnet-assembly})

(defn- directly-resolvable-package?
  [package]
  (contains? directly-resolvable-import-ecosystems (:ecosystem package)))

(defn- can-resolve-import-packages?
  [packages-by-manifest map-overlay]
  (or (seq (mapping-entries map-overlay))
      (some directly-resolvable-package?
            (mapcat identity (vals packages-by-manifest)))))

(defn- mapping-package
  [packages-by-id mapping]
  (let [ecosystem (some-> (:ecosystem mapping) keyword)
        package-name (or (:package mapping) (:package-name mapping))]
    (some (fn [package]
            (when (and (= ecosystem (:ecosystem package))
                       (= package-name (:package-name package)))
              package))
          (vals packages-by-id))))

(defn- resolve-map-import
  [packages-by-id map-overlay repo-id target]
  (->> (mapping-entries map-overlay)
       (filter #(or (nil? (:repo %))
                    (= repo-id (:repo %))))
       (filter #(import-prefix-match? target (:import %)))
       (sort-by (comp - count :import))
       (keep (fn [mapping]
               (when-let [package (mapping-package packages-by-id mapping)]
                 {:package package
                  :import-name (:import mapping)
                  :resolution-source :map-overlay})))
       first))

(defn- package-result
  ([package resolution-source]
   (package-result package resolution-source nil))
  ([package resolution-source import-name]
   (when package
     (cond-> {:package package
              :resolution-source resolution-source}
       import-name (assoc :import-name import-name)))))

(defn- resolve-import
  [packages-by-id packages-by-source manifest-paths files-by-path map-overlay repo-id edge]
  (let [target (namespace-target (:target-id edge))
        kind (source-kind files-by-path (:path edge))]
    (or (resolve-map-import packages-by-id map-overlay repo-id target)
        (cond
          (contains? #{:javascript :typescript :astro :vue :svelte} kind)
          (js-package-result packages-by-source
                             manifest-paths
                             (:path edge)
                             target
                             edge)

          (= :rust kind)
          (when-let [manifest-path (nearest-manifest manifest-paths (:path edge) "Cargo.toml")]
            (let [packages (packages-for packages-by-source manifest-path :cargo)]
              (package-result (rust-package packages target)
                              :declared)))

          (= :go kind)
          (when-let [manifest-path (nearest-manifest manifest-paths (:path edge) "go.mod")]
            (let [packages (packages-for packages-by-source manifest-path :go)]
              (package-result (package-by-name packages (go-package-name packages target))
                              :declared)))

          (= :python kind)
          (let [packages (ancestor-source-packages packages-by-source
                                                   (:path edge)
                                                   :pypi)]
            (package-by-import-name packages target))

          (= :java kind)
          (java-package-result packages-by-source (:path edge) target)

          (= :dotnet kind)
          (let [packages (dotnet-packages-for-source packages-by-source (:path edge))]
            (package-result (dotnet-package packages target)
                            :declared))

          :else nil))))

(defn- dependency-edge-id
  [source-id target-id path]
  (str "edge:" (hash/short-hash [source-id :imports-package target-id path])))

(defn- import-package-edge
  [run-id project-id repo-id source-kind import-edge {:keys [package import-name resolution-source]}]
  (cond-> {:xt/id (dependency-edge-id (:source-id import-edge) (:xt/id package) (:path import-edge))
           :project-id project-id
           :repo-id repo-id
           :source-id (:source-id import-edge)
           :target-id (:xt/id package)
           :relation :imports-package
           :confidence :derived
           :file-id (:file-id import-edge)
           :path (:path import-edge)
           :source-line (:source-line import-edge)
           :ecosystem (:ecosystem package)
           :package-name (:package-name package)
           :active? true
           :run-id run-id}
    source-kind (assoc :source-kind source-kind)
    resolution-source (assoc :resolution-source resolution-source)
    import-name (assoc :import-name import-name)))

(defn- import-edge-key
  [edge]
  [(:source-id edge) (:target-id edge) (:path edge)])

(defn- map-overlay-import-edges
  [project-id packages-by-id files-by-path map-overlay source-edges imports]
  (let [existing-keys (set (map import-edge-key imports))]
    (->> source-edges
         (keep (fn [edge]
                 (when-let [result (resolve-map-import packages-by-id
                                                       map-overlay
                                                       (:repo-id edge)
                                                       (namespace-target (:target-id edge)))]
                   (let [derived (import-package-edge "report:map-overlay"
                                                      project-id
                                                      (:repo-id edge)
                                                      (source-kind files-by-path (:path edge))
                                                      edge
                                                      result)]
                     (when-not (contains? existing-keys (import-edge-key derived))
                       derived)))))
         distinct
         vec)))

(defn resolve-import-package-edges
  "Return mechanically resolved source-import to external-package edges."
  ([xtdb project-id repo-id run-id]
   (resolve-import-package-edges xtdb project-id repo-id run-id {}))
  ([xtdb project-id repo-id run-id {:keys [map-overlay]}]
   (let [scope {:project-id project-id
                :repo-id repo-id}
         files (active-scope-rows xtdb (:files store/tables) scope)
         nodes (active-scope-rows xtdb (:nodes store/tables) scope)
         dependency-source-edges (active-scope-edge-rows xtdb
                                                         scope
                                                         #{:requires
                                                           :resolves
                                                           :version-of})
         files-by-path (into {} (map (juxt :path identity)) files)
         nodes-by-id (into {} (map (juxt :xt/id identity)) nodes)
         alias-nodes (filterv module-path-alias-node? nodes)
         packages-by-id (->> nodes
                             (filter package-node?)
                             (map (juxt :xt/id identity))
                             (into {}))
         packages-by-source (package-by-source nodes dependency-source-edges)
         manifest-paths (set (keys packages-by-source))]
     (if-not (can-resolve-import-packages? packages-by-source map-overlay)
       []
       (let [source-edges (active-scope-edge-rows xtdb scope #{:imports :uses})
             candidate-edges (->> source-edges
                                  (filter #(package-import-candidate? files-by-path
                                                                      alias-nodes
                                                                      nodes-by-id
                                                                      %)))]
         (->> candidate-edges
              (keep (fn [edge]
                      (when-let [result (resolve-import packages-by-id
                                                        packages-by-source
                                                        manifest-paths
                                                        files-by-path
                                                        map-overlay
                                                        repo-id
                                                        edge)]
                        (import-package-edge run-id
                                             project-id
                                             repo-id
                                             (source-kind files-by-path (:path edge))
                                             edge
                                             result))))
              distinct
              vec))))))

(defn refresh-derived-edges!
  "Replace derived package import edges for one project/repo."
  [xtdb project-id repo-id run-id opts]
  (let [edges (resolve-import-package-edges xtdb project-id repo-id run-id opts)]
    (store/commit-derived-dependency-edges! xtdb project-id repo-id edges opts)))

(defn- relation?
  [relation edge]
  (= relation (:relation edge)))

(defn- display-source
  [row edge]
  (cond-> {:id (:xt/id row)
           :path (:path row)
           :line (:source-line edge)}
    (:label row) (assoc :label (:label row))
    (:version-range edge) (assoc :version-range (:version-range edge))
    (:dependency-scope edge) (assoc :dependency-scope (:dependency-scope edge))))

(defn- version-row
  [version sources]
  (cond-> {:id (:xt/id version)
           :label (:label version)
           :version (:resolved-version version)}
    (seq sources) (assoc :resolved-by sources)))

(defn- import-row
  [node edge]
  (let [kind (or (:source-kind edge) (:kind node))]
    (cond-> {:id (:xt/id node)
             :label (:label node)
             :path (:path edge)
             :line (:source-line edge)}
      kind (assoc :kind kind)
      (:import-name edge) (assoc :import-name (:import-name edge))
      (:resolution-source edge) (assoc :resolution-source (:resolution-source edge)))))

(defn- sorted-values
  [rows]
  (->> rows
       (sort-by (juxt (comp str :ecosystem)
                      (comp str :package-name)
                      (comp str :label)))
       vec))

(defn- summarize-ecosystem
  [packages versions imports]
  (let [package-counts (frequencies (map :ecosystem packages))
        version-counts (frequencies (map :ecosystem versions))
        import-counts (frequencies (map :ecosystem imports))]
    (->> (set (concat (keys package-counts)
                      (keys version-counts)
                      (keys import-counts)))
         (sort-by str)
         (mapv (fn [ecosystem]
                 {:ecosystem ecosystem
                  :packages (get package-counts ecosystem 0)
                  :versions (get version-counts ecosystem 0)
                  :imports (get import-counts ecosystem 0)})))))

(defn- package-entry
  [package nodes-by-id requires-by-package versions-by-package resolves-by-version
   imports-by-package]
  (let [declared-by (->> (get requires-by-package (:xt/id package))
                         (keep (fn [edge]
                                 (some-> (get nodes-by-id (:source-id edge))
                                         (display-source edge))))
                         (sort-by (juxt :path :line))
                         vec)
        resolved-versions (->> (get versions-by-package (:xt/id package))
                               (map (fn [version]
                                      (let [sources (->> (get resolves-by-version (:xt/id version))
                                                         (keep (fn [edge]
                                                                 (some-> (get nodes-by-id
                                                                              (:source-id edge))
                                                                         (display-source edge))))
                                                         (sort-by (juxt :path :line))
                                                         vec)]
                                        (version-row version sources))))
                               (sort-by :version)
                               vec)
        imported-by (->> (get imports-by-package (:xt/id package))
                         (keep (fn [edge]
                                 (some-> (get nodes-by-id (:source-id edge))
                                         (import-row edge))))
                         (sort-by (juxt :path :line :label))
                         vec)]
    (cond-> {:id (:xt/id package)
             :label (:label package)
             :ecosystem (:ecosystem package)
             :package-name (:package-name package)
             :declared-by declared-by
             :resolved-versions resolved-versions
             :imported-by imported-by}
      (:version-range package) (assoc :version-range (:version-range package))
      (:dependency-scope package) (assoc :dependency-scope (:dependency-scope package)))))

(defn- version-conflicts
  [package-entries]
  (->> package-entries
       (keep (fn [entry]
               (let [versions (->> (:resolved-versions entry)
                                   (map :version)
                                   distinct
                                   sort
                                   vec)]
                 (when (< 1 (count versions))
                   (select-keys (assoc entry :versions versions)
                                [:id :label :ecosystem :package-name :versions])))))
       vec))

(defn- package-filter-match?
  [{:keys [ecosystem package with-conflicts? without-import-evidence?]} conflict-ids entry]
  (and (or (nil? ecosystem)
           (= (keyword ecosystem) (:ecosystem entry)))
       (or (nil? package)
           (= package (:package-name entry))
           (= package (:label entry)))
       (or (not with-conflicts?)
           (contains? conflict-ids (:id entry)))
       (or (not without-import-evidence?)
           (and (seq (:declared-by entry))
                (empty? (:imported-by entry))))))

(defn- limit-report-entries
  [entries limit]
  (if (and limit (not (neg? limit)))
    (vec (take limit entries))
    entries))

(defn- import-target-label
  [nodes-by-id edge]
  (or (:label (get nodes-by-id (:target-id edge)))
      (namespace-target (:target-id edge))
      (:target-id edge)))

(defn- unresolved-import-row
  [nodes-by-id files-by-path edge]
  (let [source (get nodes-by-id (:source-id edge))
        file (get files-by-path (:path edge))]
    (cond-> {:source-id (:source-id edge)
             :source-label (:label source)
             :target-id (:target-id edge)
             :import (import-target-label nodes-by-id edge)
             :repo-id (:repo-id edge)
             :path (:path edge)
             :line (:source-line edge)}
      (:kind file) (assoc :kind (:kind file)))))

(defn- package-command
  [project-id & args]
  (str "agraph packages --project "
       (command/shell-token (or project-id "<project-id>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- package-next-actions
  [project-id counts]
  (cond-> []
    (pos? (long (or (:declared-without-import-evidence counts) 0)))
    (conj {:kind :dependencies
           :label "Inspect packages without source import evidence"
           :count (:declared-without-import-evidence counts)
           :command (package-command project-id "--without-import-evidence" "--json")})

    (pos? (long (or (:version-conflicts counts) 0)))
    (conj {:kind :dependencies
           :label "Inspect package version conflicts"
           :count (:version-conflicts counts)
           :command (package-command project-id "--with-conflicts" "--json")})

    (pos? (long (or (:unresolved-imports counts) 0)))
    (conj {:kind :dependency-review
           :label "Queue unresolved import review work"
           :count (:unresolved-imports counts)
           :command "agraph sync check <project.edn> --enqueue"})

    (pos? (long (or (:unresolved-imports counts) 0)))
    (conj {:kind :dependency-review
           :label "Pull dependency review work"
           :count (:unresolved-imports counts)
           :command (str "agraph sync work pull --project "
                         (command/shell-token (or project-id "<project-id>"))
                         " --kind dependency-review --agent <agent-id>")})

    (pos? (long (or (:unresolved-imports counts) 0)))
    (conj {:kind :package-import
           :label "Record a reviewed import-package correction directly"
           :command (str "agraph sync package import <import-prefix> <ecosystem>:<package>"
                         " --map agraph.map.json --reason <reason>")})))

(defn package-report
  "Return a mechanical external package dependency report for a project/repo scope."
  ([xtdb scope] (package-report xtdb scope {}))
  ([xtdb {:keys [project-id repo-id] :as scope}
    {:keys [limit map-overlay ecosystem package with-conflicts? without-import-evidence?] :as opts}]
   (let [files (active-scope-rows xtdb (:files store/tables) scope)
         nodes (active-scope-rows xtdb (:nodes store/tables) scope)
         edges (active-scope-rows xtdb (:edges store/tables) scope)
         files-by-path (into {} (map (juxt :path identity)) files)
         nodes-by-id (into {} (map (juxt :xt/id identity)) nodes)
         alias-nodes (filterv module-path-alias-node? nodes)
         packages (->> nodes (filter package-node?) sorted-values)
         packages-by-id (into {} (map (juxt :xt/id identity)) packages)
         versions (->> nodes
                       (filter #(= :external-package-version (:kind %)))
                       sorted-values)
         requires (filter (partial relation? :requires) edges)
         resolves (filter (partial relation? :resolves) edges)
         version-of (filter (partial relation? :version-of) edges)
         imports (filter (partial relation? :imports-package) edges)
         source-edges (filter #(contains? #{:imports :uses} (:relation %))
                              edges)
         candidate-source-edges (filter #(package-import-candidate? files-by-path
                                                                    alias-nodes
                                                                    nodes-by-id
                                                                    %)
                                        source-edges)
         report-imports (vec (concat imports
                                     (map-overlay-import-edges project-id
                                                               packages-by-id
                                                               files-by-path
                                                               map-overlay
                                                               candidate-source-edges
                                                               imports)))
         requires-by-package (group-by :target-id requires)
         resolves-by-version (group-by :target-id resolves)
         versions-by-package (->> version-of
                                  (keep (fn [edge]
                                          (when-let [version (get nodes-by-id (:source-id edge))]
                                            [(:target-id edge) version])))
                                  (reduce (fn [out [package-id version]]
                                            (update out package-id (fnil conj []) version))
                                          {}))
         imports-by-package (group-by :target-id report-imports)
         entries (->> packages
                      (mapv #(package-entry % nodes-by-id
                                            requires-by-package
                                            versions-by-package
                                            resolves-by-version
                                            imports-by-package)))
         conflicts (version-conflicts entries)
         conflict-ids (set (map :id conflicts))
         filtered-entries (->> entries
                               (filter #(package-filter-match? opts conflict-ids %))
                               vec)
         filtered-package-ids (set (map :id filtered-entries))
         filtered-version-ids (->> filtered-entries
                                   (mapcat :resolved-versions)
                                   (map :id)
                                   set)
         filtered-packages (filter #(contains? filtered-package-ids (:xt/id %)) packages)
         filtered-versions (filter #(contains? filtered-version-ids (:xt/id %)) versions)
         filtered-requires (filter #(contains? filtered-package-ids (:target-id %)) requires)
         filtered-resolves (filter #(contains? filtered-version-ids (:target-id %)) resolves)
         filtered-imports (filter #(contains? filtered-package-ids (:target-id %)) report-imports)
         declared-without-import-evidence (->> filtered-entries
                                               (filter #(and (seq (:declared-by %))
                                                             (empty? (:imported-by %))))
                                               vec)
         filtered-conflicts (->> conflicts
                                 (filter #(contains? filtered-package-ids (:id %)))
                                 vec)
         packages-by-source (package-by-source nodes edges)
         manifest-paths (set (keys packages-by-source))
         unresolved-imports (if (or ecosystem package with-conflicts? without-import-evidence?)
                              []
                              (->> candidate-source-edges
                                   (filter #(nil? (resolve-import packages-by-id
                                                                  packages-by-source
                                                                  manifest-paths
                                                                  files-by-path
                                                                  map-overlay
                                                                  (:repo-id %)
                                                                  %)))
                                   (mapv #(unresolved-import-row nodes-by-id
                                                                 files-by-path
                                                                 %))
                                   (sort-by (juxt :path :line :import))
                                   vec))
         counts {:packages (count filtered-packages)
                 :versions (count filtered-versions)
                 :requires (count filtered-requires)
                 :resolves (count filtered-resolves)
                 :imports-package (count filtered-imports)
                 :source-import-candidates (count candidate-source-edges)
                 :unresolved-imports (count unresolved-imports)
                 :declared-without-import-evidence (count declared-without-import-evidence)
                 :version-conflicts (count filtered-conflicts)}
         next-actions (package-next-actions project-id counts)]
     {:schema "agraph.dependency.report/v1"
      :project-id project-id
      :repo-id repo-id
      :filters (select-keys opts [:ecosystem :package :with-conflicts? :without-import-evidence?])
      :counts counts
      :ecosystems (summarize-ecosystem filtered-packages filtered-versions filtered-imports)
      :packages (limit-report-entries filtered-entries limit)
      :declared-without-import-evidence (limit-report-entries
                                         declared-without-import-evidence
                                         limit)
      :unresolved-imports (limit-report-entries unresolved-imports limit)
      :version-conflicts (limit-report-entries filtered-conflicts limit)
      :nextActions next-actions
      :next (mapv :command next-actions)})))
