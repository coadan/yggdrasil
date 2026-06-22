(ns ygg.extract.runtime
  (:require [ygg.extract.common :as common]
            [ygg.hash :as hash]
            [ygg.text :as text]
            [clojure.string :as str]))

(def docker-command-instructions
  #{"RUN" "CMD" "ENTRYPOINT" "HEALTHCHECK"})
(defn- docker-strip-flags
  [value]
  (->> (str/split (str/trim (or value "")) #"\s+")
       (drop-while #(str/starts-with? % "--"))
       (str/join " ")
       str/trim))
(defn- docker-env-keys
  [value]
  (let [value (str/trim (or value ""))]
    (cond
      (str/blank? value) []
      (str/includes? value "=") (->> (str/split value #"\s+")
                                     (keep #(some-> (re-matches #"([^=\s]+)=.*" %)
                                                    second))
                                     distinct
                                     vec)
      :else [(first (str/split value #"\s+"))])))
(defn- docker-label-keys
  [value]
  (->> (str/split (str/trim (or value "")) #"\s+")
       (keep #(some-> (re-matches #"([^=\s]+)=.*" %) second))
       distinct
       vec))
(defn- docker-copy-sources
  [value]
  (let [tokens (->> (str/split (docker-strip-flags value) #"\s+")
                    (remove str/blank?)
                    vec)]
    (if (< 1 (count tokens))
      (subvec tokens 0 (dec (count tokens)))
      [])))
(defn- docker-stage-label
  [idx image stage]
  (or (some-> stage str/trim not-empty)
      (str "stage-" (inc idx) ":" image)))
(defn- docker-instruction-rows
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [trimmed (str/trim line)]
                 (when-not (or (str/blank? trimmed)
                               (str/starts-with? trimmed "#"))
                   (when-let [[_ instruction value]
                              (re-matches #"(?i)^([A-Z]+)\s+(.+?)\s*$" trimmed)]
                     {:instruction (str/upper-case instruction)
                      :value (str/trim value)
                      :source-line (inc idx)
                      :idx idx})))))
       vec))
(defn- docker-stage-records
  [rows]
  (loop [remaining rows
         current nil
         out []]
    (if-let [{:keys [instruction value] :as row} (first remaining)]
      (if (= "FROM" instruction)
        (let [[_ image stage] (re-matches #"(?i)^([^\s]+)(?:\s+AS\s+([A-Za-z0-9_.-]+))?.*$"
                                          value)
              stage-record (assoc row
                                  :image image
                                  :label (docker-stage-label (count out)
                                                             image
                                                             stage))]
          (recur (rest remaining)
                 (assoc stage-record :lines [row])
                 (cond-> out current (conj current))))
        (recur (rest remaining)
               (when current
                 (update current :lines conj row))
               out))
      (cond-> out current (conj current)))))
(defn- docker-facts
  [content]
  (let [rows (docker-instruction-rows content)
        stages (docker-stage-records rows)
        stage-labels (set (map :label stages))]
    (->> stages
         (mapcat
          (fn [{stage-label :label image :image source-line :source-line lines :lines}]
            (concat
             [{:kind :docker-stage
               :label stage-label
               :source-line source-line
               :relation :defines}]
             (when-not (contains? stage-labels image)
               [{:kind :container-image
                 :label image
                 :source-line source-line
                 :relation :uses
                 :stage-label stage-label}])
             (mapcat
              (fn [{:keys [instruction value source-line]}]
                (case instruction
                  "FROM" []
                  "WORKDIR" [{:kind :docker-workdir
                              :label value
                              :source-line source-line
                              :relation :defines
                              :stage-label stage-label}]
                  "EXPOSE" (mapv (fn [port]
                                   {:kind :container-port
                                    :label port
                                    :source-line source-line
                                    :relation :defines
                                    :stage-label stage-label})
                                 (common/yaml-scalar-list-values value))
                  "ENV" (mapv (fn [env-key]
                                {:kind :runtime-env-var
                                 :label (str stage-label ":" env-key)
                                 :source-line source-line
                                 :relation :defines
                                 :stage-label stage-label})
                              (docker-env-keys value))
                  "ARG" (mapv (fn [arg-key]
                                {:kind :docker-build-arg
                                 :label arg-key
                                 :source-line source-line
                                 :relation :defines
                                 :stage-label stage-label})
                              (docker-env-keys value))
                  "LABEL" (mapv (fn [label-key]
                                  {:kind :docker-label
                                   :label label-key
                                   :source-line source-line
                                   :relation :defines
                                   :stage-label stage-label})
                                (docker-label-keys value))
                  ("COPY" "ADD") (mapv (fn [source]
                                         {:kind :docker-copy-source
                                          :label source
                                          :source-line source-line
                                          :relation :references
                                          :stage-label stage-label})
                                       (docker-copy-sources value))
                  "VOLUME" [{:kind :runtime-volume
                             :label value
                             :source-line source-line
                             :relation :defines
                             :stage-label stage-label}]
                  (when (contains? docker-command-instructions instruction)
                    [{:kind :runtime-command
                      :label (str instruction " " (common/redact-sensitive-values value))
                      :source-line source-line
                      :relation :defines
                      :stage-label stage-label}])))
              lines))))
         distinct
         vec)))
(defn- docker-stage-dependencies
  [stages]
  (let [stage-labels (set (map :label stages))]
    (->> stages
         (keep (fn [{:keys [label image source-line]}]
                 (when (contains? stage-labels image)
                   {:source label
                    :target image
                    :source-line source-line})))
         distinct
         vec)))
(defn- docker-stage-chunks
  [run-id id-scope file-id path content stages]
  (let [lines (vec (str/split-lines content))]
    (->> stages
         (map-indexed
          (fn [stage-idx {:keys [label source-line]}]
            (let [start-line source-line
                  next-stage-line (some-> (nth stages (inc stage-idx) nil)
                                          :source-line)
                  end-line (or (some-> next-stage-line dec)
                               (count lines))
                  text-lines (subvec lines
                                     (dec start-line)
                                     end-line)
                  text (common/redact-sensitive-values (str/join "\n" text-lines))]
              {:xt/id (common/chunk-id id-scope path label start-line)
               :file-id file-id
               :path path
               :kind :docker-stage
               :label label
               :text text
               :tokens (text/tokenize (str label "\n" text))
               :source-line start-line
               :end-line end-line
               :content-sha (hash/sha256-hex text)
               :active? true
               :run-id run-id})))
         vec)))
(defn extract-docker
  "Extract bounded Dockerfile/Containerfile build and runtime facts."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [root-node (common/generic-node run-id id-scope file-id path :docker-file path 1)
        stages (docker-stage-records (docker-instruction-rows content))
        facts (docker-facts content)
        fact-nodes (mapv (fn [{:keys [kind label source-line]}]
                           (common/generic-node run-id id-scope file-id path kind label source-line))
                         facts)
        stage-edge-source (fn [fact]
                            (if-let [stage-label (:stage-label fact)]
                              (common/node-id id-scope :docker-stage stage-label)
                              (:xt/id root-node)))
        fact-edges (mapv (fn [{:keys [kind label source-line relation] :as fact}]
                           (common/edge-row run-id
                                            file-id
                                            path
                                            (if (= :docker-stage kind)
                                              (:xt/id root-node)
                                              (stage-edge-source fact))
                                            (common/node-id id-scope kind label)
                                            (or relation :defines)
                                            :extracted
                                            source-line))
                         facts)
        stage-dependency-edges (mapv (fn [{:keys [source target source-line]}]
                                       (common/edge-row run-id
                                                        file-id
                                                        path
                                                        (common/node-id id-scope
                                                                        :docker-stage
                                                                        source)
                                                        (common/node-id id-scope
                                                                        :docker-stage
                                                                        target)
                                                        :depends-on
                                                        :extracted
                                                        source-line))
                                     (docker-stage-dependencies stages))
        file-chunk (:chunks (common/extract-text-source run-id
                                                        (update file
                                                                :content
                                                                common/redact-sensitive-values)
                                                        :docker-file))]
    {:nodes (vec (distinct (into [root-node] fact-nodes)))
     :edges (vec (distinct (concat fact-edges stage-dependency-edges)))
     :chunks (vec (concat file-chunk
                          (docker-stage-chunks run-id
                                               id-scope
                                               file-id
                                               path
                                               content
                                               stages)))
     :diagnostics []}))
(defn- procfile-processes
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (keep (fn [[idx line]]
               (let [trimmed (str/trim line)]
                 (when-not (or (str/blank? trimmed)
                               (str/starts-with? trimmed "#"))
                   (when-let [[_ process command]
                              (re-matches #"^([A-Za-z0-9_.-]+):\s*(.+?)\s*$"
                                          trimmed)]
                     {:label process
                      :command command
                      :source-line (inc idx)})))))
       vec))
(defn extract-procfile
  "Extract explicit Procfile process declarations and commands."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [root-node (common/generic-node run-id id-scope file-id path :procfile path 1)
        processes (procfile-processes content)
        process-nodes (mapv (fn [{:keys [label source-line]}]
                              (common/generic-node run-id
                                                   id-scope
                                                   file-id
                                                   path
                                                   :runtime-process
                                                   label
                                                   source-line))
                            processes)
        command-nodes (mapv (fn [{:keys [label command source-line]}]
                              (let [command (common/redact-sensitive-values command)]
                                (common/generic-node run-id
                                                     id-scope
                                                     file-id
                                                     path
                                                     :runtime-command
                                                     (str label ":" command)
                                                     source-line)))
                            processes)
        process-edges (mapv #(common/edge-row run-id
                                              file-id
                                              path
                                              (:xt/id root-node)
                                              (:xt/id %)
                                              :defines
                                              :extracted
                                              (:source-line %))
                            process-nodes)
        command-edges (mapv (fn [{:keys [label command source-line]}]
                              (let [command (common/redact-sensitive-values command)]
                                (common/edge-row run-id
                                                 file-id
                                                 path
                                                 (common/node-id id-scope :runtime-process label)
                                                 (common/node-id id-scope
                                                                 :runtime-command
                                                                 (str label ":" command))
                                                 :defines
                                                 :extracted
                                                 source-line)))
                            processes)
        file-chunk (:chunks (common/extract-text-source run-id
                                                        (update file
                                                                :content
                                                                common/redact-sensitive-values)
                                                        :procfile))
        process-chunks (mapv (fn [{:keys [label command source-line]}]
                               (let [command (common/redact-sensitive-values command)]
                                 {:xt/id (common/chunk-id id-scope path label source-line)
                                  :file-id file-id
                                  :path path
                                  :kind :runtime-process
                                  :label label
                                  :text command
                                  :tokens (text/tokenize (str label "\n" command))
                                  :source-line source-line
                                  :content-sha (hash/sha256-hex command)
                                  :active? true
                                  :run-id run-id}))
                             processes)]
    {:nodes (vec (concat [root-node] process-nodes command-nodes))
     :edges (vec (concat process-edges command-edges))
     :chunks (vec (concat file-chunk process-chunks))
     :diagnostics []}))
