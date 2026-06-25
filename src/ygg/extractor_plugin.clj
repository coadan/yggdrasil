(ns ygg.extractor-plugin
  "Project-enabled extractor plugins.

  Plugins are explicit local extensions. They run after core extraction and may
  also opt files into scanning through configured globs. Their output is
  validated into canonical graph rows with plugin provenance."
  (:require [ygg.fs :as fs]
            [ygg.hash :as hash]
            [ygg.schema :as schema]
            [ygg.text :as text]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def input-schema
  "ygg.extractor-plugin.input/v1")

(def result-schema
  "ygg.extractor-plugin.result/v1")

(def contract-version
  "ygg.extractor-plugin/v1")

(def default-timeout-ms
  10000)

(def plugin-modes
  #{:enhance :override :scan})

(def ^:private file-transform-modes
  #{:enhance :override})

(def benchmark-statuses
  #{:unbenchmarked :benchmarked})

(def row-buckets
  [:nodes :edges :chunks :file-facts :diagnostics])

(def ^:private top-level-key-aliases
  {:fileFacts :file-facts})

(def ^:private row-key-aliases
  {:xtId :xt/id
   :fileId :file-id
   :sourceId :source-id
   :targetId :target-id
   :sourceLine :source-line
   :endLine :end-line
   :definitionKind :definition-kind
   :headingPath :heading-path
   :contentSha :content-sha
   :normalizedValue :normalized-value
   :fileKind :file-kind
   :packageName :package-name
   :versionRange :version-range
   :resolvedVersion :resolved-version
   :dependencyScope :dependency-scope
   :importNames :import-names
   :importName :import-name
   :importKind :import-kind
   :resolutionSource :resolution-source
   :urlContext :url-context
   :pluginId :plugin-id
   :pluginVersion :plugin-version
   :pluginFingerprint :plugin-fingerprint
   :benchmarkStatus :benchmark-status
   :packageClaimAuthority :plugin-package-claim-authority
   :packageManifestFingerprint :plugin-package-manifest-fingerprint
   :packageSource :plugin-package-source
   :replacementId :replacement-id})

(defn- present?
  [value]
  (and value (not (str/blank? (str value)))))

(defn- normalize-command
  [plugin-id command]
  (let [command (mapv str command)]
    (when-not (seq command)
      (throw (ex-info "Extractor plugin is missing :command."
                      {:plugin-id plugin-id})))
    command))

(defn- normalize-path-globs
  [path-globs]
  (->> path-globs
       (map str)
       (remove str/blank?)
       vec))

(defn- normalize-file-kinds
  [file-kinds]
  (->> file-kinds
       (map keyword)
       set))

(defn- normalize-modes
  [plugin]
  (let [raw-modes (or (:modes plugin)
                      (when-let [mode (:mode plugin)] [mode])
                      (cond-> [:enhance]
                        (:scan plugin) (conj :scan)))
        modes (->> raw-modes (map keyword) (filter plugin-modes) set)]
    (if (seq modes)
      modes
      #{:enhance})))

(defn- normalize-applies-to
  [applies-to]
  (let [file-kinds (normalize-file-kinds (:file-kinds applies-to))
        path-globs (normalize-path-globs (:path-globs applies-to))]
    (cond-> {}
      (seq file-kinds) (assoc :file-kinds file-kinds)
      (seq path-globs) (assoc :path-globs path-globs))))

(defn- normalize-scan
  [scan]
  (let [path-globs (normalize-path-globs (:path-globs scan))]
    (when (seq path-globs)
      {:path-globs path-globs
       :file-kind (keyword (or (:file-kind scan) :plugin-source))})))

(defn- normalize-search
  "Normalize plugin-provided query surface options.

  Search options are opt-in because graph-profile sync intentionally suppresses
  ordinary chunks and search docs. `:chunks? true` keeps plugin chunks searchable
  under graph profile without enabling the full query profile."
  [plugin]
  (let [search (:search plugin)
        chunks? (or (true? (:search-docs? plugin))
                    (true? (:chunks? search))
                    (true? (:chunks search)))]
    (cond-> {}
      chunks? (assoc :chunks? true))))

(defn normalize-plugin
  "Normalize one extractor plugin config entry.

  Plugins are disabled by absence: only entries present in project config or
  index options are used."
  [plugin]
  (let [plugin-id (some-> (:id plugin) str)]
    (when-not (present? plugin-id)
      (throw (ex-info "Extractor plugin is missing :id." {:plugin plugin})))
    (let [scan (normalize-scan (:scan plugin))
          modes (cond-> (normalize-modes plugin)
                  scan (conj :scan))
          benchmark-status (keyword (or (:benchmark-status plugin)
                                        (get-in plugin [:benchmark :status])
                                        :unbenchmarked))]
      (when-not (contains? benchmark-statuses benchmark-status)
        (throw (ex-info "Unknown extractor plugin benchmark status."
                        {:plugin-id plugin-id
                         :benchmark-status benchmark-status
                         :supported (sort benchmark-statuses)})))
      (cond-> {:id plugin-id
               :version (str (or (:version plugin) "dev"))
               :command (normalize-command plugin-id (:command plugin))
               :phase (keyword (or (:phase plugin) :post-core))
               :modes modes
               :applies-to (normalize-applies-to (:applies-to plugin))
               :timeout-ms (long (or (:timeout-ms plugin) default-timeout-ms))
               :authority (keyword (or (:authority plugin) :project-plugin))
               :benchmark-status benchmark-status
               :search (normalize-search plugin)
               :emits (mapv keyword (:emits plugin))
               :cwd (some-> (:cwd plugin) str)
               :package-id (some-> (:package-id plugin) str)
               :package-version (some-> (:package-version plugin) str)
               :package-rev (some-> (:package-rev plugin) str)
               :package-manifest-fingerprint (some-> (:package-manifest-fingerprint plugin) str)
               :package-claim-authority (:package-claim-authority plugin)
               :package-source (:package-source plugin)
               :fingerprint-seed (:fingerprint plugin)}
        scan (assoc :scan scan)))))

(defn normalize-plugins
  "Normalize a vector of extractor plugin config entries."
  [plugins]
  (mapv normalize-plugin (or plugins [])))

(defn plugin-fingerprint
  "Return the stable fingerprint for a normalized plugin config."
  [plugin]
  (str "plugin:"
       (hash/short-hash [contract-version
                         (:id plugin)
                         (:version plugin)
                         (:command plugin)
                         (:phase plugin)
                         (:modes plugin)
                         (:applies-to plugin)
                         (:scan plugin)
                         (:benchmark-status plugin)
                         (:search plugin)
                         (:emits plugin)
                         (:cwd plugin)
                         (:package-id plugin)
                         (:package-version plugin)
                         (:package-rev plugin)
                         (:package-manifest-fingerprint plugin)
                         (:package-source plugin)
                         (:fingerprint-seed plugin)])))

(defn search-chunk-plugin-ids
  "Return plugin ids whose chunks are explicit query surface material."
  [plugins]
  (->> plugins
       (keep (fn [{:keys [id search]}]
               (when (:chunks? search)
                 id)))
       set))

(defn scan-specs
  "Return plugin scan specs consumable by `ygg.fs/scan-plugin-files`."
  [plugins]
  (->> plugins
       (filter #(contains? (:modes %) :scan))
       (keep (fn [{:keys [id scan]}]
               (when scan
                 (assoc scan :plugin-id id))))
       vec))

(defn- path-matches-any?
  [path path-globs]
  (or (empty? path-globs)
      (some #(fs/path-glob-matches? % path) path-globs)))

(defn plugin-applies-to-file?
  "Return true if a normalized plugin should transform the file extraction."
  [plugin file]
  (let [{:keys [file-kinds path-globs]} (:applies-to plugin)]
    (and (some file-transform-modes (:modes plugin))
         (or (empty? file-kinds) (contains? file-kinds (:kind file)))
         (path-matches-any? (:path file) path-globs))))

(defn applicable-plugins
  "Return normalized plugins that should run for `file`."
  [plugins file]
  (let [transformers (filter #(plugin-applies-to-file? % file) plugins)
        scanned-ids (set (:plugin-ids file))
        scanners (filter #(and (contains? (:modes %) :scan)
                               (contains? scanned-ids (:id %)))
                         plugins)]
    (->> (concat transformers scanners)
         (distinct)
         vec)))

(defn applicable-fingerprints
  "Return plugin fingerprints that affect a file."
  [plugins file]
  (mapv plugin-fingerprint (applicable-plugins plugins file)))

(defn- plugin-diagnostic
  [run-id file plugin stage message]
  {:xt/id (str "diagnostic:"
               (hash/short-hash [(:file-id file)
                                 (:id plugin)
                                 stage
                                 message]))
   :file-id (:file-id file)
   :path (:path file)
   :stage :extractor-plugin
   :message (str (:id plugin) " " (name stage) ": " message)
   :active? true
   :run-id run-id
   :provenance :plugin
   :plugin-id (:id plugin)
   :plugin-version (:version plugin)
   :plugin-fingerprint (plugin-fingerprint plugin)
   :plugin-authority (:authority plugin)
   :plugin-package-id (:package-id plugin)
   :plugin-package-version (:package-version plugin)
   :plugin-package-rev (:package-rev plugin)
   :plugin-package-manifest-fingerprint (:package-manifest-fingerprint plugin)
   :plugin-package-claim-authority (:package-claim-authority plugin)
   :plugin-package-source (:package-source plugin)
   :benchmark-status (:benchmark-status plugin)})

(defn- process-result!
  [command input timeout-ms cwd]
  (let [builder (ProcessBuilder. ^java.util.List command)
        _ (when (present? cwd)
            (.directory builder (io/file cwd)))
        process (.start builder)
        out-future (future (slurp (.getInputStream process)))
        err-future (future (slurp (.getErrorStream process)))]
    (with-open [writer (io/writer (.getOutputStream process))]
      (.write writer input))
    (if (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
      {:exit (.exitValue process)
       :out @out-future
       :err @err-future}
      (do
        (.destroyForcibly process)
        {:timeout? true
         :out (deref out-future 100 "")
         :err (deref err-future 100 "")}))))

(defn- select-plugin-file
  [file]
  (select-keys file
               [:file-id
                :path
                :absolute-path
                :ext
                :kind
                :content
                :content-sha
                :mtime-ms
                :size-bytes
                :binary?
                :plugin-scanned?
                :plugin-ids]))

(defn- plugin-input-summary
  [plugin]
  (cond-> {:id (:id plugin)
           :version (:version plugin)
           :authority (:authority plugin)
           :benchmarkStatus (name (:benchmark-status plugin))}
    (:package-id plugin) (assoc :packageId (:package-id plugin))
    (:package-version plugin) (assoc :packageVersion (:package-version plugin))
    (:package-rev plugin) (assoc :packageRev (:package-rev plugin))
    (:package-manifest-fingerprint plugin)
    (assoc :packageManifestFingerprint (:package-manifest-fingerprint plugin))
    (:package-claim-authority plugin)
    (assoc :packageClaimAuthority (:package-claim-authority plugin))
    (:package-source plugin) (assoc :packageSource (:package-source plugin))))

(defn- select-core-extraction
  [core-extraction]
  (cond-> (select-keys core-extraction [:nodes :edges :chunks :diagnostics])
    (contains? core-extraction :file-facts)
    (assoc :fileFacts (:file-facts core-extraction))))

(defn- plugin-input
  [{:keys [run-id project-id repo-id root-path file core-extraction]} plugin]
  {:schema input-schema
   :project {:id project-id}
   :repo {:id repo-id
          :root root-path}
   :run {:id run-id}
   :plugin (plugin-input-summary plugin)
   :file (select-plugin-file file)
   :core (select-core-extraction core-extraction)})

(defn build-plugin-input
  "Build the JSON-compatible input packet sent to one extractor plugin.

  This is useful for dry-run tooling and agent authoring flows that need the
  contract without executing the plugin command."
  [ctx plugin]
  (plugin-input ctx plugin))

(defn- parse-output
  [out]
  (json/read-json out :key-fn keyword))

(defn- canonical-key
  [k]
  (get row-key-aliases k k))

(defn- canonical-row
  [row]
  (reduce-kv (fn [m k v]
               (assoc m (canonical-key k) v))
             {}
             row))

(defn- canonical-top-level
  [result]
  (reduce-kv (fn [m k v]
               (assoc m (get top-level-key-aliases k k) v))
             {}
             result))

(defn- keyword-value
  [row k]
  (if-let [value (get row k)]
    (assoc row k (keyword value))
    row))

(defn- keyword-values
  [row keys]
  (reduce keyword-value row keys))

(defn- generated-id
  [prefix plugin file row]
  (str prefix
       ":plugin:"
       (hash/short-hash [(:id plugin)
                         (:file-id file)
                         (:path file)
                         (:kind row)
                         (:label row)
                         (:source-line row)
                         (:source-id row)
                         (:target-id row)
                         (:relation row)])))

(defn- provenance
  [plugin]
  {:provenance :plugin
   :plugin-id (:id plugin)
   :plugin-version (:version plugin)
   :plugin-fingerprint (plugin-fingerprint plugin)
   :plugin-authority (:authority plugin)
   :plugin-package-id (:package-id plugin)
   :plugin-package-version (:package-version plugin)
   :plugin-package-rev (:package-rev plugin)
   :plugin-package-manifest-fingerprint (:package-manifest-fingerprint plugin)
   :plugin-package-claim-authority (:package-claim-authority plugin)
   :plugin-package-source (:package-source plugin)
   :benchmark-status (:benchmark-status plugin)})

(defn- base-row
  [run-id file plugin row]
  (merge {:file-id (:file-id file)
          :path (:path file)
          :active? true
          :run-id run-id}
         row
         (provenance plugin)))

(defn- normalize-node
  [run-id file plugin row]
  (let [row (base-row run-id
                      file
                      plugin
                      (-> row
                          canonical-row
                          (keyword-values [:kind :ecosystem])))]
    (cond-> row
      (not (:xt/id row)) (assoc :xt/id (generated-id "node" plugin file row))
      (not (:source-line row)) (assoc :source-line 1))))

(defn- normalize-edge
  [run-id file plugin row]
  (let [row (base-row run-id
                      file
                      plugin
                      (-> row
                          canonical-row
                          (keyword-values [:relation
                                           :confidence
                                           :ecosystem
                                           :import-kind
                                           :resolution-source])))]
    (cond-> row
      (not (:xt/id row)) (assoc :xt/id (generated-id "edge" plugin file row))
      (not (:confidence row)) (assoc :confidence :plugin))))

(defn- normalize-chunk
  [run-id file plugin row]
  (let [row (base-row run-id
                      file
                      plugin
                      (-> row
                          canonical-row
                          (keyword-values [:kind :definition-kind])))]
    (cond-> row
      (not (:xt/id row)) (assoc :xt/id (generated-id "chunk" plugin file row))
      (not (:source-line row)) (assoc :source-line 1)
      (not (:tokens row)) (assoc :tokens (text/tokenize-all (or (:text row)
                                                                (:label row)
                                                                ""))))))

(defn- normalize-file-fact
  [run-id file plugin row]
  (let [row (base-row run-id
                      file
                      plugin
                      (-> row
                          canonical-row
                          (keyword-values [:kind :file-kind :url-context])))]
    (cond-> row
      (not (:xt/id row)) (assoc :xt/id (generated-id "file-fact" plugin file row))
      (not (:file-kind row)) (assoc :file-kind (:kind file))
      (not (:source-line row)) (assoc :source-line 1)
      (not (:confidence row)) (assoc :confidence 0.7))))

(defn- normalize-diagnostic
  [run-id file plugin row]
  (let [row (base-row run-id
                      file
                      plugin
                      (-> row
                          canonical-row
                          (keyword-values [:stage])))]
    (cond-> row
      (not (:xt/id row)) (assoc :xt/id (generated-id "diagnostic" plugin file row))
      (not (:stage row)) (assoc :stage :extractor-plugin))))

(defn- normalize-overlay
  [run-id file plugin row]
  (base-row run-id
            file
            plugin
            (-> row
                canonical-row
                (keyword-values [:op :kind]))))

(defn- validate-bucket
  [schema rows message]
  (mapv #(schema/assert! schema % message) rows))

(defn- normalize-plugin-result
  [run-id file plugin result]
  (let [result (canonical-top-level result)
        nodes (mapv #(normalize-node run-id file plugin %) (:nodes result))
        edges (mapv #(normalize-edge run-id file plugin %) (:edges result))
        chunks (mapv #(normalize-chunk run-id file plugin %) (:chunks result))
        file-facts (mapv #(normalize-file-fact run-id file plugin %)
                         (:file-facts result))
        diagnostics (mapv #(normalize-diagnostic run-id file plugin %)
                          (:diagnostics result))]
    {:nodes (validate-bucket schema/node-row nodes "Invalid extractor plugin node row.")
     :edges (validate-bucket schema/edge-row edges "Invalid extractor plugin edge row.")
     :chunks (validate-bucket schema/chunk-row chunks "Invalid extractor plugin chunk row.")
     :file-facts (validate-bucket schema/file-fact-row
                                  file-facts
                                  "Invalid extractor plugin file fact row.")
     :diagnostics (validate-bucket schema/diagnostic-row
                                   diagnostics
                                   "Invalid extractor plugin diagnostic row.")
     :overlays (mapv #(normalize-overlay run-id file plugin %) (:overlays result))}))

(defn- run-plugin
  [ctx plugin]
  (try
    (let [input (json/write-json-str (plugin-input ctx plugin) {:escape-slash false})
          {:keys [exit out err timeout?]} (process-result! (:command plugin)
                                                           input
                                                           (:timeout-ms plugin)
                                                           (or (:cwd plugin)
                                                               (:root-path ctx)))]
      (cond
        timeout?
        {:diagnostics [(plugin-diagnostic (:run-id ctx)
                                          (:file ctx)
                                          plugin
                                          :timeout
                                          (str "timed out after "
                                               (:timeout-ms plugin)
                                               " ms"))]}

        (not (zero? exit))
        {:diagnostics [(plugin-diagnostic (:run-id ctx)
                                          (:file ctx)
                                          plugin
                                          :exit
                                          (str "exit " exit ": " (str/trim err)))]}

        :else
        (let [parsed (parse-output out)]
          (if (= result-schema (:schema parsed))
            (normalize-plugin-result (:run-id ctx) (:file ctx) plugin parsed)
            {:diagnostics [(plugin-diagnostic (:run-id ctx)
                                              (:file ctx)
                                              plugin
                                              :schema
                                              (str "expected "
                                                   result-schema
                                                   ", got "
                                                   (pr-str (:schema parsed))))]}))))
    (catch Exception e
      {:diagnostics [(plugin-diagnostic (:run-id ctx)
                                        (:file ctx)
                                        plugin
                                        :error
                                        (or (ex-message e) (str e)))]})))

(defn- dedupe-by-id
  [rows]
  (->> rows
       (reduce (fn [m row]
                 (assoc m (:xt/id row) row))
               {})
       vals
       (sort-by :xt/id)
       vec))

(defn- merge-bucket
  [extraction plugin-extractions bucket]
  (dedupe-by-id (mapcat #(get % bucket []) (cons extraction plugin-extractions))))

(defn- overlay-op
  [overlay]
  (keyword (or (:op overlay) (:kind overlay))))

(defn- supersede-overlays
  [plugin-extractions]
  (->> plugin-extractions
       (mapcat :overlays)
       (filter #(contains? #{:supersedes :hides} (overlay-op %)))
       (keep (fn [overlay]
               (when-let [target-id (:target-id overlay)]
                 [target-id overlay])))
       (into {})))

(defn- annotate-superseded-row
  [overlays row]
  (if-let [overlay (get overlays (:xt/id row))]
    (assoc row
           :superseded? true
           :superseded-op (overlay-op overlay)
           :superseded-by (:replacement-id overlay)
           :superseded-reason (:reason overlay)
           :superseded-by-plugin-id (:plugin-id overlay)
           :superseded-by-plugin-fingerprint (:plugin-fingerprint overlay)
           :superseded-by-plugin-package-id (:plugin-package-id overlay)
           :superseded-by-plugin-package-rev (:plugin-package-rev overlay)
           :superseded-by-plugin-package-manifest-fingerprint
           (:plugin-package-manifest-fingerprint overlay)
           :superseded-by-plugin-claim-authority
           (:plugin-package-claim-authority overlay)
           :superseded-by-plugin-benchmark-status
           (:benchmark-status overlay))
    row))

(defn merge-extractions
  "Merge plugin rows over core extraction.

  Rows with the same `:xt/id` use the last plugin row. Overlays annotate rows as
  superseded or hidden but keep raw evidence persisted for auditability."
  [extraction plugin-extractions]
  (let [overlays (supersede-overlays plugin-extractions)]
    (reduce (fn [merged bucket]
              (assoc merged bucket
                     (mapv #(annotate-superseded-row overlays %)
                           (merge-bucket extraction plugin-extractions bucket))))
            extraction
            row-buckets)))

(defn transform-extraction
  "Run applicable plugins for a file and merge valid plugin output over core rows."
  [{:keys [plugins file] :as ctx} extraction]
  (let [applicable (applicable-plugins plugins file)]
    (if (seq applicable)
      (let [plugin-extractions (mapv #(run-plugin (assoc ctx :core-extraction extraction) %)
                                     applicable)]
        (merge-extractions extraction plugin-extractions))
      extraction)))
