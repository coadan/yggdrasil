(ns ygg.extract.parser-worker
  (:require [charred.api :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def ^:dynamic *parser-worker-mode*
  nil)
(defn normalize-parser-worker-mode
  [mode]
  (some-> mode str str/lower-case str/trim not-empty))
(defn parser-worker-mode
  []
  (or (normalize-parser-worker-mode *parser-worker-mode*)
      (normalize-parser-worker-mode (System/getenv "YGG_PARSER_WORKER"))))
(defmacro with-parser-worker-mode
  [mode & body]
  `(binding [*parser-worker-mode* (normalize-parser-worker-mode ~mode)]
     ~@body))
(defn parser-worker-fingerprint
  []
  (or (parser-worker-mode) "none"))
(defn parser-worker-enabled?
  [kind]
  (let [mode (parser-worker-mode)]
    (and kind
         (or (= "all" mode)
             (= (name kind) mode)))))
(defn parser-worker-python
  []
  (or (not-empty (System/getenv "YGG_PARSER_WORKER_PYTHON"))
      "python3"))
(defn parser-worker-failure
  [stage message]
  {:definitions []
   :imports []
   :references []
   :diagnostics [{:stage stage
                  :line nil
                  :message message}]})
(defn- parser-worker-request
  [{:keys [kind path absolute-path content]}]
  (cond-> {:schema "ygg.parser.request/v1"
           :id path
           :kind (name kind)
           :path (or absolute-path path)}
    (nil? absolute-path) (assoc :content content)))
(defn- parser-worker-response->facts
  [response]
  (or (:facts response)
      (parser-worker-failure
       "parser-worker"
       "Parser worker response did not include facts.")))
(defn parser-worker-batch-facts
  "Return parser-worker facts by file path for worker-enabled file records."
  ([files]
   (parser-worker-batch-facts files {:enabled? parser-worker-enabled?
                                     :python parser-worker-python}))
  ([files {:keys [enabled? python]
           :or {enabled? parser-worker-enabled?
                python parser-worker-python}}]
   (let [files (vec (filter #(enabled? (:kind %)) files))]
     (if (empty? files)
       {}
       (try
         (let [input (str (str/join "\n"
                                    (map #(json/write-json-str
                                           (parser-worker-request %)
                                           {:escape-slash false})
                                         files))
                          "\n")
               {:keys [exit out err]} (shell/sh (python)
                                                "scripts/parser-worker.py"
                                                :in input)]
           (if (zero? exit)
             (try
               (let [responses (->> (str/split-lines out)
                                    (remove str/blank?)
                                    (map #(json/read-json % :key-fn keyword)))
                     by-id (->> responses
                                (map (fn [response]
                                       [(:id response)
                                        (parser-worker-response->facts response)]))
                                (into {}))]
                 (->> files
                      (map (fn [{:keys [path]}]
                             [path
                              (or (get by-id path)
                                  (parser-worker-failure
                                   "parser-worker"
                                   "Parser worker did not return a response for this file."))]))
                      (into {})))
               (catch Exception e
                 (let [failure (parser-worker-failure
                                "parser-worker"
                                (str "Parser worker returned unreadable JSON: "
                                     (ex-message e)))]
                   (into {} (map (juxt :path (constantly failure)) files)))))
             (let [failure (parser-worker-failure
                            "parser-worker"
                            (str "parser worker exited " exit ": "
                                 (or (not-empty err) out)))]
               (into {} (map (juxt :path (constantly failure)) files)))))
         (catch Exception e
           (let [failure (parser-worker-failure
                          "parser-worker"
                          (str "Could not run parser worker: " (ex-message e)))]
             (into {} (map (juxt :path (constantly failure)) files)))))))))
(defn parser-worker-facts
  ([file]
   (parser-worker-facts file {:enabled? parser-worker-enabled?
                              :python parser-worker-python}))
  ([{:keys [path] :as file} opts]
   (if (contains? file :parser-worker-facts)
     (:parser-worker-facts file)
     (get (parser-worker-batch-facts [file] opts)
          path
          (parser-worker-failure
           "parser-worker"
           "Parser worker did not return facts for this file.")))))
