(ns ygg.map
  "Editable system-map overlay for agent-maintained graph meaning."
  (:require [ygg.hash :as hash]
            [clojure.string :as str]))

(def schema
  "ygg.map/v2")

(def legacy-schema
  "ygg.map/v1")

(def default-path
  "ygg.map.json")

(defn now-ms
  []
  (System/currentTimeMillis))

(defn empty-map
  "Return an empty editable graph map for project-id."
  [project-id]
  {:schema schema
   :project project-id
   :systems []
   :reject []
   :edges []
   :docs []
   :packageImports []
   :updated-at-ms (now-ms)})

(defn- s
  [value]
  (some-> value str))

(defn- kname
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(defn- numeric
  [value]
  (cond
    (number? value) (double value)
    (nil? value) nil
    :else (Double/parseDouble (str value))))

(defn- path-under?
  [path prefix]
  (and (seq path)
       (seq prefix)
       (or (= path prefix)
           (str/starts-with? path (str prefix "/")))))

(defn- node-path
  [node]
  (or (:pathPrefix node)
      (:path-prefix node)
      (:path node)))

(defn- include-matches-node?
  [include node]
  (and (or (nil? (:repo include))
           (= (s (:repo include)) (s (:repo node))))
       (cond
         (:id include)
         (= (s (:id include)) (s (:id node)))

         (:path include)
         (let [include-path (s (:path include))
               node-path (s (node-path node))]
           (or (path-under? node-path include-path)
               (path-under? include-path node-path)))

         :else false)))

(defn- matching-node-ids
  [nodes system]
  (let [includes (vec (:includes system))
        explicit-id (:id system)]
    (->> nodes
         (filter (fn [node]
                   (or (= (s explicit-id) (s (:id node)))
                       (some #(include-matches-node? % node) includes))))
         (map :id)
         set)))

(defn- first-include-path
  [system]
  (some :path (:includes system)))

(defn- first-include-repo
  [system]
  (some :repo (:includes system)))

(defn- overlay-node
  [system matched-nodes]
  (let [base (or (first matched-nodes) {})
        system-id (s (:id system))]
    (cond-> (merge base
                   {:id system-id
                    :label (or (s (:label system)) system-id)
                    :kind (or (kname (:kind system)) (:kind base) "system")
                    :repo (or (s (:repo system))
                              (s (first-include-repo system))
                              (:repo base))
                    :pathPrefix (or (s (:path-prefix system))
                                    (s (:pathPrefix system))
                                    (s (first-include-path system))
                                    (:pathPrefix base))
                    :source "map-overlay"})
      (:reason system) (assoc :reason (:reason system))
      (:lifecycle system) (assoc :lifecycle (s (:lifecycle system)))
      (:clusterHint system) (assoc :clusterHint (s (:clusterHint system)))
      (:cluster-hint system) (assoc :clusterHint (s (:cluster-hint system)))
      (seq (:tags system)) (assoc :tags (mapv s (:tags system)))
      (seq (:aliases system)) (assoc :aliases (str/join ", " (:aliases system))))))

(defn- match-value?
  [actual expected]
  (or (nil? expected)
      (= (s actual) (s expected))))

(defn- node-matches?
  [node match]
  (let [kind (or (:kind match) (:type match))
        path (or (:path match) (:pathPrefix match) (:path-prefix match))
        host (:host match)]
    (and (match-value? (:id node) (:id match))
         (match-value? (:label node) (:label match))
         (match-value? (:repo node) (:repo match))
         (match-value? (:kind node) kind)
         (match-value? (node-path node) path)
         (or (nil? host)
             (and (= "external-api" (s (:kind node)))
                  (= (s host) (s (:label node))))))))

(defn- rejected-node-ids
  [nodes overlay]
  (let [matches (keep :match (:reject overlay))]
    (->> nodes
         (filter (fn [node]
                   (some #(node-matches? node %) matches)))
         (map :id)
         set)))

(defn- rewrite-edge
  [old->new edge]
  (-> edge
      (update :source #(get old->new % %))
      (update :target #(get old->new % %))))

(defn- overlay-edge
  [edge]
  (let [row (cond-> {:source (:source edge)
                     :target (:target edge)
                     :relation (kname (:relation edge))
                     :confidence (str (or (:confidence edge) 1.0))
                     :rules (or (:rules edge) "map-overlay")
                     :evidence (or (:evidence edge) (:reason edge))}
              (:visibility edge) (assoc :visibility (s (:visibility edge)))
              (:importance edge) (assoc :importance (s (:importance edge)))
              (:salience edge) (assoc :salience (:salience edge))
              (:reason edge) (assoc :reason (:reason edge))
              (seq (:tags edge)) (assoc :tags (mapv s (:tags edge))))]
    (assoc row :id (or (:id edge)
                       (str "map-edge:" (hash/short-hash row))))))

(defn- hidden-edge?
  [edge]
  (contains? #{"hidden" "noise"} (s (:visibility edge))))

(defn- edge-key
  [edge]
  [(s (:source edge)) (s (:target edge)) (s (:relation edge))])

(defn- distinct-by
  [f coll]
  (loop [remaining (seq coll)
         seen #{}
         out []]
    (if-let [item (first remaining)]
      (let [k (f item)]
        (if (contains? seen k)
          (recur (next remaining) seen out)
          (recur (next remaining) (conj seen k) (conj out item))))
      out)))

(defn apply-overlay
  "Apply editable map overlay to canonical ygg.graph/v2 data."
  [graph-data overlay]
  (let [nodes (vec (:nodes graph-data))
        nodes-by-id (into {} (map (juxt :id identity)) nodes)
        systems (vec (:systems overlay))
        matches-by-system (mapv (fn [system]
                                  [system (matching-node-ids nodes system)])
                                systems)
        old->new (into {}
                       (mapcat (fn [[system ids]]
                                 (map (fn [id] [id (s (:id system))]) ids))
                               matches-by-system))
        replaced-ids (set (keys old->new))
        overlay-nodes (mapv (fn [[system ids]]
                              (overlay-node system (keep nodes-by-id ids)))
                            matches-by-system)
        base-nodes (remove #(contains? replaced-ids (:id %)) nodes)
        nodes* (vec (concat base-nodes overlay-nodes))
        rejected (rejected-node-ids nodes* overlay)
        nodes** (vec (remove #(contains? rejected (:id %)) nodes*))
        node-ids (set (map :id nodes**))
        rewritten-edges (->> (:edges graph-data)
                             (map #(rewrite-edge old->new %))
                             (remove #(= (:source %) (:target %)))
                             (remove #(or (contains? rejected (:source %))
                                          (contains? rejected (:target %)))))
        map-edges (map overlay-edge (:edges overlay))
        hidden-edge-keys (set (map edge-key (filter hidden-edge? map-edges)))
        visible-map-edges (remove hidden-edge? map-edges)
        visible-rewritten-edges (remove #(contains? hidden-edge-keys (edge-key %))
                                        rewritten-edges)
        edges* (->> (concat visible-map-edges visible-rewritten-edges)
                    (remove #(or (contains? rejected (:source %))
                                 (contains? rejected (:target %))))
                    (filter #(and (contains? node-ids (:source %))
                                  (contains? node-ids (:target %))))
                    (distinct-by (juxt :source :target :relation))
                    vec)]
    (assoc graph-data
           :nodes nodes**
           :edges edges*
           :map {:schema schema
                 :project (:project overlay)
                 :systems (count (:systems overlay))
                 :rejects (count (:reject overlay))
                 :edges (count (:edges overlay))
                 :docs (count (:docs overlay))
                 :packageImports (count (or (:packageImports overlay)
                                            (:package-imports overlay)))})))

(defn system-entry
  "Return an editable map system candidate from stored system node."
  [node]
  (cond-> {:id (:xt/id node)
           :label (:label node)
           :kind (kname (:kind node))
           :source (kname (:source node))
           :candidateTypes (mapv kname (:candidate-types node))
           :metrics (:metrics node)
           :includes (cond-> []
                       (:path-prefix node)
                       (conj {:repo (:repo-id node)
                              :path (:path-prefix node)}))
           :aliases (:aliases node)
           :status "candidate"
           :provenance "generated-by-ygg"}
    (seq (:evidence node)) (assoc :evidence (:evidence node))
    (:path-prefix node) (assoc :pathPrefix (:path-prefix node))
    (:repo-id node) (assoc :repo (:repo-id node))))

(defn propose-map
  "Return editable candidate map from stored system nodes and edges."
  [project-id systems _edges]
  {:schema schema
   :project project-id
   :systems (->> systems
                 (sort-by (juxt :repo-id :label))
                 (mapv system-entry))
   :reject []
   :edges []
   :docs []
   :packageImports []
   :updated-at-ms (now-ms)})

(defn- generated-candidate-system?
  [system]
  (and (not= "accepted" (s (:status system)))
       (or (= "candidate" (s (:status system)))
           (= "generated-by-ygg" (s (:provenance system))))))

(defn- normalize-include
  [include]
  (cond-> {}
    (:repo include) (assoc :repo (s (:repo include)))
    (:path include) (assoc :path (s (:path include)))
    (:id include) (assoc :id (s (:id include)))))

(defn- normalize-system
  [system]
  (cond-> {:id (s (:id system))
           :label (or (s (:label system)) (s (:id system)))
           :kind (or (kname (:kind system)) "system")
           :includes (mapv normalize-include (:includes system))
           :status "accepted"}
    (:repo system) (assoc :repo (s (:repo system)))
    (or (:pathPrefix system) (:path-prefix system))
    (assoc :pathPrefix (s (or (:pathPrefix system) (:path-prefix system))))
    (seq (:aliases system)) (assoc :aliases (mapv s (:aliases system)))
    (seq (:tags system)) (assoc :tags (mapv s (:tags system)))
    (:lifecycle system) (assoc :lifecycle (s (:lifecycle system)))
    (or (:clusterHint system) (:cluster-hint system))
    (assoc :clusterHint (s (or (:clusterHint system) (:cluster-hint system))))
    (:reason system) (assoc :reason (s (:reason system)))))

(defn- normalize-reject
  [reject]
  (cond-> {:match (:match reject)}
    (:reason reject) (assoc :reason (s (:reason reject)))))

(defn- normalize-edge
  [edge]
  (cond-> {:source (s (:source edge))
           :target (s (:target edge))
           :relation (s (:relation edge))
           :status "accepted"}
    (:id edge) (assoc :id (s (:id edge)))
    (:visibility edge) (assoc :visibility (s (:visibility edge)))
    (:importance edge) (assoc :importance (s (:importance edge)))
    (:confidence edge) (assoc :confidence (numeric (:confidence edge)))
    (:salience edge) (assoc :salience (:salience edge))
    (:rules edge) (assoc :rules (s (:rules edge)))
    (:evidence edge) (assoc :evidence (:evidence edge))
    (:reason edge) (assoc :reason (s (:reason edge)))
    (seq (:tags edge)) (assoc :tags (mapv s (:tags edge)))))

(defn- normalize-doc
  [doc]
  (cond-> {:target (s (:target doc))
           :role (or (s (:role doc)) "reference")
           :source (:source doc)
           :status "accepted"}
    (:reason doc) (assoc :reason (s (:reason doc)))))

(defn- normalize-package-import
  [package-import]
  (cond-> {:import (s (:import package-import))
           :ecosystem (s (:ecosystem package-import))
           :package (s (:package package-import))
           :status "accepted"}
    (:repo package-import) (assoc :repo (s (:repo package-import)))
    (seq (:evidence package-import)) (assoc :evidence (vec (:evidence package-import)))
    (seq (:rules package-import)) (assoc :rules (:rules package-import))
    (seq (:reviewId package-import)) (assoc :reviewId (s (:reviewId package-import)))
    (contains? package-import :confidence) (assoc :confidence (numeric (:confidence package-import)))
    (:reason package-import) (assoc :reason (s (:reason package-import)))))

(defn normalize-map
  "Return a compact ygg.map/v2 overlay containing accepted corrections only."
  [overlay]
  {:schema schema
   :project (:project overlay)
   :systems (->> (:systems overlay)
                 (remove generated-candidate-system?)
                 (mapv normalize-system))
   :reject (mapv normalize-reject (:reject overlay))
   :edges (mapv normalize-edge (:edges overlay))
   :docs (mapv normalize-doc (:docs overlay))
   :packageImports (mapv normalize-package-import
                         (concat (:packageImports overlay)
                                 (:package-imports overlay)))
   :updated-at-ms (or (:updated-at-ms overlay) (now-ms))})

(defn overlay-counts
  [overlay]
  {:systems (count (:systems overlay))
   :rejects (count (:reject overlay))
   :edges (count (:edges overlay))
   :docs (count (:docs overlay))
   :packageImports (count (or (:packageImports overlay)
                              (:package-imports overlay)))})

(defn- find-system-index
  [overlay value]
  (let [value (s value)]
    (first
     (keep-indexed (fn [idx system]
                     (when (or (= value (s (:id system)))
                               (= value (s (:label system))))
                       idx))
                   (:systems overlay)))))

(defn system-by-id-or-label
  [overlay value]
  (when-let [idx (find-system-index overlay value)]
    (nth (:systems overlay) idx)))

(defn- canonical-target
  [overlay target]
  (or (some-> (system-by-id-or-label overlay target) :id s)
      target))

(defn update-system
  [overlay value f]
  (if-let [idx (find-system-index overlay value)]
    (update overlay :systems update idx f)
    (throw (ex-info "System not found in map."
                    {:system value
                     :available (mapv #(select-keys % [:id :label]) (:systems overlay))}))))

(defn set-kind
  [overlay value kind]
  (update-system overlay
                 value
                 #(assoc % :kind (kname kind) :status "accepted")))

(defn add-include
  [overlay value include]
  (update-system overlay
                 value
                 (fn [system]
                   (-> system
                       (update :includes (fnil conj []) include)
                       (assoc :status "accepted")))))

(defn add-reject
  [overlay match reason]
  (update overlay
          :reject
          (fnil conj [])
          (cond-> {:match match}
            (seq reason) (assoc :reason reason))))

(defn add-doc
  "Attach accepted documentation metadata to a graph target."
  [overlay target source {:keys [role heading start-line end-line reason]}]
  (update overlay
          :docs
          (fnil conj [])
          (cond-> {:target (canonical-target overlay target)
                   :role (or role "reference")
                   :source (cond-> source
                             heading (assoc :heading heading)
                             start-line (assoc :startLine start-line)
                             end-line (assoc :endLine end-line))
                   :status "accepted"}
            (seq reason) (assoc :reason reason))))

(defn package-imports
  "Return accepted explicit import-to-package mappings from an overlay."
  [overlay]
  (->> (concat (:packageImports overlay) (:package-imports overlay))
       (filter #(not= "rejected" (s (:status %))))
       vec))

(defn add-package-import
  "Record an accepted source import prefix to external package mapping."
  [overlay {:keys [repo import ecosystem package reason evidence rules reviewId confidence]
            :as package-import}]
  (update overlay
          :packageImports
          (fnil conj [])
          (cond-> {:import import
                   :ecosystem ecosystem
                   :package package
                   :status "accepted"}
            repo (assoc :repo repo)
            (seq evidence) (assoc :evidence (vec evidence))
            (seq rules) (assoc :rules rules)
            (seq reviewId) (assoc :reviewId reviewId)
            (contains? package-import :confidence) (assoc :confidence (numeric confidence))
            (seq reason) (assoc :reason reason))))

(defn add-edge
  "Attach an accepted project-level relationship to the editable map."
  [overlay edge]
  (update overlay :edges (fnil conj []) edge))

(defn docs-for-target
  "Return accepted doc attachments for target id."
  [overlay target]
  (let [target (s target)
        canonical (s (canonical-target overlay target))]
    (->> (:docs overlay)
         (filter (fn [doc]
                   (let [doc-target (s (:target doc))
                         doc-canonical (s (canonical-target overlay doc-target))]
                     (or (= target doc-target)
                         (= canonical doc-target)
                         (= target doc-canonical)
                         (= canonical doc-canonical)))))
         (filter #(not= "rejected" (s (:status %))))
         vec)))
