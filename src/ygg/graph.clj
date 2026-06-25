(ns ygg.graph
  "Build and render graph slices."
  (:require [ygg.corrections :as corrections]
            [ygg.correction-overlay :as correction-overlay]
            [ygg.metadata :as metadata]
            [ygg.query :as query]
            [ygg.system.cluster :as cluster]
            [ygg.system.salience :as salience]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def default-node-limit 300)
(def default-depth 1)

(def schema
  "ygg.graph/v2")

(def kind-color
  {:namespace "#2563eb"
   :application "#2563eb"
   :client "#0ea5e9"
   :external-api "#be123c"
   :infrastructure "#475569"
   :integration "#7c3aed"
   :library "#16a34a"
   :package "#16a34a"
   :external-package "#16a34a"
   :external-package-version "#22c55e"
   :dependency-lock "#64748b"
   :repository "#64748b"
   :service "#dc2626"
   :tool "#f59e0b"
   :var "#16a34a"
   :external "#64748b"
   :function "#16a34a"
   :macro "#9333ea"
   :test "#f59e0b"
   :chunk "#64748b"
   :code-file "#64748b"
   :go-file "#64748b"
   :python-file "#64748b"
   :rust-file "#64748b"
   :doc "#64748b"
   :class "#0d9488"
   :struct "#0d9488"
   :interface "#0d9488"
   :constant "#16a34a"
   :enum "#0d9488"
   :trait "#0d9488"
   :impl "#0f766e"
   :candidate-system "#64748b"
   :repo-boundary "#475569"})

(def relation-color
  {:defines "#94a3b8"
   :imports "#2563eb"
   :imports-package "#2563eb"
   :requires "#2563eb"
   :resolves "#0f766e"
   :version-of "#64748b"
   :uses "#9333ea"
   :declares-module "#f59e0b"
   :calls-http "#dc2626"
   :calls-external-api "#be123c"
   :code-depends-on "#2563eb"
   :deploys "#0f766e"
   :references "#7c3aed"
   :shares-config "#f59e0b"})

(defn- temporal-options
  [opts]
  (merge (:read-context opts)
         (select-keys opts [:valid-at :known-at :snapshot-token :current-time])))

(defn- scope-options
  [opts]
  (cond-> (select-keys opts [:project-id :repo-id])
    (seq (temporal-options opts)) (assoc :read-context (temporal-options opts))))

(defn- node-id
  [node]
  (or (:xt/id node) (:target-id node)))

(defn- edge-source
  [edge]
  (or (:source-id edge) (:source edge)))

(defn- edge-target
  [edge]
  (or (:target-id edge) (:target edge)))

(defn- edge-endpoint-id-set
  [edges]
  (reduce (fn [ids edge]
            (conj ids (edge-source edge) (edge-target edge)))
          #{}
          edges))

(defn- increment-degree
  [degree id]
  (update degree id (fnil inc 0)))

(defn- degree-map
  [edges]
  (reduce (fn [degree edge]
            (-> degree
                (increment-degree (edge-source edge))
                (increment-degree (edge-target edge))))
          {}
          edges))

(defn- value-string
  [value]
  (cond
    (nil? value) nil
    (instance? java.util.Date value) (str (.toInstant ^java.util.Date value))
    :else (str value)))

(defn- portable-value
  [value]
  (if (keyword? value)
    (name value)
    value))

(defn- node-evidence-row
  [row]
  (update-vals row portable-value))

(defn- node-row
  [degree score-by-id node]
  (let [id (node-id node)
        kind (:kind node)
        score (double (get score-by-id id 0.0))]
    (cond-> {:id id
             :label (:label node)
             :kind (name kind)
             :repo (:repo-id node)
             :repoRole (some-> (:repo-role node) name)
             :path (:path node)
             :pathPrefix (:path-prefix node)
             :ecosystem (some-> (:ecosystem node) name)
             :packageName (:package-name node)
             :versionRange (:version-range node)
             :resolvedVersion (:resolved-version node)
             :dependencyScope (:dependency-scope node)
             :importNames (:import-names node)
             :source (some-> (:source node) name)
             :candidateTypes (some->> (:candidate-types node) (mapv name))
             :metrics (:metrics node)
             :line (:source-line node)
             :degree (long (get degree id 0))
             :score score
             :color (get kind-color kind "#334155")
             :size (+ 8
                      (min 22 (* 2 (Math/sqrt (double (max 1 (get degree id 1))))))
                      (* 14 score))}
      (seq (:evidence node)) (assoc :candidateEvidence
                                    (mapv node-evidence-row (:evidence node))))))

(defn- edge-row
  [edge]
  (cond-> {:id (:xt/id edge)
           :source (:source-id edge)
           :target (:target-id edge)
           :relation (name (:relation edge))
           :confidence (some-> (:confidence edge) str)
           :ecosystem (some-> (:ecosystem edge) name)
           :packageName (:package-name edge)
           :versionRange (:version-range edge)
           :resolvedVersion (:resolved-version edge)
           :dependencyScope (:dependency-scope edge)
           :importName (:import-name edge)
           :resolutionSource (some-> (:resolution-source edge) name)
           :rules (some-> (:rules edge) seq (str/join ", "))
           :evidence (some-> (:evidence-ids edge) seq (str/join ", "))
           :path (:path edge)
           :line (:source-line edge)
           :color (get relation-color (:relation edge) "#94a3b8")}
    (:salience edge) (assoc :salience (:salience edge))
    (:visibility edge) (assoc :visibility (:visibility edge))
    (:evidence-counts edge) (assoc :evidenceCounts (:evidence-counts edge))
    (:relations edge) (assoc :relations (mapv name (:relations edge)))
    (:salience-reasons edge) (assoc :salienceReasons (:salience-reasons edge))))

(defn- keywordize
  [value]
  (cond
    (keyword? value) value
    (nil? value) nil
    :else (keyword (str value))))

(defn refresh-presentation
  "Refresh derived presentation hints after renderer-neutral graph transforms."
  [data]
  (let [degree (degree-map (:edges data))]
    (-> data
        (update :nodes
                (fn [nodes]
                  (mapv (fn [node]
                          (let [kind (keywordize (:kind node))
                                score (double (or (:score node) 0.0))
                                degree-value (long (get degree (:id node) 0))]
                            (assoc node
                                   :degree degree-value
                                   :score score
                                   :color (get kind-color kind "#334155")
                                   :size (+ 8
                                            (min 22 (* 2 (Math/sqrt (double (max 1 degree-value)))))
                                            (* 14 score)))))
                        nodes)))
        (update :edges
                (fn [edges]
                  (mapv (fn [edge]
                          (assoc edge
                                 :relation (name (keywordize (:relation edge)))
                                 :color (get relation-color
                                             (keywordize (:relation edge))
                                             "#94a3b8")))
                        edges))))))

(defn- active-nodes
  ([xtdb] (active-nodes xtdb {}))
  ([xtdb opts]
   (query/active-nodes xtdb opts)))

(defn- active-edges
  ([xtdb] (active-edges xtdb {}))
  ([xtdb opts]
   (query/active-edges xtdb opts)))

(defn- stub-node
  [id]
  {:xt/id id
   :label (query/display-id id)
   :kind :external
   :active? true})

(defn- nodes-for-ids
  [ids nodes-by-id limit]
  (->> ids
       (map #(or (get nodes-by-id %) (stub-node %)))
       (take limit)
       vec))

(defn- induced-edges
  [node-ids edges]
  (let [ids (set node-ids)]
    (filter #(and (contains? ids (:source-id %))
                  (contains? ids (:target-id %)))
            edges)))

(defn- scope-constraints
  [scope]
  (select-keys scope [:project-id :repo-id]))

(defn- distinct-active-rows-by-id
  [rows]
  (loop [remaining (seq rows)
         seen #{}
         out []]
    (if-let [row (first remaining)]
      (let [id (:xt/id row)]
        (if (and (:active? row)
                 (not (contains? seen id)))
          (recur (next remaining) (conj seen id) (conj out row))
          (recur (next remaining) seen out)))
      out)))

(defn- active-edge-rows-touching-ids
  [xtdb ids scope]
  (distinct-active-rows-by-id
   (store/edge-rows-touching-ids xtdb
                                 ids
                                 (scope-constraints scope)
                                 (store/read-context (:read-context scope)))))

(defn- expand-node-ids-bounded
  [xtdb seed-ids depth scope]
  (loop [frontier (set seed-ids)
         seen (set seed-ids)
         remaining depth]
    (if (or (zero? remaining) (empty? frontier))
      seen
      (let [edges (active-edge-rows-touching-ids xtdb frontier scope)
            next-ids (edge-endpoint-id-set edges)]
        (recur (set/difference next-ids seen)
               (into seen next-ids)
               (dec remaining))))))

(defn- active-items-by-ids
  [xtdb ids scope]
  (let [opts (merge (scope-constraints scope)
                    (select-keys scope [:read-context]))
        node-rows (filter :active? (query/nodes-by-ids xtdb ids opts))
        chunk-rows (filter :active? (query/chunks-by-ids xtdb ids opts))]
    (into {}
          (map (juxt :xt/id identity))
          (concat node-rows chunk-rows))))

(def ^:private package-edge-row-query-fields
  [:xt/id
   :project-id
   :repo-id
   :source-id
   :target-id
   :relation
   :confidence
   :ecosystem
   :package-name
   :version-range
   :resolved-version
   :dependency-scope
   :import-name
   :import-kind
   :resolution-source
   :source-kind
   :file-id
   :path
   :source-line
   :active?
   :run-id])

(defn- active-edge-rows-targeting-ids
  [xtdb ids scope]
  (let [ids (into [] (comp (remove nil?) (distinct)) ids)]
    (distinct-active-rows-by-id
     (store/rows-with-field-values xtdb
                                   {:table (:edges store/tables)
                                    :field :target-id
                                    :values ids
                                    :constraints (scope-constraints scope)
                                    :return-fields package-edge-row-query-fields
                                    :read-context (store/read-context (:read-context scope))}))))

(defn- package-deps-edges-bounded
  [xtdb package-id scope]
  (let [package-target-edges (active-edge-rows-targeting-ids xtdb [package-id] scope)
        version-edges (filter #(= :version-of (:relation %)) package-target-edges)
        version-ids (set (map :source-id version-edges))
        version-target-edges (active-edge-rows-targeting-ids xtdb version-ids scope)]
    (vec
     (concat
      (filter #(= :requires (:relation %)) package-target-edges)
      (filter #(= :imports-package (:relation %)) package-target-edges)
      version-edges
      (filter #(= :resolves (:relation %)) version-target-edges)))))

(defn- package-deps-node-ids
  [package-id edges]
  (->> edges
       (mapcat (juxt :source-id :target-id))
       (cons package-id)
       distinct
       vec))

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

(defn- graph-data
  [title nodes edges score-by-id opts]
  (let [degree (degree-map edges)
        node-ids (set (map node-id nodes))
        temporal (temporal-options opts)]
    {:title title
     :schema schema
     :basis (cond-> (select-keys opts [:project-id :repo-id])
              (:detail opts) (assoc :detail (name (:detail opts)))
              (:valid-at temporal) (assoc :validAt (value-string (:valid-at temporal)))
              (:known-at temporal) (assoc :knownAt (value-string (:known-at temporal)))
              (:snapshot-token temporal) (assoc :snapshotToken (:snapshot-token temporal)))
     :nodes (->> nodes
                 (filter #(contains? node-ids (node-id %)))
                 (mapv #(node-row degree score-by-id %)))
     :edges (mapv edge-row edges)}))

(defn- metadata-read-context
  [opts]
  (merge (scope-options opts)
         (select-keys opts [:project-id :repo-id])))

(defn- target-ids
  [data]
  (concat (map :id (:nodes data))
          (map :id (:edges data))))

(defn- used-metadata-defs
  [defs rows]
  (->> rows
       (map :key)
       distinct
       (map #(metadata/definition-for defs %))
       (sort-by (comp metadata/key-name :key))
       (mapv metadata/export-definition)))

(defn- enrich-item
  [defs rows-by-target item]
  (merge item
         (metadata/export-target-metadata defs (get rows-by-target (:id item)))))

(defn- attr-value
  [item k]
  (get-in item [:attrs (metadata/key-name k)]))

(defn- metric-value
  [item k]
  (get-in item [:metrics (metadata/key-name k)]))

(defn- matches-metadata-filter?
  [item filters]
  (every? (fn [[k expected]]
            (let [actual (or (attr-value item k)
                             (metric-value item k)
                             (when (some #{(metadata/key-name k)} (:tags item)) true))]
              (= (str expected) (str actual))))
          filters))

(defn- apply-view
  [data view]
  (if-not view
    data
    (let [node-meta (get-in view [:node-filter :metadata])
          edge-meta (get-in view [:edge-filter :metadata])
          relations (set (map name (get-in view [:edge-filter :relations])))
          display (mapv metadata/key-name (:display view))
          rank-by (mapv metadata/key-name (:rank-by view))
          nodes (cond->> (:nodes data)
                  (seq node-meta) (filter #(matches-metadata-filter? % node-meta)))
          node-ids (set (map :id nodes))
          edges (cond->> (:edges data)
                  (seq edge-meta) (filter #(matches-metadata-filter? % edge-meta))
                  (seq relations) (filter #(contains? relations (:relation %)))
                  true (filter #(and (contains? node-ids (:source %))
                                     (contains? node-ids (:target %)))))]
      (-> data
          (assoc :nodes (vec nodes)
                 :edges (vec edges)
                 :view (select-keys view [:xt/id :label :description]))
          (cond-> (seq display) (assoc-in [:view :display] display)
                  (seq rank-by) (assoc-in [:view :rankBy] rank-by))))))

(defn enrich-graph
  "Attach metadata rows and metadata definitions to graph data."
  [xtdb data opts]
  (let [ctx (metadata-read-context opts)
        ids (set (target-ids data))
        rows (store/metadata-for-targets xtdb ids ctx)
        defs (store/metadata-defs xtdb ctx)
        rows-by-target (group-by :target-id rows)
        view (when-let [view-id (:view-id opts)]
               (store/graph-view xtdb view-id ctx))]
    (-> data
        (assoc :metadataDefs (used-metadata-defs defs rows))
        (update :nodes #(mapv (partial enrich-item defs rows-by-target) %))
        (update :edges #(mapv (partial enrich-item defs rows-by-target) %))
        (apply-view view)
        refresh-presentation)))

(defn overview-graph
  "Return an overview graph slice."
  [xtdb {:keys [limit] :as opts :or {limit default-node-limit}}]
  (let [scope (scope-options opts)
        nodes (active-nodes xtdb scope)
        edges (active-edges xtdb scope)
        degree (degree-map edges)
        chosen (->> nodes
                    (sort-by (fn [node] [(- (get degree (:xt/id node) 0))
                                         (:label node)]))
                    (take limit)
                    vec)
        chosen-ids (mapv :xt/id chosen)
        chosen-edges (induced-edges chosen-ids edges)]
    (enrich-graph xtdb
                  (graph-data "Yggdrasil Overview" chosen chosen-edges {} opts)
                  opts)))

(defn deps-graph
  "Return graph slice around dependency target."
  [xtdb value {:keys [depth limit]
               :as opts
               :or {depth default-depth limit default-node-limit}}]
  (let [scope (scope-options opts)
        target (query/find-node xtdb value scope)
        package-target? (= :external-package (:kind target))]
    (if package-target?
      (let [chosen-edges (package-deps-edges-bounded xtdb (:xt/id target) scope)
            node-ids (package-deps-node-ids (:xt/id target) chosen-edges)
            nodes-by-id (active-items-by-ids xtdb node-ids scope)
            nodes (nodes-for-ids node-ids nodes-by-id limit)
            chosen-ids (mapv :xt/id nodes)]
        (enrich-graph xtdb
                      (graph-data (str "Dependencies: " (or (:label target) value))
                                  nodes
                                  (induced-edges chosen-ids chosen-edges)
                                  {(:xt/id target) 1.0}
                                  opts)
                      opts))
      (let [target-id (:xt/id target)
            node-ids (if target-id
                       (expand-node-ids-bounded xtdb [target-id] depth scope)
                       #{})
            nodes-by-id (active-items-by-ids xtdb node-ids scope)
            nodes (nodes-for-ids node-ids nodes-by-id limit)
            chosen-ids (mapv :xt/id nodes)
            edges (active-edge-rows-touching-ids xtdb chosen-ids scope)]
        (enrich-graph xtdb
                      (graph-data (str "Dependencies: " (or (:label target) value))
                                  nodes
                                  (induced-edges chosen-ids edges)
                                  (if target-id {target-id 1.0} {})
                                  opts)
                      opts)))))

(defn query-graph
  "Return graph slice around query results."
  [xtdb query-text {:keys [depth limit embedding-client retriever project-id repo-id]
                    :as opts
                    :or {depth default-depth limit 40 retriever :auto}}]
  (let [scope (scope-options opts)
        hits (query/semantic-query xtdb query-text
                                   {:limit limit
                                    :retriever retriever
                                    :embedding-client embedding-client
                                    :project-id project-id
                                    :repo-id repo-id
                                    :read-context (:read-context scope)})
        score-by-id (into {} (map (juxt :target-id :score)) hits)
        seed-ids (mapv :target-id hits)
        node-ids (expand-node-ids-bounded xtdb seed-ids depth scope)
        nodes-by-id (active-items-by-ids xtdb node-ids scope)
        nodes (nodes-for-ids node-ids nodes-by-id default-node-limit)
        chosen-ids (mapv :xt/id nodes)
        edges (active-edge-rows-touching-ids xtdb chosen-ids scope)]
    (enrich-graph xtdb
                  (graph-data (str "Query: " query-text)
                              nodes
                              (induced-edges chosen-ids edges)
                              score-by-id
                              opts)
                  opts)))

(def ^:private system-graph-node-row-fields
  [:xt/id
   :project-id
   :repo-id
   :system-key
   :label
   :kind
   :path
   :path-prefix
   :source
   :candidate-types
   :evidence
   :metrics
   :repo-role
   :aliases
   :active?
   :run-id])

(defn- active-system-nodes
  [xtdb project-id opts]
  (store/ordered-rows xtdb
                      {:table (:system-nodes store/tables)
                       :constraints {:project-id project-id
                                     :active? true}
                       :return-fields system-graph-node-row-fields
                       :read-context (store/read-context opts)}))

(def ^:private system-graph-edge-row-fields
  [:xt/id
   :project-id
   :source-id
   :target-id
   :relation
   :confidence
   :evidence-ids
   :rules
   :active?
   :run-id])

(defn- active-system-edges
  [xtdb project-id min-confidence opts]
  (store/rows-with-min-field-value
   xtdb
   {:table (:system-edges store/tables)
    :field :confidence
    :min-value (double min-confidence)
    :constraints {:project-id project-id
                  :active? true}
    :return-fields system-graph-edge-row-fields
    :read-context (store/read-context opts)}))

(defn system-graph
  "Return project-level system graph."
  [xtdb project-id {:keys [correction-overlay min-confidence limit valid-at known-at
                           snapshot-token current-time read-context view-id detail]
                    :or {min-confidence 0.55
                         limit default-node-limit
                         detail :primary}}]
  (let [read-context (merge read-context
                            (select-keys {:valid-at valid-at
                                          :known-at known-at
                                          :snapshot-token snapshot-token
                                          :current-time current-time}
                                         [:valid-at :known-at :snapshot-token :current-time]))
        detail (keyword detail)
        nodes (vec (active-system-nodes xtdb project-id read-context))
        raw-edges (vec (active-system-edges xtdb
                                            project-id
                                            min-confidence
                                            read-context))
        edges (if (= :raw detail)
                raw-edges
                (salience/filter-by-detail
                 detail
                 (salience/semantic-connections project-id nodes raw-edges)))
        degree (degree-map edges)
        incident-node-ids (edge-endpoint-id-set edges)
        chosen (let [ranked (->> nodes
                                 (filter #(or (= :raw detail)
                                              (contains? incident-node-ids (:xt/id %))))
                                 (sort-by (fn [node] [(- (get degree (:xt/id node) 0))
                                                      (:repo-id node)
                                                      (:label node)]))
                                 vec)]
                 (->> (if (seq ranked)
                        ranked
                        (sort-by (fn [node] [(:repo-id node) (:label node)]) nodes))
                      (take limit)
                      vec))
        chosen-ids (mapv :xt/id chosen)
        data (graph-data (str "Systems: " project-id)
                         chosen
                         (induced-edges chosen-ids edges)
                         {}
                         {:project-id project-id
                          :read-context read-context
                          :view-id view-id
                          :detail detail})
        data (cond
               correction-overlay (correction-overlay/apply-overlay data correction-overlay)
               :else (correction-overlay/apply-overlay data
                                              (corrections/overlay xtdb project-id)))]
    (enrich-graph xtdb
                  (if (= :raw detail)
                    data
                    (cluster/annotate-graph data))
                  {:project-id project-id
                   :read-context read-context
                   :view-id view-id})))

(defn- neighborhood-edges
  [edges visible-ids edge-limit]
  (let [visible (set visible-ids)]
    (->> edges
         (filter #(and (contains? visible (:source-id %))
                       (contains? visible (:target-id %))))
         (take edge-limit)
         vec)))

(defn system-neighborhood
  "Return a bounded system graph slice for explicit focus/frontier ids."
  [xtdb project-id {:keys [focus-ids frontier-ids correction-overlay min-confidence
                           edge-limit valid-at known-at snapshot-token current-time
                           read-context view-id detail]
                    :or {min-confidence 0.55
                         edge-limit default-node-limit
                         detail :expanded}}]
  (let [read-context (merge read-context
                            (select-keys {:valid-at valid-at
                                          :known-at known-at
                                          :snapshot-token snapshot-token
                                          :current-time current-time}
                                         [:valid-at :known-at :snapshot-token :current-time]))
        detail (keyword detail)
        visible-ids (ordered-distinct focus-ids frontier-ids)
        opts {:project-id project-id
              :min-confidence min-confidence
              :read-context read-context}
        nodes (vec (query/system-nodes-by-ids xtdb visible-ids opts))
        visible-id-set (set visible-ids)
        raw-edges (->> (query/system-edges-touching-ids xtdb visible-ids opts)
                       (filter #(<= (double min-confidence)
                                    (double (:confidence %))))
                       (filter #(and (contains? visible-id-set (:source-id %))
                                     (contains? visible-id-set (:target-id %))))
                       vec)
        edges (if (= :raw detail)
                raw-edges
                (salience/filter-by-detail
                 detail
                 (salience/semantic-connections project-id nodes raw-edges)))
        chosen-edges (neighborhood-edges edges visible-ids edge-limit)
        data (graph-data (str "Systems: " project-id)
                         nodes
                         chosen-edges
                         {}
                         {:project-id project-id
                          :read-context read-context
                          :view-id view-id
                          :detail detail})
        data (cond
               correction-overlay (correction-overlay/apply-overlay data correction-overlay)
               :else (correction-overlay/apply-overlay data
                                              (corrections/overlay xtdb project-id)))]
    (enrich-graph xtdb
                  (if (= :raw detail)
                    data
                    (cluster/annotate-graph data))
                  {:project-id project-id
                   :read-context read-context
                   :view-id view-id})))

(defn cluster-graph
  "Return a single discovered system cluster graph."
  [xtdb project-id cluster-id opts]
  (let [data (system-graph xtdb project-id opts)
        matching-cluster (some #(when (or (= cluster-id (:id %))
                                          (= cluster-id (:label %))
                                          (= cluster-id (:sourceLabel %)))
                                  %)
                               (:clusters data))
        cluster-id* (:id matching-cluster)
        nodes (if cluster-id*
                (filter #(= cluster-id* (:clusterId %)) (:nodes data))
                [])
        node-ids (set (map :id nodes))
        edges (filter #(and (contains? node-ids (:source %))
                            (contains? node-ids (:target %)))
                      (:edges data))]
    (-> data
        (assoc :title (str "Cluster: " (or (:label matching-cluster) cluster-id))
               :nodes (vec nodes)
               :edges (vec edges)
               :clusters (cond-> []
                           matching-cluster (conj matching-cluster)))
        refresh-presentation)))

(defn canonical-json
  "Return canonical ygg.graph/v2 JSON."
  [data]
  (json/write-json-str data))

(defn write-canonical!
  "Write canonical ygg.graph/v2 JSON."
  [path data]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file (canonical-json data))
    (.getPath file)))
