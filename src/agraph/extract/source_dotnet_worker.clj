(ns agraph.extract.source-dotnet-worker
  (:require [agraph.extract.common :as common]
            [agraph.extract.source-dotnet :as source-dotnet]
            [agraph.extract.source-jvm :as source-jvm]
            [agraph.text :as text]
            [clojure.string :as str]))

(defn- dotnet-worker-definition-kind
  [kind]
  (case (keyword kind)
    :class :class
    :interface :interface
    :enum :enum
    :record :record
    :struct :struct
    :delegate :delegate
    :constructor :constructor
    :method :method
    :property :property
    :symbol))
(defn- dotnet-worker-source-name
  [source definitions]
  (let [source (str source)
        names (set (map :name definitions))]
    (or (some #(when (or (= source %)
                         (str/ends-with? % (str "." source)))
                 %)
              names)
        source)))
(defn- dotnet-worker-reference-target-name
  [target]
  (some-> target
          str
          (str/replace #"<.*$" "")
          (str/replace #"\[\]$" "")
          (str/split #"\.")
          last
          not-empty))
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
(defn- with-worker-diagnostics
  [extraction run-id file-id path facts]
  (update extraction
          :diagnostics
          into
          (mapv #(worker-diagnostic-row run-id file-id path %)
                (:diagnostics facts))))
(defn- extract-dotnet-worker-facts
  [run-id {:keys [id-scope file-id path content]} facts]
  (let [diagnostics (vec (:diagnostics facts))
        module-name (or (not-empty (str (:namespace facts)))
                        (source-dotnet/dotnet-module-name path content))
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        definitions (vec (:definitions facts))
        imports (vec (:imports facts))
        import-symbols (source-jvm/java-import-symbols-by-simple-name imports)
        defs (mapv (fn [{:keys [kind name line]}]
                     (let [label (str module-name "/" name)]
                       {:xt/id (common/node-id id-scope :symbol label)
                        :label label
                        :kind (dotnet-worker-definition-kind kind)
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
        defined-names (set (map :name definitions))
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id ns-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           defs)
        import-edges (->> imports
                          (keep (fn [{:keys [target line]}]
                                  (when-not (str/blank? (str target))
                                    (common/edge-row run-id file-id path
                                                     (:xt/id ns-node)
                                                     (common/node-id id-scope
                                                                     :namespace
                                                                     (str target))
                                                     :imports
                                                     :extracted
                                                     (or line 1)))))
                          vec)
        reference-edges (->> (:references facts)
                             (keep (fn [{:keys [source target line]}]
                                     (let [source-name (dotnet-worker-source-name
                                                        source
                                                        definitions)
                                           target-name (dotnet-worker-reference-target-name
                                                        target)]
                                       (when (and (seq source-name)
                                                  (seq target-name)
                                                  (contains? defined-names source-name)
                                                  (not= (first (str/split source-name #"\."))
                                                        target-name))
                                         (common/edge-row
                                          run-id
                                          file-id
                                          path
                                          (common/node-id id-scope
                                                          :symbol
                                                          (str module-name "/" source-name))
                                          (common/node-id id-scope
                                                          :symbol
                                                          (source-jvm/java-reference-target-label
                                                           module-name
                                                           import-symbols
                                                           target-name))
                                          :references
                                          :extracted
                                          (or line 1))))))
                             distinct
                             vec)
        chunk (common/source-text-chunk run-id
                                        id-scope
                                        file-id
                                        path
                                        :dotnet-file
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
                                   (dotnet-worker-definition-kind kind)
                                   (or line 1)
                                   (common/source-range-text content
                                                             (or line 1)
                                                             (or endLine line))))
                                definitions)
        diagnostics (mapv #(worker-diagnostic-row run-id file-id path %)
                          diagnostics)]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges reference-edges))
     :chunks (into [chunk] definition-chunks)
     :diagnostics diagnostics}))
(defn extract-dotnet-worker
  "Extract .NET facts through the optional parser worker."
  [run-id {:keys [file-id path] :as file} facts]
  (let [facts (or facts {})
        diagnostics (vec (:diagnostics facts))]
    (if (some parser-worker-failure? diagnostics)
      (with-worker-diagnostics
        (source-dotnet/extract-dotnet run-id file)
        run-id
        file-id
        path
        facts)
      (extract-dotnet-worker-facts run-id file facts))))
