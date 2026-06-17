(ns agraph.system.decision-classifier
  "Focused classifier for one maintenance decision at a time."
  (:require [charred.api :as json]
            [clojure.string :as str]))

(def schema
  "agraph.maintenance.classification/v1")

(defn prompt
  "Return OpenAI-compatible chat messages for one maintenance decision."
  [decision]
  [{:role "system"
    :content (str "You classify one AGraph maintenance decision. Return JSON only. "
                  "Do not classify a whole repository. Use only the provided decision data. "
                  "Prefer hiding or rejecting noisy references over promoting weak edges.")}
   {:role "user"
    :content (str
              "Return this JSON shape:\n"
              "{"
              "\"schema\":\"" schema "\","
              "\"decisionId\":\"...\","
              "\"recommendation\":\"accept|reject|change|investigate\","
              "\"confidence\":0.0,"
              "\"reason\":\"brief rationale\","
              "\"mapPatch\":[{\"op\":\"set-edge-visibility|reject-external-api|set-system-kind|add-edge|none\","
              "\"target\":\"id or label\",\"value\":{},\"reason\":\"brief rationale\"}]"
              "}\n\n"
              (json/write-json-str {:decision decision} {:indent-str "  "}))}])

(defn classify
  "Classify one maintenance decision with an OpenAI-compatible JSON client."
  [{:keys [client decision]}]
  (when-not decision
    (throw (ex-info "Missing maintenance decision." {})))
  (let [response ((:complete-json client) (prompt decision))]
    (assoc response
           :schema (or (:schema response) schema)
           :decisionId (or (:decisionId response) (:id decision)))))

(defn decision-by-id
  "Find a decision by id or by its shortest unique suffix."
  [decisions value]
  (let [value (str value)
        exact (first (filter #(= value (:id %)) decisions))]
    (or exact
        (let [matches (filter #(str/ends-with? (:id %) value) decisions)]
          (when (= 1 (count matches))
            (first matches))))))
