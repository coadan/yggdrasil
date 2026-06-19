(ns agraph.context-architecture
  (:require [clojure.string :as str]))

(defn- s
  [value]
  (some-> value str))

(defn- display-name
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(defn- path-under?
  [path prefix]
  (and (seq path)
       (seq prefix)
       (or (= path prefix)
           (str/starts-with? path (str prefix "/")))))

(defn- compact
  [& parts]
  (->> parts
       flatten
       (remove nil?)
       (remove #(str/blank? (str %)))
       (str/join " ")))

(defn- token-score
  [query-tokens text]
  (count (filter #(str/includes? (str/lower-case (str text)) %) query-tokens)))

(defn- capped-token-score
  [query-tokens text]
  (min 4 (token-score query-tokens text)))

(defn- ranked-result-paths
  [results n]
  (->> results
       (keep :path)
       distinct
       (take n)
       set))

(defn- accepted-system-row
  [system]
  (let [path-prefix (or (:pathPrefix system)
                        (:path-prefix system)
                        (some :path (:includes system)))]
    (cond-> {:id (s (:id system))
             :label (or (s (:label system)) (s (:id system)))
             :status "accepted"}
      (:kind system) (assoc :kind (display-name (:kind system)))
      (:repo system) (assoc :repo (s (:repo system)))
      path-prefix (assoc :pathPrefix (s path-prefix))
      (seq (:includes system)) (assoc :includes (mapv #(select-keys % [:id
                                                                       :repo
                                                                       :path
                                                                       :pathPrefix
                                                                       :path-prefix])
                                                      (:includes system)))
      (seq (:tags system)) (assoc :tags (mapv s (:tags system)))
      (:reason system) (assoc :reason (:reason system))
      (:evidence system) (assoc :evidence (:evidence system)))))
(defn- source-repo
  [source]
  (or (:repo source)
      (:repo-id source)))
(defn- source-path
  [source]
  (:path source))
(defn- compatible-repo?
  [expected actual]
  (or (str/blank? (str expected))
      (str/blank? (str actual))
      (= (s expected) (s actual))))
(defn- system-source-prefixes
  [system]
  (let [system-prefix (or (:pathPrefix system)
                          (:path-prefix system)
                          (:path system))]
    (->> (concat
          (when system-prefix
            [{:repo (:repo system)
              :path system-prefix}])
          (mapcat (fn [include]
                    (keep (fn [path]
                            (when path
                              {:repo (or (:repo include) (:repo system))
                               :path path}))
                          [(:pathPrefix include)
                           (:path-prefix include)
                           (:path include)]))
                  (:includes system)))
         (remove #(str/blank? (str (:path %))))
         distinct
         vec)))
(defn- source-selects-system?
  [source system]
  (when-let [path (source-path source)]
    (some (fn [prefix]
            (and (path-under? path (:path prefix))
                 (compatible-repo? (:repo prefix) (source-repo source))))
          (system-source-prefixes system))))
(defn selected-accepted-systems
  [overlay entities selected-sources]
  (let [selected-ids (set (map :id entities))
        source-selected? (fn [system]
                           (some #(source-selects-system? % system)
                                 selected-sources))]
    (->> (:systems overlay)
         (filter #(or (contains? selected-ids (s (:id %)))
                      (source-selected? %)))
         (mapv accepted-system-row))))
(defn- candidate-system-row
  [entity]
  (cond-> {:id (:id entity)
           :label (:label entity)
           :kind (:kind entity)
           :status "candidate"
           :basis "system-graph"
           :score (:score entity)}
    (:repo entity) (assoc :repo (:repo entity))
    (:path entity) (assoc :path (:path entity))
    (:pathPrefix entity) (assoc :pathPrefix (:pathPrefix entity))
    (:clusterId entity) (assoc :clusterId (:clusterId entity))
    (:clusterLabel entity) (assoc :clusterLabel (:clusterLabel entity))
    (:why entity) (assoc :why (:why entity))))
(defn- selected-candidate-systems
  [accepted-systems entities]
  (let [accepted-ids (set (map :id accepted-systems))]
    (->> entities
         (remove #(contains? accepted-ids (:id %)))
         (mapv candidate-system-row))))
(defn- graph-edge-evidence-row
  [edge]
  (cond-> {:kind "graph-edge"
           :id (:id edge)
           :source (:source edge)
           :target (:target edge)
           :relation (:relation edge)}
    (:confidence edge) (assoc :confidence (:confidence edge))
    (:salience edge) (assoc :salience (:salience edge))
    (:score edge) (assoc :score (:score edge))
    (:evidenceCounts edge) (assoc :evidenceCounts (:evidenceCounts edge))
    (:relations edge) (assoc :relations (:relations edge))))
(defn- map-edge-evidence-row
  [edge]
  (cond-> {:kind "map-edge"
           :id (s (:id edge))
           :source (s (:source edge))
           :target (s (:target edge))
           :relation (display-name (:relation edge))
           :status (or (some-> (:status edge) display-name) "accepted")
           :provenance "map-overlay"}
    (:reason edge) (assoc :reason (:reason edge))
    (:evidence edge) (assoc :evidence (:evidence edge))))
(defn- selected-map-edges
  [overlay entities accepted-systems]
  (let [selected-ids (set (concat (map :id entities)
                                  (map :id accepted-systems)))]
    (->> (:edges overlay)
         (filter #(not= "rejected" (display-name (:status %))))
         (filter #(or (contains? selected-ids (s (:source %)))
                      (contains? selected-ids (s (:target %)))))
         (mapv map-edge-evidence-row))))
(defn- evidence-count
  [row]
  (+ (count (:evidence row))
     (reduce + 0 (map long (vals (:evidenceCounts row))))
     (count (:relations row))))
(defn- confidence-rank
  [confidence]
  (case (display-name confidence)
    "high" 3
    "medium" 2
    "low" 1
    (if (number? confidence)
      (double confidence)
      0)))
(defn- selected-endpoint-count
  [selected-ids row]
  (+ (if (contains? selected-ids (s (:source row))) 1 0)
     (if (contains? selected-ids (s (:target row))) 1 0)))
(defn- map-evidence-rank
  [row]
  (cond
    (and (= "map-edge" (:kind row))
         (= "accepted" (display-name (:status row)))) 2
    (= "map-edge" (:kind row)) 1
    :else 0))
(defn- evidence-sort-key
  [selected-ids row]
  [(- (map-evidence-rank row))
   (- (selected-endpoint-count selected-ids row))
   (- (evidence-count row))
   (- (double (or (:score row) 0.0)))
   (- (double (or (:salience row) 0.0)))
   (- (confidence-rank (:confidence row)))
   (or (display-name (:relation row)) "")
   (or (s (:source row)) "")
   (or (s (:target row)) "")
   (or (s (:id row)) "")])
(defn- ranked-evidence
  [selected-ids rows]
  (->> rows
       (sort-by #(evidence-sort-key selected-ids %))
       vec))
(defn- relationship-target-row
  [edge]
  (cond-> {:id (:id edge)
           :target (:target edge)}
    (:confidence edge) (assoc :confidence (:confidence edge))
    (:salience edge) (assoc :salience (:salience edge))
    (:visibility edge) (assoc :visibility (:visibility edge))
    (:score edge) (assoc :score (:score edge))))
(defn- relationship-group-row
  [[[relation source] edges]]
  {:relation (display-name relation)
   :source source
   :count (count edges)
   :targets (mapv relationship-target-row
                  (sort-by (juxt :target :id) edges))})
(defn relationship-groups
  [edges]
  (->> edges
       (group-by (juxt :relation :source))
       (sort-by (fn [[[relation source] grouped-edges]]
                  [(- (count grouped-edges))
                   (display-name relation)
                   source]))
       (mapv relationship-group-row)))
(defn- blast-radius-target-row
  [direction edge]
  (cond-> {:id (:id edge)
           :relation (display-name (:relation edge))
           :source (:source edge)
           :target (:target edge)
           :neighbor (case direction
                       :downstream (:target edge)
                       :upstream (:source edge))}
    (:confidence edge) (assoc :confidence (:confidence edge))
    (:salience edge) (assoc :salience (:salience edge))
    (:visibility edge) (assoc :visibility (:visibility edge))
    (:score edge) (assoc :score (:score edge))))
(defn- blast-radius-side
  [direction selected-ids edges]
  (let [rows (->> edges
                  (filter (fn [edge]
                            (case direction
                              :downstream (and (contains? selected-ids (:source edge))
                                               (not (contains? selected-ids
                                                               (:target edge))))
                              :upstream (and (contains? selected-ids (:target edge))
                                             (not (contains? selected-ids
                                                             (:source edge)))))))
                  (sort-by (juxt :relation :neighbor :id))
                  (mapv #(blast-radius-target-row direction %)))]
    {:count (count rows)
     :targets (vec (take 8 rows))}))
(defn blast-radius
  [entities edges]
  (let [selected-ids (set (map :id entities))
        downstream (blast-radius-side :downstream selected-ids edges)
        upstream (blast-radius-side :upstream selected-ids edges)]
    (when (or (pos? (:count downstream))
              (pos? (:count upstream)))
      {:basis "selected-mechanical-edges"
       :downstream downstream
       :upstream upstream})))
(defn- system-evidence-score
  [query-tokens selected-system-ids selected-paths row]
  (let [path-score (if (contains? selected-paths (:path row)) 1.0 0.0)
        token-score (capped-token-score query-tokens
                                        (compact (:kind row)
                                                 (:file-kind row)
                                                 (:label row)
                                                 (:normalized-value row)
                                                 (:path row)))
        system-score (if (contains? selected-system-ids (:system-id row)) 0.75 0.0)]
    (if (or (pos? path-score) (pos? token-score) (pos? system-score))
      (+ path-score system-score (* 0.5 token-score))
      0.0)))
(defn- system-evidence-row
  [score row]
  (cond-> {:id (:xt/id row)
           :systemId (:system-id row)
           :repo (:repo-id row)
           :path (:path row)
           :kind (display-name (:kind row))
           :label (:label row)
           :normalizedValue (:normalized-value row)
           :sourceLine (:source-line row)
           :confidence (:confidence row)
           :score score}
    (:file-kind row) (assoc :fileKind (display-name (:file-kind row)))
    (:url-context row) (assoc :urlContext (display-name (:url-context row)))
    (:auth-context row) (assoc :authContext (display-name (:auth-context row)))))
(def ^:private runtime-evidence-path-limit
  2)
(def ^:private runtime-evidence-system-limit
  2)
(def ^:private runtime-evidence-result-path-limit
  20)
(defn- runtime-evidence-path-key
  [row]
  [(:repo row) (:path row)])
(defn- runtime-evidence-selectable?
  [path-counts system-counts enforce-system-limit? row]
  (let [path-count (long (get path-counts (runtime-evidence-path-key row) 0))
        system-count (long (get system-counts (:systemId row) 0))]
    (and (< path-count runtime-evidence-path-limit)
         (or (not enforce-system-limit?)
             (< system-count runtime-evidence-system-limit)))))
(defn- add-runtime-evidence-row
  [state row]
  (-> state
      (update :seen-ids conj (:id row))
      (update :path-counts update (runtime-evidence-path-key row) (fnil inc 0))
      (update :system-counts update (:systemId row) (fnil inc 0))
      (update :out conj row)))
(defn- take-system-evidence-pass
  [rows limit state enforce-system-limit?]
  (reduce (fn [state row]
            (cond
              (>= (count (:out state)) limit)
              (reduced state)

              (contains? (:seen-ids state) (:id row))
              state

              (runtime-evidence-selectable? (:path-counts state)
                                            (:system-counts state)
                                            enforce-system-limit?
                                            row)
              (add-runtime-evidence-row state row)

              :else
              state))
          state
          rows))
(defn- take-diverse-system-evidence
  [rows limit]
  (let [initial-state {:seen-ids #{}
                       :path-counts {}
                       :system-counts {}
                       :out []}
        diverse-state (take-system-evidence-pass rows limit initial-state true)
        filled-state (if (< (count (:out diverse-state)) limit)
                       (take-system-evidence-pass rows limit diverse-state false)
                       diverse-state)]
    (:out filled-state)))
(defn select-system-evidence
  [query-tokens selected-system-ids results evidence limit]
  (let [selected-system-ids (set selected-system-ids)
        selected-paths (set (ranked-result-paths results runtime-evidence-result-path-limit))]
    (->> evidence
         (map (fn [row]
                (let [score (system-evidence-score query-tokens
                                                   selected-system-ids
                                                   selected-paths
                                                   row)]
                  (when (pos? score)
                    (system-evidence-row score row)))))
         (keep identity)
         (sort-by (juxt (comp - :score)
                        (comp - #(double (or (:confidence %) 0.0)))
                        :repo
                        :path
                        :sourceLine
                        :kind
                        :label))
         (#(take-diverse-system-evidence % limit))
         vec)))
(def dependency-relations
  #{"imports-package" "requires" "version-of" "resolves"})
(defn- dependency-evidence?
  [edge]
  (contains? dependency-relations (display-name (:relation edge))))
(def ^:private dependency-result-path-limit
  20)
(defn- source-location-row
  [source]
  (cond-> {:path (:path source)}
    (:id source) (assoc :id (:id source))
    (:label source) (assoc :label (:label source))
    (:line source) (assoc :line (:line source))
    (:version-range source) (assoc :versionRange (:version-range source))
    (:dependency-scope source) (assoc :dependencyScope (display-name (:dependency-scope source)))))
(defn- selected-source-paths
  [results accepted-systems candidate-systems]
  (let [result-paths (ranked-result-paths results dependency-result-path-limit)
        system-prefixes (mapcat system-source-prefixes
                                (concat accepted-systems candidate-systems))]
    {:result-paths result-paths
     :system-prefixes system-prefixes}))
(defn- source-path-selected?
  [{:keys [result-paths system-prefixes]} source]
  (let [path (:path source)
        repo (:repo source)]
    (or (contains? result-paths path)
        (some (fn [prefix]
                (and (path-under? path (:path prefix))
                     (compatible-repo? (:repo prefix) repo)))
              system-prefixes))))
(defn- dependency-token-score
  [query-tokens row]
  (capped-token-score query-tokens
                      (compact (:kind row)
                               (:relation row)
                               (:label row)
                               (:package row)
                               (:import row)
                               (:ecosystem row)
                               (:path row)
                               (:versions row))))
(defn- package-import-evidence-rows
  [selection query-tokens package]
  (let [sources (filter #(source-path-selected? selection %)
                        (:imported-by package))]
    (mapv (fn [source]
            (cond-> {:kind "package-import"
                     :id (str (:id package) ":import:" (:path source) ":" (:line source))
                     :packageId (:id package)
                     :package (:package-name package)
                     :label (:label package)
                     :ecosystem (display-name (:ecosystem package))
                     :relation "imports-package"
                     :path (:path source)
                     :sourceLine (:line source)
                     :score (+ 1.0
                               (* 0.25
                                  (dependency-token-score query-tokens
                                                          (assoc package
                                                                 :kind "package-import"
                                                                 :path (:path source)))))}
              (:import-name source) (assoc :importName (:import-name source))
              (:resolution-source source) (assoc :resolutionSource (display-name (:resolution-source source)))))
          sources)))
(defn- unresolved-import-evidence-row
  [selection query-tokens row]
  (when (source-path-selected? selection row)
    (cond-> {:kind "unresolved-import"
             :id (str "unresolved-import:" (:source-id row) ":" (:target-id row) ":" (:path row) ":" (:line row))
             :source (:source-id row)
             :target (:target-id row)
             :import (:import row)
             :repo (:repo-id row)
             :path (:path row)
             :sourceLine (:line row)
             :relation "unresolved-import"
             :score (+ 1.25
                       (* 0.25
                          (dependency-token-score query-tokens
                                                  (assoc row
                                                         :kind "unresolved-import"
                                                         :label (:source-label row)))))}
      (:source-label row) (assoc :sourceLabel (:source-label row))
      (:kind row) (assoc :fileKind (display-name (:kind row))))))
(defn- declared-without-import-evidence-row
  [query-tokens package]
  (let [declared-by (mapv source-location-row (take 3 (:declared-by package)))]
    (when (seq declared-by)
      {:kind "declared-package-without-import-evidence"
       :id (str (:id package) ":declared-without-import-evidence")
       :packageId (:id package)
       :package (:package-name package)
       :label (:label package)
       :ecosystem (display-name (:ecosystem package))
       :relation "declared-without-import-evidence"
       :declaredBy declared-by
       :score (+ 0.5
                 (* 0.25
                    (dependency-token-score query-tokens
                                            (assoc package
                                                   :kind "declared-package-without-import-evidence"))))})))
(defn- version-conflict-evidence-row
  [query-tokens conflict]
  {:kind "package-version-conflict"
   :id (str (:id conflict) ":version-conflict")
   :packageId (:id conflict)
   :package (:package-name conflict)
   :label (:label conflict)
   :ecosystem (display-name (:ecosystem conflict))
   :relation "version-conflict"
   :versions (:versions conflict)
   :score (+ 0.75
             (* 0.25
                (dependency-token-score query-tokens
                                        (assoc conflict
                                               :kind "package-version-conflict"))))})
(defn- dependency-report-evidence
  [query-tokens results accepted-systems candidate-systems dependency-report]
  (let [selection (selected-source-paths results accepted-systems candidate-systems)]
    (->> (concat
          (mapcat #(package-import-evidence-rows selection query-tokens %)
                  (:packages dependency-report))
          (keep #(unresolved-import-evidence-row selection query-tokens %)
                (:unresolved-imports dependency-report))
          (map #(declared-without-import-evidence-row query-tokens %)
               (:declared-without-import-evidence dependency-report))
          (map #(version-conflict-evidence-row query-tokens %)
               (:version-conflicts dependency-report)))
         (keep identity)
         (sort-by (juxt (comp - #(double (or (:score %) 0.0)))
                        :kind
                        :ecosystem
                        :package
                        :path
                        :sourceLine
                        :id))
         vec)))
(defn- architecture-doc-row
  [doc]
  (cond-> (select-keys doc [:target
                            :role
                            :status
                            :source
                            :score
                            :provenance
                            :reason
                            :warning])
    (:snippetOmitted doc) (assoc :snippetOmitted true)))
(defn- accepted-architecture-doc?
  [doc]
  (or (= "map-attachment" (:provenance doc))
      (= "accepted" (display-name (:status doc)))))
(defn- open-decision-row
  [item]
  (select-keys item [:id
                     :kind
                     :status
                     :source
                     :sourceId
                     :sourcePath
                     :payloadSchema
                     :expectedResultSchema
                     :resultSchema
                     :targetIds
                     :summary
                     :score
                     :updatedAtMs]))
(defn- open-decision?
  [item]
  (not= "completed" (s (:status item))))
(def ^:private freshness-gap-statuses
  #{"partial" "stale" "unknown" "unsynced"})
(defn- freshness-validation-gap
  [freshness]
  (let [status (display-name (:status freshness))]
    (when (contains? freshness-gap-statuses status)
      (cond-> {:plane "graph-basis"
               :status status}
        (seq (:counts freshness)) (assoc :counts (:counts freshness))
        (seq (:warnings freshness)) (assoc :warnings (vec (take 3 (:warnings freshness))))))))
(def ^:private action-kinds-by-plane
  {:source-files #{:source-files :coverage}
   :source-graph #{:source-graph :coverage}
   :dependencies #{:dependencies :dependency-review}
   :system-evidence #{:system-evidence}
   :docs #{:docs}
   :embeddings #{:embeddings}
   :system-graph #{:system-graph}
   :activity #{:activity}
   :validation-history #{:activity}
   :map-overlay #{:map-overlay}})
(defn- action-kind
  [action]
  (keyword (:kind action)))
(defn- matching-plane-actions
  [actions plane]
  (let [kinds (get action-kinds-by-plane plane)]
    (->> actions
         (filter #(contains? kinds (action-kind %)))
         (take 2)
         vec)))
(defn- validation-gap-row
  [answerability plane status]
  (let [actions (matching-plane-actions (:nextActions answerability) plane)]
    (cond-> {:plane (name plane)
             :status status}
      (seq actions) (assoc :nextActions actions))))
(defn- stale-architecture-doc?
  [doc]
  (= "stale" (display-name (:status doc))))
(defn- stale-doc-sample
  [doc]
  (cond-> (select-keys doc [:target :role :source :warning :reason])
    (:provenance doc) (assoc :provenance (:provenance doc))))
(defn- stale-doc-validation-gap
  [docs]
  (let [stale-docs (filter stale-architecture-doc? docs)]
    (when (seq stale-docs)
      {:plane "docs-contracts"
       :status "stale"
       :count (count stale-docs)
       :samples (mapv stale-doc-sample (take 3 stale-docs))})))
(defn- freshness-validation-gap-row
  [freshness]
  (when-let [gap (freshness-validation-gap freshness)]
    (cond-> gap
      (seq (:nextActions freshness))
      (assoc :nextActions (vec (take 2 (:nextActions freshness)))))))
(defn- validation-gaps
  [answerability freshness docs]
  (vec (concat (when-let [gap (freshness-validation-gap-row freshness)]
                 [gap])
               (when-let [gap (stale-doc-validation-gap docs)]
                 [gap])
               (map (fn [plane] (validation-gap-row answerability plane "missing"))
                    (:missing answerability))
               (map (fn [plane] (validation-gap-row answerability plane "weak"))
                    (:weak answerability))
               (map (fn [plane] (validation-gap-row answerability plane "unsupported"))
                    (:unsupported answerability)))))
(def ^:private architecture-family-specs
  [{:family "source-structure"
    :source-keys [:acceptedSystems :candidateSystems :boundaryEvidence]
    :planes [:source-graph :system-graph]}
   {:family "dependency-flow"
    :source-keys [:dependencyEvidence]
    :planes [:dependencies]}
   {:family "runtime-config"
    :source-keys [:runtimeEvidence]
    :planes [:system-evidence]}
   {:family "deploy-topology"
    :source-keys [:deployEvidence]
    :planes [:system-evidence]}
   {:family "docs-contracts"
    :source-keys [:docs]
    :planes [:docs]}
   {:family "map-corrections"
    :source-keys [:acceptedSystems :mapEdges]
    :planes [:map-overlay]}
   {:family "maintenance"
    :source-keys [:openDecisions]
    :planes [:activity]}])
(defn- family-plane-status
  [answerability plane]
  (cond
    (contains? (set (:weak answerability)) plane) "weak"
    (contains? (set (:missing answerability)) plane) "missing"
    (contains? (set (:unsupported answerability)) plane) "unsupported"
    (contains? (set (:available answerability)) plane) "available"))
(defn- family-plane-rows
  [answerability planes]
  (->> planes
       (keep (fn [plane]
               (when-let [status (family-plane-status answerability plane)]
                 {:plane (name plane)
                  :status status})))
       vec))
(defn- architecture-source-count
  [section source-key]
  (case source-key
    :mapEdges (count (filter #(= "map-edge" (:kind %))
                             (:boundaryEvidence section)))
    (count (get section source-key))))
(defn- architecture-source-counts
  [section source-keys]
  (->> source-keys
       (keep (fn [source-key]
               (let [n (architecture-source-count section source-key)]
                 (when (pos? n)
                   {:key (name source-key)
                    :count n}))))
       vec))
(defn- family-status
  [row-count plane-rows]
  (cond
    (pos? row-count) "available"
    (some #(= "weak" (:status %)) plane-rows) "weak"
    (some #(= "missing" (:status %)) plane-rows) "missing"
    (some #(= "unsupported" (:status %)) plane-rows) "unsupported"))
(defn- architecture-evidence-family-row
  [section answerability {:keys [family source-keys planes]}]
  (let [source-counts (architecture-source-counts section source-keys)
        row-count (reduce + 0 (map :count source-counts))
        plane-rows (family-plane-rows answerability planes)
        status (family-status row-count plane-rows)]
    (when status
      (cond-> {:family family
               :status status
               :rowCount row-count}
        (seq source-counts) (assoc :sourceCounts source-counts)
        (seq plane-rows) (assoc :planes plane-rows)))))
(defn- architecture-evidence-families
  [section answerability]
  (->> architecture-family-specs
       (keep #(architecture-evidence-family-row section answerability %))
       vec))
(def ^:private deploy-evidence-kinds
  #{"container-image"
    "container-image-producer"
    "container-image-consumer"
    "container-port"
    "devcontainer-command"
    "devcontainer-feature"
    "devcontainer-port"
    "devcontainer-service"
    "docker-build-arg"
    "docker-copy-source"
    "docker-label"
    "docker-stage"
    "docker-workdir"
    "runtime-command"})
(def ^:private deploy-file-kinds
  #{"compose"
    "devcontainer"
    "docker"
    "helm"
    "kustomize"
    "procfile"})
(defn- deploy-evidence?
  [row]
  (or (contains? deploy-evidence-kinds (display-name (:kind row)))
      (contains? deploy-file-kinds (display-name (or (:fileKind row)
                                                     (:file-kind row))))))
(defn- deploy-evidence-rows
  [runtime-evidence]
  (->> runtime-evidence
       (filter deploy-evidence?)
       (take 8)
       vec))
(defn- architecture-supported?
  [section]
  (some seq
        (map section
             [:acceptedSystems
              :candidateSystems
              :boundaryEvidence
              :runtimeEvidence
              :deployEvidence
              :dependencyEvidence
              :docs
              :openDecisions])))
(defn- inspect-action-label
  [prefix row]
  (str prefix " " (or (:label row) (:id row))))
(defn- inspect-action
  [target label reason]
  (when-let [target (some-> target s str/trim not-empty)]
    {:kind :inspect
     :label label
     :target target
     :mcpTool "agraph_node"
     :mcpArgs {:target target}
     :reason reason}))
(defn- dependency-inspect-action
  [evidence]
  (let [target (or (:target evidence)
                   (:source evidence)
                   (:id evidence))]
    (inspect-action target
                    (compact "Inspect dependency"
                             (:relation evidence)
                             "target"
                             target)
                    "Open dependency endpoint with incident graph evidence")))
(defn- architecture-doc-inspect-action
  [doc]
  (let [target (or (get-in doc [:source :path])
                   (:target doc))]
    (inspect-action target
                    (compact "Inspect architecture doc" target)
                    "Open accepted architecture doc source and attached map evidence")))
(defn- architecture-inspect-actions
  [accepted-systems candidate-systems runtime-evidence dependency-evidence docs]
  (vec
   (concat
    (keep (fn [system]
            (inspect-action (:id system)
                            (inspect-action-label "Inspect accepted system" system)
                            "Open accepted map system with graph neighbors and attached evidence"))
          (take 2 accepted-systems))
    (keep (fn [system]
            (inspect-action (:id system)
                            (inspect-action-label "Inspect candidate system" system)
                            "Open candidate system with mechanical graph neighbors"))
          (take 2 candidate-systems))
    (keep (fn [evidence]
            (inspect-action (:id evidence)
                            (inspect-action-label "Inspect evidence" evidence)
                            "Open exact evidence row and source window"))
          (take 2 runtime-evidence))
    (keep dependency-inspect-action
          (take 2 dependency-evidence))
    (keep architecture-doc-inspect-action
          (take 2 docs)))))
(defn- status-counts
  [rows]
  (->> rows
       (map :status)
       (remove nil?)
       frequencies
       (into (sorted-map))))
(defn- kind-counts
  [rows]
  (->> rows
       (map action-kind)
       (remove nil?)
       frequencies
       (into (sorted-map))))
(defn- status-by-key
  [rows key]
  (->> rows
       (keep (fn [row]
               (when-let [k (some-> (get row key) display-name not-empty)]
                 (when-let [status (some-> (:status row) display-name not-empty)]
                   [k status]))))
       (into (sorted-map))))
(defn- summary-action-samples
  [actions]
  (->> actions
       (take 3)
       (mapv #(select-keys % [:kind
                              :label
                              :target
                              :command
                              :mcpTool
                              :mcpArgs]))))
(defn- summary-gap-samples
  [gaps]
  (->> gaps
       (take 3)
       (mapv #(select-keys % [:plane
                              :status
                              :count
                              :nextActions]))))
(defn- architecture-summary
  [section]
  {:counts {:acceptedSystems (count (:acceptedSystems section))
            :candidateSystems (count (:candidateSystems section))
            :boundaryEvidence (count (:boundaryEvidence section))
            :runtimeEvidence (count (:runtimeEvidence section))
            :deployEvidence (count (:deployEvidence section))
            :dependencyEvidence (count (:dependencyEvidence section))
            :docs (count (:docs section))
            :openDecisions (count (:openDecisions section))
            :validationGaps (count (:validationGaps section))
            :warnings (count (:warnings section))
            :nextActions (count (:nextActions section))}
   :evidenceFamilyStatuses (status-counts (:evidenceFamilies section))
   :evidenceFamilyStatusByFamily (status-by-key (:evidenceFamilies section)
                                                :family)
   :validationGapStatuses (status-counts (:validationGaps section))
   :validationGapStatusByPlane (status-by-key (:validationGaps section)
                                              :plane)
   :validationGapSamples (summary-gap-samples (:validationGaps section))
   :nextActionSamples (summary-action-samples (:nextActions section))
   :nextActionKinds (kind-counts (:nextActions section))})
(defn- freshness-next-actions
  [freshness]
  (vec (take 3 (:nextActions freshness))))
(defn- architecture-warnings
  [answerability freshness]
  (vec (take 5 (concat (:warnings freshness)
                       (:warnings answerability)))))
(defn architecture-section
  [{:keys [overlay entities results edges runtime-evidence dependency-report docs activity answerability freshness
           accepted-systems query-tokens]}]
  (let [accepted-systems (or accepted-systems
                             (selected-accepted-systems overlay entities results))
        candidate-systems (selected-candidate-systems accepted-systems entities)
        selected-ids (set (concat (map :id entities)
                                  (map :id accepted-systems)))
        map-edges (selected-map-edges overlay entities accepted-systems)
        boundary-evidence (vec (take 12
                                     (ranked-evidence
                                      selected-ids
                                      (concat map-edges
                                              (map graph-edge-evidence-row edges)))))
        dependency-evidence (vec (take 8
                                       (ranked-evidence
                                        selected-ids
                                        (concat
                                         (map graph-edge-evidence-row
                                              (filter dependency-evidence? edges))
                                         (dependency-report-evidence
                                          query-tokens
                                          results
                                          accepted-systems
                                          candidate-systems
                                          dependency-report)))))
        deploy-evidence (deploy-evidence-rows runtime-evidence)
        architecture-docs (mapv architecture-doc-row
                                (take 8 (filter accepted-architecture-doc? docs)))
        inspect-actions (architecture-inspect-actions accepted-systems
                                                      candidate-systems
                                                      runtime-evidence
                                                      dependency-evidence
                                                      architecture-docs)
        section {:basis "mechanical-plus-map"
                 :acceptedSystems accepted-systems
                 :candidateSystems candidate-systems
                 :boundaryEvidence boundary-evidence
                 :runtimeEvidence runtime-evidence
                 :deployEvidence deploy-evidence
                 :dependencyEvidence dependency-evidence
                 :docs architecture-docs
                 :openDecisions (mapv open-decision-row
                                      (take 6 (filter open-decision? activity)))
                 :validationGaps (vec (take 12
                                            (validation-gaps answerability
                                                             freshness
                                                             architecture-docs)))
                 :warnings (architecture-warnings answerability freshness)
                 :nextActions (vec (take 6 (concat inspect-actions
                                                   (freshness-next-actions freshness)
                                                   (:nextActions answerability))))}
        section (assoc section
                       :evidenceFamilies
                       (architecture-evidence-families section answerability))
        section (assoc section :summary (architecture-summary section))]
    (when (architecture-supported? section)
      section)))
(defn- system-summary-row
  [system]
  (select-keys system [:id
                       :label
                       :kind
                       :status
                       :basis
                       :repo
                       :path
                       :pathPrefix
                       :score
                       :why
                       :reason]))
(defn systems-section
  [architecture]
  (let [accepted-systems (:acceptedSystems architecture)
        candidate-systems (:candidateSystems architecture)
        accepted (mapv system-summary-row (take 6 accepted-systems))
        candidates (mapv system-summary-row (take 6 candidate-systems))]
    (when (or (seq accepted) (seq candidates))
      {:basis (:basis architecture)
       :accepted accepted
       :candidates candidates
       :counts {:accepted (count accepted-systems)
                :candidates (count candidate-systems)}})))
