(ns agraph.benchmark-expectations
  (:require [agraph.xtdb :as store]))

(def graph-expectations-schema
  "agraph.benchmark.graph-expectations/v1")

(def ^:private expectation-match-keys
  #{:xt/id
    :kind
    :path
    :file-kind
    :url-context
    :auth-context
    :label
    :normalized-value
    :source-line
    :end-line
    :definition-kind
    :content-sha
    :repo-id
    :system-id
    :relation
    :source-id
    :target-id})
(def ^:private expectation-key-aliases
  {:id :xt/id
   :fileKind :file-kind
   :file-kind :file-kind
   :urlContext :url-context
   :url-context :url-context
   :authContext :auth-context
   :auth-context :auth-context
   :normalizedValue :normalized-value
   :normalized-value :normalized-value
   :sourceLine :source-line
   :source-line :source-line
   :endLine :end-line
   :end-line :end-line
   :definitionKind :definition-kind
   :definition-kind :definition-kind
   :contentSha :content-sha
   :content-sha :content-sha
   :repoId :repo-id
   :repo-id :repo-id
   :systemId :system-id
   :system-id :system-id
   :sourceId :source-id
   :source-id :source-id
   :targetId :target-id
   :target-id :target-id})
(defn- normalize-expectation-key
  [k]
  (or (get expectation-key-aliases k)
      (get expectation-key-aliases (keyword (name k)))
      k))
(defn- normalize-match-value
  [value]
  (cond
    (keyword? value) (name value)
    (symbol? value) (name value)
    (number? value) value
    :else (str value)))
(defn- normalize-expectation-map
  [expectation]
  (let [expectation (if (and (map? expectation) (:match expectation))
                      (:match expectation)
                      expectation)]
    (cond
      (map? expectation)
      (->> expectation
           (map (fn [[k v]]
                  [(normalize-expectation-key k) v]))
           (filter (fn [[k v]]
                     (and (contains? expectation-match-keys k)
                          (some? v))))
           (into (sorted-map)))

      (or (keyword? expectation) (string? expectation) (symbol? expectation))
      {:relation expectation}

      :else {})))
(defn- row-matches-expectation?
  [row expectation]
  (and (seq expectation)
       (every? (fn [[k expected]]
                 (= (normalize-match-value expected)
                    (normalize-match-value (get row k))))
               expectation)))
(defn- evidence-match-summary
  [row]
  (select-keys row [:xt/id
                    :kind
                    :path
                    :file-kind
                    :url-context
                    :auth-context
                    :label
                    :normalized-value
                    :source-line
                    :system-id]))
(defn- node-match-summary
  [row]
  (select-keys row [:xt/id
                    :kind
                    :path
                    :file-kind
                    :label
                    :source-line]))
(defn- chunk-match-summary
  [row]
  (select-keys row [:xt/id
                    :kind
                    :path
                    :file-kind
                    :definition-kind
                    :label
                    :source-line
                    :end-line
                    :content-sha]))
(defn- edge-match-summary
  [row]
  (select-keys row [:xt/id
                    :relation
                    :source-id
                    :target-id
                    :confidence
                    :evidence-ids]))
