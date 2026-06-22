(ns ygg.system.salience
  "Derive semantic, salience-ranked system connections from raw system edges."
  (:require [ygg.hash :as hash]))

(def relation-weight
  {:code-depends-on 6.0
   :deploys 5.0
   :calls-external-api 4.0
   :calls-http 3.0
   :shares-config 1.0
   :references 0.5})

(def primary-threshold 7.0)
(def secondary-threshold 4.0)
(def supporting-threshold 2.0)

(defn- keywordize
  [value]
  (cond
    (keyword? value) value
    (nil? value) nil
    :else (keyword (str value))))

(defn- node-id
  [node]
  (or (:xt/id node) (:id node)))

(defn- node-kind
  [node]
  (keywordize (:kind node)))

(defn- repo-boundary-edge?
  [node-by-id edge]
  (let [source (get node-by-id (:source-id edge))
        target (get node-by-id (:target-id edge))]
    (or (= :repo-boundary (node-kind source))
        (= :repo-boundary (node-kind target)))))

(defn- relation-counts
  [edges]
  (->> edges
       (map (comp name keywordize :relation))
       frequencies
       (into (sorted-map))))

(defn- evidence-count
  [edges]
  (count (distinct (mapcat :evidence-ids edges))))

(defn- relation-score
  [relations]
  (reduce + 0.0 (map #(get relation-weight % 0.5) relations)))

(defn- score-bonuses
  [edges]
  (let [relations (set (map (comp keywordize :relation) edges))
        evidence-n (evidence-count edges)]
    (cond-> []
      (< 1 (count relations))
      (conj {:reason "multiple relation types"
             :delta 2.0})

      (<= 3 evidence-n)
      (conj {:reason "multiple evidence rows"
             :delta (min 3.0 (double (dec evidence-n)))}))))

(defn- score-penalties
  [node-by-id edges]
  (let [relations (set (map (comp keywordize :relation) edges))]
    (cond-> []
      (every? #(repo-boundary-edge? node-by-id %) edges)
      (conj {:reason "repo-boundary-only connection"
             :delta -3.0})

      (= #{:shares-config} relations)
      (conj {:reason "shared config without runtime/code evidence"
             :delta -2.0}))))

(defn- visibility
  [score]
  (cond
    (<= primary-threshold score) "primary"
    (<= secondary-threshold score) "secondary"
    (<= supporting-threshold score) "supporting"
    :else "noise"))

(defn- dominant-relation
  [edges]
  (->> edges
       (map (comp keywordize :relation))
       frequencies
       (sort-by (fn [[relation n]]
                  [(- (double (get relation-weight relation 0.5)))
                   (- n)
                   (name relation)]))
       ffirst))

(defn- connection-id
  [project-id source-id target-id relations evidence-ids]
  (str "system-connection:"
       (hash/short-hash [project-id source-id target-id relations evidence-ids])))

(defn semantic-connection
  "Return one salience-ranked connection from raw system edges sharing source/target."
  [project-id node-by-id edges]
  (let [source-id (:source-id (first edges))
        target-id (:target-id (first edges))
        relations (->> edges (map (comp keywordize :relation)) distinct sort vec)
        evidence-ids (vec (distinct (mapcat :evidence-ids edges)))
        base (relation-score relations)
        bonuses (score-bonuses edges)
        penalties (score-penalties node-by-id edges)
        adjustments (concat bonuses penalties)
        score (max 0.0 (+ base (reduce + 0.0 (map :delta adjustments))))
        relation (dominant-relation edges)]
    {:xt/id (connection-id project-id source-id target-id relations evidence-ids)
     :project-id project-id
     :source-id source-id
     :target-id target-id
     :relation relation
     :relations relations
     :confidence (apply max (map #(double (:confidence % 0.0)) edges))
     :evidence-ids evidence-ids
     :evidence-counts (relation-counts edges)
     :rules (vec (distinct (mapcat :rules edges)))
     :salience score
     :visibility (visibility score)
     :salience-reasons (mapv (fn [{:keys [reason delta]}]
                               {:reason reason
                                :delta delta})
                             adjustments)
     :active? true}))

(defn semantic-connections
  "Aggregate raw relation-level system edges into semantic connection bundles."
  [project-id nodes edges]
  (let [node-by-id (into {} (map (juxt node-id identity)) nodes)]
    (->> edges
         (filter #(and (:source-id %) (:target-id %) (not= (:source-id %) (:target-id %))))
         (group-by (juxt :source-id :target-id))
         vals
         (mapv #(semantic-connection project-id node-by-id %))
         (sort-by (juxt (comp - double :salience) :source-id :target-id)))))

(def visibility-rank
  {"primary" 0
   "secondary" 1
   "supporting" 2
   "noise" 3})

(defn include-for-detail?
  [detail edge]
  (let [visibility (:visibility edge)]
    (case (keyword (or detail :primary))
      :raw true
      :evidence true
      :expanded (contains? #{"primary" "secondary" "supporting"} visibility)
      :primary (= "primary" visibility)
      (= "primary" visibility))))

(defn filter-by-detail
  "Filter semantic connections for graph detail level."
  [detail edges]
  (->> edges
       (filter #(include-for-detail? detail %))
       (sort-by (juxt (comp visibility-rank :visibility)
                      (comp - double :salience)
                      :source-id
                      :target-id))
       vec))
