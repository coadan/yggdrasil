(ns ygg.system-report
  (:require [ygg.command :as command]
            [ygg.dependency :as dependency]
            [ygg.dependency-review :as dependency-review]
            [ygg.hash :as hash]
            [ygg.infra-review :as infra-review]
            [ygg.correction-overlay :as correction-overlay]
            [ygg.system.cluster :as cluster]
            [ygg.system.salience :as salience]
            [ygg.xtdb :as store]
            [clojure.set :as set]))

(defn- system-summary
  [system]
  (select-keys system [:xt/id :label :kind :repo-id :path-prefix :metrics]))
(defn- edge-summary
  [system-by-id edge]
  (assoc (select-keys edge [:xt/id :relation :relations :salience :visibility
                            :evidence-ids :evidence-counts :salience-reasons])
         :source (system-summary (get system-by-id (:source-id edge)))
         :target (system-summary (get system-by-id (:target-id edge)))))
(defn- edge-endpoint-id-set
  [edges]
  (reduce (fn [ids edge]
            (conj ids (:source-id edge) (:target-id edge)))
          #{}
          edges))
(defn- edge-degree-map
  [edges]
  (reduce (fn [degree edge]
            (-> degree
                (update (:source-id edge) (fnil inc 0))
                (update (:target-id edge) (fnil inc 0))))
          {}
          edges))
(defn- stable-row-signature
  [rows keys]
  (->> rows
       (map #(select-keys % keys))
       (sort-by pr-str)
       vec))
(defn- s
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))
(defn- match-value?
  [actual expected]
  (or (nil? expected)
      (= (s actual) (s expected))))
(defn- system-matches-reject?
  [system match]
  (let [kind (or (:kind match) (:type match))
        path (or (:path match) (:pathPrefix match) (:path-prefix match))
        host (:host match)]
    (and (match-value? (:xt/id system) (:id match))
         (match-value? (:label system) (:label match))
         (match-value? (:repo-id system) (:repo match))
         (match-value? (:kind system) kind)
         (match-value? (:path-prefix system) path)
         (or (nil? host)
             (and (= :external-api (:kind system))
                  (= (s host) (s (:label system))))))))
(defn- reject-matches
  [overlay]
  (keep :match (:reject overlay)))
