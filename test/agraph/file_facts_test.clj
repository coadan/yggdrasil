(ns agraph.file-facts-test
  (:require [agraph.file-facts :as file-facts]
            [clojure.test :refer [deftest is]]))

(defn- facts
  [content kind]
  (file-facts/facts-for-file
   "run/test"
   "project"
   "repo"
   {:file-id "file:test"
    :path "scripts/deploy.sh"
    :kind kind
    :content content}))

(deftest facts-for-file-preserves-line-numbers-with-mixed-newlines
  (let [rows (facts (str "plain\r\n"
                         "API_URL=https://example.test/api\r"
                         "route = \"/v1/panels\"\n"
                         "containerPort: 8080\n")
                    :shell)
        by-kind (group-by :kind rows)]
    (is (= 2 (:source-line (first (:env-var by-kind)))))
    (is (= 2 (:source-line (first (:url by-kind)))))
    (is (= 3 (:source-line (first (:route by-kind)))))
    (is (= 4 (:source-line (first (:port by-kind)))))))

(deftest facts-for-file-streams-shell-container-image-lines
  (let [rows (facts (str "IMAGE=registry.example.test/team/api:1\n"
                         "WORKER_IMAGE=registry.example.test/team/worker:2\n")
                    :shell)
        image-facts (filter #(= :container-image-consumer (:kind %)) rows)]
    (is (= [1 2] (mapv :source-line image-facts)))
    (is (= #{"container-image:api" "container-image:worker"}
           (set (map :normalized-value image-facts))))))
