(ns agraph.extract.api-schema
  (:require [agraph.extract.common :as common]
            [charred.api :as json]
            [clojure.string :as str]))

(def openapi-methods
  #{"get" "put" "post" "delete" "options" "head" "patch" "trace"})
(defn- openapi-operation-label
  [{:keys [path method operation-id]}]
  (str (str/upper-case method)
       " "
       path
       (when operation-id
         (str " " operation-id))))
(defn- openapi-ref-schema-name
  [ref]
  (common/json-ref-tail "#/components/schemas/" ref))
(defn- openapi-yaml-ref-values
  [lines]
  (->> lines
       (keep (fn [[idx line]]
               (when-let [[_ ref] (re-matches #"^\s*\$ref:\s*['\"]?([^'\"]+)['\"]?\s*$"
                                              line)]
                 {:ref ref
                  :source-line (inc idx)})))
       vec))
(defn- openapi-yaml-server-facts
  [lines]
  (loop [remaining (map-indexed vector lines)
         in-servers? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)]
        (cond
          (and entry (zero? (:indent entry)) (= "servers" (:key entry)))
          (recur (rest remaining) true out)

          (and in-servers? entry (zero? (:indent entry)))
          (recur (rest remaining) false out)

          in-servers?
          (if-let [[_ url] (re-matches #"^\s*-\s*url:\s*(.+?)\s*$" line)]
            (recur (rest remaining)
                   true
                   (conj out {:url (common/strip-yaml-scalar url)
                              :source-line (inc idx)}))
            (recur (rest remaining) true out))

          :else
          (recur (rest remaining) in-servers? out)))
      (vec (distinct out)))))
