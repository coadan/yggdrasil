(ns agraph.system
  "Derived project system graph inference."
  (:require [agraph.hash :as hash]
            [agraph.infra-review :as infra-review]
            [agraph.map :as graph-map]
            [agraph.search-doc :as search-doc]
            [agraph.system.candidate :as candidate]
            [agraph.system.cluster :as cluster]
            [agraph.system.salience :as salience]
            [agraph.text :as text]
            [agraph.xtdb :as store]
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
                :kind :external-api}))

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
  (->> (store/rows-by-field xtdb (:files store/tables) :project-id project-id)
       (filter :active?)))

(defn- active-nodes
  [xtdb project-id]
  (->> (store/rows-by-field xtdb (:nodes store/tables) :project-id project-id)
       (filter :active?)))

(defn- active-edges
  [xtdb project-id]
  (->> (store/rows-by-field xtdb (:edges store/tables) :project-id project-id)
       (filter :active?)))

(defn- active-file-facts
  [xtdb project-id]
  (->> (store/rows-by-field xtdb (:file-facts store/tables) :project-id project-id)
       (filter :active?)))

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
                                ["url-host"])))))))

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

(defn- system-summary
  [system]
  (select-keys system [:xt/id :label :kind :repo-id :path-prefix :metrics]))

(defn- edge-summary
  [system-by-id edge]
  (assoc (select-keys edge [:xt/id :relation :relations :salience :visibility
                            :evidence-ids :evidence-counts :salience-reasons])
         :source (system-summary (get system-by-id (:source-id edge)))
         :target (system-summary (get system-by-id (:target-id edge)))))

(defn- stable-row-signature
  [rows keys]
  (->> rows
       (map #(select-keys % keys))
       (sort-by pr-str)
       vec))

(defn- s
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))

(defn- match-value?
  [actual expected]
  (or (nil? expected)
      (= (s actual) (s expected))))

(defn- system-matches-reject?
  [system match]
  (let [kind (or (:kind match) (:type match))
        path (or (:path match) (:pathPrefix match) (:path-prefix match))
        host (:host match)]
    (and (match-value? (:xt/id system) (:id match))
         (match-value? (:label system) (:label match))
         (match-value? (:repo-id system) (:repo match))
         (match-value? (:kind system) kind)
         (match-value? (:path-prefix system) path)
         (or (nil? host)
             (and (= :external-api (:kind system))
                  (= (s host) (s (:label system))))))))

(defn- reject-matches
  [overlay]
  (keep :match (:reject overlay)))

