(ns agraph.report
  "Human-readable local report bundles for AGraph projects."
  (:require [agraph.context :as context]
            [agraph.coverage :as coverage]
            [agraph.dependency :as dependency]
            [agraph.evidence :as evidence]
            [agraph.graph :as graph]
            [agraph.map :as graph-map]
            [agraph.project :as project]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema
  "agraph.report/v2")

(def default-output-dir
  "agraph-out")

(def default-detail
  :primary)

(def ^:private report-ui-dir
  "resources/agraph/report-ui")

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn- absolute-path
  [file]
  (.getAbsolutePath (io/file file)))

(defn prepare-output-dir!
  "Create or reuse an output directory.

  A regular file at the target is treated as a likely typo. Pass `force?` to
  replace that file with the report directory."
  [out force?]
  (let [dir (io/file out)]
    (cond
      (and (.exists dir) (.isFile dir) (not force?))
      (throw (ex-info "Report output points to an existing file. Pass --force to replace it."
                      {:out (.getPath dir)}))

      (and (.exists dir) (.isFile dir) force?)
      (do
        (when-not (.delete dir)
          (throw (ex-info "Could not replace existing report output file."
                          {:out (.getPath dir)})))
        (.mkdirs dir))

      :else
      (.mkdirs dir))
    (.getPath dir)))

(defn- write-json!
  [path value]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file (str (json/write-json-str value {:indent-str "  "}) "\n"))
    (.getPath file)))

(defn- json-for-script
  [value]
  (-> (json/write-json-str value)
      (str/replace "<" "\\u003c")
      (str/replace ">" "\\u003e")
      (str/replace "&" "\\u0026")
      (str/replace "\u2028" "\\u2028")
      (str/replace "\u2029" "\\u2029")))

(defn- write-text!
  [path value]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file value)
    (.getPath file)))

