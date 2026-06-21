(ns agraph.benchmark-prepare
  (:require [agraph.benchmark-io :as benchmark-io]
            [agraph.benchmark-paths :as benchmark-paths]
            [agraph.benchmark-progress :as benchmark-progress]
            [agraph.benchmark-score :as benchmark-score]
            [agraph.benchmark-suite :as benchmark-suite]
            [agraph.benchmark-util :as benchmark-util]
            [agraph.fs :as fs]
            [agraph.hash :as hash]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def prepared-case-schema
  "agraph.benchmark.prepared-case/v1")

(defn- repo-by-id
  [suite]
  (benchmark-suite/repo-by-id suite))

(defn- selected-cases
  [suite selector]
  (benchmark-suite/selected-cases suite selector))

(defn- case-selector
  [opts]
  (benchmark-suite/case-selector opts))

(defn- case-tags
  [case]
  (benchmark-suite/case-tags case))

(defn- case-expectations
  [case]
  (benchmark-suite/case-expectations case))

(defn run-git!
  [repo-root args]
  (let [{:keys [exit out err]} (apply shell/sh "git" "-C" repo-root args)]
    (when-not (zero? exit)
      (throw (ex-info "Git command failed."
                      {:repo-root repo-root
                       :args args
                       :exit exit
                       :err err
                       :out out})))
    out))
(defn git-lines
  [repo-root & args]
  (->> (run-git! repo-root args)
       str/split-lines
       (remove str/blank?)
       vec))
(defn git-head
  [repo-root]
  (str/trim (run-git! repo-root ["rev-parse" "HEAD"])))
(defn changed-files
  [repo-root base-sha fix-sha]
  (git-lines repo-root "diff" "--name-only" base-sha fix-sha "--"))
(defn ensure-worktree!
  [repo-root base-sha path]
  (let [path-file (.getCanonicalFile (io/file path))]
    (if (.isDirectory path-file)
      (let [actual (git-head (.getPath path-file))]
        (when-not (= actual base-sha)
          (throw (ex-info "Benchmark worktree already exists at a different commit."
                          {:path (.getPath path-file)
                           :expected base-sha
                           :actual actual}))))
      (do
        (.mkdirs (.getParentFile path-file))
        (run-git! repo-root ["worktree" "add" "--detach" (.getPath path-file) base-sha])))
    (.getPath path-file)))
(defn case-repo
  [suite case]
  (let [repo-id (str (:repo-id case))]
    (or (get (repo-by-id suite) repo-id)
        (throw (ex-info "Benchmark case references unknown repo."
                        {:suite-id (:id suite)
                         :case-id (:id case)
                         :repo-id repo-id})))))
(defn explicit-ground-truth
  [case]
  (let [truth (:ground-truth case)]
    (when (seq (:changed-files truth))
      (mapv str (:changed-files truth)))))
(defn explicit-localization-files
  [case]
  (let [truth (:ground-truth case)]
    (when (seq (:localization-files truth))
      (mapv str (:localization-files truth)))))
(defn ground-truth
  [repo case]
  (let [files (or (explicit-ground-truth case)
                  (changed-files (:root repo) (:base-sha case) (:fix-sha case)))
        localization-files (or (explicit-localization-files case) files)]
    {:changedFiles files
     :localizationFiles localization-files
     :changedSymbols (mapv str (get-in case [:ground-truth :changed-symbols] []))}))
(defn unsupported-ground-truth-files
  [root changed-files]
  (let [rows (->> (:files (fs/scan-file-coverage root))
                  (map (juxt :path identity))
                  (into {}))]
    (->> changed-files
         (keep (fn [path]
                 (let [row (get rows path)]
                   (cond
                     (nil? row)
                     {:path path
                      :reason "missing-at-base"}

                     (not (:supported? row))
                     {:path path
                      :ext (:ext row)
                      :reason (name (:skip-reason row))}))))
         vec)))
(defn normalize-source-kind
  [value]
  (when-not (benchmark-util/blankish? value)
    (name (keyword value))))
(defn declared-source-kinds
  [case]
  (->> (or (get-in case [:coverage :source-kinds])
           (:source-kinds case)
           [])
       (keep normalize-source-kind)
       distinct
       sort
       vec))
(defn path-source-kind
  ([path]
   (some-> path fs/file-kind normalize-source-kind))
  ([kind-by-path path]
   (or (get kind-by-path path)
       (path-source-kind path))))
(defn scanned-path-kinds
  [root]
  (->> (:files (fs/scan-file-coverage root))
       (map (fn [{:keys [path kind]}]
              [path (normalize-source-kind kind)]))
       (into {})))
(defn coverage-filtered-ground-truth
  [case root truth]
  (let [unsupported (set (map :path (:unsupportedGroundTruthFiles truth)))
        targets (->> (benchmark-score/target-ground-truth-files truth)
                     (remove unsupported)
                     vec)
        declared (set (declared-source-kinds case))]
    (if (empty? declared)
      {:scoreableFiles targets
       :coverageExcludedFiles []}
      (let [kind-by-path (scanned-path-kinds root)
            grouped (group-by #(contains? declared (:kind %))
                              (map (fn [path]
                                     {:path path
                                      :kind (get kind-by-path path
                                                 (path-source-kind path))})
                                   targets))]
        {:scoreableFiles (mapv :path (get grouped true))
         :coverageExcludedFiles (mapv (fn [row]
                                        (cond-> {:path (:path row)}
                                          (:kind row) (assoc :kind (:kind row))))
                                      (get grouped false))}))))
(defn scoreable-files-by-kind
  [root truth]
  (let [unsupported (set (map :path (:unsupportedGroundTruthFiles truth)))
        scoreable (->> (benchmark-score/target-ground-truth-files truth)
                       (remove unsupported)
                       set)]
    (->> (:files (fs/scan-file-coverage root))
         (filter #(contains? scoreable (:path %)))
         (keep (fn [{:keys [kind]}]
                 (normalize-source-kind kind)))
         frequencies
         (map (fn [[kind files]]
                {:kind kind
                 :files files}))
         (sort-by :kind)
         vec)))
(defn ground-truth-coverage
  [case root truth]
  (let [declared (declared-source-kinds case)
        by-kind (scoreable-files-by-kind root truth)
        observed (set (map :kind by-kind))]
    {:declaredSourceKinds declared
     :scoreableSourceKinds (vec (sort observed))
     :scoreableFilesByKind by-kind
     :missingDeclaredSourceKinds (->> declared
                                      (remove observed)
                                      vec)}))
(defn issue-comments
  [issue]
  (->> (:comments issue)
       (map (fn [comment]
              (if (map? comment)
                (or (:body comment) (:text comment) "")
                (str comment))))
       (remove str/blank?)
       vec))
(defn issue-text
  "Return the fair issue text used as benchmark query input."
  [case]
  (let [issue (:issue case)]
    (str/join "\n\n"
              (remove str/blank?
                      (concat [(:title issue) (:body issue)]
                              (issue-comments issue))))))
(defn canonical-fingerprint-value
  [value]
  (cond
    (map? value)
    (into (sorted-map)
          (map (fn [[k v]]
                 [(cond
                    (keyword? k) (name k)
                    (symbol? k) (str k)
                    :else (str k))
                  (canonical-fingerprint-value v)]))
          value)

    (set? value)
    (->> value
         (map canonical-fingerprint-value)
         (sort-by pr-str)
         vec)

    (sequential? value)
    (mapv canonical-fingerprint-value value)

    (keyword? value)
    (name value)

    :else
    value))
(defn case-fingerprint-input
  [suite case]
  {:schema "agraph.benchmark.case-fingerprint-input/v1"
   :suite-id (:id suite)
   :case-id (:id case)
   :repo-id (:repo-id case)
   :base-sha (:base-sha case)
   :fix-sha (:fix-sha case)
   :tags (case-tags case)
   :query-text (issue-text case)
   :coverage {:source-kinds (declared-source-kinds case)}
   :expectations (case-expectations case)
   :ground-truth (:ground-truth case)})

(defn agent-input-fingerprint-input
  [suite case]
  {:schema "agraph.benchmark.agent-input-fingerprint-input/v1"
   :suite-id (:id suite)
   :case-id (:id case)
   :repo-id (:repo-id case)
   :base-sha (:base-sha case)
   :query-text (issue-text case)
   :coverage {:source-kinds (declared-source-kinds case)}})

(defn- fingerprint
  [input]
  (str "sha256:"
       (hash/sha256-hex
        (pr-str (canonical-fingerprint-value input)))))

(defn case-fingerprint
  [suite case]
  (fingerprint (case-fingerprint-input suite case)))

(defn agent-input-fingerprint
  [suite case]
  (fingerprint (agent-input-fingerprint-input suite case)))

(defn input-hints
  [input-text truth]
  (let [text (str input-text)
        mentioned-files (->> (:changedFiles truth)
                             (filter #(and (not (benchmark-util/blankish? %))
                                           (str/includes? text %)))
                             vec)]
    {:hinted (boolean (seq mentioned-files))
     :mentionedChangedFiles mentioned-files
     :mentionedChangedFileCount (count mentioned-files)
     :changedFileCount (count (:changedFiles truth))}))
(defn prepared-case
  [suite case repo worktree-root truth]
  (let [input-text (issue-text case)
        score-fingerprint (case-fingerprint suite case)
        agent-input-fingerprint (agent-input-fingerprint suite case)
        unsupported (unsupported-ground-truth-files worktree-root
                                                    (:changedFiles truth))
        truth (assoc truth :unsupportedGroundTruthFiles unsupported)
        coverage-filter (coverage-filtered-ground-truth case worktree-root truth)
        truth (merge truth coverage-filter)]
    {:schema prepared-case-schema
     :suite-id (:id suite)
     :case-id (:id case)
     :repo-id (:id repo)
     :project-id (str (:project-id suite) "-" (:id case))
     :caseFingerprint score-fingerprint
     :agentInputFingerprint agent-input-fingerprint
     :tags (case-tags case)
     :expectations (case-expectations case)
     :baseSha (:base-sha case)
     :fixSha (:fix-sha case)
     :worktreeRoot worktree-root
     :input {:issueId (get-in case [:issue :id])
             :title (get-in case [:issue :title])
             :body (get-in case [:issue :body])
             :comments (issue-comments (:issue case))
             :queryText input-text}
     :inputHints (input-hints input-text truth)
     :coverage (ground-truth-coverage case worktree-root truth)
     :groundTruth truth}))
(defn prepare-case!
  "Prepare one benchmark case and write its prepared JSON artifact."
  [suite case opts]
  (let [repo (case-repo suite case)
        base-sha (or (:base-sha case)
                     (throw (ex-info "Benchmark case is missing :base-sha."
                                     {:case-id (:id case)})))
        _ (when-not (:fix-sha case)
            (throw (ex-info "Benchmark case is missing :fix-sha."
                            {:case-id (:id case)})))
        worktree-root (benchmark-progress/progress-stage!
                       suite
                       case
                       opts
                       :prepare-worktree
                       #(ensure-worktree! (:root repo)
                                          base-sha
                                          (.getPath (benchmark-paths/worktree-dir suite case opts)))
                       (fn [root]
                         {:worktreeRoot root
                          :baseSha base-sha}))
        truth (benchmark-progress/progress-stage!
               suite
               case
               opts
               :prepare-ground-truth
               #(ground-truth repo case)
               (fn [truth]
                 {:changedFiles (count (:changedFiles truth))
                  :localizationFiles (count (:localizationFiles truth))}))
        prepared (prepared-case suite case repo worktree-root truth)]
    (benchmark-progress/progress-stage!
     suite
     case
     opts
     :write-prepared-case
     #(benchmark-io/write-json-file! (benchmark-paths/prepared-path suite case opts) prepared)
     (fn [path]
       {:path (fs/canonical-path path)}))
    prepared))
(defn prepare-suite!
  "Prepare selected benchmark cases."
  [suite opts]
  {:schema "agraph.benchmark.prepare/v1"
   :suite-id (:id suite)
   :cases (mapv #(prepare-case! suite % opts)
                (selected-cases suite (case-selector opts)))})
