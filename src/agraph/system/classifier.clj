(ns agraph.system.classifier
  "LLM-assisted candidate classification into an editable graph map overlay."
  (:require [agraph.hash :as hash]
            [agraph.map :as graph-map]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema
  "agraph.system.classification/v1")

(def default-cache-dir
  ".dev/agraph/classifier-cache")

(def semantic-kinds
  ["application"
   "client"
   "external-api"
   "infrastructure"
   "integration"
   "library"
   "package"
   "repository"
   "service"
   "tool"
   "unknown"])

(defn- kname
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(defn- candidate-node?
  [system]
  (contains? #{:candidate-system :repo-boundary} (:kind system)))

(defn- edge-counts
  [system-id edges]
  {:incoming (count (filter #(= system-id (:target-id %)) edges))
   :outgoing (count (filter #(= system-id (:source-id %)) edges))})

(defn- candidate-entry
  [edges system]
  (let [counts (edge-counts (:xt/id system) edges)]
    (cond-> {:id (:xt/id system)
             :label (:label system)
             :repo (:repo-id system)
             :repoRole (kname (:repo-role system))
             :structuralKind (kname (:kind system))
             :candidateTypes (mapv kname (:candidate-types system))
             :pathPrefix (:path-prefix system)
             :metrics (:metrics system)
             :edgeCounts counts}
      (seq (:aliases system)) (assoc :aliases (:aliases system))
      (seq (:evidence system)) (assoc :evidence (take 8 (:evidence system))))))

(defn payload
  [project-id systems edges {:keys [limit] :or {limit 200}}]
  (let [limit (or limit 200)]
    {:schema schema
     :project project-id
     :rules {:rawGraphIsSourceOfTruth true
             :doNotUsePathRegexSemantics true
             :classifyOnlyFromProvidedEvidence true
             :semanticKinds semantic-kinds}
     :candidates (->> systems
                      (filter candidate-node?)
                      (sort-by (juxt :repo-id :label))
                      (take limit)
                      (mapv #(candidate-entry edges %)))}))

(defn input-sha
  [payload]
  (hash/sha256-hex (json/write-json-str payload)))

(defn prompt
  [payload]
  [{:role "system"
    :content (str "You classify deterministic software architecture candidates for AGraph. "
                  "Return JSON only. Do not invent source files or edges. "
                  "Do not classify by path-name rules alone; use the provided structural evidence, metrics, manifests, and dependency shape. "
                  "If evidence is weak, keep status candidate or use kind unknown.")}
   {:role "user"
    :content (str
              "Classify these candidate systems into an editable graph map overlay.\n"
              "Allowed semantic kinds: " (str/join ", " semantic-kinds) ".\n"
              "Return this exact JSON shape:\n"
              "{"
              "\"systems\":[{\"id\":\"candidate id\",\"label\":\"human label\","
              "\"kind\":\"one allowed semantic kind\",\"status\":\"accepted|candidate\","
              "\"confidence\":0.0,\"reason\":\"brief rationale\",\"aliases\":[],\"tags\":[]}],"
              "\"reject\":[{\"id\":\"candidate id\",\"reason\":\"brief rationale\"}]"
              "}\n\n"
              (json/write-json-str payload {:indent-str "  "}))}])

(defn- cache-path
  [cache-dir sha]
  (.getPath (io/file cache-dir (str sha ".json"))))

(defn- read-cache
  [cache-dir sha]
  (let [file (io/file (cache-path cache-dir sha))]
    (when (.isFile file)
      (json/read-json (slurp file) :key-fn keyword))))

(defn- write-cache!
  [cache-dir sha data]
  (let [file (io/file (cache-path cache-dir sha))]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file (str (json/write-json-str data {:indent-str "  "}) "\n"))
    data))

(defn classify
  "Return raw structured classification response, cached by candidate payload hash."
  [{:keys [client project project-id systems edges cache-dir limit]
    :or {cache-dir default-cache-dir}}]
  (let [project-id (or project-id project)
        payload (payload project-id systems edges {:limit limit})
        sha (input-sha payload)]
    (or (read-cache cache-dir sha)
        (let [response ((:complete-json client) (prompt payload))
              data {:schema schema
                    :project project-id
                    :provider (kname (:provider client))
                    :model (:model client)
                    :input-sha sha
                    :payload payload
                    :response response}]
          (write-cache! cache-dir sha data)))))

(defn- source-include
  [candidate]
  (cond-> {:repo (:repo candidate)}
    (:pathPrefix candidate) (assoc :path (:pathPrefix candidate))))

(defn- normalize-status
  [value]
  (let [value (str/lower-case (str value))]
    (if (#{"accepted" "candidate"} value)
      value
      "candidate")))

(defn- normalize-kind
  [value]
  (let [value (str/lower-case (str value))]
    (if (some #{value} semantic-kinds)
      value
      "unknown")))

(defn- system-entry
  [classification candidate meta]
  (cond-> {:id (:id candidate)
           :label (or (:label classification) (:label candidate))
           :kind (normalize-kind (:kind classification))
           :status (normalize-status (:status classification))
           :confidence (:confidence classification)
           :reason (:reason classification)
           :includes [(source-include candidate)]
           :aliases (vec (:aliases classification))
           :tags (vec (:tags classification))
           :candidateId (:id candidate)
           :candidateTypes (:candidateTypes candidate)
           :metrics (:metrics candidate)
           :provenance meta}
    (nil? (:confidence classification)) (dissoc :confidence)
    (str/blank? (str (:reason classification))) (dissoc :reason)
    (empty? (:aliases classification)) (dissoc :aliases)
    (empty? (:tags classification)) (dissoc :tags)))

(defn overlay
  "Convert classifier output into agraph.map/v1 overlay data."
  [{:keys [project] :as args}]
  (let [classification (classify args)
        candidates (:candidates (:payload classification))
        by-id (into {} (map (juxt :id identity)) candidates)
        response (:response classification)
        meta (select-keys classification [:schema :provider :model :input-sha])
        classified (->> (:systems response)
                        (keep (fn [entry]
                                (when-let [candidate (get by-id (:id entry))]
                                  (system-entry entry candidate meta))))
                        vec)
        rejects (->> (:reject response)
                     (keep (fn [entry]
                             (when-let [candidate (get by-id (:id entry))]
                               (cond-> {:match {:id (:id candidate)}
                                        :provenance meta}
                                 (:reason entry) (assoc :reason (:reason entry))))))
                     vec)]
    {:schema graph-map/schema
     :project project
     :systems classified
     :reject rejects
     :edges []
     :docs []
     :classification meta
     :candidate-count (count candidates)
     :updated-at-ms (graph-map/now-ms)}))
