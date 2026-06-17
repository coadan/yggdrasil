(ns agraph.graph
  "Build and render graph slices."
  (:require [agraph.map :as graph-map]
            [agraph.metadata :as metadata]
            [agraph.query :as query]
            [agraph.system.cluster :as cluster]
            [agraph.system.salience :as salience]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def default-node-limit 300)
(def default-depth 1)

(def schema
  "agraph.graph/v2")

(def kind-color
  {:namespace "#2563eb"
   :application "#2563eb"
   :client "#0ea5e9"
   :external-api "#be123c"
   :infrastructure "#475569"
   :integration "#7c3aed"
   :library "#16a34a"
   :package "#16a34a"
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
   :requires "#2563eb"
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

(defn- degree-map
  [edges]
  (frequencies (mapcat (juxt edge-source edge-target) edges)))

(defn- value-string
  [value]
  (cond
    (nil? value) nil
    (instance? java.util.Date value) (str (.toInstant ^java.util.Date value))
    :else (str value)))

(defn- node-row
  [degree score-by-id node]
  (let [id (node-id node)
        kind (:kind node)
        score (double (get score-by-id id 0.0))]
    {:id id
     :label (:label node)
     :kind (name kind)
     :repo (:repo-id node)
     :repoRole (some-> (:repo-role node) name)
     :path (:path node)
     :pathPrefix (:path-prefix node)
     :source (some-> (:source node) name)
     :candidateTypes (some->> (:candidate-types node) (mapv name))
     :metrics (:metrics node)
     :line (:source-line node)
     :degree (long (get degree id 0))
     :score score
     :color (get kind-color kind "#334155")
     :size (+ 8 (min 22 (* 2 (Math/sqrt (double (max 1 (get degree id 1)))))) (* 14 score))}))

(defn- edge-row
  [edge]
  (cond-> {:id (:xt/id edge)
           :source (:source-id edge)
           :target (:target-id edge)
           :relation (name (:relation edge))
           :confidence (some-> (:confidence edge) str)
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
   (filter :active? (query/all-nodes xtdb opts))))

(defn- active-edges
  ([xtdb] (active-edges xtdb {}))
  ([xtdb opts]
   (filter :active? (query/all-edges xtdb opts))))

(defn- active-chunks
  ([xtdb] (active-chunks xtdb {}))
  ([xtdb opts]
   (filter :active? (query/all-chunks xtdb opts))))

(defn- active-items
  ([xtdb] (active-items xtdb {}))
  ([xtdb opts]
   (concat (active-nodes xtdb opts) (active-chunks xtdb opts))))

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

(defn- expand-node-ids
  [edges seed-ids depth]
  (loop [frontier (set seed-ids)
         seen (set seed-ids)
         remaining depth]
    (if (zero? remaining)
      seen
      (let [next-ids (->> edges
                          (filter #(or (contains? frontier (:source-id %))
                                       (contains? frontier (:target-id %))))
                          (mapcat (juxt :source-id :target-id))
                          set)]
        (recur (set/difference next-ids seen)
               (into seen next-ids)
               (dec remaining))))))

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

(defn- metadata-text
  [item]
  (str/join " "
            (remove str/blank?
                    [(some-> (:attrs item) pr-str)
                     (when (seq (:tags item)) (str/join " " (:tags item)))
                     (some-> (:metrics item) pr-str)])))

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
                  (graph-data "AGraph Overview" chosen chosen-edges {} opts)
                  opts)))

