(ns agraph.report
  "Human-readable local report bundles for AGraph projects."
  (:require [agraph.command :as command]
            [agraph.context :as context]
            [agraph.coverage :as coverage]
            [agraph.dependency :as dependency]
            [agraph.evidence :as evidence]
            [agraph.graph :as graph]
            [agraph.map :as graph-map]
            [agraph.project :as project]
            [agraph.report-plugin :as report-plugin]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema
  "agraph.report/v2")

(def default-output-dir
  "agraph-out")

(def default-detail
  :primary)

(def ^:private report-package-diagnostic-limit
  20)

(def ^:private report-ui-dir
  "resources/agraph/report-ui")

(def ^:private empty-plugin-bundle
  {:schema report-plugin/bundle-schema
   :panels []
   :diagnostics []
   :artifacts []})

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
   :infra-review (count (:infra-review-queue maintenance))
   :dependency-review (count (:dependency-review-queue maintenance))})

(defn- compact-declared-package
  [entry]
  (select-keys entry [:id
                      :label
                      :ecosystem
                      :package-name
                      :declared-by
                      :resolved-versions]))

(defn- compact-unresolved-import
  [entry]
  (select-keys entry [:source-id
                      :source-label
                      :target-id
                      :import
                      :path
                      :line
                      :kind]))

(defn- compact-version-conflict
  [entry]
  (select-keys entry [:id
                      :label
                      :ecosystem
                      :package-name
                      :versions]))

