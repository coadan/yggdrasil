(ns agraph.benchmark-hints
  (:require [agraph.benchmark-prediction :as benchmark-prediction]))

(def agent-hints-schema
  "agraph.benchmark.agent-hints/v1")

(def default-agent-baseline-suspect-limit
  20)

(defn- source-line-range
  [source]
  (when-let [lines (seq (:lines source))]
    {:start (first lines)
     :end (last lines)}))
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
               [:rank :id :repo :path :label :kind :score :why :metrics :pathPrefix]))
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
                      :boundaryEvidence
                      :runtimeEvidence
                      :dependencyEvidence
                      :docs
                      :evidenceFamilies
                      :validationGaps
                      :warnings
                      :nextActions])
        (update :acceptedSystems #(takev 5 %))
        (update :candidateSystems #(takev 5 %))
        (update :boundaryEvidence #(takev 5 %))
        (update :runtimeEvidence #(takev 5 %))
        (update :dependencyEvidence #(takev 5 %))
        (update :docs #(takev 5 %))
        (update :evidenceFamilies #(takev 8 %))
        (update :validationGaps #(takev 6 %))
        (update :warnings #(takev 3 %))
        (update :nextActions #(takev 4 %)))))
(defn- hint-audit-scope
  [scope]
  (-> scope
      (select-keys [:kind :basis :facts :files :topEvidenceTypes :samples])
      (update :topEvidenceTypes #(takev 5 %))
      (update :samples #(takev 3 %))))
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
                                     0))]
    (cond-> []
      (zero? raw-candidates)
      (conj {:kind "zero-candidate-files"
             :severity "warning"
             :message "AGraph context produced no candidate files for this query."})

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
             :diagnostics source-diagnostics}))))
(defn context-packet->agent-hints
  "Return a compact agent-facing summary of one AGraph context packet.

  Hints are mechanically derived from retrieval docs and graph entities. They do
  not include hidden benchmark ground truth or accepted fix metadata."
  [prepared packet opts]
  (let [limit (long (or (:limit opts) default-agent-baseline-suspect-limit))
        agent-result (benchmark-prediction/context-packet->agent-result
                      packet
                      {:agent-id (or (:agent-id opts) "agraph-hints")
                       :mode "agraph"
                       :case-id (:case-id prepared)
                       :caseFingerprint (:caseFingerprint prepared)
                       :root (:worktreeRoot prepared)
                       :limit limit
                       :coverage (:coverage prepared)})
        diagnostics (hint-diagnostics prepared packet (:selection agent-result))]
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
             :answerability (:answerability packet)
             :sourceCoverage (:sourceCoverage packet)
             :warnings (:warnings packet)}
      (:architecture packet)
      (assoc :architecture (hint-architecture (:architecture packet)))
      (seq (:auditScopes packet))
      (assoc :auditScopes (mapv hint-audit-scope (take 6 (:auditScopes packet))))
      (seq diagnostics)
      (assoc :diagnostics diagnostics))))
