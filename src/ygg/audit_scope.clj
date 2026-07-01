(ns ygg.audit-scope
  "Mechanical audit-scope summaries for selected graph evidence."
  (:require [ygg.command :as command]
            [ygg.coverage :as coverage]
            [ygg.xtdb :as store]
            [clojure.set :as set]
            [clojure.string :as str]))

(def schema
  "ygg.audit-scopes/v1")

(def report-schema
  "ygg.audit-scopes.report/v1")

(def ^:private sample-limit
  3)

(def ^:private evidence-type-limit
  5)

(def ^:private registry-diagnostic-limit
  10)

(def ^:private scope-order
  {"source-structure" 0
   "corrections" 1
   "dependencies" 2
   "dependency-runtime" 3
   "runtime-config" 4
   "containers" 5
   "infra" 6
   "docs" 7
   "assets" 8
   "unknown-text" 9
   "unclassified-extractor" 99})

(def ^:private report-scope-order
  {"source" 0
   "docs" 1
   "dependencies" 2
   "dependency-runtime" 3
   "runtime-config" 4
   "containers" 5
   "infra" 6
   "assets" 7
   "unknown-text" 8
   "source-structure" 8
   "unclassified-extractor" 99})

(def ^:private dependency-relations
  {"declared-without-import-evidence" #{"dependencies" "dependency-runtime"}
   "imports-package" #{"dependencies" "dependency-runtime"}
   "requires" #{"dependencies" "dependency-runtime"}
   "version-of" #{"dependencies" "dependency-runtime"}
   "resolves" #{"dependencies" "dependency-runtime"}
   "unresolved-import" #{"dependencies" "dependency-runtime"}
   "version-conflict" #{"dependencies" "dependency-runtime"}})

(def ^:private fact-kind-scopes
  {"container-image" #{"containers"}
   "container-image-producer" #{"containers"}
   "container-image-consumer" #{"containers"}
   "container-port" #{"containers" "runtime-config"}
   "devcontainer-command" #{"runtime-config"}
   "devcontainer-feature" #{"runtime-config"}
   "devcontainer-port" #{"runtime-config"}
   "devcontainer-service" #{"runtime-config"}
   "docker-build-arg" #{"containers" "runtime-config"}
   "docker-copy-source" #{"containers"}
   "docker-label" #{"containers" "runtime-config"}
   "docker-stage" #{"containers"}
   "docker-workdir" #{"containers"}
   "env-var" #{"dependency-runtime" "runtime-config"}
   "route" #{"dependency-runtime" "runtime-config"}
   "runtime-command" #{"dependency-runtime" "runtime-config"}
   "sql-security" #{"dependency-runtime" "runtime-config"}
   "terraform-data-source" #{"infra"}
   "terraform-module" #{"infra"}
   "terraform-module-source" #{"infra"}
   "terraform-output" #{"infra"}
   "terraform-provider" #{"infra"}
   "terraform-provider-alias" #{"infra"}
   "terraform-resource" #{"infra"}
   "terraform-variable" #{"infra"}
   "url" #{"dependency-runtime" "runtime-config"}})

(def ^:private file-kind-scopes
  {"archive-asset" #{"assets"}
   "compiled-artifact" #{"assets"}
   "compose" #{"containers"}
   "devcontainer" #{"runtime-config" "containers"}
   "docker" #{"containers"}
   "env" #{"runtime-config"}
   "font-asset" #{"assets"}
   "helm" #{"infra" "containers"}
   "image-asset" #{"assets"}
   "kustomize" #{"infra" "containers"}
   "media-asset" #{"assets"}
   "opaque-asset" #{"assets"}
   "procfile" #{"runtime-config"}
   "terraform" #{"infra"}
   "unknown" #{"unknown-text"}})

(def ^:private source-file-kinds
  #{"astro"
    "code"
    "cpp"
    "dart"
    "dotnet"
    "elixir"
    "erlang"
    "go"
    "groovy"
    "haskell"
    "html"
    "java"
    "javascript"
    "julia"
    "kotlin"
    "lua"
    "objective-c"
    "ocaml"
    "odin"
    "perl"
    "php"
    "python"
    "r"
    "ruby"
    "rust"
    "scala"
    "svelte"
    "swift"
    "typescript"
    "vue"
    "zig"})

(def ^:private docs-file-kinds
  #{"codeowners"
    "doc"
    "docs-config"
    "gettext"
    "governance"
    "notebook"
    "text"})

(def ^:private dependency-file-kinds
  #{"dependency-lock"
    "manifest"
    "sbom"})

(def ^:private runtime-file-kinds
  #{"apple-config"
    "config"
    "db-config"
    "db-migration"
    "devcontainer"
    "editor-config"
    "edn"
    "env"
    "json-schema"
    "ops-config"
    "pre-commit-config"
    "procfile"
    "release-config"
    "task-runner"
    "test-config"
    "tool-config"
    "tool-version-config"
    "web-framework"
    "yaml"})

(def ^:private infra-file-kinds
  #{"asyncapi"
    "avro"
    "build"
    "ci"
    "codegen-config"
    "data-science"
    "dbt"
    "graphql"
    "helm"
    "kustomize"
    "observability-config"
    "openapi"
    "prisma"
    "protobuf"
    "quality-config"
    "sql"
    "starlark"
    "storybook"
    "style"
    "terraform"
    "workflow-orchestration"
    "xml"})

(def ^:private report-file-kind-scopes
  (merge (zipmap source-file-kinds (repeat #{"source"}))
         (zipmap docs-file-kinds (repeat #{"docs"}))
         (zipmap dependency-file-kinds (repeat #{"dependencies"}))
         (zipmap runtime-file-kinds (repeat #{"runtime-config"}))
         (zipmap infra-file-kinds (repeat #{"infra"}))
         file-kind-scopes))

(defn- display-name
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(defn- normalize-key
  [value]
  (some-> value display-name str/trim not-empty))

(defn- scopes-for-keys
  [registry values]
  (->> values
       (keep normalize-key)
       (mapcat #(get registry %))
       set))

(defn- row-file-kind
  [row]
  (or (:fileKind row)
      (:file-kind row)))

(defn- selected-path
  [row]
  (or (:path row)
      (get-in row [:source :path])))

(defn- row-id
  [row]
  (or (:id row)
      (:xt/id row)))

(defn- row-source-line
  [row]
  (or (:sourceLine row)
      (:source-line row)
      (:line row)))

(defn- sample-row
  [row]
  (cond-> (select-keys row [:kind
                            :relation
                            :path
                            :target
                            :source
                            :score
                            :repo-id
                            :repo
                            :ext
                            :status
                            :provenance
                            :match
                            :reason
                            :skipReason
                            :skip-reason
                            :count])
    (row-id row) (assoc :id (row-id row))
    (row-source-line row) (assoc :sourceLine (row-source-line row))
    (row-file-kind row) (assoc :fileKind (display-name (row-file-kind row)))))

(defn- audit-row
  [scope section row evidence-type]
  {:scope scope
   :section section
   :evidenceType evidence-type
   :path (selected-path row)
   :row row})

(defn- runtime-audit-rows
  [row]
  (let [scopes (set/union (scopes-for-keys fact-kind-scopes [(:kind row)])
                          (scopes-for-keys file-kind-scopes [(row-file-kind row)]))
        evidence-type (or (normalize-key (:kind row))
                          (normalize-key (row-file-kind row))
                          "runtime-evidence")]
    (mapv #(audit-row % "runtimeEvidence" row evidence-type) scopes)))

(defn- dependency-audit-rows
  [row]
  (let [relation (normalize-key (:relation row))
        scopes (get dependency-relations relation)]
    (mapv #(audit-row % "dependencyEvidence" row (or relation "dependency-edge"))
          scopes)))

(defn- source-audit-row
  [section row]
  (audit-row "source-structure"
             section
             row
             (or (normalize-key (:relation row))
                 (normalize-key (:kind row))
                 (normalize-key (row-file-kind row))
                 "source-evidence")))

(defn- correction-audit-rows
  [section row]
  (when (#{"correction-edge" "correction-reject"} (normalize-key (:kind row)))
    [(audit-row "corrections"
                section
                row
                (or (normalize-key (:relation row))
                    (normalize-key (:kind row))
                    "correction"))]))

(defn- doc-audit-row
  [row]
  (audit-row "docs" "docs" row (or (normalize-key (:role row))
                                   (normalize-key (:status row))
                                   "doc")))

(defn- evidence-type-counts
  [rows]
  (->> rows
       (map :evidenceType)
       frequencies
       (map (fn [[evidence-type n]]
              {:kind evidence-type
               :count n}))
       (sort-by (juxt (comp - long :count) :kind))
       (take evidence-type-limit)
       vec))

(defn- summarize-scope
  [[scope rows]]
  (let [paths (->> rows
                   (keep :path)
                   set)
        evidence-types (evidence-type-counts rows)
        samples (->> rows
                     (map (fn [{:keys [section row]}]
                            (assoc (sample-row row) :section section)))
                     (take sample-limit)
                     vec)]
    (cond-> {:kind scope
             :basis "selected-architecture-evidence"
             :facts (count rows)}
      (seq paths) (assoc :files (count paths))
      (seq evidence-types) (assoc :topEvidenceTypes evidence-types)
      (seq samples) (assoc :samples samples))))

(defn selected-summaries
  "Return audit-scope summaries for the selected architecture evidence rows.

  The grouping is based on extractor fact kinds, file kinds, and relation kinds
  only. It does not infer project meaning from paths, prose, hostnames, or
  directory vocabulary."
  [{:keys [source-evidence boundary-evidence runtime-evidence dependency-evidence
           docs rejected-corrections]}]
  (->> (concat (map #(source-audit-row "sourceEvidence" %) source-evidence)
               (map #(source-audit-row "boundaryEvidence" %) boundary-evidence)
               (mapcat #(correction-audit-rows "boundaryEvidence" %)
                       boundary-evidence)
               (mapcat #(correction-audit-rows "rejectedCorrections" %)
                       rejected-corrections)
               (mapcat runtime-audit-rows runtime-evidence)
               (mapcat dependency-audit-rows dependency-evidence)
               (map doc-audit-row docs))
       (group-by :scope)
       (map summarize-scope)
       (sort-by (fn [row]
                  [(get scope-order (:kind row) 100)
                   (:kind row)]))
       vec))

(defn- report-scopes-for-file-kind
  [file-kind]
  (let [scopes (scopes-for-keys report-file-kind-scopes [file-kind])]
    (if (seq scopes)
      scopes
      #{"unclassified-extractor"})))

(defn- report-row
  [scope section row evidence-type flags]
  (merge (audit-row scope section row evidence-type)
         flags))

(defn- file-report-rows
  [file]
  (let [kind (normalize-key (:kind file))
        row (assoc file :file-kind (:kind file))]
    (mapv #(report-row % "files" row (or kind "file") {:file? true})
          (report-scopes-for-file-kind kind))))

(defn- package-node?
  [node]
  (contains? #{:external-package
               "external-package"
               :external-package-version
               "external-package-version"}
             (:kind node)))

(defn- node-report-rows
  [file-by-id node]
  (let [file-kind (get-in file-by-id [(:file-id node) :kind])
        row (assoc node :file-kind file-kind)
        scopes (if (package-node? node)
                 #{"dependencies"}
                 (report-scopes-for-file-kind file-kind))]
    (mapv #(report-row % "nodes" row (or (normalize-key (:kind node)) "node") {})
          scopes)))

(defn- dependency-relation-scopes
  [relation]
  (get dependency-relations (normalize-key relation)))

(defn- edge-report-rows
  [file-by-id edge]
  (let [file-kind (get-in file-by-id [(:file-id edge) :kind])
        row (assoc edge :file-kind file-kind)
        relation (or (normalize-key (:relation edge)) "edge")
        scopes (or (dependency-relation-scopes (:relation edge))
                   (report-scopes-for-file-kind file-kind))]
    (mapv #(report-row % "edges" row relation {}) scopes)))

(defn- chunk-report-rows
  [file-by-id chunk]
  (let [file-kind (get-in file-by-id [(:file-id chunk) :kind])
        row (assoc chunk :file-kind file-kind)
        evidence-type (or (normalize-key (:kind chunk))
                          (normalize-key file-kind)
                          "chunk")]
    (mapv #(report-row % "chunks" row evidence-type {})
          (report-scopes-for-file-kind file-kind))))

(defn- fact-report-rows
  [section row]
  (let [scopes (set/union (scopes-for-keys fact-kind-scopes [(:kind row)])
                          (scopes-for-keys report-file-kind-scopes
                                           [(row-file-kind row)]))
        scopes (if (seq scopes) scopes #{"unclassified-extractor"})
        evidence-type (or (normalize-key (:kind row))
                          (normalize-key (row-file-kind row))
                          "fact")]
    (mapv #(report-row % section row evidence-type {}) scopes)))

(defn- diagnostic-report-rows
  [file-by-id diagnostic]
  (let [file-kind (get-in file-by-id [(:file-id diagnostic) :kind])
        row (assoc diagnostic
                   :file-kind file-kind
                   :reason (normalize-key (:stage diagnostic)))
        evidence-type (str "diagnostic:" (or (normalize-key (:stage diagnostic))
                                             "unknown"))]
    (mapv #(report-row % "diagnostics" row evidence-type {:diagnostic? true})
          (report-scopes-for-file-kind file-kind))))

(defn- overlay-doc-report-rows
  [doc]
  [(report-row "docs"
               "mapOverlay"
               doc
               (or (normalize-key (:role doc))
                   (normalize-key (:status doc))
                   "map-doc")
               {:overlay? true})])

(defn- coverage-skipped-report-rows
  [coverage-report]
  (->> (:skipped-by-reason coverage-report)
       (mapv (fn [{:keys [reason count samples]}]
               (report-row "unclassified-extractor"
                           "coverage"
                           {:reason reason
                            :count count
                            :samples samples}
                           (str "skipped:" (or reason "unknown"))
                           {:skipped-count (long (or count 0))})))))

(defn- report-evidence-type-counts
  [rows]
  (->> rows
       (group-by :evidenceType)
       (map (fn [[evidence-type grouped]]
              {:kind evidence-type
               :count (reduce + 0 (map #(long (or (:skipped-count %) 1))
                                       grouped))}))
       (sort-by (juxt (comp - long :count) :kind))
       (take evidence-type-limit)
       vec))

(defn- report-file-kind-counts
  [rows]
  (->> rows
       (keep (fn [{:keys [row]}]
               (when-let [file-kind (normalize-key (row-file-kind row))]
                 (when-let [path (selected-path row)]
                   [file-kind path]))))
       distinct
       (map first)
       frequencies
       (map (fn [[file-kind n]]
              {:kind file-kind
               :files n}))
       (sort-by (juxt (comp - long :files) :kind))
       (take evidence-type-limit)
       vec))

(defn- report-sample-rows
  [rows]
  (->> rows
       (mapcat (fn [{:keys [section row]}]
                 (if-let [samples (seq (:samples row))]
                   (map #(assoc % :section section) samples)
                   [(assoc (sample-row row) :section section)])))
       (take sample-limit)
       vec))

(defn- summarize-report-scope
  [[scope rows]]
  (let [supported-files (->> rows
                             (filter :file?)
                             (keep :path)
                             set
                             count)
        skipped-files (reduce + 0 (map #(long (or (:skipped-count %) 0)) rows))
        facts (count (remove #(or (:file? %)
                                  (:diagnostic? %)
                                  (:overlay? %)
                                  (:skipped-count %))
                             rows))
        diagnostics (count (filter :diagnostic? rows))
        overlays (count (filter :overlay? rows))
        evidence-types (report-evidence-type-counts rows)
        file-kinds (report-file-kind-counts rows)
        samples (report-sample-rows rows)]
    (cond-> {:kind scope
             :basis "indexed-graph"
             :supportedFiles supported-files
             :skippedFiles skipped-files
             :facts facts
             :diagnostics diagnostics
             :overlayCount overlays}
      (seq evidence-types) (assoc :topEvidenceTypes evidence-types)
      (seq file-kinds) (assoc :topFileKinds file-kinds)
      (seq samples) (assoc :samples samples))))

(defn- registry-diagnostic-row
  [[[section evidence-type] rows]]
  {:scope "unclassified-extractor"
   :section section
   :evidenceType evidence-type
   :rows (count rows)
   :samples (report-sample-rows rows)})

(defn- registry-diagnostics
  [audit-rows]
  (->> audit-rows
       (filter #(= "unclassified-extractor" (:scope %)))
       (group-by (juxt :section :evidenceType))
       (map registry-diagnostic-row)
       (sort-by (juxt :section :evidenceType))
       (take registry-diagnostic-limit)
       vec))

(defn- sync-subcommand
  [subcommand config-path & args]
  (str "ygg sync " subcommand " " (command/shell-token (or config-path "<project.edn>"))
       (when (seq args)
         (str " " (str/join " " (map command/shell-token args))))))

(defn- package-command
  [project-id]
  (str "ygg packages --project "
       (command/shell-token (or project-id "<project-id>"))
       " --json"))

(defn- scope-next-actions
  [{:keys [project-id config-path]} scope]
  (let [coverage-command (sync-subcommand "coverage" config-path "--json")]
    (cond-> []
      (= "dependencies" (:kind scope))
      (conj {:kind :dependencies
             :label "Inspect package dependency evidence"
             :command (package-command project-id)})

      (pos? (long (:diagnostics scope)))
      (conj {:kind :coverage
             :label "Inspect extractor diagnostics for audit scope"
             :count (:diagnostics scope)
             :command coverage-command})

      (pos? (long (:skippedFiles scope)))
      (conj {:kind :coverage
             :label "Inspect skipped or unsupported files"
             :count (:skippedFiles scope)
             :command coverage-command})

      (= "unclassified-extractor" (:kind scope))
      (conj {:kind :coverage
             :label "Classify or inspect unclassified extractor coverage"
             :command coverage-command}))))

(defn- distinct-actions
  [actions]
  (->> actions
       (reduce (fn [out action]
                 (if (contains? (:seen out) (:command action))
                   out
                   (-> out
                       (update :seen conj (:command action))
                       (update :actions conj action))))
               {:seen #{}
                :actions []})
       :actions))

(defn report-from-rows
  "Return a full-project audit-scope report from indexed rows and coverage data."
  [{:keys [project rows coverage-report correction-overlay config-path repo-id]}]
  (let [file-by-id (into {} (map (juxt :xt/id identity)) (:files rows))
        audit-rows (concat (mapcat file-report-rows (:files rows))
                           (mapcat (partial node-report-rows file-by-id) (:nodes rows))
                           (mapcat (partial edge-report-rows file-by-id) (:edges rows))
                           (mapcat (partial chunk-report-rows file-by-id) (:chunks rows))
                           (mapcat (partial fact-report-rows "fileFacts")
                                   (:file-facts rows))
                           (mapcat (partial fact-report-rows "systemEvidence")
                                   (:system-evidence rows))
                           (mapcat (partial diagnostic-report-rows file-by-id)
                                   (:diagnostics rows))
                           (mapcat overlay-doc-report-rows (:docs correction-overlay))
                           (coverage-skipped-report-rows coverage-report))
        scopes (->> audit-rows
                    (group-by :scope)
                    (map summarize-report-scope)
                    (sort-by (fn [scope]
                               [(get report-scope-order (:kind scope) 100)
                                (:kind scope)]))
                    vec)
        registry-diagnostics (registry-diagnostics audit-rows)
        action-context {:project-id (:id project)
                        :config-path config-path}
        next-actions (->> scopes
                          (mapcat #(scope-next-actions action-context %))
                          distinct-actions
                          (take 8)
                          vec)]
    (cond-> {:schema report-schema
             :project-id (:id project)
             :repo-id repo-id
             :basis "indexed-graph"
             :coverage {:files (get-in coverage-report [:totals :files] 0)
                        :supportedFiles (get-in coverage-report [:totals :supported] 0)
                        :skippedFiles (get-in coverage-report [:totals :skipped] 0)
                        :diagnostics (get-in coverage-report [:diagnostics :total] 0)}
             :scopes scopes}
      (seq registry-diagnostics)
      (assoc :registryDiagnostics registry-diagnostics)

      (seq next-actions) (assoc :nextActions next-actions))))

(defn- read-context
  [opts]
  (store/read-context (merge (:read-context opts)
                             (select-keys opts [:valid-at
                                                :known-at
                                                :snapshot-token
                                                :current-time]))))

(defn- scope-match?
  [{:keys [project-id repo-id]} row]
  (and (or (str/blank? (str project-id)) (= project-id (:project-id row)))
       (or (str/blank? (str repo-id)) (= repo-id (:repo-id row)))))

(defn- active-row?
  [row]
  (not= false (:active? row)))

(defn- scoped-active-rows
  [xtdb table opts]
  (->> (store/constrained-rows xtdb
                               table
                               {:project-id (when-not (str/blank? (str (:project-id opts)))
                                              (:project-id opts))
                                :repo-id (when-not (str/blank? (str (:repo-id opts)))
                                           (:repo-id opts))}
                               (read-context opts))
       (filter active-row?)
       (filter #(scope-match? opts %))
       vec))

(defn- indexed-rows
  [xtdb opts]
  {:files (scoped-active-rows xtdb (:files store/tables) opts)
   :nodes (scoped-active-rows xtdb (:nodes store/tables) opts)
   :edges (scoped-active-rows xtdb (:edges store/tables) opts)
   :chunks (scoped-active-rows xtdb (:chunks store/tables) opts)
   :file-facts (scoped-active-rows xtdb (:file-facts store/tables) opts)
   :system-evidence (scoped-active-rows xtdb (:system-evidence store/tables) opts)
   :diagnostics (scoped-active-rows xtdb (:diagnostics store/tables) opts)})

(defn- correction-overlay
  [{:keys [correction-overlay]}]
  correction-overlay)

(defn report
  "Return a mechanical audit-scope report for the indexed project graph."
  [xtdb project {:keys [repo-id] :as opts}]
  (let [scope (assoc opts :project-id (:id project))
        coverage-report (coverage/project-coverage xtdb project opts)]
    (report-from-rows {:project project
                       :repo-id repo-id
                       :config-path (or (:config-path opts) (:path project))
                       :coverage-report coverage-report
                       :correction-overlay (correction-overlay opts)
                       :rows (indexed-rows xtdb scope)})))
