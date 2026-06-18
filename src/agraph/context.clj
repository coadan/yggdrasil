(ns agraph.context
  "Token-bounded, graph-grounded context packets for agents."
  (:require [agraph.activity :as activity]
            [agraph.graph :as graph]
            [agraph.map :as graph-map]
            [agraph.query :as query]
            [agraph.text :as text]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.string :as str]))

(def schema
  "agraph.context/v1")

(def default-budget
  4000)

(def default-entity-limit
  8)

(def default-edge-limit
  12)

(def default-doc-limit
  6)

(def default-snippet-chars
  900)

(def default-retrieval-limit
  40)

(def unsupported-planes
  [:remote-work :session-history])

(def role-weight
  {"overview" 0.35
   "contract" 0.35
   "runbook" 0.30
   "troubleshooting" 0.30
   "rationale" 0.20
   "warning" 0.20
   "reference" 0.10
   "example" 0.05})

(defn estimate-tokens
  "Return a cheap token estimate for value."
  [value]
  (long (Math/ceil (/ (count (json/write-json-str value)) 4.0))))

(defn- s
  [value]
  (some-> value str))

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

(defn- compact
  [& parts]
  (->> parts
       flatten
       (remove nil?)
       (map str)
       (remove str/blank?)
       (str/join " ")))

(defn- token-score
  [query-tokens value]
  (text/token-score query-tokens (text/tokenize value)))

(defn- capped-token-score
  [query-tokens value]
  (min 1.0 (token-score query-tokens value)))

(defn- result-matches-node?
  [result node]
  (or (= (:target-id result) (:id node))
      (and (:path result)
           (node-path node)
           (path-under? (:path result) (node-path node))
           (or (nil? (:repo node))
               (nil? (:repo-id result))
               (= (:repo node) (:repo-id result))))))

