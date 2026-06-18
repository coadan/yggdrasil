(ns agraph.text
  "Tokenization and lexical scoring."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def stopwords
  #{"a" "an" "and" "are" "as" "at" "be" "by" "for" "from" "how" "in" "is"
    "it" "of" "on" "or" "that" "the" "to" "what" "when" "where" "with"})

(def token-separator-pattern
  #"[^A-Za-z0-9*+?!<>=._/-]+")

(def compound-token-separator-pattern
  #"[._/-]+")

(def ^:dynamic *max-tokenize-chars*
  "Maximum characters considered for lexical token extraction.

  Large generated/resource files can otherwise spend unbounded time in regex
  tokenization while contributing little useful retrieval signal. The sample is
  taken from both ends of the text so file headers and trailing declarations
  remain searchable."
  200000)

(defn- bounded-token-text
  [text]
  (let [text (str text)
        limit (long *max-tokenize-chars*)]
    (if (or (not (pos? limit))
            (<= (count text) limit))
      text
      (let [edge (max 1 (quot limit 2))
            tail-start (max edge (- (count text) edge))]
        (str (subs text 0 edge)
             "\n"
             (subs text tail-start))))))

(defn- raw-tokens
  [text]
  (->> (str/split (bounded-token-text text) token-separator-pattern)
       (remove str/blank?)))

(defn- token-parts
  [token]
  (->> (str/split (str/lower-case token) compound-token-separator-pattern)
       (remove str/blank?)))

(defn- camel-token-parts
  [token]
  (-> token
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1 $2")
      (str/replace #"([a-z0-9])([A-Z])" "$1 $2")
      (str/split #"\s+")
      (->> (map str/lower-case)
           (remove str/blank?))))

(defn- camel-compound-parts
  [token]
  (->> (str/split token compound-token-separator-pattern)
       (mapcat camel-token-parts)
       (remove #(= (str/lower-case token) %))))

(defn- expanded-token
  [token]
  (let [token (str token)]
    (distinct (cons (str/lower-case token)
                    (concat (token-parts token)
                            (camel-compound-parts token))))))

(defn- keep-token?
  [token]
  (and (not (str/blank? token))
       (not (contains? stopwords token))))

(defn- distinct-vec
  [values]
  (let [seen (java.util.HashSet.)
        out (java.util.ArrayList.)]
    (doseq [value values]
      (when (.add seen value)
        (.add out value)))
    (vec out)))

(defn tokenize
  "Return lowercase lexical tokens from text."
  [text]
  (->> (raw-tokens text)
       (mapcat expanded-token)
       (filter keep-token?)
       distinct-vec))

(defn tokenize-all
  "Return lowercase lexical tokens from text, preserving frequency."
  [text]
  (->> (raw-tokens text)
       (mapcat expanded-token)
       (filter keep-token?)
       vec))

(defn token-score
  "Return overlap score between query and candidate token sets."
  [query-tokens candidate-tokens]
  (let [q (set query-tokens)
        c (set candidate-tokens)
        overlap (count (set/intersection q c))]
    (if (zero? overlap)
      0.0
      (double (+ overlap (/ overlap (max 1 (count c))))))))
