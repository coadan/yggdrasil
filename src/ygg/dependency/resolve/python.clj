(ns ygg.dependency.resolve.python
  "Python package import resolution."
  (:require [ygg.dependency.resolve.common :as common]))

(defn resolve-import
  [{:keys [packages-by-source edge target]}]
  (let [packages (common/ancestor-source-packages packages-by-source
                                                  (:path edge)
                                                  :pypi)]
    (common/package-by-import-name packages target)))
