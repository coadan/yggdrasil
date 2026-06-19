(ns agraph.plugin-package
  "Git-shareable plugin packages for extractor and report plugins."
  (:require [agraph.fs :as fs]
            [agraph.hash :as hash]
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

(defn- package-warnings
  [{:keys [id visibility license benchmark-status] :as package}]
  (vec
   (concat
    (when (= :public visibility)
      (concat
       (when-not (foss-license? license)
         [(str id " is marked public but does not declare a known FOSS license.")])
       (when (commercial? package)
         [(str id " is marked public and commercial; public AGraph/Yggdrasil plugins should be non-commercial.")])))
    (when (= :unbenchmarked benchmark-status)
      [(str id " is unbenchmarked; keep claims scoped until benchmarks show material improvement.")]))))

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