(defn- entity-score
  [query-tokens results node]
  (let [result-score (->> results
                          (filter #(result-matches-node? % node))
                          (map :score)
                          (reduce max 0.0))
        lexical-score (token-score query-tokens
                                   (compact (:label node)
                                            (:kind node)
                                            (:repo node)
                                            (:path node)
                                            (:pathPrefix node)
                                            (pr-str (:attrs node))
                                            (str/join " " (:tags node))
                                            (pr-str (:metrics node))))]
    (+ result-score (* 0.25 lexical-score))))

(defn- select-entities
  [query-tokens results graph-data limit]
  (->> (:nodes graph-data)
       (remove #(= "repo" (:kind %)))
       (map #(assoc % :context-score (entity-score query-tokens results %)))
       (filter #(pos? (:context-score %)))
       (sort-by (juxt (comp - :context-score) :label))
       (take limit)
       (mapv (fn [node]
               (cond-> {:id (:id node)
                        :label (:label node)
                        :kind (:kind node)
                        :repo (:repo node)
                        :path (:path node)
                        :pathPrefix (:pathPrefix node)
                        :clusterId (:clusterId node)
                        :clusterLabel (:clusterLabel node)
                        :score (double (:context-score node))
                        :why (if (some #(result-matches-node? % node) results)
                               "retrieval and graph match"
                               "graph label match")}
                 (:attrs node) (assoc :attrs (:attrs node))
                 (seq (:tags node)) (assoc :tags (:tags node))
                 (:metrics node) (assoc :metrics (:metrics node)))))))

(defn- edge-score
  [query-tokens selected-ids edge]
  (+ (cond
       (and (contains? selected-ids (:source edge))
            (contains? selected-ids (:target edge))) 1.0
       (or (contains? selected-ids (:source edge))
           (contains? selected-ids (:target edge))) 0.5
       :else 0.0)
     (* 0.15 (token-score query-tokens (:relation edge)))))

(defn- select-edges
  [query-tokens entities graph-data limit]
  (let [selected-ids (set (map :id entities))]
    (->> (:edges graph-data)
         (map #(assoc % :context-score (edge-score query-tokens selected-ids %)))
         (filter #(pos? (:context-score %)))
         (sort-by (juxt (comp - :context-score) :relation :source :target))
         (take limit)
         (mapv (fn [edge]
                 (cond-> {:id (:id edge)
                          :source (:source edge)
                          :target (:target edge)
                          :relation (:relation edge)
                          :confidence (:confidence edge)
                          :salience (:salience edge)
                          :visibility (:visibility edge)
                          :score (double (:context-score edge))}
                   (:attrs edge) (assoc :attrs (:attrs edge))
                   (seq (:tags edge)) (assoc :tags (:tags edge))
                   (:metrics edge) (assoc :metrics (:metrics edge))))))))

(defn- truncate
  [value n]
  (let [value (str value)]
    (if (<= (count value) n)
      value
      (str (subs value 0 (max 0 (- n 12))) "\n[truncated]"))))

(defn- lines
  [chunk]
  (when (:source-line chunk)
    (cond-> [(long (:source-line chunk))]
      (:end-line chunk) (conj (long (:end-line chunk))))))

(defn- chunk-source
  [chunk]
  {:repo (:repo-id chunk)
   :path (:path chunk)
   :kind (:kind chunk)
   :definitionKind (:definition-kind chunk)
   :heading (:label chunk)
   :headingPath (:heading-path chunk)
   :lines (lines chunk)
   :contentSha (:content-sha chunk)})

(defn- source-line
  [source]
  (or (:startLine source)
      (:start-line source)))

(defn- source-end-line
  [source]
  (or (:endLine source)
      (:end-line source)))

(defn- source-heading-path
  [source]
  (or (:headingPath source)
      (:heading-path source)))

(defn- source-matches-chunk?
  [source chunk]
  (and (= (s (:repo source)) (s (:repo-id chunk)))
       (= (s (:path source)) (s (:path chunk)))
       (or (nil? (:heading source))
           (= (s (:heading source)) (s (:label chunk))))
       (or (nil? (source-heading-path source))
           (= (mapv s (source-heading-path source))
              (mapv s (:heading-path chunk))))
       (or (nil? (source-line source))
           (= (long (source-line source)) (long (:source-line chunk))))))

(defn- find-source-chunk
  [chunks source]
  (first (filter #(source-matches-chunk? source %) chunks)))

(defn- attachment->doc
  [chunks snippet-chars attachment]
  (let [source (:source attachment)
        chunk (find-source-chunk chunks source)
        content-sha (or (:contentSha source) (:content-sha source))
        stale? (or (nil? chunk)
                   (and content-sha
                        (:content-sha chunk)
                        (not= (s content-sha) (s (:content-sha chunk))))
                   (and (source-end-line source)
                        (:end-line chunk)
                        (not= (long (source-end-line source))
                              (long (:end-line chunk)))))]
    (cond-> {:target (:target attachment)
             :role (or (:role attachment) "reference")
             :status (if stale? "stale" "accepted")
             :source (if chunk (chunk-source chunk) source)
             :score (+ 2.0 (get role-weight (s (:role attachment)) 0.0))
             :provenance "map-attachment"}
      (:reason attachment) (assoc :reason (:reason attachment))
      chunk (assoc :snippet (truncate (:text chunk) snippet-chars))
      (nil? chunk) (assoc :warning "attached doc source not found"))))

(defn- result-score-by-target
  [results]
  (into {} (map (juxt :target-id :score)) results))

(defn- result-score-by-path-label
  [results]
  (reduce (fn [scores {:keys [path label score]}]
            (if (and (not (str/blank? (str path)))
                     (not (str/blank? (str label))))
              (update scores [path label] #(max (double (or % 0.0))
                                                (double score)))
              scores))
          {}
          results))

(defn- result-score-for-chunk
  [result-scores result-scores-by-path-label chunk]
  (or (get result-scores (:xt/id chunk))
      (get result-scores-by-path-label [(:path chunk) (:label chunk)])))

(defn- result-by-target
  [results]
  (into {} (map (juxt :target-id identity)) results))

(defn- chunk-score
  [query-tokens selected-labels result-scores result-scores-by-path-label chunk]
  (+ (double (or (result-score-for-chunk result-scores
                                         result-scores-by-path-label
                                         chunk)
                 0.0))
     (* 0.35 (capped-token-score query-tokens
                                 (compact (:label chunk) (:path chunk) (:text chunk))))
     (* 0.15 (min 1.0
                  (text/token-score (text/tokenize (str/join " " selected-labels))
                                    (:tokens chunk))))))

(defn- inferred-docs
  [query-tokens results chunks entities snippet-chars]
  (let [result-scores (result-score-by-target results)
        result-scores-by-path-label (result-score-by-path-label results)
        results-by-target (result-by-target results)
        selected-labels (map :label entities)]
    (->> chunks
         (filter #(or (= :markdown (:kind %))
                      (result-score-for-chunk result-scores
                                              result-scores-by-path-label
                                              %)))
         (map #(assoc % :context-score (chunk-score query-tokens
                                                    selected-labels
                                                    result-scores
                                                    result-scores-by-path-label
                                                    %)
                      :retrieved? (boolean (result-score-for-chunk
                                            result-scores
                                            result-scores-by-path-label
                                            %))
                      :exact-path? (>= (double (get-in results-by-target
                                                       [(:xt/id %) :score-components :exact]
                                                       0.0))
                                       2.0)))
         (filter #(pos? (:context-score %)))
         (sort-by (juxt #(cond
                           (and (:exact-path? %)
                                (not= :markdown (:kind %))) 0
                           (and (:retrieved? %)
                                (not= :markdown (:kind %))) 1
                           :else 2)
                        (comp - :context-score)
                        :path
                        :source-line))
         (mapv (fn [chunk]
                 (cond-> {:target (:xt/id chunk)
                          :role "reference"
                          :status "candidate"
                          :source (chunk-source chunk)
                          :score (double (:context-score chunk))
                          :snippet (truncate (:text chunk) snippet-chars)
                          :provenance "retrieved-doc"}
                   (and (:retrieved? chunk)
                        (not= :markdown (:kind chunk)))
                   (assoc :retrievedSource true)

                   (and (:exact-path? chunk)
                        (not= :markdown (:kind chunk)))
                   (assoc :exactPathSource true)))))))

(defn- distinct-docs
  [docs]
  (loop [remaining (seq docs)
         seen #{}
         out []]
    (if-let [doc (first remaining)]
      (let [k [(get-in doc [:source :repo])
               (get-in doc [:source :path])
               (get-in doc [:source :lines])]]
        (if (contains? seen k)
          (recur (next remaining) seen out)
          (recur (next remaining) (conj seen k) (conj out doc))))
      out)))

(defn- selected-targets
  [entities edges]
  (set (concat (map :id entities)
               (map :label entities)
               (map :id edges))))

(defn- doc-priority
  [doc]
  (cond
    (= "map-attachment" (:provenance doc)) 0
    (:exactPathSource doc) 1
    (:retrievedSource doc) 2
    :else 3))

(defn- doc-diversity-key
  [doc]
  (let [source (:source doc)]
    [(doc-priority doc)
     (or (:repo source) :unknown-repo)
     (or (:path source)
         (:heading source)
         (get-in doc [:source :definitionKind])
         (:target doc)
         :other)]))

(defn- diversify-docs
  [docs]
  (let [{:keys [firsts rest]}
        (reduce (fn [{:keys [seen] :as acc} doc]
                  (let [k (doc-diversity-key doc)]
                    (if (contains? seen k)
                      (update acc :rest conj doc)
                      (-> acc
                          (update :seen conj k)
                          (update :firsts conj doc)))))
                {:seen #{}
                 :firsts []
                 :rest []}
                docs)]
    (into firsts rest)))

(defn- attached-docs
  [overlay chunks snippet-chars targets]
  (->> (:docs overlay)
       (filter #(contains? targets (:target %)))
       (filter #(not= "rejected" (s (:status %))))
       (map #(attachment->doc chunks snippet-chars %))
       vec))

(defn- graph-summary
  [graph-data]
  {:basis (:basis graph-data)
   :counts {:nodes (count (:nodes graph-data))
            :edges (count (:edges graph-data))
            :clusters (count (:clusters graph-data))}
   :defaultDetail "primary"})

(defn- base-packet
  [query-text budget graph-data entities edges activity warnings drilldowns answerability]
  (cond-> {:schema schema
           :query query-text
           :graph (graph-summary graph-data)
           :budget {:requested budget}
           :entities entities
           :edges edges
           :activity activity
           :docs []
           :warnings warnings
           :drilldowns drilldowns}
    answerability (assoc :answerability answerability)))

(defn- add-doc-with-budget
  [packet doc budget]
  (let [with-snippet (update packet :docs conj doc)]
    (if (<= (estimate-tokens with-snippet) budget)
      with-snippet
      (let [ref-doc (-> doc
                        (dissoc :snippet)
                        (assoc :snippetOmitted true))
            with-ref (update packet :docs conj ref-doc)]
        (if (<= (estimate-tokens with-ref) budget)
          (update with-ref :warnings conj "snippet omitted to fit context budget")
          (update packet :warnings conj "doc omitted to fit context budget"))))))

(defn- fit-budget
  [packet docs budget]
  (let [packet (reduce #(add-doc-with-budget %1 %2 budget) packet docs)
        estimated (estimate-tokens packet)]
    (assoc packet
           :budget (assoc (:budget packet)
                          :estimated estimated
                          :truncated (< (count (:docs packet)) (count docs))))))

(defn- resolve-map-overlay
  [path overlay project-id]
  (cond
    overlay overlay
    (and path (graph-map/file-exists? path)) (graph-map/read-map path)
    :else (graph-map/empty-map project-id)))

(defn- active-row?
  [row]
  (not= false (:active? row)))

(defn- scope-match?
  [{:keys [project-id repo-id]} row]
  (and (or (str/blank? (str project-id)) (= project-id (:project-id row)))
       (or (str/blank? (str repo-id)) (= repo-id (:repo-id row)))))

(defn- scoped-active-count
  [xtdb table {:keys [project-id repo-id read-context]}]
  (->> (store/all-rows xtdb table (store/read-context read-context))
       (filter active-row?)
       (filter #(scope-match? {:project-id project-id :repo-id repo-id} %))
       count))

(defn- overlay-counts
  [overlay]
  {:map-systems (count (:systems overlay))
   :map-docs (count (:docs overlay))
   :map-edges (count (:edges overlay))
   :map-rejects (count (:reject overlay))})

(defn- capability-counts
  [xtdb overlay {:keys [project-id repo-id read-context]}]
  (merge
   {:files (scoped-active-count xtdb (:files store/tables)
                                {:project-id project-id
                                 :repo-id repo-id
                                 :read-context read-context})
    :nodes (count (filter active-row?
                          (query/all-nodes xtdb {:project-id project-id
                                                 :repo-id repo-id
                                                 :read-context read-context})))
    :edges (count (filter active-row?
                          (query/all-edges xtdb {:project-id project-id
                                                 :repo-id repo-id
                                                 :read-context read-context})))
    :chunks (count (filter active-row?
                           (query/all-chunks xtdb {:project-id project-id
                                                   :repo-id repo-id
                                                   :read-context read-context})))
    :search-docs (count (query/all-search-docs xtdb {:project-id project-id
                                                     :repo-id repo-id
                                                     :read-context read-context}))
    :embeddings (count (query/all-embeddings xtdb {:project-id project-id
                                                   :repo-id repo-id
                                                   :read-context read-context}))
    :system-nodes (count (query/all-system-nodes xtdb {:project-id project-id
                                                       :read-context read-context}))
    :system-edges (count (query/all-system-edges xtdb {:project-id project-id
                                                       :read-context read-context}))
    :activity-items (count (activity/all-items xtdb {:project-id project-id
                                                     :read-context read-context}))
    :activity-events (count (activity/all-events xtdb {:project-id project-id
                                                       :read-context read-context}))
    :validation-events (count (filter #(= :validation (:event-kind %))
                                      (activity/all-events xtdb {:project-id project-id
                                                                 :read-context read-context})))
    :diagnostics (count (filter active-row?
                                (query/all-diagnostics xtdb {:project-id project-id
                                                             :repo-id repo-id
                                                             :read-context read-context})))}
   (overlay-counts overlay)))

(defn- retrieval-summary
  [{:keys [retriever embedding-client]}]
  (let [requested (keyword (or retriever :auto))
        effective (case requested
                    :auto (if embedding-client :hybrid :lexical)
                    requested)
        fallback? (and (= :auto requested) (= :lexical effective))]
    (cond-> {:requested requested
             :effective effective
             :fallback? fallback?}
      fallback? (assoc :reason "No embedding client was available."))))

(defn- available-planes
  [counts]
  (cond-> []
    (pos? (+ (:nodes counts) (:edges counts))) (conj :source-graph)
    (pos? (+ (:chunks counts) (:search-docs counts))) (conj :docs)
    (pos? (:embeddings counts)) (conj :embeddings)
    (pos? (+ (:system-nodes counts) (:system-edges counts))) (conj :system-graph)
    (pos? (+ (:activity-items counts) (:activity-events counts))) (conj :activity)
    (pos? (:validation-events counts)) (conj :validation-history)
    (pos? (+ (:map-systems counts)
             (:map-docs counts)
             (:map-edges counts)
             (:map-rejects counts))) (conj :map-overlay)))

(defn- missing-planes
  [counts]
  (cond-> []
    (zero? (:files counts)) (conj :source-files)
    (zero? (+ (:nodes counts) (:edges counts))) (conj :source-graph)
    (zero? (+ (:chunks counts) (:search-docs counts))) (conj :docs)
    (zero? (:embeddings counts)) (conj :embeddings)
    (zero? (+ (:system-nodes counts) (:system-edges counts))) (conj :system-graph)
    (zero? (+ (:activity-items counts) (:activity-events counts))) (conj :activity)
    (zero? (:validation-events counts)) (conj :validation-history)))

(defn- weak-planes
  [counts {:keys [entity-count doc-count activity-count validation-count]}]
  (cond-> []
    (and (pos? (+ (:system-nodes counts) (:system-edges counts)))
         (zero? entity-count))
    (conj :system-graph)

    (and (pos? (:search-docs counts))
         (zero? doc-count))
    (conj :docs)

    (and (pos? (+ (:activity-items counts) (:activity-events counts)))
         (zero? activity-count))
    (conj :activity)

    (and (pos? (:validation-events counts))
         (zero? validation-count))
    (conj :validation-history)))

(defn- answerability-warnings
  [counts retrieval weak]
  (cond-> []
    (and (= :auto (:requested retrieval)) (:fallback? retrieval))
    (conj "No embedding client was available; retrieval used lexical fallback.")

    (zero? (:search-docs counts))
    (conj "No search docs are indexed; context retrieval is limited.")

    (zero? (+ (:system-nodes counts) (:system-edges counts)))
    (conj "No system graph rows are indexed for this project.")

    (some #{:system-graph} weak)
    (conj "System graph rows are indexed, but no graph entities matched this query.")

    (some #{:docs} weak)
    (conj "Search docs are indexed, but no docs matched this query.")

    (zero? (+ (:activity-items counts) (:activity-events counts)))
    (conj "No activity/work rows are indexed; prior work queries are limited.")

    (some #{:activity} weak)
    (conj "Activity/work rows are indexed, but no activity matched this query.")

    (zero? (:validation-events counts))
    (conj "No validation history rows are indexed; validation-history queries are limited.")

    (some #{:validation-history} weak)
    (conj "Validation history rows are indexed, but no validation events matched this query.")

    (zero? (:embeddings counts))
    (conj "No embeddings are indexed for this project.")

    true
    (conj "Remote work items and session history are not modeled in the current graph.")))

(defn- next-steps
  [counts retrieval]
  (->> (cond-> []
         (zero? (:files counts))
         (conj "Run agraph sync <project.edn>")

         (zero? (:search-docs counts))
         (conj "Run agraph sync <project.edn> --query-index")

         (and (= :auto (:requested retrieval)) (:fallback? retrieval))
         (conj "Run agraph embed --provider openrouter")

         (zero? (+ (:system-nodes counts) (:system-edges counts)))
         (conj "Run agraph sync <project.edn>")

         (zero? (+ (:activity-items counts) (:activity-events counts)))
         (conj "Run agraph sync activity <project.edn>")

         (pos? (:diagnostics counts))
         (conj "Run agraph sync coverage <project.edn> --json"))
       distinct
       (take 5)
       vec))

(defn- answerability-status
  [missing weak retrieval {:keys [entity-count doc-count activity-count]}]
  (let [core-missing? (some #{:source-files :source-graph :docs :system-graph} missing)
        core-weak? (some #{:system-graph :docs} weak)]
    (cond
      (and (zero? entity-count) (zero? doc-count) (zero? activity-count)) :empty
      (or (:fallback? retrieval)
          core-weak?
          core-missing?) :limited
      :else :ready)))

(defn- answerability
  [xtdb overlay opts match-counts]
  (let [counts (capability-counts xtdb overlay opts)
        retrieval (retrieval-summary opts)
        missing (missing-planes counts)
        weak (weak-planes counts match-counts)]
    {:status (answerability-status missing weak retrieval match-counts)
     :available (available-planes counts)
     :missing missing
     :weak weak
     :unsupported unsupported-planes
     :counts counts
     :retrieval retrieval
     :warnings (answerability-warnings counts retrieval weak)
     :next (next-steps counts retrieval)}))

(defn context-packet
  "Return compact graph/doc context for an agent query."
  [xtdb query-text {:keys [budget entity-limit edge-limit doc-limit snippet-chars
                           retriever embedding-client project-id repo-id map-path
                           map-overlay min-confidence read-context]
                    :or {budget default-budget
                         entity-limit default-entity-limit
                         edge-limit default-edge-limit
                         doc-limit default-doc-limit
                         snippet-chars default-snippet-chars
                         retriever :auto
                         min-confidence 0.55}}]
  (when (str/blank? (str query-text))
    (throw (ex-info "Missing context query text." {})))
  (when (str/blank? (str project-id))
    (throw (ex-info "Context query requires --project." {})))
  (let [overlay (resolve-map-overlay map-path map-overlay project-id)
        query-tokens (text/tokenize query-text)
        results (query/semantic-query xtdb
                                      query-text
                                      {:limit default-retrieval-limit
                                       :retriever retriever
                                       :embedding-client embedding-client
                                       :project-id project-id
                                       :repo-id repo-id
                                       :read-context read-context})
        graph-data (graph/system-graph xtdb
                                       project-id
                                       {:limit graph/default-node-limit
                                        :min-confidence min-confidence
                                        :map-path map-path
                                        :map-overlay map-overlay
                                        :read-context read-context})
        chunks (vec (query/all-chunks xtdb {:project-id project-id
                                            :repo-id repo-id
                                            :read-context read-context}))
        entities (select-entities query-tokens results graph-data entity-limit)
        edges (select-edges query-tokens entities graph-data edge-limit)
        targets (selected-targets entities edges)
        activity (activity/select-activity xtdb
                                           query-text
                                           {:project-id project-id
                                            :read-context read-context
                                            :target-ids targets})
        warnings (cond-> []
                   (empty? entities)
                   (conj "no graph entities matched the query"))
        drilldowns (cond-> [(str "agraph query " (pr-str query-text) " --project " project-id)
                            (str "agraph graph export systems --project " project-id)]
                     map-path
                     (conj (str "agraph docs audit --project " project-id " --map " map-path)))
        docs (->> (concat (attached-docs overlay chunks snippet-chars targets)
                          (inferred-docs query-tokens results chunks entities snippet-chars))
                  distinct-docs
                  (sort-by (juxt doc-priority
                                 (comp - :score)
                                 :role
                                 #(get-in % [:source :path])))
                  diversify-docs
                  (take doc-limit)
                  vec)
        answerability (answerability xtdb
                                     overlay
                                     {:project-id project-id
                                      :repo-id repo-id
                                      :read-context read-context
                                      :retriever retriever
                                      :embedding-client embedding-client}
                                     {:entity-count (count entities)
                                      :doc-count (count docs)
                                      :activity-count (count activity)
                                      :validation-count (count (filter #(some (fn [event]
                                                                                (= :validation (:event-kind event)))
                                                                              (:events %))
                                                                       activity))})]
    (fit-budget (base-packet query-text
                             budget
                             graph-data
                             entities
                             edges
                             activity
                             warnings
                             drilldowns
                             answerability)
                docs
                budget)))

(defn doc-candidates
  "Return compact doc candidates for a graph target."
  [xtdb target {:keys [project-id limit snippet-chars read-context]
                :or {limit default-doc-limit
                     snippet-chars default-snippet-chars}}]
  (let [target-text (str target)
        target-tokens (text/tokenize target-text)
        chunks (vec (query/all-chunks xtdb {:project-id project-id
                                            :read-context read-context}))]
    (->> chunks
         (filter #(= :markdown (:kind %)))
         (map #(assoc % :context-score (token-score target-tokens
                                                    (compact (:label %) (:path %) (:text %)))))
         (filter #(pos? (:context-score %)))
         (sort-by (juxt (comp - :context-score) :path :source-line))
         (take limit)
         (mapv (fn [chunk]
                 {:target target
                  :role "reference"
                  :status "candidate"
                  :source (chunk-source chunk)
                  :score (double (:context-score chunk))
                  :snippet (truncate (:text chunk) snippet-chars)})))))

(defn docs-for
  "Return attached docs for target with resolved snippets where possible."
  [xtdb target {:keys [project-id map-path map-overlay snippet-chars read-context]
                :or {snippet-chars default-snippet-chars}}]
  (let [overlay (resolve-map-overlay map-path map-overlay project-id)
        chunks (vec (query/all-chunks xtdb {:project-id project-id
                                            :read-context read-context}))]
    {:schema "agraph.docs/v1"
     :target target
     :docs (mapv #(attachment->doc chunks snippet-chars %)
                 (graph-map/docs-for-target overlay target))}))

(defn docs-audit
  "Return maintenance findings for doc attachments and mapped systems."
  [xtdb {:keys [project-id map-path map-overlay read-context]}]
  (let [overlay (resolve-map-overlay map-path map-overlay project-id)
        chunks (vec (query/all-chunks xtdb {:project-id project-id
                                            :read-context read-context}))
        systems (:systems overlay)
        docs (:docs overlay)
        docs-by-target (group-by :target docs)
        unresolved (->> docs
                        (map #(attachment->doc chunks default-snippet-chars %))
                        (filter #(= "stale" (:status %)))
                        (mapv #(dissoc % :snippet)))
        undocumented (->> systems
                          (filter #(= "accepted" (s (:status %))))
                          (remove #(seq (get docs-by-target (:id %))))
                          (mapv #(select-keys % [:id :label :kind])))]
    {:schema "agraph.docs.audit/v1"
     :project project-id
     :counts {:attachments (count docs)
              :unresolved (count unresolved)
              :undocumentedSystems (count undocumented)}
     :unresolved unresolved
     :undocumentedSystems undocumented}))
