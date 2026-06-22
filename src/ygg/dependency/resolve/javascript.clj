(ns ygg.dependency.resolve.javascript
  "JavaScript-family package import resolution."
  (:require [ygg.dependency.resolve.common :as common]
            [clojure.string :as str]))

(def source-kinds
  #{:javascript :typescript :astro :vue :svelte})

(def ^:private npm-lock-filenames
  ["package-lock.json" "pnpm-lock.yaml" "yarn.lock" "bun.lock"])

(defn source-kind?
  [kind]
  (contains? source-kinds kind))

(defn- package-name
  [target]
  (when-not (str/starts-with? (str target) ".")
    (let [parts (str/split (str target) #"/")]
      (if (str/starts-with? (str target) "@")
        (when (<= 2 (count parts))
          (str (first parts) "/" (second parts)))
        (first parts)))))

(defn- types-package-name
  [package-name]
  (when (and (seq package-name)
             (not (str/starts-with? package-name "@")))
    (str "@types/" package-name)))

(defn- package-by-ancestor-manifest
  [packages-by-source manifest-paths ecosystem package-name]
  (some (fn [manifest-path]
          (common/package-by-name
           (common/packages-for packages-by-source manifest-path ecosystem)
           package-name))
        manifest-paths))

(defn- package-by-ancestor-npm-lock
  [packages-by-source manifest-paths source-path package-name]
  (some (fn [filename]
          (package-by-ancestor-manifest
           packages-by-source
           (common/ancestor-manifest-paths manifest-paths source-path filename)
           :npm
           package-name))
        npm-lock-filenames))

(defn- type-import?
  [edge]
  (= :type (:import-kind edge)))

(defn resolve-import
  [{:keys [packages-by-source manifest-paths edge target]}]
  (let [source-path (:path edge)
        package-name (package-name target)
        types-package-name (types-package-name package-name)]
    (or (common/package-result
         (package-by-ancestor-manifest
          packages-by-source
          (common/ancestor-manifest-paths manifest-paths source-path "deno.json")
          :deno
          package-name)
         :deno-import-map)
        (common/package-result
         (package-by-ancestor-manifest
          packages-by-source
          (common/ancestor-manifest-paths manifest-paths source-path "package.json")
          :npm
          package-name)
         :declared)
        (common/package-result
         (package-by-ancestor-npm-lock
          packages-by-source
          manifest-paths
          source-path
          package-name)
         :dependency-lock)
        (when (type-import? edge)
          (or (common/package-result
               (package-by-ancestor-manifest
                packages-by-source
                (common/ancestor-manifest-paths manifest-paths source-path "package.json")
                :npm
                types-package-name)
               :type-declaration
               package-name)
              (common/package-result
               (package-by-ancestor-npm-lock
                packages-by-source
                manifest-paths
                source-path
                types-package-name)
               :type-dependency-lock
               package-name))))))
