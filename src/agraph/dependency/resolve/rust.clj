(ns agraph.dependency.resolve.rust
  "Rust package import resolution."
  (:require [agraph.dependency.resolve.common :as common]
            [clojure.string :as str]))

(defn- import-root
  [target]
  (first (str/split (str target) #"::")))

(defn- package
  [packages target]
  (let [root (import-root target)]
    (or (common/package-by-name packages root)
        (common/package-by-name packages (some-> root (str/replace "_" "-"))))))

(defn resolve-import
  [{:keys [packages-by-source manifest-paths edge target]}]
  (when-let [manifest-path (common/nearest-manifest manifest-paths (:path edge) "Cargo.toml")]
    (let [packages (common/packages-for packages-by-source manifest-path :cargo)]
      (common/package-result (package packages target)
                             :declared))))
