(ns agraph.benchmark
  "Issue replay benchmarks for AGraph retrieval quality."
  (:require [agraph.context :as context]
            [agraph.fs :as fs]
            [agraph.project :as project]
            [agraph.query :as query]
            [agraph.text :as text]
            [agraph.xtdb :as store]
            [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def suite-schema
  "agraph.benchmark.suite/v1")

(def prepared-case-schema
  "agraph.benchmark.prepared-case/v1")

(def result-schema
  "agraph.benchmark.result/v1")

(def agent-packet-schema
  "agraph.benchmark.agent-packet/v1")

(def agent-hints-schema
  "agraph.benchmark.agent-hints/v1")

(def agent-result-schema
  "agraph.benchmark.agent-result/v1")

(def agent-score-schema
  "agraph.benchmark.agent-score/v1")

(def agent-report-schema
  "agraph.benchmark.agent-report/v1")

(def agent-check-schema
  "agraph.benchmark.agent-check/v1")

(def agent-compare-schema
  "agraph.benchmark.agent-compare/v1")

(def agent-baseline-schema
  "agraph.benchmark.agent-baseline/v1")

(def agent-baselines-schema
  "agraph.benchmark.agent-baselines/v1")

(def agent-run-schema
  "agraph.benchmark.agent-run/v1")

(def agent-runs-schema
  "agraph.benchmark.agent-runs/v1")

(def report-schema
  "agraph.benchmark.report/v1")

(def default-output-root
  ".dev/agraph/bench")

(def default-limit
  50)

(def default-agent-baseline-budget
  16000)

(def default-agent-baseline-doc-limit
  20)

(def default-agent-baseline-retrieval-limit
  100)

(def default-agent-baseline-suspect-limit
  20)

(def default-local-vector-model
  "sentence-transformers/all-MiniLM-L6-v2")

(def default-local-vector-command
  "python3 scripts/local-vector-baseline.py")

(def ^:private rank-score-token-cap
  5)

(def default-agent-run-timeout-ms
  600000)

(def supported-agent-prompt-profiles
  ["standard" "fast"])

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
  "Return suite cases, optionally narrowed to one or more case ids."
  [suite selector]
  (let [cases (:cases suite)
        case-ids (cond
                   (blankish? selector) []
                   (sequential? selector) (->> selector
                                               (map str)
                                               (remove blankish?)
                                               vec)
                   :else [(str selector)])]
    (if (empty? case-ids)
      cases
      (let [wanted (set case-ids)
            found (filter #(contains? wanted (:id %)) cases)
            found-ids (set (map :id found))
            missing (vec (remove found-ids case-ids))]
        (when (seq missing)
          (throw (ex-info "Benchmark case not found."
                          {:case-ids missing
                           :suite-id (:id suite)})))
        (seq found)))))

(defn- case-selector
  [opts]
  (or (:case-ids opts)
      (:case-id opts)))

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

(defn- agent-project-path
  [suite case opts]
  (io/file (case-output-dir suite case opts) "agent-project.edn"))

(defn- agent-packet-path
  [suite case opts]
  (io/file (case-output-dir suite case opts) "agent-packet.json"))

(defn- agent-baseline-id
  [opts]
  (str "agraph-baseline-" (name (keyword (or (:retriever opts) :lexical)))))

(defn- agent-baseline-result-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-results"
           (str (safe-id (agent-baseline-id opts)) ".json")))

(defn- agent-baseline-context-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-results"
           (str (safe-id (agent-baseline-id opts)) ".context.json")))

(defn- local-vector-request-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-results"
           (str (safe-id (agent-baseline-id opts)) ".request.json")))

(defn- agent-run-id
  [opts]
  (or (some-> (:agent-id opts) safe-id not-empty)
      "agent"))

(defn- agent-run-result-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-results"
           (str (agent-run-id opts) ".json")))

(defn- agent-run-log-path
  [suite case opts stream]
  (io/file (case-output-dir suite case opts)
           "agent-logs"
           (str (agent-run-id opts) "." stream ".txt")))

(defn- agent-run-prompt-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-prompts"
           (str (agent-run-id opts) ".md")))

(defn- agent-run-output-schema-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-output-schemas"
           (str (agent-run-id opts) ".schema.json")))

(defn- agent-run-context-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-contexts"
           (str (agent-run-id opts) ".agraph-context.json")))

(defn- agent-run-hints-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-contexts"
           (str (agent-run-id opts) ".agraph-hints.json")))

(defn- agent-run-path
  [suite case opts]
  (io/file (case-output-dir suite case opts)
           "agent-runs"
           (str (agent-run-id opts) ".json")))

