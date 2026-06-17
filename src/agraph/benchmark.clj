(ns agraph.benchmark
  "Issue replay benchmarks for AGraph retrieval quality."
  (:require [agraph.fs :as fs]
            [agraph.project :as project]
            [agraph.query :as query]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]))

(def suite-schema
  "agraph.benchmark.suite/v1")

(def prepared-case-schema
  "agraph.benchmark.prepared-case/v1")

(def result-schema
  "agraph.benchmark.result/v1")

(def report-schema
  "agraph.benchmark.report/v1")

(def default-output-root
  ".dev/agraph/bench")

(def default-limit
  50)

(def recall-limits
  [5 10 20])

(defn- blankish?
  [value]
  (str/blank? (str value)))

(defn- safe-id
  [value]
  (-> (str value)
      str/lower-case
      (str/replace #"[^a-z0-9._-]+" "-")
      (str/replace #"(^[-._]+|[-._]+$)" "")
      not-empty
      (or "benchmark")))

(defn- canonical-or-relative
  [base path]
  (let [file (io/file path)]
    (fs/canonical-path
     (if (.isAbsolute file)
       file
       (io/file base path)))))

(defn- config-dir
  [path]
  (or (some-> (io/file path) .getCanonicalFile .getParentFile)
      (io/file ".")))

(defn- normalize-repo
  [base repo]
  (let [repo-id (some-> (:id repo) str)]
    (when (blankish? repo-id)
      (throw (ex-info "Benchmark repo is missing :id." {:repo repo})))
    (when-not (:root repo)
      (throw (ex-info "Benchmark repo is missing :root." {:repo repo})))
    (assoc repo
           :id repo-id
           :root (canonical-or-relative base (:root repo))
           :role (keyword (or (:role repo) :application)))))

(defn- normalize-case
  [case]
  (let [case-id (some-> (:id case) str)
        repo-id (some-> (:repo-id case) str)]
    (when (blankish? case-id)
      (throw (ex-info "Benchmark case is missing :id." {:case case})))
    (when (blankish? repo-id)
      (throw (ex-info "Benchmark case is missing :repo-id."
                      {:case-id case-id})))
    (assoc case
           :id case-id
           :repo-id repo-id
           :base-sha (some-> (:base-sha case) str)
           :fix-sha (some-> (:fix-sha case) str))))

(defn read-suite
  "Read and normalize a benchmark suite EDN file."
  [path]
  (let [base (config-dir path)
        data (edn/read-string (slurp (io/file path)))
        suite-id (str (or (:id data) (safe-id (.getName (io/file path)))))]
    (when-not (seq (:repos data))
      (throw (ex-info "Benchmark suite is missing :repos." {:path path})))
    (when-not (seq (:cases data))
      (throw (ex-info "Benchmark suite is missing :cases." {:path path})))
    (assoc data
           :schema suite-schema
           :id suite-id
           :project-id (str (or (:project-id data) suite-id))
           :path (fs/canonical-path path)
           :repos (mapv #(normalize-repo base %) (:repos data))
           :cases (mapv normalize-case (:cases data)))))

(defn- repo-by-id
  [suite]
  (into {} (map (juxt :id identity)) (:repos suite)))

(defn selected-cases
  "Return suite cases, optionally narrowed to one case id."
  [suite case-id]
  (let [cases (:cases suite)]
    (if (blankish? case-id)
      cases
      (let [case-id (str case-id)]
        (or (seq (filter #(= case-id (:id %)) cases))
            (throw (ex-info "Benchmark case not found."
                            {:case-id case-id
                             :suite-id (:id suite)})))))))

(defn- output-root
  [suite opts]
  (io/file (or (:out opts) default-output-root) (safe-id (:id suite))))

(defn- case-output-dir
  [suite case opts]
  (io/file (output-root suite opts) "cases" (safe-id (:id case))))

(defn- worktree-dir
  [suite case opts]
  (io/file (case-output-dir suite case opts) "worktree"))

(defn- xtdb-dir
  [suite case opts]
  (.getPath (io/file (case-output-dir suite case opts) "xtdb")))

(defn- prepared-path
  [suite case opts]
  (io/file (case-output-dir suite case opts) "prepared.json"))

(defn- result-path
  [suite case opts]
  (io/file (case-output-dir suite case opts) "result.json"))

(defn- report-path
  [suite opts]
  (io/file (output-root suite opts) "report.json"))

(defn- ensure-parent!
  [file]
  (.mkdirs (.getParentFile (io/file file))))

(defn- write-json-file!
  [path value]
  (ensure-parent! path)
  (spit (io/file path) (json/write-json-str value {:indent-str "  "}))
  path)

(defn- read-json-file
  [path]
  (json/read-json (slurp (io/file path)) :key-fn keyword))

(defn- run-git!
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

(defn- git-lines
  [repo-root & args]
  (->> (run-git! repo-root args)
       str/split-lines
       (remove str/blank?)
       vec))

(defn- git-head
  [repo-root]
  (str/trim (run-git! repo-root ["rev-parse" "HEAD"])))

(defn- changed-files
  [repo-root base-sha fix-sha]
  (git-lines repo-root "diff" "--name-only" base-sha fix-sha "--"))

(defn- ensure-worktree!
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

(defn- case-repo
  [suite case]
  (let [repo-id (str (:repo-id case))]
    (or (get (repo-by-id suite) repo-id)
        (throw (ex-info "Benchmark case references unknown repo."
                        {:suite-id (:id suite)
                         :case-id (:id case)
                         :repo-id repo-id})))))

(defn- explicit-ground-truth
  [case]
  (let [truth (:ground-truth case)]
    (when (seq (:changed-files truth))
      (mapv str (:changed-files truth)))))

(defn- ground-truth
  [repo case]
  (let [files (or (explicit-ground-truth case)
                  (changed-files (:root repo) (:base-sha case) (:fix-sha case)))]
    {:changedFiles files
     :changedSymbols (mapv str (get-in case [:ground-truth :changed-symbols] []))}))

(defn- unsupported-ground-truth-files
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

(defn- issue-comments
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

(defn- prepared-case
  [suite case repo worktree-root truth]
  (let [input-text (issue-text case)]
    {:schema prepared-case-schema
     :suite-id (:id suite)
     :case-id (:id case)
     :repo-id (:id repo)
     :project-id (str (:project-id suite) "-" (:id case))
     :baseSha (:base-sha case)
     :fixSha (:fix-sha case)
     :worktreeRoot worktree-root
     :input {:issueId (get-in case [:issue :id])
             :title (get-in case [:issue :title])
             :body (get-in case [:issue :body])
             :comments (issue-comments (:issue case))
             :queryText input-text}
     :groundTruth (assoc truth
                         :unsupportedGroundTruthFiles
                         (unsupported-ground-truth-files worktree-root
                                                         (:changedFiles truth)))}))

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
        worktree-root (ensure-worktree! (:root repo)
                                        base-sha
                                        (.getPath (worktree-dir suite case opts)))
        truth (ground-truth repo case)
        prepared (prepared-case suite case repo worktree-root truth)]
    (write-json-file! (prepared-path suite case opts) prepared)
    prepared))

(defn prepare-suite!
  "Prepare selected benchmark cases."
  [suite opts]
  {:schema "agraph.benchmark.prepare/v1"
   :suite-id (:id suite)
   :cases (mapv #(prepare-case! suite % opts)
                (selected-cases suite (:case-id opts)))})

(defn- file-row
  [rank result]
  (when (and (#{:node :chunk} (:target-kind result))
             (not (blankish? (:path result))))
    {:path (:path result)
     :rank rank
     :score (:score result)
     :target-id (:target-id result)
     :target-kind (name (:target-kind result))
     :label (:label result)
     :source-line (:source-line result)}))

(defn- top-files
  [ranked]
  (->> ranked
       (map-indexed (fn [idx result] (file-row (inc idx) result)))
       (keep identity)
       (reduce (fn [best row]
                 (let [existing (get best (:path row))]
                   (if (or (nil? existing)
                           (< (:rank row) (:rank existing)))
                     (assoc best (:path row) row)
                     best)))
               {})
       vals
       (sort-by (juxt :rank :path))
       vec))

(defn- top-nodes
  [ranked]
  (->> ranked
       (map-indexed vector)
       (keep (fn [[idx result]]
               (when (= :node (:target-kind result))
                 {:id (:target-id result)
                  :rank (inc idx)
                  :score (:score result)
                  :path (:path result)
                  :label (:label result)
                  :kind (some-> (:kind result) name)
                  :source-line (:source-line result)})))
       vec))

(defn- top-systems
  [ranked]
  (->> ranked
       (map-indexed vector)
       (keep (fn [[idx result]]
               (when (= :system-node (:target-kind result))
                 {:id (:target-id result)
                  :rank (inc idx)
                  :score (:score result)
                  :label (:label result)
                  :kind (some-> (:kind result) name)})))
       vec))

(defn- ground-truth-file-ranks
  [changed-files top-files]
  (let [file-by-path (into {} (map (juxt :path identity)) top-files)]
    (mapv (fn [path]
            (if-let [row (get file-by-path path)]
              (assoc (select-keys row [:path
                                       :rank
                                       :score
                                       :target-id
                                       :target-kind
                                       :label
                                       :source-line])
                     :found? true)
              {:path path
               :found? false}))
          changed-files)))

(defn- recall-at
  [truth paths k]
  (let [truth (set truth)
        predicted (set (take k paths))]
    (if (seq truth)
      (/ (double (count (set/intersection truth predicted)))
         (double (count truth)))
      0.0)))

(defn- mean-reciprocal-rank-file
  [truth top-files]
  (let [truth (set truth)]
    (or (some (fn [{:keys [path rank]}]
                (when (contains? truth path)
                  (/ 1.0 (double rank))))
              top-files)
        0.0)))

(defn- noise-ratio-at
  [truth paths k]
  (let [truth (set truth)
        predicted (take k paths)]
    (if (seq predicted)
      (/ (double (count (remove truth predicted)))
         (double (count predicted)))
      0.0)))

(defn score-result
  "Return mechanical localization scores for a benchmark result shape."
  [{:keys [groundTruth agraph]}]
  (let [changed-files (:changedFiles groundTruth)
        paths (mapv :path (:topFiles agraph))]
    (merge
     (into {}
           (map (fn [k]
                  [(keyword (str "fileRecallAt" k))
                   (recall-at changed-files paths k)]))
           recall-limits)
     {:meanReciprocalRankFile (mean-reciprocal-rank-file changed-files
                                                         (:topFiles agraph))
      :noiseRatioAt20 (noise-ratio-at changed-files paths 20)
      :unsupportedGroundTruthFiles (count (:unsupportedGroundTruthFiles groundTruth))})))

(defn- run-query!
  [xtdb prepared opts]
  (query/semantic-query xtdb
                        (get-in prepared [:input :queryText])
                        {:project-id (:project-id prepared)
                         :repo-id (:repo-id prepared)
                         :retriever (keyword (or (:retriever opts) :lexical))
                         :limit (long (or (:limit opts) default-limit))}))

(defn run-case!
  "Run one benchmark case and write its scored result artifact."
  [suite case opts]
  (let [prepared (prepare-case! suite case opts)
        repo {:id (:repo-id prepared)
              :root (:worktreeRoot prepared)
              :role :application}
        bench-project {:id (:project-id prepared)
                       :name (:case-id prepared)
                       :repos [repo]}]
    (store/with-node (xtdb-dir suite case opts)
      (fn [xtdb]
        (let [index-summary (project/index-project! xtdb
                                                    bench-project
                                                    {:index-profile :query})
              system-summary (project/infer-project! xtdb bench-project)
              ranked (run-query! xtdb prepared opts)
              result-base
              {:schema result-schema
               :suite-id (:id suite)
               :case-id (:id case)
               :repo-id (:repo-id prepared)
               :project-id (:project-id prepared)
               :baseSha (:baseSha prepared)
               :fixSha (:fixSha prepared)
               :input (:input prepared)
               :groundTruth (:groundTruth prepared)
               :agraph {:retriever (name (keyword (or (:retriever opts) :lexical)))
                        :limit (long (or (:limit opts) default-limit))
                        :indexSummary index-summary
                        :systemSummary system-summary
                        :topFiles (top-files ranked)
                        :topNodes (top-nodes ranked)
                        :topSystems (top-systems ranked)
                        :warnings []}}
              result-without-scores
              (assoc result-base
                     :groundTruthRanks
                     {:files (ground-truth-file-ranks
                              (get-in result-base [:groundTruth :changedFiles])
                              (get-in result-base [:agraph :topFiles]))})
              result (assoc result-without-scores
                            :scores (score-result result-without-scores))]
          (write-json-file! (result-path suite case opts) result)
          result)))))

(defn run-suite!
  "Run selected benchmark cases."
  [suite opts]
  {:schema "agraph.benchmark.run/v1"
   :suite-id (:id suite)
   :cases (mapv #(run-case! suite % opts)
                (selected-cases suite (:case-id opts)))})

(defn- case-result-file
  [suite case opts]
  (let [file (result-path suite case opts)]
    (when (.isFile file)
      file)))

(defn case-result
  "Read one case result when it exists."
  [suite case opts]
  (some-> (case-result-file suite case opts) read-json-file))

(defn show-case
  "Return one case result, or its prepared artifact when no result exists."
  [suite case-id opts]
  (let [case (first (selected-cases suite case-id))
        result (case-result suite case opts)
        prepared (prepared-path suite case opts)]
    (or result
        (when (.isFile prepared)
          (read-json-file prepared))
        (throw (ex-info "Benchmark case has not been prepared or run."
                        {:suite-id (:id suite)
                         :case-id case-id})))))

(defn- average
  [values]
  (if (seq values)
    (/ (double (reduce + values)) (double (count values)))
    0.0))

(defn- aggregate-scores
  [results]
  (let [score-keys [:fileRecallAt5
                    :fileRecallAt10
                    :fileRecallAt20
                    :meanReciprocalRankFile
                    :noiseRatioAt20]]
    (into {}
          (map (fn [k]
                 [k (average (keep #(get-in % [:scores k]) results))]))
          score-keys)))

(defn report-suite
  "Aggregate existing benchmark result artifacts."
  [suite opts]
  (let [cases (selected-cases suite (:case-id opts))
        results (keep #(case-result suite % opts) cases)
        missing (->> cases
                     (remove #(case-result suite % opts))
                     (mapv :id))
        report {:schema report-schema
                :suite-id (:id suite)
                :cases (count cases)
                :completed (count results)
                :missing missing
                :scores (aggregate-scores results)
                :results (mapv #(select-keys % [:case-id
                                                :repo-id
                                                :baseSha
                                                :fixSha
                                                :scores])
                               results)}]
    (write-json-file! (report-path suite opts) report)
    report))
