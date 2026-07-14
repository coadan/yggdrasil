(ns ygg.filesystem-query-test
  (:require [ygg.filesystem-query :as filesystem-query]
            [ygg.ripgrep :as ripgrep]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util.concurrent CountDownLatch]))

(def project
  {:id "demo"
   :repos [{:id "app" :root "/tmp/app"}
           {:id "docs" :root "/tmp/docs"}]})

(deftest query-patterns-use-only-bounded-mechanical-inputs
  (is (= ["AUTH_URL" "AuthHandler" "src/auth_handler.clj" "handled" "where"]
         (filesystem-query/query-patterns
          "where is auth handled src/auth_handler.clj"
          {:symbols ["AuthHandler"]
           :literals ["AUTH_URL"]
           :pattern-limit 5})))
  (is (= ["x"]
         (filesystem-query/query-patterns "x" {}))))

(deftest search-project-emits-canonical-degraded-packet
  (let [calls (atom [])]
    (with-redefs [ripgrep/search-counts-many
                  (fn [root patterns paths opts]
                    (swap! calls conj {:root root
                                       :patterns patterns
                                       :paths paths
                                       :opts opts})
                    (if (= "/tmp/app" root)
                      {:elapsed-ms 7
                       :matches [{:path "src/auth.clj" :count 4}
                                 {:path "src/routes.clj" :count 1}]
                       :match-count 5
                       :file-count 2
                       :diagnostics []}
                      {:elapsed-ms 3
                       :matches [{:path "auth.md" :count 2}]
                       :match-count 2
                       :file-count 1
                       :diagnostics [{:kind :stdout-truncated}]}))]
      (let [{:keys [rows packet]}
            (filesystem-query/search-project
             project
             "where is auth handled"
             {:retriever :auto
              :reason :active-indexing
              :message "Indexing is active; using filesystem search."
              :operation {:op "sync" :projectId "demo"}
              :query-input {:task :locate}})]
        (is (= 2 (count @calls)))
        (is (every? #(= ["handled" "where" "auth"] (:patterns %)) @calls))
        (is (= ["src/auth.clj" "auth.md" "src/routes.clj"]
               (mapv :path rows)))
        (is (= filesystem-query/schema (:schema packet)))
        (is (= {:status :limited :degraded true} (:basis packet)))
        (is (= {:requested :auto
                :effective :filesystem
                :fallback? true
                :reason :active-indexing}
               (:retrieval packet)))
        (is (= :active-indexing (get-in packet [:degradation :reason])))
        (is (= :filesystem (get-in packet [:degradation :fallback])))
        (is (= {:op "sync" :projectId "demo"}
               (get-in packet [:degradation :operation])))
        (is (= 2 (get-in packet [:search :instrumentation :filesystem-processes])))
        (is (= 10 (get-in packet [:search :instrumentation :filesystem-search-ms])))
        (is (= 7 (get-in packet
                         [:search :instrumentation :filesystem-slowest-repo-ms])))
        (is (= 7 (get-in packet [:search :instrumentation :filesystem-match-count])))
        (is (= {:stdout-truncated 1}
               (get-in packet
                       [:search :instrumentation :filesystem-diagnostic-kinds])))
        (is (true? (get-in packet
                           [:search :instrumentation :filesystem-incomplete?])))
        (is (= 2 (get-in packet [:search :instrumentation :filesystem-repos])))
        (is (some #(re-find #"results may be incomplete" %)
                  (:warnings packet)))
        (is (= ["src/auth.clj" "auth.md" "src/routes.clj"]
               (mapv :resolvedPath (:results packet))))))))

(deftest search-project-honors-repo-scope
  (let [roots (atom [])]
    (with-redefs [ripgrep/search-counts-many
                  (fn [root _ _ _]
                    (swap! roots conj root)
                    {:elapsed-ms 1
                     :matches []
                     :match-count 0
                     :file-count 0
                     :diagnostics []})]
      (let [result (filesystem-query/search-project
                    project
                    "needle"
                    {:repo-id "docs"
                     :reason :active-embedding
                     :message "Embedding is active; using filesystem search."})]
        (is (= ["/tmp/docs"] @roots))
        (testing "empty results remain a successful degraded response"
          (is (= [] (get-in result [:packet :results])))
          (is (= :filesystem
                 (get-in result [:packet :retrieval :effective])))
          (is (= :active-embedding
                 (get-in result [:packet :degradation :reason]))))))))

(deftest search-project-bounds-and-parallelizes-multi-repo-search
  (let [active (atom 0)
        max-active (atom 0)
        both-started (promise)]
    (with-redefs [ripgrep/search-counts-many
                  (fn [_root _patterns _paths _opts]
                    (let [current (swap! active inc)]
                      (swap! max-active max current)
                      (when (= 2 current)
                        (deliver both-started true))
                      @both-started
                      (swap! active dec)
                      {:elapsed-ms 5
                       :matches []
                       :match-count 0
                       :file-count 0
                       :diagnostics []}))]
      (let [packet (:packet (filesystem-query/search-project
                             project
                             "needle"
                             {:max-parallel-repos 2}))]
        (is (= 2 @max-active))
        (is (= 2 (get-in packet [:search :instrumentation :filesystem-processes])))
        (is (= 10 (get-in packet [:search :instrumentation :filesystem-search-ms])))
        (is (= 5 (get-in packet
                         [:search :instrumentation :filesystem-slowest-repo-ms])))))))

(deftest search-project-enforces-one-deadline-across-repository-batches
  (let [repos (mapv (fn [index]
                      {:id (str "repo-" index)
                       :root (str "/tmp/repo-" index)})
                    (range 6))
        started (CountDownLatch. 2)]
    (with-redefs [ripgrep/search-counts-many
                  (fn [_root _patterns _paths _opts]
                    (.countDown started)
                    (try
                      (Thread/sleep 250)
                      (catch InterruptedException _
                        (.interrupt (Thread/currentThread))))
                    {:elapsed-ms 1000
                     :matches []
                     :match-count 0
                     :file-count 0
                     :diagnostics []})]
      (let [started-ns (System/nanoTime)
            packet (:packet (filesystem-query/search-project
                             {:id "many-repos" :repos repos}
                             "needle"
                             {:max-parallel-repos 2
                              :timeout-ms 100}))
            elapsed-ms (/ (- (System/nanoTime) started-ns) 1000000.0)
            instrumentation (get-in packet [:search :instrumentation])]
        (is (zero? (.getCount started)))
        (is (< elapsed-ms 400.0))
        (is (< (:filesystem-total-ms instrumentation) 400))
        (is (= 2 (:filesystem-processes instrumentation)))
        (is (= 6 (:filesystem-repos instrumentation)))
        (is (<= 100 (:filesystem-search-ms instrumentation) 400))
        (is (= {:project-timeout 6}
               (:filesystem-diagnostic-kinds instrumentation)))
        (is (true? (:filesystem-incomplete? instrumentation)))
        (is (some #(re-find #"results may be incomplete" %)
                  (:warnings packet)))))))
