(ns agraph.fs
  "Repository file discovery and file metadata."
  (:require [agraph.hash :as hash]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def supported-extensions
  #{".clj" ".cljc" ".cljs" ".edn" ".gradle" ".json" ".md" ".rs" ".toml"
    ".yaml" ".yml" ".xml"})

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
        ".rs" :rust
        ".edn" :edn
        ".toml" :config
        (".yaml" ".yml") :yaml
        ".json" :config
        ".xml" :config
        ".gradle" :config
        ".md" :doc
        nil))))

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

(defn- supported-file?
  [file]
  (and (.isFile file)
       (not (contains? ignored-filenames
                       (str/lower-case (.getName file))))
       (or (contains? supported-extensions (extension file))
           (contains? supported-filenames
                      (str/lower-case (.getName file))))))

(defn- ignored-path?
  [rel-path]
  (let [parts (str/split rel-path #"/")]
    (or (some ignored-dirs parts)
        (some #(str/starts-with? % ".") parts))))

(defn- ignored-dir-path?
  [rel-path]
  (let [parts (str/split rel-path #"/")]
    (some ignored-dirs parts)))

(defn- git-indexable-files
  [root]
  (let [{:keys [exit out]} (shell/sh "git"
                                     "-C"
                                     (canonical-path root)
                                     "ls-files"
                                     "--cached"
                                     "--others"
                                     "--exclude-standard")]
    (when (zero? exit)
      (->> (str/split-lines out)
           (remove str/blank?)
           (remove ignored-dir-path?)
           (map #(io/file root %))
           (filter supported-file?)
           vec))))

(defn scan-files
  "Return supported files under root with content metadata."
  [root]
  (let [root-file (io/file root)]
    (when-not (.isDirectory root-file)
      (throw (ex-info "Index root is not a directory." {:root root})))
    (->> (or (seq (git-indexable-files root-file))
             (->> (file-seq root-file)
                  (filter supported-file?)
                  (remove #(ignored-path? (relative-path root-file %)))))
         (map #(file-record root-file %))
         (sort-by :path)
         vec)))
