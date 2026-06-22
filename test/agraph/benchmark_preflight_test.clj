(ns agraph.benchmark-preflight-test
  (:require [agraph.benchmark-preflight :as benchmark-preflight]
            [clojure.test :refer [deftest is]]))

(deftest maintenance-preflight-prefers-sync-inspect-family-status
  (let [preflight (benchmark-preflight/maintenance-preflight
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
                                              {:family :map-overlay
                                               :status :missing
                                               :counts {:systems 0
                                                        :docs 0
                                                        :edges 0
                                                        :rejects 0}}]
                                   :nextActions [{:kind :dependency-review
                                                  :command "agraph sync check project.edn --enqueue"}
                                                 {:kind :validation-history
                                                  :command "agraph sync work validate <work-id>"}
                                                 {:kind :map-overlay
                                                  :command "agraph sync init project.edn"}]}})
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
                            :command "agraph sync check project.edn --enqueue"}]}
            {:plane "validation-history"
             :status "missing"
             :counts {:validation-events 0}
             :nextActions [{:kind :validation-history
                            :command "agraph sync work validate <work-id>"}]}
            {:plane "map-overlay"
             :status "missing"
             :counts {:systems 0
                      :docs 0
                      :edges 0
                      :rejects 0}
             :nextActions [{:kind :map-overlay
                            :command "agraph sync init project.edn"}]}]
           (:blockingValidationGaps sync-check)))))

(deftest claim-readiness-requires-passed-maintenance-preflight
  (is (true? (benchmark-preflight/claim-ready? {:status "passed"})))
  (doseq [status ["failed" "not-run" "not-configured" nil]]
    (is (false? (benchmark-preflight/claim-ready? {:status status}))))
  (is (= {:maintenancePreflight {:status "failed"}
          :claimReady false}
         (benchmark-preflight/assoc-run-preflight {} {:status "failed"})))
  (is (= {:maintenancePreflight {:status "passed"}
          :claimReady true}
         (benchmark-preflight/assoc-run-preflight {} {:status "passed"})))
  (is (= {}
         (benchmark-preflight/assoc-run-preflight {} nil))))
