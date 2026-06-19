(ns agraph.extract.ops-config
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(defn- ops-strip-scalar
  [value]
  (-> (str value)
      (str/replace #"^\s*['\"]|['\"]\s*$" "")
      str/trim))
(defn- ops-yaml-key-line
  [idx line]
  (when-let [[_ indent key value]
             (re-matches #"^(\s*)(?:-\s*)?([A-Za-z0-9_.-]+):(?:\s*(.*))?$"
                         line)]
    {:indent (count indent)
     :key key
     :value (str/trim (or value ""))
     :source-line (inc idx)}))
(defn- ops-yaml-section-blocks
  [content section-name]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-section? false
         section-indent nil
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (ops-yaml-key-line idx line)]
        (cond
          (and entry (= section-name (:key entry)))
          (recur (rest remaining) true (:indent entry) nil out)

          (and in-section?
               entry
               (<= (:indent entry) section-indent)
               (not= section-name (:key entry)))
          (recur (rest remaining) false nil nil (cond-> out current (conj current)))

          (and in-section?
               entry
               (= (:indent entry) (+ section-indent 2)))
          (recur (rest remaining)
                 true
                 section-indent
                 {:label (:key entry)
                  :source-line (:source-line entry)
                  :lines [[idx line]]}
                 (cond-> out current (conj current)))

          (and in-section? current)
          (recur (rest remaining)
                 true
                 section-indent
                 (update current :lines conj [idx line])
                 out)

          :else
          (recur (rest remaining) in-section? section-indent current out)))
      (cond-> out current (conj current)))))
(defn- ops-yaml-section-settings
  [content section-name]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-section? false
         section-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (ops-yaml-key-line idx line)]
        (cond
          (and entry (= section-name (:key entry)))
          (recur (rest remaining) true (:indent entry) out)

          (and in-section?
               entry
               (<= (:indent entry) section-indent)
               (not= section-name (:key entry)))
          (recur (rest remaining) false nil out)

          (and in-section?
               entry
               (> (:indent entry) section-indent)
               (seq (:value entry)))
          (recur (rest remaining)
                 true
                 section-indent
                 (conj out {:key (:key entry)
                            :value (ops-strip-scalar (:value entry))
                            :source-line (:source-line entry)}))

          :else
          (recur (rest remaining) in-section? section-indent out)))
      out)))
(defn- ops-block-value
  [block key-name]
  (->> (:lines block)
       (keep (fn [[idx line]]
               (when-let [{:keys [key value source-line]} (ops-yaml-key-line idx line)]
                 (when (and (= key-name key) (seq value))
                   {:value (ops-strip-scalar value)
                    :source-line source-line}))))
       first))
