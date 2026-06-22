(ns ygg.dependency.resolve.go
  "Go package import resolution."
  (:require [ygg.dependency.resolve.common :as common]
            [clojure.string :as str]))

(defn- package-name
  [packages target]
  (->> packages
       (filter #(or (= (:package-name %) target)
                    (str/starts-with? target (str (:package-name %) "/"))))
       (sort-by (comp - count :package-name))
       first
       :package-name))

(defn resolve-import
  [{:keys [packages-by-source manifest-paths edge target]}]
  (when-let [manifest-path (common/nearest-manifest manifest-paths (:path edge) "go.mod")]
    (let [packages (common/packages-for packages-by-source manifest-path :go)]
      (common/package-result (common/package-by-name packages
                                                     (package-name packages target))
                             :declared))))
