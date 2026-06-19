(ns agraph.plugin-package
  "Git-shareable plugin packages for extractor and report plugins."
  (:require [agraph.extractor-plugin :as extractor-plugin]
            [agraph.fs :as fs]
            [agraph.hash :as hash]
            [agraph.plugin-package-scaffold :as plugin-package-scaffold]
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

(def remove-schema
  "agraph.plugin.remove/v1")

(def new-schema
  "agraph.plugin.new/v1")

(def validate-schema
  "agraph.plugin.validate/v1")

(def dry-run-schema
  "agraph.plugin.dry-run/v1")

(def diagnose-schema
  "agraph.plugin.diagnose/v1")

(def registry-schema
  "agraph.plugin.registry/v1")

(def registry-validate-schema
  "agraph.plugin.registry.validate/v1")

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

(def ^:private scope-kinds
  #{:project-local :base})

(def ^:private benchmark-statuses
  #{:unbenchmarked :benchmarked})

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

(defn- manifest-fingerprint
  [path]
  (str "sha256:" (hash/sha256-bytes-hex (Files/readAllBytes (.toPath (io/file path))))))

(defn- write-edn-file!
  [path data]
  (with-open [writer (io/writer path)]
    (binding [*out* writer
              *print-namespace-maps* false]
      (pprint/pprint data)))
  path)

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

(defn- normalize-scope
  [scope]
  (let [scope (cond
                (map? scope) scope
                (present? scope) {:kind scope}
                :else {})]
    (cond-> (assoc scope :kind (keyword (or (:kind scope) :project-local)))
      (:reason scope) (update :reason str))))

(defn- base-scope?
  [package]
  (= :base (get-in package [:scope :kind])))

(defn- scope-diagnostics
  [{:keys [id scope]}]
  (let [kind (:kind scope)]
    (cond
      (not (contains? scope-kinds kind))
      [{:code :scope-kind-unsupported
        :severity :error
        :applies-to [:public-sharing :claims :core-promotion]
        :message (str id " declares unsupported plugin scope: " (name kind))
        :evidence {:scope scope
                   :supported (sort scope-kinds)}}]

      (= :project-local kind)
      [{:code :project-local-scope
        :severity :warning
        :applies-to [:public-sharing :claims :core-promotion]
        :message (str id " is declared project-local; keep it external and do not promote it to core.")
        :evidence {:scope scope}}]

      :else
      [])))

(defn- benchmark-status
  [manifest]
  (let [status (keyword (or (get-in manifest [:benchmark :status])
                            :unbenchmarked))]
    (when-not (contains? benchmark-statuses status)
      (throw (ex-info "Unknown plugin package benchmark status."
                      {:benchmark-status status
                       :supported (sort benchmark-statuses)})))
    status))

(defn- benchmark-artifacts
  [package]
  (let [artifacts (get-in package [:benchmark :artifacts])]
    (cond
      (nil? artifacts) []
      (sequential? artifacts) (vec artifacts)
      :else [artifacts])))

(defn- artifact-path
  [artifact]
  (cond
    (map? artifact) (:path artifact)
    (present? artifact) (str artifact)
    :else nil))

(defn- artifact-summary
  [package artifact]
  (let [path (artifact-path artifact)]
    (cond-> {:path path}
      (map? artifact) (merge (select-keys artifact [:kind :case-id :report-id :description]))
      (present? path) (assoc :resolved-path (resolve-path (:path package) path)))))

(defn- benchmark-artifact-diagnostics
  [{:keys [id benchmark-status] :as package}]
  (let [artifacts (benchmark-artifacts package)]
    (vec
     (concat
      (when (and (= :benchmarked benchmark-status)
                 (empty? artifacts))
        [{:code :benchmark-artifacts-missing
          :severity :error
          :applies-to [:claims :core-promotion]
          :message (str id " is marked benchmarked but declares no benchmark artifacts.")
          :evidence {:benchmark-status benchmark-status}}])
      (mapcat
       (fn [artifact]
         (let [{:keys [path resolved-path] :as summary} (artifact-summary package artifact)]
           (cond
             (not (present? path))
             [{:code :benchmark-artifact-path-missing
               :severity :error
               :applies-to [:claims :core-promotion]
               :message (str id " declares a benchmark artifact without :path.")
               :evidence {:artifact artifact}}]

             (not (.isFile (io/file resolved-path)))
             [{:code :benchmark-artifact-not-found
               :severity :error
               :applies-to [:claims :core-promotion]
               :message (str id " benchmark artifact does not exist: " path)
               :evidence summary}]

             :else
             [])))
       artifacts)))))

(def ^:private core-promotion-evidence-kinds
  {:fixtures {:missing-code :core-fixtures-missing
              :path-missing-code :core-fixture-path-missing
              :not-found-code :core-fixture-not-found
              :missing-message " declares no core-promotion fixture artifacts."}
   :tests {:missing-code :core-tests-missing
           :path-missing-code :core-test-path-missing
           :not-found-code :core-test-not-found
           :missing-message " declares no core-promotion test artifacts."}})

(defn- core-promotion-artifacts
  [package k]
  (let [artifacts (get-in package [:core-promotion k])]
    (cond
      (nil? artifacts) []
      (sequential? artifacts) (vec artifacts)
      :else [artifacts])))

(defn- core-promotion-artifact-summary
  [package evidence-kind artifact]
  (let [path (artifact-path artifact)]
    (cond-> {:path path
             :evidence-kind evidence-kind}
      (map? artifact) (merge (select-keys artifact [:kind :description :case-id]))
      (present? path) (assoc :resolved-path (resolve-path (:path package) path)))))

(defn- core-promotion-artifact-diagnostics
  [{:keys [id benchmark-status] :as package}]
  (if (and (base-scope? package)
           (= :benchmarked benchmark-status)
           (seq (benchmark-artifacts package)))
    (vec
     (mapcat
      (fn [[evidence-kind {:keys [missing-code path-missing-code not-found-code
                                  missing-message]}]]
        (let [artifacts (core-promotion-artifacts package evidence-kind)]
          (if (empty? artifacts)
            [{:code missing-code
              :severity :warning
              :applies-to [:core-promotion]
              :message (str id missing-message)
              :evidence {:core-promotion (:core-promotion package)
                         :evidence-kind evidence-kind}}]
            (mapcat
             (fn [artifact]
               (let [{:keys [path resolved-path] :as summary}
                     (core-promotion-artifact-summary package evidence-kind artifact)]
                 (cond
                   (not (present? path))
                   [{:code path-missing-code
                     :severity :warning
                     :applies-to [:core-promotion]
                     :message (str id " declares a core-promotion "
                                   (name evidence-kind)
                                   " artifact without :path.")
                     :evidence {:artifact artifact
                                :evidence-kind evidence-kind}}]

                   (not (.isFile (io/file resolved-path)))
                   [{:code not-found-code
                     :severity :warning
                     :applies-to [:core-promotion]
                     :message (str id " core-promotion "
                                   (name evidence-kind)
                                   " artifact does not exist: "
                                   path)
                     :evidence summary}]

                   :else
                   [])))
             artifacts))))
      core-promotion-evidence-kinds))
    []))

(defn- duplicate-plugin-id-diagnostics
  [{:keys [id] :as package} lane k]
  (->> (get package k)
       (keep (fn [plugin]
               (let [plugin-id (some-> (:id plugin) str)]
                 (when (present? plugin-id)
                   plugin-id))))
       frequencies
       (keep (fn [[plugin-id n]]
               (when (> n 1)
                 {:code (keyword (str "duplicate-" (name lane) "-plugin-id"))
                  :severity :error
                  :applies-to [:local-use :public-sharing :claims :core-promotion]
                  :message (str id " declares duplicate " (name lane)
                                " plugin id: " plugin-id)
                  :evidence {:package-id id
                             :plugin-id plugin-id
                             :lane lane
                             :count n}})))
       vec))

(defn- package-diagnostics
  [{:keys [id expected-package-id visibility license benchmark-status manifest-fingerprint
           expected-manifest-fingerprint]
    :as package}]
  (vec
   (concat
    (when (and (present? expected-package-id)
               (not= expected-package-id id))
      [{:code :package-id-mismatch
        :severity :error
        :applies-to [:local-use :public-sharing :claims :core-promotion]
        :message (str id " package id does not match the installed project entry.")
        :evidence {:expected expected-package-id
                   :actual id
                   :manifest (:manifest package)}}])
    (when (and (present? expected-manifest-fingerprint)
               (not= expected-manifest-fingerprint manifest-fingerprint))
      [{:code :manifest-fingerprint-mismatch
        :severity :error
        :applies-to [:local-use :public-sharing :claims :core-promotion]
        :message (str id " manifest fingerprint does not match the installed project entry.")
        :evidence {:expected expected-manifest-fingerprint
                   :actual manifest-fingerprint
                   :manifest (:manifest package)}}])
    (scope-diagnostics package)
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
        :evidence {:benchmark-status benchmark-status}}])
    (benchmark-artifact-diagnostics package)
    (core-promotion-artifact-diagnostics package)
    (duplicate-plugin-id-diagnostics package :extractor :extractor-plugins)
    (duplicate-plugin-id-diagnostics package :report :report-plugins))))

(defn- package-warnings
  [package]
  (mapv :message (package-diagnostics package)))

(defn- diagnostic-counts
  [diagnostics]
  (let [by-severity (frequencies (map :severity diagnostics))]
    {:total (count diagnostics)
     :errors (get by-severity :error 0)
     :warnings (get by-severity :warning 0)}))

(defn- local-use-error-diagnostics
  [package]
  (filterv #(and (= :error (:severity %))
                 (some #{:local-use} (:applies-to %)))
           (package-diagnostics package)))

(defn- claim-authority
  [{:keys [benchmark-status scope] :as package}]
  (let [scope-kind (:kind scope)
        benchmark-evidence-blockers (->> (benchmark-artifact-diagnostics package)
                                         (filter #(= :error (:severity %)))
                                         (mapv #(select-keys % [:code :message])))
        blockers (cond-> []
                   (= :project-local scope-kind)
                   (conj {:code :project-local
                          :message "Project-local package output stays external and cannot support public claims."})

                   (= :unbenchmarked benchmark-status)
                   (conj {:code :unbenchmarked
                          :message "Unbenchmarked package output is useful for review but non-authoritative for public claims."})

                   (seq benchmark-evidence-blockers)
                   (into benchmark-evidence-blockers))]
    {:status (if (seq blockers)
               :non-authoritative
               :benchmark-backed)
     :public-claims? (empty? blockers)
     :review-required? (empty? blockers)
     :blockers blockers}))

(defn- plugin-defaults
  [package entry]
  {:version (:version package)
   :authority :git-plugin
   :benchmark-status (:benchmark-status package)
   :cwd (:path package)
   :package-id (:id package)
   :package-version (:version package)
   :package-rev (get-in entry [:source :rev])
   :package-manifest-fingerprint (:manifest-fingerprint package)
   :package-claim-authority (claim-authority package)
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
        fingerprint (manifest-fingerprint manifest-path)
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
                           :manifest-fingerprint fingerprint
                           :expected-package-id (some-> (:id entry) str)
                           :expected-manifest-fingerprint (:manifest-fingerprint entry)
                           :source (:source entry)
                           :visibility (normalize-visibility manifest)
                           :license license
                           :scope (normalize-scope (:scope manifest))
                           :distribution (:distribution manifest)
                           :benchmark (:benchmark manifest)
                           :benchmark-status (benchmark-status manifest)
                           :core-promotion (:core-promotion manifest)
                           :extractor-plugins (vec (:extractor-plugins manifest))
                           :report-plugins (vec (:report-plugins manifest))}
                    (:description manifest) (assoc :description (str (:description manifest))))]
      (assoc package
             :warnings (package-warnings package)
             :resolved-extractor-plugins (normalize-plugin-configs package entry :extractor-plugins)
             :resolved-report-plugins (normalize-plugin-configs package entry :report-plugins)))))

(defn package-summary
  [package]
  (let [diagnostics (package-diagnostics package)]
    {:id (:id package)
     :name (:name package)
     :version (:version package)
     :source (:source package)
     :path (:path package)
     :manifest-fingerprint (:manifest-fingerprint package)
     :expected-package-id (:expected-package-id package)
     :expected-manifest-fingerprint (:expected-manifest-fingerprint package)
     :visibility (:visibility package)
     :license (:license package)
     :scope (:scope package)
     :benchmark-status (:benchmark-status package)
     :claim-authority (claim-authority package)
     :benchmark-artifacts (mapv #(artifact-summary package %)
                                (benchmark-artifacts package))
     :core-promotion {:fixtures (mapv #(core-promotion-artifact-summary package
                                                                        :fixtures
                                                                        %)
                                      (core-promotion-artifacts package :fixtures))
                      :tests (mapv #(core-promotion-artifact-summary package
                                                                     :tests
                                                                     %)
                                   (core-promotion-artifacts package :tests))}
     :extractor-plugins (count (:resolved-extractor-plugins package))
     :report-plugins (count (:resolved-report-plugins package))
     :diagnostics diagnostics
     :diagnostic-counts (diagnostic-counts diagnostics)
     :warnings (:warnings package)}))

