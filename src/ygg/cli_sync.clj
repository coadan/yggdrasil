(ns ygg.cli-sync
  (:require [ygg.activity :as activity]
            [ygg.cli-options :refer [dry-run? json-output? option-value parse-double-option parse-limit positional-args]]
            [ygg.corrections :as corrections]
            [ygg.coverage :as coverage]
            [ygg.index-maintenance :as index-maintenance]
            [ygg.index-maintenance-worker :as index-maintenance-worker]
            [ygg.progress :as progress]
            [ygg.project :as project]
            [ygg.project-registry :as registry]
            [ygg.queue :as queue]
            [ygg.xtdb :as store]
            [clojure.string :as str]))

(def ^:dynamic *deps* {})

(defn- call-dep
  [k & args]
  (apply (or (get *deps* k)
             (throw (ex-info "Missing CLI sync dependency." {:dependency k})))
         args))

(defn- usage [] (call-dep :usage))
(defn- print-json [data] (call-dep :print-json data))
(defn- enqueue-output? [args] (call-dep :enqueue-output? args))
(defn- queue-db [args] (call-dep :queue-db args))
(defn- queue-priority
  ([args] (call-dep :queue-priority args))
  ([args default] (call-dep :queue-priority args default)))
(defn- print-source-coverage [report] (call-dep :print-source-coverage report))
(defn- print-sync-summary [result] (call-dep :print-sync-summary result))
(defn- print-project-add-repo-summary [result] (call-dep :print-project-add-repo-summary result))
(defn- queue-agent [args] (call-dep :queue-agent args))
(defn- queue-lease-ms [args] (call-dep :queue-lease-ms args))
(defn- apply-work-result! [xtdb queue-db id]
  (call-dep :apply-work-result! xtdb queue-db id))

(defn- correction-overlay
  [xtdb project-id]
  (when (store/xtdb-handle? xtdb)
    (corrections/overlay xtdb project-id)))
(defn- validate-work-result [queue-db id]
  (call-dep :validate-work-result queue-db id))
(defn- dispatch [command args] (call-dep :dispatch command args))
(defn- print-project-status!
  ([config-path args] (call-dep :print-project-status! config-path args))
  ([project config-path args] (call-dep :print-project-status! project config-path args)))

(defn- print-node-finding
  [{:keys [label kind repo-id path-prefix]}]
  (println (str "- " label
                " [" (name kind) "]"
                " repo " repo-id
                (when path-prefix (str " path " path-prefix)))))
(defn- print-edge-finding
  [{:keys [relation confidence source-id target-id]}]
  (println "-" (name relation) (format "%.2f" (double confidence)) source-id "->" target-id))
