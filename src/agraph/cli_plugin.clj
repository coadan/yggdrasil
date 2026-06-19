(ns agraph.cli-plugin
  (:require [agraph.cli-options :refer [json-output? option-value positional-args]]
            [agraph.plugin-package :as plugin-package]
            [clojure.string :as str]))

(def ^:dynamic *usage* nil)
(def ^:dynamic *print-json* nil)

(defn- usage
  []
  (*usage*))

(defn- print-json
  [data]
  (*print-json* data))

(defn- claim-authority-text
  [claim-authority]
  [(str "status=" (name (:status claim-authority)))
   (str "public-claims=" (boolean (:public-claims? claim-authority)))])
(defn- claim-blocker-codes
  [claim-authority]
  (str/join "," (map (comp name :code) (:blockers claim-authority))))
(defn- print-plugin-claim-authority
  [prefix claim-authority]
  (when claim-authority
    (apply println prefix "claim-authority" (claim-authority-text claim-authority))
    (when (seq (:blockers claim-authority))
      (println prefix "claim-blockers" (claim-blocker-codes claim-authority)))))
(defn- print-plugin-package
  [package]
  (println "-"
           (:id package)
           (str "version=" (:version package))
           (str "extractors=" (:extractor-plugins package))
           (str "reports=" (:report-plugins package))
           (str "benchmark=" (name (or (:benchmark-status package) :unbenchmarked))))
  (print-plugin-claim-authority " " (:claim-authority package))
  (when-let [source (:source package)]
    (println "  source" (:url source) (str "rev=" (:rev source))))
  (when-let [fingerprint (:manifest-fingerprint package)]
    (println "  manifest-fingerprint" fingerprint))
  (when (seq (:warnings package))
    (doseq [warning (:warnings package)]
      (println "  warning" warning))))
(defn- print-plugin-install
  [{:keys [project-id package entry force?]}]
  (println "# Plugin Installed")
  (println "- project" project-id)
  (println "- force" force?)
  (print-plugin-package package)
  (if-let [fingerprint (:manifest-fingerprint entry)]
    (println "- manifest" (:manifest entry) (str "fingerprint=" fingerprint))
    (println "- manifest" (:manifest entry)))
  (println "- path" (:path entry)))
(defn- print-plugin-list
  [{:keys [project-id packages]}]
  (println "# Plugins")
  (println "- project" project-id)
  (println "- packages" (count packages))
  (doseq [package packages]
    (print-plugin-package package)))
(defn- print-plugin-remove
  [{:keys [project-id package-id removed-entry remaining]}]
  (println "# Plugin Removed")
  (println "- project" project-id)
  (println "- package" package-id)
  (when-let [path (:path removed-entry)]
    (println "- path" path))
  (println "- remaining" remaining))
(defn- print-plugin-new
  [{:keys [package-id path manifest files extractor? report? file-kind fixture-path]}]
  (println "# Plugin Package Created")
  (println "- package" package-id)
  (println "- path" path)
  (println "- manifest" manifest)
  (println "- extractor" extractor?)
  (println "- report" report?)
  (when file-kind
    (println "- file-kind" (name file-kind)))
  (when fixture-path
    (println "- fixture" fixture-path))
  (println "- files" (count files)))
(defn- print-plugin-validation
  [{:keys [status package extractor-plugins report-plugins warnings errors]}]
  (println "# Plugin Validation")
  (println "- status" (name status))
  (when package
    (println "- package" (:id package) (str "version=" (:version package)))
    (print-plugin-claim-authority "-" (:claim-authority package)))
  (println "- extractors" (count extractor-plugins))
  (println "- reports" (count report-plugins))
  (doseq [warning warnings]
    (println "- warning" warning))
  (doseq [error errors]
    (println "- error" error)))
(defn- print-plugin-diagnosis
  [{:keys [status package diagnostics readiness]}]
  (println "# Plugin Diagnosis")
  (println "- status" (name status))
  (when package
    (println "- package" (:id package) (str "version=" (:version package)))
    (print-plugin-claim-authority "-" (:claim-authority package)))
  (println "## Readiness")
  (doseq [[k {:keys [status reason next-actions]}] readiness]
    (println "-" (name k) (name status) "-" reason)
    (doseq [action next-actions]
      (println "  next" action)))
  (when (seq diagnostics)
    (println "## Diagnostics")
    (doseq [{:keys [severity code message]} diagnostics]
      (println "-" (name severity) (name code) "-" message))))
