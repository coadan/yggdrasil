(ns agraph.extract.starlark
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(defn- starlark-facts
  [content]
  (->> (str/split-lines content)
       (map-indexed vector)
       (mapcat (fn [[idx line]]
                 (let [source-line (inc idx)]
                   (cond
                     (re-matches #"^\s*load\(\s*\"([^\"]+)\".*\)\s*$" line)
                     (let [[_ target] (re-matches #"^\s*load\(\s*\"([^\"]+)\".*\)\s*$" line)
                           symbols (->> (re-seq #"\"([^\"]+)\"" line)
                                        (map second)
                                        rest)]
                       (concat [{:kind :starlark-load
                                 :label target
                                 :source-line source-line
                                 :relation :references}]
                               (map (fn [symbol]
                                      {:kind :starlark-symbol
                                       :label (str target ":" symbol)
                                       :source-line source-line
                                       :relation :references})
                                    symbols)))

                     (re-matches #"^\s*def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(.*" line)
                     (let [[_ name] (re-matches #"^\s*def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(.*" line)]
                       [{:kind :starlark-function
                         :label name
                         :source-line source-line
                         :relation :defines}])

                     (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*rule\s*\(.*" line)
                     (let [[_ name] (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*rule\s*\(.*" line)]
                       [{:kind :starlark-rule
                         :label name
                         :source-line source-line
                         :relation :defines}])

                     :else []))))
       distinct
       vec))
(defn extract-starlark
  "Extract bounded Starlark load, function, and rule facts."
  [run-id file]
  (common/extract-format-facts run-id
                        file
                        :starlark-file
                        :starlark-file
                        (starlark-facts (:content file))))
