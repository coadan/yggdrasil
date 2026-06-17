(ns agraph.queue
  "Filesystem-backed queue for provider-agnostic agent work handoff."
  (:require [agraph.hash :as hash]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema
  "agraph.queue.item/v1")

(def summary-schema
  "agraph.queue.summary/v1")

(def list-schema
  "agraph.queue.list/v1")

(def default-root
  ".dev/agraph/queue")

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

(defn- status-dir
  [root status]
  (io/file root (ensure-status! status)))

(defn ensure-root!
  "Create the queue root and status directories. Return root."
  [root]
  (doseq [status statuses]
    (.mkdirs (status-dir root status)))
  root)

(defn read-json-file
  "Read JSON from path using keyword object keys."
  [path]
  (json/read-json (slurp (io/file path)) :key-fn keyword))

(defn- write-json-file!
  [file value]
  (when-let [parent (.getParentFile (io/file file))]
    (.mkdirs parent))
  (spit file (str (json/write-json-str value {:indent-str "  "}) "\n"))
  (.getPath (io/file file)))

(defn- json-file?
  [file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".json")))

(defn- sanitize-id
  [id]
  (-> (str id)
      (str/replace #"[^A-Za-z0-9._-]+" "_")
      (str/replace #"^_+" "")
      (str/replace #"_+$" "")))

(defn- item-file
  [root status id]
  (io/file (status-dir root status) (str (sanitize-id id) ".json")))

(defn- payload-kind
  [payload]
  (case (:schema payload)
    "agraph.context/v1" "context"
    "agraph.cursor.packet/v1" "cursor"
    "agraph.frontier.decision/v1" "maintenance-decision"
    "agraph.maintenance.decision-packet/v1" "maintenance-decision"
    "agraph.maintenance.classification/v1" "maintenance-classification"
    "agraph.infra.review-packet/v1" "infra-review"
    "agraph.infra.review-result/v1" "infra-review-result"
    "agraph.maintain/v1" "maintenance-report"
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
              :payload payload}]
    (cond-> item
      source (assoc :source source))))

(defn enqueue!
  "Add payload as one ready queue item.

  Returns {:path string :item map}. The payload is embedded unchanged so any
  filesystem consumer can process it without calling back into AGraph first."
  ([payload] (enqueue! payload {}))
  ([payload {:keys [root] :as opts}]
   (let [root (or root default-root)
         _ (ensure-root! root)
         item (base-item payload opts)
         file (item-file root "ready" (:id item))]
     {:path (write-json-file! file item)
      :item item})))

(defn- item-files
  [root]
  (ensure-root! root)
  (->> statuses
       (mapcat (fn [status]
                 (let [dir (status-dir root status)]
                   (->> (file-seq dir)
                        (filter json-file?)
                        (map (fn [file] {:status status
                                         :path (.getPath file)}))))))
       vec))

(defn- read-found
  [{:keys [path] :as found}]
  (assoc found :item (read-json-file path)))

(defn- queue-item?
  [item]
  (= schema (:schema item)))

(defn- matching-item?
  [value {:keys [path item]}]
  (let [id (:id item)
        filename (some-> path io/file .getName (str/replace #"\.json$" ""))]
    (or (= value id)
        (= value filename)
        (str/ends-with? id value)
        (str/ends-with? filename value))))

(defn find-item
  "Find one queue item by path, id, filename, or unique id suffix."
  [root value]
  (let [file (io/file value)]
    (if (and (.isFile file)
             (queue-item? (read-json-file file)))
      (read-found {:path (.getPath file)})
      (let [matches (->> (item-files root)
                         (map read-found)
                         (filter #(matching-item? value %))
                         vec)]
        (case (count matches)
          0 nil
          1 (first matches)
          (throw (ex-info "Queue item reference is ambiguous."
                          {:value value
                           :matches (mapv (comp :id :item) matches)})))))))

(defn- move-item!
  [root found status updates]
  (let [status (ensure-status! status)
        now (now-ms)
        item (merge (:item found)
                    updates
                    {:status status
                     :updated-at-ms now})
        file (item-file root status (:id item))
        path (write-json-file! file item)]
    (when-not (= path (:path found))
      (.delete (io/file (:path found))))
    {:path path
     :item item}))

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
   (let [expired (->> (item-files root)
                      (filter #(= "claimed" (:status %)))
                      (map read-found)
                      (filter #(expired-claim? now (:item %)))
                      vec)]
     (doseq [found expired]
       (move-item! root
                   found
                   "ready"
                   {:lease nil
                    :released-at-ms now
                    :release-reason "lease expired"}))
     (count expired))))

(defn list-items
  "Return queue items, optionally filtered by status/project-id and limited."
  ([root] (list-items root {}))
  ([root {:keys [status project-id kind limit]}]
   (let [status (some-> status status-name)
         kind (some-> kind str)]
     (->> (item-files root)
          (filter #(or (nil? status) (= status (:status %))))
          (map read-found)
          (filter #(queue-item? (:item %)))
          (filter #(or (str/blank? (str project-id))
                       (= project-id (get-in % [:item :project-id]))))
          (filter #(or (str/blank? (str kind))
                       (= kind (get-in % [:item :kind]))))
          (sort-by (fn [{:keys [item]}]
                     [(get status-rank (status-name (:status item)) 99)
                      (- (long (:priority item)))
                      (long (:created-at-ms item))]))
          ((fn [items]
             (if limit
               (take limit items)
               items)))
          vec))))

(defn item-summary
  "Return a compact queue item summary for CLI listing and enqueue results."
  [{:keys [path item]}]
  (let [payload (:payload item)
        decision (:decision payload)
        payload-summary (case (:schema payload)
                          "agraph.infra.review-packet/v1"
                          {:id (:reviewId payload)
                           :kind (:kind payload)
                           :artifact (:artifact payload)}

                          "agraph.maintenance.decision-packet/v1"
                          {:id (:decisionId payload)
                           :kind (:kind decision)
                           :severity (:severity decision)
                           :target (:target decision)
                           :reason (:reason decision)}

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
      payload-summary (assoc :payload-summary payload-summary)
      (:lease item) (assoc :lease (:lease item)))))

(defn list-summary
  "Return a compact queue listing."
  [root opts]
  {:schema list-schema
   :root root
   :items (mapv item-summary (list-items root opts))})

(defn claim-next!
  "Claim the highest-priority ready item.

  Expired claimed items are first released back to ready. Returns
  {:path string :item map}, or nil when no ready item exists."
  [root {:keys [agent-id lease-ms project-id kind] :or {lease-ms (* 30 60 1000)}}]
  (release-expired! root)
  (when-let [found (first (list-items root {:status "ready"
                                            :project-id project-id
                                            :kind kind
                                            :limit 1}))]
    (let [now (now-ms)]
      (move-item! root
                  found
                  "claimed"
                  {:lease {:agent-id agent-id
                           :claimed-at-ms now
                           :expires-at-ms (+ now (long lease-ms))}
                   :claim-count (inc (long (or (get-in found [:item :claim-count]) 0)))}))))

(defn complete!
  "Complete a claimed item with a result map."
  [root id result]
  (let [found (or (find-item root id)
                  (throw (ex-info "Queue item not found." {:id id})))]
    (when-not (= "claimed" (status-name (get-in found [:item :status])))
      (throw (ex-info "Only claimed queue items can be completed."
                      {:id id
                       :status (get-in found [:item :status])})))
    (move-item! root
                found
                "done"
                {:result result
                 :completed-at-ms (now-ms)
                 :lease nil})))

(defn reject!
  "Reject a queue item with a human-readable reason."
  [root id reason]
  (let [found (or (find-item root id)
                  (throw (ex-info "Queue item not found." {:id id})))]
    (move-item! root
                found
                "rejected"
                {:reason reason
                 :rejected-at-ms (now-ms)
                 :lease nil})))

(defn fail!
  "Mark a queue item failed with a human-readable reason."
  [root id reason]
  (let [found (or (find-item root id)
                  (throw (ex-info "Queue item not found." {:id id})))]
    (move-item! root
                found
                "failed"
                {:reason reason
                 :failed-at-ms (now-ms)
                 :lease nil})))

(defn release!
  "Release a claimed item back to ready."
  [root id reason]
  (let [found (or (find-item root id)
                  (throw (ex-info "Queue item not found." {:id id})))]
    (move-item! root
                found
                "ready"
                {:lease nil
                 :released-at-ms (now-ms)
                 :release-reason reason})))

(defn heartbeat!
  "Extend a claimed item's lease."
  [root id {:keys [agent-id lease-ms] :or {lease-ms (* 30 60 1000)}}]
  (let [found (or (find-item root id)
                  (throw (ex-info "Queue item not found." {:id id})))
        item (:item found)
        now (now-ms)]
    (when-not (= "claimed" (status-name (:status item)))
      (throw (ex-info "Only claimed queue items can be heartbeated."
                      {:id id
                       :status (:status item)})))
    (move-item! root
                found
                "claimed"
                {:lease (merge (:lease item)
                               {:agent-id (or agent-id
                                              (get-in item [:lease :agent-id]))
                                :heartbeat-at-ms now
                                :expires-at-ms (+ now (long lease-ms))})})))
