(ns ygg.search-doc
  "Build searchable documents from graph rows."
  (:require [ygg.hash :as hash]
            [ygg.text :as text]
            [clojure.string :as str]))

(defn search-doc-id
  "Return stable search document id for graph target id."
  [target-id]
  (str "search-doc:" (hash/short-hash [target-id])))

(defn- node-label
  [nodes-by-id id]
  (or (:label (get nodes-by-id id))
      (some-> id (str/replace #"^node:[^:]+:" ""))
      id))

(defn- add-neighbor-label
  [neighbors target-id label]
  (if (and target-id (not (str/blank? label)))
    (update neighbors target-id (fnil conj []) label)
    neighbors))

(defn- edge-neighbor-labels-by-id
  [nodes-by-id edges]
  (reduce
   (fn [neighbors {:keys [source-id target-id relation]}]
     (let [relation-name (some-> relation name)]
       (cond-> neighbors
         (and source-id target-id relation-name)
         (add-neighbor-label source-id
                             (str relation-name " "
                                  (node-label nodes-by-id target-id)))

         (and source-id target-id relation-name)
         (add-neighbor-label target-id
                             (str relation-name " by "
                                  (node-label nodes-by-id source-id))))))
   {}
   edges))

(defn- edge-neighbor-labels
  [neighbor-labels-by-id target-id]
  (->> (get neighbor-labels-by-id target-id)
       distinct
       (take 24)
       vec))

(defn- compact
  [& parts]
  (->> parts
       flatten
       (remove nil?)
       (map str)
       (remove str/blank?)
       (str/join "\n")))

(defn- named-value
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(defn- ->search-doc
  [run-id target-kind target {:keys [text]}]
  (let [input-text (or text (:label target))
        input-sha (hash/sha256-hex input-text)]
    (cond-> {:xt/id (search-doc-id (:xt/id target))
             :project-id (:project-id target)
             :repo-id (:repo-id target)
             :target-id (:xt/id target)
             :target-kind target-kind
             :file-id (:file-id target)
             :path (:path target)
             :kind (:kind target)
             :label (:label target)
             :text input-text
             :tokens (text/tokenize-all input-text)
             :input-sha input-sha
             :source-line (:source-line target)
             :active? true
             :run-id run-id}
      (:definition-kind target) (assoc :definition-kind (:definition-kind target))
      (:heading-path target) (assoc :heading-path (:heading-path target))
      (:content-sha target) (assoc :content-sha (:content-sha target))
      (:end-line target) (assoc :end-line (:end-line target)))))

(defn build-search-docs
  "Return searchable documents for extracted nodes and chunks."
  [run-id {:keys [nodes edges chunks]}]
  (let [nodes-by-id (into {} (map (juxt :xt/id identity)) nodes)
        neighbor-labels-by-id (edge-neighbor-labels-by-id nodes-by-id edges)
        node-docs (mapv (fn [node]
                          (->search-doc run-id
                                        :node
                                        node
                                        {:text (compact
                                                (:label node)
                                                (name (:kind node))
                                                (:path node)
                                                (:namespace node)
                                                (:name node)
                                                (edge-neighbor-labels neighbor-labels-by-id
                                                                      (:xt/id node)))}))
                        nodes)
        chunk-docs (mapv (fn [chunk]
                           (->search-doc run-id
                                         :chunk
                                         chunk
                                         {:text (compact
                                                 (:label chunk)
                                                 (name (:kind chunk))
                                                 (:path chunk)
                                                 (:text chunk))}))
                         chunks)]
    (into node-docs chunk-docs)))

(defn build-memory-search-doc
  "Return the search document for a durable memory row.

  `target-labels-by-id` is a bounded lookup result supplied by the caller; this
  helper does not infer project semantics from ids or path text."
  ([run-id memory-row]
   (build-memory-search-doc run-id memory-row {}))
  ([run-id memory-row target-labels-by-id]
   (let [target-ids (mapv str (:target-ids memory-row))
         target-labels (->> target-ids
                            (keep target-labels-by-id)
                            distinct
                            vec)
         label (or (:summary memory-row) (:text memory-row) (:xt/id memory-row))
         input-text (compact (:summary memory-row)
                             (named-value (:kind memory-row))
                             (named-value (:status memory-row))
                             (:tags memory-row)
                             target-labels
                             target-ids
                             (:text memory-row))
         input-sha (hash/sha256-hex input-text)]
     (cond-> {:xt/id (search-doc-id (:xt/id memory-row))
              :project-id (:project-id memory-row)
              :target-id (:xt/id memory-row)
              :target-kind :memory
              :file-id nil
              :path (:xt/id memory-row)
              :kind :memory
              :label label
              :text input-text
              :tokens (text/tokenize-all input-text)
              :input-sha input-sha
              :source-line 1
              :active? (and (not= false (:active? memory-row))
                            (not= :suggested (:status memory-row)))
              :run-id run-id}
       (:repo-id memory-row) (assoc :repo-id (:repo-id memory-row))))))
