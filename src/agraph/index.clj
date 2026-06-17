(ns agraph.index
  "Index orchestration."
  (:require [agraph.extract :as extract]
            [agraph.file-facts :as file-facts]
            [agraph.fs :as fs]
            [agraph.hash :as hash]
            [agraph.search-doc :as search-doc]
            [agraph.xtdb :as store]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.time Instant]
           [java.util Date]))

(defn now-ms
  []
  (System/currentTimeMillis))

(defn- git-sha
  [root]
  (let [{:keys [exit out]} (shell/sh "git" "-C" root "rev-parse" "HEAD")]
    (when (zero? exit)
      (str/trim out))))

(defn- git-tree-sha
  [root]
  (let [{:keys [exit out]} (shell/sh "git" "-C" root "rev-parse" "HEAD^{tree}")]
    (when (zero? exit)
      (str/trim out))))

(defn- git-commit-instant
  [root]
  (let [{:keys [exit out]} (shell/sh "git" "-C" root "show" "-s" "--format=%cI" "HEAD")]
    (when (zero? exit)
      (Date/from (Instant/parse (str/trim out))))))

(defn- git-dirty?
  [root]
  (let [{:keys [exit out]} (shell/sh "git" "-C" root "status" "--porcelain=v1" "--untracked-files=normal")]
    (when (zero? exit)
      (not (str/blank? out)))))

(defn run-id
  ([root started-at-ms] (run-id root started-at-ms nil nil))
  ([root started-at-ms project-id repo-id]
   (str "run:" (hash/short-hash [(fs/canonical-path root) started-at-ms project-id repo-id]))))

(def default-project-id "default")
(def default-repo-id "repo")

(defn scoped-index?
  [project-id repo-id]
  (not (and (= default-project-id project-id)
            (= default-repo-id repo-id))))

(defn id-scope
  [project-id repo-id]
  (when (scoped-index? project-id repo-id)
    (str "project:" project-id ":repo:" repo-id)))

(defn file-id
  "Return stable file id for a project/repo/path."
  [project-id repo-id path]
  (if (scoped-index? project-id repo-id)
    (str "file:" project-id ":" repo-id ":" path)
    (str "file:" path)))

(defn- source-snapshot
  [project-id repo-id root started-at-ms files]
  (let [git-sha (git-sha root)
        tree-sha (when git-sha (git-tree-sha root))
        dirty? (if git-sha (boolean (git-dirty? root)) true)
        basis-kind (cond
                     (and git-sha (not dirty?)) :git-commit
                     git-sha :git-dirty
                     :else :synthetic)
        basis-instant (or (when (and git-sha (not dirty?))
                            (git-commit-instant root))
                          (Date. (long started-at-ms)))
        fingerprint (hash/short-hash
                     [project-id
                      repo-id
                      git-sha
                      tree-sha
                      dirty?
                      basis-instant
                      (mapv (juxt :path :content-sha) files)])]
    (cond-> {:xt/id (str "snapshot:"
                         project-id
                         ":"
                         repo-id
                         ":"
                         (if (= :git-commit basis-kind) git-sha fingerprint))
             :project-id project-id
             :repo-id repo-id
             :dirty? dirty?
             :basis-kind basis-kind
             :basis-instant basis-instant
             :source-root root
             :content-fingerprint fingerprint}
      git-sha (assoc :git-sha git-sha)
      tree-sha (assoc :tree-sha tree-sha))))

(defn- scoped-file
  [project-id repo-id file]
  (let [scope (id-scope project-id repo-id)]
    (assoc file
           :file-id (file-id project-id repo-id (:path file))
           :id-scope scope)))

(defn- ->file-row
  [run-id snapshot-id valid-from project-id repo-id repo-root repo-role file]
  {:xt/id (:file-id file)
   :project-id project-id
   :repo-id repo-id
   :repo-root repo-root
   :repo-role repo-role
   :snapshot-id snapshot-id
   :valid-from valid-from
   :path (:path file)
   :ext (:ext file)
   :kind (:kind file)
   :content-sha (:content-sha file)
   :extractor-fingerprint (:extractor-fingerprint file)
   :mtime-ms (long (:mtime-ms file))
   :size-bytes (long (:size-bytes file))
   :active? true
   :run-id run-id})