(defn- ops-reference-targets
  [lines]
  (->> lines
       (mapcat (fn [[idx line]]
                 (let [source-line (inc idx)]
                   (concat
                    (map (fn [[_ target]]
                           {:target target :source-line source-line})
                         (re-seq #"Ref:\s*([A-Za-z0-9]+)" line))
                    (map (fn [[_ target]]
                           {:target target :source-line source-line})
                         (re-seq #"!Ref\s+([A-Za-z0-9]+)" line))
                    (map (fn [[_ target]]
                           {:target target :source-line source-line})
                         (re-seq #"Fn::GetAtt:\s*\[\s*([A-Za-z0-9]+)\s*," line))
                    (map (fn [[_ target]]
                           {:target target :source-line source-line})
                         (re-seq #"!GetAtt\s+([A-Za-z0-9]+)\." line))))))
       distinct
       vec))
(defn- ops-block-section-entry-labels
  [block section-name]
  (loop [remaining (:lines block)
         in-section? false
         section-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (ops-yaml-key-line idx line)]
        (cond
          (and entry (= section-name (:key entry)))
          (recur (rest remaining) true (:indent entry) out)

          (and in-section?
               entry
               (<= (:indent entry) section-indent)
               (not= section-name (:key entry)))
          out

          (and in-section?
               entry
               (= (:indent entry) (+ section-indent 2)))
          (recur (rest remaining)
                 true
                 section-indent
                 (conj out {:label (:key entry)
                            :source-line (:source-line entry)}))

          :else
          (recur (rest remaining) in-section? section-indent out)))
      out)))
(defn- ops-section-name-facts
  [content section-name kind]
  (->> (ops-yaml-section-blocks content section-name)
       (mapv (fn [{:keys [label source-line]}]
               {:kind kind
                :label label
                :source-line source-line
                :relation :defines}))))
(defn- json-intrinsic-reference-targets
  [value]
  (let [get-att-key (keyword "Fn::GetAtt")]
    (cond
      (map? value)
      (vec
       (distinct
        (concat
         (when (string? (:Ref value))
           [(:Ref value)])
         (let [get-att (get value get-att-key)]
           (cond
             (and (vector? get-att) (string? (first get-att))) [(first get-att)]
             (string? get-att) [(first (str/split get-att #"\."))]
             :else []))
         (mapcat json-intrinsic-reference-targets (vals value)))))

      (vector? value)
      (vec (distinct (mapcat json-intrinsic-reference-targets value)))

      :else [])))
(defn- ops-config-format
  [{:keys [path content ext]}]
  (let [filename (str/lower-case (.getName (java.io.File. (str path))))]
    (cond
      (contains? #{"serverless.yml" "serverless.yaml"} filename) :serverless
      (= "cdk.json" filename) :cdk
      (or (str/includes? content "AWS::Serverless")
          (re-find #"(?m)^Transform:\s*['\"]?AWS::Serverless" content)) :sam
      (or (str/includes? content "AWSTemplateFormatVersion")
          (and (re-find #"(?m)^Resources:\s*$" content)
               (str/includes? content "AWS::"))
          (and (str/includes? content "\"Resources\"")
               (str/includes? content "AWS::"))) :cloudformation
      (re-matches #"pulumi(?:\.[a-z0-9_.-]+)?\.ya?ml" filename) :pulumi
      (= "nginx.conf" filename) :nginx
      (contains? #{".service" ".socket" ".timer"} ext) :systemd
      (and (or (re-find #"(?m)^\s*-\s*hosts:\s*.+" content)
               (re-find #"(?m)^\s*hosts:\s*.+" content))
           (re-find #"(?m)^\s*tasks:\s*$" content)) :ansible
      :else :ops)))
(defn- cloudformation-yaml-facts
  [content]
  (let [lines (vec (str/split-lines content))
        base (loop [remaining (map-indexed vector lines)
                    in-resources? false
                    current-resource nil
                    facts []
                    refs []]
               (if-let [[idx line] (first remaining)]
                 (cond
                   (re-matches #"^\s*Resources:\s*$" line)
                   (recur (rest remaining) true nil facts refs)

                   (and in-resources?
                        (re-matches #"^[A-Za-z0-9_.-]+:\s*.*$" line)
                        (not (re-matches #"^\s*Resources:\s*$" line)))
                   (recur (rest remaining) false nil facts refs)

                   (and in-resources?
                        (re-matches #"^\s{2}([A-Za-z0-9]+):\s*$" line))
                   (let [resource (second (re-matches #"^\s{2}([A-Za-z0-9]+):\s*$"
                                                      line))]
                     (recur (rest remaining)
                            true
                            resource
                            (conj facts {:kind :cloudformation-resource
                                         :label resource
                                         :source-line (inc idx)
                                         :relation :defines})
                            refs))

                   (and current-resource
                        (re-matches #"^\s+Type:\s*([A-Za-z0-9:_.-]+)\s*$" line))
                   (let [resource-type (second
                                        (re-matches #"^\s+Type:\s*([A-Za-z0-9:_.-]+)\s*$"
                                                    line))]
                     (recur (rest remaining)
                            in-resources?
                            current-resource
                            (conj facts {:kind :cloudformation-resource-type
                                         :label resource-type
                                         :source-line (inc idx)
                                         :relation :defines})
                            refs))

                   (and current-resource
                        (re-matches #"^\s+DependsOn:\s*([A-Za-z0-9]+)\s*$" line))
                   (let [target (second (re-matches #"^\s+DependsOn:\s*([A-Za-z0-9]+)\s*$"
                                                    line))]
                     (recur (rest remaining)
                            in-resources?
                            current-resource
                            facts
                            (conj refs {:source-kind :cloudformation-resource
                                        :source-label current-resource
                                        :target-kind :cloudformation-resource
                                        :target-label target
                                        :source-line (inc idx)})))

                   :else
                   (recur (rest remaining) in-resources? current-resource facts refs))
                 {:facts facts
                  :refs refs}))
        resources (ops-yaml-section-blocks content "Resources")
        parameters (ops-yaml-section-blocks content "Parameters")
        conditions (ops-yaml-section-blocks content "Conditions")
        outputs (ops-yaml-section-blocks content "Outputs")
        resource-labels (set (map :label resources))
        parameter-labels (set (map :label parameters))
        condition-labels (set (map :label conditions))
        target-kind (fn [target]
                      (cond
                        (contains? resource-labels target) :cloudformation-resource
                        (contains? parameter-labels target) :cloudformation-parameter
                        (contains? condition-labels target) :cloudformation-condition
                        :else nil))
        block-ref-facts (fn [source-kind {:keys [label lines]}]
                          (->> (ops-reference-targets lines)
                               (keep (fn [{:keys [target source-line]}]
                                       (when-let [target-kind (target-kind target)]
                                         {:source-kind source-kind
                                          :source-label label
                                          :target-kind target-kind
                                          :target-label target
                                          :source-line source-line})))))
        condition-ref (fn [{:keys [label] :as block}]
                        (when-let [{:keys [value source-line]} (ops-block-value block "Condition")]
                          (when (contains? condition-labels value)
                            {:source-kind :cloudformation-resource
                             :source-label label
                             :target-kind :cloudformation-condition
                             :target-label value
                             :source-line source-line})))
        extra-facts (concat
                     (ops-section-name-facts content "Parameters"
                                             :cloudformation-parameter)
                     (ops-section-name-facts content "Mappings"
                                             :cloudformation-mapping)
                     (ops-section-name-facts content "Conditions"
                                             :cloudformation-condition)
                     (ops-section-name-facts content "Outputs"
                                             :cloudformation-output))
        extra-refs (concat
                    (mapcat #(block-ref-facts :cloudformation-resource %) resources)
                    (keep condition-ref resources)
                    (mapcat #(block-ref-facts :cloudformation-condition %) conditions)
                    (mapcat #(block-ref-facts :cloudformation-output %) outputs))]
    {:facts (vec (distinct (concat (:facts base) extra-facts)))
     :refs (vec (distinct (concat (:refs base) extra-refs)))}))
(defn- cloudformation-json-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (let [resources (:Resources m)
          parameters (:Parameters m)
          mappings (:Mappings m)
          conditions (:Conditions m)
          outputs (:Outputs m)
          section-facts (fn [section kind]
                          (when (map? section)
                            (map (fn [[k _]]
                                   {:kind kind
                                    :label (common/json-key-label k)
                                    :source-line 1
                                    :relation :defines})
                                 section)))
          resource-labels (set (map common/json-key-label (keys (or resources {}))))
          parameter-labels (set (map common/json-key-label (keys (or parameters {}))))
          condition-labels (set (map common/json-key-label (keys (or conditions {}))))
          target-kind (fn [target]
                        (cond
                          (contains? resource-labels target) :cloudformation-resource
                          (contains? parameter-labels target) :cloudformation-parameter
                          (contains? condition-labels target) :cloudformation-condition
                          :else nil))
          reference-facts (fn [source-kind source-label value]
                            (->> (json-intrinsic-reference-targets value)
                                 (keep (fn [target]
                                         (when-let [target-kind (target-kind target)]
                                           {:source-kind source-kind
                                            :source-label source-label
                                            :target-kind target-kind
                                            :target-label target
                                            :source-line 1})))))]
      (if-not (map? resources)
        {:facts (vec
                 (distinct
                  (concat
                   (section-facts parameters :cloudformation-parameter)
                   (section-facts mappings :cloudformation-mapping)
                   (section-facts conditions :cloudformation-condition)
                   (section-facts outputs :cloudformation-output))))
         :refs []}
        (let [facts (->> resources
                         (mapcat (fn [[resource spec]]
                                   (let [resource-name (common/json-key-label resource)
                                         resource-type (:Type spec)]
                                     (concat
                                      [{:kind :cloudformation-resource
                                        :label resource-name
                                        :source-line 1
                                        :relation :defines}]
                                      (when (string? resource-type)
                                        [{:kind :cloudformation-resource-type
                                          :label resource-type
                                          :source-line 1
                                          :relation :defines}])))))
                         distinct
                         vec)
              depends-refs (->> resources
                                (mapcat (fn [[resource spec]]
                                          (let [depends (:DependsOn spec)
                                                targets (cond
                                                          (string? depends) [depends]
                                                          (vector? depends) (filter string? depends)
                                                          :else [])]
                                            (map (fn [target]
                                                   {:source-kind :cloudformation-resource
                                                    :source-label (common/json-key-label resource)
                                                    :target-kind :cloudformation-resource
                                                    :target-label target
                                                    :source-line 1})
                                                 targets)))))
              intrinsic-refs (concat
                              (mapcat (fn [[resource spec]]
                                        (reference-facts :cloudformation-resource
                                                         (common/json-key-label resource)
                                                         spec))
                                      resources)
                              (mapcat (fn [[condition spec]]
                                        (reference-facts :cloudformation-condition
                                                         (common/json-key-label condition)
                                                         spec))
                                      conditions)
                              (mapcat (fn [[output spec]]
                                        (reference-facts :cloudformation-output
                                                         (common/json-key-label output)
                                                         spec))
                                      outputs))]
          {:facts (vec
                   (distinct
                    (concat facts
                            (section-facts parameters :cloudformation-parameter)
                            (section-facts mappings :cloudformation-mapping)
                            (section-facts conditions :cloudformation-condition)
                            (section-facts outputs :cloudformation-output))))
           :refs (vec (distinct (concat depends-refs intrinsic-refs)))})))
    (cloudformation-yaml-facts content)))
(defn- cloudformation-facts
  [content]
  (if (common/read-json-map content)
    (cloudformation-json-facts content)
    (cloudformation-yaml-facts content)))
(defn- serverless-function-facts
  [function-block]
  (let [{:keys [label source-line lines]} function-block
        handler (ops-block-value function-block "handler")
        role (ops-block-value function-block "role")
        event-kinds (->> lines
                         (keep (fn [[idx line]]
                                 (when-let [[_ event-kind]
                                            (re-matches #"^\s*-\s*([A-Za-z0-9_.-]+):.*$"
                                                        line)]
                                   {:kind :serverless-event
                                    :label (str label ":" event-kind)
                                    :source-line (inc idx)
                                    :relation :defines}))))
        route-fact (fn [[idx line]]
                     (let [source-line (inc idx)]
                       (or
                        (when-let [[_ route]
                                   (re-matches #"^\s*path:\s*(.+?)\s*$" line)]
                          {:kind :serverless-event-route
                           :label (str label ":" (ops-strip-scalar route))
                           :source-line source-line
                           :relation :defines})
                        (when-let [[_ method]
                                   (re-matches #"^\s*method:\s*(.+?)\s*$" line)]
                          {:kind :serverless-event-method
                           :label (str label ":" (str/upper-case
                                                  (ops-strip-scalar method)))
                           :source-line source-line
                           :relation :defines}))))
        route-facts (keep route-fact lines)]
    (concat
     [{:kind :serverless-function
       :label label
       :source-line source-line
       :relation :defines}]
     (when handler
       [{:kind :serverless-handler
         :label (:value handler)
         :source-line (:source-line handler)
         :relation :defines}])
     (when role
       [{:kind :serverless-role
         :label (:value role)
         :source-line (:source-line role)
         :relation :references}])
     event-kinds
     route-facts)))
(defn- serverless-resource-facts
  [resource-blocks]
  (->> resource-blocks
       (mapcat (fn [{:keys [label source-line] :as block}]
                 (concat
                  [{:kind :serverless-resource
                    :label label
                    :source-line source-line
                    :relation :defines}]
                  (when-let [resource-type (ops-block-value block "Type")]
                    [{:kind :serverless-resource-type
                      :label (:value resource-type)
                      :source-line (:source-line resource-type)
                      :relation :defines}]))))
       distinct
       vec))
(defn- serverless-facts
  [content]
  (let [provider-settings (ops-yaml-section-settings content "provider")
        provider-facts (->> provider-settings
                            (keep (fn [{:keys [key value source-line]}]
                                    (case key
                                      "name" {:kind :serverless-provider
                                              :label value
                                              :source-line source-line
                                              :relation :uses}
                                      "runtime" {:kind :serverless-runtime
                                                 :label value
                                                 :source-line source-line
                                                 :relation :defines}
                                      "stage" {:kind :serverless-stage
                                               :label value
                                               :source-line source-line
                                               :relation :defines}
                                      nil))))
        functions (ops-yaml-section-blocks content "functions")
        resources (ops-yaml-section-blocks content "Resources")
        outputs (ops-yaml-section-blocks content "Outputs")
        resource-labels (set (map :label resources))
        output-facts (mapv (fn [{:keys [label source-line]}]
                             {:kind :serverless-output
                              :label label
                              :source-line source-line
                              :relation :defines})
                           outputs)
        function-role-ref (fn [{:keys [label] :as block}]
                            (when-let [role (ops-block-value block "role")]
                              (when (contains? resource-labels (:value role))
                                {:source-kind :serverless-function
                                 :source-label label
                                 :target-kind :serverless-resource
                                 :target-label (:value role)
                                 :source-line (:source-line role)})))
        output-ref-facts (fn [{:keys [label lines]}]
                           (->> (ops-reference-targets lines)
                                (keep (fn [{:keys [target source-line]}]
                                        (when (contains? resource-labels target)
                                          {:source-kind :serverless-output
                                           :source-label label
                                           :target-kind :serverless-resource
                                           :target-label target
                                           :source-line source-line})))))
        function-role-refs (keep function-role-ref functions)
        output-refs (mapcat output-ref-facts outputs)
        service-facts (when-let [service (common/yaml-top-level-value content "service")]
                        [{:kind :serverless-service
                          :label service
                          :source-line 1
                          :relation :defines}])]
    {:facts (vec (distinct
                  (concat service-facts
                          provider-facts
                          (mapcat serverless-function-facts functions)
                          (serverless-resource-facts resources)
                          output-facts)))
     :refs (vec (distinct (concat function-role-refs output-refs)))}))
(defn- sam-resource-facts
  [resource-blocks]
  (->> resource-blocks
       (mapcat (fn [{:keys [label source-line] :as block}]
                 (let [resource-type (ops-block-value block "Type")
                       sam-function? (= "AWS::Serverless::Function" (:value resource-type))
                       handler (ops-block-value block "Handler")
                       runtime (ops-block-value block "Runtime")
                       role (ops-block-value block "Role")
                       events (ops-block-section-entry-labels block "Events")]
                   (concat
                    [{:kind :sam-resource
                      :label label
                      :source-line source-line
                      :relation :defines}]
                    (when resource-type
                      [{:kind :sam-resource-type
                        :label (:value resource-type)
                        :source-line (:source-line resource-type)
                        :relation :defines}])
                    (when sam-function?
                      [{:kind :sam-function
                        :label label
                        :source-line source-line
                        :relation :defines}])
                    (when (and sam-function? handler)
                      [{:kind :sam-handler
                        :label (:value handler)
                        :source-line (:source-line handler)
                        :relation :defines}])
                    (when (and sam-function? runtime)
                      [{:kind :sam-runtime
                        :label (:value runtime)
                        :source-line (:source-line runtime)
                        :relation :defines}])
                    (when (and sam-function? role)
                      [{:kind :sam-role
                        :label (:value role)
                        :source-line (:source-line role)
                        :relation :references}])
                    (map (fn [{:keys [label source-line]}]
                           {:kind :sam-event
                            :label label
                            :source-line source-line
                            :relation :defines})
                         events)))))
       distinct
       vec))
(defn- sam-facts
  [content]
  (let [resources (ops-yaml-section-blocks content "Resources")
        outputs (ops-yaml-section-blocks content "Outputs")
        resource-labels (set (map :label resources))
        output-facts (mapv (fn [{:keys [label source-line]}]
                             {:kind :sam-output
                              :label label
                              :source-line source-line
                              :relation :defines})
                           outputs)
        block-ref-facts (fn [source-kind {:keys [label lines]}]
                          (->> (ops-reference-targets lines)
                               (keep (fn [{:keys [target source-line]}]
                                       (when (contains? resource-labels target)
                                         {:source-kind source-kind
                                          :source-label label
                                          :target-kind :sam-resource
                                          :target-label target
                                          :source-line source-line})))))
        resource-refs (mapcat #(block-ref-facts :sam-resource %) resources)
        output-refs (mapcat #(block-ref-facts :sam-output %) outputs)]
    {:facts (vec (distinct (concat (sam-resource-facts resources)
                                   output-facts)))
     :refs (vec (distinct (concat resource-refs output-refs)))}))
(defn- cdk-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (let [app (:app m)
          context (:context m)
          watch (:watch m)
          watch-values (fn [key kind]
                         (->> (get watch key)
                              (filter string?)
                              (map (fn [value]
                                     {:kind kind
                                      :label value
                                      :source-line 1
                                      :relation :defines}))))]
      {:facts (vec
               (distinct
                (concat
                 (when (string? app)
                   [{:kind :cdk-app
                     :label app
                     :source-line 1
                     :relation :defines}])
                 (when (map? context)
                   (mapcat (fn [[k v]]
                             (let [label (common/json-key-label k)]
                               (concat
                                [{:kind :cdk-context-key
                                  :label label
                                  :source-line 1
                                  :relation :defines}]
                                (when (or (string? v) (number? v) (boolean? v))
                                  [{:kind :cdk-context-setting
                                    :label (str label "=" v)
                                    :source-line 1
                                    :relation :defines}]))))
                           context))
                 (watch-values :include :cdk-watch-include)
                 (watch-values :exclude :cdk-watch-exclude))))
       :refs []})
    {:facts [] :refs []}))
(defn- pulumi-stack-name
  [path]
  (let [filename (str/lower-case (.getName (java.io.File. (str path))))]
    (some-> (re-matches #"pulumi\.([a-z0-9_.-]+)\.ya?ml" filename)
            second)))
(defn- pulumi-config-entries
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-config? false
         config-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (ops-yaml-key-line idx line)]
        (cond
          (and entry (= "config" (:key entry)))
          (recur (rest remaining) true (:indent entry) out)

          (and in-config?
               entry
               (<= (:indent entry) config-indent)
               (not= "config" (:key entry)))
          (recur (rest remaining) false nil out)

          (and in-config?
               (> (count (take-while #(= \space %) line)) config-indent)
               (re-matches #"^\s*([A-Za-z0-9_.:-]+):\s*(.+?)\s*$" line))
          (let [[_ key value] (re-matches #"^\s*([A-Za-z0-9_.:-]+):\s*(.+?)\s*$"
                                          line)
                value (ops-strip-scalar value)]
            (recur (rest remaining)
                   true
                   config-indent
                   (conj out {:key key
                              :value value
                              :secure? (or (str/includes? value "secure:")
                                           (str/includes? value "{secure"))
                              :source-line (inc idx)})))

          :else
          (recur (rest remaining) in-config? config-indent out)))
      out)))
(defn- pulumi-facts
  [{:keys [path content]}]
  (let [stack-name (pulumi-stack-name path)]
    (->> (concat
          (when stack-name
            [{:kind :pulumi-stack
              :label stack-name
              :source-line 1
              :relation :defines}])
          (when-let [name (common/yaml-top-level-value content "name")]
            [{:kind :pulumi-project
              :label name
              :source-line 1
              :relation :defines}])
          (when-let [runtime (common/yaml-top-level-value content "runtime")]
            [{:kind :pulumi-runtime
              :label runtime
              :source-line 1
              :relation :defines}])
          (when-let [secrets-provider (common/yaml-top-level-value content "secretsprovider")]
            [{:kind :pulumi-secrets-provider
              :label secrets-provider
              :source-line 1
              :relation :uses}])
          (mapcat (fn [{:keys [key value secure? source-line]}]
                    (concat
                     [{:kind :pulumi-config-key
                       :label key
                       :source-line source-line
                       :relation :defines}]
                     (if secure?
                       [{:kind :pulumi-secret-config
                         :label key
                         :source-line source-line
                         :relation :defines}]
                       [{:kind :pulumi-config-value
                         :label (str key "=" value)
                         :source-line source-line
                         :relation :defines}])))
                  (pulumi-config-entries content)))
         distinct
         vec)))
(defn- ansible-facts
  [content]
  (let [lines (str/split-lines content)]
    (->> lines
         (map-indexed vector)
         (mapcat
          (fn [[idx line]]
            (let [source-line (inc idx)]
              (concat
               (when-let [[_ hosts]
                          (or (re-matches #"^\s*-\s*hosts:\s*(.+?)\s*$" line)
                              (re-matches #"^\s*hosts:\s*(.+?)\s*$" line))]
                 [{:kind :ansible-play
                   :label (str "hosts=" hosts)
                   :source-line source-line
                   :relation :defines}
                  {:kind :ops-host
                   :label hosts
                   :source-line source-line
                   :relation :references}])
               (when-let [[_ task-name]
                          (re-matches #"^\s*-\s*name:\s*(.+?)\s*$" line)]
                 [{:kind :ansible-task
                   :label task-name
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ module-name]
                          (re-matches #"^\s{4,}([A-Za-z_][A-Za-z0-9_.-]*):\s*(?:.*)$"
                                      line)]
                 (when-not (contains? #{"name" "when" "with_items" "register"}
                                      module-name)
                   [{:kind :ansible-module
                     :label module-name
                     :source-line source-line
                     :relation :references}]))))))
         distinct
         vec)))
(defn- nginx-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat
        (fn [[idx line]]
          (let [source-line (inc idx)]
            (concat
             (when-let [[_ port]
                        (re-matches #"^\s*listen\s+([0-9]+)[^;]*;\s*$" line)]
               [{:kind :ops-port
                 :label port
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ route]
                        (re-matches #"^\s*location\s+([^\s{]+)\s*\{\s*$" line)]
               [{:kind :ops-route
                 :label route
                 :source-line source-line
                 :relation :defines}])
             (when-let [[_ target]
                        (re-matches #"^\s*proxy_pass\s+([^;]+);\s*$" line)]
               [{:kind :config-reference
                 :label target
                 :source-line source-line
                 :relation :references}])))))
       distinct
       vec))
(defn- systemd-facts
  [path content]
  (let [unit-name (.getName (java.io.File. (str path)))]
    (->> (str/split-lines content)
         (map-indexed vector)
         (mapcat
          (fn [[idx line]]
            (let [source-line (inc idx)]
              (concat
               (when (= 0 idx)
                 [{:kind :systemd-unit
                   :label unit-name
                   :source-line 1
                   :relation :defines}])
               (when-let [[_ section]
                          (re-matches #"^\[([A-Za-z0-9_.-]+)\]\s*$" line)]
                 [{:kind :systemd-section
                   :label section
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ command]
                          (re-matches #"^\s*Exec(?:Start|Stop|Reload)=\s*(.+?)\s*$"
                                      line)]
                 [{:kind :systemd-command
                   :label command
                   :source-line source-line
                   :relation :defines}])
               (when-let [[_ target]
                          (re-matches #"^\s*(?:After|Requires|Wants|WantedBy)=\s*(.+?)\s*$"
                                      line)]
                 [{:kind :systemd-target
                   :label target
                   :source-line source-line
                   :relation :references}])))))
         distinct
         vec)))
(defn- ops-config-facts
  [{:keys [path content] :as file}]
  (case (ops-config-format file)
    :serverless (serverless-facts content)
    :sam (sam-facts content)
    :cdk (cdk-facts content)
    :cloudformation (cloudformation-facts content)
    :pulumi {:facts (pulumi-facts file) :refs []}
    :ansible {:facts (ansible-facts content) :refs []}
    :nginx {:facts (nginx-facts content) :refs []}
    :systemd {:facts (systemd-facts path content) :refs []}
    {:facts (common/config-facts file) :refs []}))
(defn extract-ops-config
  "Extract bounded operational configuration facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [{:keys [facts refs]} (ops-config-facts file)
        config-node (common/generic-node run-id id-scope file-id path :ops-config path 1)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (common/generic-node run-id id-scope file-id path
                                         kind label source-line))
                         facts)
        define-edges (mapv (fn [{:keys [kind label source-line relation]}]
                             (common/edge-row run-id
                                       file-id
                                       path
                                       (:xt/id config-node)
                                       (common/node-id id-scope kind label)
                                       relation
                                       :extracted
                                       source-line))
                           facts)
        reference-edges (mapv (fn [{:keys [source-kind source-label target-kind
                                           target-label source-line]}]
                                (common/edge-row run-id
                                          file-id
                                          path
                                          (common/node-id id-scope source-kind source-label)
                                          (common/node-id id-scope target-kind target-label)
                                          :references
                                          :extracted
                                          source-line))
                              refs)
        chunk-result (common/extract-text-source run-id file :ops-config-file)]
    {:nodes (into [config-node] fact-nodes)
     :edges (vec (concat define-edges reference-edges))
     :chunks (:chunks chunk-result)
     :diagnostics []}))
