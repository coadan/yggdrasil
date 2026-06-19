(ns agraph.command
  "Mechanical shell command rendering for agent-facing packets."
  (:require [clojure.string :as str]))

(defn shell-token
  "Render one shell token.

  Angle-bracket placeholders are left readable for docs and packet templates.
  Other tokens are single-quoted when they contain shell-sensitive characters."
  [value]
  (let [value (str value)]
    (if (or (re-matches #"<[^>]+>" value)
            (re-matches #"[A-Za-z0-9_./:=+@%-]+" value))
      value
      (str "'" (str/replace value #"'" "'\"'\"'") "'"))))

(defn command
  "Render a command from already separated shell tokens."
  [& tokens]
  (->> tokens
       (remove nil?)
       (map shell-token)
       (str/join " ")))

(defn option
  "Render an option pair when value is present."
  [flag value]
  (when-not (str/blank? (str value))
    (str flag " " (shell-token value))))
