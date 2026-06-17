(ns demo.api
  (:require [demo.core :as core]))

(def projection-gateway-url "PROJECTION_GATEWAY_URL")
(def billing-api-url "https://api.stripe.com/v1/customers")
(def placeholder-api-url "https://api.example.com/not-runtime")

(defn greet
  [name]
  (str "hello " (core/normalize-name name) " via " projection-gateway-url " and " billing-api-url))
