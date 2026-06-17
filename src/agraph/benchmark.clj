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

(def agent-packet-schema
  "agraph.benchmark.agent-packet/v1")

(def agent-result-schema
  "agraph.benchmark.agent-result/v1")

(def agent-score-schema
  "agraph.benchmark.agent-score/v1")

(def agent-report-schema
  "agraph.benchmark.agent-report/v1")

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

(defn- agent-project-path
  [suite case opts]
  (io/file (case-output-dir suite case opts) "agent-project.edn"))

(defn- agent-packet-path
  [suite case opts]
  (io/file (case-output-dir suite case opts) "agent-packet.json"))

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

(defn- write-edn-file!
  [path value]
  (ensure-parent! path)
  (spit (io/file path) (str (pr-str value) "\n"))
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
     :inputHints (input-hints input-text truth)
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

(defn- unsupported-ground-truth-paths
  [ground-truth]
  (set (map :path (:unsupportedGroundTruthFiles ground-truth))))

(defn- scoreable-changed-files
  [ground-truth]
  (let [unsupported (unsupported-ground-truth-paths ground-truth)]
    (->> (:changedFiles ground-truth)
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

(defn- shell-quote
  [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))

(defn- env-command
  [xtdb-path command & args]
  (str "AGRAPH_XTDB_PATH="
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
  (cond-> {:shell ["Inspect the checkout with ordinary local commands such as git, rg, find, sed, and tests."
                   "Do not read the fixing diff, PR, post-fix commits, or ground-truth artifacts."]}
    (= "agraph" mode)
    (assoc :agraph
           {:projectConfig project-path
            :xtdbPath xtdb-path
            :setupCommand (env-command xtdb-path "bb" "sync" project-path)
            :askCommand (env-command xtdb-path
                                     "bb"
                                     "ask"
                                     (get-in prepared [:input :queryText])
                                     "--project"
                                     (:project-id prepared)
                                     "--json")
            :exploreCommand (env-command xtdb-path
                                         "bb"
                                         "explore"
                                         "create"
                                         (get-in prepared [:input :queryText])
                                         "--project"
                                         (:project-id prepared)
                                         "--json")})))

(defn- agent-result-contract
  []
  {:schema agent-result-schema
   :caseId "case id from the packet"
   :agentId "stable id for the agent run"
   :mode "agraph or shell-only"
   :suspectedFiles [{:path "repo-relative/path.ext"
                     :rank 1
                     :confidence 0.0
                     :reason "short evidence-based reason"}]
   :suspectedSymbols []
   :commands []
   :summary "brief rationale"})

(defn agent-packet!
  "Prepare one case and write a provider-neutral agent localization packet."
  [suite case opts]
  (let [prepared (prepare-case! suite case opts)
        mode (agent-mode opts)
        project-path (fs/canonical-path (agent-project-path suite case opts))
        xtdb-path (fs/canonical-path (xtdb-dir suite case opts))
        packet-path (fs/canonical-path (agent-packet-path suite case opts))
        project-config (agent-project prepared)
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
                :artifacts {:projectConfig project-path
                            :packetPath packet-path
                            :xtdbPath xtdb-path}
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

(defn agent-packets!
  "Write agent localization packets for selected benchmark cases."
  [suite opts]
  {:schema "agraph.benchmark.agent-packets/v1"
   :suite-id (:id suite)
   :packets (mapv #(agent-packet! suite % opts)
                  (selected-cases suite (:case-id opts)))})

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
                                     :evidence (:evidence item)))))))
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
        warnings (cond-> []
                   (empty? top-files)
                   (conj "agent result did not contain suspected files")

                   (seq (missing-predicted-files (:worktreeRoot prepared) top-files))
                   (conj "agent result referenced files missing from the base checkout"))
        result-with-ranks (assoc result-shape
                                 :groundTruthRanks
                                 {:files (ground-truth-file-ranks
                                          (get-in result-shape [:groundTruth :changedFiles])
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
       vec))

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

(defn report-agent-suite
  "Aggregate existing agent score artifacts."
  [suite opts]
  (let [cases (selected-cases suite (:case-id opts))
        results (mapcat #(agent-score-results suite % opts) cases)
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
                :byMode (group-agent-scores results [:agent :mode])
                :byAgent (group-agent-scores results [:agent :agentId])
                :results (mapv #(select-keys % [:case-id
                                                :repo-id
                                                :baseSha
                                                :fixSha
                                                :inputHints
                                                :agentResultPath
                                                :agent
                                                :scores])
                               results)}]
    (write-json-file! (agent-report-path suite opts) report)
    report))

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
