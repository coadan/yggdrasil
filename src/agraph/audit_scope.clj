(ns agraph.audit-scope
  "Mechanical audit-scope summaries for selected graph evidence."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def schema
  "agraph.audit-scopes/v1")

(def ^:private sample-limit
  3)

(def ^:private evidence-type-limit
  5)

(def ^:private scope-order
  {"dependencies" 0
   "runtime-config" 1
   "containers" 2
   "infra" 3
   "docs" 4
   "assets" 5
   "unknown-text" 6})

(def ^:private dependency-relations
  {"imports-package" #{"dependencies"}
   "requires" #{"dependencies"}
   "version-of" #{"dependencies"}
   "resolves" #{"dependencies"}})

(def ^:private fact-kind-scopes
  {"container-image" #{"containers"}
   "container-image-producer" #{"containers"}
   "container-image-consumer" #{"containers"}
   "container-port" #{"containers" "runtime-config"}
   "devcontainer-command" #{"runtime-config"}
   "devcontainer-feature" #{"runtime-config"}
   "devcontainer-port" #{"runtime-config"}
   "devcontainer-service" #{"runtime-config"}
   "docker-build-arg" #{"containers" "runtime-config"}
   "docker-copy-source" #{"containers"}
   "docker-label" #{"containers" "runtime-config"}
   "docker-stage" #{"containers"}
   "docker-workdir" #{"containers"}
   "env-var" #{"runtime-config"}
   "route" #{"runtime-config"}
   "runtime-command" #{"runtime-config"}
   "terraform-data-source" #{"infra"}
   "terraform-module" #{"infra"}
   "terraform-module-source" #{"infra"}
   "terraform-output" #{"infra"}
   "terraform-provider" #{"infra"}
   "terraform-provider-alias" #{"infra"}
   "terraform-resource" #{"infra"}
   "terraform-variable" #{"infra"}
   "url" #{"runtime-config"}})

(def ^:private file-kind-scopes
  {"archive-asset" #{"assets"}
   "compiled-artifact" #{"assets"}
   "compose" #{"containers"}
   "devcontainer" #{"runtime-config" "containers"}
   "docker" #{"containers"}
   "env" #{"runtime-config"}
   "font-asset" #{"assets"}
   "helm" #{"infra" "containers"}
   "image-asset" #{"assets"}
   "kustomize" #{"infra" "containers"}
   "media-asset" #{"assets"}
   "opaque-asset" #{"assets"}
   "procfile" #{"runtime-config"}
   "terraform" #{"infra"}
   "unknown" #{"unknown-text"}})

(defn- display-name
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(defn- normalize-key
  [value]
  (some-> value display-name str/trim not-empty))

(defn- scopes-for-keys
  [registry values]
  (->> values
       (keep normalize-key)
       (mapcat #(get registry %))
       set))

(defn- row-file-kind
  [row]
  (or (:fileKind row)
      (:file-kind row)))

(defn- selected-path
  [row]
  (or (:path row)
      (get-in row [:source :path])))

(defn- sample-row
  [row]
  (cond-> (select-keys row [:id
                            :kind
                            :relation
                            :path
                            :target
                            :source
                            :sourceLine
                            :score])
    (row-file-kind row) (assoc :fileKind (display-name (row-file-kind row)))))

(defn- audit-row
  [scope section row evidence-type]
  {:scope scope
   :section section
   :evidenceType evidence-type
   :path (selected-path row)
   :row row})

(defn- runtime-audit-rows
  [row]
  (let [scopes (set/union (scopes-for-keys fact-kind-scopes [(:kind row)])
                          (scopes-for-keys file-kind-scopes [(row-file-kind row)]))
        evidence-type (or (normalize-key (:kind row))
                          (normalize-key (row-file-kind row))
                          "runtime-evidence")]
    (mapv #(audit-row % "runtimeEvidence" row evidence-type) scopes)))

(defn- dependency-audit-rows
  [row]
  (let [relation (normalize-key (:relation row))
        scopes (get dependency-relations relation)]
    (mapv #(audit-row % "dependencyEvidence" row (or relation "dependency-edge"))
          scopes)))

(defn- doc-audit-row
  [row]
  (audit-row "docs" "docs" row (or (normalize-key (:role row))
                                   (normalize-key (:status row))
                                   "doc")))

(defn- evidence-type-counts
  [rows]
  (->> rows
       (map :evidenceType)
       frequencies
       (map (fn [[evidence-type n]]
              {:kind evidence-type
               :count n}))
       (sort-by (juxt (comp - long :count) :kind))
       (take evidence-type-limit)
       vec))

(defn- summarize-scope
  [[scope rows]]
  (let [paths (->> rows
                   (keep :path)
                   set)
        evidence-types (evidence-type-counts rows)
        samples (->> rows
                     (map (fn [{:keys [section row]}]
                            (assoc (sample-row row) :section section)))
                     (take sample-limit)
                     vec)]
    (cond-> {:kind scope
             :basis "selected-architecture-evidence"
             :facts (count rows)}
      (seq paths) (assoc :files (count paths))
      (seq evidence-types) (assoc :topEvidenceTypes evidence-types)
      (seq samples) (assoc :samples samples))))

(defn selected-summaries
  "Return audit-scope summaries for the selected architecture evidence rows.

  The grouping is based on extractor fact kinds, file kinds, and relation kinds
  only. It does not infer project meaning from paths, prose, hostnames, or
  directory vocabulary."
  [{:keys [runtime-evidence dependency-evidence docs]}]
  (->> (concat (mapcat runtime-audit-rows runtime-evidence)
               (mapcat dependency-audit-rows dependency-evidence)
               (map doc-audit-row docs))
       (group-by :scope)
       (map summarize-scope)
       (sort-by (fn [row]
                  [(get scope-order (:kind row) 100)
                   (:kind row)]))
       vec))
