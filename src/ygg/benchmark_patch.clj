(ns ygg.benchmark-patch
  "Canonical patch verifier normalization and execution."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ygg.benchmark-agent-run :as benchmark-agent-run]
            [ygg.benchmark-util :as benchmark-util]
            [ygg.hash :as hash]))

(def default-verifier-timeout-ms
  120000)

(defn- normalize-id
  [value]
  (when-not (benchmark-util/blankish? value)
    (str value)))

(defn- verifier-input-digest
  [suite path]
  (let [base (.getCanonicalFile (.getParentFile (io/file (:path suite))))
        input (.getCanonicalFile (io/file base (str path)))
        base-path (.toPath base)
        input-path (.toPath input)]
    (when-not (.startsWith input-path base-path)
      (throw (ex-info "Patch verifier input must stay under the suite directory."
                      {:suite (:path suite)
                       :input path})))
    (when-not (.isFile input)
      (throw (ex-info "Patch verifier input does not exist."
                      {:suite (:path suite)
                       :input path})))
    {:path (str path)
     :sha256 (hash/sha256-bytes-hex
              (java.nio.file.Files/readAllBytes input-path))}))

(defn normalize-verifier
  "Normalize one patch verifier and fingerprint its declared suite-local inputs."
  [suite verifier]
  (let [id (normalize-id (:id verifier))
        command (some-> (:command verifier) str str/trim not-empty)
        visibility (name (keyword (or (:visibility verifier) :public)))
        kind (name (keyword (or (:kind verifier) :structural)))]
    (when (benchmark-util/blankish? id)
      (throw (ex-info "Patch verifier is missing :id."
                      {:verifier verifier})))
    (when-not command
      (throw (ex-info "Patch verifier is missing :command."
                      {:verifier verifier})))
    (when-not (contains? #{"public" "hidden"} visibility)
      (throw (ex-info "Patch verifier has unsupported :visibility."
                      {:verifier verifier
                       :supported ["public" "hidden"]})))
    (when-not (contains? #{"structural" "behavioral"} kind)
      (throw (ex-info "Patch verifier has unsupported :kind."
                      {:verifier verifier
                       :supported ["structural" "behavioral"]})))
    (cond-> {:id id
             :command command
             :visibility visibility
             :kind kind}
      (seq (:inputs verifier))
      (assoc :inputDigests (mapv #(verifier-input-digest suite %)
                                 (:inputs verifier)))

      (:repo-id verifier) (assoc :repoId (str (:repo-id verifier)))
      (:repoId verifier) (assoc :repoId (str (:repoId verifier)))
      (:timeout-ms verifier) (assoc :timeoutMs (long (:timeout-ms verifier)))
      (:timeoutMs verifier) (assoc :timeoutMs (long (:timeoutMs verifier))))))

(defn config
  "Return the normalized patch contract for a benchmark case."
  [suite case]
  (when-let [patch (:patch case)]
    (let [verifiers (mapv #(normalize-verifier suite %) (:verifiers patch))]
      (cond-> {:required (boolean (:required? patch))
               :verifiers verifiers}
        (:policy patch) (assoc :policy (name (:policy patch)))))))

(defn agent-visible-config
  "Return the patch contract safe to expose to an external benchmark agent."
  [suite case]
  (some-> (config suite case)
          (update :verifiers (fn [verifiers]
                               (->> verifiers
                                    (remove #(= "hidden" (:visibility %)))
                                    vec)))))

(defn- verifier-root
  [prepared verifier]
  (let [repo-id (or (:repoId verifier)
                    (:repo-id verifier)
                    (:repo-id prepared))]
    (or (when repo-id
          (get (:worktreeRoots prepared) repo-id))
        (:worktreeRoot prepared))))

(defn run-verifier
  "Run one normalized verifier against prepared benchmark worktrees."
  [prepared verifier]
  (let [root (verifier-root prepared verifier)
        command (:command verifier)
        hidden? (= "hidden" (:visibility verifier))
        suite-dir (some-> (:suitePath prepared)
                          io/file
                          .getParentFile
                          .getCanonicalPath)
        env (cond-> {"YGG_BENCH_WORKTREE" (or root "")
                     "YGG_BENCH_CASE_ID" (:case-id prepared)
                     "YGG_BENCH_REPO_ID" (or (:repoId verifier)
                                             (:repo-id prepared))}
              suite-dir (assoc "YGG_BENCH_SUITE_DIR" suite-dir))
        timeout-ms (long (or (:timeoutMs verifier)
                             default-verifier-timeout-ms))]
    (if (and root command)
      (let [result (benchmark-agent-run/run-process! command root env timeout-ms)]
        (cond-> {:id (:id verifier)
                 :visibility (:visibility verifier)
                 :kind (:kind verifier)
                 :status (if (zero? (:exit result)) "passed" "failed")
                 :exit (:exit result)
                 :timedOut (boolean (:timedOut result))
                 :durationMs (:durationMs result)}
          (not hidden?)
          (assoc :command command)

          (:repoId verifier)
          (assoc :repoId (:repoId verifier))

          (not (str/blank? (:stdout result)))
          (assoc :stdout (:stdout result))

          (not (str/blank? (:stderr result)))
          (assoc :stderr (:stderr result))))
      {:id (:id verifier)
       :visibility (:visibility verifier)
       :kind (:kind verifier)
       :status "failed"
       :exit -1
       :timedOut false
       :durationMs 0
       :warnings ["patch verifier did not resolve to a benchmark worktree"]})))
