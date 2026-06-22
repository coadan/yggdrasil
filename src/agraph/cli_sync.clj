(ns agraph.cli-sync
  (:require [agraph.activity :as activity]
            [agraph.cli-options :refer [dry-run? json-output? option-value parse-double-option parse-limit positional-args]]
            [agraph.coverage :as coverage]
            [agraph.dependency-review :as dependency-review]
            [agraph.infra-review :as infra-review]
            [agraph.index :as index]
            [agraph.map-store :as map-store]
            [agraph.project :as project]
            [agraph.queue :as queue]
            [agraph.system.decision-classifier :as decision-classifier]
            [agraph.xtdb :as store]
            [clojure.string :as str]))

(def ^:dynamic *deps* {})

(defn- call-dep
  [k & args]
  (apply (or (get *deps* k)
             (throw (ex-info "Missing CLI sync dependency." {:dependency k})))
         args))

(defn- usage [] (call-dep :usage))
(defn- print-json [data] (call-dep :print-json data))
(defn- default-map-path [args] (call-dep :default-map-path args))
(defn- enqueue-output? [args] (call-dep :enqueue-output? args))
(defn- queue-root [args] (call-dep :queue-root args))
(defn- queue-priority
  ([args] (call-dep :queue-priority args))
  ([args default] (call-dep :queue-priority args default)))
(defn- severity-priority [severity] (call-dep :severity-priority severity))
(defn- print-source-coverage [report] (call-dep :print-source-coverage report))
(defn- print-sync-summary [result] (call-dep :print-sync-summary result))
(defn- print-project-add-repo-summary [result] (call-dep :print-project-add-repo-summary result))
(defn- queue-agent [args] (call-dep :queue-agent args))
(defn- queue-lease-ms [args] (call-dep :queue-lease-ms args))
(defn- required-map-path [args] (call-dep :required-map-path args))
(defn- apply-work-result! [root id map-path] (call-dep :apply-work-result! root id map-path))
(defn- validate-work-result [root id] (call-dep :validate-work-result root id))
(defn- dispatch [command args] (call-dep :dispatch command args))
(defn- print-project-status! [config-path args] (call-dep :print-project-status! config-path args))

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
  [{:keys [project-id graph-basis map counts scale graph-health fold-in orphaned-systems
           dangling-edges low-confidence-edges decision-summary
           external-api-review decision-queue infra-review-queue dependency-review-queue]}]
  (println "# Maintain")
  (println "- project" project-id)
  (when graph-basis
    (println "- graph-basis" (:hash graph-basis)))
  (when map
    (println "- map-rejects" (:rejects map))
    (println "- map-rejected-systems" (:rejected-systems map)))
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
      (println " " reason))))
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
(defn- count-text
  [n singular plural]
  (str (long (or n 0)) " " (if (= 1 (long (or n 0))) singular plural)))
(defn- sync-progress-line
  [{:keys [phase repo-id index-profile files-scanned files-changed files-skipped
           files-deleted files-extracted files-indexed dependency-edges
           chunks search-docs diagnostics total-ms path]}]
  (case phase
    :repo-start
    (str "- " repo-id " start profile=" (name (or index-profile index/default-index-profile)))

    :scan-complete
    (str "- " repo-id " scanned " (count-text files-scanned "file" "files"))

    :plan-complete
    (str "- " repo-id " plan "
         (count-text files-changed "changed file" "changed files")
         ", "
         (count-text files-skipped "skipped file" "skipped files")
         ", "
         (count-text files-deleted "deleted file" "deleted files"))

    :extract-start
    (str "- " repo-id " extracting " (count-text files-changed "changed file" "changed files"))

    :extract-progress
    (str "- " repo-id " extracted " files-extracted "/" files-changed
         (when path
           (str " " path)))

    :extract-complete
    (str "- " repo-id " extracted " (count-text files-extracted "file" "files"))

    :commit-start
    (str "- " repo-id " committing " (count-text files-indexed "file" "files"))

    :commit-complete
    (str "- " repo-id " committed " (count-text files-indexed "file" "files")
         ", " (count-text chunks "chunk" "chunks")
         ", " (count-text search-docs "search doc" "search docs")
         ", " (count-text diagnostics "diagnostic" "diagnostics"))

    :delete-complete
    (str "- " repo-id " deleted " (count-text files-deleted "stale file" "stale files"))

    :dependency-start
    (str "- " repo-id " deriving dependency edges")

    :dependency-complete
    (str "- " repo-id " derived " (count-text dependency-edges "dependency edge" "dependency edges"))

    :dry-run-complete
    (str "- " repo-id " dry-run complete " (count-text files-scanned "file" "files")
         (when total-ms
           (str ", " total-ms "ms")))

    :repo-complete
    (str "- " repo-id " complete "
         (count-text files-scanned "scanned file" "scanned files")
         ", "
         (count-text files-indexed "indexed file" "indexed files")
         ", "
         (count-text files-skipped "skipped file" "skipped files")
         (when total-ms
           (str ", " total-ms "ms")))

    nil))
