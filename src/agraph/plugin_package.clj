(ns agraph.plugin-package
  "Git-shareable plugin packages for extractor and report plugins."
  (:require [agraph.extract :as extract]
            [agraph.extractor-plugin :as extractor-plugin]
            [agraph.fs :as fs]
            [agraph.hash :as hash]
            [agraph.report-plugin :as report-plugin]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.nio.file Files]))

(def manifest-schema
  "agraph.plugin/v1")

(def install-schema
  "agraph.plugin.install/v1")

(def list-schema
  "agraph.plugin.list/v1")

(def new-schema
  "agraph.plugin.new/v1")

(def validate-schema
  "agraph.plugin.validate/v1")

(def dry-run-schema
  "agraph.plugin.dry-run/v1")

(def diagnose-schema
  "agraph.plugin.diagnose/v1")

(def manifest-filename
  "agraph.plugin.edn")

(def default-cache-root
  ".dev/agraph/plugins/cache")

(def ^:private public-foss-licenses
  #{"MIT"
    "Apache-2.0"
    "BSD-2-Clause"
    "BSD-3-Clause"
    "ISC"
    "MPL-2.0"
    "EPL-2.0"})

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn- present?
  [value]
  (and value (not (str/blank? (str value)))))

(defn- config-dir
  [path]
  (or (some-> (io/file path) .getCanonicalFile .getParentFile)
      (io/file ".")))

(defn- resolve-path
  [base path]
  (fs/canonical-path
   (let [file (io/file path)]
     (if (.isAbsolute file)
       file
       (io/file base path)))))

(defn- read-edn-file
  [path]
  (edn/read-string (slurp (io/file path))))

(defn- write-edn-file!
  [path data]
  (with-open [writer (io/writer path)]
    (binding [*out* writer
              *print-namespace-maps* false]
      (pprint/pprint data)))
  path)

(defn- write-file!
  [path content]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file content)
    (fs/canonical-path file)))

(defn- git!
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh "git" args)]
    (when-not (zero? exit)
      (throw (ex-info "Git command failed."
                      {:command (into ["git"] args)
                       :exit exit
                       :out out
                       :err err})))
    (str/trim out)))

(defn- safe-delete-tree!
  [cache-root path]
  (let [cache-path (.normalize (.toAbsolutePath (.toPath (io/file cache-root))))
        target-path (.normalize (.toAbsolutePath (.toPath (io/file path))))]
    (when (and (.exists (io/file path))
               (.startsWith target-path cache-path))
      (with-open [paths (Files/walk target-path (make-array java.nio.file.FileVisitOption 0))]
        (doseq [path (sort #(compare (str %2) (str %1))
                           (iterator-seq (.iterator paths)))]
          (Files/deleteIfExists path))))))

(defn- cache-key
  [source ref subdir]
  (hash/short-hash {:source source
                    :ref (or ref "HEAD")
                    :subdir (or subdir "")}))

(defn- cache-dir
  [cache-root source ref subdir]
  (io/file cache-root (cache-key source ref subdir)))

(defn- ensure-checkout!
  [cache-root source {:keys [ref subdir force?]}]
  (let [checkout-root (cache-dir cache-root source ref subdir)]
    (.mkdirs (io/file cache-root))
    (when (and force? (.exists checkout-root))
      (safe-delete-tree! cache-root checkout-root))
    (when-not (.exists checkout-root)
      (git! "clone" "--quiet" source (.getPath checkout-root))
      (when (present? ref)
        (git! "-C" (.getPath checkout-root) "checkout" "--quiet" ref)))
    {:checkout-root (fs/canonical-path checkout-root)
     :rev (git! "-C" (.getPath checkout-root) "rev-parse" "HEAD")}))

(defn- source-map
  [{:keys [source ref rev subdir]}]
  (cond-> {:type :git
           :url source
           :rev rev}
    (present? ref) (assoc :ref ref)
    (present? subdir) (assoc :subdir subdir)))

(defn- package-dir
  [checkout-root subdir]
  (resolve-path checkout-root (or subdir ".")))

