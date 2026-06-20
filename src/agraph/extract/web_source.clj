(ns agraph.extract.web-source
  (:require [agraph.extract.common :as common]
            [agraph.hash :as hash]
            [agraph.text :as text]
            [clojure.string :as str]))

(defn- astro-frontmatter
  [content]
  (let [lines (vec (str/split-lines content))]
    (when (= "---" (str/trim (first lines)))
      (let [end-idx (->> lines
                         (map-indexed vector)
                         (drop 1)
                         (some (fn [[idx line]]
                                 (when (= "---" (str/trim line))
                                   idx))))]
        (when end-idx
          (str/join "\n" (subvec lines 1 end-idx)))))))
(defn extract-astro
  "Extract bounded component and frontmatter import facts from an Astro file."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [module-name (common/source-module-name path)
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        frontmatter (or (astro-frontmatter content) "")
        imports (->> (str/split-lines frontmatter)
                     (map-indexed #(common/js-import-targets %1 path %2))
                     (mapcat identity)
                     distinct
                     vec)
        import-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id ns-node)
                                             (common/node-id id-scope :namespace (:target %))
                                             :imports
                                             :extracted
                                             (:source-line %))
                           imports)
        chunk-result (common/extract-text-source run-id file :astro-file)]
    {:nodes [ns-node]
     :edges import-edges
     :chunks (:chunks chunk-result)
     :diagnostics []}))
(defn- sfc-start-tag
  [tag idx line]
  (when-let [[_ attrs tail] (re-matches (re-pattern (str "(?i)^\\s*<"
                                                         tag
                                                         "\\b([^>]*)>(.*)$"))
                                        line)]
    {:attrs (str/trim attrs)
     :tail tail
     :source-line (inc idx)}))
(defn- sfc-end-tag-pattern
  [tag]
  (re-pattern (str "(?i)</" tag ">")))
(defn- sfc-blocks
  [tag content]
  (let [lines (vec (str/split-lines content))
        end-pattern (sfc-end-tag-pattern tag)]
    (loop [remaining (map-indexed vector lines)
           current nil
           out []]
      (if-let [[idx line] (first remaining)]
        (if current
          (let [[before close?] (if-let [match (re-find end-pattern line)]
                                  [(subs line 0 (str/index-of line match)) true]
                                  [line false])
                current* (update current :lines conj before)]
            (if close?
              (recur (rest remaining) nil (conj out current*))
              (recur (rest remaining) current* out)))
          (if-let [{:keys [attrs tail source-line]} (sfc-start-tag tag idx line)]
            (if-let [match (re-find end-pattern tail)]
              (let [text (subs tail 0 (str/index-of tail match))]
                (recur (rest remaining)
                       nil
                       (conj out {:attrs attrs
                                  :source-line source-line
                                  :lines [text]})))
              (recur (rest remaining)
                     {:attrs attrs
                      :source-line source-line
                      :lines [tail]}
                     out))
            (recur (rest remaining) nil out)))
        (cond-> out current (conj current))))))
(defn- sfc-script-imports
  [path scripts]
  (->> scripts
       (mapcat (fn [{:keys [source-line lines]}]
                 (map-indexed
                  (fn [idx line]
                    (common/js-import-targets (+ (dec source-line) idx) path line))
                  lines)))
       (mapcat identity)
       distinct
       vec))
(defn- sfc-script-definitions
  [scripts]
  (->> scripts
       (mapcat (fn [{:keys [source-line lines]}]
                 (keep-indexed
                  (fn [idx line]
                    (common/js-definition-line (+ (dec source-line) idx) line))
                  lines)))
       vec))
