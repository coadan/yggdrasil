(ns ygg.benchmark-maintenance
  "Shared benchmark maintenance helpers for maintained-graph claim readiness."
  (:require [ygg.activity :as activity]
            [ygg.benchmark-agent-packet :as benchmark-agent-packet]
            [ygg.benchmark-agent-score :as benchmark-agent-score]
            [ygg.benchmark-paths :as benchmark-paths]
            [ygg.benchmark-prepare :as benchmark-prepare]
            [ygg.benchmark-util :as benchmark-util]
            [ygg.corrections :as corrections]
            [ygg.evidence :as evidence]
            [ygg.fs :as fs]
            [ygg.hash :as hash]
            [ygg.correction-overlay :as correction-overlay]
            [ygg.text :as text]
            [ygg.xtdb :as store]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- normalize-package-import
  [package-import]
  (cond-> package-import
    (:ecosystem package-import) (update :ecosystem name)))

(def ^:private package-import-required-fields
  [:import :ecosystem :package])

(defn- package-import-field
  [package-import field]
  (when-let [value (get package-import field)]
    (let [value (if (= :ecosystem field)
                  (name value)
                  (str value))]
      (when-not (benchmark-util/blankish? value)
        value))))

(defn- validate-package-import!
  [package-import]
  (let [missing-fields (filterv #(nil? (package-import-field package-import %))
                                package-import-required-fields)]
    (when (seq missing-fields)
      (throw (ex-info "Benchmark correction-overlay package import is missing required fields."
                      {:package-import package-import
                       :missing-fields missing-fields
                       :required-fields package-import-required-fields})))
    package-import))

(defn- package-import-key
  [package-import]
  [(package-import-field package-import :repo)
   (package-import-field package-import :import)
   (package-import-field package-import :ecosystem)
   (package-import-field package-import :package)])

(defn- seed-package-imports
  [overlay package-imports]
  (let [existing-keys (set (map package-import-key (:packageImports overlay)))]
    (first
     (reduce (fn [[out seen-keys] package-import]
               (let [package-import (-> package-import
                                        validate-package-import!
                                        normalize-package-import)
                     k (package-import-key package-import)]
                 (if (contains? seen-keys k)
                   [out seen-keys]
                   [(correction-overlay/add-package-import out package-import)
                    (conj seen-keys k)])))
             [overlay existing-keys]
             package-imports))))

(defn- seed-case-correction-overlay
  [overlay case]
  (let [package-imports (get-in case [:correction-overlay :packageImports])]
    (cond-> overlay
      (seq package-imports) (seed-package-imports package-imports))))

(defn prepare-agent-corrections!
  "Seed benchmark case overlay fixtures as canonical correction facts."
  [xtdb case prepared opts]
  (let [overlay (seed-case-correction-overlay
                 (correction-overlay/empty-overlay (:project-id prepared))
                 case)]
    (corrections/import-overlay! xtdb
                                 (:project-id prepared)
                                 overlay
                                 {:source "benchmark-fixture"
                                  :now (:now-ms opts)})))

(defn prepare-agent-overlay!
  "Seed benchmark case correction facts and return the resolved overlay."
  [xtdb case prepared opts]
  (prepare-agent-corrections! xtdb case prepared opts)
  (corrections/overlay xtdb (:project-id prepared)))

(defn sync-inspect-summary
  "Return a bounded sync/check-equivalent evidence summary for one benchmark case."
  [xtdb suite case prepared opts]
  (let [project (benchmark-agent-packet/agent-project prepared)
        config-path (fs/canonical-path (benchmark-paths/agent-project-path suite case opts))]
    (try
      (assoc (select-keys (evidence/summarize xtdb
                                              project
                                              {:config-path config-path
                                               :correction-overlay (corrections/overlay
                                                                    xtdb
                                                                    (:id project))})
                          [:schema
                           :project-id
                           :freshness
                           :families
                           :counts
                           :nextActions])
             :status "completed")
      (catch Exception e
        {:schema "ygg.benchmark.sync-inspect/v1"
         :status "failed"
         :project-id (:id project)
         :error (.getMessage e)}))))

(defn- benchmark-activity-source
  [agent-result opts]
  (keyword (str "benchmark-"
                (benchmark-paths/safe-id (or (:agentId agent-result)
                                             (:agent-id opts)
                                             "agent")))))

(defn- benchmark-activity-source-id
  [prepared agent-result result-file]
  (str "benchmark-agent-result:"
       (hash/short-hash [(:case-id prepared)
                         (:agentInputFingerprint agent-result)
                         (:agentId agent-result)
                         (fs/canonical-path result-file)])))

(defn- benchmark-activity-status
  [run-status]
  (if (= "failed" (str run-status)) :failed :done))

(defn- benchmark-activity-summary
  [prepared agent-result run-status]
  (str/join " "
            (remove benchmark-util/blankish?
                    ["benchmark-agent-result"
                     (:case-id prepared)
                     (:agentId agent-result)
                     (:mode agent-result)
                     (str "status=" run-status)
                     (str "result-schema=" (:schema agent-result))])))

(defn benchmark-activity-rows
  "Return activity rows that record benchmark result-schema validation only."
  [prepared agent-result result-file opts run-status now-ms]
  (let [source-id (benchmark-activity-source-id prepared agent-result result-file)
        item-id (str "activity-item:" (hash/short-hash [source-id]))
        run-id (str "activity-run:" (hash/short-hash [(:project-id prepared)
                                                      source-id
                                                      now-ms]))
        summary (benchmark-activity-summary prepared agent-result run-status)
        status (benchmark-activity-status run-status)
        source (benchmark-activity-source agent-result opts)
        target-ids (->> [(:project-id prepared)
                         (:case-id prepared)
                         (:caseFingerprint prepared)
                         (:agentInputFingerprint agent-result)]
                        (remove benchmark-util/blankish?)
                        distinct
                        vec)
        item {:xt/id item-id
              :schema activity/item-schema
              :project-id (:project-id prepared)
              :source source
              :source-id source-id
              :source-path (fs/canonical-path result-file)
              :kind "benchmark-agent-result"
              :status status
              :payload-schema benchmark-prepare/prepared-case-schema
              :expected-result-schema benchmark-agent-score/agent-result-schema
              :result-schema (:schema agent-result)
              :target-ids target-ids
              :summary summary
              :tokens (text/tokenize summary)
              :created-at-ms now-ms
              :updated-at-ms now-ms
              :completed-at-ms now-ms
              :active? true
              :run-id run-id}
        event {:xt/id (str "activity-event:"
                           (hash/short-hash [source-id :validation now-ms]))
               :schema activity/event-schema
               :project-id (:project-id prepared)
               :source source
               :source-id source-id
               :item-id item-id
               :event-kind :validation
               :status status
               :agent-id (str (:agentId agent-result))
               :target-ids target-ids
               :summary (str "benchmark result-schema "
                             (if (= benchmark-agent-score/agent-result-schema
                                    (:schema agent-result))
                               "matching"
                               "mismatch"))
               :at-ms now-ms
               :active? true
               :run-id run-id}]
    {:source source
     :items [item]
     :events [event]}))

(defn record-benchmark-agent-activity!
  "Commit benchmark result-schema validation activity and return sync-inspect."
  [xtdb suite case prepared opts agent-result result-file run-status]
  (let [{:keys [source items events]}
        (benchmark-activity-rows prepared
                                 agent-result
                                 result-file
                                 opts
                                 run-status
                                 (long (or (:now-ms opts)
                                           (System/currentTimeMillis))))
        activity-result (activity/commit-activity! xtdb
                                                   {:project-id (:project-id prepared)
                                                    :source source
                                                    :items items
                                                    :events events})]
    {:activity activity-result
     :syncInspect (sync-inspect-summary xtdb suite case prepared opts)}))

(defn record-benchmark-agent-activity-from-artifacts!
  "Commit benchmark activity for an existing case XTDB store when artifacts exist."
  [suite case prepared opts agent-result result-file run-status]
  (let [xtdb-dir (benchmark-paths/xtdb-dir suite case opts)]
    (when (.exists (io/file xtdb-dir))
      (try
        (store/with-node xtdb-dir
          (fn [xtdb]
            (prepare-agent-corrections! xtdb case prepared opts)
            (record-benchmark-agent-activity! xtdb
                                              suite
                                              case
                                              prepared
                                              opts
                                              agent-result
                                              result-file
                                              run-status)))
        (catch Exception _
          nil)))))
