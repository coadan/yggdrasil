(ns ygg.project
  "Project config loading and multi-repo orchestration."
  (:require [ygg.fs :as fs]
            [ygg.extractor-plugin :as extractor-plugin]
            [ygg.index :as index]
            [ygg.index-maintenance :as index-maintenance]
            [ygg.plugin-package :as plugin-package]
            [ygg.report-plugin :as report-plugin]
            [ygg.system :as system]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
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
      (prn data)))
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
     :role (keyword (or (:role repo) :repository))
     :ignore-paths (mapv str (:ignore-paths repo))}))

(defn- duplicate-values
  [f rows]
  (->> rows
       (map f)
       frequencies
       (filter (fn [[_ n]] (< 1 n)))
       (mapv first)))

(defn- validate-unique-repos!
  [repos]
  (when-let [ids (seq (duplicate-values :id repos))]
    (throw (ex-info "Project config resolves duplicate repo ids."
                    {:repo-ids ids})))
  (when-let [roots (seq (duplicate-values :root repos))]
    (throw (ex-info "Project config resolves duplicate repo roots."
                    {:repo-roots roots})))
  repos)

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
  (validate-unique-repos!
   (cond
     (:workbench-root data)
     (vec (concat (mapv #(normalize-repo base %) (:repos data))
                  (workbench-repos base data)))

     (seq (:repos data))
     (mapv #(normalize-repo base %) (:repos data))

     :else
     (throw (ex-info "Project config is missing :repos or :workbench-root." {})))))

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

(def ^:private index-maintenance-executor-types
  #{:openai-compatible :anthropic-compatible :command-harness})

(def ^:private index-maintenance-api-providers
  #{:deepseek :openrouter})

(def ^:private index-maintenance-apply-modes
  #{:complete-only :validate-only})

(def ^:private index-maintenance-reasoning-levels
  #{"low" "medium" "high" "xhigh"})

(def ^:private maintenance-schedule-tasks
  #{:sync})

(def ^:private embedding-providers
  #{:local :openrouter :openai})

(defn- normalize-optional-long
  [value key]
  (when (some? value)
    (try
      (Long/parseLong (str value))
      (catch NumberFormatException ex
        (throw (ex-info "Project config value must be an integer."
                        {:key key
                         :value value}
                        ex))))))

(defn- normalize-embeddings
  [data]
  (when-let [embeddings (:embeddings data)]
    (let [provider (some-> (:provider embeddings) keyword)]
      (when-not provider
        (throw (ex-info "Project embeddings config requires :provider."
                        {:embeddings embeddings})))
      (when-not (contains? embedding-providers provider)
        (throw (ex-info "Project embeddings config declares unsupported :provider."
                        {:provider provider
                         :supported (sort embedding-providers)})))
      (cond-> {:provider provider}
        (:model embeddings)
        (assoc :model (str (:model embeddings)))

        (some? (:request-timeout-ms embeddings))
        (assoc :request-timeout-ms
               (normalize-optional-long (:request-timeout-ms embeddings)
                                        :request-timeout-ms))

        (some? (:max-retries embeddings))
        (assoc :max-retries
               (normalize-optional-long (:max-retries embeddings)
                                        :max-retries))))))

(defn- deepseek-v4+-model?
  [model]
  (boolean
   (re-matches #"^(?:deepseek/)?deepseek-v(?:[4-9]|[1-9][0-9]+)(?:[-._/].*)?$"
               (str model))))

(defn- normalize-work-kinds
  [executor]
  (let [kinds (:kinds executor)]
    (when-not (and (or (sequential? kinds) (set? kinds)) (seq kinds))
      (throw (ex-info "Index maintenance executor requires non-empty :kinds."
                      {:executor-id (:id executor)})))
    (set (map name kinds))))

(defn- normalize-reasoning
  [executor]
  (let [value (or (:reasoning executor) "medium")
        reasoning (if (keyword? value) (name value) (str value))]
    (when-not (contains? index-maintenance-reasoning-levels reasoning)
      (throw (ex-info "Index maintenance executor reasoning level is not supported."
                      {:executor-id (:id executor)
                       :reasoning reasoning
                       :supported (sort index-maintenance-reasoning-levels)})))
    reasoning))

(defn- normalize-api-executor
  [executor]
  (let [provider (keyword (or (:provider executor) :deepseek))
        model (str (or (:model executor)
                       (case provider
                         :openrouter "deepseek/deepseek-v4-flash"
                         "deepseek-v4-flash")))]
    (when-not (contains? index-maintenance-api-providers provider)
      (throw (ex-info "Index maintenance API executor only supports DeepSeek or OpenRouter."
                      {:executor-id (:id executor)
                       :provider provider
                       :supported (sort index-maintenance-api-providers)})))
    (when-not (deepseek-v4+-model? model)
      (throw (ex-info "Index maintenance API executor model must be DeepSeek V4 or newer."
                      {:executor-id (:id executor)
                       :model model})))
    (assoc executor
           :provider provider
           :model model
           :env (str (or (:env executor)
                         (case provider
                           :openrouter "YGG_OPENROUTER_API_KEY"
                           "YGG_DEEPSEEK_API_KEY"))))))

(defn- normalize-command-executor
  [executor]
  (let [command (:command executor)]
    (when-not (and (sequential? command) (seq command))
      (throw (ex-info "Command index maintenance executor requires non-empty :command."
                      {:executor-id (:id executor)})))
    (assoc executor
           :command (mapv str command)
           :timeout-ms (long (or (:timeout-ms executor) 600000)))))

(defn- normalize-index-maintenance-executor
  [executor]
  (let [id (some-> (:id executor) str)
        type (keyword (:type executor))]
    (when (str/blank? id)
      (throw (ex-info "Index maintenance executor is missing :id." {:executor executor})))
    (when-not (contains? index-maintenance-executor-types type)
      (throw (ex-info "Index maintenance executor declares unsupported :type."
                      {:executor-id id
                       :type type
                       :supported (sort index-maintenance-executor-types)})))
    (cond-> (assoc executor
                   :id id
                   :type type
                   :kinds (normalize-work-kinds executor)
                   :reasoning (normalize-reasoning executor))
      (contains? #{:openai-compatible :anthropic-compatible} type)
      normalize-api-executor

      (= :command-harness type)
      normalize-command-executor)))

(defn- normalize-index-maintenance-apply
  [apply-config]
  (let [apply-config (or apply-config {})
        mode (keyword (or (:mode apply-config) :complete-only))]
    (when-not (contains? index-maintenance-apply-modes mode)
      (throw (ex-info "Index maintenance worker apply mode is not supported yet."
                      {:mode mode
                       :supported (sort index-maintenance-apply-modes)})))
    (assoc apply-config :mode mode)))

(defn- normalize-maintenance-schedule
  [schedule]
  (let [task (keyword (or (:task schedule) :sync))
        id (str (or (:id schedule) (name task)))
        every-minutes (long (or (:every-minutes schedule) 60))]
    (when (str/blank? id)
      (throw (ex-info "Maintenance schedule requires an id." {:schedule schedule})))
    (when-not (contains? maintenance-schedule-tasks task)
      (throw (ex-info "Maintenance schedule task is not supported."
                      {:schedule-id id
                       :task task
                       :supported (sort maintenance-schedule-tasks)})))
    (when-not (pos? every-minutes)
      (throw (ex-info "Maintenance schedule interval must be positive."
                      {:schedule-id id
                       :every-minutes every-minutes})))
    (assoc schedule
           :id id
           :task task
           :enabled (boolean (:enabled schedule))
           :every-minutes every-minutes
           :enqueue (if (contains? schedule :enqueue)
                      (boolean (:enqueue schedule))
                      true)
           :check (if (contains? schedule :check)
                    (boolean (:check schedule))
                    true)
           :query-index (boolean (:query-index schedule))
           :run-on-start (boolean (:run-on-start schedule)))))

(defn- normalize-maintenance-schedules
  [schedules]
  (let [schedules (mapv normalize-maintenance-schedule (or schedules []))]
    (when-let [ids (seq (duplicate-values :id schedules))]
      (throw (ex-info "Maintenance schedule ids must be unique."
                      {:schedule-ids ids})))
    schedules))

(defn- normalize-index-maintenance-worker
  [base _project-id maintenance]
  (when-let [worker (:worker maintenance)]
    (let [executors (:executors worker)]
      (when-not (and (sequential? executors) (seq executors))
        (throw (ex-info "Index maintenance worker requires non-empty :executors." {})))
      (assoc worker
             :enabled (boolean (:enabled worker))
             :agent-id (str (or (:agent-id worker) "ygg-auto"))
             :queue-db (:queue-db maintenance)
             :report-dir (:report-dir maintenance)
             :lease-minutes (long (or (:lease-minutes worker) 10))
             :max-items-per-run (long (or (:max-items-per-run worker) 1))
             :max-failures-per-run (long (or (:max-failures-per-run worker) 3))
             :apply (normalize-index-maintenance-apply (:apply worker))
             :executors (mapv normalize-index-maintenance-executor executors)
             :base-dir (fs/canonical-path base)))))

(defn- normalize-maintenance
  [base project-id data]
  (when-let [maintenance (:maintenance data)]
    (when-let [key (some #(when (contains? maintenance %) %)
                         [:queue-dir :queue-db])]
      (throw (ex-info "Maintenance queue storage is central and cannot be configured."
                      {:project-id project-id
                       :key key})))
    (let [maintenance (assoc maintenance
                             :enabled (boolean (:enabled maintenance))
                             :work (index-maintenance/normalize-work-controls
                                    (:work maintenance))
                             :queue-db (store/project-sqlite-path project-id)
                             :report-dir (if (:report-dir maintenance)
                                           (resolve-root base (:report-dir maintenance))
                                           (store/project-data-path project-id
                                                                    "reports"
                                                                    "maintenance"))
                             :schedules (normalize-maintenance-schedules
                                         (:schedules maintenance)))]
      (cond-> maintenance
        (:worker maintenance)
        (assoc :worker (normalize-index-maintenance-worker base
                                                           project-id
                                                           maintenance))))))

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

(declare normalize-project)

(defn read-project
  "Read and normalize a project.edn file."
  [path]
  (let [base (config-dir path)
        data (read-config-data path)]
    (normalize-project base data {:path (fs/canonical-path path)})))

(defn normalize-project
  "Normalize project config data using base as the directory for relative paths."
  [base data {:keys [path]}]
  (let [base (or base (io/file "."))
        project-id (some-> (:id data) str)]
    (when (str/blank? project-id)
      (throw (ex-info "Project config is missing :id."
                      (cond-> {}
                        path (assoc :path path)))))
    (let [plugins (plugin-config base data)
          embeddings (normalize-embeddings data)
          maintenance (normalize-maintenance base project-id data)]
      (cond-> {:id project-id
               :name (str (or (:name data) project-id))
               :repos (normalize-repos base data)}
        path (assoc :path path)
        embeddings
        (assoc :embeddings embeddings)
        (some seq (vals plugins))
        (assoc :plugins plugins)
        maintenance
        (assoc :maintenance maintenance)))))

(defn add-repo-to-config!
  "Add a repo entry to project config and return the normalized project.

  `repo-root` is written as a canonical path. If `repo-id` is omitted, the id is
  derived from the repo directory name. For workbench configs, explicit repos
  supplement the source repos discovered from `repos.json`."
  [config-path repo-root {:keys [repo-id role]}]
  (let [file (io/file config-path)
        data (read-config-data file)
        canonical-root (fs/canonical-path repo-root)
        repo-id (str (or repo-id (repo-id-from-root canonical-root)))
        role (keyword (or role (infer-role repo-id)))
        existing-repos (vec (:repos data))]
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

(defn- update-config-data!
  [config-path f]
  (let [file (io/file config-path)
        data (read-config-data file)]
    (write-config-data! file (f data))
    (read-project config-path)))

(defn- ensure-maintenance
  [data]
  (update data :maintenance #(or % {})))

(defn- require-maintenance-worker
  [data config-path]
  (when-not (get-in data [:maintenance :worker])
    (throw (ex-info "Project config has no [:maintenance :worker] block."
                    {:config-path (str config-path)
                     :hint "Add [:maintenance :worker :executors] before enabling the auto-completion worker."})))
  data)

(defn set-maintenance-enabled!
  "Enable or disable project maintenance schedules."
  [config-path enabled?]
  (update-config-data!
   config-path
   (fn [data]
     (assoc-in (ensure-maintenance data) [:maintenance :enabled] (boolean enabled?)))))

(defn set-maintenance-worker-enabled!
  "Enable or disable the project-configured maintenance worker."
  [config-path enabled?]
  (update-config-data!
   config-path
   (fn [data]
     (-> (require-maintenance-worker data config-path)
         (assoc-in [:maintenance :worker :enabled] (boolean enabled?))))))

(defn- upsert-schedule
  [schedules schedule-id patch]
  (let [schedule-id (str schedule-id)
        schedules (vec schedules)
        match? #(= schedule-id (str (:id %)))]
    (if (some match? schedules)
      (mapv #(if (match? %) (merge % patch) %) schedules)
      (conj schedules (assoc patch :id schedule-id)))))

(defn set-maintenance-schedule!
  "Upsert a project maintenance schedule."
  [config-path schedule-id {:keys [enabled every-minutes task enqueue check query-index run-on-start]}]
  (update-config-data!
   config-path
   (fn [data]
     (update-in (ensure-maintenance data)
                [:maintenance :schedules]
                upsert-schedule
                schedule-id
                (cond-> {}
                  (some? enabled) (assoc :enabled (boolean enabled))
                  (some? every-minutes) (assoc :every-minutes (long every-minutes))
                  task (assoc :task (keyword task))
                  (some? enqueue) (assoc :enqueue (boolean enqueue))
                  (some? check) (assoc :check (boolean check))
                  (some? query-index) (assoc :query-index (boolean query-index))
                  (some? run-on-start) (assoc :run-on-start (boolean run-on-start)))))))

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

(def ^:private project-metadata-fields
  [:xt/id :project-id :name :active?])

(def ^:private repo-metadata-fields
  [:xt/id :project-id :repo-id :root :role :active?])

(defn- stable-project-row
  [row]
  (select-keys row project-metadata-fields))

(defn- stable-repo-row
  [row]
  (select-keys row repo-metadata-fields))

(defn- desired-project-metadata
  [project]
  (let [updated-at-ms 0]
    {:project (stable-project-row (project-row project updated-at-ms))
     :repos (->> (:repos project)
                 (map #(stable-repo-row (repo-row (:id project) updated-at-ms %)))
                 (sort-by :repo-id)
                 vec)}))

(defn- current-project-metadata
  [xtdb project]
  (let [repo-ids (set (map (comp str :id) (:repos project)))]
    {:project (first
               (store/ordered-rows
                xtdb
                {:table (:projects store/tables)
                 :constraints {:project-id (:id project)
                               :active? true}
                 :return-fields project-metadata-fields}))
     :repos (->> (store/ordered-rows
                  xtdb
                  {:table (:repos store/tables)
                   :constraints {:project-id (:id project)
                                 :active? true}
                   :order-fields [:repo-id]
                   :return-fields repo-metadata-fields})
                 (filter #(contains? repo-ids (str (:repo-id %))))
                 vec)}))

(defn- project-metadata-current?
  [xtdb project]
  (= (desired-project-metadata project)
     (current-project-metadata xtdb project)))

(defn persist-project!
  "Persist project metadata rows."
  [xtdb project]
  (if (project-metadata-current? xtdb project)
    {:status :skipped
     :reason :unchanged-project-metadata
     :project-id (:id project)
     :repos (count (:repos project))}
    (let [updated-at-ms (now-ms)]
      (store/commit-project! xtdb
                             (project-row project updated-at-ms)
                             (mapv #(repo-row (:id project) updated-at-ms %)
                                   (:repos project))))))

(defn- with-index-deadline
  [{:keys [index-timeout-ms index-deadline-ns] :as opts}]
  (if (or index-deadline-ns (nil? index-timeout-ms))
    opts
    (assoc opts :index-deadline-ns (index/deadline-ns index-timeout-ms))))

(defn index-project!
  "Index every repo in project config into XTDB."
  [xtdb project {:keys [dry-run? index-profile correction-overlay index-timeout-ms index-deadline-ns
                        progress-fn progress-interval extract-parallelism]
                 :or {dry-run? false
                      index-profile index/default-index-profile}}]
  (let [index-opts (with-index-deadline {:index-profile index-profile
                                         :correction-overlay correction-overlay
                                         :index-timeout-ms index-timeout-ms
                                         :index-deadline-ns index-deadline-ns
                                         :progress-fn progress-fn
                                         :progress-interval progress-interval
                                         :extract-parallelism extract-parallelism
                                         :extractors (extractors project)})]
    (if dry-run?
      {:project-id (:id project)
       :status :dry-run
       :repos (mapv (fn [{:keys [id root role ignore-paths]}]
                      (index/index-repo! nil
                                         root
                                         (assoc index-opts
                                                :dry-run? true
                                                :project-id (:id project)
                                                :repo-id id
                                                :repo-role role
                                                :ignore-paths ignore-paths)))
                    (:repos project))}
      (do
        (persist-project! xtdb project)
        {:project-id (:id project)
         :status :completed
         :repos (mapv (fn [{:keys [id root role ignore-paths]}]
                        (index/index-repo! xtdb
                                           root
                                           (assoc index-opts
                                                  :project-id (:id project)
                                                  :repo-id id
                                                  :repo-role role
                                                  :ignore-paths ignore-paths)))
                      (:repos project))}))))

(defn index-project-repo!
  "Index one repo from a project config into XTDB."
  [xtdb project repo-id {:keys [dry-run? index-profile correction-overlay
                                index-timeout-ms index-deadline-ns
                                progress-fn progress-interval extract-parallelism]
                         :or {dry-run? false
                              index-profile index/default-index-profile}}]
  (let [repo (or (some #(when (= repo-id (:id %)) %) (:repos project))
                 (throw (ex-info "Project repo not found."
                                 {:project-id (:id project)
                                  :repo-id repo-id})))
        index-opts (with-index-deadline {:index-profile index-profile
                                         :correction-overlay correction-overlay
                                         :index-timeout-ms index-timeout-ms
                                         :index-deadline-ns index-deadline-ns
                                         :progress-fn progress-fn
                                         :progress-interval progress-interval
                                         :extract-parallelism extract-parallelism
                                         :extractors (extractors project)})]
    (if dry-run?
      (index/index-repo! nil
                         (:root repo)
                         (assoc index-opts
                                :dry-run? true
                                :project-id (:id project)
                                :repo-id (:id repo)
                                :repo-role (:role repo)
                                :ignore-paths (:ignore-paths repo)))
      (do
        (persist-project! xtdb project)
        (index/index-repo! xtdb
                           (:root repo)
                           (assoc index-opts
                                  :project-id (:id project)
                                  :repo-id (:id repo)
                                  :repo-role (:role repo)
                                  :ignore-paths (:ignore-paths repo)))))))

(defn infer-project!
  "Infer and persist a derived system graph for project."
  [xtdb project]
  (persist-project! xtdb project)
  (system/infer-project! xtdb project))

(defn maintain-project
  "Return read-only maintenance findings for project."
  [xtdb project opts]
  (let [work (get-in project [:maintenance :work])]
    (system/maintenance-report
     xtdb
     (:id project)
     (merge {:max-queued-decisions (:max-decisions work)
             :max-queued-decisions-per-kind (:max-decisions-per-kind work)}
            opts))))