(defn- normalize-visibility
  [manifest]
  (keyword (or (get-in manifest [:distribution :visibility])
               (when (true? (get-in manifest [:distribution :public?]))
                 :public)
               :private)))

(defn- normalize-license
  [license]
  (cond
    (map? license)
    (cond-> license
      (:spdx license) (update :spdx str)
      (:id license) (update :id str))

    (present? license)
    {:spdx (str license)}

    :else
    {}))

(defn- license-id
  [license]
  (or (:spdx license) (:id license)))

(defn- foss-license?
  [license]
  (or (true? (:foss? license))
      (contains? public-foss-licenses (license-id license))))

(defn- commercial?
  [manifest]
  (or (true? (get-in manifest [:distribution :commercial?]))
      (true? (get-in manifest [:distribution :monetized?]))))

(defn- benchmark-status
  [manifest]
  (keyword (or (get-in manifest [:benchmark :status])
               :unbenchmarked)))

(defn- package-diagnostics
  [{:keys [id visibility license benchmark-status] :as package}]
  (vec
   (concat
    (when (= :public visibility)
      (concat
       (when-not (foss-license? license)
         [{:code :public-license-missing
           :severity :error
           :applies-to [:public-sharing]
           :message (str id " is marked public but does not declare a known FOSS license.")
           :evidence {:visibility visibility
                      :license license}}])
       (when (commercial? package)
         [{:code :public-commercial
           :severity :error
           :applies-to [:public-sharing]
           :message (str id " is marked public and commercial; public AGraph/Yggdrasil plugins should be non-commercial.")
           :evidence {:visibility visibility
                      :distribution (:distribution package)}}])))
    (when (= :unbenchmarked benchmark-status)
      [{:code :unbenchmarked
        :severity :warning
        :applies-to [:claims :core-promotion]
        :message (str id " is unbenchmarked; keep claims scoped until benchmarks show material improvement.")
        :evidence {:benchmark-status benchmark-status}}]))))

(defn- package-warnings
  [package]
  (mapv :message (package-diagnostics package)))

(defn- plugin-defaults
  [package entry]
  {:version (:version package)
   :authority :git-plugin
   :benchmark-status (:benchmark-status package)
   :cwd (:path package)
   :package-id (:id package)
   :package-version (:version package)
   :package-rev (get-in entry [:source :rev])
   :package-source (:source entry)})

(defn- normalize-plugin-configs
  [package entry k]
  (mapv #(merge (plugin-defaults package entry) %)
        (get package k)))

(defn read-package
  "Read and normalize an installed plugin package entry.

  The entry path is resolved relative to the project config directory. Returned
  extractor and report plugin entries are still ordinary project plugin config
  maps; callers pass them through the existing plugin normalizers."
  [project-base entry]
  (let [package-path (resolve-path project-base (:path entry))
        manifest-path (io/file package-path (or (:manifest entry) manifest-filename))
        manifest (read-edn-file manifest-path)
        package-id (some-> (:id manifest) str)]
    (when-not (present? package-id)
      (throw (ex-info "Plugin package manifest is missing :id."
                      {:path (.getPath manifest-path)})))
    (when-not (= manifest-schema (:schema manifest))
      (throw (ex-info "Unknown plugin package manifest schema."
                      {:path (.getPath manifest-path)
                       :schema (:schema manifest)
                       :expected manifest-schema})))
    (let [license (normalize-license (:license manifest))
          package (cond-> {:schema manifest-schema
                           :id package-id
                           :name (str (or (:name manifest) package-id))
                           :version (str (or (:version manifest) "dev"))
                           :path package-path
                           :manifest (.getPath manifest-path)
                           :source (:source entry)
                           :visibility (normalize-visibility manifest)
                           :license license
                           :distribution (:distribution manifest)
                           :benchmark (:benchmark manifest)
                           :benchmark-status (benchmark-status manifest)
                           :extractor-plugins (vec (:extractor-plugins manifest))
                           :report-plugins (vec (:report-plugins manifest))}
                    (:description manifest) (assoc :description (str (:description manifest))))]
      (assoc package
             :warnings (package-warnings package)
             :resolved-extractor-plugins (normalize-plugin-configs package entry :extractor-plugins)
             :resolved-report-plugins (normalize-plugin-configs package entry :report-plugins)))))

