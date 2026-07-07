(ns ygg.benchmark-preflight-test
  (:require [ygg.benchmark-preflight :as benchmark-preflight]
            [clojure.test :refer [deftest is]]))

(deftest benchmark-preflight-prefers-sync-inspect-family-status
  (let [preflight (benchmark-preflight/benchmark-preflight
                   {:index-summary {:status "completed"}
                    :system-summary {:status "completed"}
                    :graph-expectations {:status "passed"
                                         :summary {:expectedNodes 1
                                                   :missingNodes 0}}
                    :expectations {:nodes [{:kind :namespace
                                            :path "src/app.clj"}]}
                    :hints {:architecture {:validationGaps []}}
                    :sync-inspect {:families [{:family :dependencies
                                               :status :weak
                                               :counts {:packages 3
                                                        :unresolved-imports 2}
                                               :diagnostics [{:reason :candidate-unresolved
                                                              :count 2
                                                              :blocking true
                                                              :message "Source import candidates were extracted, but some did not resolve to package facts."}]}
                                              {:family :activity
                                               :status :available
                                               :counts {:activity-items 1
                                                        :activity-events 1}}
                                              {:family :validation-history
                                               :status :missing
                                               :counts {:validation-events 0}}
                                              {:family :correction-overlay
                                               :status :missing
                                               :counts {:systems 0
                                                        :docs 0
                                                        :edges 0
                                                        :rejects 0}}]
                                   :nextActions [{:kind :dependency-review
                                                  :command "ygg sync project.edn --check --enqueue"}
                                                 {:kind :validation-history
                                                  :command "ygg sync work validate <work-id>"}
                                                 {:kind :correction-overlay
                                                  :command "ygg sync init project.edn"}]}})
        sync-check (get-in preflight [:checks :syncCheck])]
    (is (= "failed" (:status preflight)))
    (is (= "sync-inspect" (:source sync-check)))
    (is (= [{:plane "dependencies"
             :status "weak"
             :counts {:packages 3
                      :unresolved-imports 2}
             :diagnostics [{:reason :candidate-unresolved
                            :count 2
                            :blocking true
                            :message "Source import candidates were extracted, but some did not resolve to package facts."}]
             :nextActions [{:kind :dependency-review
                            :command "ygg sync project.edn --check --enqueue"}]}
            {:plane "validation-history"
             :status "missing"
             :counts {:validation-events 0}
             :nextActions [{:kind :validation-history
                            :command "ygg sync work validate <work-id>"}]}
            {:plane "correction-overlay"
             :status "missing"
             :counts {:systems 0
                      :docs 0
                      :edges 0
                      :rejects 0}
             :nextActions [{:kind :correction-overlay
                            :command "ygg sync init project.edn"}]}]
           (:blockingValidationGaps sync-check)))))

(deftest benchmark-preflight-blocks-stale-sync-inspect-freshness
  (let [preflight (benchmark-preflight/benchmark-preflight
                   {:index-summary {:status "completed"}
                    :system-summary {:status "completed"}
                    :graph-expectations {:status "passed"}
                    :hints {:architecture {:validationGaps []}}
                    :sync-inspect {:freshness {:status :stale
                                               :counts {:indexed 1
                                                        :current 1
                                                        :changed 0
                                                        :missing 0
                                                        :unindexed 0
                                                        :upstream-stale 1}
                                               :repos [{:repo-id "app"
                                                        :status :stale
                                                        :git-state {:git-branch "main"
                                                                    :git-upstream "origin/main"
                                                                    :git-upstream-status :behind
                                                                    :git-upstream-current? false
                                                                    :git-ahead 0
                                                                    :git-behind 2}}]}
                                   :families []
                                   :nextActions [{:kind :freshness
                                                  :command "ygg sync project.edn --check"}]}})
        sync-check (get-in preflight [:checks :syncCheck])]
    (is (= "failed" (:status preflight)))
    (is (= "failed" (:status sync-check)))
    (is (= "sync-inspect" (:source sync-check)))
    (is (= [{:plane "freshness"
             :status "stale"
             :counts {:indexed 1
                      :current 1
                      :changed 0
                      :missing 0
                      :unindexed 0
                      :upstream-stale 1}
             :repos [{:repo-id "app"
                      :status :stale
                      :git-state {:git-branch "main"
                                  :git-upstream "origin/main"
                                  :git-upstream-status :behind
                                  :git-upstream-current? false
                                  :git-ahead 0
                                  :git-behind 2}}]
             :nextActions [{:kind :freshness
                            :command "ygg sync project.edn --check"}]}]
           (:blockingValidationGaps sync-check)))))