(defn- copy-file!
  [source target]
  (when-let [parent (.getParentFile target)]
    (.mkdirs parent))
  (java.nio.file.Files/copy (.toPath source)
                            (.toPath target)
                            (into-array java.nio.file.CopyOption
                                        [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
  (.getPath target))

(defn- copy-tree!
  [source-dir target-dir]
  (doseq [source (file-seq source-dir)
          :when (.isFile source)]
    (let [relative (.relativize (.toPath source-dir) (.toPath source))
          target (io/file target-dir (.toString relative))]
      (copy-file! source target))))

(defn- report-ui-source-dir
  []
  (let [source-dir (io/file report-ui-dir)]
    (when (.isDirectory source-dir)
      source-dir)))

(defn- require-report-ui-source-dir!
  []
  (or (report-ui-source-dir)
      (throw (ex-info "Missing compiled report UI assets. Run bb report-ui:build."
                      {:path report-ui-dir}))))

(defn- boot-script
  [boot]
  (str "<script>window.__AGRAPH_BOOT__="
       (json-for-script boot)
       ";</script>"))

(defn- rewrite-asset-prefix
  [html asset-prefix]
  (if asset-prefix
    (str/replace html
                 #"((?:src|href)=\")\./assets/"
                 (fn [[_ attr]]
                   (str attr asset-prefix "assets/")))
    html))

(defn- inject-boot
  [html boot]
  (let [script (boot-script boot)]
    (if (str/includes? html "</head>")
      (str/replace-first html "</head>" (str "    " script "\n  </head>"))
      (str script "\n" html))))

(defn- write-ui-index!
  ([path boot]
   (write-ui-index! path boot nil))
  ([path boot {:keys [asset-prefix]}]
   (let [source (io/file (require-report-ui-source-dir!) "index.html")
         html (-> (slurp source)
                  (rewrite-asset-prefix asset-prefix)
                  (inject-boot boot))]
     (write-text! path html))))

(defn- copy-report-ui!
  [out-dir]
  (copy-tree! (require-report-ui-source-dir!) (io/file out-dir))
  true)

(defn- file-stem
  [file]
  (let [name (.getName file)]
    (if (str/ends-with? name ".html")
      (subs name 0 (- (count name) (count ".html")))
      name)))

(defn write-graph-viewer!
  "Write a graph-mode viewer backed by the shared report UI assets."
  [path data]
  (let [file (io/file path)
        parent (or (.getParentFile file) (io/file "."))
        stem (file-stem file)
        asset-dir-name (str stem ".assets")
        asset-dir (io/file parent asset-dir-name)
        graph-json-file (io/file parent (str stem ".graph.json"))]
    (copy-tree! (require-report-ui-source-dir!) asset-dir)
    (write-json! graph-json-file data)
    (write-ui-index! file
                     {:mode "graph"
                      :graph data}
                     {:asset-prefix (str asset-dir-name "/")})
    (.getPath file)))

(defn- bullets
  [rows render-empty render-row]
  (if (seq rows)
    (str/join "\n" (map render-row rows))
    (str "- " render-empty)))

(defn- fmt-counts
  [counts]
  (->> counts
       (sort-by (comp name key))
       (map (fn [[k v]]
              (str "- " (name k) ": " v)))
       (str/join "\n")))

(defn- repo-line
  [{:keys [id root role]}]
  (str "- " id
       " (" (name role) ")"
       "\n  root: `" root "`"))

(defn- coverage-lines
  [{:keys [totals diagnostics]}]
  [(str "- files: " (:files totals 0))
   (str "- supported: " (:supported totals 0))
   (str "- skipped: " (:skipped totals 0))
   (str "- diagnostics: " (:total diagnostics 0))])

(defn- graph-summary-lines
  [graph-data systems-data]
  [(str "- overview nodes: " (count (:nodes graph-data)))
   (str "- overview edges: " (count (:edges graph-data)))
   (str "- system nodes: " (count (:nodes systems-data)))
   (str "- system edges: " (count (:edges systems-data)))])

(defn- artifact
  [filename]
  {:path filename})

(defn- compact-repo
  [repo]
  (select-keys repo [:id :root :role]))

(defn- graph-counts
  [data]
  (cond-> {:nodes (count (:nodes data))
           :edges (count (:edges data))}
    (contains? data :clusters) (assoc :clusters (count (:clusters data)))))

(defn- queue-summary
  [maintenance]
  {:decisions (count (:decision-queue maintenance))
   :infra-review (count (:infra-review-queue maintenance))})

(defn report-data
  "Return the canonical project report packet for a generated report bundle."
  [{:keys [project map-path detail generated-at-ms graph-data systems-data coverage
           maintenance context-example evidence package-report artifacts]}]
  {:schema schema
   :project {:id (:id project)
             :name (:name project)
             :config-path (:path project)
             :map-path map-path
             :detail (name detail)}
   :basis (cond-> {:generated-at-ms generated-at-ms}
            (get-in maintenance [:graph-basis :hash])
            (assoc :graph-basis-hash (get-in maintenance [:graph-basis :hash])))
   :repos (mapv compact-repo (:repos project))
   :evidence evidence
   :coverage {:totals (:totals coverage)
              :top-file-kinds (vec (take 12 (:files-by-kind coverage)))
              :skipped-by-reason (vec (take 8 (:skipped-by-reason coverage)))
              :diagnostics (select-keys (:diagnostics coverage)
                                        [:total :by-stage :by-extractor])}
   :graphs {:overview (assoc (graph-counts graph-data)
                             :artifact "graph.json")
            :systems (assoc (graph-counts systems-data)
                            :artifact "systems.json")}
   :packages {:counts (:counts package-report)
              :ecosystems (:ecosystems package-report)
              :artifact "report.json"}
   :maintenance {:counts (:counts maintenance)
                 :external-api-review (:external-api-review maintenance)
                 :top-hubs (vec (take 10 (or (get-in maintenance [:graph-health :high-degree-hubs])
                                             (get-in maintenance [:scale :top-hubs]))))
                 :visible-connections (->> (:semantic-connections maintenance)
                                           (remove #(= "noise" (:visibility %)))
                                           (take 20)
                                           vec)
                 :orphaned-candidates (vec (take 20 (:orphaned-systems maintenance)))
                 :queue (queue-summary maintenance)
                 :decision-queue (vec (take 20 (:decision-queue maintenance)))
                 :infra-review-queue (vec (take 20 (:infra-review-queue maintenance)))}
   :context-example {:artifact "context-example.json"
                     :answerability (:answerability context-example)}
   :artifacts artifacts
   :commands [(str "agraph sync " (or (:path project) "<project.edn>") " --check"
                   (when map-path (str " --map " map-path)))
              (str "agraph ask \"where is this handled?\" --project " (:id project) " --json")
              (str "agraph view systems --project " (:id project)
                   (when map-path (str " --map " map-path)))
              (str "agraph packages --project " (:id project) " --json")
              (str "agraph report " (or (:path project) "<project.edn>")
                   (when map-path (str " --map " map-path))
                   " --out " default-output-dir)]})

(defn- hub-line
  [{:keys [label kind repo-id degree salience] :as hub}]
  (str "- " (or label (:id hub))
       " [" (if (keyword? kind) (name kind) kind) "]"
       (when repo-id (str " repo " repo-id))
       " degree " (or degree 0)
       (when salience (str " salience " (format "%.2f" (double salience))))))

(defn- system-label
  [nodes-by-id id]
  (or (get-in nodes-by-id [id :label]) id))

(defn- connection-line
  [nodes-by-id {:keys [source-id target-id source target relation salience confidence visibility]}]
  (let [source-id (or source-id source)
        target-id (or target-id target)]
    (str "- " (system-label nodes-by-id source-id)
         " -> "
         (system-label nodes-by-id target-id)
         " (" (name relation) ")"
         (when visibility (str " " visibility))
         (when salience (str " salience " (format "%.2f" (double salience))))
         (when confidence (str " confidence " (format "%.2f" (double confidence)))))))

(defn- orphan-line
  [{:keys [label kind repo-id path-prefix]}]
  (str "- " label
       " [" (name kind) "]"
       (when repo-id (str " repo " repo-id))
       (when path-prefix (str " path " path-prefix))))

(defn- decision-line
  [{:keys [id kind severity target reason]}]
  (str "- " (name severity) " " (name kind) " " target
       (when reason (str " - " reason))
       "\n  id: `" id "`"))

(defn- infra-line
  [{:keys [reviewId kind artifact question]}]
  (str "- " kind " " artifact
       (when question (str " - " question))
       "\n  id: `" reviewId "`"))

(defn- report-mdx
  [{:keys [project map-path detail generated-at-ms graph-data systems-data coverage maintenance
           evidence]}]
  (let [nodes-by-id (into {} (map (juxt :id identity)) (:nodes systems-data))
        hubs (or (get-in maintenance [:graph-health :high-degree-hubs])
                 (get-in maintenance [:scale :top-hubs]))
        visible-connections (->> (:semantic-connections maintenance)
                                 (remove #(= "noise" (:visibility %)))
                                 (take 20))
        queue (concat (:decision-queue maintenance)
                      (:infra-review-queue maintenance))
        config-path (:path project)]
    (str "import { EvidenceSurface, MetricGrid, GraphSummary, PackageSummary, "
         "MaintenanceQueue, CommandList } from './components'\n\n"
         "# AGraph Report\n\n"
         "<EvidenceSurface data={report.evidence} />\n\n"
         "## Basis\n\n"
         "- project: `" (:id project) "`\n"
         "- config: `" (or config-path "not provided") "`\n"
         "- map: `" (or map-path "none") "`\n"
         "- detail: `" (name detail) "`\n"
         "- generated-at-ms: " generated-at-ms "\n"
         (when-let [hash (get-in maintenance [:graph-basis :hash])]
           (str "- graph-basis: `" hash "`\n"))
         "\n## Repos\n\n"
         (bullets (:repos project) "no repos configured" repo-line)
         "\n\n## Evidence Surface\n\n"
         "- available: "
         (if (seq (:available evidence))
           (str/join ", " (map name (:available evidence)))
           "none")
         "\n"
         "- files: " (get-in evidence [:counts :files] 0) "\n"
         "- nodes: " (get-in evidence [:counts :nodes] 0) "\n"
         "- edges: " (get-in evidence [:counts :edges] 0) "\n"
         "- packages: " (get-in evidence [:counts :packages] 0) "\n"
         "- diagnostics: " (get-in evidence [:counts :diagnostics] 0) "\n"
         "\n\n## File Coverage\n\n"
         "<MetricGrid data={report.coverage} />\n\n"
         (str/join "\n" (coverage-lines coverage))
         "\n\n## System Graph\n\n"
         "<GraphSummary graph={report.graphs.systems} />\n\n"
         (str/join "\n" (graph-summary-lines graph-data systems-data))
         "\n\n## Maintenance Counts\n\n"
         (fmt-counts (:counts maintenance))
         "\n\n## Top Hubs\n\n"
         (bullets (take 10 hubs) "none" hub-line)
         "\n\n## Visible System Connections\n\n"
         (bullets visible-connections "none" #(connection-line nodes-by-id %))
         "\n\n## Orphaned Candidates\n\n"
         (bullets (take 20 (:orphaned-systems maintenance)) "none" orphan-line)
         "\n\n## Maintenance Queue\n\n"
         (if (seq queue)
           (str
            (when (seq (:decision-queue maintenance))
              (str "### Decisions\n\n"
                   (bullets (take 20 (:decision-queue maintenance)) "none" decision-line)
                   "\n\n"))
            (when (seq (:infra-review-queue maintenance))
              (str "### Infra Review\n\n"
                   (bullets (take 20 (:infra-review-queue maintenance)) "none" infra-line))))
           "- none\n")
         "\n## Suggested Commands\n\n"
         "<CommandList commands={report.commands} />\n\n"
         "- `agraph sync " (or config-path "<project.edn>") " --check"
         (when map-path (str " --map " map-path))
         "`\n"
         "- `agraph ask \"where is this handled?\" --project " (:id project) " --json`\n"
         "- `agraph view systems --project " (:id project)
         (when map-path (str " --map " map-path))
         "`\n"
         "- `agraph report " (or config-path "<project.edn>")
         (when map-path (str " --map " map-path))
         " --out " default-output-dir "`\n")))

(defn- context-example
  [xtdb project map-path]
  (context/context-packet xtdb
                          "where is this handled?"
                          {:project-id (:id project)
                           :retriever :lexical
                           :map-path map-path
                           :budget context/default-budget}))

(defn bundle!
  "Write a local report bundle and return the generated file paths."
  [xtdb project {:keys [out map-path detail force? generated-at-ms]
                 :or {out default-output-dir
                      detail default-detail}}]
  (let [detail (keyword detail)
        generated-at-ms (or generated-at-ms (now-ms))
        out-dir (prepare-output-dir! out force?)
        overlay (when map-path (graph-map/read-map map-path))
        graph-data (graph/overview-graph xtdb {:project-id (:id project)})
        systems-data (graph/system-graph xtdb
                                         (:id project)
                                         {:detail detail
                                          :map-overlay overlay})
        coverage (coverage/project-coverage xtdb project {})
        maintenance (project/maintain-project xtdb
                                              project
                                              {:map-overlay overlay})
        ctx (context-example xtdb project map-path)
        package-report (dependency/package-report xtdb
                                                  {:project-id (:id project)}
                                                  {:map-overlay overlay
                                                   :limit 0})
        evidence-summary (evidence/summarize xtdb
                                             project
                                             {:map-overlay overlay
                                              :config-path (:path project)
                                              :map-path map-path})
        artifacts {:index (artifact "index.html")
                   :report (artifact "REPORT.mdx")
                   :report-data (artifact "report.json")
                   :graph (artifact "graph.json")
                   :systems (artifact "systems.json")
                   :context-example (artifact "context-example.json")}
        report-packet (report-data {:project project
                                    :map-path map-path
                                    :detail detail
                                    :generated-at-ms generated-at-ms
                                    :graph-data graph-data
                                    :systems-data systems-data
                                    :coverage coverage
                                    :maintenance maintenance
                                    :context-example ctx
                                    :evidence evidence-summary
                                    :package-report package-report
                                    :artifacts artifacts})
        report (report-mdx {:project project
                            :map-path map-path
                            :detail detail
                            :generated-at-ms generated-at-ms
                            :graph-data graph-data
                            :systems-data systems-data
                            :coverage coverage
                            :maintenance maintenance
                            :evidence evidence-summary})
        data-files {:report (write-text! (io/file out-dir "REPORT.mdx") report)
                    :report-data (write-json! (io/file out-dir "report.json") report-packet)
                    :graph (write-json! (io/file out-dir "graph.json") graph-data)
                    :systems (write-json! (io/file out-dir "systems.json") systems-data)
                    :context-example (write-json! (io/file out-dir "context-example.json") ctx)}
        index-path (do
                     (copy-report-ui! out-dir)
                     (write-ui-index! (io/file out-dir "index.html")
                                      {:mode "report"
                                       :report report-packet
                                       :graph systems-data}))
        files (assoc data-files :index index-path)]
    {:schema schema
     :project-id (:id project)
     :detail (name detail)
     :out (absolute-path out-dir)
     :evidence evidence-summary
     :files (update-vals files absolute-path)}))