(defn- without-json-suffix
  [path]
  (str/replace (.getName (io/file path)) #"\.json$" ""))

(defn- agent-score-path
  [suite case opts result-file]
  (io/file (case-output-dir suite case opts)
           "agent-scores"
           (str (safe-id (without-json-suffix result-file)) ".score.json")))

(defn- agent-score-dir
  [suite case opts]
  (io/file (case-output-dir suite case opts) "agent-scores"))

(defn- agent-report-path
  [suite opts]
  (io/file (output-root suite opts) "agent-report.json"))

(defn- agent-check-path
  [suite opts]
  (io/file (output-root suite opts) "agent-check.json"))

(defn- agent-compare-path
  [suite opts]
  (io/file (output-root suite opts) "agent-compare.json"))

(defn- progress-path
  [suite case opts]
  (io/file (case-output-dir suite case opts) "progress.json"))

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

(defn- write-text-file!
  [path value]
  (ensure-parent! path)
  (spit (io/file path) (str value))
  path)

(defn- write-edn-file!
  [path value]
  (ensure-parent! path)
  (spit (io/file path) (str (pr-str value) "\n"))
  path)

(defn- read-json-file
  [path]
  (json/read-json (slurp (io/file path)) :key-fn keyword))

(defn- now-string
  []
  (str (java.time.Instant/now)))

(defn- elapsed-ms
  [started-ns]
  (max 1 (long (/ (- (System/nanoTime) started-ns) 1000000))))

(defn- progress-event
  [stage status extra]
  (merge {:stage (name stage)
          :status (name status)
          :at (now-string)}
         extra))

(defn- read-progress
  [path suite case]
  (if (.isFile (io/file path))
    (read-json-file path)
    {:schema "agraph.benchmark.case-progress/v1"
     :suite-id (:id suite)
     :case-id (:id case)
     :events []}))

(defn- append-progress-event!
  [suite case opts event]
  (let [path (progress-path suite case opts)
        progress (read-progress path suite case)]
    (write-json-file! path
                      (-> progress
                          (assoc :updatedAt (now-string))
                          (update :events (fnil conj []) event)))
    path))

(defn- progress-stage!
  ([suite case opts stage f]
   (progress-stage! suite case opts stage f (constantly nil)))
  ([suite case opts stage f summarize]
   (let [started-ns (System/nanoTime)]
     (append-progress-event! suite
                             case
                             opts
                             (progress-event stage :started {}))
     (try
       (let [result (f)
             summary (summarize result)]
         (append-progress-event! suite
                                 case
                                 opts
                                 (progress-event
                                  stage
                                  :completed
                                  (cond-> {:elapsedMs (elapsed-ms started-ns)}
                                    (some? summary) (assoc :summary summary))))
         result)
       (catch Throwable t
         (append-progress-event! suite
                                 case
                                 opts
                                 (progress-event
                                  stage
                                  :failed
                                  {:elapsedMs (elapsed-ms started-ns)
                                   :error {:class (.getName (class t))
                                           :message (ex-message t)}}))
         (throw t))))))

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

(defn- explicit-localization-files
  [case]
  (let [truth (:ground-truth case)]
    (when (seq (:localization-files truth))
      (mapv str (:localization-files truth)))))

(defn- ground-truth
  [repo case]
  (let [files (or (explicit-ground-truth case)
                  (changed-files (:root repo) (:base-sha case) (:fix-sha case)))
        localization-files (or (explicit-localization-files case) files)]
    {:changedFiles files
     :localizationFiles localization-files
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

(defn- normalize-source-kind
  [value]
  (when-not (blankish? value)
    (name (keyword value))))

(defn- declared-source-kinds
  [case]
  (->> (or (get-in case [:coverage :source-kinds])
           (:source-kinds case)
           [])
       (keep normalize-source-kind)
       distinct
       sort
       vec))

(defn- scoreable-files-by-kind
  [root truth]
  (let [unsupported (set (map :path (:unsupportedGroundTruthFiles truth)))
        scoreable (->> (or (:localizationFiles truth) (:changedFiles truth))
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

(defn- ground-truth-coverage
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

(defn- input-hints
  [input-text truth]
  (let [text (str input-text)
        mentioned-files (->> (:changedFiles truth)
                             (filter #(and (not (blankish? %))
                                           (str/includes? text %)))
                             vec)]
    {:hinted (boolean (seq mentioned-files))
     :mentionedChangedFiles mentioned-files
     :mentionedChangedFileCount (count mentioned-files)
     :changedFileCount (count (:changedFiles truth))}))

(defn- prepared-case
  [suite case repo worktree-root truth]
  (let [input-text (issue-text case)
        unsupported (unsupported-ground-truth-files worktree-root
                                                    (:changedFiles truth))
        truth (assoc truth :unsupportedGroundTruthFiles unsupported)]
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
        worktree-root (progress-stage!
                       suite
                       case
                       opts
                       :prepare-worktree
                       #(ensure-worktree! (:root repo)
                                          base-sha
                                          (.getPath (worktree-dir suite case opts)))
                       (fn [root]
                         {:worktreeRoot root
                          :baseSha base-sha}))
        truth (progress-stage!
               suite
               case
               opts
               :prepare-ground-truth
               #(ground-truth repo case)
               (fn [truth]
                 {:changedFiles (count (:changedFiles truth))
                  :localizationFiles (count (:localizationFiles truth))}))
        prepared (prepared-case suite case repo worktree-root truth)]
    (progress-stage!
     suite
     case
     opts
     :write-prepared-case
     #(write-json-file! (prepared-path suite case opts) prepared)
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

(defn- unsupported-ground-truth-paths
  [ground-truth]
  (set (map :path (:unsupportedGroundTruthFiles ground-truth))))

(defn- scoreable-changed-files
  [ground-truth]
  (let [unsupported (unsupported-ground-truth-paths ground-truth)]
    (->> (or (:localizationFiles ground-truth) (:changedFiles ground-truth))
         (remove unsupported)
         vec)))

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
        scoreable-files (scoreable-changed-files groundTruth)
        paths (mapv :path (:topFiles agraph))]
    (merge
     (into {}
           (map (fn [k]
                  [(keyword (str "fileRecallAt" k))
                   (recall-at scoreable-files paths k)]))
           recall-limits)
     {:meanReciprocalRankFile (mean-reciprocal-rank-file scoreable-files
                                                         (:topFiles agraph))
      :noiseRatioAt20 (noise-ratio-at scoreable-files paths 20)
      :changedFiles (count changed-files)
      :localizationFiles (count (or (:localizationFiles groundTruth)
                                    changed-files))
      :scoreableChangedFiles (count scoreable-files)
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
               :inputHints (:inputHints prepared)
               :coverage (:coverage prepared)
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
                              (scoreable-changed-files
                               (get-in result-base [:groundTruth]))
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
                (selected-cases suite (case-selector opts)))})

(defn- shell-quote
  [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))

(defn- agraph-command-root
  []
  (.getCanonicalPath (io/file (System/getProperty "user.dir"))))

(defn- agent-clj-config-dir
  [prepared]
  (fs/canonical-path (io/file (:worktreeRoot prepared) ".cpcache" "clj-config")))

(defn- env-command
  [xtdb-path clj-config-dir command & args]
  (str "mkdir -p "
       (shell-quote clj-config-dir)
       " && cd "
       (shell-quote (agraph-command-root))
       " && CLJ_CONFIG="
       (shell-quote clj-config-dir)
       " AGRAPH_XTDB_PATH="
       (shell-quote xtdb-path)
       " "
       (str/join " " (cons command (map shell-quote args)))))

(defn- agent-mode
  [opts]
  (let [mode (name (keyword (or (:mode opts) :agraph)))]
    (when-not (#{"agraph" "shell-only"} mode)
      (throw (ex-info "Unknown benchmark agent mode."
                      {:mode mode
                       :supported ["agraph" "shell-only"]})))
    mode))

(defn- agent-project
  [prepared]
  {:id (:project-id prepared)
   :name (:case-id prepared)
   :repos [{:id (:repo-id prepared)
            :root (:worktreeRoot prepared)
            :role :application}]})

(defn- agent-command-hints
  [prepared project-path xtdb-path mode]
  (let [clj-config-dir (agent-clj-config-dir prepared)]
    (cond-> {:shell ["Inspect the checkout with ordinary local commands such as git, rg, find, sed, and tests."
                     "Do not read the fixing diff, PR, post-fix commits, or ground-truth artifacts."]}
      (= "agraph" mode)
      (assoc :agraph
             {:projectConfig project-path
              :xtdbPath xtdb-path
              :cljConfigDir clj-config-dir
              :setupCommand (env-command xtdb-path clj-config-dir "bb" "sync" project-path)
              :askCommand (env-command xtdb-path
                                       clj-config-dir
                                       "bb"
                                       "ask"
                                       (get-in prepared [:input :queryText])
                                       "--project"
                                       (:project-id prepared)
                                       "--json")
              :exploreCommand (env-command xtdb-path
                                           clj-config-dir
                                           "bb"
                                           "explore"
                                           "create"
                                           (get-in prepared [:input :queryText])
                                           "--project"
                                           (:project-id prepared)
                                           "--json")}))))

(defn- agent-result-contract
  []
  {:schema agent-result-schema
   :caseId "case id from the packet"
   :agentId "stable id for the agent run"
   :mode "agraph or shell-only"
   :suspectedFiles [{:path "repo-relative/path.ext"
                     :rank 1
                     :confidence 0.0
                     :reason "short evidence-based reason"
                     :evidence []}]
   :suspectedSymbols []
   :commands []
   :warnings []
   :summary "brief rationale"})

(defn- agent-packet-from-prepared!
  [suite case prepared opts]
  (let [mode (agent-mode opts)
        project-path (fs/canonical-path (agent-project-path suite case opts))
        xtdb-path (fs/canonical-path (xtdb-dir suite case opts))
        packet-path (fs/canonical-path (agent-packet-path suite case opts))
        project-config (agent-project prepared)
        artifacts (cond-> {:projectConfig project-path
                           :packetPath packet-path
                           :xtdbPath xtdb-path}
                    (:agraph-context-path opts)
                    (assoc :agraphContextPath (:agraph-context-path opts))
                    (:agraph-hints-path opts)
                    (assoc :agraphHintsPath (:agraph-hints-path opts)))
        packet {:schema agent-packet-schema
                :suite-id (:suite-id prepared)
                :case-id (:case-id prepared)
                :repo-id (:repo-id prepared)
                :project-id (:project-id prepared)
                :mode mode
                :baseSha (:baseSha prepared)
                :worktreeRoot (:worktreeRoot prepared)
                :input (:input prepared)
                :task {:kind "issue-localization"
                       :objective (str "Identify the repo-relative files and optional symbols most likely "
                                       "needed to fix the issue from the base checkout.")
                       :rules ["Use only the base checkout and issue text in this packet."
                               "Return ranked suspected files before attempting a patch."
                               "Keep reasoning evidence-based and cite commands or graph context used."
                               "Do not inspect the fixing diff, PR body, post-fix commits, or ground-truth artifacts."]
                       :expectedResultSchema agent-result-schema
                       :resultContract (agent-result-contract)}
                :tools (agent-command-hints prepared project-path xtdb-path mode)
                :artifacts artifacts
                :fairness {:allowedInput ["issue title"
                                          "issue body"
                                          "pre-fix issue comments"
                                          "base checkout"
                                          "AGraph output generated from the base checkout"]
                           :forbiddenInput ["fix diff"
                                            "PR title or body"
                                            "post-fix issue comments"
                                            "post-fix commits"
                                            "ground-truth benchmark artifacts"]}}]
    (write-edn-file! project-path project-config)
    (write-json-file! packet-path packet)
    packet))

(defn agent-packet!
  "Prepare one case and write a provider-neutral agent localization packet."
  [suite case opts]
  (agent-packet-from-prepared! suite case (prepare-case! suite case opts) opts))

(defn agent-packets!
  "Write agent localization packets for selected benchmark cases."
  [suite opts]
  {:schema "agraph.benchmark.agent-packets/v1"
   :suite-id (:id suite)
   :packets (mapv #(agent-packet! suite % opts)
                  (selected-cases suite (case-selector opts)))})

(declare parse-double-safe score-agent-result)

(defn- existing-file-path?
  [root path]
  (and (not (blankish? path))
       (or (nil? root)
           (.isFile (io/file root path)))))

(defn- bounded-confidence
  [value]
  (if-let [score (parse-double-safe value)]
    (max 0.0 (min 1.0 score))
    0.0))

(defn- line-label
  [source]
  (when-let [lines (seq (:lines source))]
    (str " lines " (str/join "-" lines))))

(defn- evidence-text
  [doc]
  (str/join "\n"
            (remove blankish?
                    [(get-in doc [:source :path])
                     (get-in doc [:source :heading])
                     (:snippet doc)])))

(defn- token-matches
  [query-tokens text]
  (let [query-token-set (set query-tokens)
        evidence-token-set (set (text/tokenize text))]
    (set/intersection query-token-set evidence-token-set)))

(defn- doc-prediction
  [root query-tokens idx doc]
  (let [source (:source doc)
        path (:path source)]
    (when (existing-file-path? root path)
      {:path path
       :source-rank (inc idx)
       :confidence (bounded-confidence (:score doc))
       :evidence-score (double (or (parse-double-safe (:score doc)) 0.0))
       :evidence-kind :doc
       :retrieved-source? (boolean (:retrievedSource doc))
       :exact-path-source? (boolean (:exactPathSource doc))
       :definition-kind (some-> (:definitionKind source) name)
       :matched-tokens (token-matches query-tokens (evidence-text doc))
       :reason (str "AGraph context doc"
                    (when-let [heading (:heading source)]
                      (str " " (pr-str heading)))
                    " from " path
                    (line-label source)
                    " with provenance "
                    (or (:provenance doc) "unknown")
                    ".")})))

(defn- entity-prediction
  [root query-tokens idx entity]
  (let [path (:path entity)]
    (when (existing-file-path? root path)
      {:path path
       :source-rank (+ 1000 (inc idx))
       :confidence (bounded-confidence (:score entity))
       :evidence-score (double (or (parse-double-safe (:score entity)) 0.0))
       :evidence-kind :entity
       :retrieved-source? false
       :exact-path-source? false
       :definition-kind (some-> (:kind entity) name)
       :matched-tokens (token-matches query-tokens
                                      (str (:path entity)
                                           "\n"
                                           (:label entity)))
       :reason (str "AGraph graph entity "
                    (pr-str (:label entity))
                    " references "
                    path
                    ".")})))

(defn- candidate-file-prediction
  [root query-tokens idx candidate]
  (let [path (:path candidate)]
    (when (existing-file-path? root path)
      {:path path
       :source-rank (+ 500 (inc idx))
       :confidence (bounded-confidence (:score candidate))
       :evidence-score (* 0.6 (double (or (parse-double-safe (:score candidate)) 0.0)))
       :evidence-kind :candidate-file
       :retrieved-source? false
       :exact-path-source? false
       :definition-kind (some-> (:targetKind candidate) name)
       :matched-tokens (token-matches query-tokens
                                      (str (:path candidate)
                                           "\n"
                                           (:label candidate)))
       :reason (str "AGraph retrieved candidate file "
                    path
                    " from result rank "
                    (:rank candidate)
                    ".")})))

(defn- ranked-file-predictions
  [rows]
  (let [combine-rows (fn [path grouped-rows]
                       (let [ordered (sort-by :source-rank grouped-rows)
                             best-row (first ordered)
                             support-count (count ordered)
                             extra-count (dec support-count)
                             confidence (bounded-confidence
                                         (apply max
                                                (map :confidence ordered)))
                             matched-tokens (->> ordered
                                                 (mapcat :matched-tokens)
                                                 set)
                             doc-count (count (filter #(= :doc (:evidence-kind %))
                                                      ordered))
                             entity-count (count (filter #(= :entity (:evidence-kind %))
                                                         ordered))
                             candidate-count (count (filter #(= :candidate-file (:evidence-kind %))
                                                            ordered))
                             retrieved-source-count (count (filter :retrieved-source?
                                                                   ordered))
                             exact-path-source-count (count (filter :exact-path-source?
                                                                    ordered))
                             max-evidence-score (apply max
                                                       0.0
                                                       (map :evidence-score ordered))
                             rank-score (+ max-evidence-score
                                           (* 0.22 (min rank-score-token-cap
                                                        (count matched-tokens)))
                                           (* 0.08 support-count)
                                           (* 0.08 retrieved-source-count)
                                           (* 0.12 exact-path-source-count)
                                           (* 0.04 candidate-count)
                                           (* 0.03 entity-count))
                             metrics {:firstSourceRank (:source-rank best-row)
                                      :supportCount support-count
                                      :docCount doc-count
                                      :entityCount entity-count
                                      :candidateFileCount candidate-count
                                      :retrievedSourceCount retrieved-source-count
                                      :exactPathSourceCount exact-path-source-count
                                      :maxConfidence confidence
                                      :rankScore rank-score
                                      :matchedTokenCount (count matched-tokens)
                                      :definitionKinds (->> ordered
                                                            (keep :definition-kind)
                                                            distinct
                                                            vec)}]
                         (cond-> (assoc best-row
                                        :path path
                                        :confidence confidence
                                        :rank-score rank-score
                                        :metrics metrics)
                           (pos? extra-count)
                           (update :reason
                                   str
                                   " Additional AGraph evidence: "
                                   extra-count
                                   " more matching "
                                   (if (= 1 extra-count) "row" "rows")
                                   "."))))]
    (->> rows
         (group-by :path)
         (map (fn [[path grouped-rows]]
                (combine-rows path grouped-rows)))
         (sort-by (juxt (comp - :rank-score)
                        :source-rank
                        :path))
         (map-indexed (fn [idx row]
                        (-> row
                            (dissoc :source-rank
                                    :rank-score
                                    :evidence-score
                                    :evidence-kind
                                    :retrieved-source?
                                    :exact-path-source?
                                    :matched-tokens
                                    :definition-kind)
                            (assoc :rank (inc idx)))))
         vec)))

(defn- path-source-kind
  [path]
  (some-> path fs/file-kind normalize-source-kind))

(defn- coverage-source-kinds
  [coverage]
  (->> (or (:declaredSourceKinds coverage)
           (:declared-source-kinds coverage)
           (:sourceKinds coverage)
           (:source-kinds coverage))
       (keep normalize-source-kind)
       set))

(defn- keep-coverage-source-kind?
  [source-kinds row]
  (or (empty? source-kinds)
      (contains? source-kinds (path-source-kind (:path row)))))

(defn- context-symbols
  [packet]
  (->> (:docs packet)
       (map-indexed
        (fn [idx doc]
          (let [source (:source doc)]
            (when (and (:path source) (:heading source))
              {:name (:heading source)
               :path (:path source)
               :rank (inc idx)
               :kind (some-> (:definitionKind source) name)}))))
       (keep identity)
       (reduce (fn [best row]
                 (let [k [(:path row) (:name row)]]
                   (if (contains? best k)
                     best
                     (assoc best k row))))
               {})
       vals
       (sort-by (juxt :rank :path :name))
       vec))

(defn context-packet->agent-result
  "Convert one AGraph context packet into the benchmark agent-result contract.

  This is a deterministic agent-help baseline: it ranks files from the same
  docs/entities packet an agent would receive, without reading hidden ground
  truth or fix artifacts."
  ([packet]
   (context-packet->agent-result packet {}))
  ([packet {:keys [agent-id mode case-id root limit coverage]}]
   (let [query-tokens (text/tokenize (:query packet))
         source-kinds (coverage-source-kinds coverage)
         doc-rows (keep-indexed #(doc-prediction root query-tokens %1 %2) (:docs packet))
         entity-rows (keep-indexed #(entity-prediction root query-tokens %1 %2) (:entities packet))
         candidate-file-rows (keep-indexed #(candidate-file-prediction root query-tokens %1 %2)
                                           (:candidateFiles packet))
         candidate-files (->> (concat doc-rows
                                      entity-rows
                                      candidate-file-rows)
                              (filter #(keep-coverage-source-kind? source-kinds %))
                              ranked-file-predictions)
         suspected-files (cond->> candidate-files
                           limit (take (long limit))
                           true vec)]
     {:schema agent-result-schema
      :caseId case-id
      :agentId (or agent-id "agraph-baseline")
      :mode (or mode "agraph")
      :suspectedFiles suspected-files
      :suspectedSymbols (context-symbols packet)
      :commands (:drilldowns packet)
      :selection {:candidateFiles (count candidate-files)
                  :limit limit
                  :coverageSourceKinds (vec (sort source-kinds))}
      :summary (str "Deterministic AGraph baseline ranked "
                    (count suspected-files)
                    " suspected files from "
                    (count candidate-files)
                    " context packet file candidates.")})))

(defn- agent-baseline-context-options
  [prepared opts]
  {:project-id (:project-id prepared)
   :repo-id (:repo-id prepared)
   :retriever (keyword (or (:retriever opts) :lexical))
   :budget (long (or (:budget opts) default-agent-baseline-budget))
   :doc-limit (long (or (:doc-limit opts) default-agent-baseline-doc-limit))
   :retrieval-limit (long (or (:retrieval-limit opts) default-agent-baseline-retrieval-limit))
   :snippet-chars (long (or (:snippet-chars opts) context/default-snippet-chars))})

(defn- agent-baseline-suspect-limit
  [opts]
  (long (or (:limit opts) default-agent-baseline-suspect-limit)))

(defn agent-baseline!
  "Generate, write, and score one deterministic AGraph agent-result artifact."
  [suite case opts]
  (let [prepared (prepare-case! suite case opts)
        bench-project (agent-project prepared)
        project-path (fs/canonical-path (agent-project-path suite case opts))
        result-path (agent-baseline-result-path suite case opts)
        context-path (agent-baseline-context-path suite case opts)
        progress-path (progress-path suite case opts)
        agent-id (agent-baseline-id opts)]
    (progress-stage!
     suite
     case
     opts
     :write-agent-project
     #(write-edn-file! project-path bench-project)
     (fn [path]
       {:path (fs/canonical-path path)}))
    (store/with-node (xtdb-dir suite case opts)
      (fn [xtdb]
        (let [index-summary (progress-stage!
                             suite
                             case
                             opts
                             :index-project
                             #(project/index-project! xtdb
                                                      bench-project
                                                      {:index-profile :query})
                             #(select-keys % [:files :repos :rows :extractors]))
              system-summary (progress-stage!
                              suite
                              case
                              opts
                              :infer-project
                              #(project/infer-project! xtdb bench-project)
                              #(select-keys % [:systems :candidates :edges]))
              packet (progress-stage!
                      suite
                      case
                      opts
                      :context-packet
                      #(context/context-packet
                        xtdb
                        (get-in prepared [:input :queryText])
                        (agent-baseline-context-options prepared opts))
                      (fn [packet]
                        {:docs (count (:docs packet))
                         :entities (count (:entities packet))
                         :edges (count (:edges packet))
                         :warnings (count (:warnings packet))}))
              agent-result (progress-stage!
                            suite
                            case
                            opts
                            :agent-result
                            #(context-packet->agent-result
                              packet
                              {:agent-id agent-id
                               :mode "agraph"
                               :case-id (:case-id prepared)
                               :root (:worktreeRoot prepared)
                               :coverage (:coverage prepared)
                               :limit (agent-baseline-suspect-limit opts)})
                            (fn [result]
                              {:suspectedFiles (count (:suspectedFiles result))
                               :suspectedSymbols (count (:suspectedSymbols result))}))
              score-path (agent-score-path suite case opts result-path)
              scored (progress-stage!
                      suite
                      case
                      opts
                      :score-agent-result
                      #(assoc (score-agent-result prepared agent-result)
                              :agentResultPath (fs/canonical-path result-path)
                              :contextPacketPath (fs/canonical-path context-path))
                      (fn [scored]
                        (select-keys (:scores scored)
                                     [:fileRecallAt5
                                      :fileRecallAt10
                                      :fileRecallAt20
                                      :meanReciprocalRankFile
                                      :noiseRatioAt20])))
              baseline {:schema agent-baseline-schema
                        :suite-id (:suite-id prepared)
                        :case-id (:case-id prepared)
                        :repo-id (:repo-id prepared)
                        :project-id (:project-id prepared)
                        :agentId agent-id
                        :mode "agraph"
                        :retriever (name (keyword (or (:retriever opts)
                                                      :lexical)))
                        :suspectLimit (agent-baseline-suspect-limit opts)
                        :artifacts {:projectConfig project-path
                                    :agentResultPath (fs/canonical-path result-path)
                                    :contextPacketPath (fs/canonical-path context-path)
                                    :agentScorePath (fs/canonical-path score-path)
                                    :progressPath (fs/canonical-path progress-path)}
                        :agraph {:indexSummary index-summary
                                 :systemSummary system-summary}
                        :scores (:scores scored)}]
          (progress-stage!
           suite
           case
           opts
           :write-agent-artifacts
           (fn []
             (write-json-file! context-path packet)
             (write-json-file! result-path agent-result)
             (write-json-file! score-path scored)
             {:contextPath context-path
              :agentResultPath result-path
              :agentScorePath score-path})
           (fn [paths]
             {:contextPacketPath (fs/canonical-path (:contextPath paths))
              :agentResultPath (fs/canonical-path (:agentResultPath paths))
              :agentScorePath (fs/canonical-path (:agentScorePath paths))}))
          baseline)))))

(defn- local-vector-model
  [opts]
  (str (or (:vector-model opts) default-local-vector-model)))

(defn- local-vector-command
  [opts]
  (str (or (:vector-command opts) default-local-vector-command)))

(defn- local-vector-request
  [prepared opts agent-id]
  {:schema "agraph.benchmark.local-vector-request/v1"
   :caseId (:case-id prepared)
   :agentId agent-id
   :mode "local-vector"
   :worktreeRoot (:worktreeRoot prepared)
   :input (:input prepared)
   :task {:kind "issue-localization"
          :objective "Rank repo-relative files by local semantic vector similarity to the issue text."
          :rules ["Use only the base checkout and issue text in this request."
                  "Do not inspect the fixing diff, PR body, post-fix commits, or ground-truth artifacts."]}
   :limit (agent-baseline-suspect-limit opts)
   :model (local-vector-model opts)})

(defn- run-local-vector-command!
  [request-path result-path opts]
  (let [command (local-vector-command opts)
        model (local-vector-model opts)
        cmdline (str command
                     " "
                     (shell-quote (fs/canonical-path request-path))
                     " "
                     (shell-quote (fs/canonical-path result-path))
                     " "
                     (shell-quote model))
        {:keys [exit out err] :as process} (shell/sh "sh" "-c" cmdline)]
    (when-not (zero? exit)
      (throw (ex-info "Local vector benchmark worker failed."
                      {:command command
                       :exit exit
                       :out out
                       :err err})))
    (select-keys process [:exit :out :err])))

(defn- normalize-local-vector-result
  [agent-result prepared agent-id]
  (assoc agent-result
         :schema agent-result-schema
         :caseId (:case-id prepared)
         :agentId agent-id
         :mode "local-vector"))

(defn local-vector-baseline!
  "Generate, write, and score one local semantic-vector benchmark baseline.

  This is an optional benchmark control. It shells out to a local worker and
  keeps vector dependencies outside AGraph core."
  [suite case opts]
  (let [prepared (prepare-case! suite case opts)
        agent-id (agent-baseline-id opts)
        request-path (local-vector-request-path suite case opts)
        result-path (agent-baseline-result-path suite case opts)
        score-path (agent-score-path suite case opts result-path)
        progress-path (progress-path suite case opts)
        request (local-vector-request prepared opts agent-id)]
    (progress-stage!
     suite
     case
     opts
     :write-local-vector-request
     #(write-json-file! request-path request)
     (fn [path]
       {:path (fs/canonical-path path)}))
    (let [process (progress-stage!
                   suite
                   case
                   opts
                   :local-vector-worker
                   #(run-local-vector-command! request-path result-path opts)
                   #(select-keys % [:exit]))
          agent-result (progress-stage!
                        suite
                        case
                        opts
                        :local-vector-result
                        #(normalize-local-vector-result
                          (read-json-file result-path)
                          prepared
                          agent-id)
                        (fn [result]
                          {:suspectedFiles (count (:suspectedFiles result))
                           :suspectedSymbols (count (:suspectedSymbols result))}))
          scored (progress-stage!
                  suite
                  case
                  opts
                  :score-agent-result
                  #(assoc (score-agent-result prepared agent-result)
                          :agentResultPath (fs/canonical-path result-path)
                          :localVectorRequestPath (fs/canonical-path request-path))
                  (fn [scored]
                    (select-keys (:scores scored)
                                 [:fileRecallAt5
                                  :fileRecallAt10
                                  :fileRecallAt20
                                  :meanReciprocalRankFile
                                  :noiseRatioAt20])))
          baseline {:schema agent-baseline-schema
                    :suite-id (:suite-id prepared)
                    :case-id (:case-id prepared)
                    :repo-id (:repo-id prepared)
                    :project-id (:project-id prepared)
                    :agentId agent-id
                    :mode "local-vector"
                    :retriever "local-vector"
                    :suspectLimit (agent-baseline-suspect-limit opts)
                    :artifacts {:localVectorRequestPath (fs/canonical-path request-path)
                                :agentResultPath (fs/canonical-path result-path)
                                :agentScorePath (fs/canonical-path score-path)
                                :progressPath (fs/canonical-path progress-path)}
                    :localVector {:command (local-vector-command opts)
                                  :model (local-vector-model opts)
                                  :process process}
                    :scores (:scores scored)}]
      (progress-stage!
       suite
       case
       opts
       :write-agent-artifacts
       (fn []
         (write-json-file! result-path agent-result)
         (write-json-file! score-path scored)
         {:agentResultPath result-path
          :agentScorePath score-path})
       (fn [paths]
         {:agentResultPath (fs/canonical-path (:agentResultPath paths))
          :agentScorePath (fs/canonical-path (:agentScorePath paths))}))
      baseline)))

(defn agent-baselines!
  "Generate deterministic AGraph agent-result artifacts for selected cases."
  [suite opts]
  {:schema agent-baselines-schema
   :suite-id (:id suite)
   :baselines (mapv #(if (= :local-vector (keyword (or (:retriever opts) :lexical)))
                       (local-vector-baseline! suite % opts)
                       (agent-baseline! suite % opts))
                    (selected-cases suite (case-selector opts)))})

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

(defn context-packet->agent-hints
  "Return a compact agent-facing summary of one AGraph context packet.

  Hints are mechanically derived from retrieval docs and graph entities. They do
  not include hidden benchmark ground truth or accepted fix metadata."
  [prepared packet opts]
  (let [limit (long (or (:limit opts) default-agent-baseline-suspect-limit))
        agent-result (context-packet->agent-result
                      packet
                      {:agent-id (or (:agent-id opts) "agraph-hints")
                       :mode "agraph"
                       :case-id (:case-id prepared)
                       :root (:worktreeRoot prepared)
                       :limit limit})]
    {:schema agent-hints-schema
     :suite-id (:suite-id prepared)
     :case-id (:case-id prepared)
     :repo-id (:repo-id prepared)
     :project-id (:project-id prepared)
     :query (:query packet)
     :topFiles (:suspectedFiles agent-result)
     :topSymbols (vec (take 10 (:suspectedSymbols agent-result)))
     :topDocs (mapv hint-doc (range) (take 10 (:docs packet)))
     :candidateSystems (mapv hint-system (range) (take 10 (:entities packet)))
     :commands (:drilldowns packet)
     :answerability (:answerability packet)
     :warnings (:warnings packet)}))

(defn- write-agent-agraph-artifacts!
  [suite case prepared opts]
  (when (= "agraph" (agent-mode opts))
    (let [context-path (agent-run-context-path suite case opts)
          hints-path (agent-run-hints-path suite case opts)]
      (store/with-node (xtdb-dir suite case opts)
        (fn [xtdb]
          (let [packet (context/context-packet xtdb
                                               (get-in prepared [:input :queryText])
                                               (agent-baseline-context-options
                                                prepared
                                                opts))
                hints (context-packet->agent-hints prepared packet opts)]
            (write-json-file! context-path packet)
            (write-json-file! hints-path hints)
            {:context-path (fs/canonical-path context-path)
             :hints-path (fs/canonical-path hints-path)}))))))

(defn- agent-run-command
  [opts]
  (or (some-> (:command opts) not-empty)
      (throw (ex-info "Missing agent command." {:option "--command"}))))

(defn- agent-run-timeout-ms
  [opts]
  (long (or (:timeout-ms opts) default-agent-run-timeout-ms)))

(defn- agent-prompt-profile
  [opts]
  (let [profile (name (keyword (or (:prompt-profile opts) :standard)))]
    (when-not ((set supported-agent-prompt-profiles) profile)
      (throw (ex-info "Unknown benchmark agent prompt profile."
                      {:prompt-profile profile
                       :supported supported-agent-prompt-profiles})))
    profile))

(defn- ensure-agent-run-id!
  [opts]
  (when (blankish? (:agent-id opts))
    (throw (ex-info "Missing benchmark agent id." {:option "--agent"}))))

(defn- process-output-future
  [stream]
  (future (slurp stream)))

(defn- wait-for-process
  [process timeout-ms]
  (let [finished? (.waitFor process timeout-ms TimeUnit/MILLISECONDS)]
    (if finished?
      {:exit (.exitValue process)
       :timedOut false}
      (do
        (.destroyForcibly process)
        {:exit -1
         :timedOut true}))))

(defn- run-process!
  [command cwd env timeout-ms]
  (let [started-at (System/currentTimeMillis)
        process-builder (ProcessBuilder. ["sh" "-lc" command])
        process-env (.environment process-builder)]
    (.directory process-builder (io/file cwd))
    (doseq [[k v] env]
      (.put process-env k (str v)))
    (let [process (.start process-builder)
          out-future (process-output-future (.getInputStream process))
          err-future (process-output-future (.getErrorStream process))
          result (wait-for-process process timeout-ms)]
      (assoc result
             :durationMs (- (System/currentTimeMillis) started-at)
             :stdout @out-future
             :stderr @err-future))))

(defn- write-agent-run-logs!
  [suite case opts process-result]
  (let [stdout-path (agent-run-log-path suite case opts "stdout")
        stderr-path (agent-run-log-path suite case opts "stderr")]
    (write-text-file! stdout-path (:stdout process-result))
    (write-text-file! stderr-path (:stderr process-result))
    {:stdoutPath (fs/canonical-path stdout-path)
     :stderrPath (fs/canonical-path stderr-path)}))

(defn- json-example
  [value]
  (json/write-json-str value {:indent-str "  "}))

(defn- agent-result-json-schema
  []
  {"$schema" "https://json-schema.org/draft/2020-12/schema"
   "title" "AGraph benchmark agent result"
   "type" "object"
   "additionalProperties" false
   "required" ["schema"
               "caseId"
               "agentId"
               "mode"
               "suspectedFiles"
               "suspectedSymbols"
               "commands"
               "warnings"
               "summary"]
   "properties" {"schema" {"type" "string"
                           "enum" [agent-result-schema]}
                 "caseId" {"type" "string"}
                 "agentId" {"type" "string"}
                 "mode" {"type" "string"
                         "enum" ["agraph" "shell-only"]}
                 "suspectedFiles" {"type" "array"
                                   "items" {"type" "object"
                                            "additionalProperties" false
                                            "required" ["path"
                                                        "rank"
                                                        "confidence"
                                                        "reason"
                                                        "evidence"]
                                            "properties" {"path" {"type" "string"}
                                                          "rank" {"type" "integer"
                                                                  "minimum" 1}
                                                          "confidence" {"type" "number"
                                                                        "minimum" 0
                                                                        "maximum" 1}
                                                          "reason" {"type" "string"}
                                                          "evidence" {"type" "array"
                                                                      "items" {"type" "string"}}}}}
                 "suspectedSymbols" {"type" "array"
                                     "items" {"type" "object"
                                              "additionalProperties" false
                                              "required" ["name"
                                                          "path"
                                                          "kind"
                                                          "rank"
                                                          "confidence"
                                                          "reason"
                                                          "evidence"]
                                              "properties" {"name" {"type" "string"}
                                                            "path" {"type" "string"}
                                                            "kind" {"type" "string"}
                                                            "rank" {"type" "integer"
                                                                    "minimum" 1}
                                                            "confidence" {"type" "number"
                                                                          "minimum" 0
                                                                          "maximum" 1}
                                                            "reason" {"type" "string"}
                                                            "evidence" {"type" "array"
                                                                        "items" {"type" "string"}}}}}
                 "commands" {"type" "array"
                             "items" {"type" "string"}}
                 "warnings" {"type" "array"
                             "items" {"type" "string"}}
                 "summary" {"type" "string"}}})

(defn- write-agent-output-schema!
  [suite case opts]
  (let [schema-path (agent-run-output-schema-path suite case opts)]
    (write-json-file! schema-path (agent-result-json-schema))
    (fs/canonical-path schema-path)))

(defn- agent-prompt-profile-lines
  [profile]
  (case profile
    "fast" ["## Fast Localization Profile"
            "- Localization only. Do not patch files."
            "- Do not run full test/build suites."
            "- Use at most 8 local shell commands."
            "- Inspect at most 12 files or snippets."
            "- Prefer `rg`, focused `sed`, and packet-provided AGraph ask/explore commands."
            "- Return the best 1-5 suspected files as soon as evidence is sufficient."
            "- If structured output is active, make the final response the result JSON."
            "- Otherwise write JSON to `AGRAPH_BENCH_RESULT`; do not include prose outside JSON."
            ""]
    []))

(defn- agent-run-prompt
  [packet result-path output-schema-path opts]
  (let [profile (agent-prompt-profile opts)]
    (str/join
     "\n"
     (concat
      ["# AGraph Issue Localization Benchmark"
       ""
       "You are evaluating a coding-agent workflow against one real issue."
       "Use only the base checkout, the issue text in the packet, and AGraph output generated from that base checkout."
       "Do not inspect the fixing diff, PR body, post-fix commits, or benchmark ground-truth artifacts."
       ""
       "## Files"
       (str "- Packet JSON: " (get-in packet [:artifacts :packetPath]))
       (str "- Result JSON to write: " (fs/canonical-path result-path))
       (str "- Output JSON Schema: " output-schema-path)
       (str "- Worktree: " (:worktreeRoot packet))
       (str "- Project config: " (get-in packet [:artifacts :projectConfig]))
       (str "- XTDB path: " (get-in packet [:artifacts :xtdbPath]))
       (when-let [hints-path (get-in packet [:artifacts :agraphHintsPath])]
         (str "- AGraph hints JSON: " hints-path))
       (when-let [context-path (get-in packet [:artifacts :agraphContextPath])]
         (str "- AGraph context JSON: " context-path))
       ""
       "## Environment"
       "- `AGRAPH_BENCH_PACKET` points to the packet JSON."
       "- `AGRAPH_BENCH_AGRAPH_HINTS` points to compact AGraph hints when available."
       "- `AGRAPH_BENCH_AGRAPH_CONTEXT` points to precomputed AGraph context when available."
       "- `AGRAPH_BENCH_OUTPUT_SCHEMA` points to the JSON Schema for the result."
       "- `AGRAPH_BENCH_PROMPT_PROFILE` identifies the prompt profile for this run."
       "- `AGRAPH_BENCH_RESULT` is the only result file scored by the benchmark."
       "- `AGRAPH_BENCH_WORKTREE` is the base checkout."
       "- `AGRAPH_BENCH_PROJECT` is the generated AGraph project config."
       "- `AGRAPH_BENCH_XTDB_PATH` and `AGRAPH_XTDB_PATH` point to the graph store."
       ""]
      (agent-prompt-profile-lines profile)
      ["## Task"
       (get-in packet [:task :objective])
       ""
       "Read the packet, inspect the checkout, and write the ranked localization result JSON."
       "Return files before proposing or applying a patch."
       ""
       "## Result Contract"
       (str "Write JSON with schema `" agent-result-schema "` to `AGRAPH_BENCH_RESULT`.")
       "When your agent runner supports structured output, use `AGRAPH_BENCH_OUTPUT_SCHEMA`."
       "For structured-output runners that capture the final response, return only the JSON result as the final response and do not also shell-write the result file."
       "For plain shell runners, write the JSON result directly to `AGRAPH_BENCH_RESULT`."
       "Use repo-relative file paths from the base checkout."
       ""
       "```json"
       (json-example (agent-result-contract))
       "```"
       ""
       "## AGraph Mode"
       (if (= "agraph" (:mode packet))
         (str "AGraph is available and has already been prepared for this run. "
              "Read `AGRAPH_BENCH_AGRAPH_HINTS` first when it is set; use "
              "`AGRAPH_BENCH_AGRAPH_CONTEXT` for supporting snippets. Use live "
              "ask/explore commands only if the context artifact is missing or "
              "insufficient; run setup only if graph commands report missing data.")
         "AGraph is not part of this run. Use ordinary local shell inspection only.")
       ""
       "## Run Metadata"
       (str "- Suite: " (:suite-id packet))
       (str "- Case: " (:case-id packet))
       (str "- Repo: " (:repo-id packet))
       (str "- Project: " (:project-id packet))
       (str "- Agent: " (:agent-id opts))
       (str "- Mode: " (:mode packet))
       (str "- Prompt profile: " profile)
       ""]))))

(defn- write-agent-run-prompt!
  [suite case packet result-path output-schema-path opts]
  (let [prompt-path (agent-run-prompt-path suite case opts)]
    (write-text-file! prompt-path
                      (agent-run-prompt packet
                                        result-path
                                        output-schema-path
                                        opts))
    (fs/canonical-path prompt-path)))

(defn- normalize-agent-run-result
  [prepared agent-result opts]
  (assoc agent-result
         :schema agent-result-schema
         :caseId (:case-id prepared)
         :agentId (:agent-id opts)
         :mode (agent-mode opts)))

(defn- failure-agent-result
  [prepared opts warning]
  {:schema agent-result-schema
   :caseId (:case-id prepared)
   :agentId (:agent-id opts)
   :mode (agent-mode opts)
   :suspectedFiles []
   :suspectedSymbols []
   :commands [(agent-run-command opts)]
   :warnings [warning]
   :summary warning})

(defn- read-agent-run-result
  [prepared result-path opts process-result]
  (cond
    (not (zero? (:exit process-result)))
    {:agent-result (failure-agent-result
                    prepared
                    opts
                    (if (:timedOut process-result)
                      (str "Agent command timed out after "
                           (agent-run-timeout-ms opts)
                           " ms.")
                      (str "Agent command exited with status " (:exit process-result) ".")))
     :artifact-ok? false}

    (not (.isFile (io/file result-path)))
    {:agent-result (failure-agent-result
                    prepared
                    opts
                    "Agent command completed but did not write the result JSON artifact.")
     :artifact-ok? false}

    :else
    (try
      {:agent-result (normalize-agent-run-result prepared (read-json-file result-path) opts)
       :artifact-ok? true}
      (catch Exception e
        {:agent-result (failure-agent-result
                        prepared
                        opts
                        (str "Agent command wrote unreadable result JSON: " (.getMessage e)))
         :artifact-ok? false}))))

(defn- agent-run-env
  [packet result-path prompt-path output-schema-path opts]
  (cond-> {"AGRAPH_BENCH_SUITE_ID" (:suite-id packet)
           "AGRAPH_BENCH_CASE_ID" (:case-id packet)
           "AGRAPH_BENCH_REPO_ID" (:repo-id packet)
           "AGRAPH_BENCH_PROJECT_ID" (:project-id packet)
           "AGRAPH_BENCH_MODE" (:mode packet)
           "AGRAPH_BENCH_AGENT_ID" (:agent-id opts)
           "AGRAPH_BENCH_PACKET" (get-in packet [:artifacts :packetPath])
           "AGRAPH_BENCH_PROMPT" prompt-path
           "AGRAPH_BENCH_PROMPT_PROFILE" (agent-prompt-profile opts)
           "AGRAPH_BENCH_OUTPUT_SCHEMA" output-schema-path
           "AGRAPH_BENCH_RESULT" (fs/canonical-path result-path)
           "AGRAPH_BENCH_WORKTREE" (:worktreeRoot packet)
           "AGRAPH_BENCH_PROJECT" (get-in packet [:artifacts :projectConfig])
           "AGRAPH_BENCH_XTDB_PATH" (get-in packet [:artifacts :xtdbPath])
           "AGRAPH_XTDB_PATH" (get-in packet [:artifacts :xtdbPath])}
    (get-in packet [:artifacts :agraphContextPath])
    (assoc "AGRAPH_BENCH_AGRAPH_CONTEXT"
           (get-in packet [:artifacts :agraphContextPath]))
    (get-in packet [:artifacts :agraphHintsPath])
    (assoc "AGRAPH_BENCH_AGRAPH_HINTS"
           (get-in packet [:artifacts :agraphHintsPath]))))

(defn- prepare-agent-graph!
  [suite case prepared opts]
  (when (= "agraph" (agent-mode opts))
    (store/with-node (xtdb-dir suite case opts)
      (fn [xtdb]
        (let [bench-project (agent-project prepared)]
          {:indexSummary (project/index-project! xtdb
                                                 bench-project
                                                 {:index-profile :query})
           :systemSummary (project/infer-project! xtdb bench-project)})))))

(defn agent-run!
  "Run one external agent command against a benchmark packet, then score it."
  [suite case opts]
  (ensure-agent-run-id! opts)
  (let [prepared (prepare-case! suite case opts)
        agraph-summary (prepare-agent-graph! suite case prepared opts)
        agraph-artifacts (write-agent-agraph-artifacts! suite case prepared opts)
        packet (agent-packet-from-prepared! suite
                                            case
                                            prepared
                                            (cond-> opts
                                              (:context-path agraph-artifacts)
                                              (assoc :agraph-context-path
                                                     (:context-path agraph-artifacts))
                                              (:hints-path agraph-artifacts)
                                              (assoc :agraph-hints-path
                                                     (:hints-path agraph-artifacts))))
        result-path (agent-run-result-path suite case opts)
        run-path (agent-run-path suite case opts)
        score-path (agent-score-path suite case opts result-path)
        output-schema-path (write-agent-output-schema! suite case opts)
        prompt-path (write-agent-run-prompt! suite
                                             case
                                             packet
                                             result-path
                                             output-schema-path
                                             opts)
        command (agent-run-command opts)
        timeout-ms (agent-run-timeout-ms opts)
        _ (ensure-parent! result-path)
        process-result (run-process! command
                                     (:worktreeRoot prepared)
                                     (agent-run-env packet
                                                    result-path
                                                    prompt-path
                                                    output-schema-path
                                                    opts)
                                     timeout-ms)
        logs (write-agent-run-logs! suite case opts process-result)
        read-result (read-agent-run-result prepared result-path opts process-result)
        agent-result (:agent-result read-result)
        _ (write-json-file! result-path agent-result)
        scored (assoc (score-agent-result prepared agent-result)
                      :agentResultPath (fs/canonical-path result-path))
        status (if (:artifact-ok? read-result)
                 "passed"
                 "failed")
        run {:schema agent-run-schema
             :suite-id (:suite-id prepared)
             :case-id (:case-id prepared)
             :repo-id (:repo-id prepared)
             :project-id (:project-id prepared)
             :agentId (:agent-id opts)
             :mode (agent-mode opts)
             :promptProfile (agent-prompt-profile opts)
             :status status
             :command command
             :timeoutMs timeout-ms
             :process (select-keys process-result [:exit :timedOut :durationMs])
             :artifacts (merge {:packetPath (get-in packet [:artifacts :packetPath])
                                :promptPath prompt-path
                                :outputSchemaPath output-schema-path
                                :agraphHintsPath (get-in packet
                                                         [:artifacts :agraphHintsPath])
                                :agraphContextPath (get-in packet
                                                           [:artifacts :agraphContextPath])
                                :projectConfig (get-in packet [:artifacts :projectConfig])
                                :xtdbPath (get-in packet [:artifacts :xtdbPath])
                                :agentResultPath (fs/canonical-path result-path)
                                :agentScorePath (fs/canonical-path score-path)
                                :agentRunPath (fs/canonical-path run-path)}
                               logs)
             :agraph agraph-summary
             :scores (:scores scored)
             :warnings (get-in scored [:agent :warnings])}]
    (write-json-file! score-path scored)
    (write-json-file! run-path run)
    run))

(defn agent-runs!
  "Run an external agent command for selected benchmark cases."
  [suite opts]
  (let [runs (mapv #(agent-run! suite % opts)
                   (selected-cases suite (case-selector opts)))]
    {:schema agent-runs-schema
     :suite-id (:id suite)
     :runs runs
     :completed (count runs)
     :failed (count (filter #(= "failed" (:status %)) runs))}))

(defn- parse-long-safe
  [value]
  (cond
    (integer? value) (long value)
    (number? value) (long value)
    (str/blank? (str value)) nil
    :else (try
            (Long/parseLong (str value))
            (catch NumberFormatException _
              nil))))

(defn- parse-double-safe
  [value]
  (cond
    (number? value) (double value)
    (str/blank? (str value)) nil
    :else (try
            (Double/parseDouble (str value))
            (catch NumberFormatException _
              nil))))

(defn- relativize-path
  [root path]
  (let [path (str/trim (str path))
        file (io/file path)]
    (str/replace
     (str/replace
      (if (.isAbsolute file)
        (try
          (let [root-path (.toPath (.getCanonicalFile (io/file root)))
                file-path (.toPath (.getCanonicalFile file))]
            (str (.relativize root-path file-path)))
          (catch Exception _
            path))
        path)
      #"^\./"
      "")
     #"\\" "/")))

(defn- suspected-file-path
  [root item]
  (cond
    (string? item) (relativize-path root item)
    (map? item) (some->> (or (:path item)
                             (:file item)
                             (:filePath item)
                             (:file-path item))
                         (relativize-path root))
    :else nil))

(defn- agent-file-predictions
  [prepared agent-result]
  (let [root (:worktreeRoot prepared)
        raw-files (or (:suspectedFiles agent-result)
                      (:suspected-files agent-result)
                      (:files agent-result))]
    (->> raw-files
         (map-indexed
          (fn [idx item]
            (when-let [path (some-> (suspected-file-path root item) not-empty)]
              (let [rank (if (map? item)
                           (parse-long-safe (:rank item))
                           nil)]
                (cond-> {:path path
                         :rank (long (or rank (inc idx)))}
                  (map? item) (assoc :confidence (parse-double-safe (:confidence item))
                                     :reason (:reason item)
                                     :evidence (:evidence item)
                                     :metrics (:metrics item)))))))
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
         vec)))

(defn- missing-predicted-files
  [root predictions]
  (->> predictions
       (keep (fn [{:keys [path]}]
               (when-not (.isFile (io/file root path))
                 path)))
       vec))

(defn score-agent-result
  "Score an agent localization result against a prepared case artifact."
  [prepared agent-result]
  (let [top-files (agent-file-predictions prepared agent-result)
        result-shape {:groundTruth (:groundTruth prepared)
                      :agraph {:topFiles top-files}}
        warnings (cond-> (vec (or (:warnings agent-result) []))
                   (empty? top-files)
                   (conj "agent result did not contain suspected files")

                   (seq (missing-predicted-files (:worktreeRoot prepared) top-files))
                   (conj "agent result referenced files missing from the base checkout"))
        result-with-ranks (assoc result-shape
                                 :groundTruthRanks
                                 {:files (ground-truth-file-ranks
                                          (scoreable-changed-files
                                           (get-in result-shape [:groundTruth]))
                                          top-files)})]
    {:schema agent-score-schema
     :suite-id (:suite-id prepared)
     :case-id (:case-id prepared)
     :repo-id (:repo-id prepared)
     :project-id (:project-id prepared)
     :baseSha (:baseSha prepared)
     :fixSha (:fixSha prepared)
     :input (:input prepared)
     :inputHints (:inputHints prepared)
     :coverage (:coverage prepared)
     :groundTruth (:groundTruth prepared)
     :agent {:schema (:schema agent-result)
             :agentId (:agentId agent-result)
             :mode (:mode agent-result)
             :topFiles top-files
             :suspectedSymbols (or (:suspectedSymbols agent-result)
                                   (:suspected-symbols agent-result))
             :commands (:commands agent-result)
             :summary (:summary agent-result)
             :warnings warnings
             :missingPredictedFiles (missing-predicted-files (:worktreeRoot prepared)
                                                             top-files)}
     :groundTruthRanks (:groundTruthRanks result-with-ranks)
     :scores (score-result result-with-ranks)}))

(defn score-agent-result!
  "Read, score, and write one agent localization result artifact."
  [suite case opts]
  (let [result-file (or (:result-path opts)
                        (throw (ex-info "Missing agent result path."
                                        {:case-id (:id case)})))
        prepared (prepare-case! suite case opts)
        agent-result (read-json-file result-file)
        scored (assoc (score-agent-result prepared agent-result)
                      :agentResultPath (fs/canonical-path result-file))]
    (write-json-file! (agent-score-path suite case opts result-file) scored)
    scored))

(defn- json-file?
  [file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".json")))

(defn- agent-score-files
  [suite case opts]
  (let [dir (agent-score-dir suite case opts)]
    (when (.isDirectory dir)
      (->> (file-seq dir)
           (filter json-file?)
           (sort-by #(.getPath %))
           vec))))

(defn- agent-score-results
  [suite case opts]
  (->> (agent-score-files suite case opts)
       (map read-json-file)
       (filter #(or (blankish? (:mode opts))
                    (= (:mode opts) (get-in % [:agent :mode]))))
       (filter #(or (blankish? (:agent-id opts))
                    (= (:agent-id opts) (get-in % [:agent :agentId]))))
       vec))

(defn- progress-summary
  [suite case opts]
  (let [path (progress-path suite case opts)]
    (when (.isFile (io/file path))
      (let [progress (read-json-file path)
            events (vec (:events progress))
            completed (filter #(= "completed" (:status %)) events)
            failed (filter #(= "failed" (:status %)) events)
            last-event (last events)
            stage-rows (->> events
                            (keep (fn [{:keys [stage status elapsedMs]}]
                                    (when elapsedMs
                                      {:stage stage
                                       :status status
                                       :elapsedMs elapsedMs})))
                            vec)
            stage-elapsed (->> stage-rows
                               (group-by :stage)
                               (map (fn [[stage rows]]
                                      {:stage stage
                                       :elapsedMs (reduce + (map :elapsedMs rows))}))
                               (sort-by :stage)
                               vec)]
        (cond-> {:case-id (:id case)
                 :repo-id (:repo-id case)
                 :path (fs/canonical-path path)
                 :status (get {"started" "running"
                               "completed" "completed"
                               "failed" "failed"}
                              (:status last-event)
                              "unknown")
                 :events (count events)
                 :completedStages (count completed)
                 :failedStages (count failed)
                 :elapsedMs (reduce + (map :elapsedMs stage-rows))
                 :stages stage-rows
                 :stageElapsedMs stage-elapsed}
          (= "started" (:status last-event))
          (assoc :activeStage (:stage last-event))

          (seq failed)
          (assoc :failedStage (:stage (last failed))))))))

(defn- aggregate-progress
  [summaries]
  (let [summaries (vec summaries)
        stage-elapsed (->> summaries
                           (mapcat :stageElapsedMs)
                           (group-by :stage)
                           (map (fn [[stage rows]]
                                  {:stage stage
                                   :elapsedMs (reduce + (map :elapsedMs rows))}))
                           (sort-by :stage)
                           vec)
        slowest (->> summaries
                     (sort-by (comp - :elapsedMs))
                     (take 10)
                     (mapv #(select-keys % [:case-id
                                            :repo-id
                                            :status
                                            :activeStage
                                            :elapsedMs
                                            :failedStage])))]
    {:cases (count summaries)
     :runningCases (count (filter #(= "running" (:status %)) summaries))
     :failedCases (count (filter #(= "failed" (:status %)) summaries))
     :elapsedMs (reduce + (map :elapsedMs summaries))
     :stageElapsedMs stage-elapsed
     :slowestCases slowest}))

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

(defn- sum-score
  [results k]
  (reduce + 0 (keep #(get-in % [:scores k]) results)))

(defn- aggregate-agent-scores
  [results]
  (assoc (aggregate-scores results)
         :changedFiles (sum-score results :changedFiles)
         :scoreableChangedFiles (sum-score results :scoreableChangedFiles)
         :unsupportedGroundTruthFiles (sum-score results :unsupportedGroundTruthFiles)))

(defn- input-hint-summary
  [results]
  (let [hinted (filter #(get-in % [:inputHints :hinted]) results)
        hinted-cases (->> hinted
                          (map :case-id)
                          set
                          sort
                          vec)]
    {:inputHintedRuns (count hinted)
     :inputHintedCases (count hinted-cases)
     :inputHintedCaseIds hinted-cases}))

(defn- group-agent-scores
  [results key-path]
  (->> results
       (group-by #(or (get-in % key-path) "unknown"))
       (map (fn [[k rows]]
              {:key k
               :runs (count rows)
               :scores (aggregate-agent-scores rows)
               :inputHints (input-hint-summary rows)}))
       (sort-by :key)
       vec))

(defn- coverage-cases
  [results]
  (->> results
       (group-by :case-id)
       vals
       (keep first)))

(defn- aggregate-coverage
  [results]
  (let [cases (coverage-cases results)
        source-rows (mapcat #(get-in % [:coverage :scoreableFilesByKind]) cases)
        declared (->> cases
                      (mapcat #(get-in % [:coverage :declaredSourceKinds]))
                      distinct
                      sort
                      vec)
        missing (->> cases
                     (mapcat (fn [case]
                               (map (fn [kind]
                                      {:case-id (:case-id case)
                                       :kind kind})
                                    (get-in case
                                            [:coverage :missingDeclaredSourceKinds]))))
                     (sort-by (juxt :kind :case-id))
                     vec)
        case-kinds (->> cases
                        (mapcat (fn [case]
                                  (map (fn [kind]
                                         [kind (:case-id case)])
                                       (get-in case [:coverage :scoreableSourceKinds]))))
                        (group-by first))
        files-by-kind (->> source-rows
                           (group-by :kind)
                           (map (fn [[kind rows]]
                                  {:kind kind
                                   :cases (count (set (map second
                                                           (get case-kinds kind))))
                                   :scoreableFiles (reduce + (map :files rows))}))
                           (sort-by :kind)
                           vec)]
    {:declaredSourceKinds declared
     :scoreableSourceKinds (vec (sort (map :kind files-by-kind)))
     :scoreableFilesByKind files-by-kind
     :missingDeclaredSourceKinds missing}))

(defn report-agent-suite
  "Aggregate existing agent score artifacts."
  [suite opts]
  (let [cases (selected-cases suite (case-selector opts))
        progress (->> cases
                      (keep #(progress-summary suite % opts))
                      vec)
        progress-by-case (into {} (map (juxt :case-id identity)) progress)
        results (mapcat (fn [case]
                          (let [case-progress (get progress-by-case (:id case))]
                            (map #(cond-> %
                                    case-progress (assoc :progress case-progress))
                                 (agent-score-results suite case opts))))
                        cases)
        completed-cases (set (map :case-id results))
        missing (->> cases
                     (remove #(contains? completed-cases (:id %)))
                     (mapv :id))
        report {:schema agent-report-schema
                :suite-id (:id suite)
                :cases (count cases)
                :completed (count completed-cases)
                :runs (count results)
                :missing missing
                :scores (aggregate-agent-scores results)
                :inputHints (input-hint-summary results)
                :coverage (aggregate-coverage results)
                :timings (aggregate-progress progress)
                :caseProgress progress
                :byMode (group-agent-scores results [:agent :mode])
                :byAgent (group-agent-scores results [:agent :agentId])
                :results (mapv #(select-keys % [:case-id
                                                :repo-id
                                                :baseSha
                                                :fixSha
                                                :inputHints
                                                :coverage
                                                :agentResultPath
                                                :agent
                                                :progress
                                                :scores])
                               results)}]
    (write-json-file! (agent-report-path suite opts) report)
    report))

(defn- threshold
  [opts opt-key artifact-key]
  (when-some [value (get opts opt-key)]
    [artifact-key (double value)]))

(defn- agent-check-thresholds
  [opts]
  (into {:requireComplete (not (:allow-missing? opts))
         :allowDuplicateRuns (boolean (:allow-duplicate-runs? opts))}
        (keep (fn [[opt-key artifact-key]]
                (threshold opts opt-key artifact-key)))
        [[:min-cases :minCases]
         [:min-runs :minRuns]
         [:min-file-recall-at-5 :minFileRecallAt5]
         [:min-file-recall-at-10 :minFileRecallAt10]
         [:min-file-recall-at-20 :minFileRecallAt20]
         [:min-mrr :minMeanReciprocalRankFile]
         [:max-noise-at-20 :maxNoiseRatioAt20]
         [:min-case-file-recall-at-5 :minCaseFileRecallAt5]
         [:min-case-file-recall-at-10 :minCaseFileRecallAt10]
         [:min-case-file-recall-at-20 :minCaseFileRecallAt20]
         [:min-case-mrr :minCaseMeanReciprocalRankFile]
         [:max-case-noise-at-20 :maxCaseNoiseRatioAt20]
         [:max-input-hinted-cases :maxInputHintedCases]
         [:max-unsupported-ground-truth-files :maxUnsupportedGroundTruthFiles]]))

(defn- metric-failure
  [metric operator expected actual]
  {:metric metric
   :operator operator
   :expected expected
   :actual actual})

(defn- min-failure
  [report threshold-key metric-key metric-label]
  (when-some [expected (get-in report [:thresholds threshold-key])]
    (let [actual (double (get-in report [:report :scores metric-key] 0.0))]
      (when (< actual expected)
        (metric-failure metric-label ">=" expected actual)))))

(defn- max-failure
  [report threshold-key metric-path metric-label]
  (when-some [expected (get-in report [:thresholds threshold-key])]
    (let [actual (double (get-in report (into [:report] metric-path) 0.0))]
      (when (> actual expected)
        (metric-failure metric-label "<=" expected actual)))))

(defn- min-count-failure
  [report threshold-key report-key metric-label]
  (when-some [expected (get-in report [:thresholds threshold-key])]
    (let [actual (double (get-in report [:report report-key] 0))]
      (when (< actual expected)
        (metric-failure metric-label ">=" expected actual)))))

(defn- completeness-failures
  [report]
  (vec
   (concat
    (keep (fn [[threshold-key report-key metric-label]]
            (min-count-failure report threshold-key report-key metric-label))
          [[:minCases :cases "cases"]
           [:minRuns :runs "runs"]])
    (cond-> []
      (and (nil? (get-in report [:thresholds :minRuns]))
           (zero? (long (get-in report [:report :runs] 0))))
      (conj {:metric "runs"
             :operator ">"
             :expected 0
             :actual 0
             :message "No agent score artifacts matched the selected suite, case, and mode."})

      (and (get-in report [:thresholds :requireComplete])
           (seq (get-in report [:report :missing])))
      (conj {:metric "completed"
             :operator "="
             :expected (get-in report [:report :cases])
             :actual (get-in report [:report :completed])
             :missing (get-in report [:report :missing])
             :message "Some selected cases do not have matching agent score artifacts."})))))

(defn- run-identity
  [result]
  {:case-id (:case-id result)
   :agentId (or (get-in result [:agent :agentId]) "unknown")
   :mode (or (get-in result [:agent :mode]) "unknown")})

(defn- duplicate-run-failures
  [check]
  (when-not (get-in check [:thresholds :allowDuplicateRuns])
    (->> (get-in check [:report :results])
         (group-by run-identity)
         (keep (fn [[run-key rows]]
                 (when (> (count rows) 1)
                   (merge (metric-failure "duplicateRuns" "=" 1 (count rows))
                          run-key
                          {:agentResultPaths (mapv :agentResultPath rows)
                           :message "Multiple agent score artifacts matched one case, agent, and mode."}))))
         vec)))

(defn- case-metric-failure
  [result metric operator expected actual]
  (assoc (metric-failure metric operator expected actual)
         :case-id (:case-id result)
         :agentId (get-in result [:agent :agentId])
         :mode (get-in result [:agent :mode])))

(defn- case-min-failure
  [check result threshold-key metric-key metric-label]
  (when-some [expected (get-in check [:thresholds threshold-key])]
    (let [actual (double (get-in result [:scores metric-key] 0.0))]
      (when (< actual expected)
        (case-metric-failure result metric-label ">=" expected actual)))))

(defn- case-max-failure
  [check result threshold-key metric-key metric-label]
  (when-some [expected (get-in check [:thresholds threshold-key])]
    (let [actual (double (get-in result [:scores metric-key] 0.0))]
      (when (> actual expected)
        (case-metric-failure result metric-label "<=" expected actual)))))

(defn- case-threshold-failures
  [check]
  (let [results (get-in check [:report :results])]
    (vec
     (mapcat
      (fn [result]
        (concat
         (keep (fn [[threshold-key metric-key metric-label]]
                 (case-min-failure check result threshold-key metric-key metric-label))
               [[:minCaseFileRecallAt5 :fileRecallAt5 "case.fileRecallAt5"]
                [:minCaseFileRecallAt10 :fileRecallAt10 "case.fileRecallAt10"]
                [:minCaseFileRecallAt20 :fileRecallAt20 "case.fileRecallAt20"]
                [:minCaseMeanReciprocalRankFile
                 :meanReciprocalRankFile
                 "case.meanReciprocalRankFile"]])
         (keep (fn [[threshold-key metric-key metric-label]]
                 (case-max-failure check result threshold-key metric-key metric-label))
               [[:maxCaseNoiseRatioAt20 :noiseRatioAt20 "case.noiseRatioAt20"]])))
      results))))

(def ^:private case-diagnostic-score-keys
  [:fileRecallAt5
   :fileRecallAt10
   :fileRecallAt20
   :meanReciprocalRankFile
   :noiseRatioAt20
   :changedFiles
   :scoreableChangedFiles
   :unsupportedGroundTruthFiles])

(defn- failure-matches-result?
  [failure result]
  (and (= (:case-id failure) (:case-id result))
       (or (nil? (:agentId failure))
           (= (:agentId failure) (get-in result [:agent :agentId])))
       (or (nil? (:mode failure))
           (= (:mode failure) (get-in result [:agent :mode])))))

(defn- result-case-diagnostic
  [failures result]
  (let [result-failures (filterv #(failure-matches-result? % result) failures)]
    {:case-id (:case-id result)
     :agentId (get-in result [:agent :agentId])
     :mode (get-in result [:agent :mode])
     :agentResultPath (:agentResultPath result)
     :status (if (seq result-failures) "failed" "passed")
     :scores (select-keys (:scores result) case-diagnostic-score-keys)
     :failures result-failures}))

(defn- missing-case-diagnostic
  [case-id]
  {:case-id case-id
   :status "missing"
   :failures [{:metric "completed"
               :operator "="
               :expected 1
               :actual 0
               :message "Selected case does not have a matching agent score artifact."}]})

(defn- case-diagnostics
  [check failures]
  (vec
   (concat
    (map #(result-case-diagnostic failures %)
         (get-in check [:report :results]))
    (map missing-case-diagnostic
         (get-in check [:report :missing])))))

(defn check-agent-report
  "Return a pass/fail check over an agent benchmark report."
  [report opts]
  (let [check-base {:schema agent-check-schema
                    :suite-id (:suite-id report)
                    :thresholds (agent-check-thresholds opts)
                    :report report}
        failures (vec
                  (concat
                   (completeness-failures check-base)
                   (duplicate-run-failures check-base)
                   (keep (fn [[threshold-key metric-key metric-label]]
                           (min-failure check-base
                                        threshold-key
                                        metric-key
                                        metric-label))
                         [[:minFileRecallAt5 :fileRecallAt5 "fileRecallAt5"]
                          [:minFileRecallAt10 :fileRecallAt10 "fileRecallAt10"]
                          [:minFileRecallAt20 :fileRecallAt20 "fileRecallAt20"]
                          [:minMeanReciprocalRankFile
                           :meanReciprocalRankFile
                           "meanReciprocalRankFile"]])
                   (keep (fn [[threshold-key metric-path metric-label]]
                           (max-failure check-base
                                        threshold-key
                                        metric-path
                                        metric-label))
                         [[:maxNoiseRatioAt20 [:scores :noiseRatioAt20] "noiseRatioAt20"]
                          [:maxInputHintedCases
                           [:inputHints :inputHintedCases]
                           "inputHintedCases"]
                          [:maxUnsupportedGroundTruthFiles
                           [:scores :unsupportedGroundTruthFiles]
                           "unsupportedGroundTruthFiles"]])
                   (case-threshold-failures check-base)))]
    (assoc check-base
           :status (if (seq failures) "failed" "passed")
           :caseDiagnostics (case-diagnostics check-base failures)
           :failures failures)))

(defn check-agent-suite
  "Aggregate existing agent score artifacts and check them against thresholds."
  [suite opts]
  (let [check (check-agent-report (report-agent-suite suite opts) opts)]
    (write-json-file! (agent-check-path suite opts) check)
    check))

(def ^:private comparison-score-specs
  [{:key :fileRecallAt5
    :label "fileRecallAt5"
    :direction :higher}
   {:key :fileRecallAt10
    :label "fileRecallAt10"
    :direction :higher}
   {:key :fileRecallAt20
    :label "fileRecallAt20"
    :direction :higher}
   {:key :meanReciprocalRankFile
    :label "meanReciprocalRankFile"
    :direction :higher}
   {:key :noiseRatioAt20
    :label "noiseRatioAt20"
    :direction :lower}])

(defn- score-delta
  [baseline candidate {:keys [key label direction]} tolerance]
  (let [before (double (get baseline key 0.0))
        after (double (get candidate key 0.0))
        delta (- after before)
        regression? (case direction
                      :higher (< delta (- tolerance))
                      :lower (> delta tolerance))]
    (cond-> {:metric label
             :direction (name direction)
             :baseline before
             :candidate after
             :delta delta}
      regression? (assoc :regression? true))))

(defn- score-deltas
  [baseline candidate tolerance]
  (mapv #(score-delta baseline candidate % tolerance)
        comparison-score-specs))

(defn- report-result-by-case
  [report]
  (->> (:results report)
       (map (juxt :case-id identity))
       (into {})))

(defn- same-case-set?
  [baseline-by-case candidate-by-case]
  (= (set (keys baseline-by-case))
     (set (keys candidate-by-case))))

(defn- mark-aggregate-deltas-not-comparable
  [deltas]
  (mapv #(cond-> (dissoc % :regression?)
           (:regression? %) (assoc :ignored? true
                                   :reason "case-set-changed"))
        deltas))

(defn- case-comparison
  [baseline-by-case candidate-by-case case-id tolerance]
  (let [baseline (get baseline-by-case case-id)
        candidate (get candidate-by-case case-id)]
    (cond
      (nil? candidate)
      {:case-id case-id
       :status "missing"
       :regressions [{:metric "case.present"
                      :baseline true
                      :candidate false
                      :regression? true}]}

      (nil? baseline)
      {:case-id case-id
       :status "added"
       :regressions []}

      :else
      (let [deltas (mapv #(update % :metric (fn [metric]
                                              (str "case." metric)))
                         (score-deltas (:scores baseline)
                                       (:scores candidate)
                                       tolerance))
            regressions (filterv :regression? deltas)]
        {:case-id case-id
         :status (if (seq regressions) "regressed" "ok")
         :deltas deltas
         :regressions regressions}))))

(defn compare-agent-reports
  "Compare two agent reports and mark metric regressions.

  Higher is better for recall and MRR; lower is better for noise. The optional
  `:regression-tolerance` allows tiny floating-point or sampling drift without
  hiding meaningful regressions."
  [baseline-report candidate-report opts]
  (let [tolerance (double (or (:regression-tolerance opts) 0.0))
        baseline-by-case (report-result-by-case baseline-report)
        candidate-by-case (report-result-by-case candidate-report)
        case-ids (->> (concat (keys baseline-by-case)
                              (keys candidate-by-case))
                      distinct
                      sort
                      vec)
        aggregate-comparable? (same-case-set? baseline-by-case
                                              candidate-by-case)
        raw-aggregate-deltas (score-deltas (:scores baseline-report)
                                           (:scores candidate-report)
                                           tolerance)
        aggregate-deltas (if aggregate-comparable?
                           raw-aggregate-deltas
                           (mark-aggregate-deltas-not-comparable
                            raw-aggregate-deltas))
        aggregate-regressions (if aggregate-comparable?
                                (filterv :regression? aggregate-deltas)
                                [])
        cases (mapv #(case-comparison baseline-by-case
                                      candidate-by-case
                                      %
                                      tolerance)
                    case-ids)
        case-regressions (vec (mapcat :regressions cases))
        regressions (vec (concat aggregate-regressions case-regressions))]
    {:schema agent-compare-schema
     :suite-id (or (:suite-id candidate-report)
                   (:suite-id baseline-report))
     :status (if (seq regressions) "failed" "passed")
     :tolerance tolerance
     :aggregateComparable aggregate-comparable?
     :baseline {:cases (:cases baseline-report)
                :completed (:completed baseline-report)
                :runs (:runs baseline-report)
                :scores (:scores baseline-report)}
     :candidate {:cases (:cases candidate-report)
                 :completed (:completed candidate-report)
                 :runs (:runs candidate-report)
                 :scores (:scores candidate-report)}
     :aggregateDeltas aggregate-deltas
     :caseDeltas cases
     :regressions regressions}))

(defn compare-agent-report-files!
  "Read, compare, and write an agent report comparison artifact."
  [suite opts]
  (let [baseline-path (or (:baseline-report opts)
                          (throw (ex-info "Missing --baseline-report."
                                          {:usage "bench agent-compare <benchmark.edn> --baseline-report before.json --candidate-report after.json"})))
        candidate-path (or (:candidate-report opts)
                           (throw (ex-info "Missing --candidate-report."
                                           {:usage "bench agent-compare <benchmark.edn> --baseline-report before.json --candidate-report after.json"})))
        comparison (compare-agent-reports (read-json-file baseline-path)
                                          (read-json-file candidate-path)
                                          opts)]
    (write-json-file! (agent-compare-path suite opts) comparison)
    comparison))

(defn report-suite
  "Aggregate existing benchmark result artifacts."
  [suite opts]
  (let [cases (selected-cases suite (case-selector opts))
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
