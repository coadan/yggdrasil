(ns ygg.embedding.openrouter
  "OpenRouter embeddings HTTP client."
  (:require [ygg.env :as env]
            [charred.api :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def default-base-url "https://openrouter.ai/api/v1")
(def default-model "openai/text-embedding-3-small")
(def default-request-timeout-ms
  60000)

(defn api-key
  "Return the configured OpenRouter API key, without logging or exposing it."
  []
  (env/get-env "YGG_OPENROUTER_API_KEY" "OPENROUTER_API_KEY" "OPEN_ROUTER_API_KEY"))

(defn- embeddings-url
  [base-url]
  (str (or base-url default-base-url) "/embeddings"))

(defn- request-body
  [model inputs]
  (json/write-json-str {"model" model
                        "input" (vec inputs)
                        "encoding_format" "float"}))

(defn- preview-body
  [body]
  (subs (str body) 0 (min 500 (count (str body)))))

(defn- embedding-data
  [body]
  (let [data (json/read-json body)]
    (when-let [error (get data "error")]
      (let [body-preview (preview-body body)]
        (throw (ex-info (str "OpenRouter embeddings request failed: "
                             body-preview)
                        {:status 200
                         :error error
                         :body-preview body-preview}))))
    (->> (get data "data")
         (sort-by #(get % "index"))
         (mapv #(get % "embedding")))))

(defn- retryable-status?
  [status]
  (or (= 429 status)
      (= 529 status)
      (<= 500 status 599)))

(defn- sleep-backoff!
  [attempt]
  (Thread/sleep (long (min 8000 (* 500 (Math/pow 2 attempt))))))

(defn embed-batch
  "Return embedding vectors for input strings using OpenRouter embeddings."
  [{:keys [api-key base-url model http-client max-retries request-timeout-ms]
    :or {base-url default-base-url
         model default-model
         request-timeout-ms default-request-timeout-ms
         max-retries 5}}
   inputs]
  (when-not (seq inputs)
    [])
  (when-not api-key
    (throw (ex-info "Missing OpenRouter API key. Set YGG_OPENROUTER_API_KEY or OPENROUTER_API_KEY."
                    {:provider :openrouter})))
  (let [client (or http-client (HttpClient/newHttpClient))
        request (fn []
                  (-> (HttpRequest/newBuilder (URI/create (embeddings-url base-url)))
                      (.timeout (Duration/ofMillis (long request-timeout-ms)))
                      (.header "Authorization" (str "Bearer " api-key))
                      (.header "Content-Type" "application/json")
                      (.POST (HttpRequest$BodyPublishers/ofString
                              (request-body model inputs)))
                      (.build)))]
    (loop [attempt 0]
      (let [response (.send client (request) (HttpResponse$BodyHandlers/ofString))
            status (.statusCode response)
            body (.body response)]
        (cond
          (<= 200 status 299)
          (embedding-data body)

          (and (retryable-status? status) (< attempt max-retries))
          (do
            (sleep-backoff! attempt)
            (recur (inc attempt)))

          :else
          (let [body-preview (preview-body body)]
            (throw (ex-info (str "OpenRouter embeddings request failed: "
                                 body-preview)
                            {:status status
                             :body-preview body-preview}))))))))

(defn client
  "Return an OpenRouter embedding client map."
  ([] (client {}))
  ([{:keys [base-url model http-client max-retries request-timeout-ms] :as opts
     :or {model default-model}}]
   (let [configured-api-key (:api-key opts)]
     {:provider :openrouter
      :model model
      :embed-batch (fn [inputs]
                     (embed-batch {:api-key (or configured-api-key (api-key))
                                   :base-url base-url
                                   :model model
                                   :http-client http-client
                                   :max-retries max-retries
                                   :request-timeout-ms request-timeout-ms}
                                  inputs))})))
