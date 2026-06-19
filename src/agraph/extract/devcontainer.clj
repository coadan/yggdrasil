(ns agraph.extract.devcontainer
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(def devcontainer-command-keys
  [:initializeCommand :onCreateCommand :updateContentCommand
   :postCreateCommand :postStartCommand :postAttachCommand])
(defn- devcontainer-command-label
  [command]
  (cond
    (string? command) command
    (vector? command) (str/join " " (map str command))
    (map? command) (str/join " && " (map (fn [[k v]]
                                           (str (common/json-key-label k) "="
                                                (common/json-label v)))
                                         command))
    :else (str command)))
(defn- devcontainer-facts
  [content]
  (let [m (common/read-json-map content)
        features (:features m)
        run-services (:runServices m)
        forward-ports (:forwardPorts m)]
    (vec (concat
          (remove nil?
                  [(when-let [image (:image m)]
                     {:kind :container-image
                      :label (common/json-label image)
                      :source-line 1
                      :relation :uses})
                   (when-let [dockerfile (get-in m [:build :dockerfile])]
                     {:kind :build-reference
                      :label (common/json-label dockerfile)
                      :source-line 1
                      :relation :references})])
          (when (map? features)
            (map (fn [[feature _]]
                   {:kind :devcontainer-feature
                    :label (common/json-key-label feature)
                    :source-line 1
                    :relation :uses})
                 features))
          (when (vector? run-services)
            (map (fn [service]
                   {:kind :devcontainer-service
                    :label (common/json-label service)
                    :source-line 1
                    :relation :uses})
                 run-services))
          (when (vector? forward-ports)
            (map (fn [port]
                   {:kind :devcontainer-port
                    :label (common/json-label port)
                    :source-line 1
                    :relation :references})
                 forward-ports))
          (keep (fn [k]
                  (when-let [command (get m k)]
                    {:kind :devcontainer-command
                     :label (str (name k) "="
                                 (devcontainer-command-label command))
                     :source-line 1
                     :relation :defines}))
                devcontainer-command-keys)))))
(defn extract-devcontainer
  "Extract bounded Dev Container configuration facts."
  [run-id file]
  (common/extract-format-facts run-id
                        file
                        :devcontainer-file
                        :devcontainer-file
                        (devcontainer-facts (:content file))))
