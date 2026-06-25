(ns ygg.native
  "Native-image root for the long-lived Yggdrasil server."
  (:require [ygg.cli]
            [ygg.cli-project]
            [ygg.cli-query]
            [ygg.cli-start]
            [ygg.cli-sync]
            [ygg.mcp]
            [ygg.server :as server])
  (:gen-class))

(defn -main
  [& args]
  (apply server/-main args))
