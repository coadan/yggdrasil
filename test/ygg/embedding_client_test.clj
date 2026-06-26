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
