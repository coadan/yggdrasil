(ns agraph.system-salience-test
  (:require [agraph.system.cluster :as cluster]
            [agraph.system.decision-classifier :as decision-classifier]
            [agraph.system.salience :as salience]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- node
  [id label kind]
  {:xt/id id
   :id id
   :label label
   :kind kind})

(defn- edge
  [source target relation & {:keys [evidence confidence]
                             :or {evidence ["e1"]
                                  confidence 0.9}}]
  {:xt/id (str source ":" target ":" (name relation))
   :source-id source
   :target-id target
   :relation relation
   :confidence confidence
   :evidence-ids evidence
   :rules [(name relation)]})

(deftest salience-ranks-strong-connections-above-noise
  (let [nodes [(node "api" "API" :service)
               (node "core" "Core" :library)
               (node "repo" "repo" :repo-boundary)
               (node "external" "external.example" :external-api)]
        connections (salience/semantic-connections
                     "fixture"
                     nodes
                     [(edge "api" "core" :code-depends-on
                            :evidence ["a" "b" "c"])
                      (edge "api" "external" :references)
                      (edge "repo" "api" :shares-config)])
        by-target (into {} (map (juxt :target-id identity)) connections)]
    (is (= "primary" (:visibility (get by-target "core"))))
    (is (= "noise" (:visibility (get by-target "external"))))
    (is (= "noise" (:visibility (get by-target "api"))))
    (is (< (:salience (get by-target "external"))
           (:salience (get by-target "core"))))))

(deftest clusters-split-dense-subgraphs-across-weak-bridges
  (let [nodes [(node "api" "Flows API" :service)
               (node "worker" "Flows Worker" :service)
               (node "lib" "Flows Library" :library)
               (node "cli" "Workbench CLI" :tool)
               (node "vscode" "VS Code Extension" :package)]
        edges [(assoc (edge "api" "lib" :code-depends-on
                            :evidence ["a" "b" "c"])
                      :salience 9.0
                      :visibility "primary")
               (assoc (edge "worker" "lib" :code-depends-on
                            :evidence ["d" "e" "f"])
                      :salience 9.0
                      :visibility "primary")
               (assoc (edge "cli" "vscode" :code-depends-on
                            :evidence ["g" "h" "i"])
                      :salience 9.0
                      :visibility "primary")
               (assoc (edge "cli" "api" :references)
                      :salience 1.0
                      :visibility "noise")]
        labels (cluster/cluster-labels nodes edges)]
    (is (= (get labels "api") (get labels "worker")))
    (is (= (get labels "api") (get labels "lib")))
    (is (= (get labels "cli") (get labels "vscode")))
    (is (not= (get labels "api") (get labels "cli")))))

(deftest decision-classifier-sends-one-maintenance-decision
  (let [messages* (atom nil)
        decision {:id "maintenance-decision:123"
                  :kind :ambiguous-high-salience-edge
                  :target "system-connection:123"}
        result (decision-classifier/classify
                {:decision decision
                 :client {:provider :fake
                          :model "fake"
                          :complete-json (fn [messages]
                                           (reset! messages* messages)
                                           {:recommendation "reject"
                                            :confidence 0.9
                                            :reason "Weak evidence."
                                            :mapPatch []})}})]
    (is (= decision-classifier/schema (:schema result)))
    (is (= "maintenance-decision:123" (:decisionId result)))
    (is (= "reject" (:recommendation result)))
    (is (re-find #"maintenance-decision:123" (-> @messages* second :content)))
    (is (not (re-find #"\"candidates\"" (-> @messages* second :content))))))

(deftest decision-packet-includes-explicit-allowed-actions
  (let [packet (decision-classifier/decision-packet
                {:id "maintenance-decision:123"
                 :kind :unclustered-system
                 :target "system:fixture:api"})]
    (is (= ["accept-system"
            "reject-system"
            "set-system-kind"
            "add-edge"
            "set-edge-visibility"
            "reject-external-api"
            "none"]
           (:allowedActions packet)))
    (is (str/includes? (-> packet :messages second :content)
                       "\"allowedActions\""))))
