(ns ygg.context-graph-support-test
  (:require [clojure.test :refer [deftest is]]
            [ygg.context :as context]))

(deftest graph-neighbor-support-labels-prefer-earlier-seeds
  (let [row (#'context/source-graph-neighbor-row
             {:xt/id "node:local"
              :path "main.tf"
              :label "local.vpc_id"
              :kind :terraform-local
              :repo-id "repo"}
             1.2
             [{:rank 12 :label "aws_vpn_gateway_attachment.this"}
              {:rank 3 :label "aws_flow_log.this"}
              {:rank 8 :label "local.flow_log_destination_arn"}
              {:rank 2 :label "vpc-flow-logs.tf"}
              {:rank 20 :label "aws_internet_gateway.this"}]
             2)]
    (is (= ["vpc-flow-logs.tf"
            "aws_flow_log.this"
            "local.flow_log_destination_arn"
            "aws_vpn_gateway_attachment.this"]
           (:supportLabels row)))))

(deftest candidate-input-ranking-reserves-supported-graph-neighbor-paths
  (with-redefs [context/candidate-input-retrieval-prefix-limit 1
                context/candidate-input-supported-source-prefix-limit 3]
    (let [ranked (#'context/ranked-candidate-inputs
                  ["flow" "log" "vpc"]
                  [{:path "docs/noise.md"
                    :score 9.0
                    :label "flow log reference"}
                   {:path "docs/other.md"
                    :score 8.0
                    :label "another flow log reference"}]
                  [{:path "vpc-flow-logs.tf"
                    :score 0.6
                    :target-kind :node
                    :result-kind :node
                    :label "aws_flow_log.this"
                    :supportLabels ["local.vpc_id"]}
                   {:path "variables.tf"
                    :score 0.6
                    :target-kind :node
                    :result-kind :node
                    :label "var.flow_log_destination_type"
                    :supportLabels ["aws_flow_log.this"]}
                   {:path "main.tf"
                    :score 0.6
                    :target-kind :node
                    :result-kind :node
                    :label "local.vpc_id"
                    :supportLabels ["aws_flow_log.this"]}
                   {:path "docs/flow-log.md"
                    :score 20.0
                    :target-kind :node
                    :result-kind :node
                    :kind :markdown
                    :label "flow log reference"
                    :supportLabels ["aws_flow_log.this"]}
                   {:path "modules/flow-log/main.tf"
                    :score 10.0
                    :target-kind :node
                    :result-kind :node
                    :label "module flow log"
                    :supportLabels ["aws_flow_log.this"]}
                   {:path "unconnected.tf"
                    :score 100.0
                    :target-kind :node
                    :result-kind :node
                    :label "unconnected"}])]
      (is (= ["main.tf"
              "vpc-flow-logs.tf"
              "variables.tf"
              "docs/noise.md"]
             (->> ranked
                  (map :path)
                  (take 4)
                  vec))))))
