(ns ygg.benchmark-hints
  (:require [ygg.benchmark-prediction :as benchmark-prediction]
            [ygg.text :as text]
            [clojure.set :as set]
            [clojure.string :as str]))

(def agent-hints-schema
  "ygg.benchmark.agent-hints/v1")

(def default-agent-baseline-suspect-limit
  20)

(def ^:private prepared-localization-limit
  12)

(def ^:private prepared-localization-declaration-slots
  8)

(def ^:private prepared-localization-file-slots
  4)

(def ^:private prepared-localization-declaration-limit
  4)

(defn- source-line-range
  [source]
  (when-let [lines (seq (:lines source))]
    {:start (first lines)
     :end (last lines)}))
(defn- field-name
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))
(defn- source-line-label
  [row]
  (when-let [source-line (or (:sourceLine row)
                             (:source-line row))]
    (str " lines "
         source-line
         (when-let [end-line (or (:endLine row)
                                 (:end-line row))]
           (str "-" end-line)))))
(defn- declaration-text
  [row]
  (str/join "\n"
            (remove str/blank?
                    (map str
                         (concat [(:path row)
                                  (:label row)
                                  (:kind row)]
                                 (:supportLabels row))))))
(defn- matched-query-tokens
  [query-tokens row]
  (->> (set/intersection (set query-tokens)
                         (set (text/tokenize (declaration-text row))))
       sort
       vec))
(defn- declaration-evidence
  [row]
  (str "source-declaration:"
       (:path row)
       (when-let [rank (:sourceRank row)]
         (str " sourceRank=" rank))
       (source-line-label row)
       (when-let [kind (field-name (:kind row))]
         (str " kind=" kind))
       (when-let [label (not-empty (str (:label row)))]
         (str " label=" (pr-str label)))
       (when-let [support-labels (seq (:supportLabels row))]
         (str " supportLabels=" (pr-str (vec support-labels))))
       (when-some [score (:score row)]
         (str " score=" score))))
(defn- hint-doc
  [idx doc]
  (let [source (:source doc)]
    (cond-> {:rank (inc idx)
             :path (:path source)
             :heading (:heading source)
             :kind (:kind source)
             :definitionKind (:definitionKind source)
             :score (:score doc)
             :provenance (:provenance doc)
             :snippet (:snippet doc)}
      (:repo source) (assoc :repo (:repo source))
      (source-line-range source) (assoc :lines (source-line-range source)))))
(defn- hint-system
  [idx entity]
  (select-keys (assoc entity :rank (inc idx))
               [:rank :id :repo :path :label :kind :score :why :metrics :pathPrefix
                :candidateTypes :candidateEvidence]))
(defn- hint-related-file
  [idx row]
  (select-keys (assoc row :rank (inc idx))
               [:rank
                :path
                :repoId
                :repo
                :sourceLine
                :relation
                :reason
                :evidence
                :via]))
(defn- takev
  [n coll]
  (vec (take n coll)))
