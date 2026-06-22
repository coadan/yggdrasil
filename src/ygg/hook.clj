(ns ygg.hook
  "Git hook installation for keeping Yggdrasil refreshed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema
  "ygg.hook/v1")

(def hook-names
  ["post-commit" "post-checkout" "post-merge"])

(def ^:private begin-marker
  "# BEGIN YGG HOOK")

(def ^:private end-marker
  "# END YGG HOOK")

(defn- read-file
  [file]
  (when (.exists file)
    (slurp file)))

(defn- write-file!
  [file content]
  (let [parent (.getParentFile file)
        tmp (io/file (str (.getPath file) ".tmp"))]
    (when parent
      (.mkdirs parent))
    (spit tmp content)
    (try
      (java.nio.file.Files/move
       (.toPath tmp)
       (.toPath file)
       (into-array java.nio.file.CopyOption
                   [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                    java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      (catch java.nio.file.AtomicMoveNotSupportedException _
        (java.nio.file.Files/move
         (.toPath tmp)
         (.toPath file)
         (into-array java.nio.file.CopyOption
                     [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))
    (.setExecutable file true false)
    (.getPath file)))

(defn- shell-quote
  [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))

(defn- absolute-path
  [path]
  (let [file (io/file path)]
    (.getPath (.getAbsoluteFile file))))

(defn- hook-dir
  [repo-root]
  (io/file repo-root ".git" "hooks"))

(defn git-repo?
  "Return true when repo root has a normal `.git` directory."
  [repo-root]
  (.isDirectory (io/file repo-root ".git")))

(defn- hook-file
  [repo-root hook-name]
  (io/file (hook-dir repo-root) hook-name))

(defn- sync-command
  [{:keys [ygg-bin config-path map-path query-index? repo-id]}]
  (str (shell-quote (or ygg-bin "ygg"))
       " sync "
       (shell-quote (absolute-path config-path))
       " --repo "
       (shell-quote repo-id)
       " --check"
       (when map-path
         (str " --map " (shell-quote (absolute-path map-path))))
       (when query-index?
         " --query-index")))

(defn- hook-section
  [{:keys [repo-id] :as opts}]
  (str begin-marker "\n"
       "YGG_HOOK_LOG_DIR=\".dev/ygg/hooks\"\n"
       "mkdir -p \"$YGG_HOOK_LOG_DIR\" 2>/dev/null || true\n"
       "YGG_HOOK_LOG=\"$YGG_HOOK_LOG_DIR/" repo-id "-$(date +%Y%m%d%H%M%S).log\"\n"
       "if [ -z \"${YGG_HOOK_RUNNING:-}\" ]; then\n"
       "  YGG_HOOK_RUNNING=1 " (sync-command opts) " >\"$YGG_HOOK_LOG\" 2>&1 || true\n"
       "fi\n"
       end-marker "\n"))

(defn- replace-marked-section
  [content section]
  (let [content (or content "#!/usr/bin/env sh\n")
        pattern (re-pattern (str "(?s)"
                                 (java.util.regex.Pattern/quote begin-marker)
                                 ".*?"
                                 (java.util.regex.Pattern/quote end-marker)
                                 "\\n*"))]
    (if (re-find pattern content)
      (str/replace content pattern (fn [_] section))
      (str (str/trimr content)
           (when (seq (str/trim content)) "\n\n")
           section))))

(defn- install-hook!
  [repo hook-name opts]
  (let [file (hook-file (:root repo) hook-name)
        content (replace-marked-section
                 (read-file file)
                 (hook-section (assoc opts :repo-id (:id repo))))]
    {:hook hook-name
     :path (write-file! file content)
     :installed true}))

(defn install!
  "Install Yggdrasil refresh blocks into Git hooks for every Git repo in project."
  [project opts]
  (let [repos (mapv (fn [repo]
                      (if (git-repo? (:root repo))
                        (assoc repo
                               :hooks
                               (mapv #(install-hook! repo % opts) hook-names)
                               :status "installed")
                        (assoc repo
                               :hooks []
                               :status "skipped"
                               :reason "not-a-git-repo")))
                    (:repos project))]
    {:schema schema
     :action "install"
     :project-id (:id project)
     :repos (mapv #(select-keys % [:id :root :status :reason :hooks]) repos)}))

(defn- remove-section
  [content]
  (when content
    (let [pattern (re-pattern (str "(?s)\\n*"
                                   (java.util.regex.Pattern/quote begin-marker)
                                   ".*?"
                                   (java.util.regex.Pattern/quote end-marker)
                                   "\\n*"))]
      (str/trimr (str/replace content pattern "\n")))))

(defn- uninstall-hook!
  [repo-root hook-name]
  (let [file (hook-file repo-root hook-name)
        content (read-file file)
        cleaned (remove-section content)
        removed? (boolean (and content (not= content cleaned)))]
    (when removed?
      (if (seq (str/trim cleaned))
        (write-file! file (str cleaned "\n"))
        (.delete file)))
    {:hook hook-name
     :path (.getPath file)
     :removed removed?}))

(defn uninstall!
  "Remove Yggdrasil-owned Git hook blocks for every repo in project."
  [project]
  {:schema schema
   :action "uninstall"
   :project-id (:id project)
   :repos (mapv (fn [repo]
                  {:id (:id repo)
                   :root (:root repo)
                   :hooks (mapv #(uninstall-hook! (:root repo) %) hook-names)})
                (:repos project))})

(defn- installed-hook?
  [repo-root hook-name]
  (boolean (some-> (read-file (hook-file repo-root hook-name))
                   (str/includes? begin-marker))))

(defn status
  "Return installed/missing hook status for every repo."
  [project]
  {:schema schema
   :action "status"
   :project-id (:id project)
   :repos (mapv (fn [repo]
                  {:id (:id repo)
                   :root (:root repo)
                   :git? (git-repo? (:root repo))
                   :hooks (mapv (fn [hook-name]
                                  {:hook hook-name
                                   :path (.getPath (hook-file (:root repo) hook-name))
                                   :installed? (installed-hook? (:root repo) hook-name)})
                                hook-names)})
                (:repos project))})

(defn hookable-repo-roots
  "Return repo roots that can receive normal Git hooks."
  [project]
  (->> (:repos project)
       (filter #(git-repo? (:root %)))
       (mapv :root)))