(defn package-summary
  [package]
  {:id (:id package)
   :name (:name package)
   :version (:version package)
   :source (:source package)
   :path (:path package)
   :visibility (:visibility package)
   :license (:license package)
   :benchmark-status (:benchmark-status package)
   :extractor-plugins (count (:resolved-extractor-plugins package))
   :report-plugins (count (:resolved-report-plugins package))
   :warnings (:warnings package)})

(defn read-installed-packages
  [config-path]
  (let [base (config-dir config-path)
        data (read-edn-file config-path)]
    (mapv #(read-package base %) (:plugin-packages data))))

(defn list-installed
  [config-path]
  (let [data (read-edn-file config-path)]
    {:schema list-schema
     :project-id (some-> (:id data) str)
     :packages (mapv package-summary (read-installed-packages config-path))}))

(defn- installed-entry
  [{:keys [id source rev ref subdir package-path]}]
  (cond-> {:id id
           :source (source-map {:source source
                                :ref ref
                                :rev rev
                                :subdir subdir})
           :path package-path
           :manifest manifest-filename
           :installed-at-ms (now-ms)}
    (present? ref) (assoc :ref ref)))

(defn- upsert-package-entry
  [entries entry force?]
  (let [existing (some #(when (= (:id entry) (:id %)) %) entries)]
    (when (and existing (not force?))
      (throw (ex-info "Plugin package is already installed. Re-run with --force to replace it."
                      {:plugin-package-id (:id entry)})))
    (->> entries
         (remove #(= (:id entry) (:id %)))
         (concat [entry])
         vec)))

(defn install!
  "Clone a git plugin package, pin its revision, and add it to project config."
  [config-path source {:keys [ref subdir cache-root force?] :as opts}]
  (when-not (present? source)
    (throw (ex-info "Missing plugin git source." {:config-path config-path})))
  (let [base (config-dir config-path)
        cache-root (resolve-path base (or cache-root default-cache-root))
        {:keys [checkout-root rev]} (ensure-checkout! cache-root
                                                      source
                                                      {:ref ref
                                                       :subdir subdir
                                                       :force? force?})
        package-path (package-dir checkout-root subdir)
        manifest-path (io/file package-path manifest-filename)
        _ (when-not (.exists manifest-path)
            (throw (ex-info "Plugin package manifest not found."
                            {:path (.getPath manifest-path)})))
        data (read-edn-file config-path)
        entry (installed-entry {:id (str (:id (read-edn-file manifest-path)))
                                :source source
                                :rev rev
                                :ref ref
                                :subdir subdir
                                :package-path package-path})
        updated (update data
                        :plugin-packages
                        #(upsert-package-entry (vec %) entry (boolean force?)))]
    (write-edn-file! config-path updated)
    (let [package (read-package base entry)]
      {:schema install-schema
       :project-id (some-> (:id data) str)
       :package (package-summary package)
       :entry entry
       :force? (boolean force?)
       :opts (select-keys opts [:ref :subdir :cache-root])})))

(defn- slug
  [value]
  (-> (str value)
      str/lower-case
      (str/replace #"[^a-z0-9._-]+" "-")
      (str/replace #"(^[-._]+|[-._]+$)" "")
      not-empty
      (or "agraph-plugin")))

(defn- default-package-id
  [dir]
  (slug (.getName (io/file dir))))

(defn- extractor-template
  []
  (str "#!/usr/bin/env python3\n"
       "import json\n"
       "import sys\n\n"
       "packet = json.load(sys.stdin)\n"
       "path = packet[\"file\"][\"path\"]\n"
       "content = packet[\"file\"].get(\"content\") or \"\"\n\n"
       "json.dump({\n"
       "    \"schema\": \"agraph.extractor-plugin.result/v1\",\n"
       "    \"nodes\": [],\n"
       "    \"edges\": [],\n"
       "    \"fileFacts\": [{\n"
       "        \"kind\": \"plugin-observation\",\n"
       "        \"label\": f\"plugin saw {path}\",\n"
       "        \"normalizedValue\": path,\n"
       "        \"sourceLine\": 1,\n"
       "        \"confidence\": 0.5,\n"
       "    }] if content else [],\n"
       "    \"chunks\": [{\n"
       "        \"kind\": \"plugin-summary\",\n"
       "        \"label\": f\"summary for {path}\",\n"
       "        \"text\": content[:500],\n"
       "        \"sourceLine\": 1,\n"
       "    }] if content else [],\n"
       "    \"diagnostics\": [],\n"
       "}, sys.stdout)\n"))

(defn- report-template
  []
  (str "#!/usr/bin/env python3\n"
       "import json\n"
       "import sys\n\n"
       "packet = json.load(sys.stdin)\n"
       "project = packet[\"project\"][\"id\"]\n\n"
       "json.dump({\n"
       "    \"schema\": \"agraph.report-plugin.result/v1\",\n"
       "    \"panels\": [{\n"
       "        \"id\": \"plugin-summary\",\n"
       "        \"label\": \"Plugin Summary\",\n"
       "        \"slot\": \"plugins\",\n"
       "        \"order\": 100,\n"
       "        \"mdx\": \"## Plugin Summary\\n\\n<MetricGrid dataKey=\\\"metrics\\\" />\",\n"
       "        \"data\": {\"metrics\": [{\"label\": \"Project\", \"value\": project}]},\n"
       "    }],\n"
       "    \"diagnostics\": [],\n"
       "    \"artifacts\": [],\n"
       "}, sys.stdout)\n"))

(defn- package-readme
  [package-id]
  (str "# " package-id "\n\n"
       "AGraph plugin package scaffold.\n\n"
       "Useful commands:\n\n"
       "```sh\n"
       "bb plugin validate .\n"
       "bb plugin dry-run extractor . /path/to/repo src/example.clj --json\n"
       "bb plugin install /path/to/project.edn . --force\n"
       "```\n\n"
       "Keep project-specific experiments in plugins. Promote to core only with "
       "project-agnostic behavior, fixtures, and benchmark evidence.\n"))

(defn- manifest
  [package-id {:keys [name extractor? report?]}]
  (cond-> {:schema manifest-schema
           :id package-id
           :name (str (or name package-id))
           :version "0.1.0"
           :license {:spdx "MIT"}
           :distribution {:visibility :private
                          :commercial? false}
           :benchmark {:status :unbenchmarked}}
    extractor?
    (assoc :extractor-plugins
           [{:id (str package-id "-extractor")
             :command ["python3" "extract.py"]
             :modes [:enhance :scan]
             :applies-to {:file-kinds [:code]
                          :path-globs ["src/*" "src/**/*"]}
             :scan {:path-globs ["fixtures/**/*"]
                    :file-kind :plugin-source}
             :search {:chunks? true}
             :emits [:plugin-observation]}])

    report?
    (assoc :report-plugins
           [{:id (str package-id "-report")
             :command ["python3" "report.py"]
             :slots [:plugins]}])))

(defn new!
  "Create a local plugin package scaffold."
  [dir {:keys [id extractor? report? force?] :as opts}]
  (let [package-id (slug (or id (default-package-id dir)))
        target (io/file dir)
        any-kind? (or extractor? report?)
        extractor? (if any-kind? extractor? true)
        report? (if any-kind? report? true)
        manifest-path (io/file target manifest-filename)]
    (when (and (.exists manifest-path) (not force?))
      (throw (ex-info "Plugin package already exists. Re-run with --force to replace scaffold files."
                      {:path (.getPath manifest-path)})))
    (.mkdirs target)
    (let [files (cond-> [(write-edn-file! manifest-path
                                          (manifest package-id
                                                    (assoc opts
                                                           :extractor? extractor?
                                                           :report? report?)))
                         (write-file! (io/file target "README.md")
                                      (package-readme package-id))
                         (write-file! (io/file target "fixtures/sample.clj")
                                      "(ns sample)\n(defn value [] 1)\n")]
                  extractor? (conj (write-file! (io/file target "extract.py")
                                                (extractor-template)))
                  report? (conj (write-file! (io/file target "report.py")
                                             (report-template))))]
      {:schema new-schema
       :package-id package-id
       :path (fs/canonical-path target)
       :manifest (fs/canonical-path manifest-path)
       :files files
       :extractor? (boolean extractor?)
       :report? (boolean report?)})))

(defn read-local-package
  [package-dir]
  (read-package "."
                {:path package-dir
                 :manifest manifest-filename
                 :source {:type :local
                          :path (fs/canonical-path package-dir)}}))

(defn validate-local
  "Validate a plugin package manifest and normalized plugin configs."
  [package-dir]
  (try
    (let [package (read-local-package package-dir)
          extractor-plugins (extractor-plugin/normalize-plugins
                             (:resolved-extractor-plugins package))
          report-plugins (report-plugin/normalize-plugins
                          (:resolved-report-plugins package))
          warnings (:warnings package)
          errors (cond-> []
                   (and (empty? extractor-plugins)
                        (empty? report-plugins))
                   (conj "Package declares no extractor or report plugins."))]
      {:schema validate-schema
       :status (cond
                 (seq errors) :failed
                 (seq warnings) :warning
                 :else :passed)
       :package (package-summary package)
       :extractor-plugins (mapv #(select-keys % [:id :version :modes :scan :search])
                                extractor-plugins)
       :report-plugins (mapv #(select-keys % [:id :version :slots])
                             report-plugins)
       :warnings warnings
       :errors errors})
    (catch Exception e
      {:schema validate-schema
       :status :failed
       :package-dir (fs/canonical-path package-dir)
       :warnings []
       :errors [(or (ex-message e) (str e))]
       :data (ex-data e)})))

(defn- validation-diagnostics
  [validation]
  (mapv (fn [error]
          {:code :validation-error
           :severity :error
           :applies-to [:local-use :public-sharing :core-promotion]
           :message error})
        (:errors validation)))

(defn- lane
  [status reason next-actions]
  {:status status
   :reason reason
   :next-actions next-actions})

(defn- readiness
  [package validation diagnostics]
  (let [validation-failed? (= :failed (:status validation))
        public? (= :public (:visibility package))
        public-policy-errors (filter #(and (= :error (:severity %))
                                           (some #{:public-sharing} (:applies-to %)))
                                     diagnostics)
        unbenchmarked? (= :unbenchmarked (:benchmark-status package))]
    {:local-use
     (if validation-failed?
       (lane :blocked
             "Package manifest or plugin configs failed validation."
             ["Fix validation errors before using this package."])
       (lane :ready
             "Package loads through the same normalizers used by project config."
             ["Use plugin dry-run on representative files before installing."]))

     :public-sharing
     (cond
       validation-failed?
       (lane :blocked
             "Invalid packages should not be shared."
             ["Fix validation errors first."])

       (not public?)
       (lane :private
             "Package is not declared public."
             ["Keep it private for local experiments, or declare public distribution metadata before publishing."])

       (seq public-policy-errors)
       (lane :blocked
             "Public plugin packages must be FOSS and non-commercial."
             ["Declare a known FOSS license and remove commercial/monetized distribution metadata, or keep the package private."])

       unbenchmarked?
       (lane :caution
             "Package can be shared as experimental, but claims must stay scoped."
             ["Add benchmark artifacts before claiming agent or architecture-understanding improvements."])

       :else
       (lane :ready
             "Package declares public FOSS, non-commercial distribution metadata and benchmark status."
             ["Review benchmark artifacts before making performance or quality claims."]))

     :core-promotion
     (cond
       validation-failed?
       (lane :blocked
             "Invalid packages cannot be promoted."
             ["Fix validation errors first."])

       unbenchmarked?
       (lane :blocked
             "Core promotion requires benchmark evidence."
             ["Add fixtures, tests, benchmark cases, and reports showing material improvement."])

       :else
       (lane :review-required
             "Benchmark metadata is present; project-agnostic core suitability still needs review."
             ["Verify there are no project-specific rules, path/name semantics, host-name heuristics, or brittle substring classifiers."]))}))

(defn diagnose-local
  "Diagnose package readiness for local use, public sharing, and core promotion."
  [package-dir]
  (let [validation (validate-local package-dir)]
    (if (= :failed (:status validation))
      {:schema diagnose-schema
       :status :failed
       :package-dir (fs/canonical-path package-dir)
       :validation validation
       :diagnostics (validation-diagnostics validation)
       :readiness (readiness nil validation (validation-diagnostics validation))}
      (let [package (read-local-package package-dir)
            diagnostics (vec (concat (package-diagnostics package)
                                     (validation-diagnostics validation)))
            status (cond
                     (some #(= :error (:severity %)) diagnostics) :failed
                     (some #(= :warning (:severity %)) diagnostics) :warning
                     :else :passed)]
        {:schema diagnose-schema
         :status status
         :package (package-summary package)
         :validation validation
         :diagnostics diagnostics
         :readiness (readiness package validation diagnostics)}))))

(defn- selected-extractor-plugins
  [package plugin-id]
  (let [plugins (extractor-plugin/normalize-plugins
                 (:resolved-extractor-plugins package))]
    (if (present? plugin-id)
      (let [selected (filterv #(= plugin-id (:id %)) plugins)]
        (when-not (seq selected)
          (throw (ex-info "Extractor plugin not found in package."
                          {:plugin-id plugin-id
                           :available (mapv :id plugins)})))
        selected)
      plugins)))

(defn- file-record-for-dry-run
  [root file plugins]
  (let [root-path (fs/canonical-path root)
        file-path (resolve-path root-path file)
        rel (fs/relative-path root-path file-path)
        scan-matches (->> (extractor-plugin/scan-specs plugins)
                          (filter (fn [{:keys [path-globs]}]
                                    (some #(fs/path-glob-matches? % rel)
                                          path-globs)))
                          vec)
        file (if (seq scan-matches)
               (fs/plugin-file-record root-path
                                      rel
                                      {:file-kind (:file-kind (first scan-matches))
                                       :plugin-ids (mapv :plugin-id scan-matches)})
               (fs/file-record root-path file-path))]
    (assoc file
           :file-id (str "file:plugin-dry-run:repo:" (:path file))
           :id-scope "project:plugin-dry-run:repo:repo")))

(defn- counts
  [extraction]
  (into {}
        (map (fn [k] [k (count (get extraction k))]))
        [:nodes :edges :chunks :file-facts :diagnostics]))

(defn dry-run-extractor
  "Run package extractor plugins against one file without writing graph state."
  [package-dir root file {:keys [plugin-id]}]
  (let [package (read-local-package package-dir)
        plugins (selected-extractor-plugins package plugin-id)
        root-path (fs/canonical-path root)
        file-record (file-record-for-dry-run root-path file plugins)
        run-id "run:plugin-dry-run"
        core (extract/extract-file run-id file-record)
        enhanced (extractor-plugin/enhance-extraction
                  {:plugins plugins
                   :run-id run-id
                   :project-id "plugin-dry-run"
                   :repo-id "repo"
                   :root-path root-path
                   :file file-record}
                  core)]
    {:schema dry-run-schema
     :status (if (seq (:diagnostics enhanced)) :warning :passed)
     :package (select-keys package [:id :version :path :warnings])
     :plugins (mapv #(select-keys % [:id :version :authority :benchmark-status])
                    plugins)
     :file (select-keys file-record [:file-id :path :kind :plugin-scanned? :plugin-ids])
     :core-counts (counts core)
     :enhanced-counts (counts enhanced)
     :diagnostics (:diagnostics enhanced)
     :rows enhanced}))
