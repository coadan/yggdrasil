(ns ygg.cli-start
  (:require [ygg.cli-options :refer [option-value positional-args]]
            [ygg.init :as init]))

(defn- dep
  [deps k]
  (or (get deps k)
      (throw (ex-info "Missing CLI start dependency." {:dependency k}))))

(defn- init-sync-args
  [project-id config-path query-index?]
  (cond-> (if config-path
            [config-path "--check" "--no-progress"]
            ["--project" project-id "--check" "--no-progress"])
    query-index? (conj "--query-index")))

(defn- sync-output
  [args deps result]
  (let [dispatch (dep deps :dispatch)
        query-index? (dep deps :query-index?)]
    (with-out-str
      (dispatch "sync"
                (init-sync-args (:project-id result)
                                (:config result)
                                (query-index? args))))))

(defn init!
  [args deps]
  (let [print-json (dep deps :print-json)
        workbench-root (option-value args "--workbench")
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
       (assoc :sync-output (sync-output args deps result))))))
