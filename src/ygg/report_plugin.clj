(ns ygg.report-plugin
  "Generation-time report dashboard plugins.

  Report plugins are presentation extensions. They run during report generation
  over already-derived Yggdrasil report and graph artifacts. Plugins may crawl the
  exported graph in their own ways and emit dashboard panels. Core report panels
  use the same contract as project plugins."
  (:require [ygg.hash :as hash]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def input-schema
  "ygg.report-plugin.input/v1")

(def result-schema
  "ygg.report-plugin.result/v1")

(def bundle-schema
  "ygg.report.plugins/v1")

(def contract-version
  "ygg.report-plugin/v1")

(def core-plugin-id
  "ygg-core-report")

(def default-timeout-ms
  10000)

(def benchmark-statuses
  #{:unbenchmarked :benchmarked})

(def ^:private panel-key-aliases
  {:pluginId :plugin-id
   :dataKey :data-key})

(def ^:private diagnostic-key-aliases
  {:pluginId :plugin-id})

(defn- present?
  [value]
  (and value (not (str/blank? (str value)))))

(defn- normalize-command
  [plugin-id command]
  (let [command (mapv str command)]
    (when-not (seq command)
      (throw (ex-info "Report plugin is missing :command."
                      {:plugin-id plugin-id})))
    command))

(defn normalize-plugin
  "Normalize one report plugin config entry."
  [plugin]
  (let [plugin-id (some-> (:id plugin) str)]
    (when-not (present? plugin-id)
      (throw (ex-info "Report plugin is missing :id." {:plugin plugin})))
    (let [benchmark-status (keyword (or (:benchmark-status plugin)
                                        (get-in plugin [:benchmark :status])
                                        :unbenchmarked))]
      (when-not (contains? benchmark-statuses benchmark-status)
        (throw (ex-info "Unknown report plugin benchmark status."
                        {:plugin-id plugin-id
                         :benchmark-status benchmark-status
                         :supported (sort benchmark-statuses)})))
      {:id plugin-id
       :version (str (or (:version plugin) "dev"))
       :command (normalize-command plugin-id (:command plugin))
       :slots (mapv (comp name keyword) (:slots plugin))
       :timeout-ms (long (or (:timeout-ms plugin) default-timeout-ms))
       :authority (keyword (or (:authority plugin) :project-plugin))
       :cwd (some-> (:cwd plugin) str)
       :package-id (some-> (:package-id plugin) str)
       :package-version (some-> (:package-version plugin) str)
       :package-rev (some-> (:package-rev plugin) str)
       :package-manifest-fingerprint (some-> (:package-manifest-fingerprint plugin) str)
       :package-claim-authority (:package-claim-authority plugin)
       :package-source (:package-source plugin)
       :benchmark-status benchmark-status
       :fingerprint-seed (:fingerprint plugin)})))

(defn normalize-plugins
  "Normalize a vector of report plugin config entries."
  [plugins]
  (mapv normalize-plugin (or plugins [])))

(defn plugin-fingerprint
  "Return the stable fingerprint for a normalized report plugin config."
  [plugin]
  (str "plugin:"
       (hash/short-hash [contract-version
                         (:id plugin)
                         (:version plugin)
                         (:command plugin)
                         (:slots plugin)
                         (:authority plugin)
                         (:cwd plugin)
                         (:package-id plugin)
                         (:package-version plugin)
                         (:package-rev plugin)
                         (:package-manifest-fingerprint plugin)
                         (:package-source plugin)
                         (:package-claim-authority plugin)
                         (:benchmark-status plugin)
                         (:fingerprint-seed plugin)])))

(def core-plugin
  {:id core-plugin-id
   :version "1"
   :authority :core
   :fingerprint-seed "core-report-panels"})

(defn- plugin-summary
  [plugin]
  (cond-> {:id (:id plugin)
           :version (:version plugin)
           :authority (name (:authority plugin))
           :fingerprint (plugin-fingerprint plugin)}
    (:slots plugin) (assoc :slots (:slots plugin))
    (:package-id plugin) (assoc :packageId (:package-id plugin))
    (:package-version plugin) (assoc :packageVersion (:package-version plugin))
    (:package-rev plugin) (assoc :packageRev (:package-rev plugin))
    (:package-manifest-fingerprint plugin)
    (assoc :packageManifestFingerprint (:package-manifest-fingerprint plugin))
    (:package-source plugin) (assoc :packageSource (:package-source plugin))
    (:package-claim-authority plugin)
    (assoc :packageClaimAuthority (:package-claim-authority plugin))
    (:benchmark-status plugin) (assoc :benchmarkStatus (name (:benchmark-status plugin)))))

(defn- provenance
  [plugin]
  (cond-> {:provenance (if (= :core (:authority plugin)) :core :plugin)
           :plugin-id (:id plugin)
           :plugin-version (:version plugin)
           :plugin-fingerprint (plugin-fingerprint plugin)
           :plugin-authority (:authority plugin)}
    (:package-id plugin) (assoc :plugin-package-id (:package-id plugin))
    (:package-version plugin) (assoc :plugin-package-version (:package-version plugin))
    (:package-rev plugin) (assoc :plugin-package-rev (:package-rev plugin))
    (:package-manifest-fingerprint plugin)
    (assoc :plugin-package-manifest-fingerprint (:package-manifest-fingerprint plugin))
    (:package-claim-authority plugin)
    (assoc :plugin-package-claim-authority (:package-claim-authority plugin))
    (:package-source plugin) (assoc :plugin-package-source (:package-source plugin))
    (:benchmark-status plugin) (assoc :benchmark-status (:benchmark-status plugin))))

(defn- canonical-key
  [aliases k]
  (get aliases k k))

(defn- canonical-map
  [aliases value]
  (if (map? value)
    (reduce-kv (fn [m k v]
                 (assoc m (canonical-key aliases k) v))
               {}
               value)
    {}))

(defn- safe-slot
  [value]
  (let [slot (some-> value keyword name)]
    (if (present? slot)
      slot
      "plugins")))

(defn- numeric-order
  [value default]
  (if (number? value)
    value
    default))

(defn- normalize-panel
  [plugin idx panel]
  (let [panel (canonical-map panel-key-aliases panel)
        id (str (or (:id panel)
                    (str (:id plugin) "-panel-" idx)))
        label (str (or (:label panel) id))
        slot (safe-slot (:slot panel))]
    (cond-> (merge {:id id
                    :label label
                    :slot slot
                    :order (numeric-order (:order panel) (+ 100 idx))
                    :mdx (str (or (:mdx panel) ""))
                    :data (or (:data panel) {})
                    :plugin (plugin-summary plugin)}
                   (provenance plugin))
      (:component panel) (assoc :component (str (:component panel)))
      (:description panel) (assoc :description (str (:description panel))))))

(defn- diagnostic
  [plugin stage message]
  (merge {:plugin (plugin-summary plugin)
          :stage (if (keyword? stage) (name stage) (str stage))
          :message (str message)}
         (provenance plugin)))

(defn- normalize-diagnostic
  [plugin diagnostic]
  (let [diagnostic (if (map? diagnostic)
                     (canonical-map diagnostic-key-aliases diagnostic)
                     {:message diagnostic})]
    (merge (select-keys diagnostic [:path :details])
           {:plugin (plugin-summary plugin)
            :stage (str (or (:stage diagnostic) "plugin"))
            :message (str (or (:message diagnostic) "Report plugin diagnostic."))}
           (provenance plugin))))

(defn- sequence-field
  [plugin field value]
  (cond
    (nil? value)
    {:rows []
     :diagnostics []}

    (sequential? value)
    {:rows (vec value)
     :diagnostics []}

    :else
    {:rows []
     :diagnostics [(diagnostic plugin
                               (str "invalid-" (name field))
                               (str "expected " (name field) " to be an array"))]}))

(defn- normalize-panel-row
  [plugin idx panel]
  (if (map? panel)
    {:panel (normalize-panel plugin idx panel)}
    {:diagnostic (diagnostic plugin
                             :invalid-panel
                             (str "expected panel at index " idx " to be an object"))}))

(defn- normalize-artifact-row
  [plugin idx artifact]
  (if (map? artifact)
    {:artifact (merge artifact
                      {:plugin (plugin-summary plugin)}
                      (provenance plugin))}
    {:diagnostic (diagnostic plugin
                             :invalid-artifact
                             (str "expected artifact at index " idx " to be an object"))}))

(defn normalize-result
  [plugin result]
  (if (and (map? result) (= result-schema (:schema result)))
    (let [{panel-rows :rows panel-field-diagnostics :diagnostics}
          (sequence-field plugin :panels (:panels result))
          {diagnostic-rows :rows diagnostic-field-diagnostics :diagnostics}
          (sequence-field plugin :diagnostics (:diagnostics result))
          {artifact-rows :rows artifact-field-diagnostics :diagnostics}
          (sequence-field plugin :artifacts (:artifacts result))
          panels-and-diagnostics (map-indexed #(normalize-panel-row plugin %1 %2)
                                              panel-rows)
          artifacts-and-diagnostics (map-indexed #(normalize-artifact-row plugin %1 %2)
                                                 artifact-rows)]
      {:panels (->> panels-and-diagnostics
                    (keep :panel)
                    vec)
       :diagnostics (vec (concat panel-field-diagnostics
                                 diagnostic-field-diagnostics
                                 artifact-field-diagnostics
                                 (keep :diagnostic panels-and-diagnostics)
                                 (map #(normalize-diagnostic plugin %) diagnostic-rows)
                                 (keep :diagnostic artifacts-and-diagnostics)))
       :artifacts (->> artifacts-and-diagnostics
                       (keep :artifact)
                       vec)})
    {:panels []
     :diagnostics [(diagnostic plugin
                               :schema
                               (str "expected " result-schema ", got "
                                    (pr-str (:schema result))))]
     :artifacts []}))

(defn- slurp-stream-async
  [stream]
  (let [result (promise)
        thread (Thread. (fn []
                          (try
                            (deliver result (slurp stream))
                            (catch Exception e
                              (deliver result (or (ex-message e) (str e)))))))]
    (.setDaemon thread true)
    (.start thread)
    result))

(defn- process-result!
  [command input timeout-ms cwd]
  (let [builder (ProcessBuilder. ^java.util.List command)
        _ (when (present? cwd)
            (.directory builder (io/file cwd)))
        process (.start builder)
        out-result (slurp-stream-async (.getInputStream process))
        err-result (slurp-stream-async (.getErrorStream process))]
    (with-open [writer (io/writer (.getOutputStream process))]
      (.write writer input))
    (if (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
      {:exit (.exitValue process)
       :out @out-result
       :err @err-result}
      (do
        (.destroyForcibly process)
        {:timeout? true
         :out (deref out-result 100 "")
         :err (deref err-result 100 "")}))))

(defn- plugin-input
  [{:keys [project generated-at-ms report graph systems coverage maintenance evidence
           package-report plugin-package-summary artifacts]} plugin]
  {:schema input-schema
   :project (select-keys project [:id :name :path :repos])
   :generatedAtMs generated-at-ms
   :plugin (plugin-summary plugin)
   :report (assoc-in report [:plugins :packages] plugin-package-summary)
   :graphs {:overview graph
            :systems systems}
   :coverage coverage
   :maintenance maintenance
   :evidence evidence
   :packages package-report
   :pluginPackages plugin-package-summary
   :artifacts artifacts})

(defn build-plugin-input
  "Build the JSON-compatible input packet sent to one report plugin."
  [ctx plugin]
  (plugin-input ctx plugin))

(defn run-plugin
  "Run one external report plugin and return normalized panels/diagnostics."
  [ctx plugin]
  (try
    (let [input (json/write-json-str (plugin-input ctx plugin) {:escape-slash false})
          cwd (or (:cwd plugin)
                  (some-> (:project ctx) :path io/file .getParent))
          {:keys [exit out err timeout?]} (process-result! (:command plugin)
                                                           input
                                                           (:timeout-ms plugin)
                                                           cwd)]
      (cond
        timeout?
        {:panels []
         :diagnostics [(diagnostic plugin
                                   :timeout
                                   (str "timed out after " (:timeout-ms plugin) " ms"))]
         :artifacts []}

        (not (zero? exit))
        {:panels []
         :diagnostics [(diagnostic plugin :exit (str "exit " exit ": " (str/trim err)))]
         :artifacts []}

        :else
        (normalize-result plugin (json/read-json out :key-fn keyword))))
    (catch Exception e
      {:panels []
       :diagnostics [(diagnostic plugin :error (or (ex-message e) (str e)))]
       :artifacts []})))

(defn- metric
  [label value]
  {:label label
   :value (or value 0)})

(defn- count-at
  [report path]
  (or (get-in report path) 0))

(defn core-panels
  "Return first-party Yggdrasil report panels using the same contract as plugins."
  [report]
  (let [plugin core-plugin
        panels [{:id "core-atlas-summary"
                 :label "Project Atlas"
                 :slot "atlas"
                 :order 0
                 :mdx "## Project Atlas\n\n<MetricGrid dataKey=\"metrics\" />\n\n<DataTable dataKey=\"actions\" />"
                 :data {:metrics [(metric "Files" (count-at report [:atlas :evidence :files]))
                                  (metric "Nodes" (count-at report [:atlas :evidence :nodes]))
                                  (metric "Edges" (count-at report [:atlas :evidence :edges]))
                                  (metric "Systems" (count-at report [:atlas :systems :nodes]))
                                  (metric "Packages" (count-at report [:atlas :dependencies :packages]))]
                        :actions {:columns [{:key "kind" :label "Kind"}
                                            {:key "label" :label "Action"}
                                            {:key "count" :label "Count"}]
                                  :rows (get-in report [:atlas :next-actions])}}}
                {:id "core-systems-summary"
                 :label "Systems"
                 :slot "systems"
                 :order 0
                 :mdx "## Systems\n\n<MetricGrid dataKey=\"metrics\" />"
                 :data {:metrics [(metric "System Nodes" (count-at report [:atlas :systems :nodes]))
                                  (metric "System Edges" (count-at report [:atlas :systems :edges]))
                                  (metric "Clusters" (count-at report [:atlas :systems :clusters]))
                                  (metric "Visible Connections" (count-at report [:atlas :systems :visible-connections]))
                                  (metric "Orphans" (count-at report [:atlas :systems :orphaned-systems]))]}}
                {:id "core-dependency-summary"
                 :label "Dependencies"
                 :slot "dependencies"
                 :order 0
                 :mdx "## Dependencies\n\n<MetricGrid dataKey=\"metrics\" />"
                 :data {:metrics [(metric "Packages" (count-at report [:packages :counts :packages]))
                                  (metric "Versions" (count-at report [:packages :counts :versions]))
                                  (metric "Imports" (count-at report [:packages :counts :imports-package]))
                                  (metric "Unresolved" (count-at report [:packages :counts :unresolved-imports]))
                                  (metric "Conflicts" (count-at report [:packages :counts :version-conflicts]))]}}
                {:id "core-evidence-summary"
                 :label "Evidence"
                 :slot "evidence"
                 :order 0
                 :mdx "## Evidence\n\n<MetricGrid dataKey=\"metrics\" />"
                 :data {:metrics [(metric "Files" (count-at report [:evidence :counts :files]))
                                  (metric "Nodes" (count-at report [:evidence :counts :nodes]))
                                  (metric "Edges" (count-at report [:evidence :counts :edges]))
                                  (metric "Diagnostics" (count-at report [:evidence :counts :diagnostics]))
                                  (metric "Activity Events" (count-at report [:evidence :counts :activity-events]))]}}
                {:id "core-maintenance-summary"
                 :label "Maintenance"
                 :slot "maintenance"
                 :order 0
                 :mdx "## Maintenance\n\n<MetricGrid dataKey=\"metrics\" />"
                 :data {:metrics [(metric "Decisions" (count-at report [:maintenance :queue :decisions]))
                                  (metric "Infra Reviews" (count-at report [:maintenance :queue :infra-review]))
                                  (metric "Dependency Reviews" (count-at report [:maintenance :queue :dependency-review]))
                                  (metric "External API Fanouts" (count-at report [:atlas :maintenance :external-api-review :source-fanouts]))
                                  (metric "Result Mismatches" (count-at report [:atlas :activity :result-schema-mismatch-events]))]}}]]
    (mapv #(normalize-panel plugin %1 %2) (range) panels)))

(defn bundle
  "Combine core and external report plugin output into the report plugin bundle."
  [{:keys [report plugins plugin-package-summary] :as ctx}]
  (let [core {:panels (core-panels report)
              :diagnostics []
              :artifacts []}
        external (mapv #(run-plugin ctx %) plugins)
        outputs (cons core external)]
    {:schema bundle-schema
     :packages plugin-package-summary
     :panels (->> outputs (mapcat :panels) (sort-by (juxt :slot :order :id)) vec)
     :diagnostics (->> outputs (mapcat :diagnostics) vec)
     :artifacts (->> outputs (mapcat :artifacts) vec)}))
