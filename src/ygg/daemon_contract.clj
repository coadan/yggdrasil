(ns ygg.daemon-contract
  "Shared daemon-required command boundary contract.")

(def unavailable-exit
  75)

(defn direct-entrypoint-message
  [entrypoint command]
  (str "Direct " entrypoint " entrypoint is disabled. "
       "Yggdrasil commands require the local server. "
       "Run `ygg init` first, or `ygg start` when a project is already initialized, "
       "then use `" command "`.\n"))

(defn direct-entrypoint-response
  [entrypoint command]
  {:ok false
   :exit unavailable-exit
   :out ""
   :err (direct-entrypoint-message entrypoint command)})

(defn print-response!
  [{:keys [out err]}]
  (when (seq out)
    (print out)
    (flush))
  (when (seq err)
    (binding [*out* *err*]
      (print err)
      (flush))))

(defn exit!
  [{:keys [exit] :as response}]
  (print-response! response)
  (shutdown-agents)
  (System/exit (or exit 1)))
