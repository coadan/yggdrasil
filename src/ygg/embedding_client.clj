(ns ygg.embedding-client
  "Embedding provider resolution and server-owned client pooling."
  (:require [ygg.embedding.local :as local]
            [ygg.embedding.openai :as openai]
            [ygg.embedding.openrouter :as openrouter]
            [clojure.string :as str]))

(def ^:dynamic *client-pool*
  "Optional atom keyed by [provider model]. When bound, provider clients are
  reused and closed by the pool owner instead of the caller."
  nil)

(defn default-provider
  []
  :local)

(defn default-model
  [provider]
  (case provider
    :local (local/configured-model)
    :openrouter openrouter/default-model
    :openai openai/default-model
    (throw (ex-info "Unsupported embedding provider."
                    {:provider provider
                     :supported [:local :openrouter :openai]}))))

(defn provider-api-key
  [provider]
  (case provider
    :local true
    :openrouter (openrouter/api-key)
    :openai (openai/api-key)
    nil))

(defn provider-client
  [provider model]
  (case provider
    :local (local/client {:model model})
    :openrouter (openrouter/client {:model model})
    :openai (openai/client {:model model})
    (throw (ex-info "Unsupported embedding provider."
                    {:provider provider
                     :supported [:local :openrouter :openai]}))))

(defn missing-key-message
  [provider]
  (case provider
    :local (str "Local embeddings require sentence-transformers. "
                "Run `ygg embed setup`, or set YGG_LOCAL_EMBEDDING_COMMAND "
                "to a custom worker command.")
    :openrouter "Missing OpenRouter API key. Set YGG_OPENROUTER_API_KEY or OPENROUTER_API_KEY."
    :openai "Missing OpenAI API key. Set YGG_OPENAI_API_KEY or OPENAI_API_KEY."
    "Missing embedding provider API key."))

(defn- client-key
  [provider model]
  [(keyword provider) (str model)])

(defn- pooled-client-view
  [client]
  (dissoc client :close))

(defn pooled-client!
  [client-pool provider model]
  (let [k (client-key provider model)]
    (locking client-pool
      (pooled-client-view
       (or (get @client-pool k)
           (let [client (provider-client provider model)]
             (swap! client-pool assoc k client)
             client))))))

(defn client
  [provider model]
  (if *client-pool*
    (pooled-client! *client-pool* provider model)
    (provider-client provider model)))

(defn close-client!
  [client]
  (when-let [close (:close client)]
    (close)))

(defn close-client-pool!
  [client-pool]
  (let [clients (vals @client-pool)]
    (doseq [client clients]
      (close-client! client))
    (reset! client-pool {})))

(defn query-embedding-client
  [retriever provider model]
  (let [retriever (keyword retriever)
        provider (keyword provider)
        model (or model (default-model provider))]
    (cond
      (= :lexical retriever)
      nil

      (provider-api-key provider)
      (client provider model)

      (= :auto retriever)
      nil

      :else
      (throw (ex-info (missing-key-message provider)
                      {:retriever retriever
                       :provider provider})))))

(defn configured-query-client
  [retriever {:keys [provider model]}]
  (let [provider (keyword (or provider (default-provider)))
        model (or (some-> model str/trim not-empty)
                  (default-model provider))]
    (query-embedding-client retriever provider model)))
