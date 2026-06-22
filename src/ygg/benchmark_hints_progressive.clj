(ns ygg.benchmark-hints-progressive
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def compact-profile
  "compact-v1")

(def default-limits
  {:top-files 10
   :top-symbols 5
   :top-docs 5
   :candidate-systems 3
   :commands 6
   :audit-scopes 3
   :evidence-per-row 1
   :reason-chars 220})

(defn full-hints-path
  [hints-path]
  (let [file (io/file hints-path)
        parent (.getParentFile file)
        name (.getName file)
        full-name (if (str/ends-with? name ".ygg-hints.json")
                    (str/replace name #"\.ygg-hints\.json$" ".ygg-hints.full.json")
                    (str name ".full.json"))]
    (if parent
      (io/file parent full-name)
      (io/file full-name))))

(defn- canonical-path
  [path]
  (when path
    (try
      (.getCanonicalPath (io/file path))
      (catch Exception _
        (str path)))))

(defn- takev
  [n coll]
  (vec (take n coll)))

(defn- bounded-text
  [limit value]
  (when (some? value)
    (let [text (str value)
          limit (long limit)]
      (if (<= (count text) limit)
        text
        (str (subs text 0 (max 0 (- limit 14))) " [truncated]")))))

(defn- compact-row-evidence
  [row limits]
  (let [evidence (vec (:evidence row))
        evidence-limit (:evidence-per-row limits)]
    (cond-> (dissoc row :evidence)
      (seq evidence)
      (assoc :evidence (takev evidence-limit evidence))
      (> (count evidence) evidence-limit)
      (assoc :evidenceCount (count evidence)))))

(defn- compact-reason
  [row limits]
  (if (contains? row :reason)
    (assoc row :reason (bounded-text (:reason-chars limits) (:reason row)))
    row))

(defn- compact-top-file
  [limits row]
  (-> row
      (select-keys [:rank :path :repoId :repo :confidence :reason :evidence])
      (compact-reason limits)
      (compact-row-evidence limits)))

(defn- compact-symbol
  [limits row]
  (-> row
      (select-keys [:rank :name :path :repoId :repo :kind :confidence :reason :evidence])
      (compact-reason limits)
      (compact-row-evidence limits)))

(defn- compact-doc
  [row]
  (cond-> (select-keys row
                       [:rank
                        :path
                        :repo
                        :heading
                        :kind
                        :definitionKind
                        :score
                        :provenance
                        :lines])
    (contains? row :snippet)
    (assoc :snippetAvailable true)))

(defn- compact-system
  [row]
  (select-keys row [:rank :id :repo :path :label :kind :score :metrics]))

(defn- compact-action
  [row]
  (select-keys row [:kind :label :target :mcpTool :mcpArgs :command :reason]))

(defn- compact-architecture
  [architecture limits]
  (when architecture
    (cond-> {:summary (:summary architecture)}
      (seq (:validationGaps architecture))
      (assoc :validationGaps (takev 3 (:validationGaps architecture)))
      (seq (:nextActions architecture))
      (assoc :nextActions (mapv compact-action
                                (take (:commands limits)
                                      (:nextActions architecture))))
      (seq (:warnings architecture))
      (assoc :warnings (takev 3 (:warnings architecture))))))

(defn- compact-audit-scope
  [scope]
  (select-keys scope
               [:kind
                :basis
                :facts
                :supportedFiles
                :skippedFiles
                :diagnostics
                :overlayCount
                :topEvidenceTypes]))

(defn- compact-evidence
  [evidence]
  (when evidence
    (select-keys evidence
                 [:status
                  :available
                  :weak
                  :missing
                  :unsupported
                  :counts
                  :warnings
                  :nextActions])))

(defn- compact-source-coverage
  [source-coverage]
  (when source-coverage
    (select-keys source-coverage [:totals :indexedConnectivity])))

(defn- section-counts
  [hints]
  {:topFiles (count (:topFiles hints))
   :topSymbols (count (:topSymbols hints))
   :topDocs (count (:topDocs hints))
   :candidateSystems (count (:candidateSystems hints))
   :commands (count (:commands hints))
   :auditScopes (count (:auditScopes hints))})

(defn compact-agent-hints
  "Return the first-pass benchmark hint packet.

  The full hint packet and context packet stay available as expansion artifacts;
  this projection keeps enough ranked evidence to start while avoiding a large
  automatic context load.
  "
  ([hints]
   (compact-agent-hints hints {}))
  ([hints opts]
   (let [limits (merge default-limits (:limits opts))
         progressive {:profile compact-profile
                      :limits limits
                      :fullHintsPath (canonical-path (:full-hints-path opts))
                      :contextPath (canonical-path (:context-path opts))
                      :env {:context "YGG_BENCH_YGG_CONTEXT"
                            :hints "YGG_BENCH_YGG_HINTS"}
                      :sourceCounts (section-counts hints)}]
     (cond-> (select-keys hints
                          [:schema
                           :suite-id
                           :case-id
                           :repo-id
                           :project-id
                           :query
                           :selection
                           :warnings
                           :diagnostics])
       true
       (assoc :progressive progressive)
       (seq (:topFiles hints))
       (assoc :topFiles (mapv #(compact-top-file limits %)
                              (take (:top-files limits) (:topFiles hints))))
       (seq (:topSymbols hints))
       (assoc :topSymbols (mapv #(compact-symbol limits %)
                                (take (:top-symbols limits) (:topSymbols hints))))
       (seq (:topDocs hints))
       (assoc :topDocs (mapv compact-doc
                             (take (:top-docs limits) (:topDocs hints))))
       (seq (:candidateSystems hints))
       (assoc :candidateSystems (mapv compact-system
                                      (take (:candidate-systems limits)
                                            (:candidateSystems hints))))
       (seq (:commands hints))
       (assoc :commands (takev (:commands limits) (:commands hints)))
       (:architecture hints)
       (assoc :architecture (compact-architecture (:architecture hints) limits))
       (seq (:auditScopes hints))
       (assoc :auditScopes (mapv compact-audit-scope
                                 (take (:audit-scopes limits)
                                       (:auditScopes hints))))
       (:evidence hints)
       (assoc :evidence (compact-evidence (:evidence hints)))
       (:sourceCoverage hints)
       (assoc :sourceCoverage (compact-source-coverage (:sourceCoverage hints)))))))
