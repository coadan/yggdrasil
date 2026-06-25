(ns ygg.file-facts-test
  (:require [ygg.file-facts :as file-facts]
            [clojure.string :as str]
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
    (is (= :runtime-config (:url-context (first (:url by-kind)))))
    (is (= 3 (:source-line (first (:route by-kind)))))
    (is (= 4 (:source-line (first (:port by-kind)))))))

(deftest facts-for-file-does-not-read-sql-security-as-env-var
  (let [rows (facts (str "create function extensions.grant_access()\n"
                         "returns event_trigger\n"
                         "security definer\n"
                         "as $$ select 1 $$;\n")
                    :sql)
        by-kind (group-by :kind rows)]
    (is (empty? (:env-var by-kind)))
    (is (= [{:kind :sql-security
             :label "SECURITY DEFINER"
             :normalized-value "security-definer"
             :source-line 3}]
           (mapv #(select-keys % [:kind :label :normalized-value :source-line])
                 (:sql-security by-kind))))))

(deftest url-facts-tag-doc-links-as-references
  (let [rows (facts "- https://docs.example.com/sdk\n" :doc)
        url-row (first (filter #(= :url (:kind %)) rows))]
    (is (= "https://docs.example.com/sdk" (:label url-row)))
    (is (= :reference (:url-context url-row)))))

(deftest facts-for-file-skips-pathologically-long-lines
  (let [rows (facts (str "API_URL=https://example.test/api\n"
                         "OPENAI_API_KEY=" (str/join "" (repeat 10000 "x")) "\n"
                         "WORKER_ENDPOINT=https://worker.example.test/jobs\n")
                    :env)
        labels (set (map :label rows))
        urls (filter #(= :url (:kind %)) rows)]
    (is (contains? labels "https://example.test/api"))
    (is (contains? labels "https://worker.example.test/jobs"))
    (is (not (contains? labels "OPENAI_API_KEY")))
    (is (= [1 3] (mapv :source-line urls)))))

(deftest facts-for-file-streams-shell-container-image-lines
  (let [rows (facts (str "IMAGE=registry.example.test/team/api:1\n"
                         "WORKER_IMAGE=registry.example.test/team/worker:2\n")
                    :shell)
        image-facts (filter #(= :container-image-consumer (:kind %)) rows)]
    (is (= [1 2] (mapv :source-line image-facts)))
    (is (= #{"container-image:api" "container-image:worker"}
           (set (map :normalized-value image-facts))))))
