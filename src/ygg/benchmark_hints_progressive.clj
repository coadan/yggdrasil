(ns ygg.benchmark-hints-progressive
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def compact-profile
  "compact-v1")

(def default-limits
  {:top-files 10
   :top-symbols 5
   :top-docs 5
   :related-files 12
   :import-packages 6
   :import-package-files 12
   :prepared-localization-candidates 12
   :prepared-localization-declarations 4
   :top-declarations 16
   :candidate-systems 6
   :commands 6
   :audit-scopes 3
   :evidence-per-row 1
   :reason-chars 220
   :read-plan-files 5
   :snippet-before-lines 20
   :snippet-after-lines 12
   :snippet-max-chars 2400})

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

(defn- compact-related-file
  [limits row]
  (-> row
      (select-keys [:rank :path :repoId :repo :sourceLine :relation :reason :evidence :via])
      (compact-reason limits)
      (compact-row-evidence limits)
      (update :via #(takev 2 %))))

(defn- compact-import-package
  [limits row]
  (-> row
      (select-keys [:rank :packagePrefix :target :relation :seedPaths :evidence :files])
      (compact-row-evidence limits)
      (update :seedPaths #(takev 4 %))
      (update :files #(mapv (fn [file]
                              (select-keys file [:path :repoId :repo :kind]))
                            (take (:import-package-files limits) %)))))

(defn- compact-symbol
  [limits row]
  (-> row
      (select-keys [:rank :name :path :repoId :repo :kind :confidence :reason :evidence])
      (compact-reason limits)
      (compact-row-evidence limits)))

(defn- compact-declaration
  [limits row]
  (-> row
      (select-keys [:rank
                    :sourceRank
                    :path
                    :repoId
                    :repo
                    :label
                    :kind
                    :targetKind
                    :resultKind
                    :sourceLine
                    :endLine
                    :score
                    :matchedTokens
                    :supportLabels
                    :evidence])
      (update :matchedTokens #(takev 6 %))
      (update :supportLabels #(takev 4 %))
      (compact-row-evidence limits)))

(defn- compact-prepared-localization-declaration
  [row]
  (select-keys row [:rank
                    :path
                    :repoId
                    :repo
                    :label
                    :kind
                    :sourceLine
                    :endLine
                    :matchedTokens]))

(defn- compact-prepared-localization-candidate
  [limits row]
  (-> row
      (select-keys [:rank
                    :path
                    :repoId
                    :confidence
                    :reason
                    :evidence
                    :declarations
                    :metrics])
      (compact-reason limits)
      (compact-row-evidence limits)
      (update :declarations #(mapv compact-prepared-localization-declaration
                                   (take (:prepared-localization-declarations limits)
                                         %)))))

(defn- compact-prepared-localization
  [prepared-localization limits]
  (when (seq (:candidates prepared-localization))
    (-> prepared-localization
        (select-keys [:basis :candidates])
        (update :candidates #(mapv (fn [candidate]
                                     (compact-prepared-localization-candidate
                                      limits
                                      candidate))
                                   (take (:prepared-localization-candidates limits)
                                         %))))))

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

(defn- roots-by-repo
  [roots]
  (cond
    (map? roots)
    roots

    (sequential? roots)
    (into {}
          (keep (fn [{:keys [id root]}]
                  (when (and id root)
                    [id root])))
          roots)

    :else
    nil))

(defn- row-root
  [opts row]
  (or (get (roots-by-repo (:roots opts))
           (or (:repoId row) (:repo-id row) (:repo row)))
      (:root opts)))

(defn- parse-line-range
  [value]
  (when-let [[_ start end] (re-find #" lines (\d+)(?:-(\d+))?" (str value))]
    {:start (Long/parseLong start)
     :end (Long/parseLong (or end start))}))

(defn- row-line-range
  [row]
  (or (some parse-line-range (:evidence row))
      (when-let [start (or (:sourceLine row) (:source-line row))]
        {:start (long start)
         :end (long (or (:endLine row) (:end-line row) start))})))

(defn- bounded-range
  [line-range limits]
  (let [start (long (:start line-range))
        end (long (:end line-range))]
    {:start (max 1 (- start (long (:snippet-before-lines limits))))
     :end (+ end (long (:snippet-after-lines limits)))}))

(defn- read-snippet
  [root path {:keys [start end]} limits]
  (when (and root path start end)
    (let [file (io/file root path)]
      (when (.isFile file)
        (with-open [reader (io/reader file)]
          (let [lines (line-seq reader)
                snippet (->> lines
                             (map-indexed (fn [idx line]
                                            [(inc idx) line]))
                             (drop-while #(< (first %) start))
                             (take-while #(<= (first %) end))
                             (map (fn [[line-no line]]
                                    (format "%d: %s" line-no line)))
                             (str/join "\n"))]
            (bounded-text (:snippet-max-chars limits) snippet)))))))

(defn- shell-quote
  [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))

(defn- read-plan-row
  [opts limits row]
  (let [path (:path row)
        root (row-root opts row)
        line-range (row-line-range row)
        snippet-range (when line-range
                        (bounded-range line-range limits))
        snippet (read-snippet root path snippet-range limits)]
    (when (and path snippet-range snippet)
      (cond-> {:rank (:rank row)
               :path path
               :lines snippet-range
               :command (str "sed -n "
                             (shell-quote (str (:start snippet-range)
                                               ","
                                               (:end snippet-range)
                                               "p"))
                             " "
                             (shell-quote path))
               :snippet snippet}
        (:repoId row) (assoc :repoId (:repoId row))
        (:repo-id row) (assoc :repoId (:repo-id row))
        (:repo row) (assoc :repo (:repo row))
        (:reason row) (assoc :reason (bounded-text (:reason-chars limits)
                                                   (:reason row)))))))

(defn- row-path-key
  [row]
  [(or (:repoId row) (:repo-id row) (:repo row)) (:path row)])

(defn- line-start
  [row]
  (long (or (:start (row-line-range row)) Long/MAX_VALUE)))

(defn- path-diverse-declaration-rows
  [rows]
  (let [rows (vec rows)
        rows-by-path (group-by row-path-key rows)
        path-order (distinct (map row-path-key rows))]
    (mapv (fn [path-key]
            (->> (get rows-by-path path-key)
                 (sort-by (juxt line-start
                                #(long (or (:rank %) Long/MAX_VALUE))))
                 first))
          path-order)))

(defn- prepared-localization-read-plan-rows
  [hints limits]
  (->> (get-in hints [:preparedLocalization :candidates])
       (keep (fn [candidate]
               (when-let [declaration (first (:declarations candidate))]
                 (let [source-line (or (:sourceLine declaration)
                                       (:source-line declaration))]
                   (cond-> (merge declaration
                                  (select-keys candidate [:rank :reason]))
                     (and source-line
                          (nil? (:endLine declaration))
                          (nil? (:end-line declaration)))
                     (assoc :endLine (+ (long source-line)
                                        (long (:snippet-after-lines limits)))))))))
       path-diverse-declaration-rows))

(defn- compact-read-plan
  [hints opts limits]
  (let [prepared-rows (prepared-localization-read-plan-rows hints limits)
        prepared-paths (set (keep :path prepared-rows))
        top-files (remove #(contains? prepared-paths (:path %))
                          (:topFiles hints))
        top-paths (into prepared-paths (keep :path top-files))
        declaration-rows (->> (:topDeclarations hints)
                              (remove #(contains? top-paths (:path %)))
                              path-diverse-declaration-rows)
        read-plan-rows (->> (concat prepared-rows
                                    top-files
                                    declaration-rows
                                    (:relatedFiles hints))
                            (remove (fn [row]
                                      (str/blank? (str (:path row)))))
                            (reduce (fn [[rows seen] row]
                                      (let [path (:path row)
                                            line-range (row-line-range row)
                                            row-key [(:repoId row)
                                                     (:repo row)
                                                     path
                                                     line-range]]
                                        (if (contains? seen row-key)
                                          [rows seen]
                                          [(conj rows row) (conj seen row-key)])))
                                    [[] #{}])
                            first)
        snippets (->> read-plan-rows
                      (take (:read-plan-files limits))
                      (keep #(read-plan-row opts limits %))
                      vec)]
    (when (seq snippets)
      {:basis "bounded snippets from compact topFiles, parser/source-graph declarations, and graph-related files; inspect these before broad search or full context expansion"
       :rules ["Do not print entire Yggdrasil JSON artifacts."
               "Use compact projections and these snippets before broad rg."
               "Open full hints or context only when compact hints do not provide enough evidence."]
       :snippets snippets})))

(defn- section-counts
  [hints]
  {:topFiles (count (:topFiles hints))
   :topSymbols (count (:topSymbols hints))
   :topDeclarations (count (:topDeclarations hints))
   :topDocs (count (:topDocs hints))
   :relatedFiles (count (:relatedFiles hints))
   :importPackages (count (:importPackages hints))
   :preparedLocalization (count (get-in hints [:preparedLocalization :candidates]))
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
         read-plan (compact-read-plan hints opts limits)
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
                           :search
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
       (seq (:topDeclarations hints))
       (assoc :topDeclarations (mapv #(compact-declaration limits %)
                                     (take (:top-declarations limits)
                                           (:topDeclarations hints))))
       (seq (:topDocs hints))
       (assoc :topDocs (mapv compact-doc
                             (take (:top-docs limits) (:topDocs hints))))
       (seq (get-in hints [:preparedLocalization :candidates]))
       (assoc :preparedLocalization
              (compact-prepared-localization (:preparedLocalization hints)
                                             limits))
       (seq (:relatedFiles hints))
       (assoc :relatedFiles (mapv #(compact-related-file limits %)
                                  (take (:related-files limits)
                                        (:relatedFiles hints))))
       (seq (:importPackages hints))
       (assoc :importPackages (mapv #(compact-import-package limits %)
                                    (take (:import-packages limits)
                                          (:importPackages hints))))
       (seq (:candidateSystems hints))
       (assoc :candidateSystems (mapv compact-system
                                      (take (:candidate-systems limits)
                                            (:candidateSystems hints))))
       read-plan
       (assoc :readPlan read-plan)
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
