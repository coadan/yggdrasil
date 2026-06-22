(ns ygg.project
  "Project config loading and multi-repo orchestration."
  (:require [ygg.fs :as fs]
            [ygg.extractor-plugin :as extractor-plugin]
            [ygg.index :as index]
            [ygg.plugin-package :as plugin-package]
            [ygg.report-plugin :as report-plugin]
            [ygg.system :as system]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn now-ms
  []
  (System/currentTimeMillis))

(defn- config-dir
  [path]
  (or (some-> (io/file path) .getCanonicalFile .getParentFile)
      (io/file ".")))

(defn- resolve-root
  [base root]
  (let [file (io/file root)]
    (fs/canonical-path
     (if (.isAbsolute file)
       file
       (io/file base root)))))

(defn- read-json-file
  [path]
  (json/read-json (slurp (io/file path)) :key-fn keyword))

(defn- infer-role
  [repo-id]
  (cond
    (str/includes? repo-id "cli") :tooling
    (or (str/includes? repo-id "env")
        (str/includes? repo-id "infra")
        (str/includes? repo-id "k8s")) :infrastructure
    :else :application))

(defn- repo-id-from-root
  [root]
  (let [name (some-> (io/file root) .getCanonicalFile .getName)]
    (-> (or name "repo")
        str/lower-case
        (str/replace #"[^a-z0-9._-]+" "-")
        (str/replace #"(^[-._]+|[-._]+$)" "")
        not-empty
        (or "repo"))))

(defn- read-config-data
  [path]
  (edn/read-string (slurp (io/file path))))

(def ^:private plugin-entry-kinds
  #{:package :extractor :report})

(defn- plugin-entry-kind
  [entry]
  (some-> (:kind entry) keyword))

(defn- plugin-entry-kind!
  [entry]
  (let [kind (plugin-entry-kind entry)]
    (when-not (contains? plugin-entry-kinds kind)
      (throw (ex-info "Project plugin entry declares unsupported :kind."
                      {:plugin-entry entry
                       :kind kind
                       :supported (sort plugin-entry-kinds)})))
    kind))

(defn- plugin-entries
  [data]
  (plugin-package/reject-legacy-project-plugin-keys! data)
  (let [entries (:plugins data)]
    (cond
      (nil? entries) []
      (sequential? entries) (mapv #(do (plugin-entry-kind! %) %) entries)
      :else (throw (ex-info "Project config :plugins must be a vector."
                            {:plugins entries})))))

(defn- write-config-data!
  [path data]
  (with-open [writer (io/writer path)]
    (binding [*out* writer
              *print-namespace-maps* false]
      (pprint/pprint data)))
  path)

(defn- normalize-repo
  [base repo]
  (let [repo-id (some-> (:id repo) str)]
    (when (str/blank? repo-id)
      (throw (ex-info "Project repo is missing :id." {:repo repo})))
    (when-not (:root repo)
      (throw (ex-info "Project repo is missing :root." {:repo repo})))
    {:id repo-id
     :root (resolve-root base (:root repo))
     :role (keyword (or (:role repo) :repository))}))

(defn- existing-dir
  [& paths]
  (some (fn [path]
          (when path
            (let [file (io/file path)]
              (when (.isDirectory file)
                (fs/canonical-path file)))))
        paths))

(defn- workbench-repos
  [base {:keys [workbench-root workbench-task]}]
  (let [root (resolve-root base workbench-root)
        repos-json (io/file root "repos.json")
        repos (-> (read-json-file repos-json) :repos keys sort)]
    (mapv
     (fn [repo-id]
       (let [task-root (when workbench-task
                         (io/file root ".worktrees" (str workbench-task) (name repo-id)))
             cache-root (io/file root ".workbench" "repos" (name repo-id))
             resolved (existing-dir task-root cache-root)]
         (when-not resolved
           (throw (ex-info "Workbench repo root not found."
                           {:workbench-root root
                            :workbench-task workbench-task
                            :repo-id repo-id})))
         {:id (name repo-id)
          :root resolved
          :role (infer-role (name repo-id))}))
     repos)))

(defn- normalize-repos
  [base data]
  (cond
    (seq (:repos data))
    (mapv #(normalize-repo base %) (:repos data))

    (:workbench-root data)
    (workbench-repos base data)

    :else
    (throw (ex-info "Project config is missing :repos or :workbench-root." {}))))

(defn- resolve-plugin-entry
  [base entry]
  (case (plugin-entry-kind entry)
    :package
    (let [package (plugin-package/read-package base entry)]
      {:packages [package]
       :extractors (:resolved-extractor-plugins package)
       :reports (:resolved-report-plugins package)})

    :extractor
    {:packages []
     :extractors [(dissoc entry :kind)]
     :reports []}

    :report
    {:packages []
     :extractors []
     :reports [(dissoc entry :kind)]}))

(defn- plugin-config
  [base data]
  (let [resolved (mapv #(resolve-plugin-entry base %) (plugin-entries data))
        packages (vec (mapcat :packages resolved))
        extractors (vec (mapcat :extractors resolved))
        reports (vec (mapcat :reports resolved))]
    {:packages (mapv plugin-package/package-summary packages)
     :extractors (extractor-plugin/normalize-plugins extractors)
     :reports (report-plugin/normalize-plugins reports)}))

(defn plugin-packages
  "Return normalized installed plugin package summaries for a project."
  [project]
  (vec (get-in project [:plugins :packages])))

(defn extractors
  "Return normalized extractor plugins for a project."
  [project]
  (vec (get-in project [:plugins :extractors])))

(defn reports
  "Return normalized report plugins for a project."
  [project]
  (vec (get-in project [:plugins :reports])))

(defn read-project
  "Read and normalize a project.edn file."
  [path]
  (let [base (config-dir path)
        data (read-config-data path)
        plugins (plugin-config base data)
        project-id (some-> (:id data) str)]
    (when (str/blank? project-id)
      (throw (ex-info "Project config is missing :id." {:path path})))
    (cond-> {:id project-id
             :name (str (or (:name data) project-id))
             :path (fs/canonical-path path)
             :repos (normalize-repos base data)}
      (some seq (vals plugins))
      (assoc :plugins plugins))))

(defn add-repo-to-config!
  "Add a repo entry to project config and return the normalized project.

  `repo-root` is written as a canonical path. If `repo-id` is omitted, the id is
  derived from the repo directory name. Workbench configs remain driven by
  `repos.json` and are not edited by this helper."
  [config-path repo-root {:keys [repo-id role]}]
  (let [file (io/file config-path)
        data (read-config-data file)
        canonical-root (fs/canonical-path repo-root)
        repo-id (str (or repo-id (repo-id-from-root canonical-root)))
        role (keyword (or role (infer-role repo-id)))
        existing-repos (vec (:repos data))]
    (when (and (:workbench-root data) (empty? existing-repos))
      (throw (ex-info "Workbench project repos are discovered from repos.json."
                      {:project config-path
                       :workbench-root (:workbench-root data)})))
    (when (some #(= repo-id (str (:id %))) existing-repos)
      (throw (ex-info "Project already has a repo with this id."
                      {:project config-path
                       :repo-id repo-id})))
    (when (some #(= canonical-root (resolve-root (config-dir config-path) (:root %)))
                existing-repos)
      (throw (ex-info "Project already has this repo root."
                      {:project config-path
                       :root canonical-root})))
    (write-config-data!
     file
     (assoc data :repos (conj existing-repos
                              {:id repo-id
                               :root canonical-root
                               :role role})))
    (read-project config-path)))

(defn- project-row
  [{:keys [id name]} updated-at-ms]
  {:xt/id (str "project:" id)
   :project-id id
   :name name
   :active? true
   :updated-at-ms updated-at-ms})

(defn- repo-row
  [project-id updated-at-ms {:keys [id root role]}]
  {:xt/id (str "repo:" project-id ":" id)
   :project-id project-id
   :repo-id id
   :root root
   :role role
   :active? true
   :updated-at-ms updated-at-ms})

(defn persist-project!
  "Persist project metadata rows."
  [xtdb project]
  (let [updated-at-ms (now-ms)]
    (store/commit-project! xtdb
                           (project-row project updated-at-ms)
                           (mapv #(repo-row (:id project) updated-at-ms %) (:repos project)))))

(defn- with-index-deadline
  [{:keys [index-timeout-ms index-deadline-ns] :as opts}]
  (if (or index-deadline-ns (nil? index-timeout-ms))
    opts
    (assoc opts :index-deadline-ns (index/deadline-ns index-timeout-ms))))

(defn index-project!
  "Index every repo in project config into XTDB."
  [xtdb project {:keys [dry-run? index-profile map-overlay index-timeout-ms index-deadline-ns
                        progress-fn progress-interval]
                 :or {dry-run? false
                      index-profile index/default-index-profile}}]
  (let [index-opts (with-index-deadline {:index-profile index-profile
                                         :map-overlay map-overlay
                                         :index-timeout-ms index-timeout-ms
                                         :index-deadline-ns index-deadline-ns
                                         :progress-fn progress-fn
                                         :progress-interval progress-interval
                                         :extractors (extractors project)})]
    (if dry-run?
      {:project-id (:id project)
       :status :dry-run
       :repos (mapv (fn [{:keys [id root role]}]
                      (index/index-repo! nil
                                         root
                                         (assoc index-opts
                                                :dry-run? true
                                                :project-id (:id project)
                                                :repo-id id
                                                :repo-role role)))
                    (:repos project))}
      (do
        (persist-project! xtdb project)
        {:project-id (:id project)
         :status :completed
         :repos (mapv (fn [{:keys [id root role]}]
                        (index/index-repo! xtdb
                                           root
                                           (assoc index-opts
                                                  :project-id (:id project)
                                                  :repo-id id
                                                  :repo-role role)))
                      (:repos project))}))))

(defn index-project-repo!
  "Index one repo from a project config into XTDB."
  [xtdb project repo-id {:keys [dry-run? index-profile map-overlay
                                index-timeout-ms index-deadline-ns
                                progress-fn progress-interval]
                         :or {dry-run? false
                              index-profile index/default-index-profile}}]
  (let [repo (or (some #(when (= repo-id (:id %)) %) (:repos project))
                 (throw (ex-info "Project repo not found."
                                 {:project-id (:id project)
                                  :repo-id repo-id})))
        index-opts (with-index-deadline {:index-profile index-profile
                                         :map-overlay map-overlay
                                         :index-timeout-ms index-timeout-ms
                                         :index-deadline-ns index-deadline-ns
                                         :progress-fn progress-fn
                                         :progress-interval progress-interval
                                         :extractors (extractors project)})]
    (if dry-run?
      (index/index-repo! nil
                         (:root repo)
                         (assoc index-opts
                                :dry-run? true
                                :project-id (:id project)
                                :repo-id (:id repo)
                                :repo-role (:role repo)))
      (do
        (persist-project! xtdb project)
        (index/index-repo! xtdb
                           (:root repo)
                           (assoc index-opts
                                  :project-id (:id project)
                                  :repo-id (:id repo)
                                  :repo-role (:role repo)))))))

(defn infer-project!
  "Infer and persist a derived system graph for project."
  [xtdb project]
  (persist-project! xtdb project)
  (system/infer-project! xtdb project))

(defn maintain-project
  "Return read-only maintenance findings for project."
  [xtdb project opts]
  (system/maintenance-report xtdb (:id project) opts))
