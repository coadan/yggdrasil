(ns ygg.extract
  "Deterministic extraction from supported source, config, and document files."
  (:require [ygg.extract.common :as extract.common]
            [ygg.hash :as hash]
            [ygg.extract.parser-worker :as extract.parser-worker]
            [ygg.extract.versions :as extract.versions]
            [ygg.extract.router :as extract.router]))

(def extraction-buckets
  [:nodes :edges :chunks :diagnostics])

(def extractor-contract-version
  extract.versions/extractor-contract-version)

(def extractor-versions
  extract.versions/extractor-versions)

(defn empty-extraction
  "Return an empty canonical extractor result."
  []
  {:nodes []
   :edges []
   :chunks []
   :diagnostics []})

(defn normalize-extraction
  "Return extractor output with the canonical Yggdrasil extraction buckets.

  External parser adapters should return data at this boundary and let Yggdrasil
  own ids, row shape, relation names, diagnostics, and persistence."
  [extraction]
  (let [extraction (or extraction {})]
    (reduce (fn [out k]
              (assoc out k (vec (get extraction k []))))
            {}
            extraction-buckets)))

(defn normalize-parser-worker-mode
  [mode]
  (extract.parser-worker/normalize-parser-worker-mode mode))

(defn parser-worker-mode
  []
  (extract.parser-worker/parser-worker-mode))

(defmacro with-parser-worker-mode
  [mode & body]
  `(extract.parser-worker/with-parser-worker-mode ~mode ~@body))

(defn- parser-worker-fingerprint
  []
  (extract.parser-worker/parser-worker-fingerprint))

(defn parser-worker-enabled?
  [kind]
  (extract.parser-worker/parser-worker-enabled? kind))

(defn parser-worker-python
  []
  (extract.parser-worker/parser-worker-python))

(defn parser-worker-batch-facts
  "Return parser-worker facts by file path for worker-enabled file records."
  [files]
  (extract.parser-worker/parser-worker-batch-facts
   files
   {:enabled? parser-worker-enabled?
    :python parser-worker-python}))

(defn- parser-worker-facts
  [file]
  (extract.parser-worker/parser-worker-facts
   file
   {:enabled? parser-worker-enabled?
    :python parser-worker-python}))

(defn extract-file
  "Extract graph rows from a file record."
  [run-id file]
  (normalize-extraction
   (extract.router/extract-file
    run-id
    file
    {:parser-worker-enabled? parser-worker-enabled?
     :parser-worker-facts parser-worker-facts})))

(defn extractor-fingerprint
  "Return the stable extractor fingerprint for a file record."
  [file]
  (let [kind (:kind file)]
    (str "extractor:"
         (hash/short-hash [extractor-contract-version
                           kind
                           (get extractor-versions kind "none/v1")
                           (parser-worker-fingerprint)]))))

(defn node-id
  "Return stable node id for kind/name."
  ([kind value] (extract.common/node-id kind value))
  ([id-scope kind value]
   (extract.common/node-id id-scope kind value)))

(defn edge-id
  "Return stable edge id."
  [source-id target-id relation _path _source-line]
  (extract.common/edge-id source-id target-id relation nil nil))

(defn chunk-id
  "Return stable chunk id."
  ([path label source-line] (extract.common/chunk-id path label source-line))
  ([id-scope path label source-line]
   (extract.common/chunk-id id-scope path label source-line)))

(defn extract-text-source
  "Extract a supported text source file as one searchable chunk."
  [run-id file chunk-kind]
  (extract.common/extract-text-source run-id file chunk-kind))

