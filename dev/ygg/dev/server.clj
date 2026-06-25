(ns ^:clj-reload/no-reload ygg.dev.server
  "Dev-only reload lifecycle for the server-backed Yggdrasil runtime."
  (:require [clj-reload.core :as reload]
            [clojure.string :as str]
            [nextjournal.beholder :as beholder]))

(def reload-dirs
  "Source dirs clj-reload should scan for server development."
  ["src" "resources" "dev"])

(def watch-extensions
  "File extensions that should trigger a server dev reload."
  #{".clj" ".cljc" ".edn"})

(defonce ^:private !initialized? (atom false))
(defonce ^:private !system (atom nil))
(defonce ^:private !watcher (atom nil))

(defn- path-extension
  [path]
  (let [s (str path)
        i (str/last-index-of s ".")]
    (when (and i (pos? i))
      (subs s i))))

(defn- reload-trigger-path?
  [path]
  (contains? watch-extensions (path-extension path)))

(defn init!
  "Initializes clj-reload for Ygg server development."
  []
  (when (compare-and-set! !initialized? false true)
    (reload/init
     {:dirs reload-dirs
      :no-reload '#{user ygg.dev.server}
      :output :quieter}))
  :initialized)

(defn- server-start!
  []
  ((requiring-resolve 'ygg.server/start!)))

(defn- server-stop!
  [system]
  ((requiring-resolve 'ygg.server/stop!) system))

(defn start!
  "Starts the server once for REPL development."
  []
  (init!)
  (or @!system
      (let [system (server-start!)]
        (reset! !system system)
        system)))

(defn stop!
  "Stops the running server, if any."
  []
  (when-let [system @!system]
    (server-stop! system)
    (reset! !system nil))
  nil)

(defn reload!
  "Stops the server, reloads changed code, then restarts after a successful reload.

  If reload fails, the server remains stopped so requests cannot keep using stale
  code. Fix the compile error and call `reload!` again."
  []
  (init!)
  (let [was-running? (boolean @!system)]
    (stop!)
    (let [result (reload/reload {:throw false})]
      (if-let [e (:exception result)]
        (do
          (println (str "Ygg server reload failed in " (:failed result) "."))
          (.printStackTrace ^Throwable e)
          result)
        (do
          (when was-running?
            (start!))
          result)))))

(defn- schedule-reload!
  [{:keys [debounce-ms generation stopped? reloading?]}]
  (let [generation-id (swap! generation inc)
        thread (Thread.
                (fn []
                  (try
                    (Thread/sleep (long debounce-ms))
                    (when (and (= generation-id @generation)
                               (not @stopped?)
                               (compare-and-set! reloading? false true))
                      (try
                        (println "Ygg server file changes settled; reloading.")
                        (reload!)
                        (finally
                          (reset! reloading? false))))
                    (catch InterruptedException _
                      nil)
                    (catch Throwable t
                      (.printStackTrace t))))
                "ygg-server-reload-debounce")]
    (.setDaemon thread true)
    (.start thread)))

(defn start-watch!
  "Starts a debounced file watcher that reloads the server after code changes."
  ([] (start-watch! {}))
  ([{:keys [debounce-ms]
     :or {debounce-ms 1500}}]
   (init!)
   (or @!watcher
       (let [watcher-state {:debounce-ms (long debounce-ms)
                            :generation (atom 0)
                            :stopped? (atom false)
                            :reloading? (atom false)}
             watch (apply beholder/watch
                          (fn [{:keys [path]}]
                            (when (and (not @(:stopped? watcher-state))
                                       (reload-trigger-path? path))
                              (schedule-reload! watcher-state)))
                          reload-dirs)
             watcher (assoc watcher-state :watch watch)]
         (reset! !watcher watcher)
         (println (str "Ygg server reload watcher started with "
                       debounce-ms
                       "ms debounce."))
         watcher))))

(defn stop-watch!
  "Stops the server reload watcher, if running."
  []
  (when-let [{:keys [stopped? watch]} @!watcher]
    (reset! stopped? true)
    (beholder/stop watch)
    (reset! !watcher nil)
    (println "Ygg server reload watcher stopped."))
  nil)

(defn restart!
  "Restarts the server without reloading namespaces."
  []
  (stop!)
  (start!))

(defn status
  "Returns the dev reload lifecycle state."
  []
  {:initialized? @!initialized?
   :running? (boolean @!system)
   :watching? (boolean @!watcher)
   :reload-dirs reload-dirs})

(defn -main
  [& args]
  (start!)
  (when-not (some #{"--no-watch"} args)
    (start-watch!))
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. #(do
                                (stop-watch!)
                                (stop!))))
  (println "Use a connected REPL to call (ygg.dev.server/reload!) for manual reloads.")
  @(promise))