(defn- print-plugin-dry-run
  [{:keys [kind status package plugins file core-counts enhanced-counts counts diagnostics]}]
  (println "# Plugin Dry Run")
  (println "- status" (name status))
  (println "- kind" (name (or kind :extractor)))
  (println "- package"
           (str/join " "
                     (cond-> [(:id package)
                              (str "version=" (:version package))
                              (str "benchmark=" (name (or (:benchmark-status package)
                                                          :unbenchmarked)))]
                       (get-in package [:scope :kind])
                       (conj (str "scope=" (name (get-in package [:scope :kind])))))))
  (when-let [fingerprint (:manifest-fingerprint package)]
    (println "- manifest-fingerprint" fingerprint))
  (print-plugin-claim-authority "-" (:claim-authority package))
  (doseq [warning (:warnings package)]
    (println "- warning" warning))
  (when file
    (println "- file"
             (:path file)
             (if-let [kind (:kind file)]
               (str "kind=" (name kind))
               "")))
  (println "- plugins" (str/join "," (map :id plugins)))
  (when core-counts
    (println "- core" core-counts))
  (when enhanced-counts
    (println "- enhanced" enhanced-counts))
  (when counts
    (println "- output" counts))
  (when (seq diagnostics)
    (println "## Diagnostics")
    (doseq [{:keys [severity code stage message path]} diagnostics]
      (println "-"
               (or (some-> severity name)
                   (some-> stage str)
                   "diagnostic")
               (or (some-> code name) "")
               (or path "")
               "-"
               message))))
(defn- print-plugin-registry-validation
  [{:keys [status path counts errors packages]}]
  (println "# Plugin Registry Validation")
  (println "- status" (name status))
  (println "- path" path)
  (println "- packages" (:packages counts))
  (println "- passed" (:passed counts))
  (println "- failed" (:failed counts))
  (println "- claim-ready" (:claim-ready counts 0))
  (println "- non-authoritative" (:non-authoritative counts 0))
  (doseq [{:keys [code message]} errors]
    (println "- error" (name code) "-" message))
  (doseq [{:keys [id status errors install diagnosis]} packages]
    (println "-" id (name status))
    (when-let [command (:command install)]
      (println "  install" command))
    (print-plugin-claim-authority " " (get-in diagnosis [:package :claim-authority]))
    (doseq [{:keys [code message]} errors]
      (println "  error" (name code) "-" message))))
