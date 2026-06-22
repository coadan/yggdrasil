(ns ygg.extract.observability
  (:require [ygg.extract.common :as common]
            [ygg.fs :as fs]
            [clojure.string :as str]))

(defn- obs-section-blocks
  [content section-name]
  (common/yaml-top-section-blocks (str/split-lines content) section-name))
(defn- obs-block-scalar
  [block key-name]
  (some (fn [[idx line]]
          (when-let [{:keys [key value source-line]} (common/yaml-key-line idx line)]
            (when (and (= key-name key) (seq value))
              {:value (common/strip-yaml-scalar value)
               :source-line source-line})))
        (:lines block)))
(defn- obs-block-list-values
  [block key-name]
  (loop [remaining (:lines block)
         list-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)
            indent (common/leading-spaces line)
            inline-values (when (and entry
                                     (= key-name (:key entry))
                                     (seq (:value entry)))
                            (map (fn [value]
                                   {:value value
                                    :source-line (:source-line entry)})
                                 (common/yaml-scalar-list-values (:value entry))))
            list-indent* (cond
                           (and entry
                                (= key-name (:key entry))
                                (str/blank? (:value entry)))
                           (:indent entry)

                           (and list-indent
                                entry
                                (<= (:indent entry) list-indent))
                           nil

                           :else list-indent)
            list-value (when (and list-indent*
                                  (> indent list-indent*))
                         (some->> (re-matches #"^\s*-\s+(.+?)\s*$" line)
                                  second
                                  common/strip-yaml-scalar))]
        (recur (rest remaining)
               list-indent*
               (cond-> out
                 inline-values (into inline-values)
                 list-value (conj {:value list-value
                                   :source-line (inc idx)}))))
      (vec (distinct out)))))
(defn- obs-yaml-named-list-blocks
  [content section-name]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-section? false
         section-indent nil
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)
            section-start? (and entry (= section-name (:key entry)))
            top-exit? (and in-section?
                           entry
                           (<= (:indent entry) section-indent)
                           (not= section-name (:key entry)))
            name-start (when (and in-section?
                                  (re-matches #"^\s*-\s*name:\s*.+$" line))
                         {:label (common/strip-yaml-scalar
                                  (second (re-matches #"^\s*-\s*name:\s*(.+?)\s*$"
                                                      line)))
                          :source-line (inc idx)
                          :lines [[idx line]]})]
        (cond
          section-start?
          (recur (rest remaining) true (:indent entry) nil out)

          top-exit?
          (recur (rest remaining) false nil nil (cond-> out current (conj current)))

          name-start
          (recur (rest remaining)
                 true
                 section-indent
                 name-start
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
(defn- obs-fact
  [kind label source-line relation]
  {:kind kind
   :label label
   :source-line (or source-line 1)
   :relation relation})
(defn- otel-pipeline-blocks
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         in-service? false
         pipelines-indent nil
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)
            in-service* (cond
                          (and entry (zero? (:indent entry)) (= "service" (:key entry)))
                          true

                          (and in-service? entry (zero? (:indent entry)))
                          false

                          :else in-service?)
            pipelines-indent* (cond
                                (and in-service*
                                     entry
                                     (= "pipelines" (:key entry))
                                     (str/blank? (:value entry)))
                                (:indent entry)

                                (and pipelines-indent
                                     entry
                                     (<= (:indent entry) pipelines-indent))
                                nil

                                :else pipelines-indent)
            pipeline-start (when (and pipelines-indent*
                                      entry
                                      (= (:indent entry) (+ pipelines-indent* 2)))
                             {:label (:key entry)
                              :source-line (:source-line entry)
                              :lines [[idx line]]})]
        (cond
          pipeline-start
          (recur (rest remaining)
                 in-service*
                 pipelines-indent*
                 pipeline-start
                 (cond-> out current (conj current)))

          (and pipelines-indent* current)
          (recur (rest remaining)
                 in-service*
                 pipelines-indent*
                 (update current :lines conj [idx line])
                 out)

          :else
          (recur (rest remaining)
                 in-service*
                 pipelines-indent*
                 current
                 out)))
      (cond-> out current (conj current)))))
