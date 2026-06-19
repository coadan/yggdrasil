(ns agraph.evidence
  "Mechanical evidence-surface summaries for agents and reports."
  (:require [agraph.activity :as activity]
            [agraph.command :as command]
            [agraph.coverage :as coverage]
            [agraph.dependency :as dependency]
            [agraph.fs :as fs]
            [agraph.query :as query]
            [agraph.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def schema
  "agraph.evidence/v2")

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
  (cond->> (store/all-rows xtdb table (store/read-context read-context))
    true (filter active?)
    project-id (filter #(= project-id (:project-id %)))
    repo-id (filter #(= repo-id (:repo-id %)))
    true vec))

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

(defn- overlay-counts
  [overlay]
  {:systems (count (:systems overlay))
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
                  :package-evidence-gaps
                  :package-conflicts
                  :unresolved-imports]
   :docs [:chunks :search-docs]
   :embeddings [:embeddings]
   :system-evidence [:system-evidence]
   :system-graph [:system-nodes :system-edges]
   :activity [:activity-items :activity-events]
   :validation-history [:validation-events :result-schema-mismatch-events]
   :map-overlay [:systems :docs :edges :rejects :package-imports]})

(defn- available
  [{:keys [files file-facts nodes edges chunks search-docs embeddings system-evidence
           system-nodes system-edges activity-items activity-events validation-events
           result-schema-mismatch-events packages map-overlay]}]
  (cond-> []
    (pos? files) (conj :source-files)
    (pos? file-facts) (conj :file-facts)
    (pos? (+ nodes edges)) (conj :source-graph)
    (pos? (+ chunks search-docs)) (conj :docs)
    (pos? embeddings) (conj :embeddings)
    (pos? system-evidence) (conj :system-evidence)
    (pos? (+ system-nodes system-edges)) (conj :system-graph)
    (pos? packages) (conj :dependencies)
    (pos? (+ activity-items activity-events)) (conj :activity)
    (pos? (+ validation-events result-schema-mismatch-events)) (conj :validation-history)
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

(defn- dependencies-weak?
  [counts]
  (or (positive-count? (:package-evidence-gaps counts))
      (positive-count? (:package-conflicts counts))
      (positive-count? (:unresolved-imports counts))))

(defn- source-files-weak?
  [counts]
  (or (positive-count? (:skipped-files counts))
      (positive-count? (:diagnostics counts))))

(defn- validation-history-weak?
  [counts]
  (positive-count? (:result-schema-mismatch-events counts)))

(defn- family-status
  [counts available-families family]
  (cond
    (and (= :source-files family)
         (source-files-weak? counts)) :weak
    (and (= :dependencies family)
         (dependencies-weak? counts)) :weak
    (and (= :validation-history family)
         (validation-history-weak? counts)) :weak
    (contains? available-families family) :available
    :else :missing))

(defn- evidence-families
  "Return compact project-level mechanical evidence-family readiness rows."
  [counts available-families]
  (let [available-families (set available-families)]
    (mapv (fn [family]
            (let [counts (family-counts counts family)]
              (cond-> {:family family
                       :status (family-status counts available-families family)}
                (seq counts) (assoc :counts counts))))
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
   :unresolved-imports (:unresolved-imports counts 0)})

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

(defn- package-command
  [project-id & args]
  (str "agraph packages --project " (command/shell-token (or project-id "<project-id>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- sync-command
  [config-path & args]
  (str "agraph sync " (command/shell-token (or config-path "<project.edn>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- sync-subcommand
  [subcommand config-path & args]
  (str "agraph sync " subcommand " " (command/shell-token (or config-path "<project.edn>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- view-systems-command
  [project-id]
  (str "agraph view systems --project " (command/shell-token (or project-id "<project-id>"))))

(defn- ask-command
  [project-id]
  (str "agraph ask \"where is this handled?\" --project "
       (command/shell-token (or project-id "<project-id>"))
       " --json"))

(defn- audit-scope-command
  [config-path map-path]
  (str "agraph audit-scope "
       (command/shell-token (or config-path "<project.edn>"))
       (when map-path
         (str " --map " (command/shell-token map-path)))
       " --json"))

(defn- package-next-actions
  [project-id {:keys [packages package-evidence-gaps unresolved-imports package-conflicts]}
   {:keys [config-path map-path]}]
  (let [check-command (str (sync-subcommand "check" config-path)
                           (when map-path
                             (str " --map " (command/shell-token map-path))))]
    (cond-> []
      (zero? (long (or packages 0)))
      (conj {:kind :dependencies
             :label "Inspect package graph facts"
             :command (package-command project-id "--json")})

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
             :command (str check-command " --enqueue")}))))

(defn- refresh-command
  [config-path map-path]
  (str (sync-command config-path "--check")
       (when map-path
         (str " --map " (command/shell-token map-path)))))

(defn- next-actions
  [{:keys [project config-path map-path counts freshness]}]
  (let [{:keys [files search-docs system-nodes system-edges activity-items
                activity-events result-schema-mismatch-events diagnostics skipped-files
                system-evidence]}
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

           (zero? (+ activity-items activity-events))
           (conj (merge {:kind :activity
                         :label "Import local activity and work rows"
                         :mcpTool "agraph_sync_activity"
                         :command (sync-subcommand "activity" config-path "--json")}
                        (when config-path
                          {:mcpArgs {:configPath config-path}})))

           (pos? result-schema-mismatch-events)
           (conj (merge {:kind :activity
                         :label "Inspect result schema mismatch activity"
                         :count result-schema-mismatch-events
                         :mcpTool "agraph_sync_activity"
                         :command (sync-subcommand "activity" config-path "--json")}
                        (when config-path
                          {:mcpArgs {:configPath config-path}})))

           (pos? skipped-files)
           (conj {:kind :coverage
                  :label "Inspect skipped source candidates"
                  :count skipped-files
                  :command (sync-subcommand "coverage" config-path "--json")})

           (pos? diagnostics)
           (conj {:kind :coverage
                  :label "Inspect extractor diagnostics"
                  :count diagnostics
                  :command (sync-subcommand "coverage" config-path "--json")})

           (zero? system-evidence)
           (conj {:kind :system-evidence
                  :label "Inspect system evidence coverage"
                  :command (sync-subcommand "coverage" config-path "--json")})

           (pos? files)
           (conj {:kind :audit-scope
                  :label "Inspect project audit scopes"
                  :command (audit-scope-command config-path map-path)})

           true
           (conj {:kind :ask
                  :label "Ask a graph-grounded implementation question"
                  :command (ask-command project-id)}))
         (distinct-by :command)
         (take 8)
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

(defn status-coverage
  "Return compact coverage fields for agent-facing project status packets."
  [summary]
  (let [counts (:counts summary)]
    (cond-> {:counts {:files (:files counts 0)
                      :skippedFiles (:skipped-files counts 0)
                      :diagnostics (:diagnostics counts 0)}}
      (seq (:top-file-kinds summary))
      (assoc :topFileKinds (vec (take 5 (:top-file-kinds summary))))

      (seq (:extractors summary))
      (assoc :extractors (vec (take 5 (:extractors summary))))

      (seq (:diagnostics summary))
      (assoc :diagnostics (select-keys (:diagnostics summary)
                                       [:total :by-stage :by-extractor])))))

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

(defn- map-exists?
  [map-path]
  (when (seq (str map-path))
    (.exists (io/file map-path))))

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

  This is an inventory, not a per-question answerability claim."
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
        counts {:files (count files)
                :file-facts (count file-facts)
                :nodes (count nodes)
                :edges (count edges)
                :chunks (count chunks)
                :search-docs (count search-docs)
                :embeddings (count embeddings)
                :system-evidence (count system-evidence)
                :system-nodes (count system-nodes)
                :system-edges (count system-edges)
                :packages (get-in package-report [:counts :packages] 0)
                :package-versions (get-in package-report [:counts :versions] 0)
                :package-imports (get-in package-report [:counts :imports-package] 0)
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
                :map-overlay (overlay-counts map-overlay)}
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
     :skipped-by-extension (vec (take 8 (:skipped-by-extension coverage-report)))
     :skipped-by-reason (vec (take 8 (:skipped-by-reason coverage-report)))
     :diagnostics diagnostics
     :packages {:counts (:counts package-report)
                :ecosystems (:ecosystems package-report)}
     :next (next-commands actions)
     :nextActions actions}))
