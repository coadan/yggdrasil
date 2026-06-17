(ns agraph.watch-test
  (:require [agraph.watch :as watch]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(deftest watchable-path-detects-supported-non-ignored-files
  (is (watch/watchable-path? "src/app.clj"))
  (is (watch/watchable-path? "Dockerfile"))
  (is (watch/watchable-path? "infra/deploy.yaml"))
  (is (not (watch/watchable-path? ".dev/report.json")))
  (is (not (watch/watchable-path? "target/classes/app.clj")))
  (is (not (watch/watchable-path? "package-lock.json")))
  (is (not (watch/watchable-path? "web/component.vue"))))

(deftest coalesce-events-keeps-distinct-watchable-paths
  (is (= ["README.md" "src/app.clj"]
         (watch/coalesce-events ["src/app.clj"
                                 "src/app.clj"
                                 ".git/index"
                                 "README.md"
                                 "node_modules/pkg/index.js"]))))

(deftest sync-args-use-graph-maintenance-profile-by-default
  (is (= ["sync" "project.edn" "--repo" "app" "--check"]
         (watch/sync-args {:config-path "project.edn"
                           :repo-id "app"})))
  (is (= ["sync" "project.edn" "--repo" "app" "--check"
          "--map" "agraph.map.json" "--query-index"]
         (watch/sync-args {:config-path "project.edn"
                           :repo-id "app"
                           :map-path "agraph.map.json"
                           :query-index? true}))))

(deftest refresh-runs-agraph-sync-command
  (let [calls (atom [])]
    (with-redefs [shell/sh (fn [& argv]
                             (swap! calls conj (vec argv))
                             {:exit 0 :out "ok" :err ""})]
      (is (= {:exit 0 :out "ok" :err ""}
             (watch/refresh! {:agraph-bin "bin/agraph"
                              :config-path "project.edn"
                              :repo-id "app"
                              :map-path "agraph.map.json"})))
      (is (= [["bin/agraph" "sync" "project.edn" "--repo" "app" "--check"
               "--map" "agraph.map.json"]]
             @calls)))))

