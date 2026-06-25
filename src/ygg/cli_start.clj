(ns ygg.cli-start
  (:require [ygg.cli-options :refer [option-value positional-args]]
            [ygg.init :as init]))

(def ^:dynamic *deps* {})

(defn- call-dep
  [k & args]
  (apply (or (get *deps* k)
             (throw (ex-info "Missing CLI start dependency." {:dependency k})))
         args))

(defn- print-json
  [data]
  (call-dep :print-json data))

(defn- dispatch
  [command args]
  (call-dep :dispatch command args))

(defn- query-index?
  [args]
  (call-dep :query-index? args))

(defn- init-sync-args
  [project-id config-path query-index?]
  (cond-> (if config-path
            [config-path "--check" "--no-progress"]
            ["--project" project-id "--check" "--no-progress"])
    query-index? (conj "--query-index")))

(defn init!
  [args deps]
  (binding [*deps* deps]
    (let [workbench-root (option-value args "--workbench")
          root (or workbench-root (first (positional-args args)) ".")
          result (init/init! root
                             {:out (option-value args "--out")
                              :force? (boolean (some #{"--force"} args))
                              :project-id (option-value args "--project")
                              :name (option-value args "--name")
                              :workbench? (boolean workbench-root)
                              :task (option-value args "--task")})]
      (print-json
       (cond-> result
         (some #{"--sync"} args)
         (assoc :sync-output
                (with-out-str
                  (dispatch "sync"
                            (init-sync-args (:project-id result)
                                            (:config result)
                                            (query-index? args))))))))))
