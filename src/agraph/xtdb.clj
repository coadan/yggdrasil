(ns agraph.xtdb
  "XTDB storage boundary for AGraph."
  (:require [agraph.metadata :as metadata]
            [agraph.schema :as schema]
            [clojure.java.io :as io]
            [xtdb.api :as xt]
            [xtdb.node :as xtn])
  (:import [java.io Closeable]
           [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]
           [java.time Instant OffsetDateTime ZonedDateTime]
           [java.util Date]))

(def default-storage-path ".dev/agraph/xtdb")

(defn storage-path
  "Return configured XTDB storage path."
  []
  (or (System/getenv "AGRAPH_XTDB_PATH")
      default-storage-path))

(defn- ensure-dir!
  [path]
  (let [file (io/file path)]
    (.mkdirs file)
    (.getPath file)))

(defn node-opts
  "Return XTDB local storage node opts."
  [path]
  (let [root (ensure-dir! path)]
    {:log [:local {:path (ensure-dir! (io/file root "log"))}]
     :storage [:local {:path (ensure-dir! (io/file root "storage"))}]}))

(defn- with-storage-lock
  [path f]
  (let [root (ensure-dir! path)
        lock-path (.toPath (io/file root ".agraph.lock"))
        options (into-array OpenOption [StandardOpenOption/CREATE
                                        StandardOpenOption/WRITE])]
    (with-open [channel (FileChannel/open lock-path options)]
      (let [lock (.lock channel)]
        (try
          (f)
          (finally
            (.release lock)))))))

(defn- await-ready!
  [node]
  (xt/status node)
  node)

(defn start-node
  "Start a local XTDB node."
  ([] (start-node (storage-path)))
  ([path]
   {:node (xtn/start-node (node-opts path))
    :path path}))

(defn stop-node!
  "Close a node handle."
  [{:keys [node]}]
  (when (instance? Closeable node)
    (.close ^Closeable node)))

(defn with-node
  "Open XTDB node at path, call f, and close the node."
  [path f]
  (with-storage-lock
    path
    (fn []
      (let [xtdb (start-node path)]
        (try
          (await-ready! (:node xtdb))
          (f xtdb)
          (finally
            (stop-node! xtdb)))))))

(def tables
  {:projects :agraph/projects
   :repos :agraph/repos
   :source-snapshots :agraph/source-snapshots
   :index-runs :agraph/index-runs
   :runs :agraph/runs
   :files :agraph/files
   :nodes :agraph/nodes
   :edges :agraph/edges
   :chunks :agraph/chunks
   :diagnostics :agraph/index-diagnostics
   :search-docs :agraph/search-docs
   :embeddings :agraph/embeddings
   :query-runs :agraph/query-runs
   :metadata-defs :agraph/metadata-defs
   :metadata :agraph/metadata
   :graph-views :agraph/graph-views
   :graph-cursors :agraph/graph-cursors
   :evidence :agraph/evidence
   :system-evidence :agraph/system-evidence
   :system-nodes :agraph/system-nodes
   :system-edges :agraph/system-edges})

(defn instant
  "Return java.util.Date instant value for XTDB temporal options."
  [value]
  (cond
    (nil? value) nil
    (instance? Date value) value
    (instance? Instant value) (Date/from value)
    (instance? ZonedDateTime value) (Date/from (.toInstant ^ZonedDateTime value))
    (instance? OffsetDateTime value) (Date/from (.toInstant ^OffsetDateTime value))
    (string? value) (Date/from (Instant/parse value))
    :else (throw (ex-info "Unsupported instant value."
                          {:value value
                           :class (some-> value class .getName)}))))

(defn read-context
  "Normalize temporal read options.

  `:valid-at` is source/project valid time and maps to XTDB `:current-time`.
  `:snapshot-token` is the exact XTDB read basis when available. `:known-at` is
  retained as caller intent, but XTDB's Clojure query API needs a snapshot token
  for exact system-time reads."
  ([] (read-context {}))
  ([opts]
   (let [{:keys [valid-at known-at snapshot-token current-time] :as opts} (or opts {})]
     (cond-> (select-keys opts [:project-id :repo-id :known-at])
       valid-at (assoc :valid-at (instant valid-at))
       known-at (assoc :known-at (instant known-at))
       snapshot-token (assoc :snapshot-token snapshot-token)
       current-time (assoc :current-time (instant current-time))))))

