(ns ygg.cursor
  "Stable, revisioned graph cursors for progressive agent graph exploration."
  (:require [ygg.command :as command]
            [ygg.context :as context]
            [ygg.graph :as graph]
            [ygg.hash :as hash]
            [ygg.map-store :as map-store]
            [ygg.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util Date]))

(def schema
  "ygg.cursor/v1")

(def packet-schema
  "ygg.cursor.packet/v1")

(def error-schema
  "ygg.cursor.error/v1")

(def default-graph-limit
  10000)

(def default-min-confidence
  0.55)

(defn now-ms
  []
  (System/currentTimeMillis))

(defn- date-string
  [value]
  (cond
    (nil? value) nil
    (instance? Date value) (str (.toInstant ^Date value))
    :else (str value)))

(defn- stable-key
  [value]
  (cond
    (keyword? value) (if-let [ns (namespace value)]
                       (str ns "/" (name value))
                       (name value))
    :else (str value)))

(defn- stable-form
  [value]
  (cond
    (map? value)
    (into (sorted-map)
          (map (fn [[k v]]
                 [(stable-key k) (stable-form v)]))
          value)

    (set? value)
    (sort (map stable-form value))

    (sequential? value)
    (mapv stable-form value)

    (keyword? value)
    (stable-key value)

    (instance? Date value)
    (date-string value)

    :else value))

(defn- cursor-id
  [row]
  (str "cursor:"
       (hash/short-hash
        (stable-form (dissoc row :xt/id :created-at-ms)))))

(defn- compact-map
  [m]
  (into {}
        (remove (fn [[_ v]]
                  (or (nil? v)
                      (and (coll? v) (empty? v)))))
        m))

(defn- ordered-distinct
  [& colls]
  (loop [remaining (seq (apply concat colls))
         seen #{}
         out []]
    (if-let [value (first remaining)]
      (if (or (nil? value) (contains? seen value))
        (recur (next remaining) seen out)
        (recur (next remaining) (conj seen value) (conj out value)))
      out)))

(defn- map-snapshot
  [path]
  (when (and path (map-store/file-exists? path))
    (let [contents (slurp (io/file path))]
      {:map-path path
       :map-sha (hash/short-hash contents)
       :map-overlay (map-store/read-map path)})))

(defn- read-context-from-basis
  [basis]
  (select-keys basis [:valid-at :known-at :snapshot-token :current-time]))

(defn- basis-packet
  [basis]
  (cond-> basis
    (:valid-at basis) (update :valid-at date-string)
    (:known-at basis) (update :known-at date-string)
    (:current-time basis) (update :current-time date-string)))

(defn- resolve-basis
  [xtdb project-id {:keys [read-context view-id map-path map-sha]}]
  (let [read-context (store/read-context read-context)
        latest-snapshot (when-not (:valid-at read-context)
                          (store/latest-source-snapshot xtdb project-id))
        valid-at (or (:valid-at read-context)
                     (:basis-instant latest-snapshot))
        basis (cond-> {:project-id project-id
                       :graph-schema graph/schema}
                valid-at (assoc :valid-at valid-at)
                (:known-at read-context) (assoc :known-at (:known-at read-context))
                (:snapshot-token read-context) (assoc :snapshot-token
                                                      (:snapshot-token read-context))
                (:current-time read-context) (assoc :current-time
                                                    (:current-time read-context))
                (:xt/id latest-snapshot) (assoc :snapshot-id (:xt/id latest-snapshot))
                view-id (assoc :view-id view-id)
                map-path (assoc :map-path map-path)
                map-sha (assoc :map-sha map-sha))
        warnings (cond-> []
                   (nil? valid-at)
                   (conj "cursor basis has no fixed valid time; graph reads use XTDB current time"))]
    {:basis basis
     :warnings warnings}))

(defn- system-graph
  [xtdb cursor]
  (graph/system-graph xtdb
                      (:project-id cursor)
                      {:limit default-graph-limit
                       :min-confidence (get-in cursor [:limits :min-confidence]
                                               default-min-confidence)
                       :detail :expanded
                       :map-overlay (:map-overlay cursor)
                       :read-context (read-context-from-basis (:basis cursor))
                       :view-id (get-in cursor [:basis :view-id])}))

