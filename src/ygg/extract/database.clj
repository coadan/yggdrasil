(ns ygg.extract.database
  (:require [ygg.extract.common :as common]
            [clojure.string :as str]))

(defn- sql-name
  [value]
  (some-> value
          (str/replace #"^[`\"\[]|[`\"\]]$" "")
          (str/replace #";$" "")))
(defn- sql-create-row
  [idx line]
  (or (when-let [[_ table]
                 (re-find #"(?i)^\s*create\s+(?:temporary\s+|temp\s+)?table\s+(?:if\s+not\s+exists\s+)?([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)"
                          line)]
        {:kind :table
         :name (sql-name table)
         :source-line (inc idx)})
      (when-let [[_ view]
                 (re-find #"(?i)^\s*create\s+(?:or\s+replace\s+)?view\s+([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)"
                          line)]
        {:kind :view
         :name (sql-name view)
         :source-line (inc idx)})))
(defn- sql-table-ranges
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (if-let [create (sql-create-row idx line)]
        (recur (rest remaining)
               (assoc create :start-idx idx :end-idx idx)
               (cond-> out current (conj current)))
        (let [current* (cond-> current
                         current (assoc :end-idx idx))]
          (if (and current (str/includes? line ");"))
            (recur (rest remaining) nil (conj out current*))
            (recur (rest remaining) current* out))))
      (cond-> out current (conj current)))))
(defn- sql-reference-targets
  [line]
  (->> (re-seq #"(?i)\breferences\s+([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)" line)
       (map second)
       (map sql-name)
       (remove str/blank?)
       distinct))
(defn- sql-table-edges
  [run-id id-scope file-id path tables lines]
  (->> tables
       (filter #(= :table (:kind %)))
       (mapcat
        (fn [{:keys [name start-idx end-idx]}]
          (let [source-id (common/node-id id-scope :table name)]
            (->> (subvec (vec lines) start-idx (inc end-idx))
                 (map-indexed vector)
                 (mapcat (fn [[offset line]]
                           (map (fn [target]
                                  (common/edge-row run-id
                                                   file-id
                                                   path
                                                   source-id
                                                   (common/node-id id-scope :table target)
                                                   :references
                                                   :extracted
                                                   (+ start-idx offset 1)))
                                (sql-reference-targets line))))))))
       distinct
       vec))
(defn- dbt-ref-calls
  [lines]
  (->> lines
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (map (fn [[_ target]]
                 {:kind :dbt-model
                  :label target
                  :source-line (inc idx)})
               (re-seq #"\bref\s*\(\s*['\"]([^'\"]+)['\"]\s*\)" line))))
       distinct
       vec))
(defn- dbt-source-calls
  [lines]
  (->> lines
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (map (fn [[_ source table]]
                 {:kind :dbt-source
                  :label (str source "." table)
                  :source-line (inc idx)})
               (re-seq #"\bsource\s*\(\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*\)"
                       line))))
       distinct
       vec))
(defn- dbt-sql-reference-facts
  [lines]
  (vec (concat (dbt-ref-calls lines)
               (dbt-source-calls lines))))
(defn- dbt-sql-reference-rows
  [run-id id-scope file-id path refs]
  (when (seq refs)
    (let [sql-node (common/generic-node run-id id-scope file-id path :dbt-sql-file path 1)
          ref-nodes (mapv (fn [{:keys [kind label source-line]}]
                            (common/generic-node run-id id-scope file-id path
                                                 kind label source-line))
                          refs)
          ref-edges (mapv (fn [{:keys [kind label source-line]}]
                            (common/edge-row run-id
                                             file-id
                                             path
                                             (:xt/id sql-node)
                                             (common/node-id id-scope kind label)
                                             :references
                                             :extracted
                                             source-line))
                          refs)]
      {:nodes (into [sql-node] ref-nodes)
       :edges ref-edges})))
(defn extract-sql
  "Extract declared SQL schema facts from a static SQL file."
  [run-id {:keys [id-scope file-id path content kind] :as file}]
  (let [lines (vec (str/split-lines content))
        declarations (sql-table-ranges lines)
        nodes (mapv (fn [{:keys [kind name source-line]}]
                      (common/generic-node run-id id-scope file-id path kind name source-line))
                    declarations)
        edges (sql-table-edges run-id id-scope file-id path declarations lines)
        dbt-rows (dbt-sql-reference-rows run-id
                                         id-scope
                                         file-id
                                         path
                                         (dbt-sql-reference-facts lines))
        chunk-result (common/extract-text-source run-id file :sql-file)]
    (assoc chunk-result
           :nodes (vec (concat nodes (:nodes dbt-rows)))
           :edges (vec (concat edges (:edges dbt-rows)))
           :chunks (mapv #(assoc % :file-kind kind) (:chunks chunk-result)))))
(defn- migration-filename-stem
  [path]
  (let [filename (.getName (java.io.File. (str path)))
        dot-idx (.lastIndexOf filename ".")]
    (if (neg? dot-idx)
      filename
      (subs filename 0 dot-idx))))
(defn- flyway-migration-fact
  [path]
  (let [stem (migration-filename-stem path)]
    (when (re-matches #"(?i)(?:V[0-9][A-Za-z0-9_.-]*|U[0-9][A-Za-z0-9_.-]*|R)__.+"
                      stem)
      {:kind :db-migration-version
       :label stem
       :source-line 1
       :relation :defines})))
(defn- distinct-migration-facts
  [facts]
  (->> facts
       (reduce (fn [acc {:keys [kind label relation] :as fact}]
                 (if (contains? acc [kind label relation])
                   acc
                   (assoc acc [kind label relation] fact)))
               {})
       vals
       vec))
(defn- migration-sql-line-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (let [source-line (inc idx)]
            (concat
             (when-let [[_ index table]
                        (re-find #"(?i)^\s*create\s+(?:unique\s+)?index\s+(?:if\s+not\s+exists\s+)?([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)\s+on\s+([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)"
                                 line)]
               [{:kind :index
                 :label (sql-name index)
                 :source-line source-line
                 :relation :defines}
                {:kind :table
                 :label (sql-name table)
                 :source-line source-line
                 :relation :references}])
             (when-let [[_ table]
                        (re-find #"(?i)^\s*alter\s+table\s+(?:if\s+exists\s+)?([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)"
                                 line)]
               [{:kind :table
                 :label (sql-name table)
                 :source-line source-line
                 :relation :references}])
             (when-let [[_ constraint]
                        (re-find #"(?i)\b(?:add\s+)?constraint\s+([A-Za-z_][A-Za-z0-9_.\"`\[\]]*)"
                                 line)]
               [{:kind :constraint
                 :label (sql-name constraint)
                 :source-line source-line
                 :relation :defines}])
             (map (fn [target]
                    {:kind :table
                     :label target
                     :source-line source-line
                     :relation :references})
                  (sql-reference-targets line))))))
       distinct-migration-facts))
(defn- migration-sql-facts
  [path lines]
  (let [version-fact (flyway-migration-fact path)
        declarations (sql-table-ranges lines)
        declaration-facts (mapv (fn [{:keys [kind name source-line]}]
                                  {:kind kind
                                   :label name
                                   :source-line source-line
                                   :relation :defines})
                                declarations)]
    (distinct-migration-facts
     (cond-> (vec (concat declaration-facts (migration-sql-line-facts lines)))
       version-fact (conj version-fact)))))
(defn- liquibase-line-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (let [source-line (inc idx)]
            (concat
             (map (fn [[_ id]]
                    {:kind :db-changeset
                     :label id
                     :source-line source-line
                     :relation :defines})
                  (re-seq #"(?i)(?:\bid\s*[:=]\s*|\"id\"\s*:\s*\")\"?([A-Za-z0-9_.:-]+)"
                          line))
             (map (fn [[_ table]]
                    {:kind :table
                     :label table
                     :source-line source-line
                     :relation :defines})
                  (re-seq #"(?i)(?:\btableName\s*[:=]\s*|\"tableName\"\s*:\s*\")\"?([A-Za-z_][A-Za-z0-9_.-]+)"
                          line))
             (map (fn [[_ index]]
                    {:kind :index
                     :label index
                     :source-line source-line
                     :relation :defines})
                  (re-seq #"(?i)(?:\bindexName\s*[:=]\s*|\"indexName\"\s*:\s*\")\"?([A-Za-z_][A-Za-z0-9_.-]+)"
                          line))
             (map (fn [[_ constraint]]
                    {:kind :constraint
                     :label constraint
                     :source-line source-line
                     :relation :defines})
                  (re-seq #"(?i)(?:\bconstraintName\s*[:=]\s*|\"constraintName\"\s*:\s*\")\"?([A-Za-z_][A-Za-z0-9_.-]+)"
                          line))
             (map (fn [[_ include-file]]
                    {:kind :project-reference
                     :label include-file
                     :source-line source-line
                     :relation :references})
                  (re-seq #"(?i)(?:\bfile\s*[:=]\s*|\"file\"\s*:\s*\")\"?([^\"'\s,}]+)"
                          line))
             (map (fn [[_ author]]
                    {:kind :db-changeset-author
                     :label author
                     :source-line source-line
                     :relation :defines})
                  (re-seq #"(?i)(?:\bauthor\s*[:=]\s*|\"author\"\s*:\s*\")\"?([A-Za-z0-9_.:-]+)"
                          line))
             (map (fn [[_ operation]]
                    {:kind :db-change-operation
                     :label operation
                     :source-line source-line
                     :relation :uses})
                  (remove #(contains? #{"changeSet" "include"} (second %))
                          (re-seq #"^\s*-\s+([A-Za-z][A-Za-z0-9]*):\s*$"
                                  line)))
             (when (re-matches #"(?i)^\s*rollback\s*:\s*.*$" line)
               [{:kind :db-rollback
                 :label "rollback"
                 :source-line source-line
                 :relation :defines}])))))
       distinct
       vec))
(defn- migration-facts
  [{:keys [path content ext]}]
  (let [lines (vec (str/split-lines content))]
    (if (= ".sql" ext)
      (migration-sql-facts path lines)
      (liquibase-line-facts lines))))
(defn extract-db-migration
  "Extract bounded database migration facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [migration-node (common/generic-node run-id id-scope file-id path
                                            :db-migration path 1)
        facts (migration-facts file)
        fact-nodes (->> facts
                        (map (fn [{:keys [kind label source-line]}]
                               (common/generic-node run-id id-scope file-id path
                                                    kind label source-line)))
                        (reduce (fn [acc node]
                                  (assoc acc (:xt/id node) node))
                                {})
                        vals
                        vec)
        fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                           (common/edge-row run-id
                                            file-id
                                            path
                                            (:xt/id migration-node)
                                            (common/node-id id-scope kind label)
                                            relation
                                            :extracted
                                            source-line))
                         facts)
        chunk-result (common/extract-text-source run-id file :db-migration-file)]
    {:nodes (into [migration-node] fact-nodes)
     :edges fact-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))
