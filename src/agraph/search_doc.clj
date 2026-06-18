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

(defn- edge-neighbor-labels
  [nodes-by-id edges target-id]
  (->> edges
       (keep (fn [edge]
               (cond
                 (= target-id (:source-id edge))
                 (str (name (:relation edge)) " " (node-label nodes-by-id (:target-id edge)))

                 (= target-id (:target-id edge))
                 (str (name (:relation edge)) " by " (node-label nodes-by-id (:source-id edge))))))
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
                                                (edge-neighbor-labels nodes-by-id edges (:xt/id node)))}))
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
