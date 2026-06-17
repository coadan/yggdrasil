(ns agraph.fs
  "Repository file discovery and file metadata."
  (:require [agraph.hash :as hash]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def supported-extensions
  #{".clj" ".cljc" ".cljs" ".css" ".cjs" ".edn" ".go" ".gradle" ".js"
    ".jsx" ".json" ".md" ".mjs" ".py" ".rs" ".scss" ".sh" ".sql" ".toml"
    ".ts" ".tsx" ".yaml" ".yml" ".xml"})

(def supported-filenames
  #{"dockerfile" "docker-compose.yml" "docker-compose.yaml"
    "compose.yml" "compose.yaml" "chart.yaml" "go.mod" "mix.exs" "package.json"
    "cargo.toml" "pyproject.toml" "pom.xml" "deno.json"})

(def ignored-dirs
  #{".git" ".dev" ".cpcache" ".clj-kondo" "target" "node_modules"
    ".shadow-cljs" ".calva" ".idea"})

(def ignored-filenames
  #{"bun.lock" "bun.lockb" "cargo.lock" "gemfile.lock" "package-lock.json"
    "pnpm-lock.yaml" "poetry.lock" "yarn.lock"})

(defn canonical-path
  "Return canonical path string for a file/path."
  [path]
  (.getCanonicalPath (io/file path)))

(defn extension
  "Return lowercase file extension including dot."
  [path]
  (let [name (.getName (io/file path))
        idx (.lastIndexOf name ".")]
    (if (neg? idx)
      ""
      (str/lower-case (subs name idx)))))

(defn file-kind
  "Return AGraph file kind for extension."
  [path]
  (let [filename (str/lower-case (.getName (io/file path)))]
    (cond
      (= "dockerfile" filename) :docker
      (contains? #{"go.mod" "mix.exs" "package.json" "cargo.toml"
                   "pyproject.toml" "pom.xml" "deno.json"}
                 filename) :manifest
      (contains? #{"docker-compose.yml" "docker-compose.yaml"
                   "compose.yml" "compose.yaml"}
                 filename) :compose
      (contains? #{"chart.yaml"} filename) :helm
      (str/starts-with? filename "values.") :helm
      :else
      (case (extension path)
        (".clj" ".cljc" ".cljs") :code
        ".go" :go
        (".js" ".jsx" ".mjs" ".cjs") :javascript
        (".ts" ".tsx") :typescript
        ".py" :python
        ".rs" :rust
        (".css" ".scss") :style
        ".sh" :shell
        ".sql" :sql
        ".edn" :edn
        ".toml" :config
        (".yaml" ".yml") :yaml
        ".json" :config
        ".xml" :config
        ".gradle" :config
        ".md" :doc
        nil))))

(defn ignored-filename?
  "Return true when a file name is intentionally excluded from indexing."
  [path]
  (contains? ignored-filenames
             (str/lower-case (.getName (io/file path)))))

(defn supported-path?
  "Return true when path has a supported extension or exact supported filename."
  [path]
  (let [filename (str/lower-case (.getName (io/file path)))]
    (or (contains? supported-extensions (extension path))
        (contains? supported-filenames filename))))

(defn relative-path
  "Return slash-normalized relative path from root to file."
  [root file]
  (let [root-path (.toPath (io/file (canonical-path root)))
        file-path (.toPath (io/file (canonical-path file)))]
    (str/replace (str (.relativize root-path file-path)) "\\" "/")))

(defn read-file
  "Read file as UTF-8-ish text."
  [file]
  (slurp (io/file file)))

(defn file-record
  "Build file metadata for supported file."
  [root file]
  (let [rel (relative-path root file)
        text (read-file file)
        kind (file-kind file)
        f (io/file file)]
    {:file-id (str "file:" rel)
     :path rel
     :absolute-path (canonical-path file)
     :ext (extension file)
     :kind kind
     :content text
     :content-sha (str "sha256:" (hash/sha256-hex text))
     :mtime-ms (.lastModified f)
     :size-bytes (.length f)
     :active? true}))

(defn- file-record-for-relative-path
  "Build file metadata when the repo-relative path is already known."
  [root rel]
  (let [file (io/file root rel)
        text (read-file file)
        kind (file-kind file)
        f (io/file file)]
    {:file-id (str "file:" rel)
     :path rel
     :absolute-path (.getPath (.getAbsoluteFile file))
     :ext (extension file)
     :kind kind
     :content text
     :content-sha (str "sha256:" (hash/sha256-hex text))
     :mtime-ms (.lastModified f)
     :size-bytes (.length f)
     :active? true}))

(defn- supported-file?
  [file]
  (and (.isFile file)
       (not (ignored-filename? file))
       (supported-path? file)))

(defn- ignored-path?
  [rel-path]
  (let [parts (str/split rel-path #"/")]
    (or (some ignored-dirs parts)
        (some #(str/starts-with? % ".") parts))))

(defn- git-candidate-paths
  [root]
  (let [{:keys [exit out]} (shell/sh "git"
                                     "-C"
                                     (.getPath root)
                                     "ls-files"
                                     "--cached"
                                     "--others"
                                     "--exclude-standard")]
    (when (zero? exit)
      (->> (str/split-lines out)
           (remove str/blank?)
           (remove ignored-path?)
           (filter #(.isFile (io/file root %)))
           vec))))

(defn- filesystem-candidate-paths
  [root]
  (->> (file-seq root)
       (filter #(.isFile %))
       (keep (fn [file]
               (let [rel (relative-path root file)]
                 (when-not (ignored-path? rel)
                   rel))))
       vec))

(defn- candidate-paths
  [root]
  (or (seq (git-candidate-paths root))
      (filesystem-candidate-paths root)))

(defn- file-coverage-row
  [root rel]
  (let [file (io/file root rel)
        ignored? (ignored-filename? file)
        supported? (and (not ignored?)
                        (supported-path? file))]
    (cond-> {:path rel
             :ext (extension rel)
             :filename (str/lower-case (.getName file))
             :supported? supported?
             :size-bytes (.length file)}
      supported? (assoc :kind (file-kind rel))
      ignored? (assoc :skip-reason :ignored-filename)
      (and (not ignored?) (not supported?)) (assoc :skip-reason :unsupported-extension))))

(defn scan-file-coverage
  "Return support coverage rows for non-ignored files under root.

  Rows do not include file contents. Supported rows use the same path support
  rules as `scan-files`; skipped rows explain unsupported or intentionally
  ignored filenames."
  [root]
  (let [root-file (.getCanonicalFile (io/file root))]
    (when-not (.isDirectory root-file)
      (throw (ex-info "Index root is not a directory." {:root root})))
    {:root (.getPath root-file)
     :files (->> (candidate-paths root-file)
                 (mapv #(file-coverage-row root-file %))
                 (sort-by :path)
                 vec)}))

(defn scan-files
  "Return supported files under root with content metadata."
  [root]
  (let [root-file (.getCanonicalFile (io/file root))]
    (when-not (.isDirectory root-file)
      (throw (ex-info "Index root is not a directory." {:root root})))
    (->> (candidate-paths root-file)
         (filter #(supported-file? (io/file root-file %)))
         (map #(file-record-for-relative-path root-file %))
         (sort-by :path)
         vec)))
