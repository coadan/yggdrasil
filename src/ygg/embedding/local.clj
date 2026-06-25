(ns ygg.embedding.local
  "Local embedding client backed by a JSONL sentence-transformers worker."
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [ygg.env :as env])
  (:import [java.io IOException]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.util.concurrent TimeUnit]))

(def default-model-name "sentence-transformers/all-MiniLM-L6-v2")
(def default-venv-path ".ygg/local-embedding-venv")

(defn configured-model
  "Return the configured local embedding model name."
  []
  (or (env/get-env "YGG_LOCAL_EMBEDDING_MODEL")
      default-model-name))

(defn- shell-quote
  [value]
  (str "'" (str/replace (str value) #"'" "'\"'\"'") "'"))

(defn- ygg-home
  []
  (or (System/getProperty "ygg.home")
      (env/get-env "YGG_HOME")
      "."))

(defn default-worker-path
  "Return the bundled local embedding worker path."
  []
  (.getPath (io/file (ygg-home) "scripts" "local-embedding-worker.py")))

(defn default-requirements-path
  "Return the bundled local embedding Python requirements path."
  []
  (.getPath (io/file (ygg-home) "scripts" "local-vector-requirements.txt")))

(defn venv-python-path
  "Return the Python executable path for a local embedding virtualenv."
  [venv-path]
  (.getPath (if (str/includes? (str/lower-case (System/getProperty "os.name" "")) "win")
              (io/file venv-path "Scripts" "python.exe")
              (io/file venv-path "bin" "python"))))

(defn default-venv-python-path
  "Return the conventional project-local embedding virtualenv Python path."
  []
  (venv-python-path default-venv-path))

(defn- existing-file-path
  [path]
  (when (and path (.isFile (io/file path)))
    path))

(defn default-python
  "Return the Python executable used by the bundled local embedding worker."
  []
  (or (env/get-env "YGG_LOCAL_EMBEDDING_PYTHON")
      (existing-file-path (default-venv-python-path))
      "python3"))

(defn default-command
  "Return the local embedding worker command without the model argument."
  []
  (or (env/get-env "YGG_LOCAL_EMBEDDING_COMMAND")
      (str (shell-quote (default-python)) " " (shell-quote (default-worker-path)))))

(defn- start-worker!
  [{:keys [command model]}]
  (let [shell-command (str "exec " command " " (shell-quote model))
        process-builder (doto (ProcessBuilder. ["sh" "-lc" shell-command])
                          (.redirectError ProcessBuilder$Redirect/INHERIT))
        process (.start process-builder)]
    {:process process
     :reader (io/reader (.getInputStream process))
     :writer (io/writer (.getOutputStream process))}))

(defn- close-quietly!
  [closeable]
  (when closeable
    (try
      (.close closeable)
      (catch Exception _ nil))))

(defn- reset-worker-state!
  [state]
  (let [{:keys [command model]} @state]
    (reset! state {:command command
                   :model model})))

(defn- close-worker!
  [state]
  (locking state
    (let [{:keys [process reader writer]} @state]
      (close-quietly! writer)
      (when process
        (try
          (when-not (.waitFor ^Process process 2 TimeUnit/SECONDS)
            (.destroy ^Process process)
            (when-not (.waitFor ^Process process 2 TimeUnit/SECONDS)
              (.destroyForcibly ^Process process)
              (.waitFor ^Process process 2 TimeUnit/SECONDS)))
          (catch Exception _ nil)))
      (close-quietly! reader)
      (reset-worker-state! state))))

(defn- ensure-worker!
  [state]
  (let [{:keys [process] :as current} @state]
    (if (and process (.isAlive ^Process process))
      current
      (locking state
        (let [{:keys [process] :as current} @state]
          (if (and process (.isAlive ^Process process))
            current
            (let [worker (start-worker! current)]
              (swap! state merge worker))))))))

(defn- exited-message
  [process]
  (if (and process (not (.isAlive ^Process process)))
    (str "exit " (.exitValue ^Process process))
    "unknown exit"))

(defn- parse-response
  [line request-count {:keys [command model]}]
  (let [response (json/read-json line :key-fn keyword)]
    (if-let [error (:error response)]
      (throw (ex-info "Local embedding worker returned an error."
                      {:provider :local
                       :model model
                       :command command
                       :error error}))
      (let [vectors (:vectors response)]
        (when-not (= request-count (count vectors))
          (throw (ex-info "Local embedding worker returned wrong vector count."
                          {:provider :local
                           :model model
                           :command command
                           :expected request-count
                           :actual (count vectors)})))
        (mapv #(mapv double %) vectors)))))

(defn- request-vectors!
  [state inputs]
  (let [{:keys [process reader writer] :as worker} (ensure-worker! state)]
    (try
      (.write writer (json/write-json-str {"inputs" (vec inputs)}))
      (.newLine writer)
      (.flush writer)
      (if-let [line (.readLine reader)]
        (parse-response line (count inputs) worker)
        (throw (ex-info "Local embedding worker exited without a response."
                        {:provider :local
                         :model (:model worker)
                         :command (:command worker)
                         :status (exited-message process)})))
      (catch IOException exc
        (throw (ex-info "Local embedding worker IO failed."
                        {:provider :local
                         :model (:model worker)
                         :command (:command worker)}
                        exc))))))

(defn- run-command!
  [command context]
  (let [{:keys [exit out err]} (apply shell/sh command)]
    (when-not (zero? exit)
      (throw (ex-info "Local embedding setup command failed."
                      (assoc context
                             :command command
                             :exit exit
                             :out out
                             :err err))))
    {:out out
     :err err}))

(defn setup-venv!
  "Create/update the conventional local embedding Python virtualenv."
  [{:keys [venv-path python]}]
  (let [venv-path (or venv-path default-venv-path)
        python (or python "python3")
        venv-file (io/file venv-path)
        requirements (default-requirements-path)
        venv-python (venv-python-path venv-path)
        python-file (io/file venv-python)
        created? (not (.isFile python-file))]
    (when-not (.isFile (io/file requirements))
      (throw (ex-info "Bundled local embedding requirements file was not found."
                      {:requirements requirements})))
    (when-let [parent (.getParentFile venv-file)]
      (.mkdirs parent))
    (when created?
      (run-command! [python "-m" "venv" venv-path]
                    {:step :create-venv
                     :venv venv-path
                     :python python}))
    (run-command! [venv-python "-m" "pip" "install" "-r" requirements]
                  {:step :install-requirements
                   :venv venv-path
                   :python venv-python
                   :requirements requirements})
    {:provider :local
     :venv venv-path
     :python venv-python
     :requirements requirements
     :created? created?
     :default? (= venv-python (default-venv-python-path))}))

(defn client
  "Return a local embedding client map.

  The command receives the model as its final shell argument. Override the
  Python executable with YGG_LOCAL_EMBEDDING_PYTHON, or override the full
  command with YGG_LOCAL_EMBEDDING_COMMAND when using a custom worker.
  "
  ([] (client {}))
  ([{:keys [command model]
     :or {model (configured-model)}}]
   (let [state (atom {:command (or command (default-command))
                      :model model})]
     {:provider :local
      :model model
      :embed-batch (fn [inputs]
                     (if (seq inputs)
                       (request-vectors! state (mapv str inputs))
                       []))
      :close (fn [] (close-worker! state))})))
