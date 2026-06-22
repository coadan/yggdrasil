(ns ygg.system
  "Derived project system graph inference."
  (:require [ygg.hash :as hash]
            [ygg.search-doc :as search-doc]
            [ygg.system-report :as system-report]
            [ygg.system.candidate :as candidate]
            [ygg.text :as text]
            [ygg.xtdb :as store]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.net URI]))

(def generic-aliases
  #{"app" "apps" "base" "bases" "client" "clients" "component" "components"
    "core" "libraries" "library" "lib" "libs" "main" "script" "scripts"
    "service" "services" "src" "test" "tests" "tool" "tools"})

(def external-repo-id "__external")

(defn now-ms
  []
  (System/currentTimeMillis))

(defn system-run-id
  [project-id started-at-ms]
  (str "system-run:" (hash/short-hash [project-id started-at-ms])))

(defn normalize-token
  [value]
  (-> (str/lower-case (str value))
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-+|-+$)" "")))

(defn- compact
  [& parts]
  (->> parts
       flatten
       (remove nil?)
       (map str)
       (remove str/blank?)
       (str/join "\n")))

(defn path-systems
  "Return neutral structural candidate systems for a repo's files."
  [repo files]
  (candidate/candidates-for-repo repo files))

(defn path-system
  "Return the neutral candidate bucket for a repo-relative path."
  ([repo path]
   (path-system repo (candidate/candidates-for-repo repo [{:path path :kind :code}]) path))
  ([repo candidates path]
   (candidate/candidate-for-path repo candidates path)))

(defn system-id
  [project-id repo-id system-key]
  (str "system:" project-id ":" repo-id ":" system-key))

(defn- alias-candidates
  [& values]
  (let [normalized (->> values
                        (map normalize-token)
                        (remove str/blank?))
        pieces (mapcat #(str/split % #"-") normalized)]
    (->> (concat normalized
                 pieces
                 (map #(str/replace % "-" "_") normalized))
         (remove str/blank?)
         (remove generic-aliases)
         (filter #(<= 4 (count %)))
         distinct
         sort
         vec)))

(defn- system-node
  [run-id project-id {:keys [id repo-id role]} {:keys [system-key label kind path-prefix
                                                       source candidate-types evidence
                                                       metrics]}]
  (let [repo-id (or repo-id id "repo")]
    (cond-> {:xt/id (system-id project-id repo-id system-key)
             :project-id project-id
             :repo-id repo-id
             :system-key system-key
             :label label
             :kind kind
             :source (or source :deterministic)
             :candidate-types (vec candidate-types)
             :evidence (vec evidence)
             :metrics (or metrics {})
             :aliases (alias-candidates repo-id system-key label path-prefix)
             :active? true
             :run-id run-id}
      path-prefix (assoc :path path-prefix
                         :path-prefix path-prefix)
      role (assoc :repo-role role))))

(defn- external-system-node
  [run-id project-id host]
  (system-node run-id
               project-id
               {:repo-id external-repo-id :role :external}
               {:system-key (str "external-api/" host)
                :label host
                :kind :external-api
                :candidate-types [:runtime-url-host]
                :evidence [{:type :runtime-url-host
                            :host host}]}))

(defn- repo-for-row
  [repo-by-id row]
  (or (get repo-by-id (:repo-id row))
      {:id (or (:repo-id row) "repo")
       :role (or (:repo-role row) :repository)}))

(defn- candidate-index
  [candidates]
  {:repo (some #(when (= "repo" (:system-key %)) %) candidates)
   :path-prefixes (->> candidates
                       (filter :path-prefix)
                       (sort-by (comp - count :path-prefix))
                       vec)})

(defn- candidate-indexes
  [candidates-by-repo]
  (into {}
        (map (fn [[repo-id candidates]]
               [repo-id (candidate-index candidates)]))
        candidates-by-repo))

(defn- indexed-candidate-for-path
  [repo candidate-index path]
  (or (some #(when (candidate/path-under-prefix? path (:path-prefix %)) %)
            (:path-prefixes candidate-index))
      (:repo candidate-index)
      (candidate/repo-candidate repo)))

(defn- candidate-for-row
  [repo-by-id candidate-index-by-repo row]
  (let [repo (repo-for-row repo-by-id row)
        spec (indexed-candidate-for-path repo
                                         (get candidate-index-by-repo (:repo-id row))
                                         (:path row))]
    [repo spec]))

(defn- system-for-file
  [project repo-by-id candidate-index-by-repo file]
  (let [[repo spec] (candidate-for-row repo-by-id candidate-index-by-repo file)]
    (system-node (:run-id project) (:id project) repo spec)))

(defn- active-files
  [xtdb project-id]
  (store/constrained-rows xtdb
                          (:files store/tables)
                          {:project-id project-id
                           :active? true}))

(defn- active-nodes
  [xtdb project-id]
  (store/constrained-rows xtdb
                          (:nodes store/tables)
                          {:project-id project-id
                           :active? true}))

(defn- active-edges
  [xtdb project-id]
  (store/constrained-rows xtdb
                          (:edges store/tables)
                          {:project-id project-id
                           :active? true}))

(defn- active-file-facts
  [xtdb project-id]
  (store/constrained-rows xtdb
                          (:file-facts store/tables)
                          {:project-id project-id
                           :active? true}))

(defn- evidence-id
  [project-id repo-id system-id kind path line normalized-value]
  (str "evidence:" (hash/short-hash [project-id repo-id system-id kind path line normalized-value])))

(defn- evidence-row
  [run-id project-id repo-id system-id fact]
  (cond-> {:xt/id (evidence-id project-id
                               repo-id
                               system-id
                               (:kind fact)
                               (:path fact)
                               (:source-line fact)
                               (:normalized-value fact))
           :project-id project-id
           :repo-id repo-id
           :system-id system-id
           :file-id (:file-id fact)
           :path (:path fact)
           :kind (:kind fact)
           :label (:label fact)
           :normalized-value (:normalized-value fact)
           :source-line (long (or (:source-line fact) 1))
           :confidence (double (:confidence fact))
           :active? true
           :run-id run-id}
    (:file-kind fact) (assoc :file-kind (:file-kind fact))
    (:url-context fact) (assoc :url-context (:url-context fact))
    (:auth-context fact) (assoc :auth-context (:auth-context fact))))

(defn- url-host
  [value]
  (try
    (some-> (URI/create value)
            .getHost
            str/lower-case
            (str/replace #"^www\." ""))
    (catch Exception _ nil)))

(defn- private-ip?
  [host]
  (or (str/starts-with? host "10.")
      (str/starts-with? host "192.168.")
      (boolean (re-find #"^172\.(1[6-9]|2[0-9]|3[0-1])\." host))))

(defn- external-host?
  [host]
  (and (seq host)
       (str/includes? host ".")
       (not (contains? #{"localhost" "127.0.0.1" "0.0.0.0"} host))
       (not (private-ip? host))
       (not (some #(str/ends-with? host %)
                  [".cluster.local" ".internal" ".local" ".svc"]))))

(defn- url-evidence?
  [row]
  (= :url (:kind row)))

(defn- external-api-evidence?
  [row]
  (and (url-evidence? row)
       (= :runtime-config (:url-context row))))

(def container-image-evidence-kinds
  #{:container-image-producer :container-image-consumer})

(def evidence-only-kinds
  #{:auth-reference})

(defn- system-id-for-row
  [project-id repo-by-id candidate-index-by-repo row]
  (let [[repo spec] (candidate-for-row repo-by-id candidate-index-by-repo row)
        repo-id (or (:repo-id row) (:repo-id repo) (:id repo) "repo")]
    (system-id project-id repo-id (:system-key spec))))

(defn- fact-evidence
  [run-id project-id repo-by-id candidate-index-by-repo fact]
  (let [system-id (system-id-for-row project-id
                                     repo-by-id
                                     candidate-index-by-repo
                                     fact)]
    (evidence-row run-id project-id (:repo-id fact) system-id fact)))

(defn- evidence-for-project
  [run-id project-id repo-by-id candidate-index-by-repo facts]
  (->> facts
       (filter :active?)
       (map #(fact-evidence run-id
                            project-id
                            repo-by-id
                            candidate-index-by-repo
                            %))
       vec))

(defn- edge-id
  [project-id source-id target-id relation rules evidence-ids]
  (str "system-edge:" (hash/short-hash [project-id source-id target-id relation rules evidence-ids])))

(defn- system-edge
  [run-id project-id source-id target-id relation confidence evidence-ids rules]
  {:xt/id (edge-id project-id source-id target-id relation rules evidence-ids)
   :project-id project-id
   :source-id source-id
   :target-id target-id
   :relation relation
   :confidence (double confidence)
   :evidence-ids (vec (distinct evidence-ids))
   :rules (vec (distinct rules))
   :active? true
   :run-id run-id})

(defn- code-system-edges
  [run-id project-id node-system-by-id edges]
  (->> edges
       (keep (fn [edge]
               (let [source-system (get node-system-by-id (:source-id edge))
                     target-system (get node-system-by-id (:target-id edge))]
                 (when (and source-system target-system (not= source-system target-system))
                   {:source-id source-system
                    :target-id target-system
                    :relation :code-depends-on
                    :evidence-id (:xt/id edge)}))))
       (group-by (juxt :source-id :target-id :relation))
       (map (fn [[[source-id target-id relation] rows]]
              (system-edge run-id
                           project-id
                           source-id
                           target-id
                           relation
                           0.90
                           (mapv :evidence-id rows)
                           ["code-edge"])))
       vec))

(defn- file-system-ids
  [project-id repo-by-id candidate-index-by-repo files]
  (into {}
        (map (fn [file]
               [(:xt/id file)
                (system-id-for-row project-id
                                   repo-by-id
                                   candidate-index-by-repo
                                   file)]))
        files))

(defn- node-system-ids
  [file-system-by-id nodes]
  (into {}
        (keep (fn [node]
                (when-let [system-id (get file-system-by-id (:file-id node))]
                  [(:xt/id node) system-id])))
        nodes))

(defn- zero-metrics
  []
  {:file-count 0
   :node-count 0
   :internal-code-edge-count 0
   :incoming-code-edge-count 0
   :outgoing-code-edge-count 0})

(defn- inc-metric
  [metrics system-id k]
  (update-in metrics [system-id k] (fnil inc 0)))

(defn- system-metrics
  [files nodes edges file-system-by-id node-system-by-id]
  (-> (reduce (fn [metrics file]
                (inc-metric metrics (get file-system-by-id (:xt/id file)) :file-count))
              {}
              files)
      (as-> metrics
            (reduce (fn [metrics node]
                      (if-let [system-id (get node-system-by-id (:xt/id node))]
                        (inc-metric metrics system-id :node-count)
                        metrics))
                    metrics
                    nodes))
      (as-> metrics
            (reduce (fn [metrics edge]
                      (let [source-system (get node-system-by-id (:source-id edge))
                            target-system (get node-system-by-id (:target-id edge))]
                        (cond
                          (or (nil? source-system) (nil? target-system))
                          metrics

                          (= source-system target-system)
                          (inc-metric metrics source-system :internal-code-edge-count)

                          :else
                          (-> metrics
                              (inc-metric source-system :outgoing-code-edge-count)
                              (inc-metric target-system :incoming-code-edge-count)))))
                    metrics
                    edges))
      (update-vals #(merge (zero-metrics) %))))

(defn- attach-metrics
  [metrics system]
  (assoc system :metrics (get metrics (:xt/id system) (zero-metrics))))

(defn- value-system-edges
  [run-id project-id evidence]
  (->> evidence
       (remove #(contains? (set/union #{:port :route :url}
                                      container-image-evidence-kinds
                                      evidence-only-kinds)
                           (:kind %)))
       (group-by :normalized-value)
       (mapcat
        (fn [[_ rows]]
          (let [systems (->> rows (map :system-id) distinct vec)]
            (when (< 1 (count systems))
              (for [source-id systems
                    target-id systems
                    :when (not= source-id target-id)]
                (system-edge run-id
                             project-id
                             source-id
                             target-id
                             :shares-config
                             0.58
                             (mapv :xt/id rows)
                             ["shared-evidence-value"]))))))
       vec))

(defn- deploy-system-edges
  [run-id project-id evidence]
  (let [by-kind-and-artifact (->> evidence
                                  (filter #(contains? container-image-evidence-kinds (:kind %)))
                                  (group-by (juxt :kind :normalized-value)))
        producer-keys (->> by-kind-and-artifact
                           keys
                           (keep (fn [[kind artifact]]
                                   (when (= :container-image-producer kind)
                                     artifact)))
                           set)
        consumer-keys (->> by-kind-and-artifact
                           keys
                           (keep (fn [[kind artifact]]
                                   (when (= :container-image-consumer kind)
                                     artifact)))
                           set)]
    (->> (set/intersection producer-keys consumer-keys)
         (mapcat
          (fn [artifact]
            (let [producers (get by-kind-and-artifact [:container-image-producer artifact])
                  consumers (get by-kind-and-artifact [:container-image-consumer artifact])
                  producer-rows-by-system (group-by :system-id producers)
                  consumer-rows-by-system (group-by :system-id consumers)]
              (for [[source-id source-rows] producer-rows-by-system
                    [target-id target-rows] consumer-rows-by-system
                    :when (not= source-id target-id)]
                (system-edge run-id
                             project-id
                             source-id
                             target-id
                             :deploys
                             0.74
                             (mapv :xt/id (concat source-rows target-rows))
                             ["container-image-artifact"])))))
         vec)))

(defn- external-api-nodes
  [run-id project-id evidence]
  (->> evidence
       (filter external-api-evidence?)
       (keep (comp url-host :label))
       (filter external-host?)
       distinct
       sort
       (mapv #(external-system-node run-id project-id %))))

(defn- external-api-edges
  [run-id project-id evidence]
  (->> evidence
       (filter external-api-evidence?)
       (keep (fn [row]
               (when-let [host (url-host (:label row))]
                 (when (external-host? host)
                   (system-edge run-id
                                project-id
                                (:system-id row)
                                (:xt/id (external-system-node run-id project-id host))
                                :calls-external-api
                                0.76
                                [(:xt/id row)]
                                ["runtime-url-host"])))))))

(defn- merge-system-edges
  [run-id project-id edges]
  (->> edges
       (group-by (juxt :source-id :target-id :relation))
       (map (fn [[[source-id target-id relation] rows]]
              (system-edge run-id
                           project-id
                           source-id
                           target-id
                           relation
                           (apply max (map :confidence rows))
                           (mapcat :evidence-ids rows)
                           (mapcat :rules rows))))
       vec))

(defn- search-doc-row
  [run-id project-id target-kind target text]
  (let [input-sha (hash/sha256-hex text)]
    {:xt/id (search-doc/search-doc-id (:xt/id target))
     :project-id project-id
     :repo-id (:repo-id target)
     :target-id (:xt/id target)
     :target-kind target-kind
     :file-id (str "project:" project-id)
     :path (or (:path target) (:label target))
     :kind (:kind target)
     :label (:label target)
     :text text
     :tokens (text/tokenize-all text)
     :input-sha input-sha
     :source-line 1
     :active? true
     :run-id run-id}))

(defn- label-terms
  [value]
  (str/replace (str value) #"[/_.-]+" " "))

(defn- system-search-docs
  [run-id project-id systems edges system-by-id evidence-by-id]
  (let [node-docs (mapv (fn [system]
                          (search-doc-row run-id
                                          project-id
                                          :system-node
                                          system
                                          (compact (:label system)
                                                   (label-terms (:label system))
                                                   (name (:kind system))
                                                   (some->> (:source system) name)
                                                   (map name (:candidate-types system))
                                                   (:repo-id system)
                                                   (some-> (:repo-role system) name)
                                                   (:path-prefix system)
                                                   (label-terms (:path-prefix system))
                                                   (some-> (:metrics system) pr-str)
                                                   (:aliases system))))
                        systems)
        edge-docs (mapv (fn [edge]
                          (let [source (get system-by-id (:source-id edge))
                                target (get system-by-id (:target-id edge))
                                evidence (keep evidence-by-id (:evidence-ids edge))]
                            (search-doc-row run-id
                                            project-id
                                            :system-edge
                                            (assoc edge
                                                   :repo-id (:repo-id source)
                                                   :label (str (:label source)
                                                               " "
                                                               (name (:relation edge))
                                                               " "
                                                               (:label target))
                                                   :kind (:relation edge))
                                            (compact (:label source)
                                                     (label-terms (:label source))
                                                     (name (:relation edge))
                                                     (:label target)
                                                     (label-terms (:label target))
                                                     (:rules edge)
                                                     (map :label evidence)))))
                        edges)]
    (into node-docs edge-docs)))

(defn infer-project!
  "Infer and persist derived system graph for project config."
  [xtdb {:keys [id repos] :as project}]
  (let [started (now-ms)
        run-id (system-run-id id started)
        project (assoc project :run-id run-id)
        repo-by-id (into {} (map (juxt :id identity)) repos)
        files (vec (active-files xtdb id))
        candidates-by-repo (candidate/candidates-by-repo repo-by-id files)
        candidate-index-by-repo (candidate-indexes candidates-by-repo)
        nodes (vec (active-nodes xtdb id))
        edges (vec (active-edges xtdb id))
        file-facts (vec (active-file-facts xtdb id))
        file-system-by-id (file-system-ids id
                                           repo-by-id
                                           candidate-index-by-repo
                                           files)
        node-system-by-id (node-system-ids file-system-by-id nodes)
        metrics (system-metrics files
                                nodes
                                edges
                                file-system-by-id
                                node-system-by-id)
        candidate-systems (->> candidates-by-repo
                               (mapcat (fn [[repo-id candidates]]
                                         (let [repo (get repo-by-id repo-id)]
                                           (map #(system-node run-id id repo %) candidates))))
                               distinct
                               (mapv #(attach-metrics metrics %)))
        file-systems (->> files
                          (map #(system-for-file project
                                                 repo-by-id
                                                 candidate-index-by-repo
                                                 %))
                          (map #(attach-metrics metrics %))
                          distinct
                          vec)
        evidence (evidence-for-project run-id
                                       id
                                       repo-by-id
                                       candidate-index-by-repo
                                       file-facts)
        external-systems (external-api-nodes run-id id evidence)
        systems (vec (distinct (concat candidate-systems file-systems external-systems)))
        code-edges (code-system-edges run-id id node-system-by-id edges)
        value-edges (value-system-edges run-id id evidence)
        deploy-edges (deploy-system-edges run-id id evidence)
        external-edges (external-api-edges run-id id evidence)
        system-edges (merge-system-edges run-id id (concat code-edges
                                                           value-edges
                                                           deploy-edges
                                                           external-edges))
        system-by-id (into {} (map (juxt :xt/id identity)) systems)
        evidence-by-id (into {} (map (juxt :xt/id identity)) evidence)
        search-docs (system-search-docs run-id id systems system-edges system-by-id evidence-by-id)
        result (store/commit-system-graph! xtdb
                                           id
                                           {:evidence evidence
                                            :nodes systems
                                            :edges system-edges
                                            :search-docs search-docs})]
    (assoc result
           :run-id run-id
           :project-id id
           :status :completed)))

(defn maintenance-report
  "Return read-only maintenance findings for a project's current system graph."
  [xtdb project-id opts]
  (system-report/maintenance-report xtdb project-id opts))
