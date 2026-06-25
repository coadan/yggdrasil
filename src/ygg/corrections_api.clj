(ns ygg.corrections-api
  "Validated operations for accepted correction facts."
  (:require [ygg.corrections :as corrections]
            [ygg.correction-overlay :as correction-overlay]
            [ygg.xtdb :as store]))

(def status-schema
  corrections/status-schema)

(def review-schema
  corrections/review-schema)

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
      (throw (ex-info "Package target must use ecosystem:package."
                      {:value value})))
    {:ecosystem (subs (str value) 0 idx)
     :package (subs (str value) (inc idx))}))

(defn reject-match
  [kind value]
  (case (keyword kind)
    :external-api {:kind "external-api" :host value}
    {:kind (name (keyword kind)) :label value}))

(defn- required-reason!
  [reason action]
  (corrections/required-reason! reason action))

(defn init!
  "Return current correction status. Corrections are created only by explicit
  accepted facts, so there is no mutable file or project-local state to create."
  [xtdb project-id]
  (assoc (corrections/status xtdb project-id)
         :action "init"))

(defn status
  [xtdb project-id]
  (corrections/status xtdb project-id))

(def ^:private review-system-order-fields
  [:repo-id :label])

(def ^:private review-system-row-fields
  [:xt/id
   :project-id
   :repo-id
   :label
   :kind
   :source
   :candidate-types
   :metrics
   :path-prefix
   :aliases
   :evidence
   :active?])

(defn active-project-system-count
  [xtdb project-id]
  (store/count-rows xtdb
                    (:system-nodes store/tables)
                    {:project-id project-id
                     :active? true}))

(defn active-project-system-page
  [xtdb project-id limit]
  (vec (store/ordered-rows
        xtdb
        {:table (:system-nodes store/tables)
         :constraints {:project-id project-id
                       :active? true}
         :order-fields review-system-order-fields
         :limit limit
         :return-fields review-system-row-fields})))

(defn active-project-system-edge-count
  [xtdb project-id]
  (store/count-rows xtdb
                    (:system-edges store/tables)
                    {:project-id project-id
                     :active? true}))

(defn review
  [xtdb project {:keys [limit]}]
  (let [project-id (:id project)
        overlay (corrections/overlay xtdb project-id)
        limit (or limit 50)
        systems (active-project-system-page xtdb project-id limit)
        system-count (active-project-system-count xtdb project-id)
        edge-count (active-project-system-edge-count xtdb project-id)]
    {:schema review-schema
     :project-id project-id
     :corrections (correction-overlay/overlay-counts overlay)
     :candidates {:systems (mapv correction-overlay/system-entry
                                 systems)
                  :totalSystems system-count
                  :totalEdges edge-count}
     :nextActions [{:kind "review"
                    :label "Accept a researched system"
                    :command "ygg corrections accept system <target> --kind KIND --label LABEL --include repo:path --reason TEXT"}
                   {:kind "reject"
                    :label "Reject a known false positive"
                    :command "ygg corrections reject <kind> <value> --reason TEXT"}]}))

(defn explain
  [xtdb project-id value]
  (correction-overlay/system-by-id-or-label (corrections/overlay xtdb project-id) value))

(defn- write-result
  [xtdb project-id before row]
  {:schema status-schema
   :action "write"
   :project-id project-id
   :rows [row]
   :before (correction-overlay/overlay-counts before)
   :after (correction-overlay/overlay-counts (corrections/overlay xtdb project-id))})

(defn- write-correction!
  [xtdb project-id operation payload opts]
  (required-reason! (:reason opts) (name (keyword operation)))
  (let [before (corrections/overlay xtdb project-id)
        row (corrections/add! xtdb project-id operation payload opts)]
    (write-result xtdb project-id before row)))

(defn accept-system!
  [xtdb project-id value {:keys [kind label include reason]}]
  (let [payload (cond-> {:id value
                         :label (or label value)}
                  kind (assoc :kind kind)
                  include (assoc :includes [include]))]
    (write-correction! xtdb
                       project-id
                       :accept-system
                       payload
                       {:reason reason
                        :source {:kind :human}})))

(defn set-kind!
  [xtdb project-id value kind reason]
  (write-correction! xtdb
                     project-id
                     :set-system-kind
                     {:target value
                      :kind kind}
                     {:reason reason
                      :source {:kind :human}}))

(defn include!
  [xtdb project-id value include reason]
  (write-correction! xtdb
                     project-id
                     :add-system-include
                     {:target value
                      :include include}
                     {:reason reason
                      :source {:kind :human}}))

(defn reject!
  [xtdb project-id kind value reason]
  (write-correction! xtdb
                     project-id
                     :reject-system
                     {:match (reject-match kind value)}
                     {:reason reason
                      :source {:kind :human}}))

(defn package-import!
  [xtdb project-id import-prefix package-target {:keys [repo reason]}]
  (write-correction! xtdb
                     project-id
                     :add-package-import
                     (merge (parse-package-target package-target)
                            {:import import-prefix
                             :repo repo})
                     {:reason reason
                      :source {:kind :human}}))

(defn docs-attach!
  [xtdb project-id target source opts]
  (write-correction! xtdb
                     project-id
                     :attach-doc
                     (merge {:target target
                             :source source}
                            (select-keys opts [:role
                                               :heading
                                               :start-line
                                               :end-line]))
                     {:reason (:reason opts)
                      :source {:kind :human}}))

(defn edge-add!
  [xtdb project-id edge]
  (write-correction! xtdb
                     project-id
                     :add-edge
                     (dissoc edge :reason :evidence)
                     {:reason (:reason edge)
                      :evidence-ids (:evidence edge)
                      :confidence (:confidence edge)
                      :source {:kind :human}}))

(defn apply-overlay!
  "Persist a pre-validated overlay update as correction facts."
  ([xtdb project-id f] (apply-overlay! xtdb project-id f {}))
  ([xtdb project-id f opts]
   (corrections/apply-overlay! xtdb project-id f opts)))