(defn- hidden-edge-overlay?
  [edge]
  (contains? #{"hidden" "noise"} (s (:visibility edge))))

(defn- overlay-edge-key
  [edge]
  [(s (:source edge)) (s (:target edge)) (s (:relation edge))])

(defn- system-edge-key
  [edge]
  [(s (:source-id edge)) (s (:target-id edge)) (s (:relation edge))])

(defn- hidden-edge-keys
  [overlay]
  (->> (:edges overlay)
       (filter hidden-edge-overlay?)
       (map overlay-edge-key)
       set))

(defn- apply-maintenance-overlay
  [systems edges evidence overlay]
  (if-not overlay
    {:systems systems
     :edges edges
     :evidence evidence
     :map nil}
    (let [matches (vec (reject-matches overlay))
          hidden-edges (hidden-edge-keys overlay)
          rejected-ids (->> systems
                            (filter (fn [system]
                                      (some #(system-matches-reject? system %) matches)))
                            (map :xt/id)
                            set)]
      {:systems (vec (remove #(contains? rejected-ids (:xt/id %)) systems))
       :edges (vec (remove #(or (contains? rejected-ids (:source-id %))
                                (contains? rejected-ids (:target-id %))
                                (contains? hidden-edges (system-edge-key %)))
                           edges))
       :evidence (vec (remove #(contains? rejected-ids (:system-id %)) evidence))
       :map {:schema graph-map/schema
             :project (:project overlay)
             :rejects (count (:reject overlay))
             :rejected-systems (count rejected-ids)}})))

(defn- graph-basis
  [project-id systems edges evidence semantic-edges]
  (let [signature {:systems (stable-row-signature systems [:xt/id :run-id :kind])
                   :edges (stable-row-signature edges [:xt/id :run-id :relation])
                   :evidence (stable-row-signature evidence [:xt/id :run-id :kind])
                   :semantic (stable-row-signature semantic-edges
                                                   [:xt/id :visibility :salience])}]
    {:schema "agraph.graph-basis/v1"
     :project-id project-id
     :hash (hash/short-hash signature)
     :counts {:systems (count systems)
              :system-edges (count edges)
              :system-evidence (count evidence)
              :semantic-connections (count semantic-edges)}
     :run-ids (->> systems
                   (keep :run-id)
                   distinct
                   sort
                   vec)}))

(defn- decision-row
  [project-id basis kind severity target reason data
   {:keys [scope evidence-ids recommended-actions]}]
  (let [evidence-ids (vec (distinct evidence-ids))]
    {:schema "agraph.frontier.decision/v1"
     :id (str "maintenance-decision:"
              (hash/short-hash [project-id kind target evidence-ids (:hash basis)]))
     :project-id project-id
     :kind kind
     :status :open
     :severity severity
     :scope (or scope {})
     :target target
     :reason reason
     :evidence-ids evidence-ids
     :recommended-actions (vec recommended-actions)
     :basis basis
     :data data}))

(defn- edge-evidence-ids
  [edge]
  (vec (:evidence-ids edge)))

(defn- ambiguous-edge-decisions
  [project-id basis system-by-id semantic-edges]
  (->> semantic-edges
       (filter #(contains? #{"primary" "secondary"} (:visibility %)))
       (filter (fn [edge]
                 (let [relations (set (:relations edge))
                       noisy-relations (set/intersection relations
                                                         #{:references
                                                           :shares-config})
                       strong-relations (set/intersection relations
                                                          #{:code-depends-on
                                                            :deploys
                                                            :calls-external-api
                                                            :calls-http})]
                   (or (contains? relations :references)
                       (and (seq noisy-relations)
                            (empty? strong-relations))))))
       (mapv (fn [edge]
               (decision-row project-id
                             basis
                             :ambiguous-high-salience-edge
                             :high
                             (:xt/id edge)
                             "Visible connection includes mixed or noisy evidence and may need agent judgment."
                             {:edge (edge-summary system-by-id edge)}
                             {:scope {:target-kind :system-edge}
                              :evidence-ids (edge-evidence-ids edge)
                              :recommended-actions [:set-edge-visibility
                                                    :hide-edge
                                                    :investigate]})))))

(defn- orphan-decisions
  [project-id basis orphaned]
  (->> orphaned
       (filter #(pos? (long (get-in % [:metrics :node-count] 0))))
       (mapv (fn [system]
               (decision-row project-id
                             basis
                             :unclustered-system
                             :low
                             (:xt/id system)
                             "System has indexed code but no visible system connections."
                             {:system (system-summary system)}
                             {:scope {:target-kind :system-node}
                              :recommended-actions [:accept-system
                                                    :merge-system
                                                    :hide-system]})))))

(def noisy-supporting-relations
  #{:calls-external-api
    :references
    :shares-config})

(def fanout-decision-min-edges
  3)

(def max-fanout-decision-edges
  50)

(def max-maintenance-decisions-per-kind
  100)

(defn- external-api-system?
  [system]
  (= :external-api (:kind system)))

(defn- support-or-noise?
  [edge]
  (contains? #{"supporting" "noise"} (:visibility edge)))

(defn- primary-or-secondary?
  [edge]
  (contains? #{"primary" "secondary"} (:visibility edge)))

(defn- noisy-supporting-edge?
  [edge]
  (and (= "supporting" (:visibility edge))
       (seq (set/intersection (set (:relations edge))
                              noisy-supporting-relations))))

(defn- fanout-edge-key
  [edge]
  [(:source-id edge) (:relation edge)])

(defn- fanout-decision-target
  [project-id source-id relation]
  (str "edge-fanout:"
       (hash/short-hash [project-id source-id relation])))

(defn- edge-visibility-patch
  [edge]
  {:source (:source-id edge)
   :target (:target-id edge)
   :relation (name (:relation edge))
   :visibility "noise"})

(defn- supporting-edge-fanout-decisions
  [project-id basis system-by-id semantic-edges]
  (->> semantic-edges
       (filter noisy-supporting-edge?)
       (group-by fanout-edge-key)
       (keep (fn [[[source-id relation] edges]]
               (let [edges (sort-by :target-id edges)
                     edge-count (count edges)]
                 (when (<= fanout-decision-min-edges edge-count)
                   (decision-row
                    project-id
                    basis
                    :low-confidence-edge-fanout
                    (if (<= 10 edge-count) :medium :low)
                    (fanout-decision-target project-id source-id relation)
                    "One source has many support-level visible connections of the same relation."
                    {:source (system-summary (get system-by-id source-id))
                     :relation relation
                     :edge-count edge-count
                     :edges (mapv #(edge-summary system-by-id %)
                                  (take max-fanout-decision-edges edges))
                     :mapPatch (mapv edge-visibility-patch edges)}
                    {:scope {:target-kind :system-edge-fanout
                             :source source-id
                             :relation relation}
                     :evidence-ids (mapcat edge-evidence-ids edges)
                     :recommended-actions [:set-edge-visibility
                                           :none
                                           :investigate]})))))
       (sort-by (fn [decision]
                  [(- (long (get-in decision [:data :edge-count] 0)))
                   (:target decision)]))
       (take max-maintenance-decisions-per-kind)
       vec))

(defn- support-only-external-api-decisions
  [project-id basis system-by-id semantic-edges]
  (let [incident (group-by (fn [edge]
                             (if (external-api-system? (get system-by-id (:target-id edge)))
                               (:target-id edge)
                               (:source-id edge)))
                           (filter (fn [edge]
                                     (or (external-api-system?
                                          (get system-by-id (:source-id edge)))
                                         (external-api-system?
                                          (get system-by-id (:target-id edge)))))
                                   semantic-edges))]
    (->> incident
         (keep (fn [[system-id edges]]
                 (let [system (get system-by-id system-id)]
                   (when (and (external-api-system? system)
                              (seq edges)
                              (every? support-or-noise? edges)
                              (not-any? primary-or-secondary? edges))
                     (decision-row
                      project-id
                      basis
                      :noisy-external-api
                      :low
                      (:xt/id system)
                      "External API node has only support/noise visible connections and no accepted map correction."
                      {:system (system-summary system)
                       :edge-count (count edges)
                       :edges (mapv #(edge-summary system-by-id %)
                                    (take max-fanout-decision-edges
                                          (sort-by :source-id edges)))}
                      {:scope {:target-kind :system-node
                               :kind :external-api}
                       :evidence-ids (mapcat edge-evidence-ids edges)
                       :recommended-actions [:reject-external-api
                                             :none
                                             :investigate]})))))
         (sort-by (fn [decision]
                    [(- (long (get-in decision [:data :edge-count] 0)))
                     (:target decision)]))
         (take max-maintenance-decisions-per-kind)
         vec)))

(defn- sparse-external-api-decisions
  [project-id basis system-by-id semantic-edges]
  (let [incident (group-by (fn [edge]
                             (if (external-api-system? (get system-by-id (:target-id edge)))
                               (:target-id edge)
                               (:source-id edge)))
                           (filter (fn [edge]
                                     (or (external-api-system?
                                          (get system-by-id (:source-id edge)))
                                         (external-api-system?
                                          (get system-by-id (:target-id edge)))))
                                   semantic-edges))]
    (->> incident
         (keep (fn [[system-id edges]]
                 (let [system (get system-by-id system-id)
                       evidence-ids (vec (distinct (mapcat edge-evidence-ids edges)))]
                   (when (and (external-api-system? system)
                              (= 1 (count edges))
                              (= 1 (count evidence-ids))
                              (some primary-or-secondary? edges))
                     (decision-row
                      project-id
                      basis
                      :sparse-external-api
                      :low
                      (:xt/id system)
                      "External API node has exactly one incident connection and one evidence row."
                      {:system (system-summary system)
                       :edge (edge-summary system-by-id (first edges))
                       :edge-count (count edges)
                       :evidence-count (count evidence-ids)}
                      {:scope {:target-kind :system-node
                               :kind :external-api}
                       :evidence-ids evidence-ids
                       :recommended-actions [:reject-external-api
                                             :none
                                             :investigate]})))))
         (sort-by :target)
         (take max-maintenance-decisions-per-kind)
         vec)))

(defn- cluster-bridge-decisions
  [project-id basis system-by-id semantic-edges clusters]
  (let [cluster-by-source (into {}
                                (map (juxt :sourceLabel :id))
                                clusters)
        cluster-labels (cluster/cluster-labels (vals system-by-id) semantic-edges)]
    (->> semantic-edges
         (filter #(contains? #{"primary" "secondary"} (:visibility %)))
         (keep (fn [edge]
                 (let [source-cluster (get cluster-by-source
                                           (get cluster-labels (:source-id edge)))
                       target-cluster (get cluster-by-source
                                           (get cluster-labels (:target-id edge)))]
                   (when (and source-cluster target-cluster
                              (not= source-cluster target-cluster))
                     (decision-row project-id
                                   basis
                                   :cluster-bridge
                                   :medium
                                   (:xt/id edge)
                                   "Visible connection bridges two discovered clusters."
                                   {:sourceCluster source-cluster
                                    :targetCluster target-cluster
                                    :edge (edge-summary system-by-id edge)}
                                   {:scope {:source-cluster source-cluster
                                            :target-cluster target-cluster
                                            :target-kind :system-edge}
                                    :evidence-ids (edge-evidence-ids edge)
                                    :recommended-actions [:promote-edge
                                                          :split-system
                                                          :hide-edge]})))))
         vec)))

(defn- severity-rank
  [severity]
  ({:high 0 :medium 1 :low 2} severity 3))

(defn- maintenance-decision-queue
  [project-id basis system-by-id semantic-edges orphaned clusters]
  (->> (concat (ambiguous-edge-decisions project-id basis system-by-id semantic-edges)
               (cluster-bridge-decisions project-id basis system-by-id semantic-edges clusters)
               (supporting-edge-fanout-decisions project-id basis system-by-id semantic-edges)
               (support-only-external-api-decisions project-id basis system-by-id semantic-edges)
               (sparse-external-api-decisions project-id basis system-by-id semantic-edges)
               (orphan-decisions project-id basis orphaned))
       (sort-by (fn [decision]
                  [(severity-rank (:severity decision))
                   (:kind decision)
                   (:target decision)]))
       vec))

(defn- ratio
  [numerator denominator]
  (if (pos? denominator)
    (/ (double numerator) (double denominator))
    0.0))

(defn- scale-tier
  [{:keys [systems semantic-connections evidence]}]
  (cond
    (or (>= systems 500)
        (>= semantic-connections 2000)
        (>= evidence 10000)) :large
    (or (>= systems 150)
        (>= semantic-connections 600)
        (>= evidence 3000)) :medium
    :else :small))

(defn- visibility-counts
  [semantic-edges]
  (->> semantic-edges
       (map :visibility)
       frequencies
       (into (sorted-map))))

(defn- relation-counts
  [semantic-edges]
  (->> semantic-edges
       (mapcat :relations)
       frequencies
       (sort-by (comp name key))
       (into (sorted-map))))

(defn- evidence-kind-counts
  [evidence]
  (->> evidence
       (map :kind)
       frequencies
       (sort-by (comp name key))
       (into (sorted-map))))

(defn- top-hubs
  [system-by-id semantic-edges]
  (let [degree (frequencies (mapcat (juxt :source-id :target-id) semantic-edges))]
    (->> degree
         (map (fn [[id n]]
                (assoc (system-summary (get system-by-id id))
                       :degree n)))
         (remove #(nil? (:xt/id %)))
         (sort-by (juxt (comp - long :degree) :repo-id :label))
         (take 10)
         vec)))

(defn- cluster-summary
  [cluster]
  (select-keys cluster [:id :label :sourceLabel :nodeCount]))

(defn- cross-cluster-edges
  [systems system-by-id visible-edges]
  (let [primary-edges (filter #(= "primary" (:visibility %)) visible-edges)
        primary-clusters (cluster/clusters systems primary-edges)
        cluster-by-source (into {}
                                (map (juxt :sourceLabel identity))
                                primary-clusters)
        cluster-labels (cluster/cluster-labels systems primary-edges)]
    (->> visible-edges
         (keep (fn [edge]
                 (let [source-cluster (get cluster-labels (:source-id edge))
                       target-cluster (get cluster-labels (:target-id edge))]
                   (when (and source-cluster
                              target-cluster
                              (not= source-cluster target-cluster))
                     (assoc (edge-summary system-by-id edge)
                            :source-cluster (cluster-summary
                                             (get cluster-by-source source-cluster))
                            :target-cluster (cluster-summary
                                             (get cluster-by-source target-cluster)))))))
         (sort-by (fn [edge]
                    [(- (double (or (:salience edge) 0.0)))
                     (:xt/id edge)]))
         (take 10)
         vec)))

(defn- evidence-concentrations
  [system-by-id evidence]
  (->> evidence
       (group-by (juxt :system-id :kind))
       (map (fn [[[system-id kind] rows]]
              {:system (system-summary (get system-by-id system-id))
               :kind kind
               :count (count rows)}))
       (remove #(nil? (get-in % [:system :xt/id])))
       (sort-by (fn [{:keys [system kind count]}]
                  [(- (long count))
                   (name kind)
                   (get system :repo-id)
                   (get system :label)]))
       (take 10)
       vec))

(defn- graph-health
  [systems system-by-id evidence visible-edges orphaned]
  {:high-degree-hubs (top-hubs system-by-id visible-edges)
   :cross-cluster-edges (cross-cluster-edges systems system-by-id visible-edges)
   :orphaned-candidates (mapv system-summary (take 10 orphaned))
   :evidence-concentrations (evidence-concentrations system-by-id evidence)})

(defn- scale-report
  [systems evidence semantic-edges visible-edges clusters orphaned system-by-id]
  (let [counts {:systems (count systems)
                :evidence (count evidence)
                :semantic-connections (count semantic-edges)
                :visible-connections (count visible-edges)
                :noise-connections (count (filter #(= "noise" (:visibility %))
                                                  semantic-edges))
                :clusters (count clusters)
                :orphaned-systems (count orphaned)}]
    {:tier (scale-tier counts)
     :ratios {:visible (ratio (:visible-connections counts)
                              (:semantic-connections counts))
              :noise (ratio (:noise-connections counts)
                            (:semantic-connections counts))
              :orphaned (ratio (:orphaned-systems counts)
                               (:systems counts))}
     :counts counts
     :visibility-counts (visibility-counts semantic-edges)
     :relation-counts (relation-counts semantic-edges)
     :evidence-kind-counts (evidence-kind-counts evidence)
     :top-hubs (top-hubs system-by-id semantic-edges)}))

(defn- fold-in-action
  [kind command reason]
  {:kind kind
   :command command
   :reason reason})

(defn- fold-in-report
  [project-id scale decision-queue]
  (let [counts (:counts scale)
        noise-ratio (get-in scale [:ratios :noise])
        orphan-ratio (get-in scale [:ratios :orphaned])
        actions (cond-> [(fold-in-action
                          :review-primary-graph
                          (str "agraph graph systems --project " project-id
                               " --detail primary")
                          "Start from the compact system view before drilling down.")]
                  (pos? (:orphaned-systems counts))
                  (conj (fold-in-action
                         :review-orphans
                         "agraph project maintain <project.edn> --json"
                         "Research orphaned systems discovered during normal work."))

                  (< 0.35 noise-ratio)
                  (conj (fold-in-action
                         :review-noise
                         (str "agraph graph export systems --project " project-id
                              " --detail evidence")
                         "Inspect noisy evidence before promoting or hiding it."))

                  (< 0.20 orphan-ratio)
                  (conj (fold-in-action
                         :add-missing-connections
                         "agraph map include agraph.map.json <system> <repo>:<path>"
                         "Fold researched boundaries into the map overlay."))

                  (seq decision-queue)
                  (conj (fold-in-action
                         :classify-one-decision
                         (str "agraph classify decision "
                              (:id (first decision-queue))
                              " --project " project-id)
                         "Ask an LLM only about one bounded maintenance decision.")))]
    {:schema "agraph.fold-in/v1"
     :summary "Maintain the graph incrementally while coding; update only researched boundaries."
     :actions actions}))

(defn maintenance-report
  "Return read-only maintenance findings for a project's current system graph."
  [xtdb project-id {:keys [low-confidence-threshold map-overlay]
                    :or {low-confidence-threshold 0.60}}]
  (let [raw-systems (->> (store/rows-by-field xtdb (:system-nodes store/tables) :project-id project-id)
                         (filter :active?)
                         vec)
        raw-edges (->> (store/rows-by-field xtdb (:system-edges store/tables) :project-id project-id)
                       (filter :active?)
                       vec)
        raw-evidence (->> (store/rows-by-field xtdb (:system-evidence store/tables) :project-id project-id)
                          (filter :active?)
                          vec)
        overlay-result (apply-maintenance-overlay raw-systems raw-edges raw-evidence map-overlay)
        systems (:systems overlay-result)
        edges (:edges overlay-result)
        evidence (:evidence overlay-result)
        system-ids (set (map :xt/id systems))
        incident-ids (set (mapcat (juxt :source-id :target-id) edges))
        orphaned (->> systems
                      (remove #(contains? incident-ids (:xt/id %)))
                      (sort-by (juxt :repo-id :label))
                      vec)
        dangling-edges (->> edges
                            (remove #(and (contains? system-ids (:source-id %))
                                          (contains? system-ids (:target-id %))))
                            (sort-by :xt/id)
                            vec)
        missing-evidence-systems (->> evidence
                                      (remove #(contains? system-ids (:system-id %)))
                                      (sort-by (juxt :repo-id :path :source-line))
                                      vec)
        low-confidence-edges (->> edges
                                  (filter #(< (double (:confidence %))
                                              (double low-confidence-threshold)))
                                  (sort-by (juxt :confidence :relation :xt/id))
                                  vec)
        semantic-edges (salience/semantic-connections project-id systems edges)
        visible-edges (remove #(= "noise" (:visibility %)) semantic-edges)
        clusters (cluster/clusters systems visible-edges)
        system-by-id (into {} (map (juxt :xt/id identity)) systems)
        basis (graph-basis project-id systems edges evidence semantic-edges)
        graph-health (graph-health systems
                                   system-by-id
                                   evidence
                                   visible-edges
                                   orphaned)
        decision-queue (maintenance-decision-queue project-id
                                                   basis
                                                   system-by-id
                                                   semantic-edges
                                                   orphaned
                                                   clusters)
        infra-review-queue (infra-review/review-packets
                            {:project-id project-id
                             :basis basis
                             :systems systems
                             :evidence evidence})
        scale (scale-report systems
                            evidence
                            semantic-edges
                            visible-edges
                            clusters
                            orphaned
                            system-by-id)]
    {:project-id project-id
     :graph-basis basis
     :map (:map overlay-result)
     :counts {:systems (count systems)
              :edges (count edges)
              :evidence (count evidence)
              :orphaned-systems (count orphaned)
              :dangling-edges (count dangling-edges)
              :evidence-with-missing-system (count missing-evidence-systems)
              :low-confidence-edges (count low-confidence-edges)
              :semantic-connections (count semantic-edges)
              :visible-connections (count visible-edges)
              :clusters (count clusters)
              :maintenance-decisions (count decision-queue)
              :infra-review-items (count infra-review-queue)}
     :scale scale
     :graph-health graph-health
     :fold-in (fold-in-report project-id scale decision-queue)
     :orphaned-systems orphaned
     :dangling-edges dangling-edges
     :evidence-with-missing-system missing-evidence-systems
     :low-confidence-edges low-confidence-edges
     :semantic-connections semantic-edges
     :clusters clusters
     :decision-queue decision-queue
     :infra-review-queue infra-review-queue}))