(defn print-maintenance-report
  [report]
  (let [{:keys [project-id graph-basis map counts scale graph-health fold-in orphaned-systems
                dangling-edges low-confidence-edges decision-summary
                external-api-review decision-queue infra-review-queue dependency-review-queue]}
        (index-maintenance/graph-report report)]
    (println "# Index Maintain")
    (println "- project" (:project-id report project-id))
    (println "- schema" (:schema report))
    (when-let [lanes (seq (:lanes report))]
      (println "- lanes" (str/join "," (clojure.core/map (comp name key) lanes))))
    (when graph-basis
      (println "- graph-basis" (:hash graph-basis)))
    (when map
      (println "- correction-rejects" (:rejects map))
      (println "- correction-rejected-systems" (:rejected-systems map)))
    (doseq [[k v] counts]
      (println "-" (name k) v))
    (when scale
      (println "- scale" (name (:tier scale)))
      (println "- noise-ratio" (format "%.2f" (double (get-in scale [:ratios :noise]))))
      (println "- orphan-ratio" (format "%.2f" (double (get-in scale [:ratios :orphaned])))))
    (when (pos? (long (:total decision-summary 0)))
      (println "- decision-summary"
               "severity"
               (str/join ","
                         (clojure.core/map (fn [{:keys [severity count]}]
                                             (str (name severity) ":" count))
                                           (:bySeverity decision-summary)))
               "kind"
               (str/join ","
                         (clojure.core/map (fn [{:keys [kind count]}]
                                             (str (name kind) ":" count))
                                           (:byKind decision-summary)))
               "action"
               (str/join ","
                         (clojure.core/map (fn [{:keys [action count]}]
                                             (str (name action) ":" count))
                                           (:byRecommendedAction decision-summary)))))
    (when (seq (or (:high-degree-hubs graph-health) (:top-hubs scale)))
      (println)
      (println "## Top Hubs")
      (doseq [{:keys [label kind repo-id degree]} (take 10 (or (:high-degree-hubs graph-health)
                                                               (:top-hubs scale)))]
        (println "-" label "[" (name kind) "]" "repo" repo-id "degree" degree)))
    (when (seq (:cross-cluster-edges graph-health))
      (println)
      (println "## Cross Cluster Edges")
      (doseq [{:keys [relation salience source target]} (take 10 (:cross-cluster-edges graph-health))]
        (println "-"
                 (name relation)
                 (format "%.2f" (double (or salience 0.0)))
                 (:label source)
                 "->"
                 (:label target))))
    (when (seq (:evidence-concentrations graph-health))
      (println)
      (println "## Evidence Concentrations")
      (doseq [{:keys [system kind count]} (take 10 (:evidence-concentrations graph-health))]
        (println "-"
                 (:label system)
                 "[" (name kind) "]"
                 "count"
                 count)))
    (when (or (pos? (long (get-in external-api-review [:counts :nodes] 0)))
              (seq (:source-fanouts external-api-review)))
      (println)
      (println "## External API Review")
      (doseq [[k v] (:counts external-api-review)]
        (println "-" (name k) v))
      (when (seq (:source-fanouts external-api-review))
        (println)
        (println "### Source Fanouts")
        (doseq [{:keys [peer relation visibility direction target-count evidence-count]} (take 10 (:source-fanouts external-api-review))]
          (println "-"
                   (:label peer)
                   (or (some-> relation name) relation)
                   (or (some-> direction name) direction)
                   "visibility"
                   visibility
                   "targets"
                   target-count
                   "evidence"
                   evidence-count))))
    (when (seq orphaned-systems)
      (println)
      (println "## Orphaned Systems")
      (doseq [node (take 20 orphaned-systems)]
        (print-node-finding node)))
    (when (seq dangling-edges)
      (println)
      (println "## Dangling Edges")
      (doseq [edge (take 20 dangling-edges)]
        (print-edge-finding edge)))
    (when (seq low-confidence-edges)
      (println)
      (println "## Low Confidence Edges")
      (doseq [edge (take 20 low-confidence-edges)]
        (print-edge-finding edge)))
    (when (seq decision-queue)
      (println)
      (println "## Decision Queue")
      (doseq [{:keys [id kind severity target reason]} (take 20 decision-queue)]
        (println "-" (name severity) (name kind) target "-" reason)
        (println " " id)))
    (when (seq infra-review-queue)
      (println)
      (println "## Infra Review Queue")
      (doseq [{:keys [reviewId kind artifact question]} (take 20 infra-review-queue)]
        (println "-" kind artifact "-" question)
        (println " " reviewId)))
    (when (seq dependency-review-queue)
      (println)
      (println "## Dependency Review Queue")
      (doseq [{:keys [reviewId question facts]} (take 20 dependency-review-queue)]
        (let [unresolved (:unresolvedImport facts)]
          (println "-"
                   (:import unresolved)
                   (when-let [path (:path unresolved)]
                     (str path (when-let [line (:line unresolved)]
                                 (str ":" line))))
                   "-"
                   question)
          (println " " reviewId))))
    (when (seq (:actions fold-in))
      (println)
      (println "## Fold In")
      (doseq [{:keys [kind command reason]} (:actions fold-in)]
        (println "-" (name kind) command)
        (println " " reason)))))
(defn- repo-run-summary
  [run]
  {:repo-id (:repo-id run)
   :status (:status run)
   :index-profile (:index-profile run)
   :stats (:stats run)})
