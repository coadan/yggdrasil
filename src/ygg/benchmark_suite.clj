(ns ygg.benchmark-suite
  (:require [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-util :as benchmark-util]
            [ygg.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def suite-schema
  "ygg.benchmark.suite/v1")

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
(defn- include-path
  [base include]
  (let [path (cond
               (string? include) include
               (map? include) (:path include)
               :else nil)]
    (when (benchmark-util/blankish? path)
      (throw (ex-info "Benchmark suite include is missing :path."
                      {:include include})))
    (canonical-or-relative base path)))
(defn- normalize-repo
  [base repo]
  (let [repo-id (some-> (:id repo) str)]
    (when (benchmark-util/blankish? repo-id)
      (throw (ex-info "Benchmark repo is missing :id." {:repo repo})))
    (when-not (:root repo)
      (throw (ex-info "Benchmark repo is missing :root." {:repo repo})))
    (assoc repo
           :id repo-id
           :root (canonical-or-relative base (:root repo))
           :role (keyword (or (:role repo) :application)))))
(defn normalize-case-tag
  [tag]
  (some-> tag name benchmark-paths/safe-id not-empty))
(defn case-tags
  [case]
  (->> (:tags case)
       (keep normalize-case-tag)
       distinct
       sort
       vec))

(defn case-expectations
  [case]
  (when-let [expectations (:expectations case)]
    expectations))
(defn- normalize-index-file
  [path]
  (let [path (some-> path str (str/replace "\\" "/") str/trim)]
    (when (benchmark-util/blankish? path)
      (throw (ex-info "Benchmark index file path is blank." {:path path})))
    (when (or (.isAbsolute (io/file path))
              (some #{".."} (str/split path #"/+")))
      (throw (ex-info "Benchmark index file path must stay repo-relative."
                      {:path path})))
    (str/replace path #"^\./+" "")))
(defn- normalize-index-files
  [repo]
  (when (seq (:index-files repo))
    (->> (:index-files repo)
         (map normalize-index-file)
         distinct
         sort
         vec)))
(defn- normalize-case-repo
  [case-id repo]
  (let [repo-id (some-> (or (:repo-id repo) (:id repo)) str)]
    (when (benchmark-util/blankish? repo-id)
      (throw (ex-info "Benchmark case repo is missing :repo-id."
                      {:case-id case-id
                       :repo repo})))
    (cond-> (assoc repo :repo-id repo-id)
      (:base-sha repo) (assoc :base-sha (str (:base-sha repo)))
      (:fix-sha repo) (assoc :fix-sha (str (:fix-sha repo)))
      (:role repo) (assoc :role (keyword (:role repo)))
      (seq (:index-files repo)) (assoc :index-files (normalize-index-files repo)))))
(defn case-repo-ids
  [case]
  (if (seq (:repos case))
    (->> (:repos case)
         (map :repo-id)
         distinct
         vec)
    [(:repo-id case)]))
(defn- normalize-case
  [case]
  (let [case-id (some-> (:id case) str)
        case-repos (mapv #(normalize-case-repo case-id %) (:repos case))
        repo-id (or (some-> (:repo-id case) str)
                    (:repo-id (first case-repos)))]
    (when (benchmark-util/blankish? case-id)
      (throw (ex-info "Benchmark case is missing :id." {:case case})))
    (when (benchmark-util/blankish? repo-id)
      (throw (ex-info "Benchmark case is missing :repo-id."
                      {:case-id case-id})))
    (cond-> (assoc case
                   :id case-id
                   :repo-id repo-id
                   :base-sha (some-> (:base-sha case) str)
                   :fix-sha (some-> (:fix-sha case) str)
                   :tags (case-tags case)
                   :expectations (case-expectations case))
      (seq case-repos)
      (assoc :repos case-repos))))
(defn- duplicate-values
  [values]
  (->> values
       frequencies
       (keep (fn [[value n]]
               (when (< 1 n)
                 value)))
       sort
       vec))
(defn- suite-shape-errors
  [repos cases]
  (let [repo-id-dups (duplicate-values (map :id repos))
        case-id-dups (duplicate-values (map :id cases))
        repo-ids (set (map :id repos))
        unknown-repos (->> cases
                           (mapcat (fn [case]
                                     (keep (fn [repo-id]
                                             (when-not (contains? repo-ids repo-id)
                                               {:case-id (:id case)
                                                :repo-id repo-id}))
                                           (case-repo-ids case))))
                           (sort-by (juxt :repo-id :case-id))
                           vec)]
    (cond-> []
      (seq repo-id-dups)
      (conj {:kind :duplicate-repo-ids
             :ids repo-id-dups})

      (seq case-id-dups)
      (conj {:kind :duplicate-case-ids
             :ids case-id-dups})

      (seq unknown-repos)
      (conj {:kind :unknown-case-repos
             :cases unknown-repos}))))
(defn- validate-suite-shape!
  [suite-id repos cases]
  (let [errors (suite-shape-errors repos cases)]
    (when (seq errors)
      (throw (ex-info "Benchmark suite has ambiguous or invalid ids."
                      {:suite-id suite-id
                       :errors errors})))))
(defn- same-repo-definition?
  [a b]
  (= (select-keys a [:id :root :role])
     (select-keys b [:id :root :role])))
(defn- repo-source
  [repo]
  (:suite-source (meta repo)))
(defn- dedupe-compatible-repos
  [repos]
  (reduce (fn [acc repo]
            (if-let [existing (some #(when (= (:id %) (:id repo)) %) acc)]
              (if (and (same-repo-definition? existing repo)
                       (not= (repo-source existing) (repo-source repo)))
                acc
                (conj acc repo))
              (conj acc repo)))
          []
          repos))
(defn- with-repo-source
  [source repo]
  (with-meta repo {:suite-source source}))
(defn- included-suite-summary
  [suite]
  {:id (:id suite)
   :path (:path suite)
   :repos (count (:repos suite))
   :cases (count (:cases suite))})
(declare read-suite-data)
(defn- read-included-suites
  [base includes seen]
  (mapv #(read-suite-data (include-path base %) seen) includes))
(defn- read-suite-data
  [path seen]
  (let [path (fs/canonical-path path)]
    (when (contains? seen path)
      (throw (ex-info "Benchmark suite includes form a cycle."
                      {:path path
                       :include-stack (vec seen)})))
    (let [base (config-dir path)
          data (edn/read-string (slurp (io/file path)))
          included (read-included-suites base
                                         (:include-suites data)
                                         (conj seen path))
          suite-id (str (or (:id data)
                            (benchmark-paths/safe-id (.getName (io/file path)))))
          repos (dedupe-compatible-repos
                 (vec (concat (mapcat :repos included)
                              (map #(with-repo-source path
                                      (normalize-repo base %))
                                   (:repos data)))))
          cases (vec (concat (mapcat :cases included)
                             (map normalize-case (:cases data))))]
      (when-not (seq repos)
        (throw (ex-info "Benchmark suite is missing :repos." {:path path})))
      (when-not (seq cases)
        (throw (ex-info "Benchmark suite is missing :cases." {:path path})))
      (validate-suite-shape! suite-id repos cases)
      (cond-> (assoc data
                     :schema suite-schema
                     :id suite-id
                     :project-id (str (or (:project-id data) suite-id))
                     :path path
                     :repos repos
                     :cases cases)
        (seq included)
        (assoc :included-suites (mapv included-suite-summary included))))))
(defn read-suite
  "Read and normalize a benchmark suite EDN file."
  [path]
  (read-suite-data path #{}))
(defn repo-by-id
  [suite]
  (into {} (map (juxt :id identity)) (:repos suite)))
(defn selected-cases
  "Return suite cases, optionally narrowed to one or more case ids."
  [suite selector]
  (let [cases (:cases suite)
        case-ids (cond
                   (benchmark-util/blankish? selector) []
                   (sequential? selector) (->> selector
                                               (map str)
                                               (remove benchmark-util/blankish?)
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
(defn case-selector
  [opts]
  (or (:case-ids opts)
      (:case-id opts)))
