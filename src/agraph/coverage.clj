(ns agraph.coverage
  "Source type coverage reporting for project repos."
  (:require [agraph.extract :as extract]
            [agraph.fs :as fs]
            [agraph.xtdb :as store]
            [clojure.string :as str]))

(def schema
  "agraph.source-coverage/v1")

(def context-schema
  "agraph.source-coverage.context/v1")

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

(defn- repo-coverage
  [{:keys [id root role]}]
  (let [{:keys [files]} (fs/scan-file-coverage root)
        files (vec files)]
    {:repo-id id
     :role role
     :root root
     :totals (totals files)
     :files-by-kind (count-rows :kind :kind (supported-files files))
     :files-by-extension (count-rows :ext :ext files)
     :skipped-by-extension (count-rows :ext :ext (skipped-files files))
     :skipped-by-reason (count-rows :reason :skip-reason (skipped-files files))
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

(defn context-summary
  "Return compact indexed source coverage for context packets.

  This summarizes the graph rows currently available to the selected
  project/repo/read context. It does not scan the filesystem for skipped or
  unsupported source candidates; use `project-coverage` for full repo coverage."
  [xtdb opts]
  (let [files (scoped-active-index-rows xtdb (:files store/tables) opts)
        diagnostics (scoped-active-index-rows xtdb (:diagnostics store/tables) opts)]
    {:schema context-schema
     :basis "indexed-graph"
     :totals {:indexedFiles (count files)
              :diagnostics (count diagnostics)
              :fileKinds (count (set (keep :kind files)))}
     :topFileKinds (vec (take 8 (count-rows :kind :kind files)))
     :extractors (vec (take 12 (context-extractor-rows files)))
     :extractorFingerprints (vec (take 12 (context-extractor-fingerprint-rows
                                           files)))
     :diagnostics {:byStage (vec (take 8 (count-rows :stage :stage diagnostics)))
                   :byExtractor (vec (take 8 (context-diagnostic-extractor-rows
                                              files
                                              diagnostics)))
                   :samples (context-diagnostic-samples files diagnostics)}}))

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
  ([xtdb {:keys [id repos] :as _project} _opts]
   (let [repos (mapv repo-coverage repos)]
     {:schema schema
      :project-id id
      :totals (project-totals repos)
      :files-by-kind (merge-counts repos :files-by-kind :kind)
      :files-by-extension (merge-counts repos :files-by-extension :ext)
      :skipped-by-extension (merge-counts repos :skipped-by-extension :ext)
      :skipped-by-reason (merge-counts repos :skipped-by-reason :reason)
      :extractors (merge-extractors repos)
      :extractor-fingerprints (indexed-extractor-fingerprint-summary xtdb id)
      :diagnostics (diagnostics-summary xtdb id)
      :repos repos})))