(defn query-index?
  [args]
  (some #{"--query-index"} args))
(defn- sync-index-profile
  [args]
  (if (and (not (query-index? args))
           (or (some #{"--check"} args)
               (enqueue-output? args)))
    :graph
    :query))
(defn- progress-output?
  [args]
  (and (not (json-output? args))
       (not (some #{"--no-progress"} args))))
(defn- sync-progress-fn
  [args]
  (when (progress-output? args)
    (let [printed-header? (atom false)]
      (fn [event]
        (when-let [line (progress/sync-progress-line event)]
          (binding [*out* *err*]
            (when (compare-and-set! printed-header? false true)
              (println "# Sync Progress"))
            (println line)
            (flush)))))))
(defn- sync-index-options
  [xtdb project args opts]
  (let [external-progress? (contains? opts :progress-fn)
        progress-fn (if external-progress?
                      (:progress-fn opts)
                      (sync-progress-fn args))]
    (cond-> {:dry-run? (dry-run? args)
             :index-profile (or (:index-profile opts)
                                (sync-index-profile args))
             :correction-overlay (correction-overlay xtdb (:id project))}
      progress-fn (assoc :progress-fn progress-fn))))

(defn- sync-index-project-with-options!
  [xtdb project args opts]
  (let [index-opts (sync-index-options xtdb project args opts)]
    (if-let [repo-id (option-value args "--repo")]
      (let [run (project/index-project-repo! xtdb project repo-id index-opts)]
        {:project-id (:id project)
         :status (:status run)
         :repos [(repo-run-summary run)]})
      (project/index-project! xtdb project index-opts))))

(defn sync-index-project!
  ([xtdb project args]
   (sync-index-project-with-options! xtdb project args {}))
  ([xtdb project args deps opts]
   (binding [*deps* deps]
     (sync-index-project-with-options! xtdb project args opts))))
(defn maintenance-report
  ([xtdb project args]
   (let [graph-report (project/maintain-project
                       xtdb
                       project
                       {:low-confidence-threshold (parse-double-option args
                                                                       "--min-confidence"
                                                                       0.60)
                        :correction-overlay (correction-overlay xtdb (:id project))})]
     (index-maintenance/from-graph-report graph-report
                                          (get-in project [:maintenance :work]))))
  ([xtdb project args deps]
   (binding [*deps* deps]
     (maintenance-report xtdb project args))))
(defn- enqueue-work-item!
  [args {:keys [kind payload priority project-id source]}]
  (queue/item-summary
   (queue/enqueue!
    payload
    {:root (if-not (str/blank? (str project-id))
             (store/project-sqlite-path project-id)
             (queue-db args))
     :kind kind
     :project-id project-id
     :priority (queue-priority args priority)
     :source source})))

(declare project-queue-db)

(defn- work-identity
  [payload]
  (case (:schema payload)
    "ygg.frontier.decision/v1"
    [(:schema payload) (:decisionId payload)]

    "ygg.frontier.decision-batch/v1"
    [(:schema payload) (:batchId payload)]

    "ygg.infra.review-packet/v1"
    [(:schema payload) (:reviewId payload)]

    "ygg.infra.review-batch-packet/v1"
    [(:schema payload) (:batchId payload)]

    "ygg.dependency.review-packet/v1"
    [(:schema payload) (:reviewId payload)]

    "ygg.dependency.review-batch-packet/v1"
    [(:schema payload) (:batchId payload)]

    nil))

(defn- existing-work-summaries
  [root project-id]
  (into {}
        (keep (fn [found]
                (when-let [identity (work-identity (get-in found [:item :payload]))]
                  [identity (queue/item-summary found)])))
        (queue/list-items root {:project-id project-id})))

(def superseded-queue-statuses
  #{"ready" "failed"})

(defn- index-maintenance-work?
  [item]
  (let [source (:source item)]
    (and (= index-maintenance/source-schema (:schema source))
         (= index-maintenance/producer (:producer source))
         (= index-maintenance/graph-lane (:lane source)))))

(defn- current-identities-by-scope
  [args work-items]
  (reduce (fn [out {:keys [payload project-id]}]
            (if-let [identity (work-identity payload)]
              (update out
                      [(project-queue-db args project-id) project-id]
                      (fnil conj #{})
                      identity)
              out))
          {}
          work-items))

(defn- supersede-stale-work!
  [root project-id current-identities]
  (let [ids (into []
                  (keep (fn [{:keys [item]}]
                          (let [identity (work-identity (:payload item))]
                            (when (and identity
                                       (not (contains? current-identities identity))
                                       (contains? superseded-queue-statuses (:status item))
                                       (index-maintenance-work? item))
                              (:id item)))))
                  (queue/list-items root {:project-id project-id}))]
    (queue/reject-many! root
                        ids
                        "Superseded by newer index maintenance report.")))

(defn- enqueue-work-items!
  [args report work-items]
  (let [identities-by-scope (current-identities-by-scope args work-items)
        scopes (or (seq (keys identities-by-scope))
                   [[(project-queue-db args (:project-id report))
                     (:project-id report)]])
        _ (doseq [[queue-db-path _project-id] scopes]
            (queue/release-expired! queue-db-path))
        _ (doseq [[queue-db-path project-id :as scope] scopes]
            (supersede-stale-work! queue-db-path
                                   project-id
                                   (get identities-by-scope scope #{})))
        existing* (atom
                   (into {}
                         (map (fn [[queue-db-path project-id :as scope]]
                                [scope (existing-work-summaries queue-db-path
                                                                project-id)]))
                         scopes))]
    (mapv (fn [{:keys [payload project-id] :as item}]
            (let [queue-db-path (project-queue-db args project-id)
                  scope [queue-db-path project-id]
                  identity (work-identity payload)
                  existing (when identity
                             (get-in @existing* [scope identity]))]
              (if existing
                (assoc existing :enqueue-status "existing")
                (let [summary (assoc (enqueue-work-item! args item)
                                     :enqueue-status "enqueued")]
                  (when identity
                    (swap! existing* assoc-in [scope identity] summary))
                  summary))))
          work-items)))

(defn- project-queue-db
  [args project-id]
  (if-not (str/blank? (str project-id))
    (store/project-sqlite-path project-id)
    (queue-db args)))

(defn enqueue-sync-work!
  ([args report]
   (enqueue-work-items! args report (index-maintenance/work-items report)))
  ([args report deps]
   (binding [*deps* deps]
     (enqueue-sync-work! args report))))

(defn enqueue-summary
  [enqueued]
  (let [items (vec (or enqueued []))
        by-status (frequencies (map :enqueue-status items))
        by-kind (frequencies (map :kind items))
        existing (long (or (get by-status "existing") 0))]
    {:items (count items)
     :enqueued (long (or (get by-status "enqueued") 0))
     :existing existing
     :over-emitted existing
     :by-status by-status
     :by-kind by-kind}))

(defn- config-path
  [args]
  (first (positional-args args)))

(defn- resolve-project-ref
  [args]
  (registry/resolve-project {:project-id (option-value args "--project")
                             :config-path (config-path args)
                             :cwd (System/getProperty "user.dir")}))

(defn- sync-coverage!
  [args]
  (let [{:keys [project]} (resolve-project-ref args)]
    (store/with-node (store/storage-path (:id project))
      (fn [xtdb]
        (let [report (coverage/project-coverage xtdb project {})]
          (if (json-output? args)
            (print-json report)
            (print-source-coverage report)))))))
(defn- print-activity-sync
  [{:keys [project-id queue-db counts result-schema-mismatches]}]
  (println "# Activity Sync")
  (println "- project" project-id)
  (println "- queue-db" queue-db)
  (println "- items" (:items counts 0))
  (println "- events" (:events counts 0))
  (println "- validation-events" (:validation-events counts 0))
  (println "- result-schema-mismatch-events" (:result-schema-mismatch-events counts 0))
  (println "- ready" (:ready counts 0))
  (println "- claimed" (:claimed counts 0))
  (println "- done" (:done counts 0))
  (println "- rejected" (:rejected counts 0))
  (println "- failed" (:failed counts 0))
  (when (seq result-schema-mismatches)
    (println)
    (println "## Result Schema Mismatches")
    (doseq [{:keys [sourceId itemId expectedResultSchema resultSchema status]} result-schema-mismatches]
      (println "-" sourceId
               "expected" expectedResultSchema
               "actual" resultSchema
               "status" status
               "item" itemId))))
(defn- sync-activity!
  [args]
  (let [{:keys [project]} (resolve-project-ref args)]
    (store/with-node (store/storage-path (:id project))
      (fn [xtdb]
        (let [result (activity/sync-queue! xtdb
                                           project
                                           {:queue-db (project-queue-db args
                                                                        (:id project))})]
          (if (json-output? args)
            (print-json result)
            (print-activity-sync result)))))))
(defn- sync-project!
  [args]
  (let [{:keys [project]} (resolve-project-ref args)
        repo-id (option-value args "--repo")
        check? (or (some #{"--check"} args)
                   (enqueue-output? args))]
    (if (dry-run? args)
      (let [index-summary (sync-index-project! nil project args)
            result {:schema "ygg.sync/v1"
                    :project-id (:id project)
                    :repo-id repo-id
                    :index-summary index-summary}]
        (if (json-output? args)
          (print-json result)
          (print-sync-summary result)))
      (store/with-node (store/storage-path (:id project))
        (fn [xtdb]
          (let [index-summary (sync-index-project! xtdb project args)
                system-summary (project/infer-project-after-index! xtdb
                                                                   project
                                                                   index-summary)
                report (when check?
                         (maintenance-report xtdb project args))
                enqueued (when (and report (enqueue-output? args))
                           (enqueue-sync-work! args report))
                result (cond-> {:schema "ygg.sync/v1"
                                :project-id (:id project)
                                :repo-id repo-id
                                :index-summary index-summary
                                :system-summary system-summary}
                         report (assoc :check-report report)
                         enqueued (assoc :enqueued enqueued
                                         :enqueue-summary (enqueue-summary enqueued)))]
            (if (json-output? args)
              (print-json result)
              (print-sync-summary result))))))))
(defn- sync-add-repo!
  [args]
  (let [[config-path repo-root] (positional-args args)]
    (when-not (and config-path repo-root)
      (throw (ex-info "Missing project config path or repo path."
                      {:usage (usage)})))
    (let [project (project/add-repo-to-config!
                   config-path
                   repo-root
                   {:repo-id (option-value args "--repo")
                    :role (some-> (option-value args "--role") keyword)})
          repo (last (:repos project))]
      (print-project-add-repo-summary
       {:project project
        :repo repo
        :next [(str "ygg sync " config-path)]}))))

(defn- work-project-id
  [root args id]
  (or (some-> (queue/find-item root id) :item :project-id)
      (option-value args "--project")
      (try
        (:project-id (registry/resolve-project {:cwd (System/getProperty "user.dir")}))
        (catch Exception _
          nil))))

(defn- require-work-project-id
  [root args id]
  (or (work-project-id root args id)
      (throw (ex-info "Unable to resolve project id for sync work apply."
                      {:id id
                       :usage (usage)}))))

(defn- sync-work!
  [args]
  (let [action (keyword (first args))
        work-args (vec (rest args))
        positional (positional-args work-args)
        queue-db* (delay (queue-db work-args))]
    (case action
      :list
      (do
        (queue/release-expired! @queue-db*)
        (print-json
         (queue/list-summary @queue-db*
                             {:status (option-value work-args "--status")
                              :project-id (option-value work-args "--project")
                              :kind (option-value work-args "--kind")
                              :limit (parse-limit work-args)})))

      :pull
      (print-json
       (if-let [found (queue/claim-next!
                       @queue-db*
                       {:agent-id (queue-agent work-args)
                        :lease-ms (queue-lease-ms work-args)
                        :project-id (option-value work-args "--project")
                        :kind (option-value work-args "--kind")})]
         (assoc (queue/item-summary found) :item (:item found))
         {:schema queue/summary-schema
          :status "empty"
          :queue-db @queue-db*}))

      :show
      (let [id (first positional)]
        (when-not id
          (throw (ex-info "Missing sync work id." {:usage (usage)})))
        (print-json (if-let [found (queue/find-item @queue-db* id)]
                      (assoc (queue/item-summary found) :item (:item found))
                      {:schema "ygg.queue.error/v1"
                       :error "sync work item not found"
                       :id id})))

      :complete
      (let [id (first positional)
            result-path (option-value work-args "--result")]
        (when-not (and id result-path)
          (throw (ex-info "Missing sync work id or --result path."
                          {:usage (usage)})))
        (print-json
         (queue/item-summary
          (queue/complete! @queue-db* id (queue/read-json-file result-path)))))

      :validate
      (let [id (first positional)]
        (when-not id
          (throw (ex-info "Missing sync work id."
                          {:usage (usage)})))
        (print-json
         (validate-work-result @queue-db* id)))

      :apply
      (let [id (first positional)]
        (when-not id
          (throw (ex-info "Missing sync work id."
                          {:usage (usage)})))
        (let [queue-db-path @queue-db*
              project-id (require-work-project-id queue-db-path work-args id)]
          (store/with-node (store/storage-path project-id)
            (fn [xtdb]
              (print-json
               (apply-work-result! xtdb queue-db-path id))))))

      :reject
      (let [id (first positional)
            reason (option-value work-args "--reason")]
        (when-not (and id reason)
          (throw (ex-info "Missing sync work id or --reason."
                          {:usage (usage)})))
        (print-json (queue/item-summary (queue/reject! @queue-db* id reason))))

      :release
      (let [id (first positional)]
        (when-not id
          (throw (ex-info "Missing sync work id." {:usage (usage)})))
        (print-json
         (queue/item-summary
          (queue/release! @queue-db* id (or (option-value work-args "--reason")
                                            "manual release")))))

      :release-expired
      (print-json {:schema "ygg.queue.release-expired/v1"
                   :queue-db @queue-db*
                   :released (queue/release-expired! @queue-db*)})

      :heartbeat
      (let [id (first positional)]
        (when-not id
          (throw (ex-info "Missing sync work id." {:usage (usage)})))
        (print-json
         (queue/item-summary
          (queue/heartbeat! @queue-db*
                            id
                            {:agent-id (queue-agent work-args)
                             :lease-ms (queue-lease-ms work-args)}))))

      :auto
      (let [config-path (first positional)]
        (print-json
         (index-maintenance-worker/run!
          (:project (registry/resolve-project
                     {:project-id (option-value work-args "--project")
                      :config-path config-path
                      :cwd (System/getProperty "user.dir")})))))

      (throw (ex-info "Unknown sync work command." {:command action
                                                    :usage (usage)})))))
(defn- retired-sync-map-command!
  [action]
  (throw (ex-info "Correction writes are handled by ygg corrections."
                  {:command (name action)
                   :replacement "ygg corrections"
                   :usage (usage)})))

(defn- sync-docs!
  [args]
  (let [action (keyword (first args))]
    (case action
      :attach
      (throw (ex-info "Doc attachment corrections are handled by ygg corrections."
                      {:command "sync docs attach"
                       :replacement "ygg corrections docs attach"
                       :usage (usage)}))

      (:candidates :for :audit)
      (dispatch "docs" args)

      (throw (ex-info "Unknown sync docs command." {:command action
                                                    :usage (usage)})))))
(defn sync-dispatch!
  ([args]
   (let [action-name (first args)
         action (keyword action-name)
         action-args (vec (rest args))]
     (case action
       :inspect
       (let [{:keys [project config-path]} (resolve-project-ref action-args)]
         (print-project-status! project config-path action-args))

       :add-repo
       (sync-add-repo! action-args)

       :coverage
       (sync-coverage! action-args)

       :activity
       (sync-activity! action-args)

       :work
       (sync-work! action-args)

       :docs
       (sync-docs! action-args)

       :meta
       (dispatch "meta" action-args)

       :view
       (dispatch "views" action-args)

       (:init :propose :explain :set-kind :include :ignore :package)
       (retired-sync-map-command! action)

       (sync-project! args))))
  ([args deps]
   (binding [*deps* deps]
     (sync-dispatch! args))))
