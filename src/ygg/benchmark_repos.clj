(ns ygg.benchmark-repos
  "Preflight checks for tracked benchmark repository inputs."
  (:require [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [ygg.benchmark-suite :as benchmark-suite]
            [ygg.fs :as fs]))

(def default-manifest-path
  "benchmarks/repos.edn")

(defn- blankish?
  [value]
  (or (nil? value)
      (and (string? value) (str/blank? value))))

(defn- option-value
  [args flag]
  (some (fn [[k v]]
          (when (= flag k) v))
        (partition 2 1 args)))

(defn- has-option?
  [args flag]
  (boolean (some #{flag} args)))

(defn- resolve-path
  [path]
  (fs/canonical-path (io/file path)))

(defn- absolute-path
  [path]
  (.getAbsolutePath (io/file path)))
(defn- config-dir
  [path]
  (or (some-> (io/file path) .getCanonicalFile .getParentFile)
      (io/file ".")))
(defn- canonical-or-relative
  [base path]
  (let [file (io/file path)]
    (resolve-path
     (if (.isAbsolute file)
       file
       (io/file base path)))))
(defn- include-path
  [base include]
  (let [path (cond
               (string? include) include
               (map? include) (:path include)
               :else nil)]
    (when (blankish? path)
      (throw (ex-info "Benchmark suite include is missing :path."
                      {:include include})))
    (canonical-or-relative base path)))
(defn read-manifest
  "Read the tracked benchmark repo manifest."
  ([]
   (read-manifest default-manifest-path))
  ([path]
   (let [manifest (edn/read-string (slurp (io/file path)))
         cache-root (resolve-path (:cache-root manifest))
         legacy-cache-root (some-> (:legacy-cache-root manifest) resolve-path)]
     (assoc manifest
            :path (resolve-path path)
            :cache-root cache-root
            :legacy-cache-root legacy-cache-root
            :repos (mapv (fn [repo]
                           (let [repo-id (str (:id repo))]
                             (when (blankish? repo-id)
                               (throw (ex-info "Benchmark repo is missing :id."
                                               {:repo repo})))
                             (assoc repo
                                    :id repo-id
                                    :dir (str (or (:dir repo) repo-id))
                                    :required-shas (vec (:required-shas repo)))))
                         (:repos manifest))))))

(defn- repo-by-id
  [manifest]
  (into {} (map (juxt :id identity)) (:repos manifest)))

(defn- raw-suite-repo-ids
  ([suite-path]
   (->> (raw-suite-repo-ids suite-path #{})
        distinct
        sort
        vec))
  ([suite-path seen]
   (let [suite-path (resolve-path suite-path)]
     (when (contains? seen suite-path)
       (throw (ex-info "Benchmark suite includes form a cycle."
                       {:path suite-path
                        :include-stack (vec seen)})))
     (let [suite (edn/read-string (slurp (io/file suite-path)))
           base (config-dir suite-path)
           included-ids (mapcat #(raw-suite-repo-ids (include-path base %)
                                                     (conj seen suite-path))
                                (:include-suites suite))]
       (concat included-ids
               (map (comp str :id) (:repos suite)))))))
(defn- suite-repo-ids
  [suite-path]
  (try
    (->> (:repos (benchmark-suite/read-suite suite-path))
         (map :id)
         distinct
         sort
         vec)
    (catch clojure.lang.ExceptionInfo e
      (if (contains? #{"Benchmark suite is missing :cases."
                       "Benchmark suite is missing :repos."}
                     (ex-message e))
        (raw-suite-repo-ids suite-path)
        (throw e)))))

(defn- selected-repo-ids
  [manifest {:keys [suite-path repo-ids]}]
  (cond
    (seq repo-ids) (->> repo-ids (map str) distinct sort vec)
    (not (blankish? suite-path)) (suite-repo-ids suite-path)
    :else (->> (:repos manifest) (map :id) sort vec)))

(defn- git-root?
  [path]
  (.exists (io/file path ".git")))

(defn- git-output
  [path & args]
  (let [{:keys [exit out]} (apply shell/sh "git" "-C" path args)]
    (when (zero? exit)
      (str/trim out))))

(defn- git-head
  [path]
  (git-output path "rev-parse" "HEAD"))

(defn- git-has-commit?
  [path sha]
  (boolean (git-output path "cat-file" "-e" (str sha "^{commit}"))))

(defn- repo-path
  [root repo]
  (absolute-path (io/file root (:dir repo))))

(defn- status-key
  [{:keys [present git missing-shas]}]
  (cond
    (not present) :missing
    (not git) :not-git
    (seq missing-shas) :missing-shas
    :else :ready))

(defn repo-status
  "Return local checkout status for one manifest repo."
  [manifest repo]
  (let [path (repo-path (:cache-root manifest) repo)
        legacy-path (some-> (:legacy-cache-root manifest)
                            (repo-path repo))
        present? (.isDirectory (io/file path))
        git? (and present? (git-root? path))
        required-shas (:required-shas repo)
        missing-shas (if git?
                       (vec (remove #(git-has-commit? path %) required-shas))
                       required-shas)
        row (cond-> (assoc repo
                           :path path
                           :present present?
                           :git (boolean git?)
                           :missing-shas missing-shas
                           :legacy-path legacy-path
                           :legacy-present (boolean
                                            (some-> legacy-path
                                                    io/file
                                                    .isDirectory)))
              git?
              (assoc :head (git-head path)))]
    (assoc row :status (status-key row))))

(defn check-repos
  "Return preflight status for benchmark repos, optionally narrowed to a suite."
  [{:keys [manifest-path] :as opts}]
  (let [manifest (read-manifest (or manifest-path default-manifest-path))
        by-id (repo-by-id manifest)
        ids (selected-repo-ids manifest opts)
        unknown-ids (vec (remove by-id ids))
        statuses (mapv #(repo-status manifest (by-id %)) (remove (set unknown-ids) ids))
        counts (frequencies (map :status statuses))
        ok? (and (empty? unknown-ids)
                 (every? #(= :ready (:status %)) statuses))]
    {:schema "ygg.benchmark.repo-check/v1"
     :status (if ok? "passed" "failed")
     :manifest-path (:path manifest)
     :cache-root (:cache-root manifest)
     :legacy-cache-root (:legacy-cache-root manifest)
     :suite-path (some-> (:suite-path opts) resolve-path)
     :counts {:repos (count ids)
              :ready (long (get counts :ready 0))
              :missing (long (get counts :missing 0))
              :not-git (long (get counts :not-git 0))
              :missing-shas (long (get counts :missing-shas 0))
              :unknown (count unknown-ids)}
     :unknown-repos unknown-ids
     :repos statuses}))

(defn- print-repo-line
  [{:keys [id status path url legacy-present legacy-path missing-shas]}]
  (println (str "- " id " " (name status)))
  (println (str "  expected: " path))
  (when url
    (println (str "  clone: git clone " url " " path)))
  (when legacy-present
    (println (str "  legacy checkout exists: " legacy-path)))
  (when (and (= :missing-shas status) (seq missing-shas))
    (println (str "  missing commits: " (str/join ", " missing-shas)))))

(defn print-human
  [check]
  (println (str "Benchmark repo preflight: " (:status check)))
  (println (str "manifest: " (:manifest-path check)))
  (println (str "cache: " (:cache-root check)))
  (when (:suite-path check)
    (println (str "suite: " (:suite-path check))))
  (let [{:keys [repos ready missing not-git missing-shas unknown]} (:counts check)]
    (println (str "repos=" repos
                  " ready=" ready
                  " missing=" missing
                  " not-git=" not-git
                  " missing-shas=" missing-shas
                  " unknown=" unknown)))
  (doseq [id (:unknown-repos check)]
    (println (str "- " id " unknown"))
    (println "  add it to benchmarks/repos.edn or fix the suite :repos entry."))
  (doseq [repo (remove #(= :ready (:status %)) (:repos check))]
    (print-repo-line repo)))

(defn- usage
  []
  (println "Usage: ygg bench repos check [--manifest PATH] [--suite PATH] [--repo ID] [--json]"))

(defn -main
  [& args]
  (let [[command & rest-args] args]
    (case command
      "check"
      (let [repo-ids (->> rest-args
                          (partition 2 1)
                          (keep (fn [[k v]]
                                  (when (= "--repo" k) v)))
                          vec)
            check (check-repos {:manifest-path (option-value rest-args "--manifest")
                                :suite-path (option-value rest-args "--suite")
                                :repo-ids repo-ids})]
        (if (has-option? rest-args "--json")
          (println (json/write-json-str check {:indent-str "  "}))
          (print-human check))
        (System/exit (if (= "failed" (:status check)) 1 0)))

      (do
        (usage)
        (System/exit 2)))))