(defn- role-counts
  [repos]
  (->> repos
       (group-by #(or (:role %) :repository))
       (map (fn [[role rows]]
              {:role role
               :count (count rows)}))
       (sort-by (comp str :role))
       vec))

(defn- package-command
  [project & args]
  (str "agraph packages --project " (command/shell-token (:id project))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- sync-command
  [project & args]
  (str "agraph sync " (command/shell-token (or (:path project) "<project.edn>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- sync-subcommand
  [project subcommand & args]
  (str "agraph sync " subcommand " " (command/shell-token (or (:path project) "<project.edn>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- atlas-next-actions
  [project package-report maintenance coverage evidence]
  (let [package-counts (:counts package-report)
        maintenance-queue (queue-summary maintenance)
        external-api-counts (get-in maintenance [:external-api-review :counts])
        diagnostics (get-in coverage [:diagnostics :total] 0)
        schema-mismatches (get-in evidence [:counts :result-schema-mismatch-events] 0)]
    (cond-> []
      (pos? (long (get package-counts :unresolved-imports 0)))
      (conj {:kind :dependency-review
             :label "Review unresolved imports"
             :count (get package-counts :unresolved-imports 0)
             :command (package-command project "--json")})

      (pos? (long (get package-counts :version-conflicts 0)))
      (conj {:kind :dependency-review
             :label "Review package version conflicts"
             :count (get package-counts :version-conflicts 0)
             :command (package-command project "--with-conflicts" "--json")})

      (pos? (long (get external-api-counts :source-fanouts 0)))
      (conj {:kind :external-api-review
             :label "Review external API fanouts"
             :count (get external-api-counts :source-fanouts 0)
             :command (sync-command project "--check" "--enqueue")})

      (some pos? (vals maintenance-queue))
      (conj {:kind :maintenance
             :label "Process maintenance work queue"
             :count (reduce + (vals maintenance-queue))
             :command (str "agraph sync work list --project "
                           (command/shell-token (:id project)))})

      (pos? (long diagnostics))
      (conj {:kind :coverage
             :label "Inspect extractor diagnostics"
             :count diagnostics
             :command (sync-subcommand project "coverage" "--json")})

      (pos? (long schema-mismatches))
      (conj (merge {:kind :activity
                    :label "Inspect result schema mismatch activity"
                    :count schema-mismatches
                    :mcpTool "agraph_sync_activity"
                    :command (sync-subcommand project "activity" "--json")}
                   (when (:path project)
                     {:mcpArgs {:configPath (:path project)}}))))))

(defn- atlas-summary
  [{:keys [project graph-data systems-data coverage maintenance evidence package-report]}]
  (let [package-counts (:counts package-report)
        maintenance-counts (:counts maintenance)
        external-api-counts (get-in maintenance [:external-api-review :counts])
        coverage-totals (:totals coverage)]
    {:schema "agraph.report.atlas/v1"
     :project {:repos (count (:repos project))
               :repo-roles (role-counts (:repos project))}
     :evidence {:available (:available evidence)
                :files (or (get-in evidence [:counts :files]) (:files coverage-totals) 0)
                :nodes (get-in evidence [:counts :nodes] 0)
                :edges (get-in evidence [:counts :edges] 0)
                :diagnostics (or (get-in evidence [:counts :diagnostics])
                                 (get-in coverage [:diagnostics :total])
                                 0)
                :extractors (count (:extractors coverage))
                :skipped (or (:skipped coverage-totals)
                             (get-in evidence [:counts :skipped-files])
                             0)}
     :systems {:nodes (count (:nodes systems-data))
               :edges (count (:edges systems-data))
               :overview-nodes (count (:nodes graph-data))
               :overview-edges (count (:edges graph-data))
               :clusters (get maintenance-counts :clusters 0)
               :visible-connections (get maintenance-counts :visible-connections 0)
               :orphaned-systems (get maintenance-counts :orphaned-systems 0)
               :maintenance-decisions (get maintenance-counts :maintenance-decisions 0)}
     :dependencies {:packages (get package-counts :packages 0)
                    :versions (get package-counts :versions 0)
                    :ecosystems (:ecosystems package-report)
                    :imports-package (get package-counts :imports-package 0)
                    :unresolved-imports (get package-counts :unresolved-imports 0)
                    :declared-without-import-evidence (get package-counts
                                                           :declared-without-import-evidence
                                                           0)
                    :version-conflicts (get package-counts :version-conflicts 0)}
     :activity {:items (get-in evidence [:counts :activity-items] 0)
                :events (get-in evidence [:counts :activity-events] 0)
                :validation-events (get-in evidence [:counts :validation-events] 0)
                :result-schema-mismatch-events (get-in evidence
                                                       [:counts
                                                        :result-schema-mismatch-events]
                                                       0)}
     :maintenance {:queue (queue-summary maintenance)
                   :decision-summary (:decision-summary maintenance)
                   :external-api-review external-api-counts}
     :next-actions (atlas-next-actions project package-report maintenance coverage evidence)}))

(defn- maintenance-work-commands
  [project-id map-path maintenance]
  (let [queue (queue-summary maintenance)
        pull (fn [kind]
               (str "agraph sync work pull --project " (command/shell-token project-id)
                    " --kind " (command/shell-token kind)
                    " --agent <agent-id>"))]
    (cond-> []
      (some pos? (vals queue))
      (conj (str "agraph sync work list --project " (command/shell-token project-id))
            "agraph sync work show <work-id>"
            (str "agraph sync work pull --project " (command/shell-token project-id)
                 " --agent <agent-id>"))

      (pos? (long (:decisions queue 0)))
      (conj (pull "maintenance-decision"))

      (pos? (long (:infra-review queue 0)))
      (conj (pull "infra-review"))

      (pos? (long (:dependency-review queue 0)))
      (conj (pull "dependency-review"))

      (some pos? (vals queue))
      (conj "agraph sync work heartbeat <work-id> --agent <agent-id> --lease-minutes 30"
            "agraph sync work complete <work-id> --result result.json"
            (str "agraph sync work apply <work-id> --map "
                 (command/shell-token (or map-path "agraph.map.json")))))))

(defn- suggested-commands
  [project map-path maintenance]
  (let [project-id (:id project)
        config-path (or (:path project) "<project.edn>")]
    (vec
     (concat
      [(str "agraph sync " (command/shell-token config-path) " --check"
            (when map-path (str " --map " (command/shell-token map-path))))
       (str "agraph ask \"where is this handled?\" --project "
            (command/shell-token project-id)
            " --json")
       (str "agraph view systems --project " (command/shell-token project-id)
            (when map-path (str " --map " (command/shell-token map-path))))
       (str "agraph packages --project " (command/shell-token project-id) " --json")
       (str "agraph report " (command/shell-token config-path)
            (when map-path (str " --map " (command/shell-token map-path)))
            " --out " default-output-dir)]
      (maintenance-work-commands project-id map-path maintenance)))))

(defn report-data
  "Return the canonical project report packet for a generated report bundle."
  [{:keys [project map-path detail generated-at-ms graph-data systems-data coverage
           maintenance context-example evidence package-report artifacts report-plugins]}]
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
   :atlas (atlas-summary {:project project
                          :graph-data graph-data
                          :systems-data systems-data
                          :coverage coverage
                          :maintenance maintenance
                          :evidence evidence
                          :package-report package-report})
   :coverage {:totals (:totals coverage)
              :top-file-kinds (vec (take 12 (:files-by-kind coverage)))
              :skipped-by-extension (vec (take 8 (:skipped-by-extension coverage)))
              :skipped-by-reason (vec (take 8 (:skipped-by-reason coverage)))
              :extractors (vec (take 20 (:extractors coverage)))
              :extractor-fingerprints (vec (take 20 (:extractor-fingerprints
                                                     coverage)))
              :diagnostics (select-keys (:diagnostics coverage)
                                        [:total :by-stage :by-extractor])}
   :graphs {:overview (assoc (graph-counts graph-data)
                             :artifact "graph.json")
            :systems (assoc (graph-counts systems-data)
                            :artifact "systems.json")}
   :packages {:counts (:counts package-report)
              :ecosystems (:ecosystems package-report)
              :declared-without-import-evidence (mapv compact-declared-package
                                                      (take report-package-diagnostic-limit
                                                            (:declared-without-import-evidence
                                                             package-report)))
              :unresolved-imports (mapv compact-unresolved-import
                                        (take report-package-diagnostic-limit
                                              (:unresolved-imports package-report)))
              :version-conflicts (mapv compact-version-conflict
                                       (take report-package-diagnostic-limit
                                             (:version-conflicts package-report)))
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
                 :decision-summary (:decision-summary maintenance)
                 :decision-queue (vec (take 20 (:decision-queue maintenance)))
                 :infra-review-queue (vec (take 20 (:infra-review-queue maintenance)))
                 :dependency-review-queue (vec (take 20 (:dependency-review-queue
                                                         maintenance)))}
   :context-example {:artifact "context-example.json"
                     :answerability (:answerability context-example)}
   :plugins (or report-plugins empty-plugin-bundle)
   :artifacts artifacts
   :commands (suggested-commands project map-path maintenance)})

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

(defn- dependency-line
  [{:keys [reviewId question facts]}]
  (let [unresolved (:unresolvedImport facts)]
    (str "- unresolved import " (:import unresolved)
         (when (:path unresolved)
           (str " at " (:path unresolved)
                (when (:line unresolved)
                  (str ":" (:line unresolved)))))
         (when question (str " - " question))
         "\n  id: `" reviewId "`")))

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
                      (:infra-review-queue maintenance)
                      (:dependency-review-queue maintenance))
        commands (suggested-commands project map-path maintenance)
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
         "- activity-events: " (get-in evidence [:counts :activity-events] 0) "\n"
         "- result-schema-mismatch-events: "
         (get-in evidence [:counts :result-schema-mismatch-events] 0) "\n"
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
                   (bullets (take 20 (:infra-review-queue maintenance)) "none" infra-line)
                   "\n\n"))
            (when (seq (:dependency-review-queue maintenance))
              (str "### Dependency Review\n\n"
                   (bullets (take 20 (:dependency-review-queue maintenance))
                            "none"
                            dependency-line))))
           "- none\n")
         "\n## Suggested Commands\n\n"
         "<CommandList commands={report.commands} />\n\n"
         (bullets commands "none" #(str "`" % "`"))
         "\n")))

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
                                                   :limit report-package-diagnostic-limit})
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
                   :context-example (artifact "context-example.json")
                   :plugins (artifact "report-plugins.json")}
        base-report-packet (report-data {:project project
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
                                         :artifacts artifacts
                                         :report-plugins empty-plugin-bundle})
        plugin-bundle (report-plugin/bundle {:project project
                                             :generated-at-ms generated-at-ms
                                             :report base-report-packet
                                             :graph graph-data
                                             :systems systems-data
                                             :coverage coverage
                                             :maintenance maintenance
                                             :evidence evidence-summary
                                             :package-report package-report
                                             :artifacts artifacts
                                             :plugins (:report-plugins project)})
        report-packet (assoc base-report-packet :plugins plugin-bundle)
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
                    :context-example (write-json! (io/file out-dir "context-example.json") ctx)
                    :plugins (write-json! (io/file out-dir "report-plugins.json") plugin-bundle)}
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
