(ns agraph.report
  "Human-readable local report bundles for AGraph projects."
  (:require [agraph.context :as context]
            [agraph.coverage :as coverage]
            [agraph.graph :as graph]
            [agraph.map :as graph-map]
            [agraph.project :as project]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema
  "agraph.report/v1")

(def default-output-dir
  "agraph-out")

(def default-detail
  :primary)

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

(defn- write-text!
  [path value]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file value)
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

(defn- report-markdown
  [{:keys [project map-path detail generated-at-ms graph-data systems-data coverage maintenance]}]
  (let [nodes-by-id (into {} (map (juxt :id identity)) (:nodes systems-data))
        hubs (or (get-in maintenance [:graph-health :high-degree-hubs])
                 (get-in maintenance [:scale :top-hubs]))
        visible-connections (->> (:semantic-connections maintenance)
                                 (remove #(= "noise" (:visibility %)))
                                 (take 20))
        queue (concat (:decision-queue maintenance)
                      (:infra-review-queue maintenance))
        config-path (:path project)]
    (str "# AGraph Report\n\n"
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
         "\n\n## File Coverage\n\n"
         (str/join "\n" (coverage-lines coverage))
         "\n\n## System Graph\n\n"
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
        report (report-markdown {:project project
                                 :map-path map-path
                                 :detail detail
                                 :generated-at-ms generated-at-ms
                                 :graph-data graph-data
                                 :systems-data systems-data
                                 :coverage coverage
                                 :maintenance maintenance})
        files {:index (graph/write-html! (io/file out-dir "index.html") systems-data)
               :report (write-text! (io/file out-dir "REPORT.md") report)
               :graph (write-json! (io/file out-dir "graph.json") graph-data)
               :systems (write-json! (io/file out-dir "systems.json") systems-data)
               :context-example (write-json! (io/file out-dir "context-example.json") ctx)}]
    {:schema schema
     :project-id (:id project)
     :detail (name detail)
     :out (absolute-path out-dir)
     :files (update-vals files absolute-path)}))
