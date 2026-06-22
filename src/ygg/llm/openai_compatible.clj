(ns ygg.llm.openai-compatible
  "Small OpenAI-compatible chat client for structured graph classification."
  (:require [ygg.env :as env]
            [charred.api :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def default-base-urls
  {:deepseek "https://api.deepseek.com"
   :openrouter "https://openrouter.ai/api/v1"
   :openai "https://api.openai.com/v1"})

(def default-models
  {:deepseek "deepseek-chat"
   :openrouter "deepseek/deepseek-chat"
   :openai "gpt-4.1-mini"})

(defn api-key
  [provider]
  (case (keyword provider)
    :deepseek (env/get-env "YGG_DEEPSEEK_API_KEY"
                           "DEEPSEEK_API_KEY")
    :openrouter (env/get-env "YGG_OPENROUTER_API_KEY"
                             "OPENROUTER_API_KEY"
                             "OPEN_ROUTER_API_KEY")
    :openai (env/get-env "YGG_OPENAI_API_KEY" "OPENAI_API_KEY")
    nil))

(defn default-model
  [provider]
  (get default-models (keyword provider)))

(defn- chat-url
  [provider base-url]
  (str (or base-url (get default-base-urls (keyword provider))) "/chat/completions"))

(defn- request-body
  [model messages]
  (json/write-json-str {"model" model
                        "messages" (mapv (fn [{:keys [role content]}]
                                           {"role" role
                                            "content" content})
                                         messages)
                        "temperature" 0
                        "response_format" {"type" "json_object"}}))

(defn- preview-body
  [body]
  (subs (str body) 0 (min 500 (count (str body)))))

(defn- retryable-status?
  [status]
  (or (= 429 status)
      (= 529 status)
      (<= 500 status 599)))

(defn- sleep-backoff!
  [attempt]
  (Thread/sleep (long (min 8000 (* 500 (Math/pow 2 attempt))))))

(defn complete-json
  [{configured-api-key :api-key
    :keys [provider base-url model http-client max-retries]
    :or {provider :deepseek
         max-retries 5}}
   messages]
  (let [provider (keyword provider)
        model (or model (default-model provider))
        api-key-value (or configured-api-key (api-key provider))]
    (when-not api-key-value
      (throw (ex-info "Missing LLM API key."
                      {:provider provider
                       :env ["YGG_DEEPSEEK_API_KEY"
                             "DEEPSEEK_API_KEY"
                             "YGG_OPENROUTER_API_KEY"
                             "OPENROUTER_API_KEY"
                             "YGG_OPENAI_API_KEY"
                             "OPENAI_API_KEY"]})))
    (let [client (or http-client (HttpClient/newHttpClient))
          request (fn []
                    (-> (HttpRequest/newBuilder (URI/create (chat-url provider base-url)))
                        (.timeout (Duration/ofSeconds 120))
                        (.header "Authorization" (str "Bearer " api-key-value))
                        (.header "Content-Type" "application/json")
                        (.POST (HttpRequest$BodyPublishers/ofString
                                (request-body model messages)))
                        (.build)))]
      (loop [attempt 0]
        (let [response (.send client (request) (HttpResponse$BodyHandlers/ofString))
              status (.statusCode response)
              body (.body response)]
          (cond
            (<= 200 status 299)
            (let [parsed (json/read-json body :key-fn keyword)
                  content (get-in parsed [:choices 0 :message :content])]
              (when-not content
                (throw (ex-info "LLM response did not contain message content."
                                {:provider provider
                                 :model model
                                 :body-preview (preview-body body)})))
              (json/read-json content :key-fn keyword))

            (and (retryable-status? status) (< attempt max-retries))
            (do
              (sleep-backoff! attempt)
              (recur (inc attempt)))

            :else
            (throw (ex-info "LLM request failed."
                            {:provider provider
                             :model model
                             :status status
                             :body-preview (preview-body body)}))))))))

(defn client
  ([] (client {}))
  ([{:keys [provider model base-url api-key http-client max-retries]
     :or {provider :deepseek}}]
   (let [provider (keyword provider)
         model (or model (default-model provider))]
     {:provider provider
      :model model
      :complete-json (fn [messages]
                       (complete-json {:provider provider
                                       :api-key api-key
                                       :base-url base-url
                                       :model model
                                       :http-client http-client
                                       :max-retries max-retries}
                                      messages))})))