(defn- sfc-block-chunks
  [run-id id-scope file-id path chunk-kind label-prefix blocks]
  (mapv (fn [{:keys [source-line lines]}]
          (let [block-text (str/join "\n" lines)
                label (str label-prefix " " source-line)
                line-count (count (str/split-lines block-text))]
            (cond-> {:xt/id (common/chunk-id id-scope path label source-line)
                     :file-id file-id
                     :path path
                     :kind chunk-kind
                     :label label
                     :text block-text
                     :tokens (text/tokenize (str label "\n" block-text))
                     :source-line source-line
                     :active? true
                     :run-id run-id}
              (pos? line-count) (assoc :end-line (+ source-line line-count -1))
              (seq block-text) (assoc :content-sha (hash/sha256-hex block-text)))))
        blocks))
(defn extract-sfc
  "Extract bounded component, script import, and script declaration facts."
  [run-id {:keys [id-scope file-id path content kind] :as file}]
  (let [module-name (common/source-module-name path)
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        component-node (common/generic-node run-id id-scope file-id path :component module-name 1)
        scripts (sfc-blocks "script" content)
        templates (sfc-blocks "template" content)
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
                   (sfc-script-definitions scripts))
        component-edge (common/edge-row run-id
                                        file-id
                                        path
                                        (:xt/id ns-node)
                                        (:xt/id component-node)
                                        :defines
                                        :extracted
                                        1)
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id component-node)
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
                           (sfc-script-imports path scripts))
        chunk-kind (case kind
                     :vue :vue-file
                     :svelte-file)
        script-chunk-kind (case kind
                            :vue :vue-script
                            :svelte-script)
        template-chunk-kind (case kind
                              :vue :vue-template
                              :svelte-template)
        file-chunks (:chunks (common/extract-text-source run-id file chunk-kind))
        script-chunks (sfc-block-chunks run-id
                                        id-scope
                                        file-id
                                        path
                                        script-chunk-kind
                                        (str module-name " script")
                                        scripts)
        template-chunks (sfc-block-chunks run-id
                                          id-scope
                                          file-id
                                          path
                                          template-chunk-kind
                                          (str module-name " template")
                                          templates)]
    {:nodes (vec (concat [ns-node component-node] defs))
     :edges (vec (concat [component-edge] define-edges import-edges))
     :chunks (vec (concat file-chunks script-chunks template-chunks))
     :diagnostics []}))
(defn- php-namespace-name
  [path content]
  (let [namespace (some (fn [line]
                          (second
                           (re-matches #"^\s*namespace\s+([A-Za-z_\\][A-Za-z0-9_\\]*)\s*;\s*$"
                                       line)))
                        (str/split-lines content))]
    (if namespace
      (str/replace namespace "\\" ".")
      (common/source-module-name path))))
(defn- php-use-targets
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-matches #"^\s*use\s+([A-Za-z_\\][A-Za-z0-9_\\]*)\s*(?:as\s+[A-Za-z_][A-Za-z0-9_]*)?\s*;\s*$"
                                      line)]
                 {:target (str/replace target "\\" ".")
                  :source-line (inc idx)})))
       distinct
       vec))
(defn- php-include-targets
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ target]
                          (re-find #"\b(?:require|require_once|include|include_once)\b.*['\"]([^'\"]+)['\"]"
                                   line)]
                 {:target target
                  :source-line (inc idx)})))
       distinct
       vec))
(defn- php-route-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (let [source-line (inc idx)]
            (concat
             (when-let [[_ method route]
                        (re-find #"Route::(get|post|put|patch|delete|options|any)\s*\(\s*['\"]([^'\"]+)['\"]"
                                 line)]
               [{:kind :framework-route
                 :label (str (str/upper-case method) " " route)
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ method route]
                        (re-find #"\$routes->(get|post|put|patch|delete|options|add)\s*\(\s*['\"]([^'\"]+)['\"]"
                                 line)]
               [{:kind :framework-route
                 :label (str (str/upper-case method) " " route)
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ route]
                        (re-find #"#\[Route\(\s*['\"]([^'\"]+)['\"]" line)]
               [{:kind :framework-route
                 :label route
                 :source-line source-line
                 :relation :defines}])))))
       distinct
       vec))
