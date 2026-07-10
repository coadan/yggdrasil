(ns ygg.progress
  "Transport-neutral progress event helpers."
  (:require [ygg.index :as index]))

(defn count-text
  [n singular plural]
  (str (long (or n 0)) " " (if (= 1 (long (or n 0))) singular plural)))

(defn reused-files
  [event]
  (or (:files-reused event) (:files-skipped event)))

(defn sync-progress-message
  "Render a sync progress event as a transport-neutral human message.

  CLI adapters can add bullets or other presentation, while MCP/server adapters
  can use this wording with structured event fields."
  [{:keys [phase repo-id index-profile files-scanned files-changed
           files-deleted files-extracted files-indexed dependency-edges
           chunks search-docs diagnostics total-ms path] :as event}]
  (case phase
    :repo-start
    (str repo-id " start profile=" (name (or index-profile index/default-index-profile)))

    :scan-complete
    (str repo-id " scanned " (count-text files-scanned "file" "files"))

    :plan-complete
    (str repo-id " plan "
         (count-text files-changed "changed file" "changed files")
         ", "
         (count-text (reused-files event) "reused unchanged file" "reused unchanged files")
         ", "
         (count-text files-deleted "deleted file" "deleted files"))

    :extract-start
    (str repo-id " extracting " (count-text files-changed "changed file" "changed files"))

    :extract-progress
    (str repo-id " extracted " files-extracted "/" files-changed
         (when path
           (str " " path)))

    :extract-complete
    (str repo-id " extracted " (count-text files-extracted "file" "files"))

    :commit-start
    (str repo-id " committing " (count-text files-indexed "file" "files"))

    :commit-complete
    (str repo-id " committed " (count-text files-indexed "file" "files")
         ", " (count-text chunks "chunk" "chunks")
         ", " (count-text search-docs "search doc" "search docs")
         ", " (count-text diagnostics "diagnostic" "diagnostics"))

    :delete-complete
    (str repo-id " deleted " (count-text files-deleted "stale file" "stale files"))

    :dependency-start
    (str repo-id " deriving dependency edges")

    :dependency-complete
    (str repo-id " derived " (count-text dependency-edges "dependency edge" "dependency edges"))

    :dry-run-complete
    (str repo-id " dry-run complete " (count-text files-scanned "file" "files")
         (when total-ms
           (str ", " total-ms "ms")))

    :repo-complete
    (str repo-id " complete "
         (count-text files-scanned "scanned file" "scanned files")
         ", "
         (count-text files-indexed "indexed file" "indexed files")
         ", "
         (count-text (reused-files event) "reused unchanged file" "reused unchanged files")
         (when total-ms
           (str ", " total-ms "ms")))

    nil))

(defn sync-progress-line
  "Render a sync progress event as the current human CLI line."
  [event]
  (when-let [message (sync-progress-message event)]
    (str "- " message)))

(defn- query-scope-text
  [{:keys [project-id repo-id]}]
  (str (or project-id "project")
       (when repo-id
         (str "/" repo-id))))

(defn query-progress-message
  "Render a query or context progress event as a transport-neutral message."
  [{:keys [phase cache-status search-docs elapsed-ms result-count entity-count] :as event}]
  (let [scope (query-scope-text event)]
    (case phase
      :context-start
      (str scope " preparing context")

      :search-corpus-load-start
      (str scope " loading "
           (if (= :bypass cache-status) "temporal " "")
           "search corpus"
           (when (= :miss cache-status) " (cold cache)"))

      :search-corpus-load-complete
      (str scope " loaded " (count-text search-docs "search doc" "search docs")
           (when elapsed-ms (str " in " elapsed-ms "ms")))

      :fts-index-start
      (str scope " checking SQLite search index")

      :semantic-search-start
      (str scope " running semantic retrieval")

      :search-complete
      (str scope " search complete " (count-text result-count "result" "results")
           (when elapsed-ms (str " in " elapsed-ms "ms")))

      :context-graph-complete
      (str scope " graph context ready " (count-text entity-count "entity" "entities"))

      :context-complete
      (str scope " context ready"
           (when elapsed-ms (str " in " elapsed-ms "ms")))

      nil)))

(defn query-progress-line
  [event]
  (when-let [message (query-progress-message event)]
    (str "- " message)))