(defn- plugin-new!
  [args]
  (let [dir (first (positional-args args))]
    (when-not dir
      (throw (ex-info "Missing plugin package directory."
                      {:usage (usage)})))
    (let [result (plugin-package/new!
                  dir
                  (cond-> {:id (option-value args "--id")
                           :extractor? (boolean (some #{"--extractor"} args))
                           :report? (boolean (some #{"--report"} args))
                           :force? (boolean (some #{"--force"} args))}
                    (option-value args "--file-kind")
                    (assoc :file-kind (option-value args "--file-kind"))

                    (option-value args "--path-glob")
                    (assoc :path-globs (option-value args "--path-glob"))

                    (option-value args "--scan-glob")
                    (assoc :scan-globs (option-value args "--scan-glob"))

                    (option-value args "--fixture")
                    (assoc :fixture-path (option-value args "--fixture"))))]
      (if (json-output? args)
        (print-json result)
        (print-plugin-new result)))))
(defn- plugin-validate!
  [args]
  (let [dir (first (positional-args args))]
    (when-not dir
      (throw (ex-info "Missing plugin package directory."
                      {:usage (usage)})))
    (let [result (plugin-package/validate-local dir)]
      (if (json-output? args)
        (print-json result)
        (print-plugin-validation result))
      (when (= :failed (:status result))
        (throw (ex-info "Plugin validation failed."
                        {:errors (:errors result)}))))))
(defn- plugin-diagnose!
  [args]
  (let [dir (first (positional-args args))]
    (when-not dir
      (throw (ex-info "Missing plugin package directory."
                      {:usage (usage)})))
    (let [result (plugin-package/diagnose-local dir)]
      (if (json-output? args)
        (print-json result)
        (print-plugin-diagnosis result))
      (when (= :failed (:status result))
        (throw (ex-info "Plugin diagnosis failed."
                        {:diagnostics (:diagnostics result)}))))))
(defn- plugin-dry-run!
  [args]
  (let [[kind package-dir root file] (positional-args args)]
    (when-not (#{"extractor" "report"} kind)
      (throw (ex-info "Unsupported plugin dry-run kind."
                      {:kind kind
                       :supported ["extractor" "report"]
                       :usage (usage)})))
    (when-not package-dir
      (throw (ex-info "Missing plugin dry-run package directory."
                      {:usage (usage)})))
    (when (and (= "extractor" kind)
               (not (and root file)))
      (throw (ex-info "Missing plugin dry-run repo root or file."
                      {:usage (usage)})))
    (let [opts {:plugin-id (option-value args "--plugin")}
          result (case kind
                   "extractor"
                   (plugin-package/dry-run-extractor package-dir root file opts)

                   "report"
                   (plugin-package/dry-run-report package-dir opts))]
      (if (json-output? args)
        (print-json result)
        (print-plugin-dry-run result))
      (when (= :failed (:status result))
        (throw (ex-info "Plugin dry-run failed."
                        {:kind kind
                         :status (:status result)
                         :diagnostics (:diagnostics result)}))))))
(defn- plugin-install!
  [args]
  (let [[config-path source] (positional-args args)]
    (when-not (and config-path source)
      (throw (ex-info "Missing plugin project config path or git source."
                      {:usage (usage)})))
    (let [result (plugin-package/install!
                  config-path
                  source
                  {:ref (option-value args "--ref")
                   :subdir (option-value args "--subdir")
                   :cache-root (option-value args "--cache-dir")
                   :force? (boolean (some #{"--force"} args))})]
      (if (json-output? args)
        (print-json result)
        (print-plugin-install result)))))
(defn- plugin-list!
  [args]
  (let [config-path (first (positional-args args))]
    (when-not config-path
      (throw (ex-info "Missing plugin project config path."
                      {:usage (usage)})))
    (let [result (plugin-package/list-installed config-path)]
      (if (json-output? args)
        (print-json result)
        (print-plugin-list result)))))
(defn- plugin-remove!
  [args]
  (let [[config-path package-id] (positional-args args)]
    (when-not (and config-path package-id)
      (throw (ex-info "Missing plugin project config path or package id."
                      {:usage (usage)})))
    (let [result (plugin-package/remove! config-path package-id)]
      (if (json-output? args)
        (print-json result)
        (print-plugin-remove result)))))
(defn- plugin-registry!
  [args]
  (let [[action registry-path] (positional-args args)]
    (when-not (= "validate" action)
      (throw (ex-info "Unknown plugin registry command."
                      {:command action
                       :supported ["validate"]
                       :usage (usage)})))
    (when-not registry-path
      (throw (ex-info "Missing plugin registry path."
                      {:usage (usage)})))
    (let [result (plugin-package/validate-registry registry-path)]
      (if (json-output? args)
        (print-json result)
        (print-plugin-registry-validation result))
      (when (= :failed (:status result))
        (throw (ex-info "Plugin registry validation failed."
                        {:errors (:errors result)
                         :packages (:packages result)}))))))
(defn plugin!
  [args {:keys [usage print-json]}]
  (binding [*usage* usage
            *print-json* print-json]
    (let [action (first args)
          action-args (vec (rest args))]
      (case action
        "new"
        (plugin-new! action-args)

        "validate"
        (plugin-validate! action-args)

        "diagnose"
        (plugin-diagnose! action-args)

        "dry-run"
        (plugin-dry-run! action-args)

        "install"
        (plugin-install! action-args)

        "list"
        (plugin-list! action-args)

        "remove"
        (plugin-remove! action-args)

        "registry"
        (plugin-registry! action-args)

        (throw (ex-info "Unknown plugin command."
                        {:command action
                         :usage (usage)}))))))