(defn- sync-progress-fn
  [args]
  (when (progress-output? args)
    (let [printed-header? (atom false)]
      (fn [event]
        (when-let [line (sync-progress-line event)]
          (binding [*out* *err*]
            (when (compare-and-set! printed-header? false true)
              (println "# Sync Progress"))
            (println line)
            (flush)))))))
(defn sync-index-project!
  ([xtdb project args]
   (let [map-path (default-map-path args)
         progress-fn (sync-progress-fn args)
         opts (cond-> {:dry-run? (dry-run? args)
                       :index-profile (sync-index-profile args)
                       :map-overlay (when map-path
                                      (map-store/read-map map-path))}
                progress-fn (assoc :progress-fn progress-fn))]
     (if-let [repo-id (option-value args "--repo")]
       (let [run (project/index-project-repo! xtdb project repo-id opts)]
         {:project-id (:id project)
          :status (:status run)
          :repos [(repo-run-summary run)]})
       (project/index-project! xtdb project opts))))
  ([xtdb project args deps]
   (binding [*deps* deps]
     (sync-index-project! xtdb project args))))
(defn maintenance-report
  ([xtdb project args]
   (let [map-path (default-map-path args)]
     (project/maintain-project
      xtdb
      project
      {:low-confidence-threshold (parse-double-option args "--min-confidence" 0.60)
       :map-overlay (when map-path
                      (map-store/read-map map-path))})))
  ([xtdb project args deps]
   (binding [*deps* deps]
     (maintenance-report xtdb project args))))
(defn- enqueue-maintenance-decisions!
  [args decisions]
  (mapv
   (fn [decision]
     (queue/item-summary
      (queue/enqueue!
       (decision-classifier/decision-packet decision)
       {:root (queue-root args)
        :kind "maintenance-decision"
        :project-id (:project-id decision)
        :priority (queue-priority args (severity-priority (:severity decision)))})))
   decisions))
(defn- enqueue-infra-review-packets!
  [args packets]
  (mapv
   (fn [packet]
     (queue/item-summary
      (queue/enqueue!
       packet
       {:root (queue-root args)
        :kind infra-review/work-kind
        :project-id (:project-id packet)
        :priority (queue-priority args 50)})))
   packets))
(defn- enqueue-dependency-review-packets!
  [args packets]
  (mapv
   (fn [packet]
     (queue/item-summary
      (queue/enqueue!
       packet
       {:root (queue-root args)
        :kind dependency-review/work-kind
        :project-id (:project-id packet)
        :priority (queue-priority args 45)})))
   packets))
(defn enqueue-sync-work!
  ([args report]
   (vec
    (concat
     (enqueue-maintenance-decisions! args (:decision-queue report))
     (enqueue-infra-review-packets! args (:infra-review-queue report))
     (enqueue-dependency-review-packets! args (:dependency-review-queue report)))))
  ([args report deps]
   (binding [*deps* deps]
     (enqueue-sync-work! args report))))
(defn- maintenance-result
  [args report]
  (let [enqueued (when (enqueue-output? args)
                   (enqueue-sync-work! args report))]
    (cond-> {:schema "agraph.sync.check/v1"
             :report report}
      enqueued (assoc :enqueued enqueued))))
(defn- print-maintenance-result
  [args result]
  (if (json-output? args)
    (print-json result)
    (do
      (print-maintenance-report (:report result))
      (when-let [enqueued (:enqueued result)]
        (println)
        (println "## Enqueued")
        (doseq [item enqueued]
          (println "-" (:id item) (:kind item) (:status item)))))))
(defn- sync-check!
  [args]
  (let [config-path (first (positional-args args))]
    (when-not config-path
      (throw (ex-info "Missing project config path." {:usage (usage)})))
    (let [project (project/read-project config-path)]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (print-maintenance-result args
                                    (maintenance-result
                                     args
                                     (maintenance-report xtdb project args))))))))
(defn- sync-coverage!
  [args]
  (let [config-path (first (positional-args args))]
    (when-not config-path
      (throw (ex-info "Missing project config path." {:usage (usage)})))
    (let [project (project/read-project config-path)]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (let [report (coverage/project-coverage xtdb project {})]
            (if (json-output? args)
              (print-json report)
              (print-source-coverage report))))))))
(defn- print-activity-sync
  [{:keys [project-id queue-root counts result-schema-mismatches]}]
  (println "# Activity Sync")
  (println "- project" project-id)
  (println "- queue-root" queue-root)
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
  (let [config-path (first (positional-args args))]
    (when-not config-path
      (throw (ex-info "Missing project config path." {:usage (usage)})))
    (let [project (project/read-project config-path)]
      (store/with-node (store/storage-path)
        (fn [xtdb]
          (let [result (activity/sync-queue! xtdb
                                             project
                                             {:queue-root (queue-root args)})]
            (if (json-output? args)
              (print-json result)
              (print-activity-sync result))))))))