(defn- hint-architecture
  [architecture]
  (when architecture
    (-> architecture
        (select-keys [:basis
                      :acceptedSystems
                      :candidateSystems
                      :rejectedCorrections
                      :boundaryEvidence
                      :runtimeEvidence
                      :deployEvidence
                      :dependencyEvidence
                      :docs
                      :openDecisions
                      :evidenceFamilies
                      :summary
                      :validationGaps
                      :warnings
                      :nextActions])
        (update :acceptedSystems #(takev 5 %))
        (update :candidateSystems #(takev 5 %))
        (update :rejectedCorrections #(takev 5 %))
        (update :boundaryEvidence #(takev 5 %))
        (update :runtimeEvidence #(takev 5 %))
        (update :deployEvidence #(takev 5 %))
        (update :dependencyEvidence #(takev 5 %))
        (update :docs #(takev 5 %))
        (update :openDecisions #(takev 3 %))
        (update :evidenceFamilies #(takev 8 %))
        (update :validationGaps #(takev 6 %))
        (update :warnings #(takev 3 %))
        (update :nextActions #(takev 4 %)))))
(defn- hint-audit-scope
  [scope]
  (-> scope
      (select-keys [:kind
                    :basis
                    :facts
                    :files
                    :supportedFiles
                    :skippedFiles
                    :diagnostics
                    :overlayCount
                    :topEvidenceTypes
                    :samples])
      (update :topEvidenceTypes #(takev 5 %))
      (update :samples #(takev 3 %))))

(defn- hint-import-package
  [idx package]
  (-> package
      (select-keys [:packagePrefix :target :relation :seedPaths :evidence :files])
      (assoc :rank (inc idx))
      (update :seedPaths #(takev 4 %))
      (update :evidence #(takev 1 %))
      (update :files #(mapv (fn [file]
                              (select-keys file [:path :repoId :repo :kind]))
                            (take 12 %)))))
(defn- hint-declaration
  [query-tokens idx row]
  (let [matched-tokens (matched-query-tokens query-tokens row)]
    (cond-> (select-keys row
                         [:path
                          :repoId
                          :repo
                          :label
                          :kind
                          :targetKind
                          :resultKind
                          :sourceRank
                          :sourceLine
                          :endLine
                          :score])
      true
      (assoc :rank (inc idx)
             :matchedTokens matched-tokens
             :supportLabels (vec (take 4 (:supportLabels row)))
             :evidence [(declaration-evidence row)])

      (seq (:scoreComponents row))
      (assoc :scoreComponents (:scoreComponents row)))))
(defn- hint-declarations
  [packet]
  (let [query-tokens (text/tokenize-all (:query packet))
        rows (or (seq (:sourceDeclarations packet))
                 (seq (:candidateFiles packet)))]
    (->> rows
         (filter #(or (:sourceLine %)
                      (:source-line %)))
         (filter #(not= "file" (field-name (or (:targetKind %)
                                               (:target-kind %)
                                               (:resultKind %)
                                               (:result-kind %)))))
         (filter (fn [row]
                   (let [label (not-empty (str (:label row)))]
                     (and label
                          (not= label (str (:path row)))))))
         (map-indexed #(hint-declaration query-tokens %1 %2))
         (take 20)
         vec)))

(defn- row-repo-key
  [row]
  (or (:repoId row) (:repo-id row) (:repo row)))

(defn- row-path-key
  [row]
  [(row-repo-key row) (:path row)])

(defn- path-depth
  [path]
  (if (str/blank? (str path))
    Long/MAX_VALUE
    (count (str/split (str path) #"/"))))

(defn- source-declaration-row?
  [row]
  (and (:path row)
       (or (:sourceLine row) (:source-line row))
       (let [label (some-> (:label row) str not-empty)]
         (and label
              (not= label (str (:path row)))))
       (not= "file" (field-name (or (:targetKind row)
                                    (:target-kind row)
                                    (:resultKind row)
                                    (:result-kind row))))))

(defn- file-candidate-row?
  [row]
  (and (:path row)
       (= "file" (field-name (or (:targetKind row)
                                 (:target-kind row)
                                 (:resultKind row)
                                 (:result-kind row))))))

(defn- first-by
  [sort-key rows]
  (first (sort-by sort-key rows)))

(defn- best-file-candidates-by-path
  [rows]
  (->> rows
       (filter file-candidate-row?)
       (group-by row-path-key)
       (map (fn [[path-key path-rows]]
              [path-key (first-by #(long (or (:rank %) Long/MAX_VALUE))
                                  path-rows)]))
       (into {})))

(defn- top-files-by-path
  [rows]
  (let [best-by-key (->> rows
                         (filter :path)
                         (group-by row-path-key)
                         (map (fn [[path-key path-rows]]
                                [path-key
                                 (first-by #(long (or (:rank %)
                                                      Long/MAX_VALUE))
                                           path-rows)]))
                         (into {}))
        unique-by-path (->> (vals best-by-key)
                            (group-by :path)
                            (keep (fn [[path path-rows]]
                                    (when (= 1 (count path-rows))
                                      [path (first path-rows)])))
                            (into {}))]
    {:best-by-key best-by-key
     :unique-by-path unique-by-path}))

(defn- top-file-for-path
  [top-files path-key]
  (or (get-in top-files [:best-by-key path-key])
      (get-in top-files [:unique-by-path (second path-key)])))

(defn- query-kind-token-count
  [query-token-set rows]
  (count (set/intersection query-token-set
                           (set (mapcat #(text/tokenize (:kind %)) rows)))))

(defn- matched-query-compound-token-pairs
  [query-compound-token-pairs row]
  (->> (set/intersection query-compound-token-pairs
                         (set (text/compound-token-pairs
                               (declaration-text row))))
       sort
       vec))

(defn- prepared-localization-declaration
  [query-tokens query-compound-token-pairs row]
  (let [matched-tokens (matched-query-tokens query-tokens row)
        matched-compound-token-pairs (matched-query-compound-token-pairs
                                      query-compound-token-pairs
                                      row)]
    (cond-> (select-keys row [:rank
                              :path
                              :repoId
                              :repo
                              :label
                              :kind
                              :sourceLine
                              :endLine
                              :score])
      true
      (assoc :matchedTokens matched-tokens
             :matchedCompoundTokenPairs matched-compound-token-pairs)
      (:source-line row)
      (assoc :sourceLine (:source-line row))
      (:end-line row)
      (assoc :endLine (:end-line row)))))

(defn- prepared-localization-evidence
  [candidate]
  (vec
   (concat
    (map (fn [declaration]
           (str "prepared-declaration:"
                (:path declaration)
                (source-line-label declaration)
                (when-let [kind (field-name (:kind declaration))]
                  (str " kind=" kind))
                (when-let [label (some-> (:label declaration) str not-empty)]
                  (str " label=" (pr-str label)))))
         (take prepared-localization-declaration-limit
               (:declarations candidate)))
    (when-let [file-candidate (:fileCandidate candidate)]
      [(str "prepared-file-candidate:"
            (:path file-candidate)
            " rank=" (:rank file-candidate)
            (when-some [score (:score file-candidate)]
              (str " score=" score)))])
    (when-let [top-file (:topFile candidate)]
      [(str "prepared-top-file:"
            (:path top-file)
            " rank=" (:rank top-file))]))))

(defn- prepared-localization-score
  [query-token-set candidate]
  (let [declarations (:declarations candidate)
        declaration-count (count declarations)
        declaration-matched-token-count (count (set (mapcat :matchedTokens
                                                            declarations)))
        matched-token-count (if (pos? declaration-matched-token-count)
                              declaration-matched-token-count
                              (long (or (get-in candidate
                                                [:metrics :matchedTokenCount])
                                        0)))
        kind-token-count (query-kind-token-count query-token-set declarations)
        compound-token-pair-count (long (or (get-in candidate
                                                    [:metrics
                                                     :matchedCompoundTokenPairCount])
                                            0))
        best-declaration-score (reduce max 0.0 (map #(double (or (:score %) 0.0))
                                                    declarations))
        best-declaration-rank (reduce min Long/MAX_VALUE
                                      (map #(long (or (:rank %) Long/MAX_VALUE))
                                           declarations))
        file-candidate-rank (some-> candidate :fileCandidate :rank long)
        top-file-rank (some-> candidate :topFile :rank long)]
    (+ (if (pos? declaration-count) 20.0 0.0)
       (* 3.0 (min 4 kind-token-count))
       (* 1.5 matched-token-count)
       (* 8.0 (min 3 compound-token-pair-count))
       (* 1.2 (min 5 declaration-count))
       (min 10.0 best-declaration-score)
       (if file-candidate-rank (/ 6.0 file-candidate-rank) 0.0)
       (if top-file-rank (/ 3.0 top-file-rank) 0.0)
       (if (< best-declaration-rank Long/MAX_VALUE)
         (/ 4.0 best-declaration-rank)
         0.0))))

(defn- prepared-localization-candidate
  [query-tokens
   query-compound-token-pairs
   query-token-set
   top-file-by-path
   file-candidate-by-path
   path-key
   rows]
  (let [declarations (->> rows
                          (map #(prepared-localization-declaration
                                 query-tokens
                                 query-compound-token-pairs
                                 %))
                          (sort-by (juxt #(long (or (:rank %) Long/MAX_VALUE))
                                         #(long (or (:sourceLine %) Long/MAX_VALUE))
                                         :label))
                          vec)
        [_ path] path-key
        top-file (top-file-for-path top-file-by-path path-key)
        file-candidate (get file-candidate-by-path path-key)
        candidate (cond-> {:path path
                           :declarations declarations
                           :metrics {:declarationCount (count declarations)
                                     :pathDepth (path-depth path)
                                     :matchedTokenCount (count (set (mapcat :matchedTokens
                                                                            declarations)))
                                     :matchedCompoundTokenPairCount
                                     (count (set (mapcat :matchedCompoundTokenPairs
                                                         declarations)))
                                     :kindQueryTokenCount (query-kind-token-count
                                                           query-token-set
                                                           rows)}}
                    (row-repo-key (or top-file file-candidate (first rows)))
                    (assoc :repoId (row-repo-key (or top-file file-candidate (first rows))))
                    top-file
                    (assoc :topFile (select-keys top-file [:rank :path :repoId :repo :confidence]))
                    file-candidate
                    (assoc :fileCandidate (select-keys file-candidate
                                                       [:rank :path :repoId :repo :label :score])))]
    (assoc candidate
           :score (prepared-localization-score query-token-set candidate)
           :evidence (prepared-localization-evidence candidate)
           :reason (if (seq declarations)
                     "Mechanical prepared candidate from parser/source declarations matched to query tokens."
                     "Mechanical prepared candidate from a file-level graph candidate."))))

(defn- file-only-prepared-localization-candidate
  [query-token-set top-file-by-path path-key file-candidate]
  (let [[_ path] path-key
        top-file (top-file-for-path top-file-by-path path-key)
        candidate (cond-> {:path path
                           :declarations []
                           :metrics {:declarationCount 0
                                     :pathDepth (path-depth path)
                                     :matchedTokenCount (count (set/intersection
                                                                query-token-set
                                                                (set (text/tokenize-all
                                                                      (str (:path file-candidate)
                                                                           " "
                                                                           (:label file-candidate))))))
                                     :kindQueryTokenCount 0}
                           :fileCandidate (select-keys file-candidate
                                                       [:rank :path :repoId :repo :label :score])}
                    (row-repo-key file-candidate)
                    (assoc :repoId (row-repo-key file-candidate))
                    top-file
                    (assoc :topFile (select-keys top-file [:rank :path :repoId :repo :confidence])))]
    (assoc candidate
           :score (prepared-localization-score query-token-set candidate)
           :evidence (prepared-localization-evidence candidate)
           :reason "Mechanical prepared candidate from a file-level graph candidate.")))

(defn- prepared-localization-sort-key
  [candidate]
  [(- (double (:score candidate)))
   (path-depth (:path candidate))
   (:path candidate)])

(defn- file-candidate-sort-key
  [candidate]
  [(long (or (get-in candidate [:fileCandidate :rank])
             Long/MAX_VALUE))
   (path-depth (:path candidate))
   (:path candidate)])

(defn- candidate-path-key
  [candidate]
  [(row-repo-key candidate) (:path candidate)])

(defn- distinct-candidates
  [candidates]
  (:candidates
   (reduce (fn [{:keys [seen] :as acc} candidate]
             (let [path-key (candidate-path-key candidate)]
               (if (contains? seen path-key)
                 acc
                 (-> acc
                     (update :seen conj path-key)
                     (update :candidates conj candidate)))))
           {:seen #{}
            :candidates []}
           candidates)))

(defn- prepared-localization-candidates
  [declaration-candidates file-candidates]
  (let [ranked-declarations (sort-by prepared-localization-sort-key
                                     declaration-candidates)
        ranked-files (sort-by file-candidate-sort-key file-candidates)
        selected (distinct-candidates
                  (concat (take prepared-localization-declaration-slots
                                ranked-declarations)
                          (take prepared-localization-file-slots
                                ranked-files)))
        selected-keys (set (map candidate-path-key selected))
        filler (->> (concat ranked-declarations ranked-files)
                    (remove #(contains? selected-keys
                                        (candidate-path-key %))))]
    (->> (concat selected filler)
         distinct-candidates
         (take prepared-localization-limit))))

(defn- prepared-localization
  [packet agent-result]
  (let [query-tokens (text/tokenize-all (:query packet))
        query-token-set (set query-tokens)
        query-compound-token-pairs (set (text/compound-token-pairs
                                         (:query packet)))
        declaration-rows (->> (or (seq (:sourceDeclarations packet))
                                  (seq (:candidateFiles packet)))
                              (filter source-declaration-row?)
                              vec)
        declarations-by-path (group-by row-path-key declaration-rows)
        file-candidate-by-path (best-file-candidates-by-path (:candidateFiles packet))
        top-file-by-path (top-files-by-path (:suspectedFiles agent-result))
        declaration-path-keys (set (keys declarations-by-path))
        declaration-candidates (map (fn [[path-key rows]]
                                      (prepared-localization-candidate
                                       query-tokens
                                       query-compound-token-pairs
                                       query-token-set
                                       top-file-by-path
                                       file-candidate-by-path
                                       path-key
                                       rows))
                                    declarations-by-path)
        file-candidates (->> file-candidate-by-path
                             (remove (fn [[path-key _]]
                                       (contains? declaration-path-keys path-key)))
                             (map (fn [[path-key row]]
                                    (file-only-prepared-localization-candidate
                                     query-token-set
                                     top-file-by-path
                                     path-key
                                     row))))
        candidates (->> (prepared-localization-candidates declaration-candidates
                                                          file-candidates)
                        (remove #(str/blank? (str (:path %))))
                        (map-indexed (fn [idx row]
                                       (-> row
                                           (assoc :rank (inc idx)
                                                  :confidence (min 1.0
                                                                   (/ (double (:score row))
                                                                      40.0)))
                                           (dissoc :score))))
                        (take prepared-localization-limit)
                        vec)]
    (when (seq candidates)
      {:basis "mechanical prepared localization candidates from parser/source declarations, file-level graph candidates, and ranked top-file support; excludes benchmark ground truth"
       :candidates candidates})))
(defn- audit-scope-issue?
  [scope]
  (or (= "unclassified-extractor" (:kind scope))
      (pos? (long (or (:skippedFiles scope) 0)))
      (pos? (long (or (:diagnostics scope) 0)))))
(defn- audit-scope-diagnostic
  [scope]
  {:kind "audit-scope-trust-boundary"
   :severity (if (= "unclassified-extractor" (:kind scope))
               "warning"
               "info")
   :message "Audit scope contains skipped files, extractor diagnostics, or unclassified extractor rows."
   :scope (:kind scope)
   :supportedFiles (long (or (:supportedFiles scope) 0))
   :skippedFiles (long (or (:skippedFiles scope) 0))
   :diagnostics (long (or (:diagnostics scope) 0))
   :facts (long (or (:facts scope) 0))})
(defn- audit-scope-diagnostics
  [packet]
  (->> (:auditScopes packet)
       (filter audit-scope-issue?)
       (mapv audit-scope-diagnostic)))
(defn- hint-commands
  [packet]
  (benchmark-prediction/packet-commands packet))
(defn- hint-diagnostics
  [prepared packet selection]
  (let [raw-candidates (long (or (:rawCandidateFiles selection) 0))
        candidate-files (long (or (:candidateFiles selection) 0))
        filtered-files (long (or (:coverageFilteredCandidateFiles selection) 0))
        coverage-kinds (vec (:coverageSourceKinds selection))
        missing-kinds (->> (get-in prepared [:coverage :missingDeclaredSourceKinds])
                           (map str)
                           sort
                           vec)
        source-diagnostics (long (or (get-in packet [:sourceCoverage
                                                     :totals
                                                     :diagnostics])
                                     0))
        source-skipped-files (long (or (get-in packet [:sourceCoverage
                                                       :totals
                                                       :skippedFiles])
                                       0))
        connectivity (get-in packet [:sourceCoverage :indexedConnectivity])
        isolated-files (long (or (:isolatedFiles connectivity) 0))
        audit-diagnostics (audit-scope-diagnostics packet)]
    (cond-> []
      (zero? raw-candidates)
      (conj {:kind "zero-candidate-files"
             :severity "warning"
             :message "Yggdrasil context produced no candidate files for this query."})

      (pos? filtered-files)
      (conj {:kind "coverage-filtered-candidate-files"
             :severity "info"
             :message "Declared source coverage filtered candidate files out of the agent shortlist."
             :coverageSourceKinds coverage-kinds
             :rawCandidateFiles raw-candidates
             :candidateFiles candidate-files
             :filteredCandidateFiles filtered-files})

      (seq missing-kinds)
      (conj {:kind "missing-declared-source-kinds"
             :severity "warning"
             :message "The benchmark case declares source kinds with no scoreable indexed files."
             :sourceKinds missing-kinds})

      (pos? source-diagnostics)
      (conj {:kind "source-extraction-diagnostics"
             :severity "warning"
             :message "Indexed source coverage contains extraction diagnostics; inspect sourceCoverage.diagnostics.samples."
             :diagnostics source-diagnostics})

      (pos? source-skipped-files)
      (conj (cond-> {:kind "source-skipped-files"
                     :severity "info"
                     :message "Indexed source coverage contains skipped files; inspect sourceCoverage skipped breakdowns before treating missing facts as absent."
                     :skippedFiles source-skipped-files}
              (seq (get-in packet [:sourceCoverage :skippedByExtension]))
              (assoc :skippedByExtension
                     (takev 5 (get-in packet [:sourceCoverage :skippedByExtension])))
              (seq (get-in packet [:sourceCoverage :skippedByReason]))
              (assoc :skippedByReason
                     (takev 5 (get-in packet [:sourceCoverage :skippedByReason])))))

      (pos? isolated-files)
      (conj {:kind "isolated-indexed-files"
             :severity "info"
             :message "Indexed source coverage contains files without active graph edges; inspect sourceCoverage.indexedConnectivity."
             :isolatedFiles isolated-files
             :connectedFiles (long (or (:connectedFiles connectivity) 0))
             :crossFileConnectedFiles (long (or (:crossFileConnectedFiles connectivity)
                                                0))})

      (seq audit-diagnostics)
      (into audit-diagnostics))))
(defn context-packet->agent-hints
  "Return a compact agent-facing summary of one Yggdrasil context packet.

  Hints are mechanically derived from retrieval docs and graph entities. They do
  not include hidden benchmark ground truth or accepted fix metadata."
  [prepared packet opts]
  (let [limit (long (or (:limit opts) default-agent-baseline-suspect-limit))
        agent-result (benchmark-prediction/context-packet->agent-result
                      packet
                      {:agent-id (or (:agent-id opts) "ygg-hints")
                       :mode "ygg"
                       :case-id (:case-id prepared)
                       :caseFingerprint (:caseFingerprint prepared)
                       :agentInputFingerprint (:agentInputFingerprint prepared)
                       :root (:worktreeRoot prepared)
                       :roots (:worktreeRoots prepared)
                       :limit limit
                       :coverage (:coverage prepared)
                       :result-scope (:resultScope prepared)})
        diagnostics (hint-diagnostics prepared packet (:selection agent-result))
        declarations (hint-declarations packet)
        prepared-localization (prepared-localization packet agent-result)]
    (cond-> {:schema agent-hints-schema
             :suite-id (:suite-id prepared)
             :case-id (:case-id prepared)
             :repo-id (:repo-id prepared)
             :project-id (:project-id prepared)
             :query (:query packet)
             :topFiles (:suspectedFiles agent-result)
             :topSymbols (vec (take 10 (:suspectedSymbols agent-result)))
             :topDocs (mapv hint-doc (range) (take 10 (:docs packet)))
             :candidateSystems (mapv hint-system (range) (take 10 (:entities packet)))
             :commands (hint-commands packet)
             :selection (:selection agent-result)
             :search (:search packet)
             :evidence (:evidence packet)
             :sourceCoverage (:sourceCoverage packet)
             :warnings (:warnings packet)}
      (seq declarations)
      (assoc :topDeclarations declarations)
      prepared-localization
      (assoc :preparedLocalization prepared-localization)
      (:architecture packet)
      (assoc :architecture (hint-architecture (:architecture packet)))
      (seq (:auditScopes packet))
      (assoc :auditScopes (mapv hint-audit-scope (take 6 (:auditScopes packet))))
      (seq (:relatedFiles packet))
      (assoc :relatedFiles (mapv hint-related-file
                                 (range)
                                 (take 12 (:relatedFiles packet))))
      (seq (:importPackages packet))
      (assoc :importPackages (mapv hint-import-package
                                   (range)
                                   (take 8 (:importPackages packet))))
      (seq diagnostics)
      (assoc :diagnostics diagnostics))))
