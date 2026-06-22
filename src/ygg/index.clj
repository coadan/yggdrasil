(ns ygg.index
  "Index orchestration."
  (:require [ygg.dependency :as dependency]
            [ygg.extract :as extract]
            [ygg.extractor-plugin :as extractor-plugin]
            [ygg.file-facts :as file-facts]
            [ygg.fs :as fs]
            [ygg.hash :as hash]
            [ygg.search-doc :as search-doc]
            [ygg.xtdb :as store]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.time Instant]
           [java.util Date]))

(defn now-ms
  []
  (System/currentTimeMillis))

(defn- timed
  [timings k f]
  (let [started (now-ms)
        result (f)
        finished (now-ms)]
    [result (assoc timings k (max 0 (- finished started)))]))

(defn- stats-with-timings
  [stats timings started-at-ms finished-at-ms]
  (assoc stats
         :timings-ms (assoc timings
                            :total-ms (max 0 (- finished-at-ms started-at-ms)))))

(defn deadline-ns
  [timeout-ms]
  (when (and timeout-ms (pos? (long timeout-ms)))
    (+ (System/nanoTime) (* 1000000 (long timeout-ms)))))

(defn- check-deadline!
  [deadline-ns phase extra]
  (when (and deadline-ns (not (pos? (- (long deadline-ns) (System/nanoTime)))))
    (throw (ex-info "Index deadline exceeded."
                    (merge {:phase phase}
                           extra)))))

(def default-progress-interval
  50)

(defn- progress!
  [progress-fn project-id repo-id event]
  (when (and progress-fn event)
    (progress-fn (assoc event
                        :project-id project-id
                        :repo-id repo-id))))

(defn- normalized-progress-interval
  [progress-interval]
  (max 1 (long (or progress-interval default-progress-interval))))

(defn- extraction-progress?
  [idx total progress-interval]
  (let [finished (inc (long idx))]
    (or (= finished total)
        (= 1 finished)
        (zero? (mod finished progress-interval)))))

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
  (cond-> {:xt/id (:file-id file)
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
           :run-id run-id}
    (:plugin-scanned? file) (assoc :plugin-scanned? true
                                   :plugin-ids (:plugin-ids file)
                                   :benchmark-status (:benchmark-status file))))

(def indexed-relations
  "High-confidence relations persisted by default."
  #{:defines
    :imports
    :references
    :imports-package
    :requires
    :resolves
    :version-of
    :declares-module
    :uses})

(def indexing-contract-version
  "ygg.index/v8")

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
   (extractor-fingerprint file index-profile []))
  ([file index-profile extractors]
   (let [index-profile (normalize-index-profile index-profile)]
     (str "extractor:"
          (hash/short-hash [indexing-contract-version
                            index-profile
                            (extract/extractor-fingerprint file)
                            file-facts/facts-contract-version
                            (extractor-plugin/applicable-fingerprints
                             extractors
                             file)
                            (sort indexed-relations)])))))

(defn- attach-extractor-fingerprint
  [index-profile extractors file]
  (let [file (assoc file :index-profile index-profile)]
    (assoc file :extractor-fingerprint
           (extractor-fingerprint file index-profile extractors))))

(defn- plugin-search-chunk?
  [search-plugin-ids chunk]
  (and (= :plugin (:provenance chunk))
       (contains? search-plugin-ids (:plugin-id chunk))))

(defn- profile-extraction
  [extraction index-profile extractors]
  (case (normalize-index-profile index-profile)
    :query extraction
    :graph (let [search-plugin-ids (extractor-plugin/search-chunk-plugin-ids
                                    extractors)]
             (update extraction
                     :chunks
                     #(filterv (partial plugin-search-chunk? search-plugin-ids)
                               %)))))

