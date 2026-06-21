(ns agraph.benchmark-suite
  (:require [agraph.benchmark-paths :as benchmark-paths]
            [agraph.benchmark-util :as benchmark-util]
            [agraph.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def suite-schema
  "agraph.benchmark.suite/v1")

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
(defn- normalize-case
  [case]
  (let [case-id (some-> (:id case) str)
        repo-id (some-> (:repo-id case) str)]
    (when (benchmark-util/blankish? case-id)
      (throw (ex-info "Benchmark case is missing :id." {:case case})))
    (when (benchmark-util/blankish? repo-id)
      (throw (ex-info "Benchmark case is missing :repo-id."
                      {:case-id case-id})))
    (assoc case
           :id case-id
           :repo-id repo-id
           :base-sha (some-> (:base-sha case) str)
           :fix-sha (some-> (:fix-sha case) str)
           :tags (case-tags case)
           :expectations (case-expectations case))))
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
                           (keep (fn [case]
                                   (when-not (contains? repo-ids (:repo-id case))
                                     {:case-id (:id case)
                                      :repo-id (:repo-id case)})))
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
(defn read-suite
  "Read and normalize a benchmark suite EDN file."
  [path]
  (let [base (config-dir path)
        data (edn/read-string (slurp (io/file path)))
        suite-id (str (or (:id data) (benchmark-paths/safe-id (.getName (io/file path)))))
        repos (mapv #(normalize-repo base %) (:repos data))
        cases (mapv normalize-case (:cases data))]
    (when-not (seq (:repos data))
      (throw (ex-info "Benchmark suite is missing :repos." {:path path})))
    (when-not (seq (:cases data))
      (throw (ex-info "Benchmark suite is missing :cases." {:path path})))
    (validate-suite-shape! suite-id repos cases)
    (assoc data
           :schema suite-schema
           :id suite-id
           :project-id (str (or (:project-id data) suite-id))
           :path (fs/canonical-path path)
           :repos repos
           :cases cases)))
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
