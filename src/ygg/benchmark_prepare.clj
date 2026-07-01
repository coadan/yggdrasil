(ns ygg.benchmark-prepare
  (:require [ygg.benchmark-classes :as benchmark-classes]
            [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-progress :as benchmark-progress]
            [ygg.benchmark-score :as benchmark-score]
            [ygg.benchmark-suite :as benchmark-suite]
            [ygg.benchmark-util :as benchmark-util]
            [ygg.fs :as fs]
            [ygg.hash :as hash]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def prepared-case-schema
  "ygg.benchmark.prepared-case/v1")

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

(defn- case-fingerprint-tags
  [case]
  (->> (case-tags case)
       (remove benchmark-classes/recall-class-tag?)
       vec))

(defn- case-expectations
  [case]
  (benchmark-suite/case-expectations case))

(defn- normalize-id
  [value]
  (when-not (benchmark-util/blankish? value)
    (str value)))

(defn- id-list
  [values]
  (->> values
       (map #(if (map? %) (:id %) %))
       (keep normalize-id)
       distinct
       sort
       vec))

(defn- normalize-decision-kind
  [value]
  (some-> value name))

(defn- normalize-decision-candidate
  [candidate]
  (let [id (normalize-id (:id candidate))]
    (when (benchmark-util/blankish? id)
      (throw (ex-info "Decision candidate is missing :id."
                      {:candidate candidate})))
    (cond-> (assoc candidate :id id)
      (:kind candidate) (assoc :kind (normalize-decision-kind (:kind candidate)))
      (:paths candidate) (assoc :paths (id-list (:paths candidate)))
      (:evidencePaths candidate) (assoc :evidencePaths (id-list (:evidencePaths candidate)))
      (:evidence-paths candidate) (assoc :evidencePaths (id-list (:evidence-paths candidate))))))

(defn- decision-candidates
  [case]
  (mapv normalize-decision-candidate (:decision-candidates case)))

(defn- normalize-decision-ground-truth
  [case]
  (when-let [truth (:decision-ground-truth case)]
    (cond-> (assoc truth
                   :required (id-list (or (:required truth)
                                          (:include truth)
                                          (:expected truth)))
                   :forbidden (id-list (or (:forbidden truth)
                                           (:exclude truth))))
      (:kind truth) (assoc :kind (normalize-decision-kind (:kind truth)))
      (:acceptable-defer truth) (assoc :acceptableDefer (id-list (:acceptable-defer truth)))
      (:acceptableDefer truth) (assoc :acceptableDefer (id-list (:acceptableDefer truth))))))

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
(defn- delete-file-tree!
  [path]
  (let [file (io/file path)]
    (when (.exists file)
      (doseq [entry (reverse (file-seq file))]
        (when-not (.delete entry)
          (throw (ex-info "Failed to delete generated benchmark file."
                          {:path (.getPath entry)})))))))
(defn- safe-relative-file
  [root rel-path]
  (let [root-file (.getCanonicalFile (io/file root))
        file (.getCanonicalFile (io/file root-file rel-path))
        root-path (.toPath root-file)
        file-path (.toPath file)]
    (when-not (.startsWith file-path root-path)
      (throw (ex-info "Benchmark index file path must stay under repo root."
                      {:root (.getPath root-file)
                       :path rel-path})))
    file))
(defn- copy-index-file!
  [source-root target-root rel-path]
  (let [source (safe-relative-file source-root rel-path)
        target (safe-relative-file target-root rel-path)]
    (when-not (.isFile source)
      (throw (ex-info "Benchmark index file does not exist."
                      {:root source-root
                       :path rel-path})))
    (.mkdirs (.getParentFile target))
    (io/copy source target)))

(def ^:private bounded-index-context-kinds
  #{:manifest :dependency-lock})

(defn- ancestor-directories
  [rel-path]
  (let [parts (vec (butlast (str/split (str rel-path) #"/")))]
    (loop [parts parts
           dirs []]
      (if (seq parts)
        (recur (pop parts)
               (conj dirs (str/join "/" parts)))
        (conj dirs "")))))

(defn- direct-index-context-files
  [source-root dir-path]
  (let [dir (safe-relative-file source-root dir-path)]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(.isFile %))
           (keep (fn [file]
                   (let [rel (fs/relative-path source-root file)
                         kind (fs/file-kind rel)]
                     (when (contains? bounded-index-context-kinds kind)
                       rel))))
           sort))))

(defn- index-files-with-ancestor-context
  [source-root index-files]
  (->> index-files
       (mapcat (fn [path]
                 (cons path
                       (mapcat #(direct-index-context-files source-root %)
                               (ancestor-directories path)))))
       distinct
       vec))

(defn- ensure-index-root!
  [suite case opts repo-id worktree-root index-files]
  (if (seq index-files)
    (let [target-root (.getCanonicalFile
                       (io/file (benchmark-paths/graph-index-dir suite case opts)
                                (benchmark-paths/safe-id repo-id)))]
      (delete-file-tree! target-root)
      (.mkdirs target-root)
      (doseq [path index-files]
        (copy-index-file! worktree-root (.getPath target-root) path))
      (.getPath target-root))
    worktree-root))
(defn case-repo
  [suite case]
  (let [repo-id (str (:repo-id case))]
    (or (get (repo-by-id suite) repo-id)
        (throw (ex-info "Benchmark case references unknown repo."
                        {:suite-id (:id suite)
                         :case-id (:id case)
                         :repo-id repo-id})))))
(defn- case-repo-refs
  [case]
  (if (seq (:repos case))
    (vec (:repos case))
    [{:repo-id (:repo-id case)
      :base-sha (:base-sha case)
      :fix-sha (:fix-sha case)
      :index-files (:index-files case)
      :ground-truth (:ground-truth case)}]))
(defn- resolve-case-repo
  [suite case repo-ref]
  (let [repo-id (str (:repo-id repo-ref))
        repo (or (get (repo-by-id suite) repo-id)
                 (throw (ex-info "Benchmark case references unknown repo."
                                 {:suite-id (:id suite)
                                  :case-id (:id case)
                                  :repo-id repo-id})))
        base-sha (or (:base-sha repo-ref) (:base-sha case))
        fix-sha (or (:fix-sha repo-ref) (:fix-sha case))]
    (when (benchmark-util/blankish? base-sha)
      (throw (ex-info "Benchmark case repo is missing :base-sha."
                      {:case-id (:id case)
                       :repo-id repo-id})))
    (when (benchmark-util/blankish? fix-sha)
      (throw (ex-info "Benchmark case repo is missing :fix-sha."
                      {:case-id (:id case)
                       :repo-id repo-id})))
    (merge repo
           repo-ref
           {:id repo-id
            :repo-id repo-id
            :role (keyword (or (:role repo-ref) (:role repo) :application))
            :base-sha (str base-sha)
            :fix-sha (str fix-sha)})))
(defn case-repos
  [suite case]
  (mapv #(resolve-case-repo suite case %) (case-repo-refs case)))
(defn- multi-repo-case?
  [repos]
  (< 1 (count repos)))
(defn- qualify-file
  [multi-repo? repo-id path]
  (if multi-repo?
    {:repo-id repo-id
     :path (str path)}
    (str path)))
(defn- explicit-ground-truth-files
  [truth]
  (when (seq (:changed-files truth))
    (mapv str (:changed-files truth))))
(defn explicit-ground-truth
  [case]
  (explicit-ground-truth-files (:ground-truth case)))
(defn explicit-localization-files
  [case]
  (when (seq (get-in case [:ground-truth :localization-files]))
    (mapv str (get-in case [:ground-truth :localization-files]))))
(defn ground-truth
  [repos _case]
  (let [multi-repo? (multi-repo-case? repos)
        truth-by-repo (mapv (fn [{:keys [repo-id root base-sha fix-sha ground-truth]}]
                              (let [files (or (explicit-ground-truth-files ground-truth)
                                              (changed-files root base-sha fix-sha))
                                    localization-files (or (when (seq (:localization-files ground-truth))
                                                             (mapv str (:localization-files ground-truth)))
                                                           files)]
                                {:repo-id repo-id
                                 :changed-files (mapv #(qualify-file multi-repo?
                                                                     repo-id
                                                                     %)
                                                      files)
                                 :localization-files (mapv #(qualify-file multi-repo?
                                                                          repo-id
                                                                          %)
                                                           localization-files)
                                 :changed-symbols (mapv str (:changed-symbols ground-truth []))}))
                            repos)]
    {:changedFiles (mapv identity (mapcat :changed-files truth-by-repo))
     :localizationFiles (mapv identity (mapcat :localization-files truth-by-repo))
     :changedSymbols (mapv identity (mapcat :changed-symbols truth-by-repo))}))
(defn- roots-map?
  [root]
  (and (map? root)
       (< 1 (count root))))
(defn- single-root
  [root]
  (if (map? root)
    (val (first root))
    root))
(defn- root-for-file
  [root file]
  (cond
    (roots-map? root) (get root (benchmark-score/file-repo-id file))
    (map? root) (single-root root)
    :else root))
(defn- file-field-row
  [file]
  (benchmark-score/file-row-fields file))
(defn unsupported-ground-truth-files
  [root changed-files]
  (let [coverage-by-root (atom {})]
    (->> changed-files
         (keep (fn [file]
                 (let [file-root (root-for-file root file)
                       path (benchmark-score/file-path file)
                       rows (when file-root
                              (or (get @coverage-by-root file-root)
                                  (let [rows (->> (:files (fs/scan-file-coverage file-root))
                                                  (map (juxt :path identity))
                                                  (into {}))]
                                    (swap! coverage-by-root assoc file-root rows)
                                    rows)))
                       row (get rows path)]
                   (cond
                     (nil? row)
                     (assoc (file-field-row file)
                            :reason "missing-at-base")

                     (not (:supported? row))
                     (assoc (file-field-row file)
                            :ext (:ext row)
                            :reason (name (:skip-reason row)))))))
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
(defn- scanned-file-kinds
  [root]
  (if (roots-map? root)
    (->> root
         (map (fn [[repo-id repo-root]]
                [repo-id (scanned-path-kinds repo-root)]))
         (into {}))
    (scanned-path-kinds (single-root root))))
(defn- repo-kind-map?
  [kind-by-path-or-repo]
  (and (map? kind-by-path-or-repo)
       (seq kind-by-path-or-repo)
       (every? map? (vals kind-by-path-or-repo))))
(defn- file-source-kind
  [kind-by-repo file]
  (if (repo-kind-map? kind-by-repo)
    (path-source-kind (get kind-by-repo (benchmark-score/file-repo-id file))
                      (benchmark-score/file-path file))
    (path-source-kind kind-by-repo
                      (benchmark-score/file-path file))))
(defn coverage-filtered-ground-truth
  [case root truth]
  (let [unsupported (set (map benchmark-score/file-key
                              (:unsupportedGroundTruthFiles truth)))
        targets (->> (benchmark-score/target-ground-truth-files truth)
                     (remove #(contains? unsupported (benchmark-score/file-key %)))
                     vec)
        declared (set (declared-source-kinds case))]
    (if (empty? declared)
      {:scoreableFiles targets
       :coverageExcludedFiles []}
      (let [kind-by-path (scanned-file-kinds root)
            grouped (group-by #(contains? declared (:kind %))
                              (map (fn [path]
                                     (assoc (file-field-row path)
                                            :kind (file-source-kind kind-by-path path)))
                                   targets))]
        {:scoreableFiles (mapv #(if (:repo-id %)
                                  (select-keys % [:repo-id :path])
                                  (:path %))
                               (get grouped true))
         :coverageExcludedFiles (mapv (fn [row]
                                        (cond-> {:path (:path row)}
                                          (:repo-id row) (assoc :repo-id (:repo-id row))
                                          (:kind row) (assoc :kind (:kind row))))
                                      (get grouped false))}))))
(defn scoreable-files-by-kind
  [root truth]
  (let [unsupported (set (map benchmark-score/file-key
                              (:unsupportedGroundTruthFiles truth)))
        scoreable (->> (benchmark-score/target-ground-truth-files truth)
                       (remove #(contains? unsupported (benchmark-score/file-key %)))
                       (map benchmark-score/file-key)
                       set)]
    (->> (if (roots-map? root)
           (mapcat (fn [[repo-id repo-root]]
                     (map #(assoc % :repo-id repo-id)
                          (:files (fs/scan-file-coverage repo-root))))
                   root)
           (:files (fs/scan-file-coverage (single-root root))))
         (filter #(contains? scoreable (benchmark-score/file-key %)))
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
  {:schema "ygg.benchmark.case-fingerprint-input/v1"
   :suite-id (:id suite)
   :case-id (:id case)
   :repo-id (:repo-id case)
   :repos (:repos case)
   :base-sha (:base-sha case)
   :fix-sha (:fix-sha case)
   :result-scope (:result-scope case)
   :tags (case-fingerprint-tags case)
   :query-text (issue-text case)
   :coverage {:source-kinds (declared-source-kinds case)}
   :decision-candidates (decision-candidates case)
   :expectations (case-expectations case)
   :ground-truth (:ground-truth case)
   :decision-ground-truth (normalize-decision-ground-truth case)})

(defn agent-input-fingerprint-input
  [suite case]
  {:schema "ygg.benchmark.agent-input-fingerprint-input/v1"
   :suite-id (:id suite)
   :case-id (:id case)
   :repo-id (:repo-id case)
   :repos (:repos case)
   :base-sha (:base-sha case)
   :result-scope (:result-scope case)
   :query-text (issue-text case)
   :decision-candidates (decision-candidates case)
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
                             (filter #(let [path (benchmark-score/file-path %)
                                            display (benchmark-score/file-display %)]
                                        (and (not (benchmark-util/blankish? path))
                                             (or (str/includes? text path)
                                                 (str/includes? text display)))))
                             vec)]
    {:hinted (boolean (seq mentioned-files))
     :mentionedChangedFiles mentioned-files
     :mentionedChangedFileCount (count mentioned-files)
     :changedFileCount (count (:changedFiles truth))}))
(defn prepared-case
  [suite case repos worktree-roots graph-roots truth]
  (let [input-text (issue-text case)
        score-fingerprint (case-fingerprint suite case)
        agent-input-fingerprint (agent-input-fingerprint suite case)
        repo (first repos)
        primary-worktree-root (get worktree-roots (:repo-id repo))
        unsupported (unsupported-ground-truth-files worktree-roots
                                                    (:changedFiles truth))
        truth (assoc truth :unsupportedGroundTruthFiles unsupported)
        coverage-filter (coverage-filtered-ground-truth case worktree-roots truth)
        truth (merge truth coverage-filter)]
    (cond-> {:schema prepared-case-schema
             :suite-id (:id suite)
             :case-id (:id case)
             :repo-id (:id repo)
             :repoIds (mapv :id repos)
             :repos (mapv (fn [repo]
                            {:id (:id repo)
                             :root (get worktree-roots (:id repo))
                             :graphRoot (get graph-roots (:id repo))
                             :role (:role repo)
                             :baseSha (:base-sha repo)
                             :fixSha (:fix-sha repo)
                             :indexFiles (vec (:index-files repo))})
                          repos)
             :project-id (str (:project-id suite) "-" (:id case))
             :caseFingerprint score-fingerprint
             :agentInputFingerprint agent-input-fingerprint
             :resultScope (:result-scope case)
             :tags (case-tags case)
             :expectations (case-expectations case)
             :baseSha (:base-sha repo)
             :fixSha (:fix-sha repo)
             :worktreeRoot primary-worktree-root
             :worktreeRoots worktree-roots
             :graphRoots graph-roots
             :graphIndex (let [bounded (->> repos
                                            (filter #(seq (:index-files %)))
                                            (mapv (fn [repo]
                                                    {:repo-id (:id repo)
                                                     :root (get graph-roots (:id repo))
                                                     :files (vec (:index-files repo))})))]
                           {:bounded? (boolean (seq bounded))
                            :repos bounded})
             :input {:issueId (get-in case [:issue :id])
                     :title (get-in case [:issue :title])
                     :body (get-in case [:issue :body])
                     :comments (issue-comments (:issue case))
                     :queryText input-text}
             :inputHints (input-hints input-text truth)
             :coverage (ground-truth-coverage case worktree-roots truth)
             :groundTruth truth}
      (seq (decision-candidates case))
      (assoc :decisionCandidates (decision-candidates case))

      (normalize-decision-ground-truth case)
      (assoc :decisionGroundTruth (normalize-decision-ground-truth case)))))
(defn prepare-case!
  "Prepare one benchmark case and write its prepared JSON artifact."
  [suite case opts]
  (let [repos (case-repos suite case)
        multi-repo? (multi-repo-case? repos)
        worktree-roots (benchmark-progress/progress-stage!
                        suite
                        case
                        opts
                        :prepare-worktree
                        #(->> repos
                              (map (fn [{:keys [id root base-sha]}]
                                     [id (ensure-worktree!
                                          root
                                          base-sha
                                          (.getPath
                                           (if multi-repo?
                                             (io/file (benchmark-paths/worktree-dir
                                                       suite
                                                       case
                                                       opts)
                                                      (benchmark-paths/safe-id id))
                                             (benchmark-paths/worktree-dir suite case opts))))]))
                              (into {}))
                        (fn [roots]
                          {:worktreeRoots roots
                           :repos (count roots)}))
        truth (benchmark-progress/progress-stage!
               suite
               case
               opts
               :prepare-ground-truth
               #(ground-truth repos case)
               (fn [truth]
                 {:changedFiles (count (:changedFiles truth))
                  :localizationFiles (count (:localizationFiles truth))}))
        repos (mapv (fn [{:keys [id index-files] :as repo}]
                      (if (seq index-files)
                        (assoc repo
                               :index-files
                               (index-files-with-ancestor-context
                                (get worktree-roots id)
                                index-files))
                        repo))
                    repos)
        graph-roots (benchmark-progress/progress-stage!
                     suite
                     case
                     opts
                     :prepare-graph-index
                     #(->> repos
                           (map (fn [{:keys [id index-files]}]
                                  [id (ensure-index-root! suite
                                                          case
                                                          opts
                                                          id
                                                          (get worktree-roots id)
                                                          index-files)]))
                           (into {}))
                     (fn [roots]
                       {:graphRoots roots
                        :boundedRepos (count (filter #(seq (:index-files %)) repos))}))
        prepared (prepared-case suite case repos worktree-roots graph-roots truth)]
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
  {:schema "ygg.benchmark.prepare/v1"
   :suite-id (:id suite)
   :cases (mapv #(prepare-case! suite % opts)
                (selected-cases suite (case-selector opts)))})
