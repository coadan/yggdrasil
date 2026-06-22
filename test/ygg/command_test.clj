(ns ygg.command-test
  (:require [ygg.command :as command]
            [clojure.test :refer [deftest is]]))

(deftest shell-token-quotes-only-when-needed
  (is (= "project.edn" (command/shell-token "project.edn")))
  (is (= "<project.edn>" (command/shell-token "<project.edn>")))
  (is (= "'Project Files/project.edn'"
         (command/shell-token "Project Files/project.edn")))
  (is (= "'owner'\"'\"'s map.json'"
         (command/shell-token "owner's map.json"))))

(deftest command-renders-separated-shell-tokens
  (is (= "ygg sync 'Project Files/project.edn' --check"
         (command/command "ygg" "sync" "Project Files/project.edn" "--check")))
  (is (= "--project 'demo project'"
         (command/option "--project" "demo project")))
  (is (nil? (command/option "--project" ""))))
