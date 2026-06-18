(ns agraph.dependency
  "Derived external package dependency resolution."
  (:require [agraph.hash :as hash]
            [agraph.xtdb :as store]
            [clojure.string :as str]))

(defn- active-project-rows
  [xtdb table project-id]
  (->> (store/rows-by-field xtdb table :project-id project-id)
       (filter :active?)
       vec))

(defn- active-scope-rows
  [xtdb table {:keys [project-id repo-id]}]
  (cond->> (if project-id
             (store/rows-by-field xtdb table :project-id project-id)
             (store/all-rows xtdb table))
    true (filter :active?)
    repo-id (filter #(= repo-id (:repo-id %)))
    true vec))

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

(defn- js-package-name
  [target]
  (when-not (str/starts-with? (str target) ".")
    (let [parts (str/split (str target) #"/")]
      (if (str/starts-with? (str target) "@")
        (when (<= 2 (count parts))
          (str (first parts) "/" (second parts)))
        (first parts)))))

(declare package-by-name)

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

(defn- package-by-manifest
  [nodes edges]
  (let [nodes-by-id (into {} (map (juxt :xt/id identity)) nodes)]
    (->> edges
         (filter #(= :requires (:relation %)))
         (keep (fn [edge]
                 (let [source (get nodes-by-id (:source-id edge))
                       target (get nodes-by-id (:target-id edge))]
                   (when (and (= :manifest (:kind source))
                              (package-node? target))
                     [(:path source) target]))))
         (reduce (fn [out [manifest-path package]]
                   (update out manifest-path (fnil conj []) package))
                 {}))))

(defn- packages-for
  [packages-by-manifest manifest-path ecosystem]
  (->> (get packages-by-manifest manifest-path)
       (filter #(= ecosystem (:ecosystem %)))
       vec))

(defn- package-by-name
  [packages package-name]
  (let [matches (filter #(= package-name (:package-name %)) packages)]
    (when (= 1 (count matches))
      (first matches))))

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
                     (filter (fn [package]
                               (some #(import-prefix-match? target %)
                                     (:import-names package)))))]
    (when (= 1 (count matches))
      {:package (first matches)
       :import-name (->> (:import-names (first matches))
                         (filter #(import-prefix-match? target %))
                         (sort-by count >)
                         first)
       :resolution-source :manifest-import-name})))

(defn- source-kind
  [files-by-path path]
  (:kind (get files-by-path path)))

(def ^:private rust-builtin-roots
  #{"alloc" "core" "crate" "self" "std" "super"})

(defn- package-import-candidate?
  [files-by-path edge]
  (let [target (namespace-target (:target-id edge))
        kind (source-kind files-by-path (:path edge))]
    (case kind
      (:javascript :typescript :astro :vue :svelte)
      (and target
           (not (str/starts-with? target "."))
           (not (str/starts-with? target "node:")))

      :rust
      (let [root (rust-import-root target)]
        (and (seq root)
             (not (contains? rust-builtin-roots root))))

      true)))

(defn- mapping-entries
  [map-overlay]
  (->> (concat (:packageImports map-overlay) (:package-imports map-overlay))
       (filter #(not= "rejected" (str (:status %))))
       vec))

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
  [package resolution-source]
  (when package
    {:package package
     :resolution-source resolution-source}))

(defn- resolve-import
  [packages-by-id packages-by-manifest manifest-paths files-by-path map-overlay repo-id edge]
  (let [target (namespace-target (:target-id edge))
        kind (source-kind files-by-path (:path edge))]
    (or (resolve-map-import packages-by-id map-overlay repo-id target)
        (cond
          (contains? #{:javascript :typescript :astro :vue :svelte} kind)
          (when-let [manifest-path (nearest-manifest manifest-paths (:path edge) "package.json")]
            (let [packages (packages-for packages-by-manifest manifest-path :npm)]
              (package-result (package-by-name packages (js-package-name target))
                              :declared)))

          (= :rust kind)
          (when-let [manifest-path (nearest-manifest manifest-paths (:path edge) "Cargo.toml")]
            (let [packages (packages-for packages-by-manifest manifest-path :cargo)]
              (package-result (rust-package packages target)
                              :declared)))

          (= :go kind)
          (when-let [manifest-path (nearest-manifest manifest-paths (:path edge) "go.mod")]
            (let [packages (packages-for packages-by-manifest manifest-path :go)]
              (package-result (package-by-name packages (go-package-name packages target))
                              :declared)))

          (= :python kind)
          (when-let [manifest-path (nearest-manifest manifest-paths (:path edge) "pyproject.toml")]
            (let [packages (packages-for packages-by-manifest manifest-path :pypi)]
              (package-by-import-name packages target)))

          :else nil))))

(defn- dependency-edge-id
  [source-id target-id path]
  (str "edge:" (hash/short-hash [source-id :imports-package target-id path])))

(defn- import-package-edge
  [run-id project-id repo-id import-edge {:keys [package import-name resolution-source]}]
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
    resolution-source (assoc :resolution-source resolution-source)
    import-name (assoc :import-name import-name)))

(defn resolve-import-package-edges
  "Return mechanically resolved source-import to external-package edges."
  ([xtdb project-id repo-id run-id]
   (resolve-import-package-edges xtdb project-id repo-id run-id {}))
  ([xtdb project-id repo-id run-id {:keys [map-overlay]}]
   (let [files (active-project-rows xtdb (:files store/tables) project-id)
         nodes (active-project-rows xtdb (:nodes store/tables) project-id)
         edges (active-project-rows xtdb (:edges store/tables) project-id)
         files-by-path (into {} (map (juxt :path identity)) files)
         packages-by-id (->> nodes
                             (filter package-node?)
                             (map (juxt :xt/id identity))
                             (into {}))
         packages-by-manifest (package-by-manifest nodes edges)
         manifest-paths (set (keys packages-by-manifest))
         source-edges (->> edges
                           (filter #(contains? #{:imports :uses} (:relation %)))
                           (filter #(= repo-id (:repo-id %))))]
     (->> source-edges
          (keep (fn [edge]
                  (when-let [result (resolve-import packages-by-id
                                                    packages-by-manifest
                                                    manifest-paths
                                                    files-by-path
                                                    map-overlay
                                                    repo-id
                                                    edge)]
                    (import-package-edge run-id project-id repo-id edge result))))
          distinct
          vec))))

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
  (cond-> {:id (:xt/id node)
           :label (:label node)
           :path (:path edge)
           :line (:source-line edge)}
    (:kind node) (assoc :kind (:kind node))
    (:import-name edge) (assoc :import-name (:import-name edge))
    (:resolution-source edge) (assoc :resolution-source (:resolution-source edge))))

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
             :path (:path edge)
             :line (:source-line edge)}
      (:kind file) (assoc :kind (:kind file)))))

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
         packages (->> nodes (filter package-node?) sorted-values)
         packages-by-id (into {} (map (juxt :xt/id identity)) packages)
         versions (->> nodes
                       (filter #(= :external-package-version (:kind %)))
                       sorted-values)
         requires (filter (partial relation? :requires) edges)
         resolves (filter (partial relation? :resolves) edges)
         version-of (filter (partial relation? :version-of) edges)
         imports (filter (partial relation? :imports-package) edges)
         source-edges (filter #(and (contains? #{:imports :uses} (:relation %))
                                    (package-import-candidate? files-by-path %))
                              edges)
         requires-by-package (group-by :target-id requires)
         resolves-by-version (group-by :target-id resolves)
         versions-by-package (->> version-of
                                  (keep (fn [edge]
                                          (when-let [version (get nodes-by-id (:source-id edge))]
                                            [(:target-id edge) version])))
                                  (reduce (fn [out [package-id version]]
                                            (update out package-id (fnil conj []) version))
                                          {}))
         imports-by-package (group-by :target-id imports)
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
         filtered-imports (filter #(contains? filtered-package-ids (:target-id %)) imports)
         declared-without-import-evidence (->> filtered-entries
                                               (filter #(and (seq (:declared-by %))
                                                             (empty? (:imported-by %))))
                                               vec)
         filtered-conflicts (->> conflicts
                                 (filter #(contains? filtered-package-ids (:id %)))
                                 vec)
         manifest-paths (set (keys (package-by-manifest nodes edges)))
         packages-by-manifest (package-by-manifest nodes edges)
         unresolved-imports (if (or ecosystem package with-conflicts? without-import-evidence?)
                              []
                              (->> source-edges
                                   (filter #(nil? (resolve-import packages-by-id
                                                                  packages-by-manifest
                                                                  manifest-paths
                                                                  files-by-path
                                                                  map-overlay
                                                                  (:repo-id %)
                                                                  %)))
                                   (mapv #(unresolved-import-row nodes-by-id
                                                                 files-by-path
                                                                 %))
                                   (sort-by (juxt :path :line :import))
                                   vec))]
     {:schema "agraph.dependency.report/v1"
      :project-id project-id
      :repo-id repo-id
      :filters (select-keys opts [:ecosystem :package :with-conflicts? :without-import-evidence?])
      :counts {:packages (count filtered-packages)
               :versions (count filtered-versions)
               :requires (count filtered-requires)
               :resolves (count filtered-resolves)
               :imports-package (count filtered-imports)
               :unresolved-imports (count unresolved-imports)
               :declared-without-import-evidence (count declared-without-import-evidence)
               :version-conflicts (count filtered-conflicts)}
      :ecosystems (summarize-ecosystem filtered-packages filtered-versions filtered-imports)
      :packages (limit-report-entries filtered-entries limit)
      :declared-without-import-evidence (limit-report-entries
                                         declared-without-import-evidence
                                         limit)
      :unresolved-imports (limit-report-entries unresolved-imports limit)
      :version-conflicts (limit-report-entries filtered-conflicts limit)})))