(defn deps-graph
  "Return graph slice around dependency target."
  [xtdb value {:keys [depth limit]
               :as opts
               :or {depth default-depth limit default-node-limit}}]
  (let [scope (scope-options opts)
        target (query/find-node xtdb value scope)
        edges (active-edges xtdb scope)
        node-ids (if target
                   (expand-node-ids edges [(:xt/id target)] depth)
                   #{})
        nodes-by-id (into {} (map (juxt :xt/id identity)) (active-items xtdb scope))
        nodes (nodes-for-ids node-ids nodes-by-id limit)
        chosen-ids (mapv :xt/id nodes)]
    (enrich-graph xtdb
                  (graph-data (str "Dependencies: " (or (:label target) value))
                              nodes
                              (induced-edges chosen-ids edges)
                              {(:xt/id target) 1.0}
                              opts)
                  opts)))

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
        edges (active-edges xtdb scope)
        score-by-id (into {} (map (juxt :target-id :score)) hits)
        seed-ids (mapv :target-id hits)
        node-ids (expand-node-ids edges seed-ids depth)
        nodes-by-id (into {} (map (juxt :xt/id identity)) (active-items xtdb scope))
        nodes (nodes-for-ids node-ids nodes-by-id default-node-limit)
        chosen-ids (mapv :xt/id nodes)]
    (enrich-graph xtdb
                  (graph-data (str "Query: " query-text)
                              nodes
                              (induced-edges chosen-ids edges)
                              score-by-id
                              opts)
                  opts)))

(defn- active-system-nodes
  [xtdb project-id opts]
  (->> (store/rows-by-field xtdb
                            (:system-nodes store/tables)
                            :project-id
                            project-id
                            (store/read-context opts))
       (filter :active?)))

