(ns ygg.extract.source-js
  (:require [ygg.extract.common :as common]
            [ygg.hash :as hash]
            [ygg.text :as text]
            [clojure.string :as str]))

(def ^:private js-definition-chunk-lines 80)
(def ^:private typescript-declaration-member-before-lines 32)
(def ^:private typescript-declaration-member-after-lines 8)
(def ^:private typescript-declaration-member-limit 300)
(defn- parser-worker-failure?
  [diagnostic]
  (= "parser-worker" (str (:stage diagnostic))))
(defn- worker-diagnostic-row
  [run-id file-id path diagnostic]
  (common/diagnostic-row run-id
                         file-id
                         path
                         (:stage diagnostic)
                         (:line diagnostic)
                         (:message diagnostic)))
(defn- js-worker-definition-kind
  [kind]
  (case (keyword kind)
    :class :class
    :function :function
    :method :method
    :interface :interface
    :type :type
    :enum :enum
    :var))
(defn- js-simple-name
  [name]
  (some-> name str (str/split #"\.") last not-empty))
(defn- js-worker-source-name
  [source definition-names]
  (let [source (str source)]
    (or (some #(when (or (= source %)
                         (str/ends-with? % (str "." source)))
                 %)
              definition-names)
        source)))
(defn- js-worker-reference-target-name
  [target]
  (js-simple-name target))
(defn- js-worker-reference-targets-by-simple-name
  [definitions]
  (->> definitions
       (keep (fn [{:keys [name]}]
               (when-let [simple-name (js-simple-name name)]
                 [simple-name name])))
       (group-by first)
       (keep (fn [[simple-name rows]]
               (let [names (set (map second rows))]
                 (when (= 1 (count names))
                   [simple-name (first names)]))))
       (into {})))
(defn- js-chunk-kind
  [kind]
  (case kind
    :typescript :typescript-file
    :javascript-file))
(defn- js-definition-chunk
  [run-id id-scope file-id path lines {:keys [label kind source-line]}]
  (let [chunk-text (->> lines
                        (drop (dec (or source-line 1)))
                        (take js-definition-chunk-lines)
                        (str/join "\n"))
        line-count (count (str/split-lines chunk-text))]
    (cond-> {:xt/id (common/chunk-id id-scope path label (or source-line 1))
             :file-id file-id
             :path path
             :kind :code-definition
             :definition-kind kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line (or source-line 1)
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ (or source-line 1) line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))
(defn- typescript-declaration-path?
  [path]
  (boolean (re-find #"\.d\.(?:ts|mts|cts)$" (str path))))
(defn- typescript-declaration-member-line
  [idx line]
  (let [identifier (common/js-identifier)]
    (when-let [[_ name marker]
               (re-matches
                (re-pattern
                 (str "^\\s*(?:" "readonly" "\\s+)?("
                      identifier
                      ")\\??\\s*(:|\\(|<).*$"))
                line)]
      (let [line (str/trim line)]
        (when-not (or (str/starts-with? line "export ")
                      (str/starts-with? line "import ")
                      (str/starts-with? line "interface ")
                      (str/starts-with? line "type "))
          {:kind (if (and (= ":" marker)
                          (not (str/includes? line "=>"))
                          (not (str/includes? line "(")))
                   :property
                   :method)
           :name name
           :source-line (inc idx)})))))
(defn- nearest-jsdoc-start
  [lines start-idx member-idx]
  (or (->> (range member-idx (dec start-idx) -1)
           (some (fn [idx]
                   (when (re-find #"^\s*/\*\*" (nth lines idx))
                     idx))))
      start-idx))
(defn- typescript-declaration-member-chunk
  [run-id id-scope file-id path module-name lines {:keys [kind name source-line]}]
  (let [member-idx (max 0 (dec (or source-line 1)))
        start-idx (nearest-jsdoc-start
                   lines
                   (max 0 (- member-idx typescript-declaration-member-before-lines))
                   member-idx)
        end-idx (min (count lines)
                     (+ member-idx typescript-declaration-member-after-lines 1))
        chunk-text (->> lines
                        (drop start-idx)
                        (take (- end-idx start-idx))
                        (str/join "\n"))
        line-count (count (str/split-lines chunk-text))
        label (str module-name "/" name)]
    (cond-> {:xt/id (common/chunk-id id-scope path label (or source-line 1))
             :file-id file-id
             :path path
             :kind :code-definition
             :definition-kind kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line (inc start-idx)
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ (inc start-idx) line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))
(defn- js-declaration-member-chunks
  [run-id id-scope file-id path module-name lines kind]
  (if (and (= :typescript kind)
           (typescript-declaration-path? path))
    (->> lines
         (map-indexed typescript-declaration-member-line)
         (keep identity)
         (take typescript-declaration-member-limit)
         (mapv #(typescript-declaration-member-chunk run-id
                                                     id-scope
                                                     file-id
                                                     path
                                                     module-name
                                                     lines
                                                     %)))
    []))
(defn- js-import-edge
  [run-id id-scope file-id path ns-node {:keys [target source-line line import-kind importKind]}]
  (let [target (common/js-import-target path target)]
    (when-not (str/blank? (str target))
      (cond-> (common/edge-row run-id
                               file-id
                               path
                               (:xt/id ns-node)
                               (common/node-id id-scope :namespace target)
                               :imports
                               :extracted
                               (or source-line line 1))
        (or import-kind importKind) (assoc :import-kind
                                           (keyword (or import-kind importKind)))))))
(defn- extract-js-family-regex
  [run-id {:keys [id-scope file-id path content kind]}]
  (let [module-name (common/source-module-name path)
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (str/split-lines content)
        defs (->> lines
                  (map-indexed common/js-definition-line)
                  (keep identity)
                  (mapv (fn [{:keys [kind name public? source-line]}]
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
                             :run-id run-id}))))
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id ns-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           defs)
        import-edges (->> lines
                          (map-indexed #(common/js-import-targets %1 path %2))
                          (mapcat identity)
                          (keep #(js-import-edge run-id id-scope file-id path ns-node %))
                          vec)
        chunk-text (str/join "\n" (take 100 lines))
        chunk {:xt/id (common/chunk-id id-scope path module-name 1)
               :file-id file-id
               :path path
               :kind (js-chunk-kind kind)
               :label module-name
               :text chunk-text
               :tokens (text/tokenize (str module-name "\n" chunk-text))
               :source-line 1
               :active? true
               :run-id run-id}
        definition-chunks (mapv #(js-definition-chunk run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      lines
                                                      %)
                                defs)
        declaration-member-chunks (js-declaration-member-chunks run-id
                                                                id-scope
                                                                file-id
                                                                path
                                                                module-name
                                                                lines
                                                                kind)]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] (concat definition-chunks
                                   declaration-member-chunks))
     :diagnostics []}))
(defn- extract-js-family-worker-facts
  [run-id {:keys [id-scope file-id path content kind]} facts]
  (let [module-name (common/source-module-name path)
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (str/split-lines content)
        definitions (vec (:definitions facts))
        defs (mapv (fn [{:keys [kind name line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (common/node-id id-scope :symbol label)
                        :label label
                        :kind (js-worker-definition-kind kind)
                        :file-id file-id
                        :path path
                        :namespace module-name
                        :name name
                        :public? true
                        :source-line (or line 1)
                        :tokens (text/tokenize label)
                        :active? true
                        :run-id run-id}))
                   definitions)
        definition-names (mapv :name definitions)
        defined-names (set definition-names)
        reference-targets-by-simple-name
        (js-worker-reference-targets-by-simple-name definitions)
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id ns-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           defs)
        import-edges (->> (:imports facts)
                          (keep #(js-import-edge run-id
                                                 id-scope
                                                 file-id
                                                 path
                                                 ns-node
                                                 %))
                          vec)
        reference-edges (->> (:references facts)
                             (keep (fn [{:keys [source target line]}]
                                     (let [source-name (js-worker-source-name
                                                        source
                                                        definition-names)
                                           target-name (js-worker-reference-target-name
                                                        target)
                                           target-definition
                                           (get reference-targets-by-simple-name
                                                target-name)]
                                       (when (and (seq source-name)
                                                  (seq target-definition)
                                                  (contains? defined-names source-name)
                                                  (not= (first (str/split source-name #"\."))
                                                        target-name)
                                                  (not= source-name target-definition))
                                         (common/edge-row
                                          run-id
                                          file-id
                                          path
                                          (common/node-id id-scope
                                                          :symbol
                                                          (str module-name "/" source-name))
                                          (common/node-id id-scope
                                                          :symbol
                                                          (str module-name "/" target-definition))
                                          :references
                                          :extracted
                                          (or line 1))))))
                             distinct
                             vec)
        chunk (common/source-text-chunk run-id
                                        id-scope
                                        file-id
                                        path
                                        (js-chunk-kind kind)
                                        module-name
                                        content
                                        common/source-file-chunk-lines)
        definition-chunks (mapv (fn [{:keys [kind name line endLine]}]
                                  (common/source-definition-chunk
                                   run-id
                                   id-scope
                                   file-id
                                   path
                                   (str module-name "/" name)
                                   (js-worker-definition-kind kind)
                                   (or line 1)
                                   (common/source-range-text content
                                                             (or line 1)
                                                             (or endLine line))))
                                definitions)
        declaration-member-chunks (js-declaration-member-chunks run-id
                                                                id-scope
                                                                file-id
                                                                path
                                                                module-name
                                                                lines
                                                                kind)
        diagnostics (mapv #(worker-diagnostic-row run-id file-id path %)
                          (:diagnostics facts))]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges reference-edges))
     :chunks (into [chunk] (concat definition-chunks
                                   declaration-member-chunks))
     :diagnostics diagnostics}))
(defn- with-worker-diagnostics
  [extraction run-id file-id path facts]
  (update extraction
          :diagnostics
          into
          (mapv #(worker-diagnostic-row run-id file-id path %)
                (:diagnostics facts))))
(defn extract-js-family
  "Extract bounded module facts from JavaScript and TypeScript source files."
  [run-id {:keys [file-id path] :as file}]
  (let [facts (:parser-worker-facts file)
        diagnostics (vec (:diagnostics facts))]
    (cond
      (nil? facts)
      (extract-js-family-regex run-id file)

      (some parser-worker-failure? diagnostics)
      (with-worker-diagnostics
        (extract-js-family-regex run-id file)
        run-id
        file-id
        path
        facts)

      :else
      (extract-js-family-worker-facts run-id file facts))))