(defn- otel-facts
  [content]
  (vec
   (distinct
    (concat
     [(obs-fact :observability-platform "otel-collector" 1 :uses)]
     (map (fn [{:keys [label source-line]}]
            (obs-fact :otel-receiver label source-line :defines))
          (obs-section-blocks content "receivers"))
     (map (fn [{:keys [label source-line]}]
            (obs-fact :otel-processor label source-line :defines))
          (obs-section-blocks content "processors"))
     (map (fn [{:keys [label source-line]}]
            (obs-fact :otel-exporter label source-line :defines))
          (obs-section-blocks content "exporters"))
     (mapcat
      (fn [{:keys [label source-line] :as block}]
        (concat
         [(obs-fact :otel-pipeline label source-line :defines)]
         (map (fn [{:keys [value source-line]}]
                {:kind :otel-receiver
                 :label value
                 :source-line source-line
                 :relation :uses
                 :source-kind :otel-pipeline
                 :source label})
              (obs-block-list-values block "receivers"))
         (map (fn [{:keys [value source-line]}]
                {:kind :otel-processor
                 :label value
                 :source-line source-line
                 :relation :uses
                 :source-kind :otel-pipeline
                 :source label})
              (obs-block-list-values block "processors"))
         (map (fn [{:keys [value source-line]}]
                {:kind :otel-exporter
                 :label value
                 :source-line source-line
                 :relation :uses
                 :source-kind :otel-pipeline
                 :source label})
              (obs-block-list-values block "exporters"))))
      (otel-pipeline-blocks content))))))