(defn- hidden-edge-overlay?
  [edge]
  (contains? #{"hidden" "noise"} (s (:visibility edge))))
(defn- overlay-edge-key
  [edge]
  [(s (:source edge)) (s (:target edge)) (s (:relation edge))])
(defn- system-edge-key
  [edge]
  [(s (:source-id edge)) (s (:target-id edge)) (s (:relation edge))])
(defn- hidden-edge-keys
  [overlay]
  (->> (:edges overlay)
       (filter hidden-edge-overlay?)
       (map overlay-edge-key)
       set))
(defn- apply-maintenance-overlay
  [systems edges evidence overlay]
  (if-not overlay
    {:systems systems
     :edges edges
     :evidence evidence
     :corrections nil}
    (let [matches (vec (reject-matches overlay))
          hidden-edges (hidden-edge-keys overlay)
          rejected-ids (->> systems
                            (filter (fn [system]
                                      (some #(system-matches-reject? system %) matches)))
                            (map :xt/id)
                            set)]
      {:systems (vec (remove #(contains? rejected-ids (:xt/id %)) systems))
       :edges (vec (remove #(or (contains? rejected-ids (:source-id %))
                                (contains? rejected-ids (:target-id %))
                                (contains? hidden-edges (system-edge-key %)))
                           edges))
       :evidence (vec (remove #(contains? rejected-ids (:system-id %)) evidence))
       :corrections {:schema correction-overlay/schema
                     :project (:project overlay)
                     :rejects (count (:reject overlay))
                     :rejected-systems (count rejected-ids)}})))
(defn- graph-basis
  [project-id systems edges evidence semantic-edges]
  (let [signature {:systems (stable-row-signature systems [:xt/id :run-id :kind])
                   :edges (stable-row-signature edges [:xt/id :run-id :relation])
                   :evidence (stable-row-signature evidence [:xt/id :run-id :kind])
                   :semantic (stable-row-signature semantic-edges
                                                   [:xt/id :visibility :salience])}]
    {:schema "ygg.graph-basis/v1"
     :project-id project-id
     :hash (hash/short-hash signature)
     :counts {:systems (count systems)
              :system-edges (count edges)
              :system-evidence (count evidence)
              :semantic-connections (count semantic-edges)}
     :run-ids (->> systems
                   (keep :run-id)
                   distinct
                   sort
                   vec)}))
(defn- decision-row
  [project-id basis kind severity target reason data
   {:keys [scope evidence-ids recommended-actions]}]
  (let [evidence-ids (vec (distinct evidence-ids))]
    {:schema "ygg.frontier.decision/v1"
     :id (str "maintenance-decision:"
              (hash/short-hash [project-id kind target evidence-ids (:hash basis)]))
     :project-id project-id
     :kind kind
     :status :open
     :severity severity
     :scope (or scope {})
     :target target
     :reason reason
     :evidence-ids evidence-ids
     :recommended-actions (vec recommended-actions)
     :basis basis
     :data data}))
(defn- edge-evidence-ids
  [edge]
  (vec (:evidence-ids edge)))
(defn- ambiguous-edge-decisions
  [project-id basis system-by-id semantic-edges]
  (->> semantic-edges
       (filter #(contains? #{"primary" "secondary"} (:visibility %)))
       (filter (fn [edge]
                 (let [relations (set (:relations edge))
                       noisy-relations (set/intersection relations
                                                         #{:references
                                                           :shares-config})
                       strong-relations (set/intersection relations
                                                          #{:code-depends-on
                                                            :deploys
                                                            :calls-external-api
                                                            :calls-http})]
                   (or (contains? relations :references)
                       (and (seq noisy-relations)
                            (empty? strong-relations))))))
       (mapv (fn [edge]
               (decision-row project-id
                             basis
                             :ambiguous-high-salience-edge
                             :high
                             (:xt/id edge)
                             "Visible connection includes mixed or noisy evidence and may need agent judgment."
                             {:edge (edge-summary system-by-id edge)}
                             {:scope {:target-kind :system-edge}
                              :evidence-ids (edge-evidence-ids edge)
                              :recommended-actions [:set-edge-visibility
                                                    :none]})))))
(defn- orphan-decisions
  [project-id basis orphaned]
  (->> orphaned
       (filter #(pos? (long (get-in % [:metrics :node-count] 0))))
       (mapv (fn [system]
               (decision-row project-id
                             basis
                             :unclustered-system
                             :low
                             (:xt/id system)
                             "System has indexed code but no visible system connections."
                             {:system (system-summary system)}
                             {:scope {:target-kind :system-node}
                              :recommended-actions [:accept-system
                                                    :none]})))))
(def noisy-supporting-relations
  #{:calls-external-api
    :references
    :shares-config})
(def fanout-decision-min-edges
  3)
(def max-fanout-decision-edges
  50)
(def default-max-queued-decisions
  24)
(def default-max-queued-decisions-per-kind
  8)
(def max-maintenance-decisions-per-kind
  100)
(def max-external-api-review-groups
  40)
(defn- external-api-system?
  [system]
  (= :external-api (:kind system)))
(defn- support-or-noise?
  [edge]
  (contains? #{"supporting" "noise"} (:visibility edge)))
(defn- primary-or-secondary?
  [edge]
  (contains? #{"primary" "secondary"} (:visibility edge)))
(defn- noisy-supporting-edge?
  [edge]
  (and (= "supporting" (:visibility edge))
       (seq (set/intersection (set (:relations edge))
                              noisy-supporting-relations))))
(defn- fanout-edge-key
  [edge]
  [(:source-id edge) (:relation edge)])
(defn- fanout-decision-target
  [project-id source-id relation]
  (str "edge-fanout:"
       (hash/short-hash [project-id source-id relation])))
(defn- edge-visibility-patch
  [edge]
  {:source (:source-id edge)
   :target (:target-id edge)
   :relation (name (:relation edge))
   :visibility "noise"})
(defn- external-api-edge-context
  [system-by-id edge]
  (let [source (get system-by-id (:source-id edge))
        target (get system-by-id (:target-id edge))
        source-external? (external-api-system? source)
        target-external? (external-api-system? target)]
    (when (and (not= source-external? target-external?)
               (or source-external? target-external?))
      {:edge edge
       :external (if source-external? source target)
       :peer (if source-external? target source)
       :direction (if source-external? :inbound :outbound)})))
(defn- external-api-reject-patch
  [system]
  {:op "reject-external-api"
   :target (:label system)
   :reason "External API review group accepted this host as non-architectural evidence."})
(defn- external-api-profile
  [edges evidence-count]
  (let [visibilities (set (map :visibility edges))]
    (cond
      (empty? edges) :isolated
      (and (= 1 (count edges)) (= 1 evidence-count)) :single-evidence
      (every? support-or-noise? edges) :support-only
      (contains? visibilities "primary") :primary
      (contains? visibilities "secondary") :secondary
      :else :connected)))
(defn- external-api-target-summaries
  [contexts]
  (->> contexts
       (group-by (comp :xt/id :external))
       (map (fn [[_ rows]]
              (let [system (:external (first rows))
                    edges (map :edge rows)
                    evidence-ids (vec (distinct (mapcat edge-evidence-ids edges)))]
                (assoc (system-summary system)
                       :edge-count (count edges)
                       :evidence-count (count evidence-ids)
                       :relations (->> edges (map :relation) distinct vec)
                       :visibilities (->> edges (map :visibility) distinct vec)))))
       (sort-by (juxt :label :xt/id))
       vec))
(defn- external-api-review-groups
  [project-id system-by-id semantic-edges]
  (let [contexts (->> semantic-edges
                      (keep #(external-api-edge-context system-by-id %))
                      vec)
        by-external (group-by (comp :xt/id :external) contexts)
        targets (external-api-target-summaries contexts)
        target-profiles (mapv (fn [{:keys [evidence-count] :as target}]
                                (let [edges (map :edge (get by-external (:xt/id target)))]
                                  (assoc target
                                         :profile (external-api-profile edges evidence-count))))
                              targets)
        profile-counts (->> target-profiles
                            (group-by :profile)
                            (map (fn [[profile rows]]
                                   {:profile profile
                                    :count (count rows)
                                    :samples (mapv #(select-keys % [:xt/id :label :edge-count
                                                                    :evidence-count])
                                                   (take 8 rows))}))
                            (sort-by (comp name :profile))
                            vec)
        source-fanouts (->> contexts
                            (group-by (fn [{:keys [edge peer direction]}]
                                        [(:xt/id peer)
                                         (:relation edge)
                                         (:visibility edge)
                                         direction]))
                            (keep (fn [[[_ relation visibility direction] rows]]
                                    (let [peer (:peer (first rows))
                                          targets (external-api-target-summaries rows)
                                          edges (map :edge rows)
                                          evidence-ids (vec (distinct (mapcat edge-evidence-ids edges)))
                                          group-id (str "external-api-review:"
                                                        (hash/short-hash
                                                         [project-id
                                                          (:xt/id peer)
                                                          relation
                                                          visibility
                                                          direction]))]
                                      (when (<= fanout-decision-min-edges (count targets))
                                        {:id group-id
                                         :project-id project-id
                                         :peer (system-summary peer)
                                         :direction direction
                                         :relation relation
                                         :visibility visibility
                                         :target-count (count targets)
                                         :edge-count (count edges)
                                         :evidence-count (count evidence-ids)
                                         :evidence-ids evidence-ids
                                         :targets (mapv #(select-keys % [:xt/id :label :edge-count
                                                                         :evidence-count :relations
                                                                         :visibilities])
                                                        (take max-fanout-decision-edges targets))
                                         :edges (mapv #(edge-summary system-by-id %)
                                                      (take max-fanout-decision-edges
                                                            (sort-by :target-id edges)))
                                         :correctionPatch (mapv external-api-reject-patch
                                                                (take max-fanout-decision-edges
                                                                      (keep #(get system-by-id (:xt/id %)) targets)))}))))
                            (sort-by (fn [{:keys [target-count peer relation visibility direction]}]
                                       [(- (long target-count))
                                        (get peer :label)
                                        (name relation)
                                        (str visibility)
                                        (name direction)]))
                            (take max-external-api-review-groups)
                            vec)]
    {:schema "ygg.external-api-review/v1"
     :project-id project-id
     :counts {:nodes (count targets)
              :edges (count contexts)
              :source-fanouts (count source-fanouts)
              :single-evidence-nodes (count (filter #(= :single-evidence (:profile %))
                                                    target-profiles))
              :support-only-nodes (count (filter #(= :support-only (:profile %))
                                                 target-profiles))}
     :profiles profile-counts
     :source-fanouts source-fanouts}))
(defn- external-api-review-group-decisions
  [project-id basis external-api-review]
  (->> (:source-fanouts external-api-review)
       (mapv (fn [{:keys [id target-count evidence-ids] :as group}]
               (decision-row
                project-id
                basis
                :external-api-review-group
                (if (<= 10 target-count) :medium :low)
                id
                "One graph neighborhood has many external API nodes with the same source, relation, direction, and visibility."
                group
                {:scope {:target-kind :external-api-group
                         :source (get-in group [:peer :xt/id])
                         :relation (:relation group)
                         :direction (:direction group)
                         :visibility (:visibility group)}
                 :evidence-ids evidence-ids
                 :recommended-actions [:reject-external-api
                                       :set-edge-visibility
                                       :none]})))))
(defn- covered-external-api-ids
  [external-api-review]
  (->> (:source-fanouts external-api-review)
       (mapcat :targets)
       (map :xt/id)
       set))
(defn- supporting-edge-fanout-decisions
  [project-id basis system-by-id semantic-edges]
  (->> semantic-edges
       (filter noisy-supporting-edge?)
       (group-by fanout-edge-key)
       (keep (fn [[[source-id relation] edges]]
               (let [edges (sort-by :target-id edges)
                     edge-count (count edges)]
                 (when (<= fanout-decision-min-edges edge-count)
                   (decision-row
                    project-id
                    basis
                    :low-confidence-edge-fanout
                    (if (<= 10 edge-count) :medium :low)
                    (fanout-decision-target project-id source-id relation)
                    "One source has many support-level visible connections of the same relation."
                    {:source (system-summary (get system-by-id source-id))
                     :relation relation
                     :edge-count edge-count
                     :edges (mapv #(edge-summary system-by-id %)
                                  (take max-fanout-decision-edges edges))
                     :correctionPatch (mapv edge-visibility-patch edges)}
                    {:scope {:target-kind :system-edge-fanout
                             :source source-id
                             :relation relation}
                     :evidence-ids (mapcat edge-evidence-ids edges)
                     :recommended-actions [:set-edge-visibility
                                           :none]})))))
       (sort-by (fn [decision]
                  [(- (long (get-in decision [:data :edge-count] 0)))
                   (:target decision)]))
       (take max-maintenance-decisions-per-kind)
       vec))
(defn- support-only-external-api-decisions
  [project-id basis system-by-id semantic-edges excluded-system-ids]
  (let [incident (group-by (fn [edge]
                             (if (external-api-system? (get system-by-id (:target-id edge)))
                               (:target-id edge)
                               (:source-id edge)))
                           (filter (fn [edge]
                                     (or (external-api-system?
                                          (get system-by-id (:source-id edge)))
                                         (external-api-system?
                                          (get system-by-id (:target-id edge)))))
                                   semantic-edges))]
    (->> incident
         (keep (fn [[system-id edges]]
                 (let [system (get system-by-id system-id)]
                   (when (and (external-api-system? system)
                              (not (contains? excluded-system-ids (:xt/id system)))
                              (seq edges)
                              (every? support-or-noise? edges)
                              (not-any? primary-or-secondary? edges))
                     (decision-row
                      project-id
                      basis
                      :noisy-external-api
                      :low
                      (:xt/id system)
                      "External API node has only support/noise visible connections and no accepted map correction."
                      {:system (system-summary system)
                       :edge-count (count edges)
                       :edges (mapv #(edge-summary system-by-id %)
                                    (take max-fanout-decision-edges
                                          (sort-by :source-id edges)))}
                      {:scope {:target-kind :system-node
                               :kind :external-api}
                       :evidence-ids (mapcat edge-evidence-ids edges)
                       :recommended-actions [:reject-external-api
                                             :none]})))))
         (sort-by (fn [decision]
                    [(- (long (get-in decision [:data :edge-count] 0)))
                     (:target decision)]))
         (take max-maintenance-decisions-per-kind)
         vec)))
(defn- sparse-external-api-decisions
  [project-id basis system-by-id semantic-edges excluded-system-ids]
  (let [incident (group-by (fn [edge]
                             (if (external-api-system? (get system-by-id (:target-id edge)))
                               (:target-id edge)
                               (:source-id edge)))
                           (filter (fn [edge]
                                     (or (external-api-system?
                                          (get system-by-id (:source-id edge)))
                                         (external-api-system?
                                          (get system-by-id (:target-id edge)))))
                                   semantic-edges))]
    (->> incident
         (keep (fn [[system-id edges]]
                 (let [system (get system-by-id system-id)
                       evidence-ids (vec (distinct (mapcat edge-evidence-ids edges)))]
                   (when (and (external-api-system? system)
                              (not (contains? excluded-system-ids (:xt/id system)))
                              (= 1 (count edges))
                              (= 1 (count evidence-ids))
                              (some primary-or-secondary? edges))
                     (decision-row
                      project-id
                      basis
                      :sparse-external-api
                      :low
                      (:xt/id system)
                      "External API node has exactly one incident connection and one evidence row."
                      {:system (system-summary system)
                       :edge (edge-summary system-by-id (first edges))
                       :edge-count (count edges)
                       :evidence-count (count evidence-ids)}
                      {:scope {:target-kind :system-node
                               :kind :external-api}
                       :evidence-ids evidence-ids
                       :recommended-actions [:reject-external-api
                                             :none]})))))
         (sort-by :target)
         (take max-maintenance-decisions-per-kind)
         vec)))
(defn- cluster-bridge-decisions
  [project-id basis system-by-id semantic-edges clusters]
  (let [cluster-by-source (into {}
                                (map (juxt :sourceLabel :id))
                                clusters)
        cluster-labels (cluster/cluster-labels (vals system-by-id) semantic-edges)]
    (->> semantic-edges
         (filter #(contains? #{"primary" "secondary"} (:visibility %)))
         (keep (fn [edge]
                 (let [source-cluster (get cluster-by-source
                                           (get cluster-labels (:source-id edge)))
                       target-cluster (get cluster-by-source
                                           (get cluster-labels (:target-id edge)))]
                   (when (and source-cluster target-cluster
                              (not= source-cluster target-cluster))
                     (decision-row project-id
                                   basis
                                   :cluster-bridge
                                   :medium
                                   (:xt/id edge)
                                   "Visible connection bridges two discovered clusters."
                                   {:sourceCluster source-cluster
                                    :targetCluster target-cluster
                                    :edge (edge-summary system-by-id edge)}
                                   {:scope {:source-cluster source-cluster
                                            :target-cluster target-cluster
                                            :target-kind :system-edge}
                                    :evidence-ids (edge-evidence-ids edge)
                                    :recommended-actions [:set-edge-visibility
                                                          :none]})))))
         vec)))
(defn- severity-rank
  [severity]
  ({:high 0 :medium 1 :low 2} severity 3))
(defn- non-negative-long
  [value default]
  (let [value (if (nil? value) default value)]
    (max 0 (long value))))
(defn- decision-rank
  [decision]
  [(severity-rank (:severity decision))
   (:kind decision)
   (:target decision)])
(defn- cap-decision-frontier
  [decisions {:keys [max-queued-decisions max-queued-decisions-per-kind]}]
  (let [max-total (non-negative-long max-queued-decisions default-max-queued-decisions)
        max-per-kind (non-negative-long max-queued-decisions-per-kind
                                        default-max-queued-decisions-per-kind)]
    (loop [remaining (sort-by decision-rank decisions)
           by-kind {}
           out []]
      (cond
        (or (empty? remaining) (<= max-total (count out)))
        out

        (zero? max-per-kind)
        out

        :else
        (let [decision (first remaining)
              kind (:kind decision)
              kind-count (long (get by-kind kind 0))]
          (if (< kind-count max-per-kind)
            (recur (rest remaining)
                   (update by-kind kind (fnil inc 0))
                   (conj out decision))
            (recur (rest remaining) by-kind out)))))))
(defn- maintenance-decision-candidates
  [project-id basis system-by-id semantic-edges orphaned clusters external-api-review]
  (let [grouped-external-api-ids (covered-external-api-ids external-api-review)]
    (->> (concat (ambiguous-edge-decisions project-id basis system-by-id semantic-edges)
                 (cluster-bridge-decisions project-id basis system-by-id semantic-edges clusters)
                 (supporting-edge-fanout-decisions project-id basis system-by-id semantic-edges)
                 (external-api-review-group-decisions project-id basis external-api-review)
                 (support-only-external-api-decisions project-id
                                                      basis
                                                      system-by-id
                                                      semantic-edges
                                                      grouped-external-api-ids)
                 (sparse-external-api-decisions project-id
                                                basis
                                                system-by-id
                                                semantic-edges
                                                grouped-external-api-ids)
                 (orphan-decisions project-id basis orphaned))
         (sort-by (fn [decision]
                    [(severity-rank (:severity decision))
                     (:kind decision)
                     (:target decision)]))
         vec)))
(defn- grouped-decision-counts
  [decisions k sort-key]
  (->> decisions
       (group-by k)
       (map (fn [[value rows]]
              {k value
               :count (count rows)}))
       (sort-by sort-key)
       vec))
(defn- grouped-decision-action-counts
  [decisions]
  (->> decisions
       (mapcat :recommended-actions)
       frequencies
       (map (fn [[action n]]
              {:action action
               :count n}))
       (sort-by (fn [row]
                  [(or (some-> (:action row) name) "")]))
       vec))
(defn- decision-preview
  [decision]
  (select-keys decision
               [:id
                :kind
                :severity
                :target
                :reason
                :scope
                :recommended-actions]))
(defn- decision-summary-actions
  [project-id decision-queue]
  (cond-> []
    (seq decision-queue)
    (conj {:kind :pull-work
           :label "Claim next maintenance decision work item"
           :command (str "ygg sync work pull --project "
                         (command/shell-token project-id)
                         " --kind maintenance-decision --agent <agent-id>")}
          {:kind :classify-decision
           :label "Classify the first bounded maintenance decision"
           :target (:id (first decision-queue))
           :command (str "ygg maintenance classify "
                         (command/shell-token (:id (first decision-queue)))
                         " --project "
                         (command/shell-token project-id))})))
(defn decision-queue-summary
  ([project-id decision-queue]
   (decision-queue-summary project-id decision-queue {}))
  ([project-id decision-queue {:keys [candidate-count max-queued-decisions
                                      max-queued-decisions-per-kind]}]
   (cond-> {:total (count decision-queue)
            :bySeverity (grouped-decision-counts decision-queue
                                                 :severity
                                                 (fn [row]
                                                   [(severity-rank (:severity row))
                                                    (or (some-> (:severity row) name)
                                                        "")]))
            :byKind (grouped-decision-counts decision-queue
                                             :kind
                                             (fn [row]
                                               [(or (some-> (:kind row) name)
                                                    "")]))
            :byRecommendedAction (grouped-decision-action-counts decision-queue)
            :nextActions (decision-summary-actions project-id decision-queue)}
     candidate-count
     (assoc :candidates candidate-count
            :omitted (max 0 (- (long candidate-count) (count decision-queue)))
            :limits {:max-queued-decisions (non-negative-long
                                            max-queued-decisions
                                            default-max-queued-decisions)
                     :max-queued-decisions-per-kind (non-negative-long
                                                     max-queued-decisions-per-kind
                                                     default-max-queued-decisions-per-kind)})

     (seq decision-queue)
     (assoc :next (decision-preview (first decision-queue))))))
(defn- ratio
  [numerator denominator]
  (if (pos? denominator)
    (/ (double numerator) (double denominator))
    0.0))
(defn- scale-tier
  [{:keys [systems semantic-connections evidence]}]
  (cond
    (or (>= systems 500)
        (>= semantic-connections 2000)
        (>= evidence 10000)) :large
    (or (>= systems 150)
        (>= semantic-connections 600)
        (>= evidence 3000)) :medium
    :else :small))
(defn- visibility-counts
  [semantic-edges]
  (->> semantic-edges
       (map :visibility)
       frequencies
       (into (sorted-map))))
(defn- relation-counts
  [semantic-edges]
  (->> semantic-edges
       (mapcat :relations)
       frequencies
       (sort-by (comp name key))
       (into (sorted-map))))
(defn- evidence-kind-counts
  [evidence]
  (->> evidence
       (map :kind)
       frequencies
       (sort-by (comp name key))
       (into (sorted-map))))
(defn- top-hubs
  [system-by-id semantic-edges]
  (let [degree (edge-degree-map semantic-edges)]
    (->> degree
         (map (fn [[id n]]
                (assoc (system-summary (get system-by-id id))
                       :degree n)))
         (remove #(nil? (:xt/id %)))
         (sort-by (juxt (comp - long :degree) :repo-id :label))
         (take 10)
         vec)))
(defn- cluster-summary
  [cluster]
  (select-keys cluster [:id :label :sourceLabel :nodeCount]))
(defn- cross-cluster-edges
  [systems system-by-id visible-edges]
  (let [primary-edges (filter #(= "primary" (:visibility %)) visible-edges)
        primary-clusters (cluster/clusters systems primary-edges)
        cluster-by-source (into {}
                                (map (juxt :sourceLabel identity))
                                primary-clusters)
        cluster-labels (cluster/cluster-labels systems primary-edges)]
    (->> visible-edges
         (keep (fn [edge]
                 (let [source-cluster (get cluster-labels (:source-id edge))
                       target-cluster (get cluster-labels (:target-id edge))]
                   (when (and source-cluster
                              target-cluster
                              (not= source-cluster target-cluster))
                     (assoc (edge-summary system-by-id edge)
                            :source-cluster (cluster-summary
                                             (get cluster-by-source source-cluster))
                            :target-cluster (cluster-summary
                                             (get cluster-by-source target-cluster)))))))
         (sort-by (fn [edge]
                    [(- (double (or (:salience edge) 0.0)))
                     (:xt/id edge)]))
         (take 10)
         vec)))
(defn- evidence-concentrations
  [system-by-id evidence]
  (->> evidence
       (group-by (juxt :system-id :kind))
       (map (fn [[[system-id kind] rows]]
              {:system (system-summary (get system-by-id system-id))
               :kind kind
               :count (count rows)}))
       (remove #(nil? (get-in % [:system :xt/id])))
       (sort-by (fn [{:keys [system kind count]}]
                  [(- (long count))
                   (name kind)
                   (get system :repo-id)
                   (get system :label)]))
       (take 10)
       vec))
(defn- graph-health
  [systems system-by-id evidence visible-edges orphaned]
  {:high-degree-hubs (top-hubs system-by-id visible-edges)
   :cross-cluster-edges (cross-cluster-edges systems system-by-id visible-edges)
   :orphaned-candidates (mapv system-summary (take 10 orphaned))
   :evidence-concentrations (evidence-concentrations system-by-id evidence)})
(defn- scale-report
  [systems evidence semantic-edges visible-edges clusters orphaned system-by-id]
  (let [counts {:systems (count systems)
                :evidence (count evidence)
                :semantic-connections (count semantic-edges)
                :visible-connections (count visible-edges)
                :noise-connections (count (filter #(= "noise" (:visibility %))
                                                  semantic-edges))
                :clusters (count clusters)
                :orphaned-systems (count orphaned)}]
    {:tier (scale-tier counts)
     :ratios {:visible (ratio (:visible-connections counts)
                              (:semantic-connections counts))
              :noise (ratio (:noise-connections counts)
                            (:semantic-connections counts))
              :orphaned (ratio (:orphaned-systems counts)
                               (:systems counts))}
     :counts counts
     :visibility-counts (visibility-counts semantic-edges)
     :relation-counts (relation-counts semantic-edges)
     :evidence-kind-counts (evidence-kind-counts evidence)
     :top-hubs (top-hubs system-by-id semantic-edges)}))
(defn- fold-in-action
  [kind command reason]
  {:kind kind
   :command command
   :reason reason})
(defn- fold-in-report
  [project-id scale decision-queue]
  (let [counts (:counts scale)
        noise-ratio (get-in scale [:ratios :noise])
        orphan-ratio (get-in scale [:ratios :orphaned])
        actions (cond-> [(fold-in-action
                          :review-primary-graph
                          (str "ygg view systems --project " project-id
                               " --detail primary")
                          "Start from the compact system view before drilling down.")]
                  (pos? (:orphaned-systems counts))
                  (conj (fold-in-action
                         :review-orphans
                         "ygg sync <project.edn> --check --json --enqueue"
                         "Research orphaned systems discovered during normal work."))

                  (< 0.35 noise-ratio)
                  (conj (fold-in-action
                         :review-noise
                         (str "ygg view systems --project " project-id
                              " --detail evidence --format json")
                         "Inspect noisy evidence before promoting or hiding it."))

                  (< 0.20 orphan-ratio)
                  (conj (fold-in-action
                         :add-missing-connections
                         "ygg corrections include <system> <repo>:<path> --reason <reason>"
                         "Fold researched boundaries into correction facts."))

                  (seq decision-queue)
                  (conj (fold-in-action
                         :classify-one-decision
                         (str "ygg maintenance classify "
                              (:id (first decision-queue))
                              " --project " project-id)
                         "Ask an LLM only about one bounded maintenance decision.")))]
    {:schema "ygg.fold-in/v1"
     :summary "Maintain the graph incrementally while coding; update only researched boundaries."
     :actions actions}))
(defn maintenance-report
  "Return read-only maintenance findings for a project's current system graph."
  [xtdb project-id {:keys [low-confidence-threshold correction-overlay
                           max-queued-decisions max-queued-decisions-per-kind]
                    :or {low-confidence-threshold 0.60}}]
  (let [raw-systems (vec (store/constrained-rows xtdb
                                                 (:system-nodes store/tables)
                                                 {:project-id project-id
                                                  :active? true}))
        raw-edges (vec (store/constrained-rows xtdb
                                               (:system-edges store/tables)
                                               {:project-id project-id
                                                :active? true}))
        raw-evidence (vec (store/constrained-rows xtdb
                                                  (:system-evidence store/tables)
                                                  {:project-id project-id
                                                   :active? true}))
        overlay-result (apply-maintenance-overlay raw-systems raw-edges raw-evidence correction-overlay)
        systems (:systems overlay-result)
        edges (:edges overlay-result)
        evidence (:evidence overlay-result)
        system-ids (set (map :xt/id systems))
        incident-ids (edge-endpoint-id-set edges)
        orphaned (->> systems
                      (remove #(contains? incident-ids (:xt/id %)))
                      (sort-by (juxt :repo-id :label))
                      vec)
        dangling-edges (->> edges
                            (remove #(and (contains? system-ids (:source-id %))
                                          (contains? system-ids (:target-id %))))
                            (sort-by :xt/id)
                            vec)
        missing-evidence-systems (->> evidence
                                      (remove #(contains? system-ids (:system-id %)))
                                      (sort-by (juxt :repo-id :path :source-line))
                                      vec)
        low-confidence-edges (->> edges
                                  (filter #(< (double (:confidence %))
                                              (double low-confidence-threshold)))
                                  (sort-by (juxt :confidence :relation :xt/id))
                                  vec)
        semantic-edges (salience/semantic-connections project-id systems edges)
        visible-edges (remove #(= "noise" (:visibility %)) semantic-edges)
        clusters (cluster/clusters systems visible-edges)
        system-by-id (into {} (map (juxt :xt/id identity)) systems)
        basis (graph-basis project-id systems edges evidence semantic-edges)
        graph-health (graph-health systems
                                   system-by-id
                                   evidence
                                   visible-edges
                                   orphaned)
        external-api-review (external-api-review-groups project-id
                                                        system-by-id
                                                        semantic-edges)
        decision-candidates (maintenance-decision-candidates project-id
                                                             basis
                                                             system-by-id
                                                             semantic-edges
                                                             orphaned
                                                             clusters
                                                             external-api-review)
        decision-queue (cap-decision-frontier decision-candidates
                                              {:max-queued-decisions max-queued-decisions
                                               :max-queued-decisions-per-kind max-queued-decisions-per-kind})
        infra-review-queue (infra-review/review-packets
                            {:project-id project-id
                             :basis basis
                             :systems systems
                             :evidence evidence})
        package-report (dependency/package-report xtdb
                                                  {:project-id project-id}
                                                  {:correction-overlay correction-overlay
                                                   :limit 100})
        dependency-review-queue (dependency-review/review-packets
                                 {:project-id project-id
                                  :basis basis
                                  :package-report package-report})
        scale (scale-report systems
                            evidence
                            semantic-edges
                            visible-edges
                            clusters
                            orphaned
                            system-by-id)]
    {:project-id project-id
     :graph-basis basis
     :corrections (:corrections overlay-result)
     :counts {:systems (count systems)
              :edges (count edges)
              :evidence (count evidence)
              :orphaned-systems (count orphaned)
              :dangling-edges (count dangling-edges)
              :evidence-with-missing-system (count missing-evidence-systems)
              :low-confidence-edges (count low-confidence-edges)
              :semantic-connections (count semantic-edges)
              :visible-connections (count visible-edges)
              :clusters (count clusters)
              :maintenance-decisions (count decision-queue)
              :maintenance-decision-candidates (count decision-candidates)
              :maintenance-decisions-omitted (max 0
                                                  (- (count decision-candidates)
                                                     (count decision-queue)))
              :infra-review-items (count infra-review-queue)
              :dependency-review-items (count dependency-review-queue)}
     :scale scale
     :external-api-review external-api-review
     :graph-health graph-health
     :fold-in (fold-in-report project-id scale decision-queue)
     :decision-summary (decision-queue-summary
                        project-id
                        decision-queue
                        {:candidate-count (count decision-candidates)
                         :max-queued-decisions max-queued-decisions
                         :max-queued-decisions-per-kind max-queued-decisions-per-kind})
     :orphaned-systems orphaned
     :dangling-edges dangling-edges
     :evidence-with-missing-system missing-evidence-systems
     :low-confidence-edges low-confidence-edges
     :semantic-connections semantic-edges
     :clusters clusters
     :decision-queue decision-queue
     :infra-review-queue infra-review-queue
     :dependency-review-queue dependency-review-queue}))