(defn- active-system-edges
  [xtdb project-id min-confidence opts]
  (->> (store/rows-by-field xtdb
                            (:system-edges store/tables)
                            :project-id
                            project-id
                            (store/read-context opts))
       (filter :active?)
       (filter #(<= (double min-confidence) (double (:confidence %))))))

(defn system-graph
  "Return project-level system graph."
  [xtdb project-id {:keys [map-path map-overlay min-confidence limit valid-at known-at
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
        incident-node-ids (set (mapcat (juxt edge-source edge-target) edges))
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
               map-overlay (graph-map/apply-overlay data map-overlay)
               map-path (graph-map/apply-file data map-path)
               :else data)]
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

(defn- escape-html
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- js-value
  [value]
  (json/write-json-str value))

(defn- cytoscape-js
  []
  (if-let [resource (io/resource "agraph/vendor/cytoscape.min.js")]
    (slurp resource)
    ""))

(defn- cytoscape-elements
  "Return Cytoscape.js element rows for graph data."
  [data]
  (let [repos (->> (:nodes data)
                   (keep :repo)
                   distinct
                   sort)
        repo-nodes (mapv (fn [repo]
                           {:data {:id (str "repo:" repo)
                                   :label repo
                                   :kind "repo"}})
                         repos)
        graph-nodes (mapv (fn [node]
                            {:data (cond-> (select-keys node [:id :label :kind :repo :path
                                                              :pathPrefix :line :degree
                                                              :source :candidateTypes :metrics
                                                              :score :color :size
                                                              :attrs :tags
                                                              :clusterId :clusterLabel
                                                              :clusterRank :lifecycle
                                                              :clusterHint])
                                     (seq (metadata-text node)) (assoc :metadata (metadata-text node))
                                     (:repo node) (assoc :parent (str "repo:" (:repo node))))})
                          (:nodes data))
        graph-edges (mapv (fn [edge]
                            {:data (select-keys edge [:id :source :target :relation :confidence
                                                      :rules :evidence :path :line :color
                                                      :attrs :tags :metrics
                                                      :salience :visibility
                                                      :evidenceCounts :relations
                                                      :salienceReasons
                                                      :importance :reason])})
                          (:edges data))]
    (vec (concat repo-nodes graph-nodes graph-edges))))

(defn- cytoscape-html
  "Return self-contained Cytoscape.js graph viewer HTML."
  [data]
  (let [elements (cytoscape-elements data)]
    (str "<!doctype html>
<html>
<head>
<meta charset=\"utf-8\">
<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">
<title>" (escape-html (:title data)) "</title>
<style>
:root{color-scheme:light}
body{margin:0;font:13px system-ui,-apple-system,Segoe UI,sans-serif;color:#0f172a;background:#f8fafc}
#bar{min-height:52px;display:flex;align-items:center;gap:10px;padding:8px 12px;border-bottom:1px solid #e2e8f0;background:white;box-sizing:border-box}
#bar strong{white-space:nowrap}
#q{width:260px;max-width:32vw;padding:7px 9px;border:1px solid #cbd5e1;border-radius:6px}
select,button{height:32px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#0f172a}
button{min-width:34px;padding:0 10px;cursor:pointer}
button:hover{background:#f1f5f9}
#controls{display:flex;gap:6px;margin-left:auto;align-items:center}
#wrap{display:grid;grid-template-columns:1fr 360px;height:calc(100vh - 53px)}
#cy{width:100%;height:100%;background:#f8fafc}
#panel{border-left:1px solid #e2e8f0;background:white;padding:14px;overflow:auto}
#panel h2{font-size:16px;line-height:1.25;margin:0 0 8px}
.muted{color:#64748b}.pill{display:inline-block;padding:2px 6px;border-radius:999px;background:#e2e8f0;margin:0 4px 4px 0}
.kv{margin:12px 0}.kv span{display:block;color:#64748b;font-size:11px;text-transform:uppercase;letter-spacing:.04em}
.kv code{white-space:pre-wrap;word-break:break-word}
@media(max-width:800px){#wrap{grid-template-columns:1fr}#panel{display:none}#q{max-width:42vw}}
</style>
</head>
<body>
<div id=\"bar\"><strong>" (escape-html (:title data)) "</strong><span class=\"muted\" id=\"count\"></span><input id=\"q\" placeholder=\"Filter\"><select id=\"kind\"></select><select id=\"relation\"></select><select id=\"cluster\"></select><div id=\"controls\"><select id=\"layout\"><option value=\"cose\">cose</option><option value=\"breadthfirst\">breadthfirst</option><option value=\"concentric\">concentric</option><option value=\"grid\">grid</option><option value=\"circle\">circle</option></select><button id=\"fit\">Fit</button><button id=\"reset\">Layout</button></div></div>
<div id=\"wrap\"><div id=\"cy\"></div><aside id=\"panel\"><div class=\"muted\">Click a node or edge.</div></aside></div>
<script>
" (cytoscape-js) "
</script>
<script>
const graph = " (js-value {:title (:title data) :elements elements}) ";
const cy = cytoscape({
  container: document.getElementById('cy'),
  elements: graph.elements,
  wheelSensitivity: 0.18,
  minZoom: 0.05,
  maxZoom: 4,
  style: [
    {selector:'node',style:{'background-color':'data(color)','label':'data(label)','font-size':10,'color':'#0f172a','text-outline-width':2,'text-outline-color':'#f8fafc','text-valign':'center','text-halign':'right','width':'data(size)','height':'data(size)','border-width':1.5,'border-color':'#fff'}},
    {selector:'node[kind = \"repo\"]',style:{'shape':'round-rectangle','background-color':'#e2e8f0','background-opacity':0.35,'border-color':'#cbd5e1','border-width':1,'label':'data(label)','text-valign':'top','text-halign':'center','font-weight':600,'padding':'14px'}},
    {selector:'edge',style:{'curve-style':'bezier','target-arrow-shape':'triangle','target-arrow-color':'data(color)','line-color':'data(color)','width':1.4,'opacity':0.7,'label':'data(relation)','font-size':8,'text-rotation':'autorotate','text-background-color':'#f8fafc','text-background-opacity':0.85,'text-background-padding':2}},
    {selector:':selected',style:{'border-width':4,'border-color':'#020617','line-color':'#020617','target-arrow-color':'#020617','opacity':1}},
    {selector:'.faded',style:{'display':'none'}}
  ]
});
const panel = document.getElementById('panel');
const q = document.getElementById('q');
const kind = document.getElementById('kind');
const relation = document.getElementById('relation');
const cluster = document.getElementById('cluster');
const layoutSelect = document.getElementById('layout');
const count = document.getElementById('count');
function esc(s){return String(s??'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]));}
function detailRows(obj){return Object.entries(obj).filter(([k,v])=>v!==null&&v!==undefined&&v!==''&&k!=='color'&&k!=='parent').map(([k,v])=>`<div class=\"kv\"><span>${esc(k)}</span><code>${esc(v)}</code></div>`).join('');}
function options(select,label,values){select.innerHTML=`<option value=\"\">${label}</option>`+Array.from(values).sort().map(v=>`<option value=\"${esc(v)}\">${esc(v)}</option>`).join('');}
options(kind,'All kinds',new Set(cy.nodes().map(n=>n.data('kind')).filter(Boolean)));
options(relation,'All relations',new Set(cy.edges().map(e=>e.data('relation')).filter(Boolean)));
options(cluster,'All clusters',new Set(cy.nodes().map(n=>n.data('clusterLabel')).filter(Boolean)));
function runLayout(){cy.layout({name:layoutSelect.value,animate:false,fit:true,padding:48,nodeDimensionsIncludeLabels:true}).run();}
function visibleNode(n){
  if(n.data('kind')==='repo') return true;
  const needle=q.value.trim().toLowerCase();
  const kindValue=kind.value;
  const clusterValue=cluster.value;
  const text=[n.data('label'),n.data('kind'),n.data('repo'),n.data('path'),n.data('pathPrefix'),n.data('metadata'),JSON.stringify(n.data('attrs')||{}),JSON.stringify(n.data('tags')||[]),JSON.stringify(n.data('metrics')||{})].join(' ').toLowerCase();
  return (!needle||text.includes(needle))&&(!kindValue||n.data('kind')===kindValue)&&(!clusterValue||n.data('clusterLabel')===clusterValue);
}
function visibleEdge(e){
  const rel=relation.value;
  const needle=q.value.trim().toLowerCase();
  const text=[e.data('relation'),e.data('source'),e.data('target'),e.data('path'),e.data('rules'),e.data('evidence'),JSON.stringify(e.data('attrs')||{}),JSON.stringify(e.data('tags')||[]),JSON.stringify(e.data('metrics')||{})].join(' ').toLowerCase();
  return (!rel||e.data('relation')===rel)&&(!needle||text.includes(needle)||visibleNode(e.source())||visibleNode(e.target()));
}
function applyFilters(){
  cy.elements().removeClass('faded');
  cy.nodes().forEach(n=>{if(n.data('kind')!=='repo'&&!visibleNode(n)) n.addClass('faded');});
  cy.edges().forEach(e=>{if(!visibleEdge(e)||e.source().hasClass('faded')||e.target().hasClass('faded')) e.addClass('faded');});
  cy.nodes('[kind = \"repo\"]').forEach(repo=>{const visibleChildren=repo.children().filter(n=>!n.hasClass('faded')); if(!visibleChildren.length) repo.addClass('faded');});
  const nodes=cy.nodes().filter(n=>!n.hasClass('faded')&&n.data('kind')!=='repo').length;
  const edges=cy.edges().filter(e=>!e.hasClass('faded')).length;
  count.textContent=`${nodes} nodes, ${edges} edges`;
}
cy.on('tap','node',evt=>{const d=evt.target.data(); panel.innerHTML=`<h2>${esc(d.label)}</h2><span class=\"pill\">${esc(d.kind)}</span>${detailRows(d)}`;});
cy.on('tap','edge',evt=>{const d=evt.target.data(); panel.innerHTML=`<h2>${esc(d.relation)}</h2>${detailRows(d)}`;});
q.addEventListener('input',applyFilters);
kind.addEventListener('change',applyFilters);
relation.addEventListener('change',applyFilters);
cluster.addEventListener('change',applyFilters);
layoutSelect.addEventListener('change',runLayout);
document.getElementById('fit').onclick=()=>cy.fit(cy.elements().not('.faded'),48);
document.getElementById('reset').onclick=runLayout;
runLayout();
applyFilters();
</script>
</body>
</html>")))

(defn html
  "Return graph viewer HTML."
  [data]
  (cytoscape-html data))

(defn canonical-json
  "Return canonical agraph.graph/v2 JSON."
  [data]
  (json/write-json-str data))

(defn write-canonical!
  "Write canonical agraph.graph/v2 JSON."
  [path data]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file (canonical-json data))
    (.getPath file)))

(defn write-html!
  "Write graph viewer HTML."
  [path data]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file (html data))
    (.getPath file)))
