(ns ygg.embedding-query-test
  (:require [ygg.embedding :as embedding]
            [ygg.embedding.local :as local]
            [ygg.env :as env]
            [ygg.index :as index]
            [ygg.query :as query]
            [ygg.xtdb :as store]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  [prefix]
  (let [file (java.nio.file.Files/createTempDirectory prefix
                                                      (make-array java.nio.file.attribute.FileAttribute 0))]
    (.getPath (.toFile file))))

(def fake-client
  (embedding/fake-client
   {:dimensions 3
    :dictionary {"uppercase" [1.0 0.0 0.0]
                 "touppercase" [1.0 0.0 0.0]
                 "shout" [1.0 0.0 0.0]
                 "greeting" [0.0 1.0 0.0]
                 "greet" [0.0 1.0 0.0]
                 "flow" [0.0 1.0 0.0]}}))

(deftest vector-scoring
  (is (= 1.0 (embedding/cosine [1 0] [1 0])))
  (is (= 0.0 (embedding/cosine [1 0] [0 1])))
  (is (= 1.0 (embedding/cosine01 [1 0] [1 0]))))

(deftest pending-search-docs-use-current-doc-embedding-tuples
  (let [calls (atom [])
        docs [{:xt/id "search-doc:done"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:done"
               :input-sha "sha:done"
               :active? true}
              {:xt/id "search-doc:pending"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:pending"
               :input-sha "sha:pending"
               :active? true}]
        pending (with-redefs [embedding/all-search-docs
                              (fn [_ scope]
                                (is (= {:project-id "project-a"
                                        :repo-id "app"}
                                       scope))
                                docs)
                              embedding/all-embeddings
                              (fn [& _]
                                (throw (ex-info "all embeddings should not be loaded"
                                                {})))
                              store/rows-with-field-tuples
                              (fn [_ request]
                                (swap! calls conj request)
                                [{:xt/id "embedding:done"
                                  :project-id "project-a"
                                  :repo-id "app"
                                  :target-id "target:done"
                                  :provider :fake
                                  :model "fake-model"
                                  :input-sha "sha:done"
                                  :active? true}])]
                  (vec (embedding/pending-search-docs
                        :xtdb
                        {:project-id "project-a"
                         :repo-id "app"
                         :provider :fake
                         :model "fake-model"})))]
    (is (= ["target:pending"] (mapv :target-id pending)))
    (is (= 1 (count @calls)))
    (is (= {:table (:embeddings store/tables)
            :tuple-fields [:target-id :input-sha]
            :tuples [{:target-id "target:done" :input-sha "sha:done"}
                     {:target-id "target:pending" :input-sha "sha:pending"}]
            :constraints {:project-id "project-a"
                          :repo-id "app"
                          :provider :fake
                          :model "fake-model"
                          :active? true}}
           (select-keys (first @calls)
                        [:table :tuple-fields :tuples :constraints])))
    (is (not-any? #{'*} (:return-fields (first @calls))))))

(deftest embeds-search-docs-incrementally
  (let [xtdb-path (temp-dir "ygg-embed-xtdb")
        repo (.getPath (io/file "test/fixtures/sample-repo"))]
    (store/with-node xtdb-path
      (fn [xtdb]
        (index/index-repo! xtdb repo {})
        (let [first-summary (embedding/embed-search-docs! xtdb fake-client {:batch-size 4})
              second-summary (embedding/embed-search-docs! xtdb fake-client {:batch-size 4})]
          (is (pos? (:search-docs first-summary)))
          (is (= (:search-docs first-summary) (:embedded first-summary)))
          (is (zero? (:embedded second-summary)))
          (is (= (:search-docs second-summary) (:skipped second-summary))))))))

(deftest embed-search-docs-counts-total-with-count-query
  (let [search-doc-calls (atom 0)
        count-calls (atom [])
        committed (atom [])
        docs [{:xt/id "search-doc:pending"
               :project-id "project-a"
               :repo-id "app"
               :target-id "target:pending"
               :input-sha "sha:pending"
               :text "uppercase greeting"
               :active? true}]]
    (with-redefs [embedding/all-search-docs
                  (fn [_ scope]
                    (swap! search-doc-calls inc)
                    (is (= {:project-id "project-a"
                            :repo-id "app"}
                           scope))
                    docs)
                  store/rows-with-field-tuples
                  (fn [& _] [])
                  store/count-rows
                  (fn [_ table constraints ctx]
                    (swap! count-calls conj {:table table
                                             :constraints constraints
                                             :ctx ctx})
                    3)
                  store/commit-embeddings!
                  (fn [_ rows]
                    (swap! committed conj rows)
                    {:embeddings (count rows)})]
      (let [summary (embedding/embed-search-docs!
                     :xtdb
                     fake-client
                     {:project-id "project-a"
                      :repo-id "app"
                      :batch-size 4})]
        (is (= 1 @search-doc-calls))
        (is (= [{:table (:search-docs store/tables)
                 :constraints {:project-id "project-a"
                               :repo-id "app"
                               :active? true}
                 :ctx {}}]
               @count-calls))
        (is (= 1 (count @committed)))
        (is (= {:provider :fake
                :model "fake-embedding"
                :search-docs 3
                :pending 1
                :embedded 1
                :skipped 2}
               summary))))))

(deftest embed-search-docs-closes-closeable-client
  (let [closed? (atom false)]
    (with-redefs [embedding/all-search-docs (fn [& _] [])
                  store/rows-with-field-tuples (fn [& _] [])
                  store/count-rows (fn [& _] 0)]
      (is (= {:provider :closeable
              :model "test-model"
              :search-docs 0
              :pending 0
              :embedded 0
              :skipped 0}
             (embedding/embed-search-docs!
              :xtdb
              {:provider :closeable
               :model "test-model"
               :embed-batch (fn [_] [])
               :close (fn [] (reset! closed? true))}
              {})))
      (is @closed?))))

(deftest local-embedding-client-speaks-jsonl-worker-protocol
  (let [root (temp-dir "ygg-local-embedding-worker")
        worker (io/file root "worker.py")]
    (spit worker
          (str "import json, sys\n"
               "for line in sys.stdin:\n"
               "    req = json.loads(line)\n"
               "    vectors = []\n"
               "    for text in req['inputs']:\n"
               "        vectors.append([float(len(text)), 1.0])\n"
               "    print(json.dumps({'vectors': vectors}), flush=True)\n"))
    (let [client (local/client {:command (str "python3 " (.getPath worker))
                                :model "fake-local"})]
      (try
        (is (= :local (:provider client)))
        (is (= "fake-local" (:model client)))
        (is (= [[5.0 1.0] [4.0 1.0]]
               ((:embed-batch client) ["alpha" "beta"])))
        (finally
          ((:close client)))))))

(deftest local-default-command-uses-configured-python-and-bundled-worker
  (with-redefs [local/default-python (fn [] "/tmp/ygg venv/bin/python")
                local/default-worker-path (fn [] "/tmp/ygg home/scripts/local-embedding-worker.py")]
    (is (= "'/tmp/ygg venv/bin/python' '/tmp/ygg home/scripts/local-embedding-worker.py'"
           (local/default-command)))))

(deftest local-default-python-detects-conventional-project-venv
  (let [root (temp-dir "ygg-local-embedding-venv")
        python (io/file root "python")]
    (spit python "")
    (with-redefs [env/get-env (fn [_] nil)
                  local/default-venv-python-path (fn [] (.getPath python))]
      (is (= (.getPath python) (local/default-python))))))

(deftest local-embedding-setup-uses-bundled-requirements
  (let [root (temp-dir "ygg-local-embedding-setup")
        requirements (io/file root "requirements.txt")
        calls (atom [])]
    (spit requirements "sentence-transformers\n")
    (with-redefs [local/default-requirements-path (fn [] (.getPath requirements))
                  shell/sh (fn [& args]
                             (swap! calls conj (vec args))
                             {:exit 0 :out "" :err ""})]
      (is (= {:provider :local
              :venv "embedding-venv"
              :python (local/venv-python-path "embedding-venv")
              :requirements (.getPath requirements)
              :created? true
              :default? false}
             (local/setup-venv! {:venv-path "embedding-venv"
                                 :python "python3.12"})))
      (is (= [["python3.12" "-m" "venv" "embedding-venv"]
              [(local/venv-python-path "embedding-venv")
               "-m"
               "pip"
               "install"
               "-r"
               (.getPath requirements)]]
             @calls)))))

(deftest bundled-local-embedding-worker-emits-jsonl
  (let [module-dir (temp-dir "ygg-local-embedding-module")
        package-dir (io/file module-dir "sentence_transformers")]
    (.mkdirs package-dir)
    (spit (io/file package-dir "__init__.py")
          (str "class SentenceTransformer:\n"
               "    def __init__(self, name):\n"
               "        self.name = name\n"
               "    def encode(self, texts, batch_size=None, normalize_embeddings=True, show_progress_bar=False):\n"
               "        return [[float(len(text)), 1.0] for text in texts]\n"))
    (let [{:keys [exit out err]} (shell/sh "env"
                                           (str "PYTHONPATH=" module-dir)
                                           "python3"
                                           "scripts/local-embedding-worker.py"
                                           "fake-model"
                                           :in "{\"inputs\":[\"alpha\",\"beta\"]}\n")
          response (json/read-json out :key-fn keyword)]
      (is (zero? exit) err)
      (is (= [[5.0 1.0] [4.0 1.0]]
             (:vectors response))))))

(deftest hybrid-query-ranks-semantic-matches
  (let [xtdb-path (temp-dir "ygg-hybrid-xtdb")
        repo (.getPath (io/file "test/fixtures/sample-repo"))]
    (store/with-node xtdb-path
      (fn [xtdb]
        (index/index-repo! xtdb repo {})
        (embedding/embed-search-docs! xtdb fake-client {:batch-size 4})
        (let [results (query/semantic-query xtdb
                                            "uppercase greeting"
                                            {:retriever :hybrid
                                             :embedding-client fake-client
                                             :limit 20})
              labels (set (map :label results))]
          (is (seq results))
          (is (contains? labels "sample.util/shout"))
          (is (contains? labels "Greeting Flow"))
          (is (every? :score-components results)))))))

(deftest auto-query-falls-back-before-local-worker-when-no-embeddings-exist
  (let [xtdb-path (temp-dir "ygg-auto-no-embeddings-xtdb")
        repo (.getPath (io/file "test/fixtures/sample-repo"))
        embed-called? (atom false)
        client {:provider :local
                :model "fake-local"
                :embed-batch (fn [_]
                               (reset! embed-called? true)
                               (throw (ex-info "worker should not be called" {})))}]
    (store/with-node xtdb-path
      (fn [xtdb]
        (index/index-repo! xtdb repo {})
        (let [report (query/search-report xtdb
                                          "uppercase greeting"
                                          {:retriever :auto
                                           :embedding-client client
                                           :limit 20})]
          (is (= :auto (:retriever-requested report)))
          (is (= :lexical (:retriever-effective report)))
          (is (seq (:results report)))
          (is (false? @embed-called?)))))))
