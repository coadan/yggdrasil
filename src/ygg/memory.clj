(ns ygg.memory
  "XTDB-backed project memory rows and retrieval."
  (:require [ygg.hash :as hash]
            [ygg.search-doc :as search-doc]
            [ygg.text :as text]
            [ygg.xtdb :as store]
            [clojure.set :as set]
            [clojure.string :as str]))

(def schema
  "ygg.memory/v1")

(def search-schema
  "ygg.memory.search/v1")

(def review-schema
  "ygg.memory.review/v1")

(def write-schema
  "ygg.memory.write/v1")

(def scopes
  #{:developer :project :repo})

(def visibilities
  #{:private :project})

(def kinds
  #{:preference :lesson :gotcha :decision-note :workflow :rationale})

(def statuses
  #{:suggested :observed :reviewed :rejected :superseded})

(def confidences
  #{:suggested :observed :reviewed})

(def default-limit
  8)

(def default-context-limit
  3)

(def memory-fields
  [:xt/id
   :schema
   :project-id
   :repo-id
   :scope
   :visibility
   :owner
   :agent-id
   :kind
   :status
   :confidence
   :target-ids
   :text
   :summary
   :tags
   :evidence
   :supersedes
   :reason
   :source
   :created-at-ms
   :updated-at-ms
   :valid-from-ms
   :active?])

(def ^:private target-label-fields
  [:xt/id
   :label
   :path
   :name
   :namespace
   :system-key
   :package-name])

(def ^:private target-label-tables
  [:nodes
   :files
   :chunks
   :system-nodes
   :system-evidence])

(defn now-ms
  []
  (System/currentTimeMillis))

(defn default-owner
  []
  (or (System/getenv "YGG_MEMORY_OWNER")
      (System/getenv "USER")
      (System/getProperty "user.name")
      "local"))

(defn- blankish?
  [value]
  (str/blank? (str value)))

(defn- required-value!
  [field value]
  (when (blankish? value)
    (throw (ex-info "Memory row requires a value."
                    {:field field}))))

(defn- normalize-keyword
  [value default supported field]
  (let [value (keyword (or value default))]
    (when-not (contains? supported value)
      (throw (ex-info "Unsupported memory field value."
                      {:field field
                       :value value
                       :supported (sort supported)})))
    value))

(defn- normalize-scope
  [scope]
  (normalize-keyword scope :developer scopes :scope))

(defn- normalize-visibility
  [visibility scope]
  (normalize-keyword visibility
                     (if (= :developer scope) :private :project)
                     visibilities
                     :visibility))

(defn- normalize-kind
  [kind]
  (normalize-keyword kind :lesson kinds :kind))

(defn- normalize-status
  [status]
  (normalize-keyword status :suggested statuses :status))

(defn- normalize-confidence
  [confidence status]
  (normalize-keyword confidence
                     (case status
                       :reviewed :reviewed
                       :observed :observed
                       :suggested)
                     confidences
                     :confidence))

(defn- normalize-source
  [source]
  (cond
    (nil? source) {:kind :human}
    (map? source) (cond-> source
                    (:kind source) (update :kind keyword))
    (keyword? source) {:kind source}
    :else {:kind :agent
           :id (str source)}))

(defn- normalize-strings
  [values]
  (->> values
       (remove nil?)
       (map str)
       (map str/trim)
       (remove str/blank?)
       distinct
       vec))

(defn- target-ids-required!
  [scope target-ids]
  (when (and (not= :project scope)
             (empty? target-ids))
    (throw (ex-info "Memory requires --target unless --scope project is explicit."
                    {:scope scope}))))

(defn- canonical-key
  [k]
  (cond
    (keyword? k) (name k)
    (nil? k) ""
    :else (str k)))

(defn- canonical-value
  [value]
  (cond
    (map? value)
    (into (sorted-map-by #(compare (canonical-key %1) (canonical-key %2)))
          (map (fn [[k v]] [k (canonical-value v)]))
          value)

    (set? value)
    (->> value
         (map canonical-value)
         (sort-by pr-str)
         vec)

    (sequential? value)
    (mapv canonical-value value)

    :else value))

(defn row-id
  [project-id attrs]
  (str "memory:"
       project-id
       ":"
       (hash/short-hash
        (pr-str
         (canonical-value
          (select-keys attrs
                       [:repo-id
                        :scope
                        :visibility
                        :owner
                        :agent-id
                        :kind
                        :status
                        :target-ids
                        :text
                        :created-at-ms]))))))

(defn memory-row
  "Return a validated memory row. Developer memories are private by default;
  repo- and project-scope memories default to project visibility."
  [project-id attrs]
  (let [scope (normalize-scope (:scope attrs))
        visibility (normalize-visibility (:visibility attrs) scope)
        status (normalize-status (:status attrs))
        now (long (or (:now attrs) (:created-at-ms attrs) (now-ms)))
        target-ids (normalize-strings (:target-ids attrs))
        tags (normalize-strings (:tags attrs))
        supersedes (normalize-strings (:supersedes attrs))
        owner (or (:owner attrs)
                  (when (or (= :developer scope)
                            (= :private visibility))
                    (default-owner)))
        text (some-> (:text attrs) str)
        summary (or (some-> (:summary attrs) str)
                    (some-> text str/trim))]
    (required-value! :project-id project-id)
    (required-value! :text text)
    (target-ids-required! scope target-ids)
    (cond-> {:schema schema
             :project-id project-id
             :repo-id (:repo-id attrs)
             :scope scope
             :visibility visibility
             :owner owner
             :agent-id (:agent-id attrs)
             :kind (normalize-kind (:kind attrs))
             :status status
             :confidence (normalize-confidence (:confidence attrs) status)
             :target-ids target-ids
             :text text
             :summary summary
             :tags tags
             :evidence (vec (:evidence attrs))
             :supersedes supersedes
             :reason (:reason attrs)
             :source (normalize-source (:source attrs))
             :created-at-ms now
             :updated-at-ms now
             :valid-from-ms (long (or (:valid-from-ms attrs) now))
             :active? (not (contains? #{:rejected :superseded} status))}
      true (assoc :xt/id (or (:xt/id attrs)
                             (:id attrs)
                             (row-id project-id
                                     (assoc attrs
                                            :scope scope
                                            :visibility visibility
                                            :owner owner
                                            :status status
                                            :target-ids target-ids
                                            :created-at-ms now)))))))

(defn validate-row!
  [row]
  (doseq [field [:xt/id :schema :project-id :scope :visibility :kind :status :text]]
    (required-value! field (get row field)))
  (when-not (= schema (:schema row))
    (throw (ex-info "Invalid memory row schema."
                    {:schema (:schema row)
                     :expected schema})))
  (normalize-scope (:scope row))
  (normalize-visibility (:visibility row) (:scope row))
  (normalize-kind (:kind row))
  (normalize-status (:status row))
  row)

(defn- target-label
  [row]
  (or (:label row)
      (:path row)
      (:name row)
      (:namespace row)
      (:system-key row)
      (:package-name row)
      (:xt/id row)))

(defn- target-label-rows
  [xtdb table ids scope]
  (try
    (store/rows-with-field-values
     xtdb
     {:table table
      :field :xt/id
      :values ids
      :constraints scope
      :return-fields target-label-fields})
    (catch Exception _
      [])))

(defn- target-labels-by-id
  [xtdb row]
  (let [ids (normalize-strings (:target-ids row))
        scope (cond-> {:project-id (:project-id row)
                       :active? true}
                (:repo-id row) (assoc :repo-id (:repo-id row)))]
    (if (empty? ids)
      {}
      (->> target-label-tables
           (mapcat #(target-label-rows xtdb (get store/tables %) ids scope))
           (reduce (fn [labels target-row]
                     (if-let [label (target-label target-row)]
                       (assoc labels (str (:xt/id target-row)) (str label))
                       labels))
                   {})))))

(defn search-doc-row
  ([row] (search-doc-row nil row))
  ([xtdb row]
   (search-doc/build-memory-search-doc (:xt/id row)
                                       row
                                       (if xtdb
                                         (target-labels-by-id xtdb row)
                                         {}))))

(defn commit-memory!
  [xtdb row]
  (let [row (validate-row! row)
        search-doc (search-doc-row xtdb row)]
    (store/execute-tx! xtdb [(store/put-op (:memories store/tables) row)
                             (store/put-op (:search-docs store/tables)
                                           search-doc)])
    row))

(defn add!
  [xtdb project-id attrs]
  (commit-memory! xtdb (memory-row project-id attrs)))

(defn memory-by-id
  ([xtdb id] (memory-by-id xtdb id {}))
  ([xtdb id opts]
   (store/row-by-id xtdb (:memories store/tables) id (store/read-context opts))))

(defn- active-row?
  [row]
  (not= false (:active? row)))

(defn- visible-row?
  [{:keys [owner include-private? exclude-private?]} row]
  (or (= :project (:visibility row))
      (= "project" (str (:visibility row)))
      (and (not exclude-private?)
           (or include-private?
               (= (str (or owner (default-owner))) (str (:owner row)))))))

(defn- active-memory-rows
  [xtdb {:keys [project-id repo-id read-context status]}]
  (try
    (->> (store/constrained-rows xtdb
                                 (:memories store/tables)
                                 (cond-> {:project-id project-id}
                                   repo-id (assoc :repo-id repo-id)
                                   status (assoc :status (keyword status)))
                                 (store/read-context read-context))
         (filter active-row?)
         vec)
    (catch Exception _
      [])))

(defn rows
  [xtdb opts]
  (->> (active-memory-rows xtdb opts)
       (filter #(visible-row? opts %))
       (sort-by (juxt (comp - long #(or (:updated-at-ms %) 0))
                      :xt/id))
       vec))

(defn memories-for-targets
  [xtdb {:keys [target-ids] :as opts}]
  (let [target-set (set (normalize-strings target-ids))]
    (if (empty? target-set)
      []
      (->> (rows xtdb opts)
           (filter #(seq (set/intersection target-set
                                           (set (map str (:target-ids %))))))
           vec))))

(defn- memory-search-text
  [row]
  (str/join "\n"
            (remove str/blank?
                    [(name (:kind row))
                     (:summary row)
                     (str/join " " (:tags row))
                     (str/join " " (:target-ids row))
                     (:text row)])))

(def status-weight
  {:reviewed 1.5
   :observed 1.1
   :suggested 0.7})

(defn- direct-target?
  [target-set row]
  (boolean
   (and (seq target-set)
        (seq (set/intersection target-set
                               (set (map str (:target-ids row))))))))

(defn- scored-row
  [query-tokens target-set row]
  (let [lexical-score (text/token-score query-tokens
                                        (text/tokenize-all
                                         (memory-search-text row)))
        direct? (direct-target? target-set row)
        target-score (if direct? 2.0 0.0)
        status-score (double (get status-weight (:status row) 0.5))
        matching? (or (pos? lexical-score) direct?)
        score (if matching?
                (+ lexical-score target-score status-score)
                0.0)]
    (assoc row
           :score score
           :basis (cond-> []
                    (pos? lexical-score) (conj :lexical)
                    direct? (conj :graph-attachment)
                    matching? (conj :status)))))

(defn- searchable-row?
  [{:keys [include-suggested?]} row]
  (and (not (contains? #{:rejected :superseded} (:status row)))
       (or include-suggested?
           (not= :suggested (:status row))
           (some #{:graph-attachment} (:basis row)))))

(defn packet-row
  [row]
  {:id (:xt/id row)
   :kind (:kind row)
   :scope (:scope row)
   :visibility (:visibility row)
   :status (:status row)
   :summary (:summary row)
   :text (:text row)
   :targetIds (:target-ids row)
   :tags (:tags row)
   :score (double (or (:score row) 0.0))
   :basis (vec (:basis row))
   :createdAtMs (:created-at-ms row)
   :updatedAtMs (:updated-at-ms row)})

(defn search
  "Return ranked visible memories for query text and optional target ids."
  [xtdb query-text {:keys [limit target-ids] :or {limit default-limit} :as opts}]
  (let [query-tokens (text/tokenize query-text)
        target-set (set (normalize-strings target-ids))
        ranked (->> (rows xtdb opts)
                    (map #(scored-row query-tokens target-set %))
                    (filter #(pos? (double (:score %))))
                    (filter #(searchable-row? opts %))
                    (sort-by (juxt (comp - double :score)
                                   (comp - long #(or (:updated-at-ms %) 0))
                                   :xt/id))
                    (take limit)
                    (mapv packet-row))]
    {:schema search-schema
     :project-id (:project-id opts)
     :query query-text
     :targetIds (vec target-set)
     :memories ranked
     :counts {:returned (count ranked)}}))

(defn context-memories
  [xtdb query-text opts]
  (:memories
   (search xtdb
           query-text
           (merge {:limit default-context-limit
                   :include-suggested? false}
                  opts))))

(defn review
  [xtdb {:keys [status limit] :or {status :suggested limit 50} :as opts}]
  (let [rows (->> (rows xtdb (assoc opts :status (keyword status)))
                  (take limit)
                  (mapv packet-row))]
    {:schema review-schema
     :project-id (:project-id opts)
     :status (keyword status)
     :memories rows
     :counts {:returned (count rows)}}))

(defn counts
  [xtdb {:keys [project-id repo-id read-context]}]
  (let [empty-counts {:memories 0
                      :memory-statuses {}
                      :suggested-memories 0
                      :reviewed-memories 0
                      :observed-memories 0}]
    (try
      (let [scope (cond-> {:project-id project-id}
                    repo-id (assoc :repo-id repo-id))
            read-context (store/read-context read-context)
            total (store/active-row-count xtdb (:memories store/tables) scope read-context)
            status-counts (->> (store/active-row-counts-by-field xtdb
                                                                 (:memories store/tables)
                                                                 :status
                                                                 scope
                                                                 read-context)
                               (map (fn [{:keys [value count]}]
                                      [(keyword value) (long count)]))
                               (into {}))]
        {:memories total
         :memory-statuses status-counts
         :suggested-memories (get status-counts :suggested 0)
         :reviewed-memories (get status-counts :reviewed 0)
         :observed-memories (get status-counts :observed 0)})
      (catch Exception _
        empty-counts))))

(defn- update-row!
  [xtdb id f]
  (let [row (memory-by-id xtdb id)]
    (when-not row
      (throw (ex-info "Memory not found."
                      {:id id})))
    (commit-memory! xtdb (f row))))

(defn accept!
  [xtdb id reason]
  (required-value! :reason reason)
  (update-row! xtdb
               id
               #(assoc %
                       :status :reviewed
                       :confidence :reviewed
                       :reason reason
                       :updated-at-ms (now-ms)
                       :active? true)))

(defn reject!
  [xtdb id reason]
  (required-value! :reason reason)
  (update-row! xtdb
               id
               #(assoc %
                       :status :rejected
                       :reason reason
                       :updated-at-ms (now-ms)
                       :active? false)))

(defn attach!
  [xtdb id target-ids reason]
  (required-value! :reason reason)
  (let [target-ids (normalize-strings target-ids)]
    (when (empty? target-ids)
      (throw (ex-info "Memory attach requires a target."
                      {:id id})))
    (update-row! xtdb
                 id
                 #(-> %
                      (update :target-ids (fn [existing]
                                            (normalize-strings
                                             (concat existing target-ids))))
                      (assoc :reason reason
                             :updated-at-ms (now-ms))))))

(defn supersede!
  [xtdb id attrs]
  (required-value! :reason (:reason attrs))
  (let [old (memory-by-id xtdb id)]
    (when-not old
      (throw (ex-info "Memory not found."
                      {:id id})))
    (let [now (now-ms)
          superseded (assoc old
                            :status :superseded
                            :reason (:reason attrs)
                            :updated-at-ms now
                            :active? false)
          replacement (memory-row (:project-id old)
                                  (merge old
                                         attrs
                                         {:xt/id nil
                                          :id nil
                                          :status (or (:status attrs) :suggested)
                                          :supersedes [id]
                                          :now now}))]
      (store/execute-tx!
       xtdb
       [(store/put-op (:memories store/tables) superseded)
        (store/put-op (:search-docs store/tables)
                      (search-doc-row xtdb superseded))
        (store/put-op (:memories store/tables) replacement)
        (store/put-op (:search-docs store/tables)
                      (search-doc-row xtdb replacement))])
      {:schema write-schema
       :action "supersede"
       :superseded id
       :memory replacement})))
