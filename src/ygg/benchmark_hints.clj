(ns ygg.benchmark-hints
  (:require [ygg.benchmark-prediction :as benchmark-prediction]
            [ygg.text :as text]
            [clojure.set :as set]
            [clojure.string :as str]))

(def agent-hints-schema
  "ygg.benchmark.agent-hints/v1")

(def default-agent-baseline-suspect-limit
  20)

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
        declarations (hint-declarations packet)]
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
             :evidence (:evidence packet)
             :sourceCoverage (:sourceCoverage packet)
             :warnings (:warnings packet)}
      (seq declarations)
      (assoc :topDeclarations declarations)
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
