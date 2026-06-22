(ns ygg.schema
  "Malli schemas for graph records."
  (:require [malli.core :as m]))

(defonce ^:private validators
  (atom {}))

(defn- validator
  [schema]
  (if-let [validate (@validators schema)]
    validate
    (let [validate (m/validator schema)]
      (get (swap! validators assoc schema validate) schema))))

(def file-row
  [:map
   [:xt/id string?]
   [:project-id {:optional true} string?]
   [:repo-id {:optional true} string?]
   [:repo-root {:optional true} string?]
   [:repo-role {:optional true} keyword?]
   [:path string?]
   [:ext string?]
   [:kind keyword?]
   [:content-sha string?]
   [:extractor-fingerprint {:optional true} string?]
   [:mtime-ms int?]
   [:size-bytes int?]
   [:active? boolean?]
   [:run-id {:optional true} string?]])

(def node-row
  [:map
   [:xt/id string?]
   [:project-id {:optional true} string?]
   [:repo-id {:optional true} string?]
   [:label string?]
   [:kind keyword?]
   [:file-id string?]
   [:path string?]
   [:ecosystem {:optional true} keyword?]
   [:package-name {:optional true} string?]
   [:version-range {:optional true} string?]
   [:resolved-version {:optional true} string?]
   [:dependency-scope {:optional true} [:or keyword? string?]]
   [:import-names {:optional true} [:vector string?]]
   [:namespace {:optional true} string?]
   [:name {:optional true} string?]
   [:public? {:optional true} boolean?]
   [:source-line {:optional true} int?]
   [:tokens {:optional true} [:vector string?]]
   [:active? boolean?]
   [:run-id string?]])

(def edge-row
  [:map
   [:xt/id string?]
   [:project-id {:optional true} string?]
   [:repo-id {:optional true} string?]
   [:source-id string?]
   [:target-id string?]
   [:relation keyword?]
   [:confidence keyword?]
   [:ecosystem {:optional true} keyword?]
   [:package-name {:optional true} string?]
   [:version-range {:optional true} string?]
   [:resolved-version {:optional true} string?]
   [:dependency-scope {:optional true} [:or keyword? string?]]
   [:import-name {:optional true} string?]
   [:import-kind {:optional true} keyword?]
   [:resolution-source {:optional true} keyword?]
   [:file-id string?]
   [:path string?]
   [:source-line {:optional true} int?]
   [:active? boolean?]
   [:run-id string?]])

(def chunk-row
  [:map
   [:xt/id string?]
   [:project-id {:optional true} string?]
   [:repo-id {:optional true} string?]
   [:file-id string?]
   [:path string?]
   [:kind keyword?]
   [:definition-kind {:optional true} keyword?]
   [:label string?]
   [:text string?]
   [:tokens [:vector string?]]
   [:heading-path {:optional true} [:vector string?]]
   [:content-sha {:optional true} string?]
   [:source-line {:optional true} int?]
   [:end-line {:optional true} int?]
   [:active? boolean?]
   [:run-id string?]])

(def file-fact-row
  [:map
   [:xt/id string?]
   [:project-id {:optional true} string?]
   [:repo-id {:optional true} string?]
   [:file-id string?]
   [:path string?]
   [:file-kind {:optional true} keyword?]
   [:kind keyword?]
   [:url-context {:optional true} keyword?]
   [:auth-context {:optional true} keyword?]
   [:label string?]
   [:normalized-value string?]
   [:source-line int?]
   [:confidence number?]
   [:active? boolean?]
   [:run-id string?]])

(def diagnostic-row
  [:map
   [:xt/id string?]
   [:project-id {:optional true} string?]
   [:repo-id {:optional true} string?]
   [:file-id string?]
   [:path string?]
   [:stage keyword?]
   [:message string?]
   [:active? boolean?]
   [:run-id string?]])

(def search-doc-row
  [:map
   [:xt/id string?]
   [:project-id {:optional true} string?]
   [:repo-id {:optional true} string?]
   [:target-id string?]
   [:target-kind keyword?]
   [:file-id string?]
   [:path string?]
   [:kind keyword?]
   [:definition-kind {:optional true} keyword?]
   [:label string?]
   [:text string?]
   [:tokens [:vector string?]]
   [:input-sha string?]
   [:heading-path {:optional true} [:vector string?]]
   [:content-sha {:optional true} string?]
   [:source-line {:optional true} int?]
   [:end-line {:optional true} int?]
   [:active? boolean?]
   [:run-id string?]])

(def embedding-row
  [:map
   [:xt/id string?]
   [:project-id {:optional true} string?]
   [:repo-id {:optional true} string?]
   [:target-id string?]
   [:provider keyword?]
   [:model string?]
   [:dims int?]
   [:input-sha string?]
   [:vector [:vector number?]]
   [:created-at-ms int?]
   [:active? boolean?]])

(def project-row
  [:map
   [:xt/id string?]
   [:project-id string?]
   [:name string?]
   [:active? boolean?]
   [:updated-at-ms int?]])

