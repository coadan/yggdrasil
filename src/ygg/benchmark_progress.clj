(ns ygg.benchmark-progress
  "Progress event helpers for benchmark case execution."
  (:require [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-paths :as benchmark-paths]
            [clojure.java.io :as io])
  (:import [java.time Duration Instant]))

(defn now-string
  []
  (str (java.time.Instant/now)))

(defn- instant-value
  [value]
  (cond
    (instance? Instant value) value
    (string? value) (Instant/parse value)
    :else (Instant/now)))

(defn elapsed-between-ms
  [started-at ended-at]
  (max 0 (.toMillis (Duration/between (instant-value started-at)
                                      (instant-value ended-at)))))

(defn- elapsed-ms
  [started-ns]
  (max 1 (long (/ (- (System/nanoTime) started-ns) 1000000))))

(defn- progress-event
  [stage status extra]
  (merge {:stage (name stage)
          :status (name status)
          :at (now-string)}
         extra))

(def ^:private progress-error-data-keys
  #{:phase
    :project-id
    :repo-id
    :files-scanned
    :files-changed
    :files-skipped
    :files-extracted
    :files-indexed
    :files-deleted
    :path})

(defn- progress-error-value
  [value]
  (cond
    (keyword? value) (name value)
    (symbol? value) (str value)
    (or (string? value)
        (number? value)
        (boolean? value)
        (nil? value)) value
    :else (str value)))

(defn- progress-error-data
  [throwable]
  (not-empty
   (into {}
         (keep (fn [[k v]]
                 (when (contains? progress-error-data-keys k)
                   [k (progress-error-value v)])))
         (ex-data throwable))))

(defn- progress-error
  [throwable]
  (cond-> {:class (.getName (class throwable))
           :message (ex-message throwable)}
    (progress-error-data throwable) (assoc :data (progress-error-data throwable))))

(defn- read-progress
  [path suite case]
  (if (.isFile (io/file path))
    (benchmark-io/read-json-file path)
    {:schema "ygg.benchmark.case-progress/v1"
     :suite-id (:id suite)
     :case-id (:id case)
     :events []}))

(defn- append-progress-event!
  [suite case opts event]
  (let [path (benchmark-paths/progress-path suite case opts)
        progress (read-progress path suite case)]
    (benchmark-io/write-json-file! path
                                   (-> progress
                                       (assoc :updatedAt (now-string))
                                       (update :events (fnil conj []) event)))
    path))

(defn- shutdown-hook-thread
  [f]
  (Thread. ^Runnable f "ygg-benchmark-progress-shutdown-hook"))

(defn- remove-shutdown-hook!
  [hook]
  (try
    (.removeShutdownHook (Runtime/getRuntime) hook)
    (catch IllegalStateException _
      nil)
    (catch Throwable _
      nil)))

(defn progress-stage!
  "Run f while appending benchmark progress events for the stage."
  ([suite case opts stage f]
   (progress-stage! suite case opts stage f (constantly nil)))
  ([suite case opts stage f summarize]
   (let [started-ns (System/nanoTime)
         active? (atom true)]
     (append-progress-event! suite
                             case
                             opts
                             (progress-event stage :started {}))
     (let [hook (shutdown-hook-thread
                 (fn []
                   (when (compare-and-set! active? true false)
                     (try
                       (append-progress-event!
                        suite
                        case
                        opts
                        (progress-event
                         stage
                         :failed
                         {:elapsedMs (elapsed-ms started-ns)
                          :interrupted true
                          :error {:class "java.lang.Runtime"
                                  :message
                                  "Benchmark JVM shut down before stage completed."}}))
                       (catch Throwable _
                         nil)))))]
       (.addShutdownHook (Runtime/getRuntime) hook)
       (try
         (let [result (f)
               summary (summarize result)]
           (when (compare-and-set! active? true false)
             (append-progress-event! suite
                                     case
                                     opts
                                     (progress-event
                                      stage
                                      :completed
                                      (cond-> {:elapsedMs (elapsed-ms started-ns)}
                                        (some? summary) (assoc :summary summary)))))
           result)
         (catch Throwable t
           (when (compare-and-set! active? true false)
             (append-progress-event! suite
                                     case
                                     opts
                                     (progress-event
                                      stage
                                      :failed
                                      {:elapsedMs (elapsed-ms started-ns)
                                       :error (progress-error t)})))
           (throw t))
         (finally
           (remove-shutdown-hook! hook)))))))