(deftest benchmark-preflight-allows-patch-result-freshness-diff
  (let [preflight (benchmark-preflight/benchmark-preflight
                   {:index-summary {:status "completed"}
                    :system-summary {:status "completed"}
                    :graph-expectations {:status "passed"}
                    :hints {:architecture {:validationGaps []}}
                    :allow-patch-freshness? true
                    :sync-inspect {:freshness {:status :stale
                                               :counts {:indexed 2
                                                        :current 2
                                                        :changed 1
                                                        :missing 0
                                                        :unindexed 0
                                                        :upstream-stale 0}
                                               :repos [{:repo-id "app"
                                                        :status :stale
                                                        :samples {:changed [{:repo-id "app"
                                                                             :path "src/app.clj"}]}}]}
                                   :families [{:family :dependencies
                                               :status :available
                                               :counts {:packages 1
                                                        :unresolved-imports 0}}
                                              {:family :activity
                                               :status :available
                                               :counts {:activity-items 1
                                                        :activity-events 1}}
                                              {:family :validation-history
                                               :status :available
                                               :counts {:validation-events 1}}]
                                   :nextActions [{:kind :freshness
                                                  :command "ygg sync project.edn --check"}]}})
        sync-check (get-in preflight [:checks :syncCheck])]
    (is (= "passed" (:status preflight)))
    (is (= "passed" (:status sync-check)))
    (is (= true (:patchFreshnessAllowed sync-check)))
    (is (= [] (:blockingValidationGaps sync-check)))
    (is (= [{:plane "freshness"
             :status "stale"
             :counts {:indexed 2
                      :current 2
                      :changed 1
                      :missing 0
                      :unindexed 0
                      :upstream-stale 0}
             :repos [{:repo-id "app"
                      :status :stale
                      :samples {:changed [{:repo-id "app"
                                           :path "src/app.clj"}]}}]
             :nextActions [{:kind :freshness
                            :command "ygg sync project.edn --check"}]}]
           (:allowedValidationGaps sync-check)))))

(deftest benchmark-preflight-keeps-blocking-families-when-patch-freshness-is-allowed
  (let [preflight (benchmark-preflight/benchmark-preflight
                   {:index-summary {:status "completed"}
                    :system-summary {:status "completed"}
                    :graph-expectations {:status "passed"}
                    :hints {:architecture {:validationGaps []}}
                    :allow-patch-freshness? true
                    :sync-inspect {:freshness {:status :stale
                                               :counts {:indexed 2
                                                        :current 2
                                                        :changed 1
                                                        :missing 0
                                                        :unindexed 0
                                                        :upstream-stale 0}}
                                   :families [{:family :dependencies
                                               :status :weak
                                               :counts {:packages 1
                                                        :unresolved-imports 1}}]
                                   :nextActions [{:kind :dependency-review
                                                  :command "ygg packages --json"}
                                                 {:kind :freshness
                                                  :command "ygg sync project.edn --check"}]}})
        sync-check (get-in preflight [:checks :syncCheck])]
    (is (= "failed" (:status preflight)))
    (is (= "failed" (:status sync-check)))
    (is (= true (:patchFreshnessAllowed sync-check)))
    (is (= [{:plane "dependencies"
             :status "weak"
             :counts {:packages 1
                      :unresolved-imports 1}
             :nextActions [{:kind :dependency-review
                            :command "ygg packages --json"}]}]
           (:blockingValidationGaps sync-check)))))

(deftest claim-readiness-requires-passed-benchmark-preflight
  (is (true? (benchmark-preflight/claim-ready? {:status "passed"})))
  (doseq [status ["failed" "not-run" "not-configured" nil]]
    (is (false? (benchmark-preflight/claim-ready? {:status status}))))
  (is (= {:benchmarkPreflight {:status "failed"}
          :claimReady false}
         (benchmark-preflight/assoc-run-preflight {} {:status "failed"})))
  (is (= {:benchmarkPreflight {:status "passed"}
          :claimReady true}
         (benchmark-preflight/assoc-run-preflight {} {:status "passed"})))
  (is (= {}
         (benchmark-preflight/assoc-run-preflight {} nil))))