(defn- graph-index
  [graph-data]
  (let [edges (:edges graph-data)
        by-node (group-by (fn [edge]
                            [(:source edge) (:target edge)])
                          edges)]
    {:nodes-by-id (into {} (map (juxt :id identity)) (:nodes graph-data))
     :edges edges
     :edges-by-node (reduce (fn [acc edge]
                              (-> acc
                                  (update (:source edge) (fnil conj []) edge)
                                  (update (:target edge) (fnil conj []) edge)))
                            {}
                            edges)
     :edge-pairs by-node}))

(defn- node-text
  [node]
  (str/join " "
            (remove str/blank?
                    [(str (:id node))
                     (str (:label node))
                     (str (:kind node))
                     (str (:repo node))
                     (str (:path node))
                     (str (:pathPrefix node))])))

(defn- resolve-node-id
  [graph-data value]
  (let [value (str value)
        needle (str/lower-case value)]
    (or (some #(when (= value (:id %)) (:id %)) (:nodes graph-data))
        (some #(when (= value (:label %)) (:id %)) (:nodes graph-data))
        (some #(when (str/includes? (str/lower-case (node-text %)) needle)
                 (:id %))
              (:nodes graph-data)))))

(defn- missing-cursor!
  [id]
  (throw (ex-info "Cursor not found."
                  {:schema error-schema
                   :error "cursor not found"
                   :cursor id})))

(defn- invalid-target!
  [cursor-id target]
  (throw (ex-info "Cursor target not found."
                  {:schema error-schema
                   :error "cursor target not found"
                   :cursor cursor-id
                   :target target})))

(defn- load-cursor!
  [xtdb id]
  (or (store/graph-cursor xtdb id)
      (missing-cursor! id)))

(defn- compact-node
  [node]
  (compact-map
   {:id (:id node)
    :label (:label node)
    :kind (:kind node)
    :repo (:repo node)
    :repoRole (:repoRole node)
    :path (:path node)
    :pathPrefix (:pathPrefix node)
    :clusterId (:clusterId node)
    :clusterLabel (:clusterLabel node)
    :degree (:degree node)
    :attrs (:attrs node)
    :tags (:tags node)
    :metrics (:metrics node)}))

(defn- compact-edge
  [edge]
  (compact-map
   {:id (:id edge)
    :source (:source edge)
    :target (:target edge)
    :relation (:relation edge)
    :confidence (:confidence edge)
    :salience (:salience edge)
    :visibility (:visibility edge)
    :rules (:rules edge)
    :evidence (:evidence edge)}))

(defn- top-node-ids
  [graph-data limit]
  (->> (:nodes graph-data)
       (sort-by (fn [node]
                  [(- (long (or (:degree node) 0)))
                   (:repo node)
                   (:label node)]))
       (take limit)
       (mapv :id)))

(defn- neighbor-ids
  ([graph-data ids] (neighbor-ids graph-data ids nil))
  ([graph-data ids relation]
   (let [ids (set ids)
         relation (some-> relation name)]
     (->> (:edges graph-data)
          (filter #(or (contains? ids (:source %))
                       (contains? ids (:target %))))
          (filter #(or (nil? relation)
                       (= relation (:relation %))))
          (mapcat (juxt :source :target))
          (remove ids)
          ordered-distinct))))

(defn- query-entity-ids
  [xtdb query-text {:keys [project-id retriever embedding-client read-context
                           map-path map-overlay budget node-limit edge-limit
                           doc-limit snippet-chars min-confidence]}]
  (when-not (str/blank? (str query-text))
    (->> (context/context-packet xtdb
                                 query-text
                                 {:project-id project-id
                                  :retriever (or retriever :auto)
                                  :embedding-client embedding-client
                                  :map-path map-path
                                  :map-overlay map-overlay
                                  :read-context read-context
                                  :budget budget
                                  :entity-limit node-limit
                                  :edge-limit edge-limit
                                  :doc-limit doc-limit
                                  :snippet-chars snippet-chars
                                  :min-confidence min-confidence})
         :entities
         (mapv :id))))

