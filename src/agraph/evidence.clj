(ns agraph.evidence
  "Mechanical evidence-surface summaries for agents and reports."
  (:require [agraph.activity :as activity]
            [agraph.coverage :as coverage]
            [agraph.dependency :as dependency]
            [agraph.query :as query]
            [agraph.xtdb :as store]))

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
    (pos? packages) (conj :packages)
    (pos? (+ activity-items activity-events)) (conj :activity)
    (pos? validation-events) (conj :validation-history)
    (pos? (reduce + (vals map-overlay))) (conj :map-overlay)
    (pos? files) vec))

(defn- next-commands
  [{:keys [project config-path map-path counts]}]
  (let [{:keys [files search-docs system-nodes system-edges packages activity-items
                activity-events diagnostics]} counts
        project-id (:id project)]
    (->> (cond-> []
           (zero? files)
           (conj (str "agraph sync " (or config-path "<project.edn>") " --check"
                      (when map-path (str " --map " map-path))))

           (zero? search-docs)
           (conj (str "agraph sync " (or config-path "<project.edn>") " --query-index"))

           (zero? (+ system-nodes system-edges))
           (conj (str "agraph view systems --project " project-id))

           (zero? packages)
           (conj (str "agraph packages --project " project-id " --json"))

           (zero? (+ activity-items activity-events))
           (conj (str "agraph sync activity " (or config-path "<project.edn>") " --json"))

           (pos? diagnostics)
           (conj (str "agraph sync coverage " (or config-path "<project.edn>") " --json"))

           true
           (conj (str "agraph ask \"where is this handled?\" --project " project-id " --json")))
         distinct
         (take 8)
         vec)))

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
                :map-overlay (overlay-counts map-overlay)}]
    {:schema schema
     :project-id (:id project)
     :repo-id repo-id
     :available (available counts)
     :counts counts
     :top-file-kinds (vec (take 12 (:files-by-kind coverage-report)))
     :top-node-kinds (top-counts nodes :kind)
     :top-edge-relations (top-counts edges :relation)
     :extractors (vec (take 20 (:extractors coverage-report)))
     :skipped-by-reason (vec (take 8 (:skipped-by-reason coverage-report)))
     :diagnostics (select-keys (:diagnostics coverage-report)
                               [:total :by-stage :by-extractor])
     :packages {:counts (:counts package-report)
                :ecosystems (:ecosystems package-report)}
     :next (next-commands {:project project
                           :config-path config-path
                           :map-path map-path
                           :counts counts})}))