(defn- php-type-line
  [idx line]
  (when-let [[_ kind name]
             (re-matches #"^\s*(?:final\s+|abstract\s+)?(class|interface|trait|enum)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*"
                         line)]
    {:kind (case kind
             "class" :class
             "interface" :interface
             "trait" :trait
             "enum" :enum)
     :name name
     :public? true
     :source-line (inc idx)}))
(defn- php-function-line
  [current-type idx line]
  (when-let [[_ visibility name]
             (re-matches #"^\s*(?:(public|protected|private)\s+)?(?:static\s+)?function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(.*"
                         line)]
    {:kind (if current-type :method :function)
     :name (if current-type
             (str (:name current-type) "." name)
             name)
     :public? (not= "private" visibility)
     :source-line (inc idx)}))
(defn- php-constant-line
  [current-type idx line]
  (when-let [[_ name]
             (re-matches #"^\s*(?:(?:public|protected|private)\s+)?const\s+([A-Za-z_][A-Za-z0-9_]*)\b.*" line)]
    {:kind :constant
     :name (if current-type
             (str (:name current-type) "." name)
             name)
     :public? true
     :source-line (inc idx)}))
(defn- php-definition-forms
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
              type-form (php-type-line idx line)
              function-form (when-not type-form
                              (php-function-line current-type idx line))
              constant-form (when-not (or type-form function-form)
                              (php-constant-line current-type idx line))
              forms (cond-> []
                      type-form (conj type-form)
                      function-form (conj function-form)
                      constant-form (conj constant-form))
              forms (mapv (fn [{:keys [source-line] :as form}]
                            (let [start (+ (get line-starts idx 0)
                                           (or (some->> [(:name form)]
                                                        first
                                                        (str/index-of line))
                                               0))]
                              (assoc form
                                     :text (or (common/balanced-curly-block content start)
                                               line)
                                     :source-line source-line)))
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
(defn extract-php
  "Extract bounded namespace, use/include, and declaration facts from PHP."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [namespace-name (php-namespace-name path content)
        ns-node (common/namespace-node run-id id-scope file-id path namespace-name)
        lines (vec (str/split-lines content))
        def-forms (php-definition-forms content)
        defs (mapv (fn [{:keys [kind name public? source-line]}]
                     (let [label (str namespace-name "/" name)]
                       {:xt/id (common/node-id id-scope :symbol label)
                        :label label
                        :kind kind
                        :file-id file-id
                        :path path
                        :namespace namespace-name
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
        use-edges (mapv #(common/edge-row run-id file-id path
                                          (:xt/id ns-node)
                                          (common/node-id id-scope :namespace (:target %))
                                          :imports
                                          :extracted
                                          (:source-line %))
                        (php-use-targets lines))
        include-edges (mapv #(common/edge-row run-id file-id path
                                              (:xt/id ns-node)
                                              (common/node-id id-scope :file (:target %))
                                              :uses
                                              :extracted
                                              (:source-line %))
                            (php-include-targets lines))
        route-facts (php-route-facts lines)
        route-nodes (mapv (fn [{:keys [kind label source-line]}]
                            (common/generic-node run-id id-scope file-id path
                                                 kind label source-line))
                          route-facts)
        route-edges (mapv (fn [{:keys [kind label source-line relation]}]
                            (common/edge-row run-id
                                             file-id
                                             path
                                             (:xt/id ns-node)
                                             (common/node-id id-scope kind label)
                                             relation
                                             :extracted
                                             source-line))
                          route-facts)
        chunk-result (common/extract-text-source run-id file :php-file)
        definition-chunks (mapv (fn [{:keys [kind name source-line text]}]
                                  (common/source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str namespace-name "/" name)
                                   kind
                                   source-line
                                   text))
                                def-forms)
        diagnostics (common/curly-balance-diagnostics run-id
                                                      file-id
                                                      path
                                                      content
                                                      "PHP")]
    {:nodes (vec (concat [ns-node] defs route-nodes))
     :edges (vec (concat define-edges use-edges include-edges route-edges))
     :chunks (vec (concat (:chunks chunk-result) definition-chunks))
     :diagnostics diagnostics}))
