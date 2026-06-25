(ns ygg.queue
  "SQLite-backed queue for provider-agnostic agent work handoff."
  (:require [ygg.command :as command]
            [ygg.hash :as hash]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.sql PreparedStatement ResultSet]
           [org.sqlite SQLiteConfig]))

(def schema
  "ygg.queue.item/v1")

(def summary-schema
  "ygg.queue.summary/v1")

(def list-schema
  "ygg.queue.list/v1")

(def default-root
  ".ygg/queue")

(def default-db-file
  "queue.sqlite")

(def statuses
  ["ready" "claimed" "done" "rejected" "failed"])

(def ^:private status-rank
  (zipmap statuses (range)))

(defn now-ms
  []
  (System/currentTimeMillis))

(defn status-name
  [status]
  (cond
    (keyword? status) (name status)
    (nil? status) nil
    :else (str status)))

(defn- ensure-status!
  [status]
  (let [status (status-name status)]
    (when-not (contains? (set statuses) status)
      (throw (ex-info "Unknown queue status."
                      {:status status
                       :supported statuses})))
    status))

(defn read-json-file
  "Read JSON from path using keyword object keys."
  [path]
  (json/read-json (slurp (io/file path)) :key-fn keyword))

(defn- sanitize-id
  [id]
  (-> (str id)
      (str/replace #"[^A-Za-z0-9._-]+" "_")
      (str/replace #"^_+" "")
      (str/replace #"_+$" "")))

(defn db-path
  "Return the SQLite queue database path for root."
  [root]
  (let [root (or root default-root)
        file (io/file root)]
    (if (str/ends-with? (.getName file) ".sqlite")
      (.getPath file)
      (.getPath (io/file file default-db-file)))))

(defn ensure-root!
  "Create the queue SQLite parent directory. Return root."
  [root]
  (when-let [parent (.getParentFile (io/file (db-path root)))]
    (.mkdirs parent))
  root)

(defn- sqlite-connection
  [root]
  (let [config (SQLiteConfig.)]
    (.createConnection config (str "jdbc:sqlite:" (db-path (ensure-root! root))))))

(defn- execute!
  [conn sql]
  (with-open [statement (.createStatement conn)]
    (.execute statement sql)))

(defn- ensure-schema!
  [conn]
  (execute! conn
            (str "create table if not exists ygg_queue_items ("
                 "id text primary key, "
                 "kind text not null, "
                 "project_id text, "
                 "priority integer not null, "
                 "status text not null, "
                 "created_at_ms integer not null, "
                 "updated_at_ms integer not null, "
                 "payload_schema text, "
                 "payload_json text not null, "
                 "source_json text, "
                 "result_json text, "
                 "lease_json text, "
                 "reason text, "
                 "release_reason text, "
                 "claim_count integer not null default 0, "
                 "completed_at_ms integer, "
                 "rejected_at_ms integer, "
                 "failed_at_ms integer, "
                 "released_at_ms integer)"))
  (execute! conn "create index if not exists ygg_queue_items_status_idx on ygg_queue_items(status)")
  (execute! conn "create index if not exists ygg_queue_items_project_idx on ygg_queue_items(project_id)")
  (execute! conn "create index if not exists ygg_queue_items_kind_idx on ygg_queue_items(kind)"))

(defn- with-db
  [root f]
  (with-open [conn (sqlite-connection root)]
    (ensure-schema! conn)
    (f conn)))

(defn- json-str
  [value]
  (when value
    (json/write-json-str value {:indent-str "  "})))

(defn- read-json-str
  [value]
  (when-not (str/blank? (str value))
    (json/read-json value :key-fn keyword)))

(defn- set-nullable-string!
  [^PreparedStatement statement idx value]
  (if (nil? value)
    (.setObject statement idx nil)
    (.setString statement idx (str value))))

(defn- set-nullable-long!
  [^PreparedStatement statement idx value]
  (if (nil? value)
    (.setObject statement idx nil)
    (.setLong statement idx (long value))))

(defn- nullable-long
  [^ResultSet rs column]
  (let [value (.getObject rs column)]
    (when value
      (long value))))

(defn- item-ref
  [root item]
  (str (db-path root) "#" (:id item)))

(defn- payload-kind
  [payload]
  (case (:schema payload)
    "ygg.context/v1" "context"
    "ygg.frontier.decision/v1" "maintenance-decision"
    "ygg.index-maintenance.classification/v1" "index-maintenance-classification"
    "ygg.infra.review-packet/v1" "infra-review"
    "ygg.infra.review-result/v1" "infra-review-result"
    "ygg.dependency.review-packet/v1" "dependency-review"
    "ygg.dependency.review-result/v1" "dependency-review-result"
    "ygg.index-maintenance.report/v1" "index-maintenance-report"
    nil))

(defn- payload-project-id
  [payload]
  (or (:project-id payload)
      (:projectId payload)
      (get-in payload [:basis :project-id])
      (get-in payload [:basis :projectId])
      (get-in payload [:graph-basis :project-id])
      (get-in payload [:graphBasis :projectId])
      (get-in payload [:decision :project-id])
      (get-in payload [:decision :projectId])))

(defn- project-option
  [project-id]
  (when-not (str/blank? (str project-id))
    (str " --project " (command/shell-token project-id))))

(defn- queue-option
  [root project-id]
  (when (str/blank? (str project-id))
    (str " --queue-dir " (command/shell-token root))))

(defn- kind-option
  [kind]
  (when-not (str/blank? (str kind))
    (str " --kind " (command/shell-token kind))))

(defn- work-command
  [{:keys [root item]} action & args]
  (str "ygg sync work " action
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))
       (project-option (:project-id item))
       (queue-option root (:project-id item))))

(defn- item-actions
  [{:keys [item] :as found}]
  (let [status (status-name (:status item))
        id (:id item)]
    (cond-> [{:kind :show
              :label "Inspect work item payload"
              :command (work-command found "show" id)}]
      (= "ready" status)
      (conj {:kind :claim
             :label "Claim next matching work item"
             :command (str "ygg sync work pull"
                           (project-option (:project-id item))
                           (kind-option (:kind item))
                           " --agent <agent-id>"
                           (queue-option (:root found) (:project-id item)))})

      (= "claimed" status)
      (conj {:kind :heartbeat
             :label "Extend claimed work item lease"
             :command (work-command found
                                    "heartbeat"
                                    id
                                    "--agent"
                                    "<agent-id>"
                                    "--lease-minutes"
                                    "30")}
            {:kind :complete
             :label "Complete claimed work item with result JSON"
             :command (work-command found "complete" id "--result" "result.json")}
            {:kind :release
             :label "Release claimed work item"
             :command (work-command found "release" id "--reason" "needs more context")}
            {:kind :reject
             :label "Reject work item with reason"
             :command (work-command found "reject" id "--reason" "not applicable")})

      (= "done" status)
      (conj {:kind :validate
             :label "Validate completed work result"
             :command (work-command found "validate" id)}
            {:kind :apply
             :label "Apply validated work result"
             :command (work-command found "apply" id)}))))

(defn- new-id
  [kind created-at-ms payload]
  (str "queue:" (hash/short-hash [kind created-at-ms payload])))

(defn- base-item
  [payload {:keys [kind project-id priority source now]}]
  (let [created-at-ms (long (or now (now-ms)))
        kind (or kind (payload-kind payload) "generic")
        item {:schema schema
              :id (new-id kind created-at-ms payload)
              :kind kind
              :project-id (or project-id (payload-project-id payload))
              :priority (long (or priority 50))
              :status "ready"
              :created-at-ms created-at-ms
              :updated-at-ms created-at-ms
              :payload-schema (:schema payload)
              :payload payload
              :claim-count 0}]
    (cond-> item
      source (assoc :source source))))

(defn- bind-item!
  [^PreparedStatement statement item]
  (.setString statement 1 (:id item))
  (.setString statement 2 (str (:kind item)))
  (set-nullable-string! statement 3 (:project-id item))
  (.setLong statement 4 (long (:priority item)))
  (.setString statement 5 (ensure-status! (:status item)))
  (.setLong statement 6 (long (:created-at-ms item)))
  (.setLong statement 7 (long (:updated-at-ms item)))
  (set-nullable-string! statement 8 (:payload-schema item))
  (.setString statement 9 (json-str (:payload item)))
  (set-nullable-string! statement 10 (json-str (:source item)))
  (set-nullable-string! statement 11 (json-str (:result item)))
  (set-nullable-string! statement 12 (json-str (:lease item)))
  (set-nullable-string! statement 13 (:reason item))
  (set-nullable-string! statement 14 (:release-reason item))
  (.setLong statement 15 (long (or (:claim-count item) 0)))
  (set-nullable-long! statement 16 (:completed-at-ms item))
  (set-nullable-long! statement 17 (:rejected-at-ms item))
  (set-nullable-long! statement 18 (:failed-at-ms item))
  (set-nullable-long! statement 19 (:released-at-ms item)))

(defn- upsert-item!
  [conn item]
  (with-open [statement (.prepareStatement
                         conn
                         (str "insert into ygg_queue_items "
                              "(id, kind, project_id, priority, status, created_at_ms, "
                              "updated_at_ms, payload_schema, payload_json, source_json, "
                              "result_json, lease_json, reason, release_reason, claim_count, "
                              "completed_at_ms, rejected_at_ms, failed_at_ms, released_at_ms) "
                              "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                              "on conflict(id) do update set "
                              "kind = excluded.kind, "
                              "project_id = excluded.project_id, "
                              "priority = excluded.priority, "
                              "status = excluded.status, "
                              "created_at_ms = excluded.created_at_ms, "
                              "updated_at_ms = excluded.updated_at_ms, "
                              "payload_schema = excluded.payload_schema, "
                              "payload_json = excluded.payload_json, "
                              "source_json = excluded.source_json, "
                              "result_json = excluded.result_json, "
                              "lease_json = excluded.lease_json, "
                              "reason = excluded.reason, "
                              "release_reason = excluded.release_reason, "
                              "claim_count = excluded.claim_count, "
                              "completed_at_ms = excluded.completed_at_ms, "
                              "rejected_at_ms = excluded.rejected_at_ms, "
                              "failed_at_ms = excluded.failed_at_ms, "
                              "released_at_ms = excluded.released_at_ms"))]
    (bind-item! statement item)
    (.executeUpdate statement))
  item)

(defn- row->item
  [^ResultSet rs]
  (cond-> {:schema schema
           :id (.getString rs "id")
           :kind (.getString rs "kind")
           :project-id (.getString rs "project_id")
           :priority (.getLong rs "priority")
           :status (.getString rs "status")
           :created-at-ms (.getLong rs "created_at_ms")
           :updated-at-ms (.getLong rs "updated_at_ms")
           :payload-schema (.getString rs "payload_schema")
           :payload (read-json-str (.getString rs "payload_json"))
           :claim-count (.getLong rs "claim_count")}
    (.getString rs "source_json") (assoc :source (read-json-str (.getString rs "source_json")))
    (.getString rs "result_json") (assoc :result (read-json-str (.getString rs "result_json")))
    (.getString rs "lease_json") (assoc :lease (read-json-str (.getString rs "lease_json")))
    (.getString rs "reason") (assoc :reason (.getString rs "reason"))
    (.getString rs "release_reason") (assoc :release-reason (.getString rs "release_reason"))
    (nullable-long rs "completed_at_ms") (assoc :completed-at-ms (nullable-long rs "completed_at_ms"))
    (nullable-long rs "rejected_at_ms") (assoc :rejected-at-ms (nullable-long rs "rejected_at_ms"))
    (nullable-long rs "failed_at_ms") (assoc :failed-at-ms (nullable-long rs "failed_at_ms"))
    (nullable-long rs "released_at_ms") (assoc :released-at-ms (nullable-long rs "released_at_ms"))))

(defn- all-items
  [conn]
  (with-open [statement (.prepareStatement conn "select * from ygg_queue_items")
              rs (.executeQuery statement)]
    (loop [items []]
      (if (.next rs)
        (recur (conj items (row->item rs)))
        items))))

(defn- found-record
  [root item]
  {:root root
   :path (item-ref root item)
   :item item})

(defn enqueue!
  "Add payload as one ready queue item.

  Returns {:path string :item map}. Queue state is stored in SQLite; :path is a
  stable item reference of the form <queue-db>#<queue-id>."
  ([payload] (enqueue! payload {}))
  ([payload {:keys [root] :as opts}]
   (let [root (or root default-root)
         item (base-item payload opts)]
     (with-db root #(upsert-item! % item))
     (found-record root item))))

(defn- queue-item?
  [item]
  (= schema (:schema item)))

(defn- reference-id
  [value]
  (let [value (str value)]
    (if-let [idx (str/last-index-of value "#")]
      (subs value (inc idx))
      value)))

(defn- matching-item?
  [value item]
  (let [value (reference-id value)
        id (:id item)
        filename (str (sanitize-id id) ".json")]
    (or (= value id)
        (= value (sanitize-id id))
        (= value filename)
        (str/ends-with? id value)
        (str/ends-with? filename value))))

(defn find-item
  "Find one queue item by id, unique id suffix, or SQLite item reference."
  [root value]
  (let [root (or root default-root)
        matches (with-db root
                  (fn [conn]
                    (->> (all-items conn)
                         (filter #(matching-item? value %))
                         (map #(found-record root %))
                         vec)))]
    (case (count matches)
      0 nil
      1 (first matches)
      (throw (ex-info "Queue item reference is ambiguous."
                      {:value value
                       :matches (mapv (comp :id :item) matches)})))))

(defn- move-item!
  [root item-found status updates]
  (let [status (ensure-status! status)
        item (merge (:item item-found)
                    updates
                    {:status status
                     :updated-at-ms (now-ms)})
        item (cond-> item
               (contains? updates :lease) (assoc :lease (:lease updates))
               (contains? updates :result) (assoc :result (:result updates)))]
    (with-db root #(upsert-item! % item))
    (found-record root item)))

(defn- expired-claim?
  [now item]
  (and (= "claimed" (status-name (:status item)))
       (some->> (get-in item [:lease :expires-at-ms])
                long
                (> now))))

(defn release-expired!
  "Move expired claimed items back to ready. Return number released."
  ([root] (release-expired! root (now-ms)))
  ([root now]
   (let [root (or root default-root)
         expired (with-db root
                   (fn [conn]
                     (->> (all-items conn)
                          (filter #(expired-claim? now %))
                          (map #(found-record root %))
                          vec)))]
     (doseq [item-found expired]
       (move-item! root
                   item-found
                   "ready"
                   {:lease nil
                    :released-at-ms now
                    :release-reason "lease expired"}))
     (count expired))))

(defn list-items
  "Return queue items, optionally filtered by status/project-id and limited."
  ([root] (list-items root {}))
  ([root {:keys [status project-id kind limit]}]
   (let [root (or root default-root)
         status (some-> status status-name)
         kind (some-> kind str)]
     (->> (with-db root all-items)
          (filter queue-item?)
          (filter #(or (nil? status) (= status (:status %))))
          (filter #(or (str/blank? (str project-id))
                       (= project-id (:project-id %))))
          (filter #(or (str/blank? (str kind))
                       (= kind (:kind %))))
          (sort-by (fn [item]
                     [(get status-rank (status-name (:status item)) 99)
                      (- (long (:priority item)))
                      (long (:created-at-ms item))]))
          ((fn [items]
             (if limit
               (take limit items)
               items)))
          (mapv #(found-record root %))))))

(defn item-summary
  "Return a compact queue item summary for CLI listing and enqueue results."
  [{:keys [path item] :as item-found}]
  (let [payload (:payload item)
        decision (:decision payload)
        actions (item-actions item-found)
        payload-summary (case (:schema payload)
                          "ygg.infra.review-packet/v1"
                          {:id (:reviewId payload)
                           :kind (:kind payload)
                           :artifact (:artifact payload)}

                          "ygg.dependency.review-packet/v1"
                          {:id (:reviewId payload)
                           :kind (:kind payload)
                           :import (get-in payload [:facts :unresolvedImport :import])
                           :path (get-in payload [:facts :unresolvedImport :path])
                           :line (get-in payload [:facts :unresolvedImport :line])}

                          "ygg.frontier.decision/v1"
                          (cond-> {:id (:decisionId payload)
                                   :kind (:kind decision)
                                   :severity (:severity decision)
                                   :target (:target decision)
                                   :reason (:reason decision)}
                            (get-in decision [:basis :hash])
                            (assoc :basisHash (get-in decision [:basis :hash]))

                            (:allowedActions payload)
                            (assoc :allowedActions (:allowedActions payload)))

                          nil)]
    (cond-> {:schema summary-schema
             :id (:id item)
             :kind (:kind item)
             :project-id (:project-id item)
             :status (:status item)
             :priority (:priority item)
             :payload-schema (:payload-schema item)
             :created-at-ms (:created-at-ms item)
             :updated-at-ms (:updated-at-ms item)}
      path (assoc :path path)
      (:expectedResultSchema payload)
      (assoc :expected-result-schema (:expectedResultSchema payload))
      (get-in item [:result :schema])
      (assoc :result-schema (get-in item [:result :schema]))
      (:source item) (assoc :source (:source item))
      (seq actions) (assoc :actions actions)
      payload-summary (assoc :payload-summary payload-summary)
      (:lease item) (assoc :lease (:lease item)))))

(defn list-summary
  "Return a compact queue listing."
  [root opts]
  {:schema list-schema
   :root (or root default-root)
   :queue-db (db-path root)
   :items (mapv item-summary (list-items root opts))})

(defn claim-next!
  "Claim the highest-priority ready item.

  Expired claimed items are first released back to ready. Returns
  {:path string :item map}, or nil when no ready item exists."
  [root {:keys [agent-id lease-ms project-id kind] :or {lease-ms (* 30 60 1000)}}]
  (let [root (or root default-root)]
    (release-expired! root)
    (when-let [item-found (first (list-items root {:status "ready"
                                                   :project-id project-id
                                                   :kind kind
                                                   :limit 1}))]
      (let [now (now-ms)]
        (move-item! root
                    item-found
                    "claimed"
                    {:lease {:agent-id agent-id
                             :claimed-at-ms now
                             :expires-at-ms (+ now (long lease-ms))}
                     :claim-count (inc (long (or (get-in item-found [:item :claim-count])
                                                 0)))})))))

(defn complete!
  "Complete a claimed item with a result map."
  [root id result]
  (let [root (or root default-root)
        item-found (or (find-item root id)
                       (throw (ex-info "Queue item not found." {:id id})))]
    (when-not (= "claimed" (status-name (get-in item-found [:item :status])))
      (throw (ex-info "Only claimed queue items can be completed."
                      {:id id
                       :status (get-in item-found [:item :status])})))
    (move-item! root
                item-found
                "done"
                {:result result
                 :completed-at-ms (now-ms)
                 :lease nil})))

(defn reject!
  "Reject a queue item with a human-readable reason."
  [root id reason]
  (let [root (or root default-root)
        item-found (or (find-item root id)
                       (throw (ex-info "Queue item not found." {:id id})))]
    (move-item! root
                item-found
                "rejected"
                {:reason reason
                 :rejected-at-ms (now-ms)
                 :lease nil})))

(defn fail!
  "Mark a queue item failed with a human-readable reason."
  [root id reason]
  (let [root (or root default-root)
        item-found (or (find-item root id)
                       (throw (ex-info "Queue item not found." {:id id})))]
    (move-item! root
                item-found
                "failed"
                {:reason reason
                 :failed-at-ms (now-ms)
                 :lease nil})))

(defn release!
  "Release a claimed item back to ready."
  [root id reason]
  (let [root (or root default-root)
        item-found (or (find-item root id)
                       (throw (ex-info "Queue item not found." {:id id})))]
    (move-item! root
                item-found
                "ready"
                {:lease nil
                 :released-at-ms (now-ms)
                 :release-reason reason})))

(defn heartbeat!
  "Extend a claimed item's lease."
  [root id {:keys [agent-id lease-ms] :or {lease-ms (* 30 60 1000)}}]
  (let [root (or root default-root)
        item-found (or (find-item root id)
                       (throw (ex-info "Queue item not found." {:id id})))
        item (:item item-found)
        now (now-ms)]
    (when-not (= "claimed" (status-name (:status item)))
      (throw (ex-info "Only claimed queue items can be heartbeated."
                      {:id id
                       :status (:status item)})))
    (move-item! root
                item-found
                "claimed"
                {:lease (merge (:lease item)
                               {:agent-id (or agent-id
                                              (get-in item [:lease :agent-id]))
                                :heartbeat-at-ms now
                                :expires-at-ms (+ now (long lease-ms))})})))
