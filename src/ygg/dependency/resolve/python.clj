(ns ygg.dependency.resolve.python
  "Python package import resolution."
  (:require [ygg.dependency.resolve.common :as common]
            [clojure.string :as str]))

(defn- dotted-root
  [target]
  (first (str/split (str target) #"\.")))

(defn- private-root-package
  [packages target]
  (let [root (dotted-root target)
        package-name (some-> root
                             (str/replace #"^_+" "")
                             not-empty)]
    (when (and package-name
               (not= root package-name))
      (common/package-result (common/package-by-name packages package-name)
                             :python-private-module-root
                             root))))

(defn resolve-import
  [{:keys [packages-by-source edge target]}]
  (let [packages (common/ancestor-source-packages packages-by-source
                                                  (:path edge)
                                                  :pypi)]
    (or (common/package-by-import-name packages target)
        (private-root-package packages target))))