(defn- target-docs
  [xtdb cursor target-id]
  (let [limits (:limits cursor)
        read-context (read-context-from-basis (:basis cursor))
        attached (:docs (context/docs-for xtdb
                                          target-id
                                          {:project-id (:project-id cursor)
                                           :map-overlay (:map-overlay cursor)
                                           :read-context read-context
                                           :snippet-chars (:snippet-chars limits)}))
        candidates (context/doc-candidates xtdb
                                           target-id
                                           {:project-id (:project-id cursor)
                                            :read-context read-context
                                            :limit (:doc-limit limits)
                                            :snippet-chars (:snippet-chars limits)})]
    (->> (concat attached candidates)
         (reduce (fn [out doc]
                   (let [k [(get-in doc [:source :repo])
                            (get-in doc [:source :path])
                            (get-in doc [:source :lines])
                            (:role doc)]]
                     (if (contains? (:seen out) k)
                       out
                       (-> out
                           (update :seen conj k)
                           (update :docs conj doc)))))
                 {:seen #{}
                  :docs []})
         :docs
         (sort-by (juxt (comp - #(double (or % 0.0)) :score)
                        :status
                        #(get-in % [:source :path])))
         (take (:doc-limit limits))
         vec)))

(defn- selected-edges
  [graph-data focus-ids frontier-ids edge-limit]
  (let [focus (set focus-ids)
        visible (set (concat focus-ids frontier-ids))]
    (->> (:edges graph-data)
         (filter #(or (contains? focus (:source %))
                      (contains? focus (:target %))
                      (and (contains? visible (:source %))
                           (contains? visible (:target %)))))
         (take edge-limit)
         (mapv compact-edge))))

(defn- graph-summary
  [graph-data]
  {:basis (:basis graph-data)
   :counts {:nodes (count (:nodes graph-data))
            :edges (count (:edges graph-data))
            :clusters (count (:clusters graph-data))}
   :detail "expanded"})

(defn- explore-command
  [action & args]
  (str "ygg explore " action
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- next-action-rows
  [cursor focus]
  (let [cursor-id (:xt/id cursor)]
    (vec
     (concat
      (mapcat (fn [node]
                [{:kind :expand
                  :label "Expand adjacent systems"
                  :target (:id node)
                  :command (explore-command "expand" cursor-id (:id node))
                  :reason "Follow adjacent system graph edges"}
                 {:kind :docs
                  :label "Inspect docs for this system"
                  :target (:id node)
                  :command (explore-command "docs" cursor-id (:id node))
                  :reason "Inspect accepted and candidate docs for this system"}])
              (take 3 focus))
      [{:kind :search
        :label "Search within this cursor basis"
        :command (explore-command "search" cursor-id "<text>")
        :reason "Search within this cursor basis"}]))))

(defn- next-compat
  [actions]
  (mapv #(select-keys % [:command :reason]) actions))

(defn- strip-doc-snippets
  [packet]
  (update packet :docs
          (fn [docs]
            (mapv (fn [doc]
                    (if (:snippet doc)
                      (-> doc
                          (dissoc :snippet)
                          (assoc :snippetOmitted true))
                      doc))
                  docs))))

(defn- shrink-vector
  [packet key]
  (update packet key
          (fn [items]
            (let [n (count items)]
              (if (< n 2)
                items
                (subvec (vec items) 0 (max 1 (quot n 2))))))))

(defn- add-warning
  [warnings warning]
  (let [warnings (vec warnings)]
    (if (some #{warning} warnings)
      warnings
      (conj warnings warning))))

(defn- fit-budget
  [packet budget]
  (loop [packet packet
         steps [strip-doc-snippets
                #(shrink-vector % :docs)
                #(shrink-vector % :edges)
                #(shrink-vector % :frontier)]]
    (let [estimated (context/estimate-tokens packet)]
      (if (or (<= estimated budget) (empty? steps))
        (assoc packet :budget {:requested budget
                               :estimated estimated
                               :truncated (< budget estimated)})
        (recur (update ((first steps) packet)
                       :warnings
                       add-warning
                       "cursor packet truncated to fit budget")
               (rest steps))))))

(defn render
  "Render a cursor revision as an agent-facing packet."
  ([xtdb cursor] (render xtdb cursor {}))
  ([xtdb cursor {:keys [budget include-docs? target-id]}]
   (let [limits (merge (:limits cursor)
                       (cond-> {}
                         budget (assoc :budget budget)))
         cursor (assoc cursor :limits limits)
         graph-data (system-graph xtdb cursor)
         {:keys [nodes-by-id]} (graph-index graph-data)
         focus (->> (:focus-ids cursor)
                    (keep nodes-by-id)
                    (take (:node-limit limits))
                    (mapv compact-node))
         frontier (->> (:frontier-ids cursor)
                       (remove (set (:focus-ids cursor)))
                       (keep nodes-by-id)
                       (take (:node-limit limits))
                       (mapv compact-node))
         edges (selected-edges graph-data
                               (:focus-ids cursor)
                               (mapv :id frontier)
                               (:edge-limit limits))
         doc-target (or target-id
                        (get-in cursor [:operation :target-id])
                        (first (:focus-ids cursor)))
         docs (if (and include-docs? doc-target)
                (target-docs xtdb cursor doc-target)
                [])
         next-actions (next-action-rows cursor focus)
         packet {:schema packet-schema
                 :cursor (compact-map {:id (:xt/id cursor)
                                       :parentId (:parent-id cursor)
                                       :revision (:revision cursor)
                                       :mode (name (:mode cursor))
                                       :operation (:operation cursor)})
                 :basis (basis-packet (:basis cursor))
                 :graph (graph-summary graph-data)
                 :focus focus
                 :frontier frontier
                 :edges edges
                 :docs docs
                 :warnings (vec (:warnings cursor))
                 :next (next-compat next-actions)
                 :nextActions next-actions}]
     (fit-budget packet (:budget limits)))))

(defn- persist!
  [xtdb row]
  (store/commit-graph-cursor! xtdb (assoc row :xt/id (cursor-id row))))

(defn create!
  "Create and persist an initial system graph cursor revision."
  [xtdb {:keys [project-id query-text map-path view-id read-context retriever
                embedding-client budget node-limit edge-limit doc-limit
                snippet-chars min-confidence]
         :or {budget context/default-budget
              node-limit context/default-entity-limit
              edge-limit context/default-edge-limit
              doc-limit context/default-doc-limit
              snippet-chars context/default-snippet-chars
              min-confidence default-min-confidence}}]
  (when (str/blank? (str project-id))
    (throw (ex-info "Cursor create requires --project."
                    {:schema error-schema
                     :error "missing project"})))
  (let [{:keys [map-overlay map-sha] stored-map-path :map-path} (map-snapshot map-path)
        {:keys [basis warnings]} (resolve-basis xtdb
                                                project-id
                                                {:read-context read-context
                                                 :view-id view-id
                                                 :map-path stored-map-path
                                                 :map-sha map-sha})
        limits {:budget budget
                :node-limit node-limit
                :edge-limit edge-limit
                :doc-limit doc-limit
                :snippet-chars snippet-chars
                :min-confidence min-confidence}
        base {:schema schema
              :project-id project-id
              :mode :systems
              :query-text (not-empty (str/trim (str query-text)))
              :basis basis
              :map-overlay map-overlay
              :limits limits
              :parent-id nil
              :revision 0
              :operation {:kind :create}
              :active? true
              :created-at-ms (now-ms)
              :warnings warnings}
        graph-data (system-graph xtdb base)
        read-context* (read-context-from-basis basis)
        query-ids (query-entity-ids xtdb
                                    query-text
                                    {:project-id project-id
                                     :retriever retriever
                                     :embedding-client embedding-client
                                     :read-context read-context*
                                     :map-path stored-map-path
                                     :map-overlay map-overlay
                                     :budget budget
                                     :node-limit node-limit
                                     :edge-limit edge-limit
                                     :doc-limit doc-limit
                                     :snippet-chars snippet-chars
                                     :min-confidence min-confidence})
        focus-ids (vec (or (seq query-ids)
                           (seq (top-node-ids graph-data node-limit))
                           []))
        frontier-ids (->> (neighbor-ids graph-data focus-ids)
                          (take node-limit)
                          vec)
        row (persist! xtdb
                      (assoc base
                             :root-ids focus-ids
                             :focus-ids focus-ids
                             :visited-ids []
                             :frontier-ids frontier-ids))]
    (render xtdb row {:budget budget})))

(defn show
  "Render an existing cursor revision."
  ([xtdb cursor-id] (show xtdb cursor-id {}))
  ([xtdb cursor-id opts]
   (render xtdb (load-cursor! xtdb cursor-id) opts)))

(defn- child-row
  [cursor operation updates]
  (merge cursor
         updates
         {:xt/id nil
          :parent-id (:xt/id cursor)
          :revision (inc (long (:revision cursor)))
          :operation operation
          :created-at-ms (now-ms)
          :active? true}))

(defn open!
  "Persist a cursor revision focused on one system node."
  [xtdb cursor-id target {:keys [budget]}]
  (let [cursor (load-cursor! xtdb cursor-id)
        graph-data (system-graph xtdb cursor)
        target-id (or (resolve-node-id graph-data target)
                      (invalid-target! cursor-id target))
        frontier-ids (->> (neighbor-ids graph-data [target-id])
                          (take (get-in cursor [:limits :node-limit]))
                          vec)
        row (persist! xtdb
                      (child-row cursor
                                 {:kind :open
                                  :target-id target-id}
                                 {:focus-ids [target-id]
                                  :visited-ids (ordered-distinct (:visited-ids cursor)
                                                                 (:focus-ids cursor))
                                  :frontier-ids frontier-ids}))]
    (render xtdb row {:budget budget
                      :include-docs? true
                      :target-id target-id})))

(defn expand!
  "Persist a cursor revision with adjacent systems added to the frontier."
  [xtdb cursor-id target {:keys [budget relation limit]}]
  (let [cursor (load-cursor! xtdb cursor-id)
        graph-data (system-graph xtdb cursor)
        target-id (or (resolve-node-id graph-data target)
                      (invalid-target! cursor-id target))
        limit (or limit (get-in cursor [:limits :node-limit]))
        neighbors (->> (neighbor-ids graph-data [target-id] relation)
                       (take limit)
                       vec)
        row (persist! xtdb
                      (child-row cursor
                                 (cond-> {:kind :expand
                                          :target-id target-id}
                                   relation (assoc :relation (name relation)))
                                 {:focus-ids [target-id]
                                  :visited-ids (ordered-distinct (:visited-ids cursor)
                                                                 [target-id])
                                  :frontier-ids (ordered-distinct (:frontier-ids cursor)
                                                                  neighbors)}))]
    (render xtdb row {:budget budget})))

(defn docs!
  "Persist a cursor revision that inspects docs for one system node."
  [xtdb cursor-id target {:keys [budget]}]
  (let [cursor (load-cursor! xtdb cursor-id)
        graph-data (system-graph xtdb cursor)
        target-id (or (resolve-node-id graph-data target)
                      (invalid-target! cursor-id target))
        row (persist! xtdb
                      (child-row cursor
                                 {:kind :docs
                                  :target-id target-id}
                                 {:focus-ids [target-id]}))]
    (render xtdb row {:budget budget
                      :include-docs? true
                      :target-id target-id})))

(defn search!
  "Persist a cursor revision with query matches added to the frontier."
  [xtdb cursor-id query-text {:keys [budget retriever embedding-client limit]}]
  (when (str/blank? (str query-text))
    (throw (ex-info "Cursor search requires query text."
                    {:schema error-schema
                     :error "missing query"})))
  (let [cursor (load-cursor! xtdb cursor-id)
        limits (:limits cursor)
        read-context (read-context-from-basis (:basis cursor))
        ids (query-entity-ids xtdb
                              query-text
                              {:project-id (:project-id cursor)
                               :retriever retriever
                               :embedding-client embedding-client
                               :read-context read-context
                               :map-overlay (:map-overlay cursor)
                               :budget (or budget (:budget limits))
                               :node-limit (or limit (:node-limit limits))
                               :edge-limit (:edge-limit limits)
                               :doc-limit (:doc-limit limits)
                               :snippet-chars (:snippet-chars limits)
                               :min-confidence (:min-confidence limits)})
        row (persist! xtdb
                      (child-row cursor
                                 {:kind :search
                                  :query-text query-text}
                                 {:frontier-ids (ordered-distinct (:frontier-ids cursor)
                                                                  ids)}))]
    (render xtdb row {:budget budget})))
