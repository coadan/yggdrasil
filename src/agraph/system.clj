(ns agraph.system
  "Derived project system graph inference."
  (:require [agraph.fs :as fs]
            [agraph.hash :as hash]
            [agraph.index :as index]
            [agraph.search-doc :as search-doc]
            [agraph.system.candidate :as candidate]
            [agraph.text :as text]
            [agraph.xtdb :as store]
            [clojure.string :as str])
  (:import [java.net URI]))

(def generic-aliases
  #{"app" "apps" "base" "bases" "client" "clients" "component" "components"
    "core" "libraries" "library" "lib" "libs" "main" "script" "scripts"
    "service" "services" "src" "test" "tests" "tool" "tools"})

(def external-repo-id "__external")

(def placeholder-hosts
  #{"example.com"
    "example.net"
    "example.org"
    "httpbin.org"
    "jsonplaceholder.typicode.com"
    "picsum.photos"
    "postman-echo.com"})

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

(defn- system-for-file
  [project repo-by-id candidates-by-repo file]
  (let [repo (repo-for-row repo-by-id file)
        spec (path-system repo (get candidates-by-repo (:repo-id file)) (:path file))]
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

(defn- evidence-id
  [project-id repo-id system-id kind path line normalized-value]
  (str "evidence:" (hash/short-hash [project-id repo-id system-id kind path line normalized-value])))

(defn- evidence-row
  [run-id project-id repo-id system-id file kind line label normalized-value confidence]
  {:xt/id (evidence-id project-id repo-id system-id kind (:path file) line normalized-value)
   :project-id project-id
   :repo-id repo-id
   :system-id system-id
   :file-id (:file-id file)
   :path (:path file)
   :kind kind
   :label label
   :normalized-value normalized-value
   :source-line (long (or line 1))
   :confidence (double confidence)
   :active? true
   :run-id run-id})

(defn- url-values
  [line]
  (->> (re-seq #"https?://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+" line)
       (map #(str/replace % #"[\"')\],;]+$" ""))
       distinct))

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
       (not (contains? placeholder-hosts host))
       (not (some #(str/ends-with? host (str "." %)) placeholder-hosts))
       (not (private-ip? host))
       (not (some #(str/ends-with? host %)
                  [".cluster.local" ".internal" ".local" ".svc"]))))

(defn- docs-path?
  [path]
  (let [path (str/lower-case (str path))]
    (or (str/ends-with? path ".md")
        (str/starts-with? path "docs/")
        (str/starts-with? path "doc/")
        (str/includes? path "/docs/")
        (str/includes? path "/doc/"))))

(defn- test-path?
  [path]
  (let [path (str/lower-case (str path))]
    (or (str/starts-with? path "test/")
        (str/starts-with? path "tests/")
        (str/includes? path "/test/")
        (str/includes? path "/tests/")
        (str/includes? path "/testdata/")
        (str/starts-with? path "testdata/")
        (str/includes? path "_test.")
        (str/includes? path "-test."))))

(defn- example-path?
  [path]
  (let [path (str/lower-case (str path))]
    (or (str/includes? path "example")
        (str/includes? path "fixture")
        (str/includes? path "mock")
        (str/includes? path "sample"))))

(defn- operational-url-evidence?
  [row]
  (and (= :url (:kind row))
       (not (docs-path? (:path row)))
       (not (test-path? (:path row)))
       (not (example-path? (:path row)))))

(defn- env-values
  [line]
  (->> (re-seq #"\b[A-Z][A-Z0-9_]*(?:URL|URI|HOST|PORT|ENDPOINT|QUEUE|TOPIC|TASK_QUEUE|DATABASE|DB)[A-Z0-9_]*\b"
               line)
       distinct))

(defn- port-values
  [line]
  (->> (re-seq #"\b(?:port|targetPort|containerPort):\s*([0-9]{2,5})\b" line)
       (map second)
       distinct))

(defn- route-values
  [line]
  (->> (re-seq #"['\"](/(?:[A-Za-z0-9._~:-]+/?)*)['\"]" line)
       (map second)
       (filter #(<= 2 (count %)))
       distinct))

(defn- line-evidence
  [run-id project-id repo-id system-id file idx line]
  (let [line-no (inc idx)]
    (concat
     (map #(evidence-row run-id project-id repo-id system-id file :url line-no % (normalize-token %) 0.70)
          (url-values line))
     (map #(evidence-row run-id project-id repo-id system-id file :env-var line-no % (normalize-token %) 0.65)
          (env-values line))
     (map #(evidence-row run-id project-id repo-id system-id file :port line-no (str "port " %) % 0.55)
          (port-values line))
     (map #(evidence-row run-id project-id repo-id system-id file :route line-no % (normalize-token %) 0.55)
          (route-values line)))))

(defn- yaml-docs
  [content]
  (str/split content #"(?m)^---\s*$"))

(defn- yaml-resource
  [doc]
  (let [lines (str/split-lines doc)
        kind (some (fn [line]
                     (second (re-matches #"^\s*kind:\s*([A-Za-z0-9_-]+)\s*$" line)))
                   lines)
        name (some (fn [line]
                     (second (re-matches #"^\s*name:\s*([A-Za-z0-9_.-]+)\s*$" line)))
                   lines)]
    (when (and kind name)
      {:kind kind :name name})))

(defn- yaml-evidence
  [run-id project-id repo-id system-id file]
  (when (#{:yaml :helm :compose} (:kind file))
    (->> (yaml-docs (:content file))
         (keep yaml-resource)
         (map (fn [{:keys [kind name]}]
                (let [evidence-kind (case kind
                                      "Service" :k8s-service
                                      "Ingress" :k8s-ingress
                                      ("Deployment" "StatefulSet" "DaemonSet") :k8s-workload
                                      "ConfigMap" :k8s-config
                                      :yaml-resource)]
                  (evidence-row run-id
                                project-id
                                repo-id
                                system-id
                                file
                                evidence-kind
                                1
                                (str kind " " name)
                                (normalize-token name)
                                0.80)))))))

(defn- file-evidence
  [run-id project-id repo-by-id candidates-by-repo file]
  (let [repo (repo-for-row repo-by-id file)
        node (system-node run-id
                          project-id
                          repo
                          (path-system repo
                                       (get candidates-by-repo (:repo-id file))
                                       (:path file)))
        system-id (:xt/id node)]
    (vec
     (concat
      (mapcat (fn [[idx line]]
                (line-evidence run-id project-id (:repo-id file) system-id file idx line))
              (map-indexed vector (str/split-lines (:content file))))
      (yaml-evidence run-id project-id (:repo-id file) system-id file)))))

(defn- evidence-for-project
  [run-id {:keys [id repos]} candidates-by-repo]
  (let [project-id id
        repo-by-id (into {} (map (juxt :id identity)) repos)]
    (->> repos
         (mapcat (fn [{repo-id :id root :root}]
                   (->> (fs/scan-files root)
                        (map #(assoc %
                                     :project-id project-id
                                     :repo-id repo-id
                                     :file-id (index/file-id project-id repo-id (:path %))))
                        (mapcat #(file-evidence run-id
                                                project-id
                                                repo-by-id
                                                candidates-by-repo
                                                %)))))
         vec)))

(defn- node-system-id
  [file-by-id repo-by-id candidates-by-repo project-id node]
  (when-let [file (get file-by-id (:file-id node))]
    (:xt/id (system-node (:run-id node)
                         project-id
                         (repo-for-row repo-by-id file)
                         (path-system (repo-for-row repo-by-id file)
                                      (get candidates-by-repo (:repo-id file))
                                      (:path file))))))

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
  [run-id project-id repo-by-id candidates-by-repo files nodes edges]
  (let [file-by-id (into {} (map (juxt :xt/id identity)) files)
        node-by-id (into {} (map (juxt :xt/id identity)) nodes)]
    (->> edges
         (keep (fn [edge]
                 (let [source-node (get node-by-id (:source-id edge))
                       target-node (get node-by-id (:target-id edge))
                       source-system (some->> source-node
                                              (node-system-id file-by-id
                                                              repo-by-id
                                                              candidates-by-repo
                                                              project-id))
                       target-system (some->> target-node
                                              (node-system-id file-by-id
                                                              repo-by-id
                                                              candidates-by-repo
                                                              project-id))]
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
         vec)))

(defn- system-id-for-file
  [project-id repo-by-id candidates-by-repo file]
  (let [repo (repo-for-row repo-by-id file)]
    (:xt/id (system-node (:run-id file)
                         project-id
                         repo
                         (path-system repo
                                      (get candidates-by-repo (:repo-id file))
                                      (:path file))))))

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
  [project-id repo-by-id candidates-by-repo files nodes edges]
  (let [file-system-by-id (into {}
                                (map (fn [file]
                                       [(:xt/id file)
                                        (system-id-for-file project-id
                                                            repo-by-id
                                                            candidates-by-repo
                                                            file)]))
                                files)
        node-system-by-id (into {}
                                (keep (fn [node]
                                        (when-let [system-id (get file-system-by-id (:file-id node))]
                                          [(:xt/id node) system-id])))
                                nodes)]
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
        (update-vals #(merge (zero-metrics) %)))))

(defn- attach-metrics
  [metrics system]
  (assoc system :metrics (get metrics (:xt/id system) (zero-metrics))))

(defn- value-system-edges
  [run-id project-id evidence]
  (->> evidence
       (remove #(contains? #{:port :route :url} (:kind %)))
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

(defn- external-api-nodes
  [run-id project-id evidence]
  (->> evidence
       (filter operational-url-evidence?)
       (keep (comp url-host :label))
       (filter external-host?)
       distinct
       sort
       (mapv #(external-system-node run-id project-id %))))

(defn- external-api-edges
  [run-id project-id evidence]
  (->> evidence
       (filter operational-url-evidence?)
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
        nodes (vec (active-nodes xtdb id))
        edges (vec (active-edges xtdb id))
        metrics (system-metrics id repo-by-id candidates-by-repo files nodes edges)
        candidate-systems (->> candidates-by-repo
                               (mapcat (fn [[repo-id candidates]]
                                         (let [repo (get repo-by-id repo-id)]
                                           (map #(system-node run-id id repo %) candidates))))
                               distinct
                               (mapv #(attach-metrics metrics %)))
        file-systems (->> files
                          (map #(system-for-file project repo-by-id candidates-by-repo %))
                          (map #(attach-metrics metrics %))
                          distinct
                          vec)
        evidence (evidence-for-project run-id project candidates-by-repo)
        external-systems (external-api-nodes run-id id evidence)
        systems (vec (distinct (concat candidate-systems file-systems external-systems)))
        code-edges (code-system-edges run-id id repo-by-id candidates-by-repo files nodes edges)
        value-edges (value-system-edges run-id id evidence)
        external-edges (external-api-edges run-id id evidence)
        system-edges (merge-system-edges run-id id (concat code-edges
                                                           value-edges
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
  [xtdb project-id {:keys [low-confidence-threshold]
                    :or {low-confidence-threshold 0.60}}]
  (let [systems (->> (store/rows-by-field xtdb (:system-nodes store/tables) :project-id project-id)
                     (filter :active?)
                     vec)
        edges (->> (store/rows-by-field xtdb (:system-edges store/tables) :project-id project-id)
                   (filter :active?)
                   vec)
        evidence (->> (store/rows-by-field xtdb (:system-evidence store/tables) :project-id project-id)
                      (filter :active?)
                      vec)
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
                                  vec)]
    {:project-id project-id
     :counts {:systems (count systems)
              :edges (count edges)
              :evidence (count evidence)
              :orphaned-systems (count orphaned)
              :dangling-edges (count dangling-edges)
              :evidence-with-missing-system (count missing-evidence-systems)
              :low-confidence-edges (count low-confidence-edges)}
     :orphaned-systems orphaned
     :dangling-edges dangling-edges
     :evidence-with-missing-system missing-evidence-systems
     :low-confidence-edges low-confidence-edges}))
