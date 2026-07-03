(ns ygg.embedding-client
  "Embedding provider resolution and server-owned client pooling."
  (:require [ygg.env :as env]
            [ygg.embedding.local :as local]
            [ygg.embedding.openai :as openai]
            [ygg.embedding.openrouter :as openrouter]
            [clojure.string :as str]))

(def ^:dynamic *client-pool*
  "Optional atom keyed by provider, model, and provider options. When bound,
  provider clients are reused and closed by the pool owner instead of the caller."
  nil)

(def supported-providers
  [:local :openrouter :openai])

(def ^:private supported-provider-set
  (set supported-providers))

(def default-remote-request-timeout-ms
  30000)

(def default-remote-max-retries
  1)

(defn- provider-keyword
  [value]
  (when-let [provider (some-> value str/trim not-empty keyword)]
    (when-not (supported-provider-set provider)
      (throw (ex-info "Unsupported embedding provider."
                      {:provider provider
                       :supported supported-providers})))
    provider))

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

(defn- parse-env-long
  [env-name default-value]
  (if-let [raw (some-> (env/get-env env-name) str/trim not-empty)]
    (try
      (Long/parseLong raw)
      (catch NumberFormatException ex
        (throw (ex-info "Embedding client environment value must be an integer."
                        {:env env-name
                         :value raw}
                        ex))))
    default-value))

(defn- remote-provider?
  [provider]
  (contains? #{:openrouter :openai} provider))

(defn- remote-client-defaults
  []
  {:request-timeout-ms (parse-env-long "YGG_EMBEDDING_REQUEST_TIMEOUT_MS"
                                       default-remote-request-timeout-ms)
   :max-retries (parse-env-long "YGG_EMBEDDING_MAX_RETRIES"
                                default-remote-max-retries)})

(defn- provider-client-opts
  [provider opts]
  (if (remote-provider? provider)
    (merge (remote-client-defaults) opts)
    opts))

(defn default-provider
  []
  (or (provider-keyword (env/get-env "YGG_EMBEDDING_PROVIDER"))
      (some (fn [provider]
              (when (provider-api-key provider)
                provider))
            [:openrouter :openai])
      :local))

(defn provider-client
  ([provider model] (provider-client provider model {}))
  ([provider model opts]
   (let [provider (keyword provider)
         opts (provider-client-opts provider opts)]
     (case provider
       :local (local/client {:model model})
       :openrouter (openrouter/client (merge {:model model} opts))
       :openai (openai/client (merge {:model model} opts))
       (throw (ex-info "Unsupported embedding provider."
                       {:provider provider
                        :supported [:local :openrouter :openai]}))))))

(defn missing-key-message
  [provider]
  (case provider
    :local (str "Local embeddings require sentence-transformers. "
                "Run `ygg embed setup`, or set YGG_LOCAL_EMBEDDING_COMMAND "
                "to a custom worker command.")
    :openrouter "Missing OpenRouter API key. Set YGG_OPENROUTER_API_KEY or OPENROUTER_API_KEY."
    :openai "Missing OpenAI API key. Set YGG_OPENAI_API_KEY or OPENAI_API_KEY."
    "Missing embedding provider API key."))

(def semantic-availability-schema
  "ygg.semantic-availability/v1")

(defn semantic-availability
  "Return the configured semantic retrieval availability for a query retriever.

  This is configuration availability only. Local worker runtime readiness and
  vector freshness are still reported by the query execution path."
  [retriever {:keys [provider model]}]
  (let [retriever (keyword (or retriever :auto))
        provider (keyword (or provider (default-provider)))
        model (or (some-> model str/trim not-empty)
                  (default-model provider))
        semantic-requested? (not= :lexical retriever)
        provider-configured? (boolean (provider-api-key provider))
        semantic-available? (and semantic-requested? provider-configured?)
        effective (cond
                    (= :lexical retriever) :lexical
                    (= :auto retriever) (if semantic-available? :hybrid :lexical)
                    :else retriever)
        status (cond
                 (= :lexical retriever) :not-requested
                 semantic-available? :available
                 (= :auto retriever) :lexical-fallback
                 :else :unavailable)
        reason (when (and semantic-requested?
                          (not provider-configured?))
                 :missing-provider-credentials)
        message (cond
                  semantic-available? nil
                  (= :lexical retriever) nil
                  (= :auto retriever)
                  (str (missing-key-message provider)
                       " Auto retrieval used lexical fallback.")
                  :else
                  (missing-key-message provider))]
    (cond-> {:schema semantic-availability-schema
             :requested retriever
             :effective effective
             :provider provider
             :model model
             :semanticAvailable (boolean semantic-available?)
             :status status}
      reason (assoc :reason reason)
      message (assoc :message message))))

(defn- client-key
  [provider model opts]
  [(keyword provider)
   (str model)
   (select-keys opts [:max-retries :request-timeout-ms])])

(defn- pooled-client-view
  [client]
  (dissoc client :close))

(defn pooled-client!
  [client-pool provider model opts]
  (let [k (client-key provider model opts)]
    (locking client-pool
      (pooled-client-view
       (or (get @client-pool k)
           (let [client (provider-client provider model opts)]
             (swap! client-pool assoc k client)
             client))))))

(defn client
  ([provider model] (client provider model {}))
  ([provider model opts]
   (let [provider (keyword provider)
         opts (provider-client-opts provider opts)]
     (if *client-pool*
       (pooled-client! *client-pool* provider model opts)
       (provider-client provider model opts)))))

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
  ([retriever provider model] (query-embedding-client retriever provider model {}))
  ([retriever provider model opts]
   (let [retriever (keyword retriever)
         status (semantic-availability retriever {:provider provider
                                                  :model model})
         provider (:provider status)
         model (:model status)]
     (cond
       (= :lexical retriever)
       nil

       (:semanticAvailable status)
       (client provider model opts)

       (= :auto retriever)
       nil

       :else
       (throw (ex-info (:message status)
                       (assoc status
                              :retriever retriever
                              :provider provider)))))))

(defn configured-query-client
  [retriever {:keys [provider model max-retries request-timeout-ms]}]
  (let [{:keys [provider model]} (semantic-availability retriever
                                                        {:provider provider
                                                         :model model})]
    (query-embedding-client retriever
                            provider
                            model
                            (cond-> {}
                              (some? max-retries)
                              (assoc :max-retries max-retries)
                              (some? request-timeout-ms)
                              (assoc :request-timeout-ms request-timeout-ms)))))
