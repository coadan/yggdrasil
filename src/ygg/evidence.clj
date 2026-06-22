(ns ygg.evidence
  "Mechanical evidence-surface summaries for agents and reports."
  (:require [ygg.activity :as activity]
            [ygg.command :as command]
            [ygg.coverage :as coverage]
            [ygg.dependency :as dependency]
            [ygg.fs :as fs]
            [ygg.query :as query]
            [ygg.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def schema
  "ygg.evidence/v2")

(defn- active?
  [row]
  (not= false (:active? row)))

(defn- scope
  [{:keys [project-id repo-id read-context]}]
  (cond-> {:read-context read-context}
    project-id (assoc :project-id project-id)
    repo-id (assoc :repo-id repo-id)))

(defn- active-rows
  [xtdb table {:keys [project-id repo-id read-context]}]
  (->> (store/constrained-rows xtdb
                               table
                               {:project-id project-id
                                :repo-id repo-id}
                               (store/read-context read-context))
       (filter active?)
       vec))

(defn- display
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) "(none)"
    :else (str value)))

(defn- top-counts
  ([rows key-fn] (top-counts rows key-fn 12))
  ([rows key-fn limit]
   (->> rows
        (map key-fn)
        frequencies
        (map (fn [[value n]]
               {:value (display value)
                :count n}))
        (sort-by (juxt (comp - long :count) :value))
        (take limit)
        vec)))

(defn- map-exists?
  [map-path]
  (when (seq (str map-path))
    (.exists (io/file map-path))))

(defn- overlay-counts
  [overlay map-path]
  {:map-file (if (map-exists? map-path) 1 0)
   :systems (count (:systems overlay))
   :docs (count (:docs overlay))
   :edges (count (:edges overlay))
   :rejects (count (:reject overlay))
   :package-imports (+ (count (:packageImports overlay))
                       (count (:package-imports overlay)))})

(def ^:private family-order
  [:source-files
   :file-facts
   :source-graph
   :dependencies
   :runtime-config
   :auth
   :docs
   :embeddings
   :system-evidence
   :system-graph
   :activity
   :validation-history
   :map-overlay])

(def ^:private family-count-keys
  {:source-files [:files :skipped-files :diagnostics]
   :file-facts [:file-facts]
   :source-graph [:nodes :edges]
   :dependencies [:packages
                  :package-imports
                  :source-import-candidates
                  :package-evidence-gaps
                  :package-conflicts
                  :unresolved-imports]
   :runtime-config [:runtime-config-evidence]
   :auth [:auth-references
          :service-account-references
          :secret-references
          :private-key-references
          :api-key-references]
   :docs [:chunks :search-docs]
   :embeddings [:embeddings]
   :system-evidence [:system-evidence]
   :system-graph [:system-nodes :system-edges]
   :activity [:activity-items :activity-events]
   :validation-history [:validation-events
                        :result-schema-status-items
                        :result-schema-matching-items
                        :result-schema-mismatch-items
                        :result-schema-missing-result-items
                        :result-schema-unexpected-result-items
                        :result-schema-mismatch-events]
   :map-overlay [:map-file :systems :docs :edges :rejects :package-imports]})

(defn- validation-history-count
  [counts]
  (+ (:validation-events counts 0)
     (:result-schema-status-items counts 0)
     (:result-schema-mismatch-events counts 0)))

(defn- available
  [{:keys [files file-facts nodes edges chunks search-docs embeddings system-evidence
           runtime-config-evidence auth-references system-nodes system-edges
           activity-items activity-events packages map-overlay]
    :as counts}]
  (cond-> []
    (pos? files) (conj :source-files)
    (pos? file-facts) (conj :file-facts)
    (pos? (+ nodes edges)) (conj :source-graph)
    (pos? (+ chunks search-docs)) (conj :docs)
    (pos? embeddings) (conj :embeddings)
    (pos? runtime-config-evidence) (conj :runtime-config)
    (pos? auth-references) (conj :auth)
    (pos? system-evidence) (conj :system-evidence)
    (pos? (+ system-nodes system-edges)) (conj :system-graph)
    (pos? packages) (conj :dependencies)
    (pos? (+ activity-items activity-events)) (conj :activity)
    (pos? (validation-history-count counts)) (conj :validation-history)
    (pos? (reduce + (vals map-overlay))) (conj :map-overlay)))