(defn- prometheus-scrape-facts
  [content]
  (let [blocks (loop [remaining (map-indexed vector (str/split-lines content))
                      in-section? false
                      section-indent nil
                      current nil
                      out []]
                 (if-let [[idx line] (first remaining)]
                   (let [entry (common/yaml-key-line idx line)
                         section-start? (and entry (= "scrape_configs" (:key entry)))
                         top-exit? (and in-section?
                                        entry
                                        (<= (:indent entry) section-indent)
                                        (not= "scrape_configs" (:key entry)))
                         job-start (when (and in-section?
                                              (re-matches #"^\s*-\s*job_name:\s*.+$" line))
                                     {:label (common/strip-yaml-scalar
                                              (second (re-matches #"^\s*-\s*job_name:\s*(.+?)\s*$"
                                                                  line)))
                                      :source-line (inc idx)
                                      :lines [[idx line]]})]
                     (cond
                       section-start?
                       (recur (rest remaining) true (:indent entry) nil out)

                       top-exit?
                       (recur (rest remaining) false nil nil (cond-> out current (conj current)))

                       job-start
                       (recur (rest remaining)
                              true
                              section-indent
                              job-start
                              (cond-> out current (conj current)))

                       (and in-section? current)
                       (recur (rest remaining)
                              true
                              section-indent
                              (update current :lines conj [idx line])
                              out)

                       :else
                       (recur (rest remaining) in-section? section-indent current out)))
                   (cond-> out current (conj current))))]
    (mapcat
     (fn [{:keys [label source-line] :as block}]
       (concat
        [(obs-fact :prometheus-scrape-job label source-line :defines)]
        (when-let [{metrics-path :value line :source-line}
                   (obs-block-scalar block "metrics_path")]
          [(obs-fact :prometheus-metrics-path
                     (str label ":" metrics-path)
                     line
                     :references)])
        (map (fn [{:keys [value source-line]}]
               {:kind :prometheus-target
                :label (str label ":" value)
                :source-line source-line
                :relation :scrapes
                :source-kind :prometheus-scrape-job
                :source label})
             (obs-block-list-values block "targets"))))
     blocks)))
(defn- prometheus-rule-facts
  [content]
  (->> (obs-yaml-named-list-blocks content "groups")
       (mapcat
        (fn [{group-label :label source-line :source-line :as block}]
          (let [rules (->> (:lines block)
                           (keep (fn [[idx line]]
                                   (when-let [[_ alert-name]
                                              (re-matches #"^\s*-\s*alert:\s*(.+?)\s*$"
                                                          line)]
                                     {:kind :prometheus-alert-rule
                                      :label (common/strip-yaml-scalar alert-name)
                                      :source-line (inc idx)
                                      :relation :defines
                                      :source-kind :prometheus-rule-group
                                      :source group-label}))))]
            (concat [(obs-fact :prometheus-rule-group
                               group-label
                               source-line
                               :defines)]
                    rules))))))
(defn- alertmanager-facts
  [content]
  (vec
   (distinct
    (concat
     [(obs-fact :observability-platform "alertmanager" 1 :uses)]
     (map (fn [{:keys [label source-line]}]
            (obs-fact :alertmanager-receiver label source-line :defines))
          (obs-yaml-named-list-blocks content "receivers"))
     (when-let [receiver (common/yaml-top-level-value content "receiver")]
       [(obs-fact :alertmanager-route-receiver receiver 1 :routes)])
     (->> (str/split-lines content)
          (map-indexed vector)
          (keep (fn [[idx line]]
                  (when-let [[_ matcher]
                             (re-matches #"^\s*-\s*matchers?:\s*(.+?)\s*$"
                                         line)]
                    (obs-fact :alertmanager-matcher
                              (common/strip-yaml-scalar matcher)
                              (inc idx)
                              :defines)))))))))
(defn- grafana-datasource-facts
  [content]
  (vec
   (distinct
    (concat
     [(obs-fact :observability-platform "grafana" 1 :uses)]
     (mapcat
      (fn [{:keys [label source-line] :as block}]
        (concat
         [(obs-fact :grafana-datasource label source-line :defines)]
         (when-let [{type :value type-line :source-line}
                    (obs-block-scalar block "type")]
           [(obs-fact :grafana-datasource-type
                      (str label ":" type)
                      type-line
                      :defines)])
         (when-let [{url :value url-line :source-line}
                    (obs-block-scalar block "url")]
           [(obs-fact :observability-endpoint
                      (str label ":" url)
                      url-line
                      :references)])))
      (obs-yaml-named-list-blocks content "datasources"))))))
(defn- grafana-dashboard-facts
  [content path]
  (if-let [m (common/read-json-map content)]
    (let [title (or (some-> (:title m) common/json-label) path)
          panels (if (vector? (:panels m)) (:panels m) [])]
      (vec
       (distinct
        (concat
         [(obs-fact :observability-platform "grafana" 1 :uses)
          (obs-fact :grafana-dashboard title 1 :defines)]
         (mapcat
          (fn [panel]
            (let [panel-title (common/json-label (or (:title panel)
                                                     (:id panel)
                                                     "panel"))
                  panel-label (str title ":" panel-title)
                  datasource (or (some-> (get-in panel [:datasource :uid]) common/json-label)
                                 (some-> (get-in panel [:datasource :type]) common/json-label))]
              (concat
               [(obs-fact :grafana-panel panel-label 1 :defines)]
               (when datasource
                 [{:kind :grafana-datasource
                   :label datasource
                   :source-line 1
                   :relation :references
                   :source-kind :grafana-panel
                   :source panel-label}]))))
          panels)))))
    []))
(defn- vector-toml-section-facts
  [content section-name kind]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (when-let [[_ label]
                          (re-matches (re-pattern
                                       (str "^\\[" section-name "\\.([^\\]]+)\\]\\s*$"))
                                      line)]
                 (obs-fact kind label (inc idx) :defines))))
       distinct
       vec))
