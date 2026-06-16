(ns agraph.env
  "Environment and .env helpers."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- parse-env-line
  [line]
  (let [trimmed (str/trim line)]
    (when-not (or (str/blank? trimmed)
                  (str/starts-with? trimmed "#"))
      (let [[k v] (str/split trimmed #"=" 2)]
        (when (and k v)
          [(str/trim k)
           (-> v
               str/trim
               (str/replace #"^['\"]|['\"]$" ""))])))))

(defn dotenv
  "Return key/value pairs from .env in the current working directory."
  []
  (let [file (io/file ".env")]
    (if (.exists file)
      (into {} (keep parse-env-line) (str/split-lines (slurp file)))
      {})))

(defn get-env
  "Return first non-blank environment or .env value for keys."
  [& keys]
  (let [env-file (dotenv)]
    (some (fn [k]
            (let [value (or (System/getenv k)
                            (get env-file k))]
              (when-not (str/blank? value)
                value)))
          keys)))
