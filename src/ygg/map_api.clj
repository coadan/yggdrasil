(ns ygg.map-api
  "Validated operations for accepted map corrections."
  (:require [ygg.map :as graph-map]
            [ygg.map-store :as map-store]
            [ygg.xtdb :as store]
            [clojure.string :as str]))

(def status-schema
  "ygg.map.status/v1")

(def review-schema
  "ygg.map.review/v1")

(defn parse-include
  [value]
  (let [idx (.indexOf (str value) ":")]
    (when (neg? idx)
      (throw (ex-info "Include must use repo:path." {:value value})))
    {:repo (subs value 0 idx)
     :path (subs value (inc idx))}))

(def parse-source parse-include)

(defn parse-package-target
  [value]
  (let [idx (.indexOf (str value) ":")]
    (when (neg? idx)
      (throw (ex-info "Package target must use ecosystem:package." {:value value})))
    {:ecosystem (subs (str value) 0 idx)
     :package (subs (str value) (inc idx))}))

(defn reject-match
  [kind value]
  (case (keyword kind)
    :external-api {:kind "external-api" :host value}
    {:kind (name (keyword kind)) :label value}))

(defn- required-reason!
  [reason action]
  (when (str/blank? (str reason))
    (throw (ex-info "Map correction requires --reason."
                    {:action action}))))

(defn- write-result
  [path before after]
  {:schema status-schema
   :action "write"
   :path (map-store/write-map! path after)
   :before (graph-map/overlay-counts before)
   :after (graph-map/overlay-counts after)})

(defn init!
  [path project-id]
  (let [overlay (graph-map/empty-map project-id)]
    {:schema status-schema
     :action "init"
     :path (map-store/write-map! path overlay)
     :after (graph-map/overlay-counts overlay)}))

(defn status
  [path project-id]
  (let [exists? (map-store/file-exists? path)
        overlay (if exists?
                  (map-store/read-map path)
                  (graph-map/empty-map project-id))]
    {:schema status-schema
     :action "status"
     :path path
     :exists exists?
     :map (assoc (graph-map/overlay-counts overlay)
                 :schema (:schema overlay)
                 :project (:project overlay))}))

(defn active-project-systems
  [xtdb project-id]
  (->> (store/rows-by-field xtdb (:system-nodes store/tables) :project-id project-id)
       (filter :active?)
       vec))

(defn active-project-system-edges
  [xtdb project-id]
  (->> (store/rows-by-field xtdb (:system-edges store/tables) :project-id project-id)
       (filter :active?)
       vec))

(defn review
  [xtdb project {:keys [map-path limit]}]
  (let [project-id (:id project)
        overlay (when (and map-path (map-store/file-exists? map-path))
                  (map-store/read-map map-path))
        systems (active-project-systems xtdb project-id)
        edges (active-project-system-edges xtdb project-id)]
    {:schema review-schema
     :project-id project-id
     :map-path map-path
     :map (when overlay (graph-map/overlay-counts overlay))
     :candidates {:systems (mapv graph-map/system-entry
                                 (take (or limit 50)
                                       (sort-by (juxt :repo-id :label) systems)))
                  :totalSystems (count systems)
                  :totalEdges (count edges)}
     :nextActions [{:kind "review"
                    :label "Accept a researched system"
                    :command "ygg map accept system <target> --kind KIND --label LABEL --include repo:path --reason TEXT --map ygg.map.json"}
                   {:kind "reject"
                    :label "Reject a known false positive"
                    :command "ygg map reject <kind> <value> --reason TEXT --map ygg.map.json"}]}))

(defn explain
  [path value]
  (graph-map/system-by-id-or-label (map-store/read-map path) value))

(defn accept-system!
  [path value {:keys [kind label include reason]}]
  (required-reason! reason "accept-system")
  (let [before (map-store/read-map path)
        existing (graph-map/system-by-id-or-label before value)
        system (cond-> (or existing {:id value :label (or label value)})
                 kind (assoc :kind kind)
                 label (assoc :label label)
                 reason (assoc :reason reason)
                 include (update :includes (fnil conj []) include)
                 true (assoc :status "accepted"))
        after (if existing
                (graph-map/update-system before value (constantly system))
                (update before :systems (fnil conj []) system))]
    (write-result path before after)))

(defn set-kind!
  [path value kind reason]
  (required-reason! reason "set-kind")
  (let [before (map-store/read-map path)
        after (-> before
                  (graph-map/set-kind value kind)
                  (graph-map/update-system value #(assoc % :reason reason)))]
    (write-result path before after)))

(defn include!
  [path value include reason]
  (required-reason! reason "include")
  (let [before (map-store/read-map path)
        after (-> before
                  (graph-map/add-include value include)
                  (graph-map/update-system value #(assoc % :reason reason)))]
    (write-result path before after)))

(defn reject!
  [path kind value reason]
  (required-reason! reason "reject")
  (let [before (map-store/read-map path)
        after (graph-map/add-reject before (reject-match kind value) reason)]
    (write-result path before after)))

(defn package-import!
  [path import-prefix package-target {:keys [repo reason]}]
  (required-reason! reason "package-import")
  (let [before (map-store/read-map path)
        after (graph-map/add-package-import
               before
               (merge (parse-package-target package-target)
                      {:import import-prefix
                       :repo repo
                       :reason reason}))]
    (write-result path before after)))

(defn docs-attach!
  [path target source opts]
  (required-reason! (:reason opts) "docs-attach")
  (let [before (map-store/read-map path)
        after (graph-map/add-doc before target source opts)]
    (write-result path before after)))

(defn edge-add!
  [path edge]
  (required-reason! (:reason edge) "edge-add")
  (let [before (map-store/read-map path)
        after (graph-map/add-edge before edge)]
    (write-result path before after)))

(defn apply-overlay!
  "Write a pre-validated overlay update from a review workflow."
  [path project-id f]
  (let [before (map-store/read-or-empty path project-id)
        after (f before)]
    (write-result path before after)))
