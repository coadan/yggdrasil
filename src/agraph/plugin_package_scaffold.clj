(ns agraph.plugin-package-scaffold
  "Local scaffold generation for git-shareable plugin packages."
  (:require [agraph.fs :as fs]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn- write-edn-file!
  [path data]
  (with-open [writer (io/writer path)]
    (binding [*out* writer
              *print-namespace-maps* false]
      (pprint/pprint data)))
  path)

(defn- write-file!
  [path content]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file content)
    (fs/canonical-path file)))

(defn- slug
  [value]
  (-> (str value)
      str/lower-case
      (str/replace #"[^a-z0-9._-]+" "-")
      (str/replace #"(^[-._]+|[-._]+$)" "")
      not-empty
      (or "agraph-plugin")))

(defn- default-package-id
  [dir]
  (slug (.getName (io/file dir))))

(defn- scaffold-file-kind
  [file-kind]
  (keyword (slug (or file-kind "code"))))

(defn- split-globs
  [value]
  (cond
    (nil? value) []
    (string? value) (str/split value #",")
    (sequential? value) (mapcat split-globs value)
    :else [(str value)]))

(defn- scaffold-globs
  [value default-globs]
  (let [globs (->> (split-globs value)
                   (map str/trim)
                   (remove str/blank?)
                   vec)]
    (if (seq globs)
      globs
      default-globs)))

(defn- default-fixture-path
  [file-kind]
  (if (= :code file-kind)
    "fixtures/sample.clj"
    (str "fixtures/sample." (name file-kind))))

(defn- fixture-template
  [file-kind]
  (if (= :code file-kind)
    "(ns sample)\n(defn value [] 1)\n"
    (str "# Sample " (name file-kind) " fixture.\n"
         "# Replace this with small project-agnostic input that exercises the extractor.\n")))

(defn- extractor-template
  []
  (str "#!/usr/bin/env python3\n"
       "import json\n"
       "import sys\n\n"
       "packet = json.load(sys.stdin)\n"
       "path = packet[\"file\"][\"path\"]\n"
       "content = packet[\"file\"].get(\"content\") or \"\"\n\n"
       "json.dump({\n"
       "    \"schema\": \"agraph.extractor-plugin.result/v1\",\n"
       "    \"nodes\": [],\n"
       "    \"edges\": [],\n"
       "    \"fileFacts\": [{\n"
       "        \"kind\": \"plugin-observation\",\n"
       "        \"label\": f\"plugin saw {path}\",\n"
       "        \"normalizedValue\": path,\n"
       "        \"sourceLine\": 1,\n"
       "        \"confidence\": 0.5,\n"
       "    }] if content else [],\n"
       "    \"chunks\": [{\n"
       "        \"kind\": \"plugin-summary\",\n"
       "        \"label\": f\"summary for {path}\",\n"
       "        \"text\": content[:500],\n"
       "        \"sourceLine\": 1,\n"
       "    }] if content else [],\n"
       "    \"diagnostics\": [],\n"
       "}, sys.stdout)\n"))

(defn- report-template
  []
  (str "#!/usr/bin/env python3\n"
       "import json\n"
       "import sys\n\n"
       "packet = json.load(sys.stdin)\n"
       "project = packet[\"project\"][\"id\"]\n\n"
       "json.dump({\n"
       "    \"schema\": \"agraph.report-plugin.result/v1\",\n"
       "    \"panels\": [{\n"
       "        \"id\": \"plugin-summary\",\n"
       "        \"label\": \"Plugin Summary\",\n"
       "        \"slot\": \"plugins\",\n"
       "        \"order\": 100,\n"
       "        \"mdx\": \"## Plugin Summary\\n\\n<MetricGrid dataKey=\\\"metrics\\\" />\",\n"
       "        \"data\": {\"metrics\": [{\"label\": \"Project\", \"value\": project}]},\n"
       "    }],\n"
       "    \"diagnostics\": [],\n"
       "    \"artifacts\": [],\n"
       "}, sys.stdout)\n"))

(defn- package-readme
  [package-id]
  (str "# " package-id "\n\n"
       "AGraph plugin package scaffold.\n\n"
       "Useful commands:\n\n"
       "```sh\n"
       "bb plugin validate .\n"
       "bb plugin diagnose .\n"
       "bb plugin input extractor . /path/to/repo src/example.clj --json\n"
       "bb plugin input report . --json\n"
       "bb plugin dry-run extractor . /path/to/repo src/example.clj --json\n"
       "bb plugin dry-run report . --json\n"
       "bb plugin install /path/to/project.edn . --force\n"
       "bb plugin registry validate registry.example.edn --json\n"
       "```\n\n"
       "Unsupported file families:\n\n"
       "- edit `:extractor-plugins[0] :applies-to :file-kinds` to name the "
       "incoming file kind this package handles;\n"
       "- edit `:extractor-plugins[0] :scan` when the package should discover "
       "files core does not index yet;\n"
       "- replace `fixtures/sample.clj` with representative project-agnostic "
       "fixtures for that file family;\n"
       "- keep output concrete and auditable: file facts, nodes, edges, chunks, "
       "and diagnostics should cite source paths and lines where possible.\n\n"
       "`registry.example.edn` is a sharing template. It will not pass public "
       "registry validation until this package is reviewed as base/public and "
       "declares a real git source.\n\n"
       "Keep project-specific experiments in plugins. Promote to core only with "
       "project-agnostic behavior, fixtures, and package-local benchmark artifacts.\n"))

(defn- registry-example
  [package-id registry-schema]
  (str ";; Copy this entry shape into a public registry after the package is\n"
       ";; base/public, non-commercial, FOSS-licensed, and git-hosted.\n"
       "{:schema " (pr-str registry-schema) "\n"
       " :id \"local-plugin-registry\"\n"
       " :packages [{:id " (pr-str package-id) "\n"
       "             :path \".\"\n"
       "             :source \"https://github.com/ORG/" package-id ".git\"\n"
       "             :ref \"v0.1.0\"}]}\n"))

(defn- benchmark-readme
  [package-id]
  (str "# Benchmarks for " package-id "\n\n"
       "Keep replayable benchmark artifacts here before making public claims or "
       "requesting core promotion.\n\n"
       "Expected manifest shape:\n\n"
       "```clojure\n"
       ":benchmark\n"
       "{:status :benchmarked\n"
       " :artifacts [{:path \"benchmarks/" package-id "-agent-report.json\"\n"
       "              :kind :agent-report}]}\n"
       "```\n\n"
       "Benchmark artifacts should show material improvement on project-agnostic "
       "cases and include architecture-understanding cases when that is the "
       "claimed benefit.\n"))

(defn- manifest
  [package-id {:keys [manifest-schema name extractor? report? file-kind path-globs scan-globs]}]
  (let [file-kind (scaffold-file-kind file-kind)
        path-globs (scaffold-globs path-globs ["src/*" "src/**/*"])
        scan-globs (scaffold-globs scan-globs ["fixtures/**/*"])]
    (cond-> {:schema manifest-schema
             :id package-id
             :name (str (or name package-id))
             :version "0.1.0"
             :license {:spdx "MIT"}
             :distribution {:visibility :private
                            :commercial? false}
             :scope {:kind :project-local
                     :reason "Scaffolded packages start project-local until reviewed for base reuse."}
             :benchmark {:status :unbenchmarked}}
      extractor?
      (assoc :extractor-plugins
             [{:id (str package-id "-extractor")
               :command ["python3" "extract.py"]
               :modes [:enhance :scan]
               :applies-to {:file-kinds [file-kind]
                            :path-globs path-globs}
               :scan {:path-globs scan-globs
                      :file-kind file-kind}
               :search {:chunks? true}
               :emits [:plugin-observation]}])

      report?
      (assoc :report-plugins
             [{:id (str package-id "-report")
               :command ["python3" "report.py"]
               :slots [:plugins]}]))))

(defn new!
  "Create a local plugin package scaffold."
  [dir {:keys [id extractor? report? force? fixture-path] :as opts}
   {:keys [manifest-schema new-schema registry-schema manifest-filename]}]
  (let [package-id (slug (or id (default-package-id dir)))
        target (io/file dir)
        any-kind? (or extractor? report?)
        extractor? (if any-kind? extractor? true)
        report? (if any-kind? report? true)
        file-kind (scaffold-file-kind (:file-kind opts))
        fixture-path (or (some-> fixture-path str not-empty)
                         (default-fixture-path file-kind))
        path-globs (scaffold-globs (:path-globs opts) ["src/*" "src/**/*"])
        scan-globs (scaffold-globs (:scan-globs opts) ["fixtures/**/*"])
        manifest-path (io/file target manifest-filename)]
    (when (and (.exists manifest-path) (not force?))
      (throw (ex-info "Plugin package already exists. Re-run with --force to replace scaffold files."
                      {:path (.getPath manifest-path)})))
    (.mkdirs target)
    (let [files (cond-> [(write-edn-file! manifest-path
                                          (manifest package-id
                                                    (assoc opts
                                                           :manifest-schema manifest-schema
                                                           :extractor? extractor?
                                                           :report? report?)))
                         (write-file! (io/file target "README.md")
                                      (package-readme package-id))
                         (write-file! (io/file target "registry.example.edn")
                                      (registry-example package-id registry-schema))
                         (write-file! (io/file target "benchmarks/README.md")
                                      (benchmark-readme package-id))
                         (write-file! (io/file target fixture-path)
                                      (fixture-template file-kind))]
                  extractor? (conj (write-file! (io/file target "extract.py")
                                                (extractor-template)))
                  report? (conj (write-file! (io/file target "report.py")
                                             (report-template))))]
      {:schema new-schema
       :package-id package-id
       :path (fs/canonical-path target)
       :manifest (fs/canonical-path manifest-path)
       :file-kind file-kind
       :path-globs path-globs
       :scan-globs scan-globs
       :fixture-path fixture-path
       :files files
       :extractor? (boolean extractor?)
       :report? (boolean report?)})))
