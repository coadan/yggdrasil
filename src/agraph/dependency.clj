(ns agraph.dependency
  "Derived external package dependency resolution."
  (:require [agraph.command :as command]
            [agraph.dependency.imports :as dependency-imports]
            [agraph.dependency.resolve :as dependency-resolve]
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

(defn- ancestor-dir?
  [ancestor path]
  (let [ancestor (or ancestor "")
        dir (or (dirname path) "")]
    (or (str/blank? ancestor)
        (= ancestor dir)
        (str/starts-with? dir (str ancestor "/")))))

(def ^:private package-evidence-source-kinds
  #{:manifest :doc-file})

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

(defn- source-kind
  [files-by-path path]
  (:kind (get files-by-path path)))

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

(defn- dotted-symbol-owner-labels
  [target]
  (let [parts (vec (remove str/blank? (str/split (str target) #"\.")))]
    (->> (range 1 (count parts))
         (mapcat (fn [namespace-end]
                   (let [namespace-name (str/join "."
                                                  (subvec parts 0 namespace-end))
                         symbol-parts (subvec parts namespace-end)]
                     (->> (range 1 (count symbol-parts))
                          (map (fn [symbol-end]
                                 (str namespace-name
                                      "/"
                                      (str/join "."
                                                (subvec symbol-parts
                                                        0
                                                        symbol-end)))))))))
         distinct
         vec)))

(defn- scoped-symbol-id
  [target-id symbol-label]
  (when-let [[_ scope] (re-matches #"^(.*)node:namespace:.+$"
                                   (str target-id))]
    (str scope "node:symbol:" symbol-label)))

(def ^:private java-type-symbol-kinds
  #{:class :interface :enum :record :annotation :java-symbol :symbol})

(defn- local-symbol-node?
  [node]
  (seq (:path node)))

(defn- local-type-symbol-node?
  [node]
  (and (local-symbol-node? node)
       (contains? java-type-symbol-kinds (:kind node))))

(defn- local-scoped-symbol?
  [nodes-by-id target-id symbol-label predicate]
  (when-let [node (get nodes-by-id (scoped-symbol-id target-id symbol-label))]
    (predicate node)))

(defn- local-symbol-import?
  [nodes-by-id edge kind]
  (when (= :java kind)
    (let [target (namespace-target (:target-id edge))]
      (or (some #(local-scoped-symbol? nodes-by-id
                                       (:target-id edge)
                                       %
                                       local-symbol-node?)
                (dotted-symbol-labels target))
          (some #(local-scoped-symbol? nodes-by-id
                                       (:target-id edge)
                                       %
                                       local-type-symbol-node?)
                (dotted-symbol-owner-labels target))))))

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
         (dependency-imports/supported-source-kind? kind)
         (dependency-imports/external-package-candidate? kind target))))

(defn- resolve-package-import
  [packages-by-id packages-by-source manifest-paths files-by-path map-overlay repo-id edge]
  (dependency-resolve/resolve-import {:packages-by-id packages-by-id
                                      :packages-by-source packages-by-source
                                      :manifest-paths manifest-paths
                                      :files-by-path files-by-path
                                      :map-overlay map-overlay
                                      :repo-id repo-id
                                      :edge edge}))

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
                 (when-let [result (dependency-resolve/resolve-map-import
                                    packages-by-id
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
     (if-not (dependency-resolve/can-resolve-import-packages? packages-by-source map-overlay)
       []
       (let [source-edges (active-scope-edge-rows xtdb scope #{:imports :uses})
             candidate-edges (->> source-edges
                                  (filter #(package-import-candidate? files-by-path
                                                                      alias-nodes
                                                                      nodes-by-id
                                                                      %)))]
         (->> candidate-edges
              (keep (fn [edge]
                      (when-let [result (resolve-package-import packages-by-id
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
                                   (filter #(nil? (resolve-package-import packages-by-id
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
