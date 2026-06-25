(ns user
  (:require [ygg.dev.server :as server]))

(defn start []
  (server/start!))

(defn stop []
  (server/stop!))

(defn restart []
  (server/restart!))

(defn reload []
  (server/reload!))

(defn start-watch []
  (server/start-watch!))

(defn stop-watch []
  (server/stop-watch!))

(defn status []
  (server/status))