(defn- vector-yaml-facts
  [content]
  (let [sources (obs-section-blocks content "sources")
        transforms (obs-section-blocks content "transforms")
        sinks (obs-section-blocks content "sinks")]
    (vec
     (distinct
      (concat
       [(obs-fact :observability-platform "vector" 1 :uses)]
       (map (fn [{:keys [label source-line]}]
              (obs-fact :log-source label source-line :defines))
            sources)
       (map (fn [{:keys [label source-line]}]
              (obs-fact :log-transform label source-line :defines))
            transforms)
       (mapcat
        (fn [{:keys [label source-line] :as block}]
          (concat
           [(obs-fact :log-sink label source-line :defines)]
           (map (fn [{:keys [value source-line]}]
                  {:kind :log-source
                   :label value
                   :source-line source-line
                   :relation :uses
                   :source-kind :log-sink
                   :source label})
                (obs-block-list-values block "inputs"))))
        sinks))))))
(defn- vector-toml-facts
  [content]
  (vec
   (distinct
    (concat
     [(obs-fact :observability-platform "vector" 1 :uses)]
     (vector-toml-section-facts content "sources" :log-source)
     (vector-toml-section-facts content "transforms" :log-transform)
     (vector-toml-section-facts content "sinks" :log-sink)))))
(defn- observability-facts
  [{:keys [path content]}]
  (let [filename (common/manifest-name path)]
    (vec
     (distinct
      (cond
        (contains? #{"otelcol.yaml" "otelcol.yml" "otel-collector.yaml" "otel-collector.yml"}
                   filename)
        (otel-facts content)

        (contains? #{"prometheus.yml" "prometheus.yaml"} filename)
        (vec (concat [(obs-fact :observability-platform "prometheus" 1 :uses)]
                     (prometheus-scrape-facts content)))

        (contains? #{"alertmanager.yml" "alertmanager.yaml"} filename)
        (alertmanager-facts content)

        (contains? #{"vector.yaml" "vector.yml"} filename)
        (vector-yaml-facts content)

        (= "vector.toml" filename)
        (vector-toml-facts content)

        (and (= ".json" (fs/extension path))
             (re-find #"(?s)\"schemaVersion\"\s*:" content)
             (re-find #"(?s)\"panels\"\s*:" content))
        (grafana-dashboard-facts content path)

        (re-find #"(?m)^datasources:\s*$" content)
        (grafana-datasource-facts content)

        (re-find #"(?m)^groups:\s*$" content)
        (vec (concat [(obs-fact :observability-platform "prometheus" 1 :uses)]
                     (prometheus-rule-facts content)))

        (and (re-find #"(?m)^receivers:\s*$" content)
             (re-find #"(?m)^exporters:\s*$" content)
             (re-find #"(?m)^\s*pipelines:\s*$" content))
        (otel-facts content)

        (and (re-find #"(?m)^sources:\s*$" content)
             (re-find #"(?m)^sinks:\s*$" content))
        (vector-yaml-facts content)

        :else
        [])))))
(defn- observability-reference-edges
  [run-id id-scope file-id path facts]
  (->> facts
       (keep (fn [{:keys [source-kind source kind label relation source-line]}]
               (when (and source-kind (seq source) (seq label))
                 (common/edge-row run-id
                                  file-id
                                  path
                                  (common/node-id id-scope source-kind source)
                                  (common/node-id id-scope kind label)
                                  (or relation :references)
                                  :extracted
                                  source-line))))
       distinct
       vec))
(defn extract-observability-config
  "Extract bounded observability and log pipeline configuration facts."
  [run-id {:keys [id-scope file-id path] :as file}]
  (let [facts (observability-facts file)
        result (common/extract-format-facts run-id
                                            file
                                            :observability-file
                                            :observability-file
                                            facts)
        reference-edges (observability-reference-edges run-id
                                                       id-scope
                                                       file-id
                                                       path
                                                       facts)]
    (update result :edges #(vec (distinct (concat % reference-edges))))))
