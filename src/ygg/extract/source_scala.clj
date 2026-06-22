(ns ygg.extract.source-scala
  (:require [ygg.extract.common :as common]
            [ygg.text :as text]
            [clojure.string :as str]))

(defn- scala-module-name
  [path content]
  (or (some (fn [line]
              (second (re-matches #"^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$"
                                  line)))
            (str/split-lines content))
      (common/source-module-name path)))
(defn- scala-imports
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*import\s+([A-Za-z_][A-Za-z0-9_.*{},\s]+)\s*$"
                                      line)]
                 {:target (-> target
                              (str/replace #"\s+" "")
                              (str/replace #"\{.*" ""))
                  :source-line (inc idx)})))
       distinct
       vec))
(defn- scala-type-line
  [idx line]
  (when-let [[_ visibility kind name]
             (re-matches
              #"^\s*(?:(private|protected)\s+)?(?:(?:case|sealed|abstract|final|implicit|lazy)\s+)*(class|trait|object|enum)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
              line)]
    {:kind (case kind
             "class" :class
             "trait" :trait
             "object" :object
             "enum" :enum)
     :name name
     :public? (not= "private" visibility)
     :source-line (inc idx)}))
(defn- scala-member-line
  [current-type idx line]
  (when-let [[_ visibility kind name]
             (re-matches
              #"^\s*(?:(private|protected)\s+)?(?:(?:override|inline|implicit|lazy|final)\s+)*(def|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
              line)]
    {:kind (case kind
             "def" :function
             "val" :property
             "var" :property)
     :name (if current-type
             (str (:name current-type) "." name)
             name)
     :public? (not= "private" visibility)
     :source-line (inc idx)}))
(defn- scala-definition-forms
  [content]
  (let [lines (vec (str/split-lines content))
        line-starts (common/line-start-offsets content)]
    (loop [remaining (map-indexed vector lines)
           depth 0
           type-stack []
           pending-type nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [type-stack (common/pop-closed-type-scopes type-stack depth)
              current-type (last type-stack)
              type-form (scala-type-line idx line)
              member-form (when-not type-form
                            (scala-member-line current-type idx line))
              forms (cond-> []
                      type-form (conj type-form)
                      member-form (conj member-form))
              forms (mapv (fn [{:keys [name] :as form}]
                            (let [start (+ (get line-starts idx 0)
                                           (or (str/index-of line name) 0))]
                              (assoc form
                                     :text (or (common/balanced-curly-block content start)
                                               line))))
                          forms)
              delta (common/curly-depth-delta line)
              depth* (+ depth delta)
              type-stack* (cond-> type-stack
                            (and pending-type (pos? delta))
                            (conj {:name (:name pending-type)
                                   :end-depth depth*})

                            (and type-form (pos? delta))
                            (conj {:name (:name type-form)
                                   :end-depth depth*}))
              pending-type* (cond
                              (and type-form (not (pos? delta))) type-form
                              (and pending-type (pos? delta)) nil
                              :else pending-type)]
          (recur (rest remaining)
                 depth*
                 type-stack*
                 pending-type*
                 (into out forms)))
        out))))
(defn extract-scala
  "Extract bounded package, import, and declaration facts from Scala source."
  [run-id {:keys [id-scope file-id path content]}]
  (let [module-name (scala-module-name path content)
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (vec (str/split-lines content))
        def-forms (scala-definition-forms content)
        defs (mapv (fn [{:keys [kind name public? source-line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (common/node-id id-scope :symbol label)
                        :label label
                        :kind kind
                        :file-id file-id
                        :path path
                        :namespace module-name
                        :name name
                        :public? public?
                        :source-line source-line
                        :tokens (text/tokenize label)
                        :active? true
                        :run-id run-id}))
                   def-forms)
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id ns-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           defs)
        import-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id ns-node)
                                             (common/node-id id-scope :namespace (:target %))
                                             :imports
                                             :extracted
                                             (:source-line %))
                           (scala-imports lines))
        chunk (common/source-text-chunk run-id
                                        id-scope
                                        file-id
                                        path
                                        :scala-file
                                        module-name
                                        content
                                        common/source-file-chunk-lines)
        definition-chunks (mapv (fn [{:keys [kind name source-line text]}]
                                  (common/source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str module-name "/" name)
                                   kind
                                   source-line
                                   text))
                                def-forms)
        diagnostics (common/curly-balance-diagnostics run-id
                                                      file-id
                                                      path
                                                      content
                                                      "Scala")]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))
