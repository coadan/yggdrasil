(ns ygg.system.cluster
  "Deterministic cluster discovery for salience-ranked system graphs."
  (:require [clojure.string :as str]))

(defn- node-id
  [node]
  (or (:xt/id node) (:id node)))

(defn- node-kind
  [node]
  (cond
    (keyword? (:kind node)) (:kind node)
    (nil? (:kind node)) nil
    :else (keyword (str (:kind node)))))

(defn- edge-source
  [edge]
  (or (:source-id edge) (:source edge)))

(defn- edge-target
  [edge]
  (or (:target-id edge) (:target edge)))

(defn- edge-weight
  [edge]
  (double (or (:salience edge) 1.0)))

(defn- cluster-edge?
  [edge]
  (contains? #{"primary" "secondary"} (:visibility edge)))

(defn- adjacency
  [edges]
  (reduce (fn [adj edge]
            (let [weight (edge-weight edge)]
              (-> adj
                  (update (edge-source edge) (fnil conj []) [(edge-target edge) weight])
                  (update (edge-target edge) (fnil conj []) [(edge-source edge) weight]))))
          {}
          (filter cluster-edge? edges)))

(defn cluster-labels
  "Return {node-id cluster-id} using deterministic visible-edge connected components."
  [nodes edges]
  (let [node-ids (sort (map node-id nodes))
        node-by-id (into {} (map (juxt node-id identity)) nodes)
        adj (adjacency edges)]
    (loop [remaining (set node-ids)
           labels {}]
      (if-not (seq remaining)
        labels
        (let [seed (first (sort remaining))
              component (loop [frontier #{seed}
                               seen #{}]
                          (if-not (seq frontier)
                            seen
                            (let [next-ids (->> frontier
                                                (mapcat #(map first (get adj %)))
                                                (remove seen)
                                                set)]
                              (recur next-ids (into seen frontier)))))
              cluster-id (or (->> component
                                  sort
                                  (remove #(= :external-api
                                              (node-kind (get node-by-id %))))
                                  first)
                             (first (sort component)))]
          (recur (reduce disj remaining component)
                 (into labels (map (fn [id] [id cluster-id]) component))))))))

(defn- representative-nodes
  [nodes]
  (let [internal (remove #(= :external-api (node-kind %)) nodes)]
    (if (seq internal)
      internal
      nodes)))

(defn- summarize-label
  [nodes]
  (let [labels (->> nodes
                    representative-nodes
                    (keep :label)
                    distinct
                    (take 2))]
    (if (seq labels)
      (str/join " / " labels)
      (or (:label (first nodes)) "cluster"))))

(defn clusters
  "Return stable cluster summaries for nodes and salience-ranked edges."
  [nodes edges]
  (let [labels (cluster-labels nodes edges)
        nodes-by-cluster (->> nodes
                              (group-by #(get labels (node-id %) (node-id %)))
                              (sort-by key))
        bridge-counts (frequencies
                       (keep (fn [edge]
                               (let [source-id (edge-source edge)
                                     target-id (edge-target edge)
                                     source-cluster (get labels source-id source-id)
                                     target-cluster (get labels target-id target-id)]
                                 (when (not= source-cluster target-cluster)
                                   source-cluster)))
                             edges))]
    (mapv (fn [[idx [cluster-id cluster-nodes]]]
            (let [ranked-nodes (->> cluster-nodes
                                    (sort-by (fn [node]
                                               [(- (long (or (:degree node)
                                                             (get-in node [:metrics :node-count])
                                                             0)))
                                                (:label node)]))
                                    vec)]
              {:id (str "cluster:" (inc idx))
               :label (summarize-label ranked-nodes)
               :sourceLabel cluster-id
               :nodeCount (count ranked-nodes)
               :topNodes (mapv #(select-keys % [:id :label :kind :repo :pathPrefix])
                               (take 8 ranked-nodes))
               :bridgeCount (long (get bridge-counts cluster-id 0))}))
          (map-indexed vector nodes-by-cluster))))

(defn annotate-nodes
  "Attach cluster fields to exported graph nodes."
  [nodes edges]
  (let [cluster-label-by-source (into {}
                                      (map (juxt :sourceLabel identity))
                                      (clusters nodes edges))
        labels (cluster-labels nodes edges)]
    (mapv (fn [node]
            (let [cluster (get cluster-label-by-source
                               (get labels (:id node) (:id node)))]
              (cond-> node
                cluster
                (assoc :clusterId (:id cluster)
                       :clusterLabel (:label cluster)
                       :clusterRank (:nodeCount cluster)))))
          nodes)))

(defn annotate-graph
  "Attach cluster summaries and node cluster ids to graph data."
  [data]
  (let [clusters* (clusters (:nodes data) (:edges data))]
    (-> data
        (assoc :clusters clusters*)
        (update :nodes annotate-nodes (:edges data)))))
