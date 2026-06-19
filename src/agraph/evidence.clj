(ns agraph.evidence
  "Mechanical evidence-surface summaries for agents and reports."
  (:require [agraph.activity :as activity]
            [agraph.coverage :as coverage]
            [agraph.dependency :as dependency]
            [agraph.query :as query]
            [agraph.xtdb :as store]
            [clojure.string :as str]))

(def schema
  "agraph.evidence/v1")

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

(defn- available
  [{:keys [files nodes edges chunks search-docs embeddings system-nodes system-edges
           activity-items activity-events validation-events packages map-overlay]}]
  (cond-> []
    (pos? (+ nodes edges)) (conj :source-graph)
    (pos? (+ chunks search-docs)) (conj :docs)
    (pos? embeddings) (conj :embeddings)
    (pos? (+ system-nodes system-edges)) (conj :systems)
    (pos? packages) (conj :dependencies)
    (pos? (+ activity-items activity-events)) (conj :activity)
    (pos? validation-events) (conj :validation-history)
    (pos? (reduce + (vals map-overlay))) (conj :map-overlay)
    (pos? files) vec))

(defn- positive-count?
  [value]
  (pos? (long (or value 0))))

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

(defn- shell-token
  [value]
  (let [value (str value)]
    (if (or (re-matches #"<[^>]+>" value)
            (re-matches #"[A-Za-z0-9_./:=+@%-]+" value))
      value
      (str "'" (str/replace value #"'" "'\"'\"'") "'"))))

(defn- package-command
  [project-id & args]
  (str "agraph packages --project " (shell-token (or project-id "<project-id>"))
       (when (seq args)
         (str " " (str/join " " (map shell-token args))))))

(defn- sync-command
  [config-path & args]
  (str "agraph sync " (shell-token (or config-path "<project.edn>"))
       (when (seq args)
         (str " " (str/join " " (map shell-token args))))))

(defn- sync-subcommand
  [subcommand config-path & args]
  (str "agraph sync " subcommand " " (shell-token (or config-path "<project.edn>"))
       (when (seq args)
         (str " " (str/join " " (map shell-token args))))))

(defn- view-systems-command
  [project-id]
  (str "agraph view systems --project " (shell-token (or project-id "<project-id>"))))

(defn- ask-command
  [project-id]
  (str "agraph ask \"where is this handled?\" --project "
       (shell-token (or project-id "<project-id>"))
       " --json"))

(defn- package-next-actions
  [project-id {:keys [packages package-evidence-gaps unresolved-imports package-conflicts]}
   {:keys [config-path map-path]}]
  (let [check-command (str (sync-subcommand "check" config-path)
                           (when map-path
                             (str " --map " (shell-token map-path))))]
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

(defn- next-actions
  [{:keys [project config-path map-path counts]}]
  (let [{:keys [files search-docs system-nodes system-edges activity-items
                activity-events diagnostics]} counts
        project-id (:id project)]
    (->> (cond-> []
           (zero? files)
           (conj {:kind :source-files
                  :label "Index and validate project source files"
                  :command (str (sync-command config-path "--check")
                                (when map-path
                                  (str " --map " (shell-token map-path))))})

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
           (conj {:kind :activity
                  :label "Import local activity and work rows"
                  :command (sync-subcommand "activity" config-path "--json")})

           (pos? diagnostics)
           (conj {:kind :coverage
                  :label "Inspect extractor diagnostics"
                  :count diagnostics
                  :command (sync-subcommand "coverage" config-path "--json")})

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
        nodes (filter active? (query/all-nodes xtdb scope))
        edges (filter active? (query/all-edges xtdb scope))
        chunks (filter active? (query/all-chunks xtdb scope))
        search-docs (query/all-search-docs xtdb scope)
        embeddings (query/all-embeddings xtdb scope)
        system-nodes (query/all-system-nodes xtdb {:project-id (:id project)
                                                   :read-context read-context})
        system-edges (query/all-system-edges xtdb {:project-id (:id project)
                                                   :read-context read-context})
        activity-items (activity/all-items xtdb {:project-id (:id project)
                                                 :read-context read-context})
        activity-events (activity/all-events xtdb {:project-id (:id project)
                                                   :read-context read-context})
        validation-events (filter #(= :validation (:event-kind %)) activity-events)
        counts {:files (count files)
                :nodes (count nodes)
                :edges (count edges)
                :chunks (count chunks)
                :search-docs (count search-docs)
                :embeddings (count embeddings)
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
                :diagnostics (get-in coverage-report [:diagnostics :total] 0)
                :skipped-files (get-in coverage-report [:totals :skipped] 0)
                :map-overlay (overlay-counts map-overlay)}
        actions (next-actions {:project project
                               :config-path config-path
                               :map-path map-path
                               :counts counts})]
    {:schema schema
     :project-id (:id project)
     :repo-id repo-id
     :available (available counts)
     :counts counts
     :top-file-kinds (vec (take 12 (:files-by-kind coverage-report)))
     :top-node-kinds (top-counts nodes :kind)
     :top-edge-relations (top-counts edges :relation)
     :extractors (vec (take 20 (:extractors coverage-report)))
     :skipped-by-extension (vec (take 8 (:skipped-by-extension coverage-report)))
     :skipped-by-reason (vec (take 8 (:skipped-by-reason coverage-report)))
     :diagnostics (select-keys (:diagnostics coverage-report)
                               [:total :by-stage :by-extractor])
     :packages {:counts (:counts package-report)
                :ecosystems (:ecosystems package-report)}
     :next (next-commands actions)
     :nextActions actions}))
