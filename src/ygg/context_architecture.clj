(ns ygg.context-architecture
  (:require [clojure.set :as set]
            [clojure.string :as str]))

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
  (let [text (str/lower-case (str text))]
    (count (filter #(str/includes? text %) query-tokens))))

(defn- capped-token-score
  [query-tokens text]
  (min 4 (token-score query-tokens text)))

(def ^:private system-evidence-rare-fact-token-weight
  2.25)

(def ^:private system-evidence-rare-fact-token-cap
  3.0)

(defn- distinct-query-tokens
  [query-tokens]
  (vec (distinct query-tokens)))

(defn- matched-query-tokens
  [query-tokens text]
  (let [text (str/lower-case (str text))]
    (filterv #(str/includes? text %) query-tokens)))

(defn- system-evidence-fact-text
  [row]
  (compact (:kind row)
           (:label row)
           (:normalized-value row)))

(defn- system-evidence-token-path-counts
  [query-tokens evidence]
  (->> evidence
       (reduce (fn [counts row]
                 (reduce (fn [counts token]
                           (update counts token (fnil conj #{}) (:path row)))
                         counts
                         (matched-query-tokens query-tokens
                                               (system-evidence-fact-text row))))
               {})
       (map (fn [[token paths]]
              [token (count paths)]))
       (into {})))

(defn- rare-system-evidence-fact-token-score
  [token-path-counts matched-tokens]
  (let [score (reduce
               (fn [score token]
                 (let [path-count (long (or (get token-path-counts token) 0))]
                   (if (pos? path-count)
                     (+ score (/ 1.0 (Math/sqrt (double path-count))))
                     score)))
               0.0
               (distinct matched-tokens))]
    (min system-evidence-rare-fact-token-cap
         (* system-evidence-rare-fact-token-weight score))))

(defn- ranked-result-path-order
  [results n]
  (->> results
       (keep :path)
       distinct
       (take n)
       vec))

(defn- ranked-result-paths
  [results n]
  (set (ranked-result-path-order results n)))

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
    (seq (:candidateTypes entity)) (assoc :candidateTypes (:candidateTypes entity))
    (seq (:candidateEvidence entity)) (assoc :candidateEvidence (:candidateEvidence entity))
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

(defn- reject-match-row
  [match]
  (let [kind (or (:kind match) (:type match))
        path (or (:path match)
                 (:pathPrefix match)
                 (:path-prefix match))]
    (cond-> {}
      (:id match) (assoc :id (s (:id match)))
      (:label match) (assoc :label (s (:label match)))
      (:repo match) (assoc :repo (s (:repo match)))
      kind (assoc :kind (display-name kind))
      path (assoc :path (s path))
      (:host match) (assoc :host (s (:host match))))))

(defn- reject-correction-row
  [idx reject]
  (cond-> {:kind "map-reject"
           :id (or (s (:id reject))
                   (str "map-reject:" (inc idx)))
           :status "rejected"
           :provenance "map-overlay"
           :match (reject-match-row (:match reject))}
    (:reason reject) (assoc :reason (:reason reject))
    (:evidence reject) (assoc :evidence (:evidence reject))))

(defn- row-repo
  [row]
  (or (:repo row)
      (:repo-id row)))

(defn- row-kind
  [row]
  (or (:kind row)
      (:target-kind row)
      (:type row)))

(defn- row-label
  [row]
  (or (:label row)
      (:heading row)
      (:target-id row)))

(defn- row-paths
  [row]
  (->> (concat [(or (:path row)
                    (:pathPrefix row)
                    (:path-prefix row))]
               (mapcat (fn [include]
                         [(:path include)
                          (:pathPrefix include)
                          (:path-prefix include)])
                       (:includes row)))
       (remove #(str/blank? (str %)))
       distinct
       vec))

(defn- reject-match-path
  [match]
  (or (:path match)
      (:pathPrefix match)
      (:path-prefix match)))

(defn- compatible-match-field?
  [expected actual]
  (or (nil? expected)
      (nil? actual)
      (= (s expected) (s actual))))

(defn- path-overlaps?
  [left right]
  (and (seq left)
       (seq right)
       (or (path-under? left right)
           (path-under? right left))))

(defn- row-anchors-reject-match?
  [row match]
  (let [match-id (:id match)
        match-label (:label match)
        match-host (:host match)
        match-path (reject-match-path match)
        row-paths (row-paths row)]
    (or (and match-id
             (= (s match-id) (s (or (:id row) (:target-id row)))))
        (and match-label
             (= (s match-label) (s (row-label row))))
        (and match-host
             (= (s match-host) (s (row-label row))))
        (and match-path
             (some #(path-overlaps? (s %) (s match-path)) row-paths)))))

(defn- compatible-reject-row?
  [row match]
  (and (compatible-match-field? (:repo match) (row-repo row))
       (compatible-match-field? (or (:kind match) (:type match)) (row-kind row))))

(defn- selected-reject-correction?
  [selected-rows reject]
  (let [match (:match reject)]
    (and (seq match)
         (some (fn [row]
                 (and (row-anchors-reject-match? row match)
                      (compatible-reject-row? row match)))
               selected-rows))))

(defn- selected-rejected-corrections
  [overlay selected-rows]
  (->> (:reject overlay)
       (map-indexed vector)
       (filter (fn [[_idx reject]]
                 (selected-reject-correction? selected-rows reject)))
       (take 8)
       (mapv (fn [[idx reject]]
               (reject-correction-row idx reject)))))

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
(defn- evidence-path-covered?
  [selected path]
  (some #(= path (:path %)) selected))
(defn- selected-evidence-ids
  [selected]
  (set (keep :id selected)))
(def ^:private selected-path-dependency-evidence-min-score
  1.75)
(def ^:private dependency-evidence-display-limit
  8)
(def ^:private query-supported-dependency-identity-min-score
  2)
(def ^:private query-supported-dependency-identity-preserve-limit
  2)
(defn- preservable-selected-path-evidence?
  [row]
  (<= selected-path-dependency-evidence-min-score
      (double (or (:score row) 0.0))))
(defn- best-evidence-path-candidate
  [rows selected-ids path]
  (some #(when (and (= path (:path %))
                    (preservable-selected-path-evidence? %)
                    (not (contains? selected-ids (:id %))))
           %)
        rows))
(defn- evidence-path-replacement-index
  [selected protected-paths]
  (or (->> selected
           (map-indexed vector)
           reverse
           (some (fn [[idx row]]
                   (when-not (contains? protected-paths (:path row))
                     idx))))
      (when (seq selected)
        (dec (count selected)))))
(defn- preserve-evidence-result-path-coverage
  [rows selected selected-path-order]
  (loop [selected (vec selected)
         missing-paths (vec selected-path-order)
         protected-paths #{}]
    (if-let [path (first missing-paths)]
      (let [protected-paths (if (evidence-path-covered? selected path)
                              (conj protected-paths path)
                              protected-paths)
            selected-ids (selected-evidence-ids selected)
            candidate (best-evidence-path-candidate rows selected-ids path)
            replace-idx (evidence-path-replacement-index selected protected-paths)]
        (recur (if (and candidate replace-idx)
                 (assoc selected replace-idx candidate)
                 selected)
               (subvec missing-paths 1)
               (if candidate
                 (conj protected-paths path)
                 protected-paths)))
      selected)))
(defn- dependency-identity-token-score
  [query-tokens row]
  (capped-token-score query-tokens
                      (compact (:label row)
                               (:package row)
                               (:import row)
                               (:importName row)
                               (:versions row))))
(defn- query-supported-dependency-identity?
  [query-tokens row]
  (<= query-supported-dependency-identity-min-score
      (dependency-identity-token-score query-tokens row)))
(defn- evidence-row-key
  [row]
  (or (:id row)
      [(:kind row) (:relation row) (:path row) (:sourceLine row)]))
(defn- dependency-query-identity-candidates
  [query-tokens rows selected]
  (let [selected-keys (set (map evidence-row-key selected))]
    (->> rows
         (filter #(query-supported-dependency-identity? query-tokens %))
         (remove #(contains? selected-keys (evidence-row-key %)))
         (sort-by (juxt (comp - #(dependency-identity-token-score query-tokens %))
                        (comp - #(double (or (:score %) 0.0)))
                        #(or (:path %) "")
                        #(or (s (:id %)) "")))
         (take query-supported-dependency-identity-preserve-limit))))
(defn- dependency-query-identity-replacement-index
  [query-tokens selected]
  (->> selected
       (map-indexed vector)
       reverse
       (some (fn [[idx row]]
               (when-not (query-supported-dependency-identity? query-tokens row)
                 idx)))))
(defn- preserve-query-supported-dependency-identity
  [query-tokens rows selected]
  (let [query-tokens (distinct-query-tokens query-tokens)]
    (loop [selected (vec selected)
           candidates (seq (dependency-query-identity-candidates query-tokens
                                                                 rows
                                                                 selected))]
      (if-let [candidate (first candidates)]
        (let [replace-idx (dependency-query-identity-replacement-index query-tokens
                                                                       selected)]
          (recur (cond
                   (< (count selected) dependency-evidence-display-limit)
                   (conj selected candidate)

                   replace-idx
                   (assoc selected replace-idx candidate)

                   :else
                   selected)
                 (next candidates)))
        selected))))
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
(defn- evidence-value-key
  [row]
  (when (and (:kind row) (:normalized-value row))
    [(:kind row) (:normalized-value row)]))

(defn- selected-evidence-value-file-kinds
  [selected-paths evidence]
  (->> evidence
       (filter #(contains? selected-paths (:path %)))
       (keep (fn [row]
               (when-let [k (evidence-value-key row)]
                 [k (:file-kind row)])))
       (reduce (fn [out [k file-kind]]
                 (update out k (fnil conj #{}) file-kind))
               {})))

(defn- shared-selected-evidence-value?
  [selected-value-file-kinds selected-paths row]
  (when-let [file-kinds (get selected-value-file-kinds (evidence-value-key row))]
    (and (not (contains? selected-paths (:path row)))
         (not (contains? file-kinds (:file-kind row))))))

(defn- system-evidence-score
  [query-tokens selected-system-ids selected-paths selected-value-file-kinds token-path-counts row]
  (let [path-score (if (contains? selected-paths (:path row)) 1.0 0.0)
        token-score (capped-token-score query-tokens
                                        (compact (:kind row)
                                                 (:file-kind row)
                                                 (:label row)
                                                 (:normalized-value row)
                                                 (:path row)))
        matched-fact-tokens (matched-query-tokens query-tokens
                                                  (system-evidence-fact-text row))
        fact-token-score (min 4 (count matched-fact-tokens))
        rare-fact-token-score (rare-system-evidence-fact-token-score
                               token-path-counts
                               matched-fact-tokens)
        shared-value-score (if (shared-selected-evidence-value?
                                selected-value-file-kinds
                                selected-paths
                                row)
                             0.65
                             0.0)
        system-score (if (contains? selected-system-ids (:system-id row)) 0.75 0.0)]
    (if (or (pos? path-score)
            (pos? token-score)
            (pos? fact-token-score)
            (pos? shared-value-score)
            (pos? system-score))
      (+ path-score
         shared-value-score
         system-score
         (* 0.5 token-score)
         (* 0.8 fact-token-score)
         rare-fact-token-score)
      0.0)))
(defn- system-evidence-row
  [score matched-fact-tokens rare-fact-token-score row]
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
    (seq matched-fact-tokens) (assoc ::matched-fact-tokens
                                     (set matched-fact-tokens))
    (pos? (double rare-fact-token-score)) (assoc ::rare-fact-token-score
                                                 rare-fact-token-score)
    (:file-kind row) (assoc :fileKind (display-name (:file-kind row)))
    (:url-context row) (assoc :urlContext (display-name (:url-context row)))
    (:auth-context row) (assoc :authContext (display-name (:auth-context row)))))
(def ^:private runtime-evidence-path-limit
  2)
(def ^:private runtime-evidence-system-limit
  2)
(def ^:private runtime-evidence-result-path-limit
  80)
(defn- runtime-evidence-path-key
  [row]
  [(:repo row) (:path row)])

(defn- runtime-evidence-file-kind-key
  [row]
  (:fileKind row))

(defn- runtime-evidence-lane-key
  [row]
  [(:kind row) (:fileKind row)])

(defn- runtime-evidence-file-kind-counts
  [rows]
  (frequencies (keep runtime-evidence-file-kind-key rows)))

(defn- runtime-evidence-matched-token-counts
  [rows]
  (frequencies (mapcat ::matched-fact-tokens rows)))

(defn- unique-runtime-evidence-query-fact?
  [matched-token-counts row]
  (boolean
   (some (fn [token]
           (= 1 (long (or (get matched-token-counts token) 0))))
         (::matched-fact-tokens row))))

(defn- selected-runtime-evidence-ids
  [rows]
  (set (keep :id rows)))

(defn- runtime-evidence-file-kind-replacement-index
  [rows]
  (let [counts (runtime-evidence-file-kind-counts rows)]
    (->> rows
         (map-indexed vector)
         reverse
         (some (fn [[idx row]]
                 (let [file-kind (runtime-evidence-file-kind-key row)]
                   (when (< 1 (long (or (get counts file-kind) 0)))
                     idx)))))))

(defn- best-runtime-evidence-file-kind-candidate
  [rows selected-ids file-kind]
  (->> rows
       (filter #(= file-kind (runtime-evidence-file-kind-key %)))
       (remove #(contains? selected-ids (:id %)))
       first))

(defn- runtime-evidence-path-covered?
  [rows path]
  (boolean (some #(= path (:path %)) rows)))

(defn- best-runtime-evidence-path-candidate
  [rows selected-ids path]
  (->> rows
       (filter #(= path (:path %)))
       (remove #(contains? selected-ids (:id %)))
       first))

(defn- replaceable-runtime-evidence-path-row?
  [file-kind-counts matched-token-counts protected-paths candidate row]
  (let [file-kind (runtime-evidence-file-kind-key row)
        protected? (contains? protected-paths (:path row))
        unique-query-fact? (unique-runtime-evidence-query-fact?
                            matched-token-counts
                            row)
        candidate-covers-query-fact? (boolean
                                      (seq (set/intersection
                                            (or (::matched-fact-tokens row) #{})
                                            (or (::matched-fact-tokens candidate) #{}))))
        same-lane? (= (runtime-evidence-lane-key candidate)
                      (runtime-evidence-lane-key row))
        stronger-lane-candidate? (and same-lane?
                                      (< (double (or (:score row) 0.0))
                                         (double (or (:score candidate) 0.0))))]
    (or (and protected?
             stronger-lane-candidate?)
        (and (not protected?)
             (or (not unique-query-fact?)
                 candidate-covers-query-fact?
                 stronger-lane-candidate?)
             (or (= (runtime-evidence-file-kind-key candidate) file-kind)
                 (< 1 (long (or (get file-kind-counts file-kind) 0))))))))

(defn- runtime-evidence-path-replacement-index
  [selected protected-paths candidate]
  (let [file-kind-counts (runtime-evidence-file-kind-counts selected)
        matched-token-counts (runtime-evidence-matched-token-counts selected)]
    (->> selected
         (map-indexed vector)
         reverse
         (some (fn [[idx row]]
                 (when (replaceable-runtime-evidence-path-row?
                        file-kind-counts
                        matched-token-counts
                        protected-paths
                        candidate
                        row)
                   idx))))))

(defn- preserve-runtime-evidence-result-path-coverage
  [rows selected selected-path-order]
  (loop [selected (vec selected)
         missing-paths (vec selected-path-order)
         protected-paths #{}]
    (if-let [path (first missing-paths)]
      (let [protected-paths (if (runtime-evidence-path-covered? selected path)
                              (conj protected-paths path)
                              protected-paths)
            selected-ids (selected-runtime-evidence-ids selected)
            candidate (best-runtime-evidence-path-candidate rows selected-ids path)
            replace-idx (runtime-evidence-path-replacement-index selected
                                                                 protected-paths
                                                                 candidate)]
        (recur (if (and candidate
                        replace-idx
                        (not (runtime-evidence-path-covered? selected path)))
                 (assoc selected replace-idx candidate)
                 selected)
               (subvec missing-paths 1)
               (if candidate
                 (conj protected-paths path)
                 protected-paths)))
      selected)))

(defn- preserve-runtime-evidence-file-kind-diversity
  [rows selected]
  (let [file-kinds (->> rows
                        (keep runtime-evidence-file-kind-key)
                        distinct
                        vec)]
    (loop [selected (vec selected)
           missing-kinds (->> file-kinds
                              (remove (set (keys (runtime-evidence-file-kind-counts
                                                  selected))))
                              vec)]
      (if-let [file-kind (first missing-kinds)]
        (let [selected-ids (selected-runtime-evidence-ids selected)
              candidate (best-runtime-evidence-file-kind-candidate rows
                                                                   selected-ids
                                                                   file-kind)
              replace-idx (runtime-evidence-file-kind-replacement-index selected)]
          (recur (if (and candidate replace-idx)
                   (assoc selected replace-idx candidate)
                   selected)
                 (subvec missing-kinds 1)))
        selected))))

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

(defn- runtime-evidence-sort-key
  [row]
  [(- (double (or (:score row) 0.0)))
   (- (double (or (:confidence row) 0.0)))
   (:repo row)
   (:path row)
   (:sourceLine row)
   (:kind row)
   (:label row)])

(defn- take-diverse-system-evidence
  [rows limit selected-path-order]
  (let [initial-state {:seen-ids #{}
                       :path-counts {}
                       :system-counts {}
                       :out []}
        diverse-state (take-system-evidence-pass rows limit initial-state true)
        filled-state (if (< (count (:out diverse-state)) limit)
                       (take-system-evidence-pass rows limit diverse-state false)
                       diverse-state)
        file-kind-selected (preserve-runtime-evidence-file-kind-diversity
                            rows
                            (:out filled-state))]
    (preserve-runtime-evidence-result-path-coverage rows
                                                    file-kind-selected
                                                    selected-path-order)))
(defn select-system-evidence
  [query-tokens selected-system-ids results evidence limit]
  (let [selected-system-ids (set selected-system-ids)
        selected-path-order (ranked-result-path-order results
                                                      runtime-evidence-result-path-limit)
        selected-paths (set selected-path-order)
        selected-value-file-kinds (selected-evidence-value-file-kinds selected-paths
                                                                      evidence)
        token-path-counts (system-evidence-token-path-counts query-tokens
                                                             evidence)]
    (->> evidence
         (map (fn [row]
                (let [matched-fact-tokens (matched-query-tokens
                                           query-tokens
                                           (system-evidence-fact-text row))
                      rare-fact-token-score (rare-system-evidence-fact-token-score
                                             token-path-counts
                                             matched-fact-tokens)
                      score (system-evidence-score query-tokens
                                                   selected-system-ids
                                                   selected-paths
                                                   selected-value-file-kinds
                                                   token-path-counts
                                                   row)]
                  (when (pos? score)
                    (system-evidence-row score
                                         matched-fact-tokens
                                         rare-fact-token-score
                                         row)))))
         (keep identity)
         (sort-by runtime-evidence-sort-key)
         (#(take-diverse-system-evidence % limit selected-path-order))
         (map #(dissoc % ::matched-fact-tokens ::rare-fact-token-score))
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
  ([results accepted-systems candidate-systems]
   (selected-source-paths results accepted-systems candidate-systems nil))
  ([results accepted-systems candidate-systems candidate-inputs]
   (let [selected-inputs (if (seq candidate-inputs)
                           candidate-inputs
                           results)
         result-paths (ranked-result-paths selected-inputs
                                           dependency-result-path-limit)
         system-prefixes (mapcat system-source-prefixes
                                 (concat accepted-systems candidate-systems))]
     {:result-paths result-paths
      :system-prefixes system-prefixes})))
(defn- source-path-selected?
  [{:keys [result-paths system-prefixes]} source]
  (let [path (:path source)
        repo (:repo source)]
    (or (contains? result-paths path)
        (some (fn [prefix]
                (and (path-under? path (:path prefix))
                     (compatible-repo? (:repo prefix) repo)))
              system-prefixes))))
(defn- package-source-selected?
  [selection package]
  (some #(source-path-selected? selection %)
        (concat (:declared-by package)
                (:imported-by package))))
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
(defn- dependency-query-match?
  [query-tokens row]
  (pos? (dependency-token-score query-tokens row)))
(def ^:private package-import-source-limit
  4)
(defn- package-import-source-score
  [query-tokens package source]
  (dependency-token-score query-tokens
                          (assoc package
                                 :kind "package-import"
                                 :path (:path source)
                                 :import (:import-name source))))
(defn- package-import-sources
  [selection query-tokens package]
  (let [package-match? (dependency-query-match? query-tokens package)]
    (->> (:imported-by package)
         (keep
          (fn [source]
            (let [source-selected? (source-path-selected? selection source)]
              (when (or source-selected? package-match?)
                (assoc source
                       :source-selected? source-selected?
                       :source-score (package-import-source-score query-tokens
                                                                  package
                                                                  source))))))
         (sort-by (juxt (comp not :source-selected?)
                        (comp - #(double (or (:source-score %) 0.0)))
                        :path
                        :line))
         (take package-import-source-limit)
         vec)))
(defn- package-import-evidence-rows
  [selection query-tokens package]
  (let [sources (package-import-sources selection query-tokens package)]
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
                                  (:source-score source)))}
              (:kind source) (assoc :fileKind (display-name (:kind source)))
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
  [selection query-tokens package]
  (let [declared-by (mapv source-location-row (take 3 (:declared-by package)))]
    (when (and (seq declared-by)
               (or (package-source-selected? selection package)
                   (dependency-query-match? query-tokens package)))
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
  [selection query-tokens packages-by-id conflict]
  (let [package (get packages-by-id (:id conflict))]
    (when (or (and package
                   (package-source-selected? selection package))
              (dependency-query-match? query-tokens conflict))
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
                                                   :kind "package-version-conflict"))))})))
(defn- dependency-report-evidence
  ([query-tokens results accepted-systems candidate-systems dependency-report]
   (dependency-report-evidence query-tokens
                               results
                               accepted-systems
                               candidate-systems
                               dependency-report
                               nil))
  ([query-tokens results accepted-systems candidate-systems dependency-report candidate-inputs]
   (let [query-tokens (distinct-query-tokens query-tokens)
         selection (selected-source-paths results
                                          accepted-systems
                                          candidate-systems
                                          candidate-inputs)
         packages-by-id (into {} (map (juxt :id identity))
                              (:packages dependency-report))]
     (->> (concat
           (mapcat #(package-import-evidence-rows selection query-tokens %)
                   (:packages dependency-report))
           (keep #(unresolved-import-evidence-row selection query-tokens %)
                 (:unresolved-imports dependency-report))
           (map #(declared-without-import-evidence-row selection query-tokens %)
                (:declared-without-import-evidence dependency-report))
           (map #(version-conflict-evidence-row selection query-tokens packages-by-id %)
                (:version-conflicts dependency-report)))
          (keep identity)
          (sort-by (juxt (comp - #(double (or (:score %) 0.0)))
                         :kind
                         :ecosystem
                         :package
                         :path
                         :sourceLine
                         :id))
          vec))))
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
                     :createdAtMs
                     :resultSchemaStatus
                     :events
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
  [evidence plane status]
  (let [actions (matching-plane-actions (:nextActions evidence) plane)]
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
  [evidence freshness docs]
  (vec (concat (when-let [gap (freshness-validation-gap-row freshness)]
                 [gap])
               (when-let [gap (stale-doc-validation-gap docs)]
                 [gap])
               (map (fn [plane] (validation-gap-row evidence plane "missing"))
                    (:missing evidence))
               (map (fn [plane] (validation-gap-row evidence plane "weak"))
                    (:weak evidence))
               (map (fn [plane] (validation-gap-row evidence plane "unsupported"))
                    (:unsupported evidence)))))
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
    :source-keys [:acceptedSystems :mapEdges :rejectedCorrections]
    :planes [:map-overlay]}
   {:family "maintenance"
    :source-keys [:openDecisions]
    :planes [:activity]}])
(defn- family-plane-status
  [evidence plane]
  (cond
    (contains? (set (:weak evidence)) plane) "weak"
    (contains? (set (:missing evidence)) plane) "missing"
    (contains? (set (:unsupported evidence)) plane) "unsupported"
    (contains? (set (:available evidence)) plane) "available"))
(defn- family-plane-rows
  [evidence planes]
  (->> planes
       (keep (fn [plane]
               (when-let [status (family-plane-status evidence plane)]
                 {:plane (name plane)
                  :status status})))
       vec))
(defn- architecture-source-count
  [section source-key]
  (case source-key
    :mapEdges (count (filter #(= "map-edge" (:kind %))
                             (:boundaryEvidence section)))
    (count (get section source-key))))
(defn- architecture-source-rows
  [section source-key]
  (case source-key
    :mapEdges (filter #(= "map-edge" (:kind %))
                      (:boundaryEvidence section))
    (get section source-key)))
(defn- architecture-source-counts
  [section source-keys]
  (->> source-keys
       (keep (fn [source-key]
               (let [n (architecture-source-count section source-key)]
                 (when (pos? n)
                   {:key (name source-key)
                    :count n}))))
       vec))
(defn- architecture-row-file-kind
  [row]
  (some-> (or (:fileKind row)
              (:file-kind row)
              (:sourceKind row)
              (:source-kind row))
          display-name))
(defn- architecture-source-file-kinds
  [section source-keys]
  (->> source-keys
       (mapcat #(architecture-source-rows section %))
       (keep architecture-row-file-kind)
       frequencies
       (map (fn [[kind n]]
              {:kind kind
               :count n}))
       (sort-by (juxt (comp - :count) :kind))
       vec))
(defn- family-status
  [row-count plane-rows]
  (cond
    (pos? row-count) "available"
    (some #(= "weak" (:status %)) plane-rows) "weak"
    (some #(= "missing" (:status %)) plane-rows) "missing"
    (some #(= "unsupported" (:status %)) plane-rows) "unsupported"))
(defn- architecture-evidence-family-row
  [section evidence {:keys [family source-keys planes]}]
  (let [source-counts (architecture-source-counts section source-keys)
        file-kinds (architecture-source-file-kinds section source-keys)
        row-count (reduce + 0 (map :count source-counts))
        plane-rows (family-plane-rows evidence planes)
        status (family-status row-count plane-rows)]
    (when status
      (cond-> {:family family
               :status status
               :rowCount row-count}
        (seq source-counts) (assoc :sourceCounts source-counts)
        (seq file-kinds) (assoc :fileKinds file-kinds)
        (seq plane-rows) (assoc :planes plane-rows)))))
(defn- architecture-evidence-families
  [section evidence]
  (->> architecture-family-specs
       (keep #(architecture-evidence-family-row section evidence %))
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
              :openDecisions
              :rejectedCorrections])))
(defn- inspect-action-label
  [prefix row]
  (str prefix " " (or (:label row) (:id row))))
(defn- inspect-action
  [target label reason]
  (when-let [target (some-> target s str/trim not-empty)]
    {:kind :inspect
     :label label
     :target target
     :mcpTool "ygg_node"
     :mcpArgs {:target target}
     :reason reason}))
(defn- dependency-inspect-action
  [evidence]
  (let [target (or (:packageId evidence)
                   (:target evidence)
                   (:source evidence)
                   (:id evidence))]
    (inspect-action target
                    (compact "Inspect dependency"
                             (:relation evidence)
                             "target"
                             target)
                    "Open dependency endpoint with incident graph evidence")))
(defn- open-decision-inspect-action
  [decision]
  (let [work-id (or (:sourceId decision)
                    (:id decision))]
    (when-let [work-id (some-> work-id s str/trim not-empty)]
      {:kind :work-review
       :label (compact "Inspect open decision" work-id)
       :target work-id
       :command (str "ygg sync work show " work-id)
       :mcpTool "ygg_work_show"
       :mcpArgs {:workId work-id}
       :reason "Open queued review packet before accepting or rejecting architecture evidence"})))
(defn- architecture-doc-inspect-action
  [doc]
  (let [target (or (get-in doc [:source :path])
                   (:target doc))]
    (inspect-action target
                    (compact "Inspect architecture doc" target)
                    "Open accepted architecture doc source and attached map evidence")))
(defn- architecture-inspect-actions
  [accepted-systems candidate-systems runtime-evidence dependency-evidence open-decisions docs]
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
    (keep open-decision-inspect-action
          (take 2 open-decisions))
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
            :rejectedCorrections (count (:rejectedCorrections section))
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
  [evidence freshness]
  (vec (take 5 (concat (:warnings freshness)
                       (:warnings evidence)))))
(defn architecture-section
  [{:keys [overlay entities results candidate-inputs edges runtime-evidence dependency-report docs activity evidence freshness
           accepted-systems query-tokens]}]
  (let [accepted-systems (or accepted-systems
                             (selected-accepted-systems overlay entities results))
        candidate-systems (selected-candidate-systems accepted-systems entities)
        selected-rows (concat entities
                              accepted-systems
                              candidate-systems
                              results)
        rejected-corrections (selected-rejected-corrections overlay selected-rows)
        selected-ids (set (concat (map :id entities)
                                  (map :id accepted-systems)))
        map-edges (selected-map-edges overlay entities accepted-systems)
        boundary-evidence (vec (take 12
                                     (ranked-evidence
                                      selected-ids
                                      (concat map-edges
                                              (map graph-edge-evidence-row edges)))))
        dependency-rows (ranked-evidence
                         selected-ids
                         (concat
                          (map graph-edge-evidence-row
                               (filter dependency-evidence? edges))
                          (dependency-report-evidence
                           query-tokens
                           results
                           accepted-systems
                           candidate-systems
                           dependency-report
                           candidate-inputs)))
        dependency-evidence (vec
                             (preserve-query-supported-dependency-identity
                              query-tokens
                              dependency-rows
                              (preserve-evidence-result-path-coverage
                               dependency-rows
                               (take dependency-evidence-display-limit
                                     dependency-rows)
                               (ranked-result-path-order
                                (if (seq candidate-inputs)
                                  candidate-inputs
                                  results)
                                dependency-result-path-limit))))
        deploy-evidence (deploy-evidence-rows runtime-evidence)
        architecture-docs (mapv architecture-doc-row
                                (take 8 (filter accepted-architecture-doc? docs)))
        open-decisions (mapv open-decision-row
                             (take 6 (filter open-decision? activity)))
        inspect-actions (architecture-inspect-actions accepted-systems
                                                      candidate-systems
                                                      runtime-evidence
                                                      dependency-evidence
                                                      open-decisions
                                                      architecture-docs)
        section {:basis "mechanical-plus-map"
                 :acceptedSystems accepted-systems
                 :candidateSystems candidate-systems
                 :boundaryEvidence boundary-evidence
                 :runtimeEvidence runtime-evidence
                 :deployEvidence deploy-evidence
                 :dependencyEvidence dependency-evidence
                 :docs architecture-docs
                 :openDecisions open-decisions
                 :rejectedCorrections rejected-corrections
                 :validationGaps (vec (take 12
                                            (validation-gaps evidence
                                                             freshness
                                                             architecture-docs)))
                 :warnings (architecture-warnings evidence freshness)
                 :nextActions (vec (take 6 (concat inspect-actions
                                                   (freshness-next-actions freshness)
                                                   (:nextActions evidence))))}
        section (assoc section
                       :evidenceFamilies
                       (architecture-evidence-families section evidence))
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
                       :candidateTypes
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
