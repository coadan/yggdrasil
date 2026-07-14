(ns ygg.benchmark-patch-verifier
  "Checks that behavioral patch verifiers reject base revisions and accept fixes."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [ygg.benchmark-patch :as benchmark-patch]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-prepare :as benchmark-prepare]
            [ygg.benchmark-suite :as benchmark-suite]))

(def check-schema
  "ygg.benchmark.patch-verifier-check/v1")

(def ^:private output-limit
  2000)

(defn- temp-dir
  []
  (-> (java.nio.file.Files/createTempDirectory
       "ygg-patch-verifier-"
       (make-array java.nio.file.attribute.FileAttribute 0))
      .toFile))

(defn- worktree-path
  [temp-root revision repo-id]
  (.getPath (io/file temp-root
                     (str (name revision)
                          "-"
                          (benchmark-paths/safe-id repo-id)))))

(defn- revision-sha
  [repo revision]
  (case revision
    :base (:base-sha repo)
    :fix (:fix-sha repo)))

(defn- remove-worktree!
  [repo-root worktree-root]
  (shell/sh "git" "-C" repo-root "worktree" "remove" "--force" worktree-root))

(defn- with-worktrees
  [repos revision f]
  (let [temp-root (temp-dir)
        created (atom [])]
    (try
      (let [roots (reduce (fn [acc {:keys [repo-id root] :as repo}]
                            (let [path (worktree-path temp-root revision repo-id)]
                              (benchmark-prepare/run-git!
                               root
                               ["worktree" "add" "--detach" path (revision-sha repo revision)])
                              (swap! created conj [root path])
                              (assoc acc repo-id path)))
                          {}
                          repos)]
        (f roots))
      (finally
        (doseq [[repo-root worktree-root] (reverse @created)]
          (remove-worktree! repo-root worktree-root))
        (.delete temp-root)))))

(defn- prepared-worktrees
  [suite case roots]
  {:suitePath (:path suite)
   :case-id (:id case)
   :repo-id (:repo-id case)
   :worktreeRoot (get roots (:repo-id case))
   :worktreeRoots roots})

(defn- run-at-revision
  [suite case repos verifier revision]
  (with-worktrees repos revision
    (fn [roots]
      (benchmark-patch/run-verifier
       (prepared-worktrees suite case roots)
       verifier))))

(defn- bounded-output
  [value]
  (let [value (some-> value str str/trim)]
    (when-not (str/blank? value)
      (subs value 0 (min output-limit (count value))))))

(defn- observed-result
  [result include-output?]
  (cond-> (select-keys result [:status :exit :timedOut :durationMs :warnings])
    (and include-output? (bounded-output (:stdout result)))
    (assoc :stdout (bounded-output (:stdout result)))

    (and include-output? (bounded-output (:stderr result)))
    (assoc :stderr (bounded-output (:stderr result)))))

(defn- contract-failures
  [base-result fix-result]
  (cond-> []
    (:timedOut base-result)
    (conj "behavioral verifier timed out at the base revision")

    (= "passed" (:status base-result))
    (conj "behavioral verifier passed at the base revision")

    (not= "passed" (:status fix-result))
    (conj "behavioral verifier did not pass at the fix revision")))

(defn- verifier-check!
  [suite case repos verifier]
  (try
    (let [base-result (run-at-revision suite case repos verifier :base)
          fix-result (run-at-revision suite case repos verifier :fix)
          failures (contract-failures base-result fix-result)]
      (cond-> {:id (:id verifier)
               :visibility (:visibility verifier)
               :kind (:kind verifier)
               :status (if (seq failures) "failed" "passed")
               :base (observed-result base-result
                                      (= "passed" (:status base-result)))
               :fix (observed-result fix-result
                                     (not= "passed" (:status fix-result)))
               :failures failures}
        (:repoId verifier) (assoc :repoId (:repoId verifier))))
    (catch Exception e
      {:id (:id verifier)
       :visibility (:visibility verifier)
       :kind (:kind verifier)
       :status "failed"
       :failures [(str "behavioral verifier check failed: " (.getMessage e))]})))

(defn- patch-case?
  [case]
  (= "patch" (:result-scope case)))

(defn- case-check!
  [suite case]
  (if-not (patch-case? case)
    {:caseId (:id case)
     :status "skipped"
     :verifiers []
     :failures []}
    (let [repos (benchmark-prepare/case-repos suite case)
          verifiers (->> (get-in (benchmark-patch/config suite case)
                                 [:verifiers])
                         (filter #(= "behavioral" (:kind %)))
                         vec)
          checks (mapv #(verifier-check! suite case repos %) verifiers)
          failures (cond-> []
                     (empty? verifiers)
                     (conj "patch case has no behavioral verifier"))]
      {:caseId (:id case)
       :repoIds (mapv :repo-id repos)
       :status (if (and (empty? failures)
                        (every? #(= "passed" (:status %)) checks))
                 "passed"
                 "failed")
       :verifiers checks
       :failures failures})))

(defn check-suite!
  "Check selected patch cases against their pinned base and fix revisions."
  [suite opts]
  (let [cases (benchmark-suite/selected-cases
               suite
               (benchmark-suite/case-selector opts))
        case-results (mapv #(case-check! suite %) cases)
        checked-cases (remove #(= "skipped" (:status %)) case-results)
        verifiers (mapcat :verifiers checked-cases)
        verifier-statuses (frequencies (map :status verifiers))
        missing-behavioral (count (filter #(seq (:failures %)) checked-cases))
        passed? (and (seq checked-cases)
                     (every? #(= "passed" (:status %)) checked-cases))]
    {:schema check-schema
     :suiteId (:id suite)
     :suitePath (:path suite)
     :status (if passed? "passed" "failed")
     :counts {:cases (count case-results)
              :checkedCases (count checked-cases)
              :verifiers (count verifiers)
              :passed (long (get verifier-statuses "passed" 0))
              :failed (long (get verifier-statuses "failed" 0))
              :missingBehavioralCases missing-behavioral}
     :cases case-results}))

(defn print-human
  [check]
  (println (str "Patch verifier contract check: " (:status check)))
  (println (str "suite: " (:suitePath check)))
  (let [{:keys [cases checkedCases verifiers passed failed missingBehavioralCases]}
        (:counts check)]
    (println (str "cases=" cases
                  " checked=" checkedCases
                  " verifiers=" verifiers
                  " passed=" passed
                  " failed=" failed
                  " missing-behavioral=" missingBehavioralCases)))
  (doseq [case-result (:cases check)
          :when (not= "skipped" (:status case-result))]
    (if (seq (:verifiers case-result))
      (doseq [verifier (:verifiers case-result)]
        (println (str "- "
                      (:caseId case-result)
                      " "
                      (:id verifier)
                      " "
                      (:status verifier))))
      (println (str "- " (:caseId case-result) " failed: "
                    (str/join "; " (:failures case-result)))))))
