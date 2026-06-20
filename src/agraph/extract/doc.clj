(ns agraph.extract.doc
  (:require [agraph.extract.common :as common]
            [agraph.extract.package-python :as package-python]
            [agraph.fs :as fs]
            [agraph.hash :as hash]
            [agraph.text :as text]
            [clojure.string :as str]))

(def ^:private markdown-chunk-lines 120)
(defn- markdown-heading-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ _ heading] (re-matches #"^(#{1,6})\s+(.+?)\s*$"
                                                    line)]
                 {:kind :doc-heading
                  :label heading
                  :source-line (inc idx)
                  :relation :defines})))
       distinct
       vec))
(defn- markdown-link-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (mapcat (fn [[idx line]]
                 (map (fn [[_ target]]
                        {:kind :doc-link
                         :label target
                         :source-line (inc idx)
                         :relation :references})
                      (re-seq #"\[[^\]]+\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)"
                              line))))
       distinct
       vec))
(defn- mdx-import-facts
  [path lines]
  (->> lines
       (map-indexed #(common/js-import-targets %1 path %2))
       (mapcat identity)
       (map (fn [{:keys [target source-line]}]
              {:kind :doc-import
               :label target
               :source-line source-line
               :relation :references}))
       distinct
       vec))
(defn- rst-toctree-facts
  [lines]
  (let [close-tree (fn [out current]
                     (if current
                       (let [source-line (:source-line current)
                             entries (->> (:entries current)
                                          distinct
                                          (mapv (fn [entry]
                                                  {:kind :docs-route
                                                   :label entry
                                                   :source-line source-line
                                                   :relation :references})))
                             options (->> (:options current)
                                          distinct
                                          (mapv (fn [option]
                                                  {:kind :docs-toctree-option
                                                   :label option
                                                   :source-line source-line
                                                   :relation :uses})))]
                         (into out
                               (concat [{:kind :docs-toctree
                                         :label (str "toctree@" source-line)
                                         :source-line source-line
                                         :relation :defines}]
                                       entries
                                       options)))
                       out))]
    (loop [remaining (map-indexed vector lines)
           current nil
           out []]
      (if-let [[idx line] (first remaining)]
        (cond
          (re-matches #"^\s*\.\.\s+toctree::\s*$" line)
          (recur (rest remaining)
                 {:source-line (inc idx)
                  :entries []
                  :options []}
                 (close-tree out current))

          current
          (let [trimmed (str/trim line)]
            (cond
              (str/blank? line)
              (recur (rest remaining) current out)

              (not (re-matches #"^\s+.+$" line))
              (recur remaining nil (close-tree out current))

              (str/starts-with? trimmed ":")
              (recur (rest remaining)
                     (update current :options conj trimmed)
                     out)

              :else
              (recur (rest remaining)
                     (update current :entries conj trimmed)
                     out)))

          :else
          (recur (rest remaining) nil out))
        (vec (distinct (close-tree out current)))))))
(defn- doc-structure-facts
  [path lines]
  (vec (concat (markdown-heading-facts lines)
               (markdown-link-facts lines)
               (package-python/pip-install-dependencies
                (str/join "\n" lines))
               (when (= ".rst" (fs/extension path))
                 (rst-toctree-facts lines))
               (when (= ".mdx" (fs/extension path))
                 (mdx-import-facts path lines)))))
(defn- split-doc-chunk
  [{:keys [start-line lines] :as chunk}]
  (->> (partition-all markdown-chunk-lines lines)
       (map-indexed (fn [idx part]
                      (let [part (vec part)
                            start (+ start-line (* idx markdown-chunk-lines))]
                        (assoc chunk
                               :start-line start
                               :end-line (+ start (count part) -1)
                               :lines part))))))
(defn extract-doc
  "Extract Markdown chunks."
  [run-id {:keys [id-scope file-id path content]}]
  (let [lines (str/split-lines content)
        total-lines (count lines)
        mdx? (= ".mdx" (fs/extension path))
        doc-node (common/generic-node run-id id-scope file-id path :doc-file path 1)
        facts (doc-structure-facts path lines)
        fact-nodes (mapv (fn [fact]
                           (common/fact-node run-id id-scope file-id path fact))
                         facts)
        fact-edges (mapv (fn [fact]
                           (common/fact-edge-row run-id
                                                 file-id
                                                 path
                                                 (:xt/id doc-node)
                                                 id-scope
                                                 fact))
                         facts)
        close-chunk (fn [out current end-line]
                      (if (seq (:lines current))
                        (into out (split-doc-chunk
                                   (assoc current :end-line end-line)))
                        out))
        chunks (loop [remaining (map-indexed vector lines)
                      heading-stack []
                      current {:label path
                               :start-line 1
                               :heading-path []
                               :lines []}
                      out []]
                 (if-let [[idx line] (first remaining)]
                   (if-let [[_ marker heading] (re-matches #"^(#{1,6})\s+(.+?)\s*$" line)]
                     (let [level (count marker)
                           stack (->> heading-stack
                                      (remove #(<= level (:level %)))
                                      vec)
                           stack (conj stack {:level level :label heading})]
                       (recur (rest remaining)
                              stack
                              {:label heading
                               :start-line (inc idx)
                               :heading-path (mapv :label stack)
                               :lines [line]}
                              (close-chunk out current idx)))
                     (recur (rest remaining)
                            heading-stack
                            (update current :lines conj line)
                            out))
                   (close-chunk out current total-lines)))]
    {:nodes (into [doc-node] fact-nodes)
     :edges fact-edges
     :chunks (mapv (fn [{:keys [label start-line lines] :as chunk}]
                     (let [text (str/join "\n" lines)]
                       {:xt/id (common/chunk-id id-scope path label start-line)
                        :file-id file-id
                        :path path
                        :kind (if mdx? :mdx :markdown)
                        :label label
                        :text text
                        :tokens (text/tokenize (str label "\n" text))
                        :heading-path (:heading-path chunk)
                        :content-sha (hash/sha256-hex text)
                        :source-line start-line
                        :end-line (:end-line chunk)
                        :active? true
                        :run-id run-id}))
                   chunks)
     :diagnostics []}))
