(ns ygg.llm.anthropic-compatible
  "Small Anthropic-compatible messages client for structured JSON completions."
  (:require [ygg.env :as env]
            [charred.api :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def default-base-urls
  {:deepseek "https://api.deepseek.com/anthropic"
   :openrouter "https://openrouter.ai/api"})

(def default-models
  {:deepseek "deepseek-v4-flash"
   :openrouter "deepseek/deepseek-v4-flash"})

(def default-version
  "2023-06-01")

(defn api-key
  [provider]
  (case (keyword provider)
    :deepseek (env/get-env "YGG_DEEPSEEK_API_KEY"
                           "DEEPSEEK_API_KEY")
    :openrouter (env/get-env "YGG_OPENROUTER_API_KEY"
                             "OPENROUTER_API_KEY"
                             "OPEN_ROUTER_API_KEY")
    nil))

(defn default-model
  [provider]
  (get default-models (keyword provider)))

(defn- messages-url
  [provider base-url]
  (str (str/replace (or base-url
                        (get default-base-urls (keyword provider)))
                    #"/+$"
                    "")
       "/v1/messages"))

(defn- role-name
  [role]
  (cond
    (keyword? role) (name role)
    (nil? role) nil
    :else (str role)))

(defn- split-system-messages
  [messages]
  (let [{system true ordinary false}
        (group-by #(= "system" (role-name (:role %))) messages)]
    {:system (not-empty (str/join "\n\n" (map :content system)))
     :messages (mapv (fn [{:keys [role content]}]
                       {"role" (role-name role)
                        "content" content})
                     ordinary)}))

(defn- request-body
  [{:keys [model max-tokens temperature]} messages]
  (let [{:keys [system messages]} (split-system-messages messages)]
    (json/write-json-str
     (cond-> {"model" model
              "max_tokens" (long (or max-tokens 1000))
              "messages" messages
              "temperature" (double (or temperature 0.0))}
       system (assoc "system" system)))))

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

(defn- response-text
  [parsed]
  (some (fn [block]
          (or (:text block)
              (when (string? block) block)))
        (:content parsed)))

(defn complete-json
  [{configured-api-key :api-key
    :keys [provider base-url model http-client max-retries max-tokens
           temperature anthropic-version]
    :or {provider :deepseek
         max-retries 5}}
   messages]
  (let [provider (keyword provider)
        model (or model (default-model provider))
        api-key-value (or configured-api-key (api-key provider))]
    (when-not api-key-value
      (throw (ex-info "Missing Anthropic-compatible API key."
                      {:provider provider
                       :env ["YGG_DEEPSEEK_API_KEY"
                             "DEEPSEEK_API_KEY"
                             "YGG_OPENROUTER_API_KEY"
                             "OPENROUTER_API_KEY"]})))
    (let [client (or http-client (HttpClient/newHttpClient))
          request (fn []
                    (-> (HttpRequest/newBuilder (URI/create (messages-url provider base-url)))
                        (.timeout (Duration/ofSeconds 120))
                        (.header "x-api-key" api-key-value)
                        (.header "anthropic-version" (or anthropic-version default-version))
                        (.header "Content-Type" "application/json")
                        (.POST (HttpRequest$BodyPublishers/ofString
                                (request-body {:model model
                                               :max-tokens max-tokens
                                               :temperature temperature}
                                              messages)))
                        (.build)))]
      (loop [attempt 0]
        (let [response (.send client (request) (HttpResponse$BodyHandlers/ofString))
              status (.statusCode response)
              body (.body response)]
          (cond
            (<= 200 status 299)
            (let [parsed (json/read-json body :key-fn keyword)
                  content (response-text parsed)]
              (when-not content
                (throw (ex-info "Anthropic-compatible response did not contain text content."
                                {:provider provider
                                 :model model
                                 :body-preview (preview-body body)})))
              (json/read-json content :key-fn keyword))

            (and (retryable-status? status) (< attempt max-retries))
            (do
              (sleep-backoff! attempt)
              (recur (inc attempt)))

            :else
            (throw (ex-info "Anthropic-compatible request failed."
                            {:provider provider
                             :model model
                             :status status
                             :body-preview (preview-body body)}))))))))

(defn client
  ([] (client {}))
  ([{:keys [provider model base-url api-key http-client max-retries max-tokens
            temperature anthropic-version]
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
                                       :max-retries max-retries
                                       :max-tokens max-tokens
                                       :temperature temperature
                                       :anthropic-version anthropic-version}
                                      messages))})))
