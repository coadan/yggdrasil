(ns ygg.benchmark-score-artifacts
  (:require [ygg.benchmark-agent-packet :as benchmark-agent-packet]
            [ygg.benchmark-agent-score :as benchmark-agent-score]
            [ygg.benchmark-io :as benchmark-io]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-prepare :as benchmark-prepare]
            [ygg.benchmark-util :as benchmark-util]
            [ygg.fs :as fs]
            [clojure.string :as str]))

(def agent-result-schema
  "ygg.benchmark.agent-result/v2")

(def agent-score-schema
  "ygg.benchmark.agent-score/v3")

(defn- score-json-file?
  [file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".json")))
(defn current-agent-score-artifacts
  [suite case opts {:keys [agent-id mode result-path]}]
  (let [dir (benchmark-paths/agent-score-dir suite case opts)
        expected-fingerprint (benchmark-prepare/case-fingerprint suite case)
        expected-agent-input-fingerprint (benchmark-prepare/agent-input-fingerprint
                                          suite
                                          case)
        expected-result-path (some-> result-path fs/canonical-path)
        expected-parser-worker-mode (:mode (benchmark-agent-packet/parser-worker-profile opts))
        parser-worker-match? (fn [score]
                               (or (not= "ygg" mode)
                                   (= expected-parser-worker-mode
                                      (get-in score [:parserWorker :mode]))))]
    (if-not (.isDirectory dir)
      []
      (->> (file-seq dir)
           (filter score-json-file?)
           (keep (fn [file]
                   (let [score (try
                                 (benchmark-io/read-json-file file)
                                 (catch Exception _
                                   nil))]
                     (when (and score
                                (= agent-score-schema (:schema score))
                                (= agent-result-schema (get-in score [:agent :schema]))
                                (= (:id case) (:case-id score))
                                (= expected-fingerprint (:caseFingerprint score))
                                (= expected-agent-input-fingerprint
                                   (get-in score [:agent :agentInputFingerprint]))
                                (= benchmark-agent-score/agent-result-contract-version
                                   (:agentResultContractVersion score))
                                (= agent-id (get-in score [:agent :agentId]))
                                (= mode (get-in score [:agent :mode]))
                                (= expected-result-path (:agentResultPath score))
                                (parser-worker-match? score))
                       (assoc score :agentScorePath (fs/canonical-path file))))))
           vec))))
(defn reusable-agent-score
  [suite case opts match]
  (let [matches (current-agent-score-artifacts suite case opts match)]
    (when (= 1 (count matches))
      (first matches))))
(defn- json-file?
  [file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".json")))
(defn- agent-score-files
  [suite case opts]
  (let [dir (benchmark-paths/agent-score-dir suite case opts)]
    (when (.isDirectory dir)
      (->> (file-seq dir)
           (filter json-file?)
           (sort-by #(.getPath %))
           vec))))
(defn agent-score-results
  [suite case opts]
  (let [expected-parser-worker-mode (benchmark-agent-packet/parser-worker-option opts)
        parser-worker-match? (fn [score]
                               (or (benchmark-util/blankish? expected-parser-worker-mode)
                                   (= expected-parser-worker-mode
                                      (get-in score [:parserWorker :mode]))))]
    (->> (agent-score-files suite case opts)
         (map benchmark-io/read-json-file)
         (filter #(or (benchmark-util/blankish? (:mode opts))
                      (= (:mode opts) (get-in % [:agent :mode]))))
         (filter #(or (benchmark-util/blankish? (:agent-id opts))
                      (= (:agent-id opts) (get-in % [:agent :agentId]))))
         (filter parser-worker-match?)
         vec)))
