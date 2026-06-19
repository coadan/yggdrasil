(ns agraph.coverage
  "Source type coverage reporting for project repos."
  (:require [agraph.command :as command]
            [agraph.extract :as extract]
            [agraph.fs :as fs]
            [agraph.xtdb :as store]
            [clojure.string :as str]))

(def schema
  "agraph.source-coverage/v1")

(def context-schema
  "agraph.source-coverage.context/v1")

(def ^:private grouped-sample-limit 5)

(defn- display-value
  [value]
  (cond
    (keyword? value) (name value)
    (str/blank? (str value)) "(none)"
    :else (str value)))

(defn- count-rows
  [label-key value-fn rows]
  (->> rows
       (map value-fn)
       frequencies
       (map (fn [[value n]]
              {label-key (display-value value)
               :count n}))
       (sort-by (fn [row]
                  [(- (long (:count row)))
                   (get row label-key)]))
       vec))

(defn- supported-files
  [rows]
  (filter :supported? rows))

(defn- skipped-files
  [rows]
  (remove :supported? rows))

(defn- totals
  [rows]
  (let [supported (count (supported-files rows))
        skipped (count (skipped-files rows))]
    {:files (count rows)
     :supported supported
     :skipped skipped
     :coverage (if (pos? (count rows))
                 (/ (double supported) (double (count rows)))
                 0.0)}))

(defn- extractor-rows
  [rows]
  (->> (supported-files rows)
       (group-by :kind)
       (map (fn [[kind files]]
              {:kind (display-value kind)
               :extractor-version (get extract/extractor-versions kind "none/v1")
               :files (count files)}))
       (sort-by (juxt :kind :extractor-version))
       vec))

