(ns ygg.embedding-client-test
  (:require [ygg.embedding-client :as embedding-client]
            [ygg.embedding.openrouter :as openrouter]
            [ygg.env :as env]
            [clojure.test :refer [deftest is]]))

(defn- env-values
  [values]
  (fn [& keys]
    (some values keys)))

(deftest default-provider-prefers-openrouter-key-over-local
  (with-redefs [env/get-env (env-values {"YGG_OPENROUTER_API_KEY" "openrouter-key"})]
    (is (= :openrouter (embedding-client/default-provider)))))

(deftest explicit-embedding-provider-overrides-remote-key
  (with-redefs [env/get-env (env-values {"YGG_EMBEDDING_PROVIDER" "local"
                                         "YGG_OPENROUTER_API_KEY" "openrouter-key"})]
    (is (= :local (embedding-client/default-provider)))))

(deftest default-provider-falls-back-to-local-without-remote-key
  (with-redefs [env/get-env (env-values {})]
    (is (= :local (embedding-client/default-provider)))))

(deftest configured-query-client-uses-openrouter-default-when-key-exists
  (with-redefs [env/get-env (env-values {"YGG_OPENROUTER_API_KEY" "openrouter-key"})]
    (let [client (embedding-client/configured-query-client :auto {})]
      (is (= :openrouter (:provider client)))
      (is (= openrouter/default-model (:model client))))))

(deftest provider-client-uses-bounded-remote-defaults
  (with-redefs [env/get-env (env-values {})
                openrouter/client (fn [opts]
                                    {:provider :openrouter
                                     :opts opts
                                     :embed-batch (fn [_inputs] [])})]
    (let [client (embedding-client/provider-client
                  :openrouter
                  "openai/text-embedding-3-small")]
      (is (= {:model "openai/text-embedding-3-small"
              :request-timeout-ms 30000
              :max-retries 1}
             (:opts client))))))

(deftest provider-client-uses-remote-env-overrides
  (with-redefs [env/get-env (env-values {"YGG_EMBEDDING_REQUEST_TIMEOUT_MS" "9000"
                                         "YGG_EMBEDDING_MAX_RETRIES" "0"})
                openrouter/client (fn [opts]
                                    {:provider :openrouter
                                     :opts opts
                                     :embed-batch (fn [_inputs] [])})]
    (let [client (embedding-client/provider-client
                  :openrouter
                  "openai/text-embedding-3-small")]
      (is (= {:model "openai/text-embedding-3-small"
              :request-timeout-ms 9000
              :max-retries 0}
             (:opts client))))))

(deftest configured-query-client-passes-remote-provider-options
  (with-redefs [env/get-env (env-values {"YGG_OPENROUTER_API_KEY" "openrouter-key"})
                openrouter/client (fn [opts]
                                    {:provider :openrouter
                                     :model (:model opts)
                                     :opts opts
                                     :embed-batch (fn [_inputs] [])})]
    (let [client (embedding-client/configured-query-client
                  :semantic
                  {:provider "openrouter"
                   :model "openai/text-embedding-3-small"
                   :max-retries 1
                   :request-timeout-ms 30000})]
      (is (= {:model "openai/text-embedding-3-small"
              :max-retries 1
              :request-timeout-ms 30000}
             (:opts client))))))

(deftest pooled-client-key-includes-remote-provider-options
  (let [calls (atom [])
        next-id (atom 0)
        client-pool (atom {})]
    (with-redefs [env/get-env (env-values {"YGG_OPENROUTER_API_KEY" "openrouter-key"})
                  openrouter/client (fn [opts]
                                      (let [id (swap! next-id inc)]
                                        (swap! calls conj opts)
                                        {:provider :openrouter
                                         :model (:model opts)
                                         :id id
                                         :close (fn [])
                                         :embed-batch (fn [_inputs] [])}))]
      (binding [embedding-client/*client-pool* client-pool]
        (let [first-client (embedding-client/configured-query-client
                            :semantic
                            {:provider "openrouter"
                             :model "openai/text-embedding-3-small"
                             :max-retries 1
                             :request-timeout-ms 30000})
              reused-client (embedding-client/configured-query-client
                             :semantic
                             {:provider "openrouter"
                              :model "openai/text-embedding-3-small"
                              :max-retries 1
                              :request-timeout-ms 30000})
              shorter-timeout-client (embedding-client/configured-query-client
                                      :semantic
                                      {:provider "openrouter"
                                       :model "openai/text-embedding-3-small"
                                       :max-retries 1
                                       :request-timeout-ms 5000})]
          (is (= (:id first-client) (:id reused-client)))
          (is (not= (:id first-client) (:id shorter-timeout-client)))
          (is (= [{:model "openai/text-embedding-3-small"
                   :max-retries 1
                   :request-timeout-ms 30000}
                  {:model "openai/text-embedding-3-small"
                   :max-retries 1
                   :request-timeout-ms 5000}]
                 @calls)))))))
