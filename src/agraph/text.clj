(ns agraph.text
  "Tokenization and lexical scoring."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def stopwords
  #{"a" "an" "and" "are" "as" "at" "be" "by" "for" "from" "how" "in" "is"
    "it" "of" "on" "or" "that" "the" "to" "what" "when" "where" "with"})

(defn tokenize
  "Return lowercase lexical tokens from text."
  [text]
  (->> (str/split (str/lower-case (str text)) #"[^a-z0-9*+?!<>=._/-]+")
       (remove str/blank?)
       (remove stopwords)
       distinct
       vec))

(defn tokenize-all
  "Return lowercase lexical tokens from text, preserving frequency."
  [text]
  (->> (str/split (str/lower-case (str text)) #"[^a-z0-9*+?!<>=._/-]+")
       (remove str/blank?)
       (remove stopwords)
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
