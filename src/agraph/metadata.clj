(ns agraph.metadata
  "Graph metadata definitions, rows, and export shaping."
  (:require [agraph.hash :as hash]
            [clojure.string :as str]))

(def default-source
  :user)

(def default-confidence
  1.0)

(defn key-name
  [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)]
                   (str ns "/" (name k))
                   (name k))
    (nil? k) nil
    :else (str k)))

(defn parse-key
  [value]
  (cond
    (keyword? value) value
    (str/includes? (str value) "/") (keyword (str value))
    :else (keyword "user" (str value))))

(defn def-id
  [k]
  (str "meta-def:" (key-name k)))

(defn value-text
  [value]
  (cond
    (nil? value) ""
    (keyword? value) (key-name value)
    :else (str value)))

(defn infer-value-type
  [value]
  (cond
    (keyword? value) :keyword
    (boolean? value) :boolean
    (integer? value) :integer
    (number? value) :number
    (map? value) :edn
    (sequential? value) :edn
    :else :string))

(defn coerce-value
  [value value-type]
  (case (keyword value-type)
    :string (str value)
    :keyword (parse-key value)
    :boolean (if (boolean? value)
               value
               (contains? #{"true" "yes" "1" "on"} (str/lower-case (str value))))
    :integer (long (Long/parseLong (str value)))
    :number (Double/parseDouble (str value))
    :tag (str value)
    :edn value
    :json value
    value))

(def default-definitions
  [{:xt/id (def-id :owner/team)
    :key :owner/team
    :label "Team"
    :description "Owning team or group responsible for the graph target."
    :applies-to [:file :node :edge :chunk :system-node :system-edge]
    :value-type :string
    :cardinality :one
    :queryable? true
    :display? true
    :active? true}
   {:xt/id (def-id :owner/contact)
    :key :owner/contact
    :label "Contact"
    :description "Human or channel to contact for the graph target."
    :applies-to [:file :node :edge :chunk :system-node :system-edge]
    :value-type :string
    :cardinality :one
    :queryable? true
    :display? true
    :active? true}
   {:xt/id (def-id :runtime/service)
    :key :runtime/service
    :label "Runtime service"
    :description "Runtime service or process associated with the target."
    :applies-to [:node :edge :system-node :system-edge]
    :value-type :string
    :cardinality :one
    :queryable? true
    :display? true
    :active? true}
   {:xt/id (def-id :runtime/deploy-target)
    :key :runtime/deploy-target
    :label "Deploy target"
    :description "Deployment target, environment, or platform."
    :applies-to [:node :edge :system-node :system-edge]
    :value-type :string
    :cardinality :many
    :queryable? true
    :display? true
    :active? true}
   {:xt/id (def-id :security/contains-pii?)
    :key :security/contains-pii?
    :label "Contains PII"
    :description "Whether this target is known to handle personal data."
    :applies-to [:file :node :edge :chunk :system-node :system-edge]
    :value-type :boolean
    :cardinality :one
    :queryable? true
    :display? true
    :active? true}
   {:xt/id (def-id :risk/criticality)
    :key :risk/criticality
    :label "Criticality"
    :description "Operational or architectural criticality."
    :applies-to [:node :edge :system-node :system-edge]
    :value-type :string
    :cardinality :one
    :queryable? true
    :display? true
    :active? true}
   {:xt/id (def-id :risk/churn-score)
    :key :risk/churn-score
    :label "Churn score"
    :description "Numeric churn or instability score."
    :applies-to [:file :node :edge :chunk :system-node :system-edge]
    :value-type :number
    :cardinality :one
    :queryable? true
    :display? true
    :metric? true
    :active? true}
   {:xt/id (def-id :agent/summary)
    :key :agent/summary
    :label "Agent summary"
    :description "Agent-authored concise summary."
    :applies-to [:file :node :edge :chunk :system-node :system-edge]
    :value-type :string
    :cardinality :one
    :queryable? true
    :display? true
    :active? true}
   {:xt/id (def-id :agent/needs-review?)
    :key :agent/needs-review?
    :label "Needs review"
    :description "Whether an agent marked this target for human review."
    :applies-to [:file :node :edge :chunk :system-node :system-edge]
    :value-type :boolean
    :cardinality :one
    :queryable? true
    :display? true
    :active? true}
   {:xt/id (def-id :docs/attachment)
    :key :docs/attachment
    :label "Documentation attachment"
    :description "Reference to attached documentation."
    :applies-to [:file :node :edge :chunk :system-node :system-edge]
    :value-type :string
    :cardinality :many
    :queryable? true
    :display? true
    :active? true}])

(def default-definitions-by-key
  (into {} (map (juxt :key identity)) default-definitions))

(defn metadata-id
  [{:keys [target-id key source value]}]
  (str "meta:"
       target-id
       ":"
       (key-name key)
       ":"
       (name (or source default-source))
       ":"
       (hash/short-hash [value])))

(defn row
  [{:keys [project-id repo-id target-id target-kind key value value-type source
           confidence evidence-ids run-id]}]
  (let [key (parse-key key)
        value-type (keyword (or value-type (infer-value-type value)))
        value (coerce-value value value-type)
        source (keyword (or source default-source))
        base (cond-> {:target-id target-id
                      :target-kind (keyword target-kind)
                      :key key
                      :value value
                      :value-type value-type
                      :value-text (value-text value)
                      :source source
                      :confidence (double (or confidence default-confidence))
                      :evidence-ids (vec evidence-ids)
                      :active? true}
               project-id (assoc :project-id project-id)
               repo-id (assoc :repo-id repo-id)
               run-id (assoc :run-id run-id))]
    (assoc base :xt/id (metadata-id base))))

(defn definition-for
  [defs k]
  (or (get defs k)
      (get default-definitions-by-key k)
      {:xt/id (def-id k)
       :key k
       :label (key-name k)
       :applies-to []
       :value-type :string
       :cardinality :many
       :queryable? true
       :display? true
       :active? true}))

(defn export-definition
  [definition]
  (-> definition
      (update :key key-name)
      (update :applies-to #(mapv name %))
      (update :value-type name)
      (update :cardinality name)
      (select-keys [:key :label :description :applies-to :value-type :cardinality
                    :queryable? :display? :metric?])))

(defn tag-row?
  [_definition row]
  (or (= :tag (:value-type row))
      (and (= :boolean (:value-type row))
           (true? (:value row))
           (str/ends-with? (key-name (:key row)) "?"))))

(defn metric-row?
  [definition row]
  (or (:metric? definition)
      (#{:number :integer} (:value-type row))))

(defn tag-value
  [row]
  (if (= :tag (:value-type row))
    (:value-text row)
    (str/replace (key-name (:key row)) #"\?$" "")))

(defn export-target-metadata
  [defs rows]
  (let [defs (or defs {})
        grouped (group-by :key rows)
        entries (map (fn [[k rows]]
                       (let [definition (definition-for defs k)
                             rows (sort-by (juxt :source :value-text) rows)
                             values (mapv :value rows)
                             value (if (= :one (:cardinality definition))
                                     (last values)
                                     values)]
                         [k definition rows value]))
                     grouped)
        attrs (into {}
                    (keep (fn [[k definition rows value]]
                            (when-not (or (some #(tag-row? definition %) rows)
                                          (some #(metric-row? definition %) rows))
                              [(key-name k) value])))
                    entries)
        metrics (into {}
                      (keep (fn [[k definition rows value]]
                              (when (some #(metric-row? definition %) rows)
                                [(key-name k) value])))
                      entries)
        tags (->> entries
                  (mapcat (fn [[_ definition rows _]]
                            (keep #(when (tag-row? definition %) (tag-value %)) rows)))
                  distinct
                  sort
                  vec)]
    (cond-> {}
      (seq attrs) (assoc :attrs attrs)
      (seq tags) (assoc :tags tags)
      (seq metrics) (assoc :metrics metrics))))

(defn metadata-search-text
  [row]
  (str/join " " (remove str/blank? [(key-name (:key row)) (:value-text row)])))
