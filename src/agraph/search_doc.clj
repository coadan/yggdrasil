(ns agraph.search-doc
  "Build searchable documents from graph rows."
  (:require [agraph.hash :as hash]
            [agraph.text :as text]
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
