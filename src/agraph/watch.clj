(ns agraph.watch
  "Filesystem watching helpers for refreshing AGraph."
  (:require [agraph.fs :as fs]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.nio.file FileSystems Path StandardWatchEventKinds WatchKey WatchService]))

(def schema
  "agraph.watch/v1")

(def default-debounce-ms
  750)

(def watchable-hidden-dirs
  #{".buildkite" ".changeset" ".circleci" ".devcontainer" ".github" ".storybook" ".vitepress" ".vscode"})

(defn watchable-path?
  "Return true when a repo-relative path should trigger AGraph sync."
  [path]
  (let [path (str/replace (str path) "\\" "/")
        path-lower (str/lower-case path)
        parts (remove str/blank? (str/split path #"/"))
        filename (last parts)
        hidden? #(str/starts-with? % ".")
        hidden-parts (filter hidden? parts)
        root-supported-hidden? (and (= 1 (count parts))
                                    (hidden? filename)
                                    (fs/supported-path? filename))
        env-hidden-file? (and (= [filename] (vec hidden-parts))
                              (fs/env-filename? filename))
        supported-hidden? (and (some hidden? parts)
                               (or (re-matches #"^\.devcontainer/devcontainer\.json$" path-lower)
                                   (re-matches #"^\.github/dependabot\.ya?ml$" path-lower)
                                   (re-matches #"^\.github/codeowners$" path-lower)
                                   (re-matches #"^\.github/issue_template/[^/]+\.(?:md|ya?ml|json)$" path-lower)
                                   (re-matches #"^\.github/pull_request_template(?:/[^/]+\.md|\.md)$" path-lower)
                                   (re-matches #"^\.github/funding\.ya?ml$" path-lower)
                                   (re-matches #"^(?:.*/)?\.circleci/config\.ya?ml$" path-lower)
                                   (re-matches #"^(?:.*/)?\.buildkite/pipeline\.ya?ml$" path-lower)
                                   (re-matches #"^(?:.*/)?\.drone\.ya?ml$" path-lower)
                                   (re-matches #"^(?:.*/)?\.woodpecker\.ya?ml$" path-lower)
                                   (re-matches #"^(?:.*/)?\.vscode/(?:settings|tasks|extensions)\.json$" path-lower)
                                   (re-matches #"^(?:.*/)?\.changeset/(?:config\.json|[^/]+\.md)$" path-lower)
                                   (re-matches #"^\.storybook/main\.(?:js|cjs|mjs|ts)$" path-lower)
                                   (re-matches #"^(?:.*/)?\.vitepress/config\.(?:js|mjs|mts|ts)$" path-lower)
                                   (re-matches #"^(?:.*/)?\.vitepress/config/index\.(?:js|mjs|mts|ts)$" path-lower))
                               (fs/supported-path? path))]
    (and (seq filename)
         (not-any? fs/ignored-dirs parts)
         (or root-supported-hidden?
             env-hidden-file?
             supported-hidden?
             (not (some hidden? parts)))
         (not (fs/ignored-filename? filename))
         (fs/supported-path? path))))

(defn coalesce-events
  "Return watchable distinct paths from raw event paths."
  [paths]
  (->> paths
       (map #(str/replace (str %) "\\" "/"))
       (filter watchable-path?)
       distinct
       sort
       vec))

(defn sync-args
  "Return canonical sync args for watch-triggered refresh."
  [{:keys [config-path repo-id map-path query-index?]}]
  (cond-> ["sync" config-path "--repo" repo-id "--check"]
    map-path (into ["--map" map-path])
    query-index? (conj "--query-index")))

(defn refresh!
  "Run one watch-triggered AGraph sync. Returns process data."
  [{:keys [agraph-bin] :as opts}]
  (let [argv (into [(or agraph-bin "agraph")] (sync-args opts))]
    (apply shell/sh argv)))

(defn- relative-path
  [root path]
  (fs/relative-path root (.toFile path)))

(defn- register-dir!
  [^WatchService watcher root dir]
  (.register (.toPath (io/file dir))
             watcher
             (into-array java.nio.file.WatchEvent$Kind
                         [StandardWatchEventKinds/ENTRY_CREATE
                          StandardWatchEventKinds/ENTRY_MODIFY
                          StandardWatchEventKinds/ENTRY_DELETE]))
  {:root root
   :dir (fs/canonical-path dir)})

(defn- watch-dirs
  [repo-root]
  (->> (file-seq (io/file repo-root))
       (filter #(.isDirectory %))
       (remove (fn [dir]
                 (let [rel (fs/relative-path repo-root dir)
                       parts (remove str/blank? (str/split rel #"/"))]
                   (or (some fs/ignored-dirs parts)
                       (some #(and (str/starts-with? % ".")
                                   (not (contains? watchable-hidden-dirs %)))
                             parts)))))
       (mapv fs/canonical-path)))

(defn- register-repo!
  [watcher repo-root]
  (into {}
        (map (fn [dir]
               (let [registered (register-dir! watcher repo-root dir)]
                 [(:dir registered) registered])))
        (watch-dirs repo-root)))

(defn- event-paths
  [registrations ^WatchKey key]
  (let [watchable (.watchable key)
        dir (fs/canonical-path (.toFile ^Path watchable))
        {:keys [root]} (get registrations dir)]
    (mapv (fn [event]
            (relative-path root (.resolve ^Path watchable ^Path (.context event))))
          (.pollEvents key))))

(defn watch!
  "Watch project repos and run sync when supported files change.

  This function is intentionally long-running. Tests should cover
  `watchable-path?`, `coalesce-events`, `sync-args`, and `refresh!` seams rather
  than entering this loop."
  [project opts]
  (let [debounce-ms (long (or (:debounce-ms opts) default-debounce-ms))
        watcher (.newWatchService (FileSystems/getDefault))
        registrations (into {}
                            (mapcat #(register-repo! watcher (:root %)))
                            (:repos project))]
    (println "# AGraph Watch")
    (println "- project" (:id project))
    (println "- repos" (count (:repos project)))
    (println "- directories" (count registrations))
    (loop []
      (when-let [^WatchKey key (.take watcher)]
        (let [paths (atom (event-paths registrations key))]
          (.reset key)
          (Thread/sleep debounce-ms)
          (loop [more (.poll watcher)]
            (when more
              (swap! paths into (event-paths registrations more))
              (.reset ^WatchKey more)
              (recur (.poll watcher))))
          (when-let [changed (seq (coalesce-events @paths))]
            (doseq [repo (:repos project)]
              (refresh! (assoc opts
                               :repo-id (:id repo)
                               :config-path (:path project))))
            (println "- refreshed" (count changed) "paths")))
        (recur)))))