(defn- ensure-local-use-ready!
  [package]
  (when-let [diagnostics (seq (local-use-error-diagnostics package))]
    (throw (ex-info "Plugin package local-use validation failed."
                    {:package (select-keys (package-summary package)
                                           [:id :path :diagnostics :diagnostic-counts])
                     :diagnostics (vec diagnostics)})))
  package)

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

(defn- remove-package-entry
  [entries package-id]
  (let [package-id (str package-id)
        matches (filterv #(= package-id (str (:id %))) entries)]
    (when-not (seq matches)
      (throw (ex-info "Plugin package is not installed."
                      {:plugin-package-id package-id
                       :installed (mapv #(str (:id %)) entries)})))
    {:removed (first matches)
     :entries (vec (remove #(= package-id (str (:id %))) entries))}))

(defn remove!
  "Remove an installed plugin package entry from project config.

  This updates `project.edn` only. Cached git checkouts are intentionally left in
  place so removal is non-destructive and reversible by reinstalling the package."
  [config-path package-id]
  (when-not (present? package-id)
    (throw (ex-info "Missing plugin package id." {:config-path config-path})))
  (let [data (read-edn-file config-path)
        entries (vec (:plugin-packages data))
        removal (remove-package-entry entries package-id)
        updated (assoc data :plugin-packages (:entries removal))]
    (write-edn-file! config-path updated)
    {:schema remove-schema
     :project-id (some-> (:id data) str)
     :package-id (str package-id)
     :removed-entry (:removed removal)
     :remaining (count (:entries removal))}))

(defn- installed-entry
  [{:keys [id source rev ref subdir package-path manifest-fingerprint]}]
  (cond-> {:id id
           :source (source-map {:source source
                                :ref ref
                                :rev rev
                                :subdir subdir})
           :path package-path
           :manifest manifest-filename
           :manifest-fingerprint manifest-fingerprint
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
        manifest (read-edn-file manifest-path)
        fingerprint (manifest-fingerprint manifest-path)
        data (read-edn-file config-path)
        entry (installed-entry {:id (str (:id manifest))
                                :source source
                                :rev rev
                                :ref ref
                                :subdir subdir
                                :package-path package-path
                                :manifest-fingerprint fingerprint})
        package (ensure-local-use-ready! (read-package base entry))
        updated (update data
                        :plugin-packages
                        #(upsert-package-entry (vec %) entry (boolean force?)))]
    (write-edn-file! config-path updated)
    {:schema install-schema
     :project-id (some-> (:id data) str)
     :package (package-summary package)
     :entry entry
     :force? (boolean force?)
     :opts (select-keys opts [:ref :subdir :cache-root])}))

(defn new!
  "Create a local plugin package scaffold."
  [dir opts]
  (plugin-package-scaffold/new! dir
                                opts
                                {:manifest-schema manifest-schema
                                 :new-schema new-schema
                                 :registry-schema registry-schema
                                 :manifest-filename manifest-filename}))

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
          diagnostics (package-diagnostics package)
          extractor-plugins (extractor-plugin/normalize-plugins
                             (:resolved-extractor-plugins package))
          report-plugins (report-plugin/normalize-plugins
                          (:resolved-report-plugins package))
          warnings (mapv :message
                         (filter #(= :warning (:severity %))
                                 diagnostics))
          errors (cond-> []
                   (and (empty? extractor-plugins)
                        (empty? report-plugins))
                   (conj "Package declares no extractor or report plugins."))
          errors (into errors
                       (map :message)
                       (local-use-error-diagnostics package))]
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
        unsupported-scope? (some #(= :scope-kind-unsupported (:code %)) diagnostics)
        benchmark-evidence-errors (filter #(and (= :error (:severity %))
                                                (some #{:claims} (:applies-to %)))
                                          diagnostics)
        core-promotion-evidence-codes (set (mapcat (fn [[_ codes]]
                                                     [(:missing-code codes)
                                                      (:path-missing-code codes)
                                                      (:not-found-code codes)])
                                                   core-promotion-evidence-kinds))
        core-promotion-evidence-errors (filter #(contains? core-promotion-evidence-codes
                                                           (:code %))
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

       unsupported-scope?
       (lane :blocked
             "Package declares an unsupported scope."
             ["Set scope kind to :project-local or :base."])

       (not (base-scope? package))
       (lane :blocked
             "Public plugin packages must declare base-ready scope."
             ["Keep project-local plugins private, or review and declare :scope {:kind :base} before public sharing."])

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

     :claims
     (cond
       validation-failed?
       (lane :blocked
             "Invalid packages cannot support public claims."
             ["Fix validation errors first."])

       unbenchmarked?
       (lane :blocked
             "Public improvement claims require benchmark evidence."
             ["Run replayable benchmarks and add benchmark artifacts to the package manifest."])

       unsupported-scope?
       (lane :blocked
             "Package declares an unsupported scope."
             ["Set scope kind to :project-local or :base."])

       (not (base-scope? package))
       (lane :blocked
             "Public improvement claims require base-ready scope."
             ["Keep project-local results private, or review and declare :scope {:kind :base}."])

       (seq benchmark-evidence-errors)
       (lane :blocked
             "Benchmark status is declared, but benchmark artifacts are missing or invalid."
             ["Add package-local benchmark report artifacts or set benchmark status back to :unbenchmarked."])

       :else
       (lane :ready
             "Benchmark artifacts are present for review."
             ["Cite the benchmark artifacts when making public claims."]))

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

       unsupported-scope?
       (lane :blocked
             "Package declares an unsupported scope."
             ["Set scope kind to :base before core-promotion review."])

       (not (base-scope? package))
       (lane :blocked
             "Project-local plugins must stay external."
             ["Keep this package external, or review and declare :scope {:kind :base} before core-promotion review."])

       (seq benchmark-evidence-errors)
       (lane :blocked
             "Core promotion requires existing benchmark artifacts, not benchmark status alone."
             ["Add package-local benchmark report artifacts or keep the plugin external."])

       (seq core-promotion-evidence-errors)
       (lane :blocked
             "Core promotion requires declared fixture and test artifacts."
             ["Add package-local fixture and test artifact paths under :core-promotion before requesting core review."])

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

(defn- registry-entry-path
  [registry-path entry]
  (when-let [path (:path entry)]
    (resolve-path (config-dir registry-path) path)))

(defn- shell-token
  [value]
  (let [s (str value)]
    (if (re-matches #"[A-Za-z0-9_./:@%+=,-]+" s)
      s
      (str "'" (str/replace s #"'" "'\"'\"'") "'"))))

(defn- registry-install-args
  [entry]
  (when-let [source (:source entry)]
    (cond-> ["plugin" "install" "<project.edn>" (str source)]
      (present? (:ref entry)) (conj "--ref" (str (:ref entry)))
      (present? (:subdir entry)) (conj "--subdir" (str (:subdir entry))))))

(defn- registry-install
  [entry]
  (when-let [args (registry-install-args entry)]
    (let [source (source-map {:source (:source entry)
                              :ref (:ref entry)
                              :subdir (:subdir entry)})]
      {:source (dissoc source :rev)
       :args args
       :command (str "bb " (str/join " " (map shell-token args)))})))

(defn- registry-entry-status
  [diagnosis]
  (let [public-status (get-in diagnosis [:readiness :public-sharing :status])]
    (cond
      (= :failed (:status diagnosis)) :failed
      (#{:ready :caution} public-status) :passed
      :else :failed)))

(defn- registry-entry-errors
  [entry diagnosis]
  (let [declared-id (some-> (:id entry) str)
        package-id (some-> (get-in diagnosis [:package :id]) str)
        public-status (get-in diagnosis [:readiness :public-sharing :status])]
    (vec
     (concat
      (when-not (present? (:source entry))
        [{:code :registry-source-missing
          :message "Registry entry is missing :source for git installation."}])
      (when (and (present? (:source entry))
                 (not (present? (:ref entry))))
        [{:code :registry-ref-missing
          :message "Registry entry is missing :ref for reproducible git installation."}])
      (when (and declared-id package-id (not= declared-id package-id))
        [{:code :registry-id-mismatch
          :message "Registry entry id does not match package manifest id."
          :entry-id declared-id
          :package-id package-id}])
      (when-not (#{:ready :caution} public-status)
        [{:code :public-sharing-not-ready
          :message "Registry packages must be ready or caution for public sharing."
          :public-sharing-status public-status}])))))

(defn- validate-registry-entry
  [registry-path entry]
  (let [install (registry-install entry)]
    (if-let [package-path (registry-entry-path registry-path entry)]
      (let [diagnosis (diagnose-local package-path)
            errors (registry-entry-errors entry diagnosis)
            status (if (and (= :passed (registry-entry-status diagnosis))
                            (empty? errors))
                     :passed
                     :failed)]
        (cond-> {:id (or (some-> (:id entry) str)
                         (get-in diagnosis [:package :id]))
                 :path package-path
                 :status status
                 :errors errors
                 :diagnosis diagnosis}
          install (assoc :install install)))
      (cond-> {:id (some-> (:id entry) str)
               :status :failed
               :errors [{:code :registry-path-missing
                         :message "Registry entry is missing :path for offline validation."}]
               :entry entry}
        install (assoc :install install)))))

(defn- registry-claim-counts
  [results]
  (let [statuses (frequencies
                  (keep #(get-in % [:diagnosis :package :claim-authority :status])
                        results))]
    {:claim-ready (get statuses :benchmark-backed 0)
     :non-authoritative (get statuses :non-authoritative 0)}))

(defn- registry-error-counts
  [schema-errors results]
  (frequencies
   (keep :code
         (concat schema-errors
                 (mapcat :errors results)))))

(defn- duplicate-registry-id-counts
  [results]
  (->> results
       (keep :id)
       (map str)
       (filter present?)
       frequencies
       (filter (fn [[_ count]] (< 1 count)))
       (into {})))

(defn- add-duplicate-registry-id-error
  [duplicate-counts result]
  (if-let [count (get duplicate-counts (some-> (:id result) str))]
    (-> result
        (assoc :status :failed)
        (update :errors conj {:code :registry-duplicate-package-id
                              :message "Registry package ids must be unique."
                              :id (:id result)
                              :count count}))
    result))

(defn validate-registry
  "Validate a local public plugin registry index."
  [registry-path]
  (try
    (let [registry (read-edn-file registry-path)
          entries (vec (:packages registry))
          schema-errors (cond-> []
                          (not= registry-schema (:schema registry))
                          (conj {:code :registry-schema
                                 :message "Unknown plugin registry schema."
                                 :expected registry-schema
                                 :actual (:schema registry)})

                          (not (sequential? (:packages registry)))
                          (conj {:code :registry-packages
                                 :message "Registry must contain :packages vector."}))
          raw-results (if (seq schema-errors)
                        []
                        (mapv #(validate-registry-entry registry-path %) entries))
          duplicate-counts (duplicate-registry-id-counts raw-results)
          results (mapv #(add-duplicate-registry-id-error duplicate-counts %)
                        raw-results)
          failed (count (filter #(= :failed (:status %)) results))
          claim-counts (registry-claim-counts results)
          error-counts (registry-error-counts schema-errors results)]
      {:schema registry-validate-schema
       :registry (select-keys registry [:schema :id :name :description])
       :path (fs/canonical-path registry-path)
       :status (if (or (seq schema-errors) (pos? failed)) :failed :passed)
       :counts (merge {:packages (count entries)
                       :passed (count (filter #(= :passed (:status %)) results))
                       :failed failed}
                      claim-counts)
       :error-counts error-counts
       :errors schema-errors
       :packages results})
    (catch Exception e
      {:schema registry-validate-schema
       :path (fs/canonical-path registry-path)
       :status :failed
       :counts {:packages 0
                :passed 0
                :failed 0
                :claim-ready 0
                :non-authoritative 0}
       :error-counts {:registry-read 1}
       :errors [{:code :registry-read
                 :message (or (ex-message e) (str e))
                 :data (ex-data e)}]
       :packages []})))

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

(defn- selected-report-plugins
  [package plugin-id]
  (let [plugins (report-plugin/normalize-plugins
                 (:resolved-report-plugins package))]
    (if (present? plugin-id)
      (let [selected (filterv #(= plugin-id (:id %)) plugins)]
        (when-not (seq selected)
          (throw (ex-info "Report plugin not found in package."
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

(defn- empty-extraction
  []
  {:nodes []
   :edges []
   :chunks []
   :file-facts []
   :diagnostics []})

(defn- no-selected-plugins-diagnostic
  [kind package plugin-id]
  (cond-> {:code (keyword (str "no-" (name kind) "-plugins-selected"))
           :severity :error
           :applies-to [:local-use]
           :message (str (:id package) " has no " (name kind)
                         " plugins selected for this dry-run.")
           :evidence {:package-id (:id package)
                      :kind kind}}
    (present? plugin-id) (assoc-in [:evidence :plugin-id] plugin-id)))

(defn- package-error-extractor-dry-run
  [package root-path file diagnostics]
  (let [rows (assoc (empty-extraction) :diagnostics diagnostics)]
    {:schema dry-run-schema
     :kind :extractor
     :status :failed
     :package (package-summary package)
     :plugins []
     :file {:path (str file)
            :root root-path}
     :core-counts (counts rows)
     :enhanced-counts (counts rows)
     :diagnostics diagnostics
     :rows rows}))

(defn- package-error-report-dry-run
  [package diagnostics]
  {:schema dry-run-schema
   :kind :report
   :status :failed
   :package (package-summary package)
   :plugins []
   :counts {:panels 0
            :diagnostics (count diagnostics)
            :artifacts 0}
   :diagnostics diagnostics
   :outputs []})

(defn- dry-run-plugin-summary
  [plugin]
  (select-keys plugin
               [:id
                :version
                :authority
                :benchmark-status
                :package-id
                :package-version
                :package-rev
                :package-source
                :package-claim-authority
                :package-manifest-fingerprint]))

(defn dry-run-extractor
  "Run package extractor plugins against one file without writing graph state."
  [package-dir root file {:keys [plugin-id] :as opts}]
  (let [package (read-local-package package-dir)
        root-path (fs/canonical-path root)
        package-errors (local-use-error-diagnostics package)]
    (cond
      (seq package-errors)
      (package-error-extractor-dry-run package root-path file package-errors)

      :else
      (let [plugins (selected-extractor-plugins package plugin-id)
            plugin-summaries (mapv dry-run-plugin-summary plugins)]
        (if (empty? plugins)
          (let [rows (empty-extraction)
                diagnostics [(no-selected-plugins-diagnostic :extractor
                                                             package
                                                             (:plugin-id opts))]]
            {:schema dry-run-schema
             :kind :extractor
             :status :failed
             :package (package-summary package)
             :plugins plugin-summaries
             :file {:path (str file)
                    :root root-path}
             :core-counts (counts rows)
             :enhanced-counts (counts rows)
             :diagnostics diagnostics
             :rows (assoc rows :diagnostics diagnostics)})
          (let [file-record (file-record-for-dry-run root-path file plugins)
                run-id "run:plugin-dry-run"
                extract-file (requiring-resolve 'agraph.extract/extract-file)
                core (extract-file run-id file-record)
                enhanced (extractor-plugin/enhance-extraction
                          {:plugins plugins
                           :run-id run-id
                           :project-id "plugin-dry-run"
                           :repo-id "repo"
                           :root-path root-path
                           :file file-record}
                          core)]
            {:schema dry-run-schema
             :kind :extractor
             :status (if (seq (:diagnostics enhanced)) :warning :passed)
             :package (package-summary package)
             :plugins plugin-summaries
             :file (select-keys file-record [:file-id :path :kind :plugin-scanned? :plugin-ids])
             :core-counts (counts core)
             :enhanced-counts (counts enhanced)
             :diagnostics (:diagnostics enhanced)
             :rows enhanced}))))))

(defn- report-dry-run-context
  [package-dir]
  {:project {:id "plugin-dry-run"
             :name "Plugin Dry Run"
             :path (fs/canonical-path package-dir)
             :repos []}
   :generated-at-ms (now-ms)
   :report {:schema "agraph.report/v1"
            :project {:id "plugin-dry-run"
                      :name "Plugin Dry Run"}
            :atlas {:evidence {:files 0
                               :nodes 0
                               :edges 0}
                    :systems {:nodes 0
                              :edges 0}
                    :dependencies {:packages 0}}}
   :graph {:nodes []
           :edges []}
   :systems {:nodes []
             :edges []}
   :coverage {:counts {}}
   :maintenance {:queue {}}
   :evidence {:counts {}}
   :package-report {:counts {}}
   :artifacts {}})

(defn- report-output-counts
  [output]
  {:panels (count (:panels output))
   :diagnostics (count (:diagnostics output))
   :artifacts (count (:artifacts output))})

(defn dry-run-report
  "Run package report plugins against a synthetic report context."
  [package-dir {:keys [plugin-id] :as opts}]
  (let [package (read-local-package package-dir)
        package-errors (local-use-error-diagnostics package)]
    (cond
      (seq package-errors)
      (package-error-report-dry-run package package-errors)

      :else
      (let [plugins (selected-report-plugins package plugin-id)
            plugin-summary dry-run-plugin-summary]
        (if (empty? plugins)
          (let [diagnostics [(no-selected-plugins-diagnostic :report
                                                             package
                                                             (:plugin-id opts))]]
            {:schema dry-run-schema
             :kind :report
             :status :failed
             :package (package-summary package)
             :plugins []
             :counts {:panels 0
                      :diagnostics (count diagnostics)
                      :artifacts 0}
             :diagnostics diagnostics
             :outputs []})
          (let [ctx (report-dry-run-context package-dir)
                outputs (mapv (fn [plugin]
                                (let [output (report-plugin/run-plugin ctx plugin)]
                                  {:plugin (plugin-summary plugin)
                                   :counts (report-output-counts output)
                                   :output output}))
                              plugins)
                diagnostics (->> outputs
                                 (mapcat (comp :diagnostics :output))
                                 vec)]
            {:schema dry-run-schema
             :kind :report
             :status (if (seq diagnostics) :warning :passed)
             :package (package-summary package)
             :plugins (mapv :plugin outputs)
             :counts {:panels (reduce + (map #(count (get-in % [:output :panels] [])) outputs))
                      :diagnostics (count diagnostics)
                      :artifacts (reduce + (map #(count (get-in % [:output :artifacts] [])) outputs))}
             :diagnostics diagnostics
             :outputs outputs}))))))