(defn- query-options
  [ctx]
  (let [ctx (read-context ctx)
        current-time (or (:current-time ctx) (:valid-at ctx))]
    (cond-> {}
      current-time (assoc :current-time current-time)
      (:snapshot-token ctx) (assoc :snapshot-token (:snapshot-token ctx)))))

(defn q
  "Run XTQL query."
  ([xtdb query] (q xtdb query {}))
  ([{:keys [node]} query ctx]
   (xt/q node query (query-options ctx))))

(defn all-rows
  "Return all rows from table."
  ([xtdb table] (all-rows xtdb table {}))
  ([xtdb table ctx]
   (q xtdb (list 'from table '[*]) ctx)))

(defn rows-by-field
  "Return rows from table where field equals value."
  ([xtdb table field value] (rows-by-field xtdb table field value {}))
  ([xtdb table field value ctx]
   (q xtdb
      [(list 'fn ['v] (list 'from table [{field 'v} '*])) value]
      ctx)))

(defn row-by-id
  "Return row by id from table."
  ([xtdb table id] (row-by-id xtdb table id {}))
  ([xtdb table id ctx]
   (first (q xtdb
             [(list 'fn ['doc-id] (list 'from table [{:xt/id 'doc-id} '*])) id]
             ctx))))

(defn- validate-file-row
  [row]
  (schema/assert! schema/file-row row "Invalid file row."))

(defn- validate-project-row
  [row]
  (schema/assert! schema/project-row row "Invalid project row."))

(defn- validate-repo-row
  [row]
  (schema/assert! schema/repo-row row "Invalid repo row."))

(defn- validate-node-row
  [row]
  (schema/assert! schema/node-row row "Invalid node row."))

(defn- validate-edge-row
  [row]
  (schema/assert! schema/edge-row row "Invalid edge row."))

(defn- validate-chunk-row
  [row]
  (schema/assert! schema/chunk-row row "Invalid chunk row."))

(defn- validate-diagnostic-row
  [row]
  (schema/assert! schema/diagnostic-row row "Invalid diagnostic row."))

(defn- validate-search-doc-row
  [row]
  (schema/assert! schema/search-doc-row row "Invalid search doc row."))

(defn- validate-embedding-row
  [row]
  (schema/assert! schema/embedding-row row "Invalid embedding row."))

(defn- validate-system-evidence-row
  [row]
  (schema/assert! schema/system-evidence-row row "Invalid system evidence row."))

(defn- validate-system-node-row
  [row]
  (schema/assert! schema/system-node-row row "Invalid system node row."))

(defn- validate-system-edge-row
  [row]
  (schema/assert! schema/system-edge-row row "Invalid system edge row."))

(defn- validate-metadata-def-row
  [row]
  (schema/assert! schema/metadata-def-row row "Invalid metadata definition row."))

(defn- validate-metadata-row
  [row]
  (schema/assert! schema/metadata-row row "Invalid metadata row."))

(defn- validate-graph-view-row
  [row]
  (schema/assert! schema/graph-view-row row "Invalid graph view row."))

(defn- validate-graph-cursor-row
  [row]
  (schema/assert! schema/graph-cursor-row row "Invalid graph cursor row."))

(defn put-op
  "Return an XTDB put op, optionally at `:valid-from`."
  ([table row] (put-op table row {}))
  ([table row {:keys [valid-from]}]
   (if valid-from
     [:put-docs {:into table
                 :valid-from (instant valid-from)}
      row]
     [:put-docs table row])))

(defn delete-op
  "Return an XTDB delete op, optionally at `:valid-from`."
  ([table id] (delete-op table id {}))
  ([table id {:keys [valid-from]}]
   (if valid-from
     [:delete-docs {:from table
                    :valid-from (instant valid-from)}
      id]
     [:delete-docs table id])))

(defn execute-tx!
  "Execute XTDB transaction ops and return the XTDB transaction key."
  [xtdb ops]
  (when (seq ops)
    (xt/execute-tx (:node xtdb) (vec ops))))

(defn table-ref
  "Return physical XTDB table keyword for logical table key."
  [table]
  (get tables table table))

(defn- row-id
  [row-or-id]
  (if (map? row-or-id)
    (:xt/id row-or-id)
    row-or-id))

(defn temporal-bundle-ops
  "Return XTDB ops for a temporal write bundle.

  Bundle shape:

  {:snapshot {...}
   :run {...}
   :valid-from #inst \"...\"
   :puts {:nodes [...]}
   :deletes {:edges [id-or-row ...]}}"
  [{:keys [snapshot run valid-from puts deletes]}]
  (let [temporal {:valid-from valid-from}]
    (vec
     (concat
      (when snapshot
        [(put-op (table-ref :source-snapshots) snapshot temporal)])
      (when run
        [(put-op (table-ref :index-runs) run temporal)])
      (mapcat (fn [[table rows]]
                (map #(put-op (table-ref table) % temporal) rows))
              puts)
      (mapcat (fn [[table rows-or-ids]]
                (map #(delete-op (table-ref table) (row-id %) temporal) rows-or-ids))
              deletes)))))

(defn commit-temporal-bundle!
  "Commit a temporal write bundle and return the XTDB transaction key."
  [xtdb bundle]
  (execute-tx! xtdb (temporal-bundle-ops bundle)))

(defn metadata-defs
  "Return metadata definitions, including built-in defaults and stored rows."
  ([xtdb] (metadata-defs xtdb {}))
  ([xtdb ctx]
   (->> (concat metadata/default-definitions
                (all-rows xtdb (:metadata-defs tables) ctx))
        (filter #(not= false (:active? %)))
        (map validate-metadata-def-row)
        (map (juxt :key identity))
        (into {}))))

(defn commit-metadata-defs!
  "Persist metadata definition rows."
  ([xtdb rows] (commit-metadata-defs! xtdb rows {}))
  ([xtdb rows {:keys [valid-from]}]
   (let [temporal (cond-> {}
                    valid-from (assoc :valid-from valid-from))
         rows (map validate-metadata-def-row rows)
         ops (mapv #(put-op (:metadata-defs tables) % temporal) rows)]
     (execute-tx! xtdb ops)
     {:metadata-defs (count rows)})))

(defn metadata-for-targets
  "Return metadata rows for target ids visible in read context."
  ([xtdb target-ids] (metadata-for-targets xtdb target-ids {}))
  ([xtdb target-ids ctx]
   (let [ids (set target-ids)
         project-id (:project-id ctx)
         repo-id (:repo-id ctx)]
     (->> (all-rows xtdb (:metadata tables) ctx)
          (filter #(contains? ids (:target-id %)))
          (filter #(or (nil? project-id) (= project-id (:project-id %))))
          (filter #(or (nil? repo-id) (= repo-id (:repo-id %))))
          (filter #(not= false (:active? %)))
          (map validate-metadata-row)
          vec))))

(defn- metadata-cardinality
  [xtdb row ctx]
  (get-in (metadata-defs xtdb ctx) [(:key row) :cardinality] :many))

(defn- matching-metadata-rows
  [xtdb row ctx]
  (->> (rows-by-field xtdb (:metadata tables) :target-id (:target-id row) ctx)
       (filter #(= (:key row) (:key %)))
       (filter #(= (:source row) (:source %)))
       (filter #(or (nil? (:project-id row)) (= (:project-id row) (:project-id %))))
       (filter #(not= false (:active? %)))
       vec))

(defn commit-metadata!
  "Persist metadata rows with metadata definition cardinality."
  ([xtdb rows] (commit-metadata! xtdb rows {}))
  ([xtdb rows {:keys [valid-from] :as opts}]
   (let [temporal (cond-> {}
                    valid-from (assoc :valid-from valid-from))
         read-ctx (cond-> (read-context opts)
                    valid-from (assoc :valid-at valid-from))
         rows (map validate-metadata-row rows)
         ops (vec
              (mapcat (fn [row]
                        (let [delete-ops (when (= :one (metadata-cardinality xtdb row read-ctx))
                                           (map #(delete-op (:metadata tables) (:xt/id %) temporal)
                                                (matching-metadata-rows xtdb row read-ctx)))]
                          (concat delete-ops [(put-op (:metadata tables) row temporal)])))
                      rows))]
     (execute-tx! xtdb ops)
     {:metadata (count rows)})))

(defn delete-metadata!
  "Delete metadata rows for a target/key/source."
  [xtdb {:keys [target-id key source project-id repo-id valid-from] :as opts}]
  (let [key (metadata/parse-key key)
        source (keyword (or source metadata/default-source))
        temporal (cond-> {}
                   valid-from (assoc :valid-from valid-from))
        read-ctx (cond-> (read-context opts)
                   valid-from (assoc :valid-at valid-from))
        rows (->> (rows-by-field xtdb (:metadata tables) :target-id target-id read-ctx)
                  (filter #(= key (:key %)))
                  (filter #(= source (:source %)))
                  (filter #(or (nil? project-id) (= project-id (:project-id %))))
                  (filter #(or (nil? repo-id) (= repo-id (:repo-id %))))
                  vec)
        ops (mapv #(delete-op (:metadata tables) (:xt/id %) temporal) rows)]
    (execute-tx! xtdb ops)
    {:metadata-deleted (count rows)}))

(defn commit-graph-views!
  "Persist graph view rows."
  ([xtdb rows] (commit-graph-views! xtdb rows {}))
  ([xtdb rows {:keys [valid-from]}]
   (let [temporal (cond-> {}
                    valid-from (assoc :valid-from valid-from))
         rows (map validate-graph-view-row rows)
         ops (mapv #(put-op (:graph-views tables) % temporal) rows)]
     (execute-tx! xtdb ops)
     {:graph-views (count rows)})))

(defn graph-views
  "Return graph views visible in read context."
  ([xtdb] (graph-views xtdb {}))
  ([xtdb ctx]
   (let [project-id (:project-id ctx)]
     (->> (all-rows xtdb (:graph-views tables) ctx)
          (filter #(or (nil? project-id)
                       (nil? (:project-id %))
                       (= project-id (:project-id %))))
          (filter #(not= false (:active? %)))
          (map validate-graph-view-row)
          vec))))

(defn graph-view
  "Return one graph view by id."
  ([xtdb id] (graph-view xtdb id {}))
  ([xtdb id ctx]
   (or (row-by-id xtdb (:graph-views tables) id ctx)
       (first (filter #(= id (:label %)) (graph-views xtdb ctx))))))

(defn commit-graph-cursor!
  "Persist one graph cursor revision."
  [xtdb row]
  (let [row (validate-graph-cursor-row row)]
    (execute-tx! xtdb [(put-op (:graph-cursors tables) row)])
    row))

(defn graph-cursor
  "Return graph cursor revision by id."
  [xtdb id]
  (some-> (row-by-id xtdb (:graph-cursors tables) id)
          validate-graph-cursor-row))

(defn graph-cursors
  "Return graph cursor revisions, optionally scoped to project."
  ([xtdb] (graph-cursors xtdb {}))
  ([xtdb {:keys [project-id]}]
   (->> (all-rows xtdb (:graph-cursors tables))
        (filter #(or (nil? project-id) (= project-id (:project-id %))))
        (filter #(not= false (:active? %)))
        (map validate-graph-cursor-row)
        (sort-by (juxt :project-id :created-at-ms :revision))
        vec)))

(defn latest-source-snapshot
  "Return the latest source snapshot row for project, if any."
  [xtdb project-id]
  (->> (rows-by-field xtdb (:source-snapshots tables) :project-id project-id)
       (sort-by :basis-instant)
       last))

(defn file-row
  "Return stored file row by path."
  [xtdb path]
  (first (rows-by-field xtdb (:files tables) :path path)))

(defn scoped-file-row
  "Return stored file row by project, repo, and path."
  [xtdb project-id repo-id path]
  (some #(when (and (= project-id (:project-id %))
                    (= repo-id (:repo-id %))
                    (= path (:path %)))
           %)
        (rows-by-field xtdb (:files tables) :project-id project-id)))

(defn file-scoped-rows
  "Return existing rows owned by file id."
  ([xtdb file-id] (file-scoped-rows xtdb file-id {}))
  ([xtdb file-id ctx]
   {:nodes (rows-by-field xtdb (:nodes tables) :file-id file-id ctx)
    :edges (rows-by-field xtdb (:edges tables) :file-id file-id ctx)
    :chunks (rows-by-field xtdb (:chunks tables) :file-id file-id ctx)
    :diagnostics (rows-by-field xtdb (:diagnostics tables) :file-id file-id ctx)
    :search-docs (rows-by-field xtdb (:search-docs tables) :file-id file-id ctx)}))

(defn file-tx
  "Return replace transaction ops and counts for one file."
  ([xtdb file-row extraction] (file-tx xtdb file-row extraction {:existing? true}))
  ([xtdb file-row extraction {:keys [existing? valid-from] :or {existing? true}}]
   (let [file-id (:xt/id file-row)
         temporal (cond-> {}
                    valid-from (assoc :valid-from valid-from))
         read-ctx (cond-> {}
                    valid-from (assoc :valid-at valid-from))
         existing (if existing?
                    (file-scoped-rows xtdb file-id read-ctx)
                    {:nodes [] :edges [] :chunks [] :diagnostics [] :search-docs []})
         delete-ops (concat
                     (map #(delete-op (:nodes tables) (:xt/id %) temporal) (:nodes existing))
                     (map #(delete-op (:edges tables) (:xt/id %) temporal) (:edges existing))
                     (map #(delete-op (:chunks tables) (:xt/id %) temporal) (:chunks existing))
                     (map #(delete-op (:diagnostics tables) (:xt/id %) temporal) (:diagnostics existing))
                     (map #(delete-op (:search-docs tables) (:xt/id %) temporal) (:search-docs existing)))
         nodes (map validate-node-row (:nodes extraction))
         edges (map validate-edge-row (:edges extraction))
         chunks (map validate-chunk-row (:chunks extraction))
         diagnostics (map validate-diagnostic-row (:diagnostics extraction))
         search-docs (map validate-search-doc-row (:search-docs extraction))
         put-ops (concat
                  [(put-op (:files tables) (validate-file-row file-row) temporal)]
                  (map #(put-op (:nodes tables) % temporal) nodes)
                  (map #(put-op (:edges tables) % temporal) edges)
                  (map #(put-op (:chunks tables) % temporal) chunks)
                  (map #(put-op (:diagnostics tables) % temporal) diagnostics)
                  (map #(put-op (:search-docs tables) % temporal) search-docs))
         tx-ops (vec (concat delete-ops put-ops))]
     {:ops tx-ops
      :counts {:nodes (count nodes)
               :edges (count edges)
               :chunks (count chunks)
               :diagnostics (count diagnostics)
               :search-docs (count search-docs)}})))

(defn- merge-counts
  [a b]
  (merge-with + a b))

(defn commit-file!
  "Replace graph rows for one file."
  [xtdb file-row extraction]
  (let [{:keys [ops counts]} (file-tx xtdb file-row extraction)]
    (xt/execute-tx (:node xtdb) ops)
    counts))

(defn commit-files!
  "Replace graph rows for file entries in bounded transaction batches."
  ([xtdb entries] (commit-files! xtdb entries {:batch-size 50}))
  ([xtdb entries {:keys [batch-size] :or {batch-size 50}}]
   (reduce
    (fn [summary batch]
      (let [txs (map #(file-tx xtdb
                               (:file-row %)
                               (:extraction %)
                               {:existing? (:existing? %)
                                :valid-from (:valid-from %)})
                     batch)
            ops (vec (mapcat :ops txs))
            counts (reduce merge-counts
                           {:nodes 0 :edges 0 :chunks 0 :diagnostics 0 :search-docs 0}
                           (map :counts txs))]
        (when (seq ops)
          (xt/execute-tx (:node xtdb) ops))
        (merge-counts summary counts)))
    {:nodes 0 :edges 0 :chunks 0 :diagnostics 0 :search-docs 0}
    (partition-all batch-size entries))))

(defn file-delete-tx
  "Return temporal delete ops for one removed file and its owned rows."
  [xtdb file-row {:keys [valid-from]}]
  (let [temporal (cond-> {}
                   valid-from (assoc :valid-from valid-from))
        read-ctx (cond-> {}
                   valid-from (assoc :valid-at valid-from))
        existing (file-scoped-rows xtdb (:xt/id file-row) read-ctx)
        ops (vec
             (concat
              (map #(delete-op (:nodes tables) (:xt/id %) temporal) (:nodes existing))
              (map #(delete-op (:edges tables) (:xt/id %) temporal) (:edges existing))
              (map #(delete-op (:chunks tables) (:xt/id %) temporal) (:chunks existing))
              (map #(delete-op (:diagnostics tables) (:xt/id %) temporal) (:diagnostics existing))
              (map #(delete-op (:search-docs tables) (:xt/id %) temporal) (:search-docs existing))
              [(delete-op (:files tables) (:xt/id file-row) temporal)]))]
    {:ops ops
     :counts {:files-deleted 1
              :nodes-deleted (count (:nodes existing))
              :edges-deleted (count (:edges existing))
              :chunks-deleted (count (:chunks existing))
              :diagnostics-deleted (count (:diagnostics existing))
              :search-docs-deleted (count (:search-docs existing))}}))

(defn commit-file-deletes!
  "Delete removed files and their graph rows in bounded transaction batches."
  ([xtdb file-rows opts] (commit-file-deletes! xtdb file-rows opts {:batch-size 50}))
  ([xtdb file-rows opts {:keys [batch-size] :or {batch-size 50}}]
   (reduce
    (fn [summary batch]
      (let [txs (map #(file-delete-tx xtdb % opts) batch)
            ops (vec (mapcat :ops txs))
            counts (reduce merge-counts {} (map :counts txs))]
        (execute-tx! xtdb ops)
        (merge-counts summary counts)))
    {:files-deleted 0
     :nodes-deleted 0
     :edges-deleted 0
     :chunks-deleted 0
     :diagnostics-deleted 0
     :search-docs-deleted 0}
    (partition-all batch-size file-rows))))

(defn commit-embeddings!
  "Persist embedding rows."
  [xtdb rows]
  (let [validated (map validate-embedding-row rows)
        ops (mapv #(put-op (:embeddings tables) %) validated)]
    (when (seq ops)
      (xt/execute-tx (:node xtdb) ops))
    {:embeddings (count validated)}))

(defn commit-project!
  "Persist project and repo rows."
  [xtdb project-row repo-rows]
  (let [ops (into [(put-op (:projects tables) (validate-project-row project-row))]
                  (map #(put-op (:repos tables) (validate-repo-row %))
                       repo-rows))]
    (xt/execute-tx (:node xtdb) ops)
    {:project project-row
     :repos (count repo-rows)}))

(defn- project-rows
  [xtdb table project-id]
  (rows-by-field xtdb table :project-id project-id))

(defn commit-system-graph!
  "Replace derived system graph rows for project."
  [xtdb project-id {:keys [evidence nodes edges search-docs]}]
  (let [existing-evidence (project-rows xtdb (:system-evidence tables) project-id)
        existing-nodes (project-rows xtdb (:system-nodes tables) project-id)
        existing-edges (project-rows xtdb (:system-edges tables) project-id)
        existing-search-docs (->> (project-rows xtdb (:search-docs tables) project-id)
                                  (filter #(#{:system-node :system-edge}
                                            (:target-kind %))))
        evidence (map validate-system-evidence-row evidence)
        nodes (map validate-system-node-row nodes)
        edges (map validate-system-edge-row edges)
        search-docs (map validate-search-doc-row search-docs)
        ops (vec
             (concat
              (map #(delete-op (:system-evidence tables) (:xt/id %)) existing-evidence)
              (map #(delete-op (:system-nodes tables) (:xt/id %)) existing-nodes)
              (map #(delete-op (:system-edges tables) (:xt/id %)) existing-edges)
              (map #(delete-op (:search-docs tables) (:xt/id %)) existing-search-docs)
              (map #(put-op (:system-evidence tables) %) evidence)
              (map #(put-op (:system-nodes tables) %) nodes)
              (map #(put-op (:system-edges tables) %) edges)
              (map #(put-op (:search-docs tables) %) search-docs)))]
    (when (seq ops)
      (xt/execute-tx (:node xtdb) ops))
    {:system-evidence (count evidence)
     :system-nodes (count nodes)
     :system-edges (count edges)
     :search-docs (count search-docs)}))

(defn commit-run!
  "Persist run row."
  [xtdb run-row]
  (xt/execute-tx (:node xtdb) [(put-op (:runs tables) run-row)])
  run-row)

(defn commit-query-run!
  "Persist query run row."
  [xtdb row]
  (xt/execute-tx (:node xtdb) [(put-op (:query-runs tables) row)])
  row)