(defn- indexable-extraction
  [run-id project-id repo-id index-profile extractors file extraction]
  (let [annotate #(assoc % :project-id project-id :repo-id repo-id)
        plugin-edge? #(= :plugin (:provenance %))
        filtered (-> extraction
                     (profile-extraction index-profile extractors)
                     (update :nodes #(mapv annotate %))
                     (update :edges #(mapv annotate %))
                     (update :chunks #(mapv annotate %))
                     (update :file-facts #(mapv annotate %))
                     (update :diagnostics #(mapv annotate %))
                     (update :edges #(filterv (fn [edge]
                                                (or (plugin-edge? edge)
                                                    (indexed-relations (:relation edge))))
                                              %)))
        search-docs (case (normalize-index-profile index-profile)
                      :query (search-doc/build-search-docs run-id filtered)
                      :graph (if (seq (:chunks filtered))
                               (search-doc/build-search-docs
                                run-id
                                (assoc filtered :nodes [] :edges []))
                               []))]
    (assoc filtered
           :file-facts (vec (concat (file-facts/facts-for-file run-id
                                                               project-id
                                                               repo-id
                                                               file)
                                    (:file-facts filtered)))
           :search-docs search-docs)))

(defn index-repo!
  "Index root into XTDB. Returns run summary."
  [xtdb root {:keys [dry-run? project-id repo-id repo-role index-profile map-overlay
                     index-timeout-ms index-deadline-ns extractors
                     progress-fn progress-interval]
              :or {dry-run? false
                   project-id default-project-id
                   repo-id default-repo-id
                   repo-role :repository
                   index-profile default-index-profile}}]
  (let [started (now-ms)
        index-deadline-ns (or index-deadline-ns (deadline-ns index-timeout-ms))
        index-profile (normalize-index-profile index-profile)
        progress-interval (normalized-progress-interval progress-interval)
        extractors (extractor-plugin/normalize-plugins extractors)
        [root-path timings] (timed {} :canonicalize-root-ms #(fs/canonical-path root))
        _ (progress! progress-fn
                     project-id
                     repo-id
                     {:phase :repo-start
                      :root root-path
                      :index-profile index-profile
                      :dry-run? dry-run?})
        _ (check-deadline! index-deadline-ns
                           :canonicalize-root
                           {:project-id project-id
                            :repo-id repo-id})
        [files timings] (timed timings
                               :scan-ms
                               #(let [core-files (fs/scan-files root-path)
                                      core-supported-paths (->> core-files
                                                                (remove (fn [file]
                                                                          (= :unknown
                                                                             (:kind file))))
                                                                (map :path))
                                      plugin-files (fs/scan-plugin-files
                                                    root-path
                                                    (extractor-plugin/scan-specs
                                                     extractors)
                                                    core-supported-paths)
                                      plugin-paths (set (map :path plugin-files))]
                                  (->> (concat (remove (fn [file]
                                                         (contains? plugin-paths
                                                                    (:path file)))
                                                       core-files)
                                               plugin-files)
                                       (mapv (fn [file]
                                               (attach-extractor-fingerprint
                                                index-profile
                                                extractors
                                                (scoped-file project-id repo-id file))))
                                       (sort-by :path)
                                       vec)))
        _ (progress! progress-fn
                     project-id
                     repo-id
                     {:phase :scan-complete
                      :files-scanned (count files)})
        _ (check-deadline! index-deadline-ns
                           :scan
                           {:project-id project-id
                            :repo-id repo-id
                            :files-scanned (count files)})
        [snapshot timings] (timed timings
                                  :snapshot-ms
                                  #(source-snapshot project-id
                                                    repo-id
                                                    root-path
                                                    started
                                                    files))
        _ (check-deadline! index-deadline-ns
                           :snapshot
                           {:project-id project-id
                            :repo-id repo-id})
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
                 :git-sha (:git-sha snapshot)
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
                         :dependency-edges 0
                         :files-deleted 0}}]
    (if dry-run?
      (let [finished-at (now-ms)]
        (progress! progress-fn
                   project-id
                   repo-id
                   {:phase :dry-run-complete
                    :files-scanned (count files)
                    :total-ms (max 0 (- finished-at started))})
        (assoc initial
               :status :dry-run
               :finished-at-ms finished-at
               :stats (stats-with-timings (:stats initial)
                                          timings
                                          started
                                          finished-at)
               :files (mapv #(select-keys % [:path
                                             :kind
                                             :content-sha
                                             :extractor-fingerprint])
                            files)))
      (do
        (store/commit-run! xtdb (assoc initial :xt/id run-id))
        (store/commit-temporal-bundle!
         xtdb
         {:snapshot snapshot
          :run (assoc initial :xt/id run-id :status :running)
          :valid-from valid-from})
        (let [[existing-by-path timings] (timed
                                          timings
                                          :load-existing-ms
                                          #(->> (store/constrained-rows
                                                 xtdb
                                                 (:files store/tables)
                                                 {:project-id project-id
                                                  :repo-id repo-id})
                                                (map (juxt :path identity))
                                                (into {})))
              current-paths (set (map :path files))
              [removed-files timings] (timed
                                       timings
                                       :plan-deletes-ms
                                       #(->> existing-by-path
                                             vals
                                             (remove (fn [row]
                                                       (contains? current-paths (:path row))))
                                             vec))
              [planned-files timings] (timed
                                       timings
                                       :plan-changes-ms
                                       #(reduce
                                         (fn [acc file]
                                           (let [existing (get existing-by-path (:path file))]
                                             (if (and (= (:content-sha existing) (:content-sha file))
                                                      (= (:extractor-fingerprint existing)
                                                         (:extractor-fingerprint file)))
                                               (update acc :skipped inc)
                                               (update acc :changed conj
                                                       {:file file
                                                        :existing? (boolean existing)
                                                        :valid-from valid-from}))))
                                         {:changed [] :skipped 0}
                                         files))
              _ (progress! progress-fn
                           project-id
                           repo-id
                           {:phase :plan-complete
                            :files-scanned (count files)
                            :files-changed (count (:changed planned-files))
                            :files-skipped (:skipped planned-files)
                            :files-deleted (count removed-files)})
              _ (check-deadline! index-deadline-ns
                                 :plan-changes
                                 {:project-id project-id
                                  :repo-id repo-id
                                  :files-scanned (count files)
                                  :files-changed (count (:changed planned-files))
                                  :files-skipped (:skipped planned-files)})
              [parser-worker-facts timings] (timed
                                             timings
                                             :parser-worker-ms
                                             #(extract/parser-worker-batch-facts
                                               (mapv :file (:changed planned-files))))
              _ (check-deadline! index-deadline-ns
                                 :parser-worker
                                 {:project-id project-id
                                  :repo-id repo-id
                                  :files-changed (count (:changed planned-files))})
              _ (progress! progress-fn
                           project-id
                           repo-id
                           {:phase :extract-start
                            :files-changed (count (:changed planned-files))})
              [planned timings] (timed
                                 timings
                                 :extract-ms
                                 #(update planned-files
                                          :changed
                                          (fn [changed]
                                            (mapv
                                             (fn [idx {:keys [file existing? valid-from]}]
                                               (check-deadline!
                                                index-deadline-ns
                                                :extract
                                                {:project-id project-id
                                                 :repo-id repo-id
                                                 :files-changed (count changed)
                                                 :files-extracted idx
                                                 :path (:path file)})
                                               (let [file (if-let [facts (get parser-worker-facts
                                                                              (:path file))]
                                                            (assoc file
                                                                   :parser-worker-facts
                                                                   facts)
                                                            file)
                                                     core-extraction (extract/extract-file run-id file)
                                                     extraction (extractor-plugin/transform-extraction
                                                                 {:plugins extractors
                                                                  :run-id run-id
                                                                  :project-id project-id
                                                                  :repo-id repo-id
                                                                  :root-path root-path
                                                                  :file file}
                                                                 core-extraction)
                                                     entry {:file-row (->file-row run-id
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
                                                                         extractors
                                                                         file
                                                                         extraction)
                                                            :existing? existing?
                                                            :valid-from valid-from}]
                                                 (progress! progress-fn
                                                            project-id
                                                            repo-id
                                                            (when (extraction-progress?
                                                                   idx
                                                                   (count changed)
                                                                   progress-interval)
                                                              {:phase :extract-progress
                                                               :files-extracted (inc idx)
                                                               :files-changed (count changed)
                                                               :path (:path file)}))
                                                 entry))
                                             (range)
                                             changed))))
              _ (progress! progress-fn
                           project-id
                           repo-id
                           {:phase :extract-complete
                            :files-extracted (count (:changed planned))
                            :files-changed (count (:changed planned-files))})
              _ (check-deadline! index-deadline-ns
                                 :extract
                                 {:project-id project-id
                                  :repo-id repo-id
                                  :files-extracted (count (:changed planned))})
              _ (progress! progress-fn
                           project-id
                           repo-id
                           {:phase :commit-start
                            :files-indexed (count (:changed planned))})
              [result timings] (timed timings
                                      :commit-files-ms
                                      #(store/commit-files! xtdb (:changed planned)))
              _ (progress! progress-fn
                           project-id
                           repo-id
                           {:phase :commit-complete
                            :files-indexed (count (:changed planned))
                            :nodes (:nodes result)
                            :edges (:edges result)
                            :chunks (:chunks result)
                            :file-facts (:file-facts result)
                            :search-docs (:search-docs result)
                            :diagnostics (:diagnostics result)})
              _ (check-deadline! index-deadline-ns
                                 :commit-files
                                 {:project-id project-id
                                  :repo-id repo-id
                                  :files-indexed (count (:changed planned))})
              [deletes timings] (timed timings
                                       :delete-files-ms
                                       #(store/commit-file-deletes! xtdb
                                                                    removed-files
                                                                    {:valid-from valid-from}))
              _ (progress! progress-fn
                           project-id
                           repo-id
                           {:phase :delete-complete
                            :files-deleted (:files-deleted deletes)})
              _ (check-deadline! index-deadline-ns
                                 :delete-files
                                 {:project-id project-id
                                  :repo-id repo-id
                                  :files-deleted (:files-deleted deletes)})
              _ (progress! progress-fn
                           project-id
                           repo-id
                           {:phase :dependency-start})
              [dependency-result timings] (timed
                                           timings
                                           :dependency-ms
                                           #(dependency/refresh-derived-edges!
                                             xtdb
                                             project-id
                                             repo-id
                                             run-id
                                             {:valid-from valid-from
                                              :project-id project-id
                                              :repo-id repo-id
                                              :map-overlay map-overlay}))
              _ (progress! progress-fn
                           project-id
                           repo-id
                           {:phase :dependency-complete
                            :dependency-edges (:dependency-edges dependency-result)})
              _ (check-deadline! index-deadline-ns
                                 :dependency
                                 {:project-id project-id
                                  :repo-id repo-id})
              finished-at (now-ms)
              summary (-> (:stats initial)
                          (assoc :files-skipped (:skipped planned)
                                 :files-indexed (count (:changed planned))
                                 :files-deleted (:files-deleted deletes))
                          (update :nodes + (:nodes result))
                          (update :edges + (:edges result))
                          (update :chunks + (:chunks result))
                          (update :file-facts + (:file-facts result))
                          (update :search-docs + (:search-docs result))
                          (update :diagnostics + (:diagnostics result))
                          (update :dependency-edges + (:dependency-edges dependency-result))
                          (stats-with-timings timings started finished-at))
              finished (assoc initial
                              :xt/id run-id
                              :status :completed
                              :finished-at-ms finished-at
                              :stats summary)]
          (store/commit-temporal-bundle!
           xtdb
           {:run finished
            :valid-from valid-from})
          (store/commit-run! xtdb finished)
          (progress! progress-fn
                     project-id
                     repo-id
                     {:phase :repo-complete
                      :files-scanned (:files-scanned summary)
                      :files-indexed (:files-indexed summary)
                      :files-skipped (:files-skipped summary)
                      :files-deleted (:files-deleted summary)
                      :total-ms (get-in summary [:timings-ms :total-ms])})
          finished)))))