(def indexed-relations
  "High-confidence relations persisted by default."
  #{:defines :imports :requires :declares-module :uses})

(def indexing-contract-version
  "agraph.index/v4")

(def index-profiles
  "Supported persistence profiles.

  `:query` keeps the full code/doc search surface. `:graph` keeps only rows
  needed for graph maintenance and system inference."
  #{:graph :query})

(def default-index-profile
  :query)

(defn normalize-index-profile
  [profile]
  (let [profile (keyword (or profile default-index-profile))]
    (when-not (contains? index-profiles profile)
      (throw (ex-info "Unknown index profile."
                      {:profile profile
                       :supported (sort index-profiles)})))
    profile))

(defn extractor-fingerprint
  "Return the persisted extractor fingerprint for a file record.

  The value includes extractor dispatch plus index-time relation filtering, so
  unchanged files can be reindexed when either boundary changes."
  ([file] (extractor-fingerprint file (or (:index-profile file) default-index-profile)))
  ([file index-profile]
   (let [index-profile (normalize-index-profile index-profile)]
     (str "extractor:"
          (hash/short-hash [indexing-contract-version
                            index-profile
                            (extract/extractor-fingerprint file)
                            (sort indexed-relations)])))))

(defn- attach-extractor-fingerprint
  [index-profile file]
  (let [file (assoc file :index-profile index-profile)]
    (assoc file :extractor-fingerprint (extractor-fingerprint file))))

(defn- profile-extraction
  [extraction index-profile]
  (case (normalize-index-profile index-profile)
    :query extraction
    :graph (assoc extraction :chunks [])))

(defn- indexable-extraction
  [run-id project-id repo-id index-profile file extraction]
  (let [annotate #(assoc % :project-id project-id :repo-id repo-id)
        filtered (-> extraction
                     (profile-extraction index-profile)
                     (update :nodes #(mapv annotate %))
                     (update :edges #(mapv annotate %))
                     (update :chunks #(mapv annotate %))
                     (update :diagnostics #(mapv annotate %))
                     (update :edges #(filterv (comp indexed-relations :relation) %)))
        search-docs (case (normalize-index-profile index-profile)
                      :query (search-doc/build-search-docs run-id filtered)
                      :graph [])]
    (assoc filtered
           :file-facts (file-facts/facts-for-file run-id project-id repo-id file)
           :search-docs search-docs)))

(defn index-repo!
  "Index root into XTDB. Returns run summary."
  [xtdb root {:keys [dry-run? project-id repo-id repo-role index-profile]
              :or {dry-run? false
                   project-id default-project-id
                   repo-id default-repo-id
                   repo-role :repository
                   index-profile default-index-profile}}]
  (let [started (now-ms)
        index-profile (normalize-index-profile index-profile)
        root-path (fs/canonical-path root)
        files (mapv #(attach-extractor-fingerprint index-profile
                                                   (scoped-file project-id repo-id %))
                    (fs/scan-files root-path))
        snapshot (source-snapshot project-id repo-id root-path started files)
        valid-from (:basis-instant snapshot)
        run-id (run-id root-path started project-id repo-id)
        initial {:run-id run-id
                 :snapshot-id (:xt/id snapshot)
                 :valid-from valid-from
                 :project-id project-id
                 :repo-id repo-id
                 :repo-root root-path
                 :repo-role repo-role
                 :index-profile index-profile
                 :git-sha (git-sha root-path)
                 :tree-sha (:tree-sha snapshot)
                 :status :running
                 :started-at-ms started
                 :finished-at-ms nil
                 :stats {:files-scanned (count files)
                         :files-indexed 0
                         :files-skipped 0
                         :nodes 0
                         :edges 0
                         :chunks 0
                         :file-facts 0
                         :search-docs 0
                         :diagnostics 0
                         :files-deleted 0}}]
    (if dry-run?
      (assoc initial
             :status :dry-run
             :finished-at-ms (now-ms)
             :files (mapv #(select-keys % [:path
                                           :kind
                                           :content-sha
                                           :extractor-fingerprint])
                          files))
      (do
        (store/commit-run! xtdb (assoc initial :xt/id run-id))
        (store/commit-temporal-bundle!
         xtdb
         {:snapshot snapshot
          :run (assoc initial :xt/id run-id :status :running)
          :valid-from valid-from})
        (let [existing-by-path (->> (store/all-rows xtdb (:files store/tables))
                                    (filter #(and (= project-id (:project-id %))
                                                  (= repo-id (:repo-id %))))
                                    (map (juxt :path identity))
                                    (into {}))
              current-paths (set (map :path files))
              removed-files (->> existing-by-path
                                 vals
                                 (remove #(contains? current-paths (:path %)))
                                 vec)
              planned (reduce
                       (fn [acc file]
                         (let [existing (get existing-by-path (:path file))]
                           (if (and (= (:content-sha existing) (:content-sha file))
                                    (= (:extractor-fingerprint existing)
                                       (:extractor-fingerprint file)))
                             (update acc :skipped inc)
                             (update acc :changed conj
                                     {:file-row (->file-row run-id
                                                            (:xt/id snapshot)
                                                            valid-from
                                                            project-id
                                                            repo-id
                                                            root-path
                                                            repo-role
                                                            file)
                                      :extraction (indexable-extraction
                                                   run-id
                                                   project-id
                                                   repo-id
                                                   index-profile
                                                   file
                                                   (extract/extract-file run-id file))
                                      :existing? (boolean existing)
                                      :valid-from valid-from}))))
                       {:changed [] :skipped 0}
                       files)
              result (store/commit-files! xtdb (:changed planned))
              deletes (store/commit-file-deletes! xtdb removed-files {:valid-from valid-from})
              summary (-> (:stats initial)
                          (assoc :files-skipped (:skipped planned)
                                 :files-indexed (count (:changed planned))
                                 :files-deleted (:files-deleted deletes))
                          (update :nodes + (:nodes result))
                          (update :edges + (:edges result))
                          (update :chunks + (:chunks result))
                          (update :file-facts + (:file-facts result))
                          (update :search-docs + (:search-docs result))
                          (update :diagnostics + (:diagnostics result)))
              finished (assoc initial
                              :xt/id run-id
                              :status :completed
                              :finished-at-ms (now-ms)
                              :stats summary)]
          (store/commit-temporal-bundle!
           xtdb
           {:run finished
            :valid-from valid-from})
          (store/commit-run! xtdb finished)
          finished)))))