(defn- openapi-yaml-operation-blocks
  [lines]
  (loop [remaining (map-indexed vector lines)
         current-path nil
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (cond
        (re-matches #"^\s{2}(/[^\s:]+):\s*$" line)
        (let [[_ path] (re-matches #"^\s{2}(/[^\s:]+):\s*$" line)]
          (recur (rest remaining) path nil (cond-> out current (conj current))))

        (re-matches #"^\s{4}([a-z]+):\s*$" line)
        (let [[_ method] (re-matches #"^\s{4}([a-z]+):\s*$" line)]
          (if (and current-path (contains? openapi-methods method))
            (recur (rest remaining)
                   current-path
                   {:path current-path
                    :method method
                    :source-line (inc idx)
                    :lines [[idx line]]}
                   (cond-> out current (conj current)))
            (recur (rest remaining) current-path current out)))

        (and current
             (not (str/blank? line))
             (zero? (common/leading-spaces line)))
        (recur (rest remaining) nil nil (conj out current))

        current
        (recur (rest remaining)
               current-path
               (update current :lines conj [idx line])
               out)

        :else
        (recur (rest remaining) current-path current out))
      (cond-> out current (conj current)))))
(defn- openapi-yaml-schema-blocks
  [lines]
  (loop [remaining (map-indexed vector lines)
         in-schemas? false
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (cond
        (re-matches #"^\s{2}schemas:\s*$" line)
        (recur (rest remaining) true nil out)

        (and in-schemas?
             (re-matches #"^\s{2}[A-Za-z0-9_.-]+:\s*$" line))
        (recur (rest remaining) false nil (cond-> out current (conj current)))

        (and in-schemas?
             (re-matches #"^\s{4}([A-Za-z0-9_.-]+):\s*$" line))
        (let [[_ name] (re-matches #"^\s{4}([A-Za-z0-9_.-]+):\s*$" line)]
          (recur (rest remaining)
                 true
                 {:name name
                  :source-line (inc idx)
                  :lines [[idx line]]}
                 (cond-> out current (conj current))))

        current
        (recur (rest remaining)
               in-schemas?
               (update current :lines conj [idx line])
               out)

        :else
        (recur (rest remaining) in-schemas? current out))
      (cond-> out current (conj current)))))
(defn- openapi-json-spec
  [content]
  (try
    (let [parsed (json/read-json content :key-fn keyword)]
      (when (or (:openapi parsed) (:swagger parsed))
        parsed))
    (catch Exception _ nil)))
(defn- openapi-yaml-lines
  [lines]
  (when (some #(re-matches #"^\s*(openapi|swagger):\s*.+$" %) lines)
    (let [operation-blocks (openapi-yaml-operation-blocks lines)
          schema-blocks (openapi-yaml-schema-blocks lines)]
      {:servers (openapi-yaml-server-facts lines)
       :paths (->> lines
                   (map-indexed vector)
                   (keep (fn [[idx line]]
                           (when-let [[_ path] (re-matches #"^\s{2}(/[^\s:]+):\s*$"
                                                           line)]
                             {:path path
                              :source-line (inc idx)})))
                   vec)
       :operations (->> operation-blocks
                        (mapv (fn [{:keys [lines] :as operation}]
                                (assoc operation
                                       :operation-id
                                       (some (fn [[_ line]]
                                               (some-> (re-matches #"^\s{6}operationId:\s*(.+?)\s*$" line)
                                                       second
                                                       common/strip-yaml-scalar))
                                             lines)))))
       :schemas (mapv #(select-keys % [:name :source-line]) schema-blocks)
       :operation-refs (->> operation-blocks
                            (mapcat (fn [operation]
                                      (map (fn [ref]
                                             (assoc ref
                                                    :source-kind :api-operation
                                                    :source-label (openapi-operation-label
                                                                   (assoc operation
                                                                          :operation-id
                                                                          (some (fn [[_ line]]
                                                                                  (some-> (re-matches #"^\s{6}operationId:\s*(.+?)\s*$" line)
                                                                                          second
                                                                                          common/strip-yaml-scalar))
                                                                                (:lines operation))))))
                                           (openapi-yaml-ref-values (:lines operation)))))
                            vec)
       :schema-refs (->> schema-blocks
                         (mapcat (fn [{:keys [name lines]}]
                                   (map (fn [ref]
                                          (assoc ref
                                                 :source-kind :api-schema
                                                 :source-label name))
                                        (openapi-yaml-ref-values lines))))
                         vec)})))
(defn- openapi-json-facts
  [spec]
  (when spec
    {:servers (->> (:servers spec)
                   (keep (fn [server]
                           (when-let [url (:url server)]
                             {:url url
                              :source-line 1})))
                   vec)
     :paths (mapv (fn [path]
                    {:path (name path)
                     :source-line 1})
                  (keys (:paths spec)))
     :operations (->> (:paths spec)
                      (mapcat (fn [[path methods]]
                                (->> methods
                                     (filter (fn [[method _]]
                                               (contains? openapi-methods (name method))))
                                     (map (fn [[method operation]]
                                            {:path (name path)
                                             :method (name method)
                                             :operation-id (:operationId operation)
                                             :source-line 1
                                             :refs (common/json-ref-values operation)})))))
                      vec)
     :schemas (mapv (fn [schema]
                      {:name (name schema)
                       :source-line 1})
                    (keys (get-in spec [:components :schemas])))
     :operation-refs (->> (:paths spec)
                          (mapcat (fn [[path methods]]
                                    (->> methods
                                         (filter (fn [[method _]]
                                                   (contains? openapi-methods (name method))))
                                         (mapcat (fn [[method operation]]
                                                   (map (fn [ref]
                                                          {:ref ref
                                                           :source-line 1
                                                           :source-kind :api-operation
                                                           :source-label (openapi-operation-label
                                                                          {:path (name path)
                                                                           :method (name method)
                                                                           :operation-id (:operationId operation)})})
                                                        (common/json-ref-values operation)))))))
                          vec)
     :schema-refs (->> (get-in spec [:components :schemas])
                       (mapcat (fn [[schema schema-value]]
                                 (map (fn [ref]
                                        {:ref ref
                                         :source-line 1
                                         :source-kind :api-schema
                                         :source-label (name schema)})
                                      (common/json-ref-values schema-value))))
                       vec)}))
(defn- openapi-facts
  [content]
  (let [lines (str/split-lines content)]
    (or (openapi-json-facts (openapi-json-spec content))
        (openapi-yaml-lines lines)
        {:paths []
         :operations []
         :schemas []
         :diagnostics [{:stage :parse
                        :line 1
                        :message "OpenAPI extractor did not find openapi or swagger declaration."}]})))
(defn extract-openapi
  "Extract declared OpenAPI paths, operations, and schemas."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [facts (openapi-facts content)
        spec-node (common/generic-node run-id id-scope file-id path :api-spec path 1)
        server-nodes (mapv (fn [{:keys [url source-line]}]
                             (common/generic-node run-id id-scope file-id (:path file)
                                                  :api-server url source-line))
                           (:servers facts))
        path-nodes (mapv (fn [{:keys [path source-line]}]
                           (common/generic-node run-id id-scope file-id (:path file)
                                                :api-path path source-line))
                         (:paths facts))
        operation-nodes (mapv (fn [{:keys [path method operation-id source-line]}]
                                (common/generic-node run-id id-scope file-id (:path file)
                                                     :api-operation
                                                     (openapi-operation-label
                                                      {:path path
                                                       :method method
                                                       :operation-id operation-id})
                                                     source-line))
                              (:operations facts))
        schema-nodes (mapv (fn [{:keys [name source-line]}]
                             (common/generic-node run-id id-scope file-id (:path file)
                                                  :api-schema name source-line))
                           (:schemas facts))
        path-edges (mapv #(common/edge-row run-id file-id path
                                           (:xt/id spec-node)
                                           (:xt/id %)
                                           :defines
                                           :extracted
                                           (:source-line %))
                         path-nodes)
        server-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id spec-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           server-nodes)
        path-id-by-label (into {} (map (juxt :label :xt/id)) path-nodes)
        operation-edges (mapv (fn [{:keys [path method operation-id source-line]}]
                                (common/edge-row run-id
                                                 file-id
                                                 (:path file)
                                                 (get path-id-by-label path)
                                                 (common/node-id id-scope
                                                                 :api-operation
                                                                 (openapi-operation-label
                                                                  {:path path
                                                                   :method method
                                                                   :operation-id operation-id}))
                                                 :defines
                                                 :extracted
                                                 source-line))
                              (:operations facts))
        schema-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id spec-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           schema-nodes)
        schema-id-by-label (into {} (map (juxt :label :xt/id)) schema-nodes)
        ref-edges (->> (concat (:operation-refs facts) (:schema-refs facts))
                       (keep (fn [{:keys [source-kind source-label ref source-line]}]
                               (when-let [schema-name (openapi-ref-schema-name ref)]
                                 (when-let [target-id (get schema-id-by-label schema-name)]
                                   (common/edge-row run-id
                                                    file-id
                                                    path
                                                    (common/node-id id-scope source-kind source-label)
                                                    target-id
                                                    :references
                                                    :extracted
                                                    source-line)))))
                       distinct
                       vec)
        chunk-result (common/extract-text-source run-id file :openapi-file)
        diagnostics (mapv #(common/diagnostic-row run-id
                                                  file-id
                                                  path
                                                  (:stage %)
                                                  (:line %)
                                                  (:message %))
                          (:diagnostics facts))]
    {:nodes (vec (concat [spec-node] server-nodes path-nodes operation-nodes schema-nodes))
     :edges (vec (concat server-edges path-edges operation-edges schema-edges ref-edges))
     :chunks (:chunks chunk-result)
     :diagnostics diagnostics}))
(def asyncapi-operation-keys
  #{"publish" "subscribe" "send" "receive"})
(defn- asyncapi-json-spec
  [content]
  (let [parsed (common/read-json-map content)]
    (when (:asyncapi parsed)
      parsed)))
(defn- asyncapi-json-facts
  [spec]
  (when spec
    (let [channels (:channels spec)
          servers (:servers spec)
          messages (get-in spec [:components :messages])
          schemas (get-in spec [:components :schemas])
          operation-traits (get-in spec [:components :operationTraits])
          operations (->> channels
                          (mapcat
                           (fn [[channel operations]]
                             (->> operations
                                  (filter (fn [[operation _]]
                                            (contains? asyncapi-operation-keys
                                                       (common/json-key-label operation))))
                                  (map (fn [[operation details]]
                                         (let [operation-name (common/json-key-label operation)
                                               channel-name (common/json-key-label channel)]
                                           {:channel channel-name
                                            :operation operation-name
                                            :operation-id (:operationId details)
                                            :label (or (:operationId details)
                                                       (str (str/upper-case operation-name)
                                                            " "
                                                            channel-name))
                                            :message-refs (->> (common/json-ref-values (:message details))
                                                               (keep #(common/json-ref-tail
                                                                       "#/components/messages/"
                                                                       %))
                                                               distinct
                                                               vec)
                                            :trait-refs (->> (common/json-ref-values (:traits details))
                                                             (keep #(common/json-ref-tail
                                                                     "#/components/operationTraits/"
                                                                     %))
                                                             distinct
                                                             vec)
                                            :source-line 1}))))))
                          vec)]
      {:channels (mapv (fn [channel]
                         {:label (common/json-key-label channel)
                          :source-line 1})
                       (keys channels))
       :servers (mapv (fn [[server server-value]]
                        {:label (common/json-key-label server)
                         :url (common/json-label (:url server-value))
                         :protocol (common/json-label (:protocol server-value))
                         :source-line 1})
                      servers)
       :operations operations
       :operation-traits (mapv (fn [trait]
                                 {:label (common/json-key-label trait)
                                  :source-line 1})
                               (keys operation-traits))
       :messages (mapv (fn [message]
                         {:label (common/json-key-label message)
                          :source-line 1})
                       (keys messages))
       :schemas (mapv (fn [schema]
                        {:label (common/json-key-label schema)
                         :source-line 1})
                      (keys schemas))
       :bindings (vec
                  (concat
                   (mapcat
                    (fn [[channel channel-value]]
                      (map (fn [[binding binding-value]]
                             {:label (str (common/json-key-label channel)
                                          ":"
                                          (common/json-key-label binding))
                              :source-line 1
                              :value (json/write-json-str binding-value)})
                           (:bindings channel-value)))
                    channels)
                   (mapcat
                    (fn [[message message-value]]
                      (map (fn [[binding binding-value]]
                             {:label (str (common/json-key-label message)
                                          ":"
                                          (common/json-key-label binding))
                              :source-line 1
                              :value (json/write-json-str binding-value)})
                           (:bindings message-value)))
                    messages)))
       :headers (mapv (fn [[message message-value]]
                        (when (:headers message-value)
                          {:label (common/json-key-label message)
                           :source-line 1
                           :refs (common/json-ref-values (:headers message-value))}))
                      messages)
       :correlation-ids (mapv (fn [[message message-value]]
                                (when-let [location (get-in message-value
                                                            [:correlationId
                                                             :location])]
                                  {:label (str (common/json-key-label message)
                                               ":"
                                               (common/json-label location))
                                   :source-line 1}))
                              messages)
       :message-schema-refs (->> messages
                                 (mapcat
                                  (fn [[message message-value]]
                                    (map (fn [ref]
                                           {:source-label (common/json-key-label message)
                                            :source-kind :asyncapi-message
                                            :ref ref
                                            :source-line 1})
                                         (common/json-ref-values message-value))))
                                 vec)
       :schema-refs (->> schemas
                         (mapcat
                          (fn [[schema schema-value]]
                            (map (fn [ref]
                                   {:source-label (common/json-key-label schema)
                                    :source-kind :asyncapi-schema
                                    :ref ref
                                    :source-line 1})
                                 (common/json-ref-values schema-value))))
                         vec)})))
(defn- asyncapi-yaml-facts
  [content]
  (let [lines (str/split-lines content)]
    (when (some #(re-matches #"^\s*asyncapi:\s*.+$" %) lines)
      (loop [remaining (map-indexed vector lines)
             top-section nil
             component-section nil
             current-channel nil
             out {:channels []
                  :operations []
                  :messages []
                  :schemas []}]
        (if-let [[idx line] (first remaining)]
          (cond
            (str/blank? (str/trim line))
            (recur (rest remaining) top-section component-section current-channel out)

            (re-matches #"^[A-Za-z][A-Za-z0-9_-]*:\s*.*$" line)
            (let [section (second (re-matches #"^([A-Za-z][A-Za-z0-9_-]*):\s*.*$"
                                              line))]
              (recur (rest remaining)
                     section
                     nil
                     nil
                     out))

            (and (= "channels" top-section)
                 (re-matches #"^\s{2}([^#\s][^:]*):\s*$" line))
            (let [channel (-> (second (re-matches #"^\s{2}([^#\s][^:]*):\s*$" line))
                              str/trim)]
              (recur (rest remaining)
                     top-section
                     component-section
                     channel
                     (update out :channels conj
                             {:label channel
                              :source-line (inc idx)})))

            (and (= "channels" top-section)
                 current-channel
                 (re-matches #"^\s{4}(publish|subscribe|send|receive):\s*$" line))
            (let [operation (second (re-matches #"^\s{4}(publish|subscribe|send|receive):\s*$"
                                                line))]
              (recur (rest remaining)
                     top-section
                     component-section
                     current-channel
                     (update out :operations conj
                             {:channel current-channel
                              :operation operation
                              :label (str (str/upper-case operation)
                                          " "
                                          current-channel)
                              :message-refs []
                              :source-line (inc idx)})))

            (and (= "components" top-section)
                 (re-matches #"^\s{2}(messages|schemas):\s*$" line))
            (let [section (second (re-matches #"^\s{2}(messages|schemas):\s*$" line))]
              (recur (rest remaining)
                     top-section
                     section
                     nil
                     out))

            (and (= "components" top-section)
                 (contains? #{"messages" "schemas"} component-section)
                 (re-matches #"^\s{4}([A-Za-z0-9_.-]+):\s*$" line))
            (let [label (second (re-matches #"^\s{4}([A-Za-z0-9_.-]+):\s*$" line))
                  target-key (if (= "messages" component-section) :messages :schemas)]
              (recur (rest remaining)
                     top-section
                     component-section
                     nil
                     (update out target-key conj
                             {:label label
                              :source-line (inc idx)})))

            :else
            (recur (rest remaining) top-section component-section current-channel out))
          out)))))
(defn- asyncapi-facts
  [content]
  (or (asyncapi-json-facts (asyncapi-json-spec content))
      (asyncapi-yaml-facts content)
      {:channels []
       :servers []
       :operations []
       :operation-traits []
       :messages []
       :schemas []
       :bindings []
       :headers []
       :correlation-ids []
       :message-schema-refs []
       :schema-refs []
       :diagnostics [{:stage :parse
                      :line 1
                      :message "AsyncAPI extractor did not find asyncapi declaration."}]}))
(defn extract-asyncapi
  "Extract declared AsyncAPI channels, operations, messages, and schemas."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [facts (asyncapi-facts content)
        spec-node (common/generic-node run-id id-scope file-id path :asyncapi-spec path 1)
        server-nodes (mapv (fn [{:keys [label source-line]}]
                             (common/generic-node run-id id-scope file-id path
                                                  :asyncapi-server label source-line))
                           (:servers facts))
        channel-nodes (mapv (fn [{:keys [label source-line]}]
                              (common/generic-node run-id id-scope file-id path
                                                   :asyncapi-channel label source-line))
                            (:channels facts))
        operation-nodes (mapv (fn [{:keys [label source-line]}]
                                (common/generic-node run-id id-scope file-id path
                                                     :asyncapi-operation label source-line))
                              (:operations facts))
        message-nodes (mapv (fn [{:keys [label source-line]}]
                              (common/generic-node run-id id-scope file-id path
                                                   :asyncapi-message label source-line))
                            (:messages facts))
        schema-nodes (mapv (fn [{:keys [label source-line]}]
                             (common/generic-node run-id id-scope file-id path
                                                  :asyncapi-schema label source-line))
                           (:schemas facts))
        trait-nodes (mapv (fn [{:keys [label source-line]}]
                            (common/generic-node run-id id-scope file-id path
                                                 :asyncapi-operation-trait
                                                 label
                                                 source-line))
                          (:operation-traits facts))
        binding-nodes (mapv (fn [{:keys [label source-line]}]
                              (common/generic-node run-id id-scope file-id path
                                                   :asyncapi-binding label source-line))
                            (:bindings facts))
        header-nodes (mapv (fn [{:keys [label source-line]}]
                             (common/generic-node run-id id-scope file-id path
                                                  :asyncapi-header label source-line))
                           (remove nil? (:headers facts)))
        correlation-id-nodes (mapv (fn [{:keys [label source-line]}]
                                     (common/generic-node run-id id-scope file-id path
                                                          :asyncapi-correlation-id
                                                          label
                                                          source-line))
                                   (remove nil? (:correlation-ids facts)))
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id spec-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           (concat server-nodes
                                   channel-nodes
                                   message-nodes
                                   schema-nodes
                                   trait-nodes
                                   binding-nodes
                                   header-nodes
                                   correlation-id-nodes))
        channel-id-by-label (into {} (map (juxt :label :xt/id)) channel-nodes)
        operation-edges (mapv (fn [{:keys [channel label source-line]}]
                                (common/edge-row run-id
                                                 file-id
                                                 path
                                                 (get channel-id-by-label channel)
                                                 (common/node-id id-scope :asyncapi-operation label)
                                                 :defines
                                                 :extracted
                                                 source-line))
                              (:operations facts))
        reference-edges (->> (:operations facts)
                             (mapcat
                              (fn [{:keys [label message-refs trait-refs
                                           source-line]}]
                                (concat
                                 (map (fn [target]
                                        (common/edge-row run-id
                                                         file-id
                                                         path
                                                         (common/node-id id-scope
                                                                         :asyncapi-operation
                                                                         label)
                                                         (common/node-id id-scope
                                                                         :asyncapi-message
                                                                         target)
                                                         :references
                                                         :extracted
                                                         source-line))
                                      message-refs)
                                 (map (fn [target]
                                        (common/edge-row run-id
                                                         file-id
                                                         path
                                                         (common/node-id id-scope
                                                                         :asyncapi-operation
                                                                         label)
                                                         (common/node-id id-scope
                                                                         :asyncapi-operation-trait
                                                                         target)
                                                         :references
                                                         :extracted
                                                         source-line))
                                      trait-refs))))
                             distinct
                             vec)
        schema-id-by-label (into {} (map (juxt :label :xt/id)) schema-nodes)
        schema-reference-edges (->> (concat (:message-schema-refs facts)
                                            (:schema-refs facts))
                                    (keep
                                     (fn [{:keys [source-kind source-label ref
                                                  source-line]}]
                                       (when-let [schema-name
                                                  (common/json-ref-tail
                                                   "#/components/schemas/"
                                                   ref)]
                                         (when-let [target-id
                                                    (get schema-id-by-label
                                                         schema-name)]
                                           (common/edge-row run-id
                                                            file-id
                                                            path
                                                            (common/node-id id-scope
                                                                            (or source-kind
                                                                                :asyncapi-message)
                                                                            source-label)
                                                            target-id
                                                            :references
                                                            :extracted
                                                            source-line)))))
                                    distinct
                                    vec)
        chunk-result (common/extract-text-source run-id file :asyncapi-file)
        diagnostics (mapv #(common/diagnostic-row run-id
                                                  file-id
                                                  path
                                                  (:stage %)
                                                  (:line %)
                                                  (:message %))
                          (:diagnostics facts))]
    {:nodes (vec (concat [spec-node]
                         server-nodes
                         channel-nodes
                         operation-nodes
                         message-nodes
                         schema-nodes
                         trait-nodes
                         binding-nodes
                         header-nodes
                         correlation-id-nodes))
     :edges (vec (concat define-edges
                         operation-edges
                         reference-edges
                         schema-reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics diagnostics}))
(defn- json-schema-facts
  [content path]
  (if-let [schema (common/read-json-map content)]
    (let [root-label (or (get schema common/json-id-key) (:title schema) path)
          definitions (concat (get schema common/json-defs-key)
                              (:definitions schema))
          properties (:properties schema)]
      {:root-label root-label
       :definitions (mapv (fn [[definition _]]
                            {:label (common/json-key-label definition)
                             :source-line 1})
                          definitions)
       :properties (mapv (fn [[property _]]
                           {:label (common/json-key-label property)
                            :source-line 1})
                         properties)
       :references (->> (common/json-ref-values schema)
                        distinct
                        (mapv (fn [ref]
                                {:label ref
                                 :source-line 1})))})
    {:root-label path
     :definitions []
     :properties []
     :references []
     :diagnostics [{:stage :parse
                    :line 1
                    :message "JSON Schema extractor could not parse JSON object."}]}))
(defn extract-json-schema
  "Extract declared JSON Schema definitions, properties, and explicit refs."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [facts (json-schema-facts content path)
        schema-node (common/generic-node run-id id-scope file-id path
                                         :json-schema
                                         (:root-label facts)
                                         1)
        definition-nodes (mapv (fn [{:keys [label source-line]}]
                                 (common/generic-node run-id id-scope file-id path
                                                      :json-schema-definition
                                                      label
                                                      source-line))
                               (:definitions facts))
        property-nodes (mapv (fn [{:keys [label source-line]}]
                               (common/generic-node run-id id-scope file-id path
                                                    :json-schema-property
                                                    label
                                                    source-line))
                             (:properties facts))
        reference-nodes (mapv (fn [{:keys [label source-line]}]
                                (common/generic-node run-id id-scope file-id path
                                                     :json-schema-reference
                                                     label
                                                     source-line))
                              (:references facts))
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id schema-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           (concat definition-nodes property-nodes))
        reference-edges (mapv #(common/edge-row run-id file-id path
                                                (:xt/id schema-node)
                                                (:xt/id %)
                                                :references
                                                :extracted
                                                (:source-line %))
                              reference-nodes)
        chunk-result (common/extract-text-source run-id file :json-schema-file)
        diagnostics (mapv #(common/diagnostic-row run-id
                                                  file-id
                                                  path
                                                  (:stage %)
                                                  (:line %)
                                                  (:message %))
                          (:diagnostics facts))]
    {:nodes (vec (concat [schema-node]
                         definition-nodes
                         property-nodes
                         reference-nodes))
     :edges (vec (concat define-edges reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics diagnostics}))
