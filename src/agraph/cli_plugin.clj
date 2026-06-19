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

(defn- progress-output?
  [args]
  (and (not (json-output? args))
       (not (some #{"--no-progress"} args))))
(defn- plugin-progress-fn
  [args]
  (when (progress-output? args)
    (let [printed-header? (atom false)]
      (fn [line]
        (binding [*out* *err*]
          (when (compare-and-set! printed-header? false true)
            (println "# Plugin Progress"))
          (println line)
          (flush))))))
(defn- plugin-progress!
  [progress & parts]
  (when progress
    (progress (str/join " " (remove nil? parts)))))

(defn- claim-authority-text
  [claim-authority]
  [(str "status=" (name (:status claim-authority)))
   (str "public-claims=" (boolean (:public-claims? claim-authority)))])
(defn- claim-blocker-codes
  [claim-authority]
  (str/join "," (map (comp name :code) (:blockers claim-authority))))
(defn- present-text?
  [value]
  (and value (not (str/blank? (str value)))))
(defn- print-plugin-claim-authority
  [prefix claim-authority]
  (when claim-authority
    (apply println prefix "claim-authority" (claim-authority-text claim-authority))
    (when (seq (:blockers claim-authority))
      (println prefix "claim-blockers" (claim-blocker-codes claim-authority)))))
(defn- id-list
  [ids]
  (if (seq ids)
    (str/join "," ids)
    "none"))
(defn- print-plugin-benchmark-cases
  [prefix benchmark-cases]
  (when (and benchmark-cases (pos? (long (or (:artifacts benchmark-cases) 0))))
    (println prefix
             "benchmark-cases"
             (str "artifacts=" (:artifacts benchmark-cases))
             (str "case-ids=" (id-list (:case-ids benchmark-cases)))
             (str "problem-classes=" (id-list (:problem-classes benchmark-cases)))
             (str "improvement-metrics=" (id-list (:improvement-metrics benchmark-cases))))))
(defn- print-plugin-package
  [package]
  (println "-"
           (:id package)
           (str "version=" (:version package))
           (str "extractors=" (:extractor-plugins package))
           (str "reports=" (:report-plugins package))
           (str "benchmark=" (name (or (:benchmark-status package) :unbenchmarked))))
  (print-plugin-claim-authority " " (:claim-authority package))
  (print-plugin-benchmark-cases " " (:benchmark-cases package))
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
(defn- print-plugin-update
  [{:keys [project-id package-id package entry previous-entry update-ref update-subdir refresh?]}]
  (println "# Plugin Updated")
  (println "- project" project-id)
  (println "- package" package-id)
  (println "- refresh" refresh?)
  (when update-ref
    (println "- ref" update-ref))
  (when update-subdir
    (println "- subdir" update-subdir))
  (when-let [previous-rev (get-in previous-entry [:source :rev])]
    (println "- previous-rev" previous-rev))
  (when-let [rev (get-in entry [:source :rev])]
    (println "- rev" rev))
  (print-plugin-package package)
  (if-let [fingerprint (:manifest-fingerprint entry)]
    (println "- manifest" (:manifest entry) (str "fingerprint=" fingerprint))
    (println "- manifest" (:manifest entry)))
  (println "- path" (:path entry)))
(defn- print-next-actions
  [actions]
  (when (seq actions)
    (println "- next-actions")
    (doseq [{:keys [id command reason]} actions]
      (println " " (name id) command)
      (when reason
        (println "  " reason)))))
(defn- print-plugin-list
  [{:keys [project-id filters counts packages next-actions]}]
  (println "# Plugins")
  (println "- project" project-id)
  (when (seq filters)
    (println "- filters"
             (str/join " "
                       (keep (fn [[k v]]
                               (when (present-text? v)
                                 (str (name k) "=" v)))
                             filters))))
  (println "- packages" (or (:packages counts) (count packages)))
  (when counts
    (println "- matched" (:matched counts))
    (println "- extractor" (:extractor counts))
    (println "- report" (:report counts)))
  (print-next-actions next-actions)
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
  [{:keys [package-id path manifest files extractor? report? file-kind fixture-path visibility scope]}]
  (println "# Plugin Package Created")
  (println "- package" package-id)
  (println "- path" path)
  (println "- manifest" manifest)
  (println "- extractor" extractor?)
  (println "- report" report?)
  (when visibility
    (println "- visibility" (name visibility)))
  (when-let [scope-kind (:kind scope)]
    (println "- scope" (name scope-kind)))
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
    (print-plugin-benchmark-cases "-" (:benchmark-cases package))
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
    (print-plugin-benchmark-cases "-" (:benchmark-cases package))
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
(defn- print-plugin-core-check
  [{:keys [status package core-promotion diagnostics]}]
  (println "# Plugin Core Promotion Check")
  (println "- status" (name status))
  (when package
    (println "- package" (:id package) (str "version=" (:version package)))
    (print-plugin-benchmark-cases "-" (:benchmark-cases package))
    (print-plugin-claim-authority "-" (:claim-authority package)))
  (when core-promotion
    (println "- core-promotion"
             (name (:status core-promotion))
             "-"
             (:reason core-promotion))
    (doseq [action (:next-actions core-promotion)]
      (println "  next" action)))
  (when (seq diagnostics)
    (println "## Diagnostics")
    (doseq [{:keys [severity code message]} diagnostics]
      (println "-" (name severity) (name code) "-" message))))
(defn- maintainer-text
  [maintainer]
  (cond
    (map? maintainer) (or (:name maintainer)
                          (:id maintainer)
                          (:url maintainer)
                          (pr-str maintainer))
    (present-text? maintainer) (str maintainer)))
(defn- print-plugin-registry-entry
  [prefix entry]
  (when entry
    (println prefix
             "kinds"
             (if (sequential? (:kinds entry))
               (id-list (map name (:kinds entry)))
               "invalid"))
    (when-let [support-status (get-in entry [:support :status])]
      (println prefix "support" (name support-status)))
    (when (contains? (:trust entry) :code-reviewed?)
      (println prefix "code-reviewed" (boolean (get-in entry [:trust :code-reviewed?]))))
    (let [maintainers (keep maintainer-text (:maintainers entry))]
      (when (seq maintainers)
        (println prefix "maintainers" (str/join "," maintainers))))))
(defn- print-plugin-registry-package-summary
  [prefix package-summary]
  (when package-summary
    (println prefix
             "package"
             (str "version=" (:version package-summary))
             (str "visibility=" (name (or (:visibility package-summary) :unknown)))
             (str "benchmark=" (name (or (:benchmark-status package-summary)
                                         :unbenchmarked)))
             (str "scope=" (name (or (get-in package-summary [:scope :kind])
                                     :unknown))))
    (when-let [license (or (get-in package-summary [:license :spdx])
                           (get-in package-summary [:license :id]))]
      (println prefix "license" license))
    (print-plugin-benchmark-cases prefix (:benchmark-cases package-summary))
    (when-let [diagnostic-counts (:diagnostic-counts package-summary)]
      (println prefix "diagnostics" diagnostic-counts))))
(defn- print-plugin-selection
  [selection]
  (when selection
    (println "- selection"
             (str "available=" (id-list (:available selection)))
             (str "selected=" (id-list (:selected selection)))
             (str "skipped=" (id-list (:skipped selection))))
    (when-let [plugin-id (:requested-plugin-id selection)]
      (println "- requested-plugin" plugin-id))))
(defn- print-plugin-dry-run
  [{:keys [kind
           status
           package
           plugins
           selection
           file
           core-counts
           enhanced-counts
           counts
           diagnostics]}]
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
  (print-plugin-benchmark-cases "-" (:benchmark-cases package))
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
  (print-plugin-selection selection)
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
(defn- print-plugin-input-sample
  [{:keys [kind status package plugins selection file core-counts diagnostics inputs]}]
  (println "# Plugin Input Sample")
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
  (when file
    (println "- file"
             (:path file)
             (if-let [kind (:kind file)]
               (str "kind=" (name kind))
               "")))
  (print-plugin-benchmark-cases "-" (:benchmark-cases package))
  (println "- plugins" (id-list (map :id plugins)))
  (print-plugin-selection selection)
  (when core-counts
    (println "- core" core-counts))
  (println "- inputs" (count inputs))
  (when (seq diagnostics)
    (println "## Diagnostics")
    (doseq [{:keys [severity code message]} diagnostics]
      (println "-"
               (or (some-> severity name) "diagnostic")
               (or (some-> code name) "")
               "-"
               message))))
(defn- print-plugin-gap
  [{:keys [kind
           status
           package
           plugins
           selection
           file
           core-counts
           counts
           diagnostics
           inputs
           output-contract
           proof]}]
  (println (str "# Plugin " (str/capitalize (name (or kind :extractor))) " Gap"))
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
  (when file
    (println "- file"
             (:path file)
             (if-let [kind (:kind file)]
               (str "kind=" (name kind))
               "")))
  (print-plugin-benchmark-cases "-" (:benchmark-cases package))
  (println "- plugins" (id-list (map :id plugins)))
  (print-plugin-selection selection)
  (when core-counts
    (println "- core" core-counts))
  (when counts
    (println "- output" counts))
  (println "- inputs" (count inputs))
  (println "- output-schema" (:schema output-contract))
  (println "- output-buckets" (id-list (map (comp name :name)
                                            (:buckets output-contract))))
  (when (seq (:local-checks proof))
    (println "## Proof Commands")
    (doseq [{:keys [id command]} (:local-checks proof)]
      (println "-" (name id) command)))
  (when (seq diagnostics)
    (println "## Diagnostics")
    (doseq [{:keys [severity code message]} diagnostics]
      (println "-"
               (or (some-> severity name) "diagnostic")
               (or (some-> code name) "")
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
  (doseq [{:keys [id status errors install diagnosis registry-entry package-summary]} packages]
    (println "-" id (name status))
    (print-plugin-registry-entry " " registry-entry)
    (print-plugin-registry-package-summary " " package-summary)
    (when-let [command (:command install)]
      (println "  install" command))
    (print-plugin-claim-authority " " (get-in diagnosis [:package :claim-authority]))
    (doseq [{:keys [code message]} errors]
      (println "  error" (name code) "-" message))))

(defn- print-plugin-registry-list
  [{:keys [status path filters counts errors packages]}]
  (println "# Plugin Registry")
  (println "- status" (name status))
  (println "- path" path)
  (when (seq filters)
    (println "- filters"
             (str/join " "
                       (keep (fn [[k v]]
                               (when (present-text? v)
                                 (str (name k) "=" v)))
                             filters))))
  (println "- packages" (:packages counts))
  (println "- matched" (:matched counts))
  (println "- listed" (:listed counts))
  (println "- invalid" (:invalid counts))
  (println "- installable" (:installable counts))
  (doseq [{:keys [code message]} errors]
    (println "- error" (name code) "-" message))
  (doseq [{:keys [id status errors install registry-entry]} packages]
    (println "-" id (name status))
    (print-plugin-registry-entry " " registry-entry)
    (when-let [description (:description registry-entry)]
      (println " " "description" description))
    (when-let [command (:command install)]
      (println "  install" command))
    (doseq [{:keys [code message]} errors]
      (println "  error" (name code) "-" message))))

(defn- print-plugin-registry-install
  [{:keys [registry-path package-id registry-package install]}]
  (println "# Plugin Registry Install")
  (println "- registry" registry-path)
  (println "- package" package-id)
  (when-let [entry (:registry-entry registry-package)]
    (print-plugin-registry-entry "-" entry))
  (print-plugin-registry-package-summary "-" (:package-summary registry-package))
  (when-let [command (get-in registry-package [:install :command])]
    (println "- registry-command" command))
  (print-plugin-install install))

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
                           :public-base? (boolean (some #{"--public-base"} args))
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
(defn- plugin-core-check!
  [args]
  (let [dir (first (positional-args args))]
    (when-not dir
      (throw (ex-info "Missing plugin package directory."
                      {:usage (usage)})))
    (let [result (plugin-package/core-promotion-check dir)]
      (if (json-output? args)
        (print-json result)
        (print-plugin-core-check result))
      (when (= :failed (:status result))
        (throw (ex-info "Plugin core-promotion check failed."
                        {:core-promotion (:core-promotion result)
                         :diagnostics (:diagnostics result)}))))))
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
    (let [progress (plugin-progress-fn args)
          opts {:plugin-id (option-value args "--plugin")}
          _ (plugin-progress! progress
                              "- dry-run start"
                              kind
                              package-dir
                              (when (= "extractor" kind) file))
          result (case kind
                   "extractor"
                   (plugin-package/dry-run-extractor package-dir root file opts)

                   "report"
                   (plugin-package/dry-run-report package-dir opts))]
      (plugin-progress! progress
                        "- dry-run complete"
                        (str "status=" (name (:status result)))
                        (str "plugins=" (count (:plugins result))))
      (if (json-output? args)
        (print-json result)
        (print-plugin-dry-run result))
      (when (= :failed (:status result))
        (throw (ex-info "Plugin dry-run failed."
                        {:kind kind
                         :status (:status result)
                         :diagnostics (:diagnostics result)}))))))
(defn- plugin-input!
  [args]
  (let [[kind package-dir root file] (positional-args args)]
    (when-not (#{"extractor" "report"} kind)
      (throw (ex-info "Unsupported plugin input kind."
                      {:kind kind
                       :supported ["extractor" "report"]
                       :usage (usage)})))
    (when-not package-dir
      (throw (ex-info "Missing plugin input package directory."
                      {:usage (usage)})))
    (when (and (= "extractor" kind)
               (not (and root file)))
      (throw (ex-info "Missing plugin input repo root or file."
                      {:usage (usage)})))
    (let [opts {:plugin-id (option-value args "--plugin")}
          result (case kind
                   "extractor"
                   (plugin-package/sample-extractor-inputs package-dir
                                                           root
                                                           file
                                                           opts)

                   "report"
                   (plugin-package/sample-report-inputs package-dir opts))]
      (if (json-output? args)
        (print-json result)
        (print-plugin-input-sample result))
      (when (= :failed (:status result))
        (throw (ex-info "Plugin input sample failed."
                        {:kind kind
                         :status (:status result)
                         :diagnostics (:diagnostics result)}))))))
(defn- plugin-gap!
  [args]
  (let [[kind package-dir root file] (positional-args args)]
    (when-not (#{"extractor" "report"} kind)
      (throw (ex-info "Unsupported plugin gap kind."
                      {:kind kind
                       :supported ["extractor" "report"]
                       :usage (usage)})))
    (when-not package-dir
      (throw (ex-info "Missing plugin gap package directory."
                      {:usage (usage)})))
    (when (and (= "extractor" kind)
               (not (and root file)))
      (throw (ex-info "Missing plugin gap repo root or file."
                      {:usage (usage)})))
    (let [opts {:plugin-id (option-value args "--plugin")}
          result (case kind
                   "extractor"
                   (plugin-package/extractor-gap-packet package-dir root file opts)

                   "report"
                   (plugin-package/report-gap-packet package-dir opts))]
      (if (json-output? args)
        (print-json result)
        (print-plugin-gap result))
      (when (= :failed (:status result))
        (throw (ex-info "Plugin gap packet failed."
                        {:kind kind
                         :status (:status result)
                         :diagnostics (:diagnostics result)}))))))
(defn- plugin-install!
  [args]
  (let [[config-path source] (positional-args args)]
    (when-not (and config-path source)
      (throw (ex-info "Missing plugin project config path or git source."
                      {:usage (usage)})))
    (let [progress (plugin-progress-fn args)
          _ (plugin-progress! progress "- install start" source)
          result (plugin-package/install!
                  config-path
                  source
                  {:ref (option-value args "--ref")
                   :subdir (option-value args "--subdir")
                   :cache-root (option-value args "--cache-dir")
                   :force? (boolean (some #{"--force"} args))})]
      (plugin-progress! progress
                        "- install complete"
                        (str "package=" (get-in result [:package :id]))
                        (when-let [rev (get-in result [:entry :source :rev])]
                          (str "rev=" rev)))
      (if (json-output? args)
        (print-json result)
        (print-plugin-install result)))))
(defn- plugin-update!
  [args]
  (let [[config-path package-id] (positional-args args)]
    (when-not (and config-path package-id)
      (throw (ex-info "Missing plugin project config path or package id."
                      {:usage (usage)})))
    (let [progress (plugin-progress-fn args)
          _ (plugin-progress! progress "- update start" package-id)
          result (plugin-package/update!
                  config-path
                  package-id
                  {:ref (option-value args "--ref")
                   :subdir (option-value args "--subdir")
                   :cache-root (option-value args "--cache-dir")})]
      (plugin-progress! progress
                        "- update complete"
                        (str "package=" (:package-id result))
                        (when-let [rev (get-in result [:entry :source :rev])]
                          (str "rev=" rev)))
      (if (json-output? args)
        (print-json result)
        (print-plugin-update result)))))
(defn- plugin-list!
  [args]
  (let [config-path (first (positional-args args))]
    (when-not config-path
      (throw (ex-info "Missing plugin project config path."
                      {:usage (usage)})))
    (let [result (plugin-package/list-installed
                  config-path
                  (cond-> {}
                    (option-value args "--kind")
                    (assoc :kind (option-value args "--kind"))

                    (option-value args "--query")
                    (assoc :query (option-value args "--query"))))]
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
  (let [[action registry-path config-path package-id] (positional-args args)]
    (when-not (#{"list" "validate" "install"} action)
      (throw (ex-info "Unknown plugin registry command."
                      {:command action
                       :supported ["list" "validate" "install"]
                       :usage (usage)})))
    (when-not registry-path
      (throw (ex-info "Missing plugin registry path."
                      {:usage (usage)})))
    (case action
      "list"
      (let [progress (plugin-progress-fn args)
            _ (plugin-progress! progress "- registry list start" registry-path)
            result (plugin-package/list-registry
                    registry-path
                    (cond-> {}
                      (option-value args "--kind")
                      (assoc :kind (option-value args "--kind"))

                      (option-value args "--query")
                      (assoc :query (option-value args "--query"))))]
        (plugin-progress! progress
                          "- registry list complete"
                          (str "status=" (name (:status result)))
                          (str "matched=" (get-in result [:counts :matched] 0))
                          (str "installable=" (get-in result [:counts :installable] 0)))
        (if (json-output? args)
          (print-json result)
          (print-plugin-registry-list result))
        (when (= :failed (:status result))
          (throw (ex-info "Plugin registry list failed."
                          {:errors (:errors result)}))))

      "validate"
      (let [progress (plugin-progress-fn args)
            _ (plugin-progress! progress "- registry validate start" registry-path)
            result (plugin-package/validate-registry registry-path)]
        (plugin-progress! progress
                          "- registry validate complete"
                          (str "status=" (name (:status result)))
                          (str "packages=" (get-in result [:counts :packages] 0))
                          (str "failed=" (get-in result [:counts :failed] 0)))
        (if (json-output? args)
          (print-json result)
          (print-plugin-registry-validation result))
        (when (= :failed (:status result))
          (throw (ex-info "Plugin registry validation failed."
                          {:errors (:errors result)
                           :packages (:packages result)}))))

      "install"
      (do
        (when-not (and config-path package-id)
          (throw (ex-info "Missing plugin registry install project config path or package id."
                          {:usage (usage)})))
        (let [progress (plugin-progress-fn args)
              _ (plugin-progress! progress "- registry install start" package-id)
              result (plugin-package/registry-install!
                      registry-path
                      config-path
                      package-id
                      {:cache-root (option-value args "--cache-dir")
                       :force? (boolean (some #{"--force"} args))})]
          (plugin-progress! progress
                            "- registry install complete"
                            (str "package=" package-id)
                            (when-let [rev (get-in result
                                                   [:install :entry :source :rev])]
                              (str "rev=" rev)))
          (if (json-output? args)
            (print-json result)
            (print-plugin-registry-install result)))))))
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

        "core-check"
        (plugin-core-check! action-args)

        "dry-run"
        (plugin-dry-run! action-args)

        "input"
        (plugin-input! action-args)

        "gap"
        (plugin-gap! action-args)

        "install"
        (plugin-install! action-args)

        "update"
        (plugin-update! action-args)

        "list"
        (plugin-list! action-args)

        "remove"
        (plugin-remove! action-args)

        "registry"
        (plugin-registry! action-args)

        (throw (ex-info "Unknown plugin command."
                        {:command action
                         :usage (usage)}))))))
