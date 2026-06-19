(ns agraph.affected
  "Conservative mechanical affected-file analysis."
  (:require [agraph.command :as command]
            [agraph.index :as index]
            [agraph.xtdb :as store]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def schema
  "agraph.affected/v1")

(def supported-relations
  (disj index/indexed-relations :defines))

(def ^:private test-node-kinds
  #{:test})

(defn- display-name
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(defn- relation-key
  [value]
  (cond
    (keyword? value) value
    (nil? value) nil
    :else (keyword (str value))))

(defn split-files
  "Split a comma-separated file list into normalized relative paths."
  [value]
  (->> (str/split (str value) #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- active?
  [row]
  (not= false (:active? row)))

(defn- scope-match?
  [{:keys [project-id repo-id]} row]
  (and (or (nil? project-id) (= project-id (:project-id row)))
       (or (nil? repo-id) (= repo-id (:repo-id row)))))

(defn- read-context
  [opts]
  (store/read-context (:read-context opts)))

(defn- scoped-active-rows
  [xtdb table scope]
  (->> (store/all-rows xtdb table (read-context scope))
       (filter active?)
       (filter #(scope-match? scope %))
       vec))

(defn- project-scope
  [project opts]
  (cond-> {:project-id (:id project)}
    (:repo-id opts) (assoc :repo-id (:repo-id opts))
    (:read-context opts) (assoc :read-context (:read-context opts))))

(defn- rows-for-analysis
  [xtdb project opts]
  (let [scope (project-scope project opts)]
    {:files (scoped-active-rows xtdb (:files store/tables) scope)
     :nodes (scoped-active-rows xtdb (:nodes store/tables) scope)
     :edges (scoped-active-rows xtdb (:edges store/tables) scope)
     :chunks (scoped-active-rows xtdb (:chunks store/tables) scope)}))

(defn- repo-by-id
  [project]
  (into {} (map (juxt :id identity)) (:repos project)))

(defn- selected-repos
  [project repo-id]
  (let [repos (repo-by-id project)]
    (if repo-id
      [(or (get repos repo-id)
           (throw (ex-info "Unknown repo id."
                           {:repo-id repo-id
                            :known-repos (sort (keys repos))})))]
      (:repos project))))

(defn- git-diff-files
  [repo since]
  (let [result (shell/sh "git" "-C" (:root repo) "diff" "--name-only" since "--")]
    (when-not (zero? (:exit result))
      (throw (ex-info "git diff failed while finding affected input files."
                      {:repo-id (:id repo)
                       :root (:root repo)
                       :since since
                       :exit (:exit result)
                       :err (:err result)})))
    (->> (str/split-lines (:out result))
         (map str/trim)
         (remove str/blank?)
         (mapv (fn [path]
                 {:repo-id (:id repo)
                  :path path})))))

(defn input-files
  "Return requested input files from explicit paths or a git diff basis."
  [project {:keys [files since repo-id]}]
  (cond
    (seq files)
    (mapv (fn [path]
            (cond-> {:path path}
              repo-id (assoc :repo-id repo-id)))
          files)

    (seq since)
    (mapv identity
          (mapcat #(git-diff-files % since)
                  (selected-repos project repo-id)))

    :else
    (throw (ex-info "Missing affected input files."
                    {:hint "Pass explicit paths or --since <git-rev-or-range>."}))))

(defn- file-summary
  [file]
  (cond-> {:repo-id (:repo-id file)
           :path (:path file)
           :fileId (:xt/id file)}
    (:kind file) (assoc :kind (name (:kind file)))
    (:content-sha file) (assoc :contentSha (:content-sha file))))

(defn- index-files-by-repo-path
  [files]
  (group-by (juxt :repo-id :path) files))

(defn- index-files-by-path
  [files]
  (group-by :path files))

(defn- resolve-input-file
  [files-by-repo-path files-by-path {:keys [repo-id path] :as input}]
  (let [matches (if repo-id
                  (get files-by-repo-path [repo-id path])
                  (get files-by-path path))]
    (cond
      (= 1 (count matches))
      (assoc (file-summary (first matches))
             :status "indexed"
             :input input)

      (seq matches)
      {:path path
       :repo-id repo-id
       :status "ambiguous"
       :matches (mapv file-summary (sort-by (juxt :repo-id :path) matches))
       :input input}

      :else
      {:path path
       :repo-id repo-id
       :status "unindexed"
       :input input})))

(defn- indexed-input?
  [input]
  (= "indexed" (:status input)))

(defn- node-id
  [node]
  (:xt/id node))

(defn- edge-source
  [edge]
  (:source-id edge))

(defn- edge-target
  [edge]
  (:target-id edge))

(defn- relation-supported?
  [edge]
  (contains? supported-relations (relation-key (:relation edge))))

(defn- test-node?
  [node]
  (contains? test-node-kinds (:kind node)))

(defn- test-chunk?
  [chunk]
  (= :test (:definition-kind chunk)))

(defn- file-test-evidence
  [nodes-by-file chunks-by-file file-id]
  (let [nodes (->> (get nodes-by-file file-id)
                   (filter test-node?)
                   (mapv #(select-keys % [:xt/id :label :kind :path :source-line])))
        chunks (->> (get chunks-by-file file-id)
                    (filter test-chunk?)
                    (mapv #(select-keys % [:xt/id :label :definition-kind :path :source-line])))]
    (vec (concat nodes chunks))))

(defn- node-file-id
  [node]
  (:file-id node))

(defn- changed-file-by-node-id
  [nodes-by-id files-by-id changed-file-ids]
  (reduce (fn [out node]
            (if (contains? changed-file-ids (node-file-id node))
              (assoc out (node-id node) (get files-by-id (node-file-id node)))
              out))
          {}
          (vals nodes-by-id)))

(defn- affected-direction
  [changed-node-ids edge]
  (cond
    (contains? changed-node-ids (edge-target edge)) "incoming-dependent"
    (contains? changed-node-ids (edge-source edge)) "outgoing-dependency"))

(defn- neighbor-node-id
  [direction edge]
  (case direction
    "incoming-dependent" (edge-source edge)
    "outgoing-dependency" (edge-target edge)))

(defn- changed-node-id
  [direction edge]
  (case direction
    "incoming-dependent" (edge-target edge)
    "outgoing-dependency" (edge-source edge)))

(defn- via-row
  [direction edge changed-file neighbor-node changed-node]
  (cond-> {:direction direction
           :edgeId (:xt/id edge)
           :relation (display-name (:relation edge))
           :sourceNode (edge-source edge)
           :targetNode (edge-target edge)
           :changedFile (select-keys changed-file [:repo-id :path])}
    neighbor-node (assoc :neighborNode (select-keys neighbor-node
                                                    [:xt/id :label :kind :path :source-line]))
    changed-node (assoc :changedNode (select-keys changed-node
                                                  [:xt/id :label :kind :path :source-line]))
    (:confidence edge) (assoc :confidence (display-name (:confidence edge)))
    (:source-line edge) (assoc :sourceLine (:source-line edge))))

(defn- affected-edge-row
  [nodes-by-id files-by-id changed-file-by-node changed-node-ids edge]
  (when (relation-supported? edge)
    (when-let [direction (affected-direction changed-node-ids edge)]
      (let [neighbor-id (neighbor-node-id direction edge)
            neighbor-node (get nodes-by-id neighbor-id)
            neighbor-file (get files-by-id (node-file-id neighbor-node))
            changed-id (changed-node-id direction edge)
            changed-node (get nodes-by-id changed-id)
            changed-file (get changed-file-by-node changed-id)]
        (when (and neighbor-file
                   changed-file
                   (not= (:xt/id neighbor-file) (:xt/id changed-file)))
          {:file neighbor-file
           :via (via-row direction edge changed-file neighbor-node changed-node)})))))

(defn- unsupported-incident-edge?
  [changed-node-ids edge]
  (and (or (contains? changed-node-ids (edge-source edge))
           (contains? changed-node-ids (edge-target edge)))
       (not (relation-supported? edge))))

(defn- unsupported-edge-row
  [edge]
  (cond-> {:edgeId (:xt/id edge)
           :relation (display-name (:relation edge))
           :sourceNode (edge-source edge)
           :targetNode (edge-target edge)}
    (:path edge) (assoc :path (:path edge))
    (:source-line edge) (assoc :sourceLine (:source-line edge))))

(defn- affected-file-row
  [nodes-by-file chunks-by-file tests-only? [file-id rows]]
  (let [file (:file (first rows))
        via (mapv :via rows)
        test-evidence (file-test-evidence nodes-by-file chunks-by-file file-id)]
    (when (or (not tests-only?)
              (seq test-evidence))
      (cond-> (assoc (file-summary file)
                     :edgeCount (count via)
                     :directions (->> via (map :direction) distinct vec)
                     :via via)
        (seq test-evidence) (assoc :testEvidence test-evidence)))))

(defn- warning-rows
  [resolved-inputs changed-nodes affected-files tests-only?]
  (vec
   (concat
    (when-let [rows (seq (filter #(= "unindexed" (:status %)) resolved-inputs))]
      [{:kind "unindexed-inputs"
        :message "Some input paths are not indexed; run sync before trusting absence of impact."
        :inputs (mapv #(select-keys % [:repo-id :path]) rows)}])
    (when-let [rows (seq (filter #(= "ambiguous" (:status %)) resolved-inputs))]
      [{:kind "ambiguous-inputs"
        :message "Some input paths match more than one repo; pass --repo or explicit repo-scoped paths."
        :inputs (mapv #(select-keys % [:repo-id :path :matches]) rows)}])
    (when (and (seq (filter indexed-input? resolved-inputs))
               (empty? changed-nodes))
      [{:kind "no-indexed-nodes"
        :message "Indexed input files have no source graph nodes; impact is limited to file presence."}])
    (when tests-only?
      [{:kind "tests-filter-boundary"
        :message "The --tests filter only uses indexed :test definitions; it never infers tests from file names or paths."}])
    (when (and tests-only?
               (seq changed-nodes)
               (empty? affected-files))
      [{:kind "no-mechanical-test-impact"
        :message "No impacted files had indexed :test definitions."}]))))

(defn- distinct-by
  [f coll]
  (loop [remaining (seq coll)
         seen #{}
         out []]
    (if-let [item (first remaining)]
      (let [k (f item)]
        (if (contains? seen k)
          (recur (next remaining) seen out)
          (recur (next remaining) (conj seen k) (conj out item))))
      out)))

(defn- sync-command
  [config-path & args]
  (apply command/command
         (concat ["agraph" "sync" (or config-path "<project.edn>")]
                 args)))

(defn- sync-subcommand
  [subcommand config-path & args]
  (apply command/command
         (concat ["agraph" "sync" subcommand (or config-path "<project.edn>")]
                 args)))

(defn- affected-command
  [config-path {:keys [repo-id files since tests-only?]}]
  (apply command/command
         (concat ["agraph" "affected" (or config-path "<project.edn>")]
                 (when repo-id ["--repo" repo-id])
                 (when (seq files) ["--files" (str/join "," files)])
                 (when since ["--since" since])
                 (when tests-only? ["--tests"])
                 ["--json"])))

(defn- warning-next-actions
  [warnings opts]
  (let [warning-kinds (set (map :kind warnings))
        config-path (:config-path opts)
        input-files (vec (:files opts))]
    (->> (cond-> []
           (contains? warning-kinds "ambiguous-inputs")
           (conj {:kind :affected
                  :label "Re-run affected with an explicit repo"
                  :command (affected-command config-path
                                             (assoc opts
                                                    :repo-id "<repo-id>"
                                                    :files input-files))})

           (or (contains? warning-kinds "unindexed-inputs")
               (contains? warning-kinds "no-indexed-nodes"))
           (conj {:kind :sync
                  :label "Refresh the graph index before trusting absence of impact"
                  :command (sync-command config-path "--check")})

           (contains? warning-kinds "no-mechanical-test-impact")
           (conj {:kind :coverage
                  :label "Inspect source coverage and extractor diagnostics"
                  :command (sync-subcommand "coverage" config-path "--json")}))
         (distinct-by :command)
         vec)))

(defn analyze-rows
  "Return conservative affected-file analysis from already loaded graph rows."
  [project rows opts]
  (let [inputs (input-files project opts)
        files (vec (:files rows))
        files-by-id (into {} (map (juxt :xt/id identity)) files)
        files-by-repo-path (index-files-by-repo-path files)
        files-by-path (index-files-by-path files)
        resolved-inputs (mapv #(resolve-input-file files-by-repo-path files-by-path %) inputs)
        changed-file-ids (set (keep :fileId (filter indexed-input? resolved-inputs)))
        nodes-by-id (into {} (map (juxt node-id identity)) (:nodes rows))
        nodes-by-file (group-by :file-id (:nodes rows))
        chunks-by-file (group-by :file-id (:chunks rows))
        changed-nodes (->> (:nodes rows)
                           (filter #(contains? changed-file-ids (:file-id %)))
                           vec)
        changed-node-ids (set (map node-id changed-nodes))
        changed-file-by-node (changed-file-by-node-id nodes-by-id
                                                      files-by-id
                                                      changed-file-ids)
        affected-edge-rows (->> (:edges rows)
                                (keep #(affected-edge-row nodes-by-id
                                                          files-by-id
                                                          changed-file-by-node
                                                          changed-node-ids
                                                          %)))
        tests-only? (boolean (:tests-only? opts))
        affected-files (->> affected-edge-rows
                            (group-by (comp :xt/id :file))
                            (keep #(affected-file-row nodes-by-file
                                                      chunks-by-file
                                                      tests-only?
                                                      %))
                            (sort-by (juxt :repo-id :path))
                            vec)
        unsupported-edges (->> (:edges rows)
                               (filter #(unsupported-incident-edge? changed-node-ids %))
                               (mapv unsupported-edge-row))
        warnings (warning-rows resolved-inputs
                               changed-nodes
                               affected-files
                               tests-only?)
        next-actions (warning-next-actions warnings opts)]
    {:schema schema
     :project-id (:id project)
     :basis (cond-> {:mode (if (seq (:since opts)) "git-diff" "explicit-files")
                     :supportedRelations (mapv name (sort-by name supported-relations))
                     :testsOnly tests-only?}
              (:repo-id opts) (assoc :repo-id (:repo-id opts))
              (:since opts) (assoc :since (:since opts)))
     :inputs resolved-inputs
     :changedFiles (->> resolved-inputs
                        (filter indexed-input?)
                        (mapv #(select-keys % [:repo-id :path :fileId :kind :contentSha])))
     :changedNodes (mapv #(select-keys % [:xt/id :label :kind :path :source-line])
                         (sort-by (juxt :path :source-line :label) changed-nodes))
     :affectedFiles affected-files
     :unsupportedIncidentEdges {:count (count unsupported-edges)
                                :samples (vec (take 8 unsupported-edges))}
     :warnings warnings
     :nextActions next-actions}))

(defn analyze
  "Load indexed graph rows and return conservative affected-file analysis."
  [xtdb project opts]
  (analyze-rows project (rows-for-analysis xtdb project opts) opts))