(def repo-row
  [:map
   [:xt/id string?]
   [:project-id string?]
   [:repo-id string?]
   [:root string?]
   [:role keyword?]
   [:active? boolean?]
   [:updated-at-ms int?]])

(def system-evidence-row
  [:map
   [:xt/id string?]
   [:project-id string?]
   [:repo-id string?]
   [:system-id string?]
   [:file-id string?]
   [:path string?]
   [:file-kind {:optional true} keyword?]
   [:kind keyword?]
   [:url-context {:optional true} keyword?]
   [:auth-context {:optional true} keyword?]
   [:label string?]
   [:normalized-value string?]
   [:source-line int?]
   [:confidence number?]
   [:active? boolean?]
   [:run-id string?]])

(def system-node-row
  [:map
   [:xt/id string?]
   [:project-id string?]
   [:repo-id string?]
   [:system-key string?]
   [:label string?]
   [:kind keyword?]
   [:path {:optional true} string?]
   [:path-prefix {:optional true} string?]
   [:source {:optional true} keyword?]
   [:candidate-types {:optional true} [:vector keyword?]]
   [:evidence {:optional true} [:vector map?]]
   [:metrics {:optional true} map?]
   [:repo-role {:optional true} keyword?]
   [:aliases [:vector string?]]
   [:active? boolean?]
   [:run-id string?]])

(def system-edge-row
  [:map
   [:xt/id string?]
   [:project-id string?]
   [:source-id string?]
   [:target-id string?]
   [:relation keyword?]
   [:confidence number?]
   [:evidence-ids [:vector string?]]
   [:rules [:vector string?]]
   [:active? boolean?]
   [:run-id string?]])

(def metadata-def-row
  [:map
   [:xt/id string?]
   [:key keyword?]
   [:label string?]
   [:description {:optional true} string?]
   [:applies-to [:vector keyword?]]
   [:value-type keyword?]
   [:cardinality keyword?]
   [:queryable? boolean?]
   [:display? boolean?]
   [:metric? {:optional true} boolean?]
   [:active? {:optional true} boolean?]
   [:project-id {:optional true} string?]
   [:run-id {:optional true} string?]])

(def metadata-row
  [:map
   [:xt/id string?]
   [:project-id {:optional true} string?]
   [:repo-id {:optional true} string?]
   [:target-id string?]
   [:target-kind keyword?]
   [:key keyword?]
   [:value any?]
   [:value-type keyword?]
   [:value-text string?]
   [:source keyword?]
   [:confidence {:optional true} number?]
   [:evidence-ids {:optional true} [:vector string?]]
   [:active? {:optional true} boolean?]
   [:run-id {:optional true} string?]])

(def graph-view-row
  [:map
   [:xt/id string?]
   [:label string?]
   [:description {:optional true} string?]
   [:project-id {:optional true} string?]
   [:node-filter {:optional true} any?]
   [:edge-filter {:optional true} any?]
   [:group-by {:optional true} [:vector keyword?]]
   [:rank-by {:optional true} [:vector keyword?]]
   [:display {:optional true} [:vector keyword?]]
   [:active? {:optional true} boolean?]
   [:run-id {:optional true} string?]])

(def graph-cursor-row
  [:map
   [:xt/id string?]
   [:schema string?]
   [:project-id string?]
   [:mode keyword?]
   [:query-text {:optional true} [:maybe string?]]
   [:root-ids [:vector string?]]
   [:focus-ids [:vector string?]]
   [:visited-ids [:vector string?]]
   [:frontier-ids [:vector string?]]
   [:basis map?]
   [:map-overlay {:optional true} [:maybe map?]]
   [:limits map?]
   [:parent-id {:optional true} [:maybe string?]]
   [:revision int?]
   [:operation map?]
   [:active? boolean?]
   [:created-at-ms int?]])

(def activity-item-row
  [:map
   [:xt/id string?]
   [:schema string?]
   [:project-id {:optional true} string?]
   [:source keyword?]
   [:source-id string?]
   [:source-path {:optional true} string?]
   [:kind string?]
   [:status keyword?]
   [:payload-schema {:optional true} string?]
   [:expected-result-schema {:optional true} string?]
   [:result-schema {:optional true} string?]
   [:target-ids [:vector string?]]
   [:summary string?]
   [:tokens [:vector string?]]
   [:created-at-ms int?]
   [:updated-at-ms int?]
   [:completed-at-ms {:optional true} int?]
   [:active? boolean?]
   [:run-id string?]])

(def activity-event-row
  [:map
   [:xt/id string?]
   [:schema string?]
   [:project-id {:optional true} string?]
   [:source keyword?]
   [:source-id string?]
   [:item-id string?]
   [:event-kind keyword?]
   [:status {:optional true} keyword?]
   [:agent-id {:optional true} string?]
   [:target-ids [:vector string?]]
   [:summary string?]
   [:at-ms int?]
   [:active? boolean?]
   [:run-id string?]])

(defn assert!
  "Validate value against schema and return value."
  [schema value message]
  (when-not ((validator schema) value)
    (throw (ex-info message {:value value
                             :explain (m/explain schema value)})))
  value)