(defn expected-row-results
  [rows expectations summarize]
  (mapv (fn [expectation]
          (let [normalized (normalize-expectation-map expectation)
                matches (filter #(row-matches-expectation? % normalized) rows)]
            {:expectation normalized
             :found? (boolean (seq matches))
             :matchCount (count matches)
             :matches (mapv summarize (take 5 matches))}))
        expectations))
(defn forbidden-row-results
  [rows expectations summarize]
  (mapv (fn [expectation]
          (let [normalized (normalize-expectation-map expectation)
                matches (filter #(row-matches-expectation? % normalized) rows)]
            {:expectation normalized
             :violated? (boolean (seq matches))
             :matchCount (count matches)
             :matches (mapv summarize (take 5 matches))}))
        expectations))
(defn- graph-expectation-status
  [expected-evidence expected-nodes expected-chunks expected-edges forbidden-nodes forbidden-chunks forbidden-edges]
  (if (or (some (complement :found?) expected-evidence)
          (some (complement :found?) expected-nodes)
          (some (complement :found?) expected-chunks)
          (some (complement :found?) expected-edges)
          (some :violated? forbidden-nodes)
          (some :violated? forbidden-chunks)
          (some :violated? forbidden-edges))
    "failed"
    "passed"))
(defn- graph-expectation-summary
  [expected-evidence expected-nodes expected-chunks expected-edges forbidden-nodes forbidden-chunks forbidden-edges]
  {:expectedEvidence (count expected-evidence)
   :foundEvidence (count (filter :found? expected-evidence))
   :missingEvidence (count (remove :found? expected-evidence))
   :expectedNodes (count expected-nodes)
   :foundNodes (count (filter :found? expected-nodes))
   :missingNodes (count (remove :found? expected-nodes))
   :expectedChunks (count expected-chunks)
   :foundChunks (count (filter :found? expected-chunks))
   :missingChunks (count (remove :found? expected-chunks))
   :expectedEdges (count expected-edges)
   :foundEdges (count (filter :found? expected-edges))
   :missingEdges (count (remove :found? expected-edges))
   :forbiddenNodes (count forbidden-nodes)
   :forbiddenNodeViolations (count (filter :violated? forbidden-nodes))
   :forbiddenChunks (count forbidden-chunks)
   :forbiddenChunkViolations (count (filter :violated? forbidden-chunks))
   :forbiddenEdges (count forbidden-edges)
   :forbiddenEdgeViolations (count (filter :violated? forbidden-edges))})
(defn evaluate-graph-expectations
  [xtdb prepared]
  (let [expectations (:expectations prepared)
        evidence-expectations (vec (:evidence expectations))
        node-expectations (vec (:nodes expectations))
        chunk-expectations (vec (:chunks expectations))
        edge-expectations (vec (:edges expectations))
        forbidden-node-expectations (vec (:forbidden-nodes expectations))
        forbidden-chunk-expectations (vec (:forbidden-chunks expectations))
        forbidden-edge-expectations (vec (:forbidden-edges expectations))]
    (when (or (seq evidence-expectations)
              (seq node-expectations)
              (seq chunk-expectations)
              (seq edge-expectations)
              (seq forbidden-node-expectations)
              (seq forbidden-chunk-expectations)
              (seq forbidden-edge-expectations))
      (let [evidence (store/rows-by-field xtdb
                                          (:system-evidence store/tables)
                                          :project-id
                                          (:project-id prepared))
            nodes (store/rows-by-field xtdb
                                       (:nodes store/tables)
                                       :project-id
                                       (:project-id prepared))
            chunks (store/rows-by-field xtdb
                                        (:chunks store/tables)
                                        :project-id
                                        (:project-id prepared))
            edges (store/rows-by-field xtdb
                                       (:system-edges store/tables)
                                       :project-id
                                       (:project-id prepared))
            expected-evidence (expected-row-results evidence
                                                    evidence-expectations
                                                    evidence-match-summary)
            expected-nodes (expected-row-results nodes
                                                 node-expectations
                                                 node-match-summary)
            expected-chunks (expected-row-results chunks
                                                  chunk-expectations
                                                  chunk-match-summary)
            expected-edges (expected-row-results edges
                                                 edge-expectations
                                                 edge-match-summary)
            forbidden-nodes (forbidden-row-results nodes
                                                   forbidden-node-expectations
                                                   node-match-summary)
            forbidden-chunks (forbidden-row-results chunks
                                                    forbidden-chunk-expectations
                                                    chunk-match-summary)
            forbidden-edges (forbidden-row-results edges
                                                   forbidden-edge-expectations
                                                   edge-match-summary)]
        {:schema graph-expectations-schema
         :status (graph-expectation-status expected-evidence
                                           expected-nodes
                                           expected-chunks
                                           expected-edges
                                           forbidden-nodes
                                           forbidden-chunks
                                           forbidden-edges)
         :summary (graph-expectation-summary expected-evidence
                                             expected-nodes
                                             expected-chunks
                                             expected-edges
                                             forbidden-nodes
                                             forbidden-chunks
                                             forbidden-edges)
         :expectedEvidence expected-evidence
         :expectedNodes expected-nodes
         :expectedChunks expected-chunks
         :expectedEdges expected-edges
         :forbiddenNodes forbidden-nodes
         :forbiddenChunks forbidden-chunks
         :forbiddenEdges forbidden-edges}))))