(defn- family-counts
  [counts family]
  (let [source (if (= :map-overlay family)
                 (:map-overlay counts)
                 counts)]
    (when-let [ks (seq (get family-count-keys family))]
      (into {}
            (map (fn [k] [k (long (or (get source k) 0))]))
            ks))))

(defn- positive-count?
  [value]
  (pos? (long (or value 0))))

(declare dependency-diagnostics)

(defn- blocking-diagnostic?
  [diagnostic]
  (not= false (:blocking diagnostic)))

(defn- dependencies-weak?
  [counts]
  (boolean (some blocking-diagnostic? (dependency-diagnostics counts))))

(defn- dependencies-not-applicable?
  [counts]
  (and (zero? (long (or (:packages counts) 0)))
       (zero? (long (or (:source-import-candidates counts) 0)))
       (zero? (long (or (:package-imports counts) 0)))))

(defn- diagnostic
  ([reason count message]
   (diagnostic reason count message true))
  ([reason count message blocking?]
   {:reason reason
    :count (long (or count 0))
    :blocking (boolean blocking?)
    :message message}))

(defn- dependency-diagnostics
  [counts]
  (let [packages (:packages counts 0)
        package-imports (:package-imports counts 0)
        source-import-candidates (:source-import-candidates counts 0)
        package-evidence-gaps (:package-evidence-gaps counts 0)
        package-conflicts (:package-conflicts counts 0)
        unresolved-imports (:unresolved-imports counts 0)]
    (cond-> []
      (and (positive-count? source-import-candidates)
           (zero? (long (or packages 0))))
      (conj (diagnostic
             :missing-package-facts
             source-import-candidates
             "Source import candidates were extracted, but no package facts are indexed for resolution."))

      (and (positive-count? packages)
           (zero? (long (or package-imports 0)))
           (zero? (long (or source-import-candidates 0))))
      (conj (diagnostic
             :no-source-import-candidates
             packages
             "Package facts are indexed, but no source import candidates were emitted for package resolution."))

      (positive-count? unresolved-imports)
      (conj (diagnostic
             :candidate-unresolved
             unresolved-imports
             "Source import candidates were extracted, but some did not resolve to package facts."))

      (positive-count? package-evidence-gaps)
      (conj (diagnostic
             :package-without-import-evidence
             package-evidence-gaps
             "Declared package facts exist without matching source import evidence."
             false))

      (positive-count? package-conflicts)
      (conj (diagnostic
             :package-version-conflict
             package-conflicts
             "Package version conflicts are present in indexed dependency facts."
             false)))))

(defn- source-files-weak?
  [counts]
  (or (positive-count? (:skipped-files counts))
      (positive-count? (:diagnostics counts))))

(defn- validation-history-weak?
  [counts]
  (or (positive-count? (:result-schema-mismatch-events counts))
      (positive-count? (:result-schema-mismatch-items counts))
      (positive-count? (:result-schema-missing-result-items counts))
      (positive-count? (:result-schema-unexpected-result-items counts))))

(def ^:private runtime-config-evidence-kinds
  #{"auth-reference"
    "container-image-consumer"
    "container-image-producer"
    "env-var"
    "k8s-config"
    "k8s-ingress"
    "k8s-service"
    "k8s-workload"
    "port"
    "route"
    "yaml-resource"})

(defn- row-kind-name
  [row]
  (display (:kind row)))

(defn- row-context-name
  [row k]
  (display (get row k)))