(defn- skipped-samples
  [rows]
  (->> (skipped-files rows)
       (sort-by :path)
       (take 20)
       (mapv #(select-keys % [:path :ext :skip-reason]))))

(defn- skipped-row-sample
  [repo-id row]
  (cond-> (select-keys row [:path :ext :skip-reason])
    (seq (str repo-id)) (assoc :repo-id repo-id)))

(defn- samples-for
  [rows]
  (->> rows
       (sort-by :path)
       (take grouped-sample-limit)
       vec))

(defn- count-rows-with-samples
  [label-key value-fn sample-fn rows]
  (->> rows
       (group-by value-fn)
       (map (fn [[value value-rows]]
              {label-key (display-value value)
               :count (count value-rows)
               :samples (samples-for (map sample-fn value-rows))}))
       (sort-by (fn [row]
                  [(- (long (:count row)))
                   (get row label-key)]))
       vec))

(defn- repo-coverage
  [{:keys [id root role]}]
  (let [{:keys [files]} (fs/scan-file-coverage root)
        files (vec files)
        skipped (vec (skipped-files files))
        sample-fn #(skipped-row-sample id %)]
    {:repo-id id
     :role role
     :root root
     :totals (totals files)
     :files-by-kind (count-rows :kind :kind (supported-files files))
     :files-by-extension (count-rows :ext :ext files)
     :skipped-by-extension (count-rows-with-samples :ext :ext sample-fn skipped)
     :skipped-by-reason (count-rows-with-samples :reason
                                                 :skip-reason
                                                 sample-fn
                                                 skipped)
     :extractors (extractor-rows files)
     :skipped-samples (skipped-samples files)}))

(defn- merge-counts
  [repos k label-key]
  (->> repos
       (mapcat k)
       (reduce (fn [counts row]
                 (update counts (get row label-key) (fnil + 0) (:count row)))
               {})
       (map (fn [[value n]]
              {label-key value
               :count n}))
       (sort-by (fn [row]
                  [(- (long (:count row)))
                   (get row label-key)]))
       vec))

(defn- merge-counts-with-samples
  [repos k label-key]
  (->> repos
       (mapcat k)
       (group-by #(get % label-key))
       (map (fn [[value rows]]
              {label-key value
               :count (reduce + (map :count rows))
               :samples (samples-for (mapcat :samples rows))}))
       (sort-by (fn [row]
                  [(- (long (:count row)))
                   (get row label-key)]))
       vec))

(defn- merge-extractors
  [repos]
  (->> repos
       (mapcat :extractors)
       (group-by (juxt :kind :extractor-version))
       (map (fn [[[kind version] rows]]
              {:kind kind
               :extractor-version version
               :files (reduce + (map :files rows))}))
       (sort-by (juxt :kind :extractor-version))
       vec))

(defn- project-totals
  [repos]
  (let [files (reduce + (map #(get-in % [:totals :files]) repos))
        supported (reduce + (map #(get-in % [:totals :supported]) repos))
        skipped (reduce + (map #(get-in % [:totals :skipped]) repos))]
    {:files files
     :supported supported
     :skipped skipped
     :coverage (if (pos? files)
                 (/ (double supported) (double files))
                 0.0)}))

(defn- coverage-command
  [config-path]
  (str "agraph sync coverage "
       (command/shell-token (or config-path "<project.edn>"))
       " --json"))

(defn- coverage-next-actions
  [{:keys [totals diagnostics indexedConnectivity]} config-path]
  (let [skipped (long (or (:skipped totals) 0))
        diagnostic-count (long (or (:total diagnostics) 0))
        isolated-count (long (or (:isolatedFiles indexedConnectivity) 0))
        command (coverage-command config-path)]
    (cond-> []
      (pos? skipped)
      (conj {:kind :coverage
             :label "Inspect skipped source candidates"
             :count skipped
             :command command})

      (pos? diagnostic-count)
      (conj {:kind :coverage
             :label "Inspect extractor diagnostics"
             :count diagnostic-count
             :command command})

      (pos? isolated-count)
      (conj {:kind :coverage
             :label "Inspect isolated indexed files"
             :count isolated-count
             :command command}))))

(defn- active-rows
  [rows]
  (filter :active? rows))

(defn- active-index-row?
  [row]
  (not= false (:active? row)))

(defn- scope-match?
  [{:keys [project-id repo-id]} row]
  (and (or (str/blank? (str project-id)) (= project-id (:project-id row)))
       (or (str/blank? (str repo-id)) (= repo-id (:repo-id row)))))

(defn- scoped-active-index-rows
  [xtdb table {:keys [project-id repo-id read-context]}]
  (->> (store/all-rows xtdb table (store/read-context read-context))
       (filter active-index-row?)
       (filter #(scope-match? {:project-id project-id :repo-id repo-id} %))
       vec))

(defn- completed-index-run?
  [run]
  (= "completed" (display-value (:status run))))

(defn- run-finished-at
  [run]
  (long (or (:finished-at-ms run)
            (:started-at-ms run)
            0)))

(defn- latest-index-runs
  [runs]
  (->> runs
       (filter completed-index-run?)
       (group-by :repo-id)
       vals
       (mapv #(apply max-key run-finished-at %))))

(defn index-run-skipped-files
  "Return skipped file count from the latest completed index run per repo."
  [xtdb opts]
  (->> (scoped-active-index-rows xtdb (:index-runs store/tables) opts)
       latest-index-runs
       (map #(get-in % [:stats :files-skipped] 0))
       (reduce + 0)
       long))

(defn- context-extractor-rows
  [files]
  (->> files
       (group-by :kind)
       (map (fn [[kind kind-files]]
              {:kind (display-value kind)
               :extractorVersion (get extract/extractor-versions kind "none/v1")
               :files (count kind-files)}))
       (sort-by (juxt :kind :extractorVersion))
       vec))

(defn- extractor-fingerprint-value
  [file]
  (let [fingerprint (:extractor-fingerprint file)]
    (if (str/blank? (str fingerprint))
      "missing"
      (str fingerprint))))

(defn- context-extractor-fingerprint-rows
  [files]
  (->> files
       (group-by (juxt :kind extractor-fingerprint-value))
       (map (fn [[[kind fingerprint] kind-files]]
              {:kind (display-value kind)
               :extractorVersion (get extract/extractor-versions kind "none/v1")
               :extractorFingerprint fingerprint
               :files (count kind-files)}))
       (sort-by (juxt :kind :extractorVersion :extractorFingerprint))
       vec))

(defn- context-diagnostic-extractor-rows
  [files diagnostics]
  (let [file-by-id (into {} (map (juxt :xt/id identity)) files)]
    (->> diagnostics
         (map (fn [diagnostic]
                (let [kind (or (:kind (get file-by-id (:file-id diagnostic)))
                               :unknown)]
                  {:kind (display-value kind)
                   :extractorVersion (if (= :unknown kind)
                                       "unknown"
                                       (get extract/extractor-versions kind "none/v1"))
                   :stage (display-value (:stage diagnostic))})))
         (group-by (juxt :kind :extractorVersion :stage))
         (map (fn [[[kind version stage] rows]]
                {:kind kind
                 :extractorVersion version
                 :stage stage
                 :count (count rows)}))
         (sort-by (fn [row]
                    [(- (long (:count row)))
                     (:kind row)
                     (:stage row)]))
         vec)))

(defn- context-diagnostic-samples
  [files diagnostics]
  (let [file-by-id (into {} (map (juxt :xt/id identity)) files)]
    (->> diagnostics
         (sort-by (juxt :file-id :source-line :stage :message))
         (take 12)
         (mapv (fn [diagnostic]
                 (let [file (get file-by-id (:file-id diagnostic))]
                   (cond-> (select-keys diagnostic
                                        [:file-id
                                         :stage
                                         :message
                                         :source-line])
                     (:path file) (assoc :path (:path file))
                     (:kind file) (assoc :kind (display-value (:kind file))))))))))

(defn- edge-file-pairs
  [node-file-by-id edge]
  (let [source-file-id (get node-file-by-id (:source-id edge))
        target-file-id (get node-file-by-id (:target-id edge))]
    (cond-> []
      source-file-id (conj [source-file-id target-file-id])
      target-file-id (conj [target-file-id source-file-id]))))

(defn- connected-file-ids
  [node-file-by-id edges]
  (->> edges
       (mapcat #(edge-file-pairs node-file-by-id %))
       (map first)
       set))

(defn- cross-file-connected-file-ids
  [node-file-by-id edges]
  (->> edges
       (mapcat #(edge-file-pairs node-file-by-id %))
       (filter (fn [[file-id neighbor-file-id]]
                 (and neighbor-file-id
                      (not= file-id neighbor-file-id))))
       (map first)
       set))

(defn- connectivity-kind-rows
  [files connected-file-ids cross-file-file-ids]
  (->> files
       (group-by :kind)
       (map (fn [[kind kind-files]]
              (let [file-ids (set (map :xt/id kind-files))]
                {:kind (display-value kind)
                 :indexedFiles (count kind-files)
                 :connectedFiles (count (filter file-ids connected-file-ids))
                 :crossFileConnectedFiles (count (filter file-ids
                                                         cross-file-file-ids))
                 :isolatedFiles (count (remove connected-file-ids file-ids))})))
       (sort-by (juxt :kind))
       vec))

(defn- indexed-connectivity-from-rows
  [files nodes edges]
  (let [file-ids (set (map :xt/id files))
        node-file-by-id (into {} (keep (fn [node]
                                         (when (contains? file-ids (:file-id node))
                                           [(:xt/id node) (:file-id node)])))
                              nodes)
        connected-ids (connected-file-ids node-file-by-id edges)
        cross-file-ids (cross-file-connected-file-ids node-file-by-id edges)]
    {:indexedFiles (count files)
     :nodes (count nodes)
     :edges (count edges)
     :connectedFiles (count connected-ids)
     :crossFileConnectedFiles (count cross-file-ids)
     :isolatedFiles (count (remove connected-ids file-ids))
     :byKind (connectivity-kind-rows files connected-ids cross-file-ids)}))

(defn- context-next-actions
  [skipped-files diagnostics indexed-connectivity]
  (let [diagnostic-count (count diagnostics)
        isolated-count (long (or (:isolatedFiles indexed-connectivity) 0))]
    (cond-> []
      (pos? skipped-files)
      (conj {:kind :coverage
             :label "Inspect skipped source candidates"
             :count skipped-files
             :command (coverage-command nil)})

      (pos? diagnostic-count)
      (conj {:kind :coverage
             :label "Inspect extractor diagnostics"
             :count diagnostic-count
             :command (coverage-command nil)})

      (pos? isolated-count)
      (conj {:kind :coverage
             :label "Inspect isolated indexed files"
             :count isolated-count
             :command (coverage-command nil)}))))

(defn context-summary
  "Return compact indexed source coverage for context packets.

  This summarizes the graph rows currently available to the selected
  project/repo/read context. It does not scan the filesystem for skipped or
  unsupported source candidates; use `project-coverage` for full repo coverage."
  [xtdb opts]
  (let [files (scoped-active-index-rows xtdb (:files store/tables) opts)
        nodes (scoped-active-index-rows xtdb (:nodes store/tables) opts)
        edges (scoped-active-index-rows xtdb (:edges store/tables) opts)
        diagnostics (scoped-active-index-rows xtdb (:diagnostics store/tables) opts)
        skipped-files (index-run-skipped-files xtdb opts)
        indexed-connectivity (indexed-connectivity-from-rows files nodes edges)
        summary {:schema context-schema
                 :basis "indexed-graph"
                 :totals {:indexedFiles (count files)
                          :skippedFiles skipped-files
                          :diagnostics (count diagnostics)
                          :fileKinds (count (set (keep :kind files)))}
                 :indexedConnectivity indexed-connectivity
                 :topFileKinds (vec (take 8 (count-rows :kind :kind files)))
                 :extractors (vec (take 12 (context-extractor-rows files)))
                 :extractorFingerprints (vec (take 12 (context-extractor-fingerprint-rows
                                                       files)))
                 :diagnostics {:byStage (vec (take 8 (count-rows :stage
                                                                 :stage
                                                                 diagnostics)))
                               :byExtractor (vec (take 8
                                                       (context-diagnostic-extractor-rows
                                                        files
                                                        diagnostics)))
                               :samples (context-diagnostic-samples files
                                                                    diagnostics)}}
        actions (context-next-actions skipped-files diagnostics indexed-connectivity)]
    (cond-> summary
      (seq actions) (assoc :nextActions actions))))

(defn- indexed-extractor-fingerprint-summary
  [xtdb project-id]
  (if-not xtdb
    []
    (->> (store/rows-by-field xtdb
                              (:files store/tables)
                              :project-id
                              project-id)
         (filter active-index-row?)
         (group-by (juxt :kind extractor-fingerprint-value))
         (map (fn [[[kind fingerprint] files]]
                {:kind (display-value kind)
                 :extractor-version (get extract/extractor-versions kind "none/v1")
                 :extractor-fingerprint fingerprint
                 :files (count files)}))
         (sort-by (juxt :kind :extractor-version :extractor-fingerprint))
         vec)))

(defn- active-project-rows
  [xtdb table project-id]
  (->> (store/rows-by-field xtdb table :project-id project-id)
       (filter active-index-row?)
       vec))

(defn- indexed-connectivity-summary
  [xtdb project-id]
  (when xtdb
    (let [files (active-project-rows xtdb (:files store/tables) project-id)
          nodes (active-project-rows xtdb (:nodes store/tables) project-id)
          edges (active-project-rows xtdb (:edges store/tables) project-id)]
      (indexed-connectivity-from-rows files nodes edges))))

(defn- diagnostics-summary
  [xtdb project-id]
  (if-not xtdb
    {:total 0
     :by-stage []
     :by-extractor []
     :samples []}
    (let [files-by-id (->> (store/rows-by-field xtdb
                                                (:files store/tables)
                                                :project-id
                                                project-id)
                           active-rows
                           (map (juxt :xt/id identity))
                           (into {}))
          diagnostics (->> (store/rows-by-field xtdb
                                                (:diagnostics store/tables)
                                                :project-id
                                                project-id)
                           active-rows
                           vec)
          diagnostic-extractor (fn [diagnostic]
                                 (let [file (get files-by-id (:file-id diagnostic))
                                       kind (or (:kind file) :unknown)]
                                   {:kind (display-value kind)
                                    :extractor-version (if (= :unknown kind)
                                                         "unknown"
                                                         (get extract/extractor-versions
                                                              kind
                                                              "none/v1"))
                                    :stage (display-value (:stage diagnostic))}))]
      {:total (count diagnostics)
       :by-stage (count-rows :stage :stage diagnostics)
       :by-extractor (->> diagnostics
                          (map diagnostic-extractor)
                          (group-by (juxt :kind :extractor-version :stage))
                          (map (fn [[[kind version stage] rows]]
                                 {:kind kind
                                  :extractor-version version
                                  :stage stage
                                  :count (count rows)}))
                          (sort-by (fn [row]
                                     [(- (long (:count row)))
                                      (:kind row)
                                      (:stage row)]))
                          vec)
       :samples (->> diagnostics
                     (sort-by (juxt :path :source-line :stage))
                     (take 20)
                     (mapv #(select-keys % [:path
                                            :stage
                                            :message
                                            :source-line
                                            :file-id])))})))

(defn project-coverage
  "Return source type coverage for project repos.

  When `xtdb` is provided, indexed diagnostics are joined with active file rows
  to summarize parser/extractor failures by source kind and extractor version."
  ([project] (project-coverage nil project {}))
  ([xtdb {:keys [id repos path] :as _project} {:keys [config-path]}]
   (let [repos (mapv repo-coverage repos)
         report {:schema schema
                 :project-id id
                 :totals (project-totals repos)
                 :files-by-kind (merge-counts repos :files-by-kind :kind)
                 :files-by-extension (merge-counts repos :files-by-extension :ext)
                 :skipped-by-extension (merge-counts-with-samples repos
                                                                  :skipped-by-extension
                                                                  :ext)
                 :skipped-by-reason (merge-counts-with-samples repos
                                                               :skipped-by-reason
                                                               :reason)
                 :extractors (merge-extractors repos)
                 :extractor-fingerprints (indexed-extractor-fingerprint-summary xtdb id)
                 :indexedConnectivity (indexed-connectivity-summary xtdb id)
                 :diagnostics (diagnostics-summary xtdb id)
                 :repos repos}
         actions (coverage-next-actions report (or config-path path))]
     (cond-> report
       (seq actions) (assoc :nextActions actions)))))