(defn- sync-project!
  [args]
  (let [config-path (first (positional-args args))]
    (when-not config-path
      (throw (ex-info "Missing project config path." {:usage (usage)})))
    (let [project (project/read-project config-path)
          repo-id (option-value args "--repo")
          check? (or (some #{"--check"} args)
                     (enqueue-output? args))]
      (if (dry-run? args)
        (let [index-summary (sync-index-project! nil project args)
              result {:schema "agraph.sync/v1"
                      :project-id (:id project)
                      :repo-id repo-id
                      :index-summary index-summary}]
          (if (json-output? args)
            (print-json result)
            (print-sync-summary result)))
        (store/with-node (store/storage-path)
          (fn [xtdb]
            (let [index-summary (sync-index-project! xtdb project args)
                  system-summary (project/infer-project! xtdb project)
                  report (when check?
                           (maintenance-report xtdb project args))
                  enqueued (when (and report (enqueue-output? args))
                             (enqueue-sync-work! args report))
                  result (cond-> {:schema "agraph.sync/v1"
                                  :project-id (:id project)
                                  :repo-id repo-id
                                  :index-summary index-summary
                                  :system-summary system-summary}
                           report (assoc :check-report report)
                           enqueued (assoc :enqueued enqueued))]
              (if (json-output? args)
                (print-json result)
                (print-sync-summary result)))))))))
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
        :next [(str "agraph sync " config-path)]}))))
(defn- sync-work!
  [args]
  (let [action (keyword (first args))
        work-args (vec (rest args))
        positional (positional-args work-args)
        root (queue-root work-args)]
    (case action
      :list
      (print-json
       (queue/list-summary root
                           {:status (option-value work-args "--status")
                            :project-id (option-value work-args "--project")
                            :kind (option-value work-args "--kind")
                            :limit (parse-limit work-args)}))

      :pull
      (print-json
       (if-let [found (queue/claim-next!
                       root
                       {:agent-id (queue-agent work-args)
                        :lease-ms (queue-lease-ms work-args)
                        :project-id (option-value work-args "--project")
                        :kind (option-value work-args "--kind")})]
         (assoc (queue/item-summary found) :item (:item found))
         {:schema queue/summary-schema
          :status "empty"
          :root root}))

      :show
      (let [id (first positional)]
        (when-not id
          (throw (ex-info "Missing sync work id." {:usage (usage)})))
        (print-json (if-let [found (queue/find-item root id)]
                      (assoc (queue/item-summary found) :item (:item found))
                      {:schema "agraph.queue.error/v1"
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
          (queue/complete! root id (queue/read-json-file result-path)))))

      :validate
      (let [id (first positional)]
        (when-not id
          (throw (ex-info "Missing sync work id."
                          {:usage (usage)})))
        (print-json
         (validate-work-result root id)))

      :apply
      (let [id (first positional)
            map-path (required-map-path work-args)]
        (when-not id
          (throw (ex-info "Missing sync work id."
                          {:usage (usage)})))
        (print-json
         (apply-work-result! root id map-path)))

      :reject
      (let [id (first positional)
            reason (option-value work-args "--reason")]
        (when-not (and id reason)
          (throw (ex-info "Missing sync work id or --reason."
                          {:usage (usage)})))
        (print-json (queue/item-summary (queue/reject! root id reason))))

      :release
      (let [id (first positional)]
        (when-not id
          (throw (ex-info "Missing sync work id." {:usage (usage)})))
        (print-json
         (queue/item-summary
          (queue/release! root id (or (option-value work-args "--reason")
                                      "manual release")))))

      :heartbeat
      (let [id (first positional)]
        (when-not id
          (throw (ex-info "Missing sync work id." {:usage (usage)})))
        (print-json
         (queue/item-summary
          (queue/heartbeat! root
                            id
                            {:agent-id (queue-agent work-args)
                             :lease-ms (queue-lease-ms work-args)}))))

      (throw (ex-info "Unknown sync work command." {:command action
                                                    :usage (usage)})))))
(defn- retired-sync-map-command!
  [action]
  (throw (ex-info "Map corrections are handled by the agraph map API."
                  {:command (name action)
                   :replacement "agraph map"
                   :usage (usage)})))

(defn- sync-docs!
  [args]
  (let [action (keyword (first args))]
    (case action
      :attach
      (throw (ex-info "Map doc attachments are handled by the agraph map API."
                      {:command "sync docs attach"
                       :replacement "agraph map docs attach"
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
       (let [config-path (first (positional-args action-args))]
         (print-project-status! config-path action-args))

       :add-repo
       (sync-add-repo! action-args)

       :check
       (sync-check! action-args)

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

       (if action-name
         (sync-project! args)
         (throw (ex-info "Missing sync project config path." {:usage (usage)}))))))
  ([args deps]
   (binding [*deps* deps]
     (sync-dispatch! args))))