(defn- runtime-config-evidence?
  [row]
  (or (contains? runtime-config-evidence-kinds (row-kind-name row))
      (and (= "url" (row-kind-name row))
           (= "runtime-config" (row-context-name row :url-context)))))

(defn- auth-evidence?
  [row]
  (= "auth-reference" (row-kind-name row)))

(defn- auth-context-count
  [rows context]
  (count (filter #(= context (row-context-name % :auth-context)) rows)))

(defn- family-status
  [counts available-families family]
  (cond
    (and (= :source-files family)
         (source-files-weak? counts)) :weak
    (and (= :dependencies family)
         (dependencies-not-applicable? counts)) :not-applicable
    (and (= :dependencies family)
         (dependencies-weak? counts)) :weak
    (and (= :validation-history family)
         (validation-history-weak? counts)) :weak
    (contains? available-families family) :available
    :else :missing))

(defn- family-diagnostics
  [counts family]
  (case family
    :dependencies (dependency-diagnostics counts)
    []))

(defn- evidence-families
  "Return compact project-level mechanical evidence-family readiness rows."
  [counts available-families]
  (let [available-families (set available-families)]
    (mapv (fn [family]
            (let [family-counts (family-counts counts family)
                  diagnostics (family-diagnostics counts family)]
              (cond-> {:family family
                       :status (family-status counts available-families family)}
                (seq family-counts) (assoc :counts family-counts)
                (seq diagnostics) (assoc :diagnostics diagnostics))))
          family-order)))

(defn- kind-counts
  [rows]
  (->> rows
       (map :kind)
       frequencies
       (map (fn [[kind n]]
              {:kind kind
               :count n}))
       (sort-by (juxt (comp - long :count) (comp name :kind)))
       vec))

(defn- evidence-kinds
  [{:keys [file-facts system-evidence nodes edges files]}]
  (cond-> {}
    (seq file-facts) (assoc :file-facts (kind-counts file-facts))
    (seq system-evidence) (assoc :system-evidence (kind-counts system-evidence))
    (seq nodes) (assoc-in [:source-graph :nodes] (top-counts nodes :kind))
    (seq edges) (assoc-in [:source-graph :edges] (top-counts edges :relation))
    (seq files) (assoc-in [:source-files :files] (top-counts files :kind))))

(defn- dependency-health
  [counts]
  {:package-evidence-gaps (:package-evidence-gaps counts 0)
   :package-conflicts (:package-conflicts counts 0)
   :source-import-candidates (:source-import-candidates counts 0)
   :unresolved-imports (:unresolved-imports counts 0)
   :diagnostics (dependency-diagnostics counts)})

(defn- evidence-state
  [freshness diagnostics counts]
  {:freshness freshness
   :diagnostics diagnostics
   :dependency-health (dependency-health counts)})

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

(defn- action-distinct-key
  [action]
  (if (contains? #{:coverage :system-evidence :validation-history} (:kind action))
    [(:kind action) (:label action) (:command action)]
    [(:command action)]))

(defn- package-command
  [project-id & args]
  (str "ygg packages --project " (command/shell-token (or project-id "<project-id>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- sync-command
  [config-path & args]
  (str "ygg sync " (command/shell-token (or config-path "<project.edn>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- sync-subcommand
  [subcommand config-path & args]
  (str "ygg sync " subcommand " " (command/shell-token (or config-path "<project.edn>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- sync-work-command
  [action & args]
  (apply command/command "ygg" "sync" "work" action args))

(defn- sync-work-list-command
  [project-id & args]
  (apply sync-work-command
         "list"
         "--project"
         (or project-id "<project-id>")
         args))

(defn- view-systems-command
  [project-id]
  (str "ygg view systems --project " (command/shell-token (or project-id "<project-id>"))))

(defn- ask-command
  [project-id]
  (str "ygg ask \"where is this handled?\" --project "
       (command/shell-token (or project-id "<project-id>"))
       " --json"))

(defn- audit-scope-command
  [config-path map-path]
  (str "ygg audit-scope "
       (command/shell-token (or config-path "<project.edn>"))
       (when map-path
         (str " --map " (command/shell-token map-path)))
       " --json"))

(defn- map-init-command
  [config-path map-path]
  (str "ygg sync init "
       (command/shell-token (or config-path "<project.edn>"))
       " --out "
       (command/shell-token (or map-path "ygg.map.json"))))

(defn- canonical-work-loop-commands
  [project-id config-path map-path]
  (let [check-command (str (sync-subcommand "check" config-path)
                           (when map-path
                             (str " --map " (command/shell-token map-path)))
                           " --enqueue")]
    [check-command
     (sync-work-list-command project-id "--status" "ready")
     (sync-work-command "pull"
                        "--project"
                        (or project-id "<project-id>")
                        "--agent"
                        "<agent-id>")
     (sync-work-command "complete" "<work-id>" "--result" "result.json")
     (sync-work-command "validate" "<work-id>")
     (sync-work-command "apply"
                        "<work-id>"
                        "--map"
                        (or map-path "ygg.map.json"))
     (sync-subcommand "activity" config-path "--json")]))

(defn- validation-history-action
  [project-id config-path map-path]
  (let [commands (canonical-work-loop-commands project-id config-path map-path)]
    {:kind :validation-history
     :label "Run sync work validation loop"
     :command (first commands)
     :commands commands}))

(defn- extractor-plugin-gap-command
  []
  "bb plugin gap extractor <package-dir> <repo-root> <file> --json")

(defn- extractor-plugin-registry-command
  []
  "bb plugin registry list <registry.edn> --kind extractor --query <file-kind-or-extension>")

(defn- extractor-plugin-scaffold-command
  []
  "bb plugin new <package-dir> --extractor --file-kind <file-kind> --path-glob '<glob>' --fixture fixtures/sample.<ext>")

(defn- status-mcp
  [config-path map-path]
  (let [args (cond-> {}
               config-path (assoc :configPath config-path)
               map-path (assoc :mapPath map-path))]
    (cond-> {:mcpTool "ygg_status"}
      (seq args) (assoc :mcpArgs args))))

(defn- skipped-source-action
  [skipped-files config-path map-path]
  (merge {:kind :coverage
          :label "Inspect skipped source candidates"
          :count skipped-files
          :command (sync-subcommand "coverage" config-path "--json")
          :pluginRegistryCommand (extractor-plugin-registry-command)
          :pluginScaffoldCommand (extractor-plugin-scaffold-command)
          :pluginGapCommand (extractor-plugin-gap-command)}
         (status-mcp config-path map-path)))

(defn- package-next-actions
  [project-id {:keys [packages package-evidence-gaps unresolved-imports package-conflicts
                      source-import-candidates]}
   {:keys [config-path map-path]}]
  (let [check-command (str (sync-subcommand "check" config-path)
                           (when map-path
                             (str " --map " (command/shell-token map-path))))]
    (cond-> []
      (and (zero? (long (or packages 0)))
           (positive-count? source-import-candidates))
      (conj {:kind :dependencies
             :label "Inspect package graph facts"
             :command (package-command project-id "--json")})

      (and (zero? (long (or packages 0)))
           (zero? (long (or source-import-candidates 0))))
      (conj {:kind :dependencies
             :label "No package manifests or source package imports found"
             :command (sync-subcommand "coverage" config-path "--json")})

      (positive-count? package-evidence-gaps)
      (conj {:kind :dependencies
             :label "Inspect packages without source import evidence"
             :count package-evidence-gaps
             :command (package-command project-id "--without-import-evidence" "--json")})

      (positive-count? package-conflicts)
      (conj {:kind :dependencies
             :label "Inspect package version conflicts"
             :count package-conflicts
             :command (package-command project-id "--with-conflicts" "--json")})

      (positive-count? unresolved-imports)
      (conj {:kind :dependencies
             :label "Inspect unresolved import package evidence"
             :count unresolved-imports
             :command (package-command project-id "--json")})

      (positive-count? unresolved-imports)
      (conj {:kind :dependency-review
             :label "Queue unresolved import review work"
             :count unresolved-imports
             :command (str check-command " --enqueue")})

      (positive-count? unresolved-imports)
      (conj {:kind :dependency-correction
             :label "Apply accepted import-to-package correction"
             :count unresolved-imports
             :command (str "ygg map package import <import-prefix> <ecosystem>:<package>"
                           (when map-path
                             (str " --map " (command/shell-token map-path))))}))))

(defn- refresh-command
  [config-path map-path]
  (str (sync-command config-path "--check")
       (when map-path
         (str " --map " (command/shell-token map-path)))))

(defn- next-actions
  [{:keys [project config-path map-path counts freshness]}]
  (let [{:keys [files search-docs system-nodes system-edges activity-items
                activity-events result-schema-mismatch-events
                result-schema-missing-result-items result-schema-unexpected-result-items
                diagnostics skipped-files
                system-evidence map-overlay]}
        counts
        project-id (:id project)
        stale-count (+ (get-in freshness [:counts :changed] 0)
                       (get-in freshness [:counts :missing] 0)
                       (get-in freshness [:counts :unindexed] 0))]
    (->> (cond-> []
           (zero? files)
           (conj {:kind :source-files
                  :label "Index and validate project source files"
                  :command (refresh-command config-path map-path)})

           (contains? #{:stale :unsynced} (:status freshness))
           (conj {:kind :freshness
                  :label "Refresh indexed graph basis"
                  :count stale-count
                  :command (refresh-command config-path map-path)})

           (zero? search-docs)
           (conj {:kind :docs
                  :label "Build query index"
                  :command (sync-command config-path "--query-index")})

           (zero? (+ system-nodes system-edges))
           (conj {:kind :systems
                  :label "Inspect system graph"
                  :command (view-systems-command project-id)})

           true
           (into (package-next-actions project-id counts
                                       {:config-path config-path
                                        :map-path map-path}))

           (zero? (long (or (:map-file map-overlay) 0)))
           (conj {:kind :map-overlay
                  :label "Initialize editable graph map"
                  :command (map-init-command config-path map-path)})

           (zero? (+ activity-items activity-events))
           (conj (merge {:kind :activity
                         :label "Import local activity and work rows"
                         :mcpTool "ygg_sync_activity"
                         :command (sync-subcommand "activity" config-path "--json")}
                        (when config-path
                          {:mcpArgs {:configPath config-path}})))

           (zero? (validation-history-count counts))
           (conj (validation-history-action project-id config-path map-path))

           (pos? result-schema-mismatch-events)
           (conj (merge {:kind :activity
                         :label "Inspect result schema mismatch activity"
                         :count result-schema-mismatch-events
                         :mcpTool "ygg_sync_activity"
                         :command (sync-subcommand "activity" config-path "--json")}
                        (when config-path
                          {:mcpArgs {:configPath config-path}})))

           (pos? (+ (long (or result-schema-missing-result-items 0))
                    (long (or result-schema-unexpected-result-items 0))))
           (conj (merge {:kind :activity
                         :label "Inspect result schema status activity"
                         :count (+ (long (or result-schema-missing-result-items 0))
                                   (long (or result-schema-unexpected-result-items 0)))
                         :mcpTool "ygg_sync_activity"
                         :command (sync-subcommand "activity" config-path "--json")}
                        (when config-path
                          {:mcpArgs {:configPath config-path}})))

           (pos? skipped-files)
           (conj (skipped-source-action skipped-files config-path map-path))

           (pos? diagnostics)
           (conj (merge {:kind :coverage
                         :label "Inspect extractor diagnostics"
                         :count diagnostics
                         :command (sync-subcommand "coverage" config-path "--json")}
                        (status-mcp config-path map-path)))

           (zero? system-evidence)
           (conj (merge {:kind :system-evidence
                         :label "Inspect system evidence coverage"
                         :command (sync-subcommand "coverage" config-path "--json")}
                        (status-mcp config-path map-path)))

           (pos? files)
           (conj {:kind :audit-scope
                  :label "Inspect project audit scopes"
                  :command (audit-scope-command config-path map-path)})

           true
           (conj {:kind :ask
                  :label "Ask a graph-grounded implementation question"
                  :command (ask-command project-id)}))
         (distinct-by action-distinct-key)
         (take 12)
         vec)))

(defn- next-commands
  [actions]
  (mapv :command actions))

(def packet-freshness-action-kinds
  #{:source-files :freshness :docs :coverage})

(defn packet-freshness
  "Return the bounded freshness value used by primary context packets."
  [summary]
  (let [actions (->> (:nextActions summary)
                     (filter #(contains? packet-freshness-action-kinds (:kind %)))
                     vec)]
    (cond-> (:freshness summary)
      (seq actions) (assoc :nextActions actions))))

(defn- compact-connectivity
  [connectivity]
  (when (seq connectivity)
    (cond-> (select-keys connectivity
                         [:indexedFiles
                          :nodes
                          :edges
                          :connectedFiles
                          :crossFileConnectedFiles
                          :isolatedFiles])
      (seq (:byKind connectivity))
      (assoc :byKind (vec (take 5 (:byKind connectivity)))))))

(defn status-coverage
  "Return compact coverage fields for agent-facing project status packets."
  [summary]
  (let [counts (:counts summary)
        connectivity (compact-connectivity (:indexedConnectivity summary))]
    (cond-> {:counts {:files (:files counts 0)
                      :skippedFiles (:skipped-files counts 0)
                      :diagnostics (:diagnostics counts 0)}}
      connectivity
      (assoc :connectivity connectivity)

      (seq (:top-file-kinds summary))
      (assoc :topFileKinds (vec (take 5 (:top-file-kinds summary))))

      (seq (:extractors summary))
      (assoc :extractors (vec (take 5 (:extractors summary))))

      (seq (:extractor-fingerprints summary))
      (assoc :extractorFingerprints (vec (take 5 (:extractor-fingerprints summary))))

      (seq (:skipped-by-extension summary))
      (assoc :skippedByExtension (vec (take 5 (:skipped-by-extension summary))))

      (seq (:skipped-by-reason summary))
      (assoc :skippedByReason (vec (take 5 (:skipped-by-reason summary))))

      (seq (:diagnostics summary))
      (assoc :diagnostics (cond-> (select-keys (:diagnostics summary)
                                               [:total :by-stage :by-extractor])
                            (seq (get-in summary [:diagnostics :samples]))
                            (assoc :samples
                                   (vec (take 5
                                              (get-in summary
                                                      [:diagnostics :samples])))))))))

(def freshness-sample-limit
  8)

(defn- path-sample
  [repo-id path]
  {:repo-id repo-id
   :path path})

(defn- current-files
  [{:keys [id root]}]
  (try
    {:repo-id id
     :root root
     :files (fs/scan-files root)}
    (catch Exception e
      {:repo-id id
       :root root
       :error {:class (.getName (class e))
               :message (ex-message e)}})))

(defn- repo-freshness
  [indexed-files {:keys [id root] :as repo}]
  (let [scan (current-files repo)
        indexed (filter #(= id (:repo-id %)) indexed-files)
        indexed-by-path (into {} (map (juxt :path identity)) indexed)
        current-by-path (into {} (map (juxt :path identity)) (:files scan))
        indexed-paths (set (keys indexed-by-path))
        current-paths (set (keys current-by-path))
        missing (sort (remove current-paths indexed-paths))
        unindexed (sort (remove indexed-paths current-paths))
        changed (->> (set/intersection indexed-paths current-paths)
                     (filter (fn [path]
                               (not= (:content-sha (get indexed-by-path path))
                                     (:content-sha (get current-by-path path)))))
                     sort
                     vec)
        status (cond
                 (:error scan) :unknown
                 (and (zero? (count indexed))
                      (zero? (count (:files scan)))) :empty
                 (zero? (count indexed)) :unsynced
                 (seq (concat missing unindexed changed)) :stale
                 :else :current)]
    (cond-> {:repo-id id
             :root root
             :status status
             :counts {:indexed (count indexed)
                      :current (count (:files scan))
                      :changed (count changed)
                      :missing (count missing)
                      :unindexed (count unindexed)}}
      (:error scan) (assoc :error (:error scan))
      (seq changed) (assoc-in [:samples :changed]
                              (mapv #(path-sample id %) (take freshness-sample-limit changed)))
      (seq missing) (assoc-in [:samples :missing]
                              (mapv #(path-sample id %) (take freshness-sample-limit missing)))
      (seq unindexed) (assoc-in [:samples :unindexed]
                                (mapv #(path-sample id %) (take freshness-sample-limit unindexed))))))

(defn- freshness-status
  [repo-statuses]
  (cond
    (some #(= :unknown (:status %)) repo-statuses) :unknown
    (some #(= :stale (:status %)) repo-statuses) :stale
    (some #(= :unsynced (:status %)) repo-statuses) :unsynced
    (every? #(= :empty (:status %)) repo-statuses) :empty
    :else :current))

(defn- add-counts
  [a b]
  (merge-with + a b))

(defn- freshness-summary
  [indexed-files project repo-id]
  (let [repos (cond->> (:repos project)
                repo-id (filter #(= repo-id (:id %))))
        repo-statuses (mapv #(repo-freshness indexed-files %) repos)
        counts (reduce add-counts
                       {:indexed 0
                        :current 0
                        :changed 0
                        :missing 0
                        :unindexed 0}
                       (map :counts repo-statuses))]
    {:status (freshness-status repo-statuses)
     :counts counts
     :repos repo-statuses}))

(defn- query-index-missing?
  [counts]
  (zero? (long (or (:search-docs counts) 0))))

(defn- freshness-basis
  [counts]
  (if (pos? (long (or (:files counts) 0)))
    "indexed-graph"
    "none"))

(defn- freshness-readiness-status
  [status counts]
  (if (and (= :current status)
           (pos? (long (or (:files counts) 0)))
           (query-index-missing? counts))
    :partial
    status))

(defn- annotate-freshness
  [freshness counts {:keys [config-path map-path]}]
  (cond-> (assoc freshness
                 :status (freshness-readiness-status (:status freshness) counts)
                 :basis (freshness-basis counts)
                 :missingQueryIndex (query-index-missing? counts))
    (seq (str config-path)) (assoc :projectConfig config-path)
    (seq (str map-path)) (assoc :map map-path
                                :mapExists (boolean (map-exists? map-path)))))

(defn summarize
  "Return a project-level mechanical evidence summary.

  This is an inventory, not a per-question semantic confidence claim."
  [xtdb project {:keys [repo-id map-overlay config-path map-path read-context] :as opts}]
  (let [scope (scope {:project-id (:id project)
                      :repo-id repo-id
                      :read-context read-context})
        coverage-report (coverage/project-coverage xtdb project opts)
        package-report (dependency/package-report xtdb
                                                  {:project-id (:id project)
                                                   :repo-id repo-id}
                                                  {:map-overlay map-overlay
                                                   :limit 0})
        files (active-rows xtdb (:files store/tables) scope)
        file-facts (active-rows xtdb (:file-facts store/tables) scope)
        nodes (filter active? (query/all-nodes xtdb scope))
        edges (filter active? (query/all-edges xtdb scope))
        chunks (filter active? (query/all-chunks xtdb scope))
        search-docs (query/all-search-docs xtdb scope)
        embeddings (query/all-embeddings xtdb scope)
        system-evidence (query/all-system-evidence xtdb scope)
        runtime-config-evidence (filter runtime-config-evidence? system-evidence)
        auth-evidence (filter auth-evidence? system-evidence)
        system-nodes (query/all-system-nodes xtdb {:project-id (:id project)
                                                   :read-context read-context})
        system-edges (query/all-system-edges xtdb {:project-id (:id project)
                                                   :read-context read-context})
        activity-items (activity/all-items xtdb {:project-id (:id project)
                                                 :read-context read-context})
        activity-events (activity/all-events xtdb {:project-id (:id project)
                                                   :read-context read-context})
        validation-events (filter #(= :validation (:event-kind %)) activity-events)
        result-schema-mismatch-events (filter #(= :result-schema-mismatch
                                                  (:event-kind %))
                                              activity-events)
        freshness (freshness-summary files project repo-id)
        counts (merge {:files (count files)
                       :file-facts (count file-facts)
                       :nodes (count nodes)
                       :edges (count edges)
                       :chunks (count chunks)
                       :search-docs (count search-docs)
                       :embeddings (count embeddings)
                       :system-evidence (count system-evidence)
                       :runtime-config-evidence (count runtime-config-evidence)
                       :auth-references (count auth-evidence)
                       :service-account-references (auth-context-count auth-evidence
                                                                       "service-account")
                       :secret-references (auth-context-count auth-evidence "secret")
                       :private-key-references (auth-context-count auth-evidence
                                                                   "private-key")
                       :api-key-references (auth-context-count auth-evidence "api-key")
                       :system-nodes (count system-nodes)
                       :system-edges (count system-edges)
                       :packages (get-in package-report [:counts :packages] 0)
                       :package-versions (get-in package-report [:counts :versions] 0)
                       :package-imports (get-in package-report [:counts :imports-package] 0)
                       :source-import-candidates (get-in package-report
                                                         [:counts :source-import-candidates]
                                                         0)
                       :package-conflicts (get-in package-report [:counts :version-conflicts] 0)
                       :package-evidence-gaps (get-in package-report
                                                      [:counts :declared-without-import-evidence]
                                                      0)
                       :unresolved-imports (get-in package-report [:counts :unresolved-imports] 0)
                       :activity-items (count activity-items)
                       :activity-events (count activity-events)
                       :validation-events (count validation-events)
                       :result-schema-mismatch-events (count result-schema-mismatch-events)
                       :diagnostics (get-in coverage-report [:diagnostics :total] 0)
                       :skipped-files (get-in coverage-report [:totals :skipped] 0)
                       :map-overlay (overlay-counts map-overlay map-path)}
                      (activity/result-schema-counts activity-items))
        freshness (annotate-freshness freshness counts opts)
        available-families (available counts)
        families (evidence-families counts available-families)
        diagnostics (select-keys (:diagnostics coverage-report)
                                 [:total :by-stage :by-extractor])
        kinds (evidence-kinds {:files files
                               :file-facts file-facts
                               :nodes nodes
                               :edges edges
                               :system-evidence system-evidence})
        state (evidence-state freshness diagnostics counts)
        actions (next-actions {:project project
                               :config-path config-path
                               :map-path map-path
                               :counts counts
                               :freshness freshness})]
    {:schema schema
     :project-id (:id project)
     :repo-id repo-id
     :freshness freshness
     :available available-families
     :families families
     :kinds kinds
     :state state
     :counts counts
     :top-file-kinds (vec (take 12 (:files-by-kind coverage-report)))
     :top-node-kinds (top-counts nodes :kind)
     :top-edge-relations (top-counts edges :relation)
     :extractors (vec (take 20 (:extractors coverage-report)))
     :indexedConnectivity (:indexedConnectivity coverage-report)
     :skipped-by-extension (vec (take 8 (:skipped-by-extension coverage-report)))
     :skipped-by-reason (vec (take 8 (:skipped-by-reason coverage-report)))
     :diagnostics diagnostics
     :packages {:counts (:counts package-report)
                :ecosystems (:ecosystems package-report)}
     :next (next-commands actions)
     :nextActions actions}))
