(ns ygg.dependency.resolve
  "Dispatch package import resolution to language-specific mechanical resolvers."
  (:require [ygg.dependency.resolve.common :as common]
            [ygg.dependency.resolve.dotnet :as dotnet]
            [ygg.dependency.resolve.go :as go]
            [ygg.dependency.resolve.java :as java]
            [ygg.dependency.resolve.javascript :as javascript]
            [ygg.dependency.resolve.python :as python]
            [ygg.dependency.resolve.rust :as rust]))

(def ^:private directly-resolvable-import-ecosystems
  #{:npm :cargo :go :pypi :maven :nuget :deno :dotnet-assembly})

(defn source-kind
  [files-by-path path]
  (:kind (get files-by-path path)))

(defn mapping-entries
  [map-overlay]
  (->> (concat (:packageImports map-overlay) (:package-imports map-overlay))
       (filter #(not= "rejected" (str (:status %))))
       vec))

(defn- directly-resolvable-package?
  [package]
  (contains? directly-resolvable-import-ecosystems (:ecosystem package)))

(defn can-resolve-import-packages?
  [packages-by-source map-overlay]
  (or (seq (mapping-entries map-overlay))
      (some directly-resolvable-package?
            (mapcat identity (vals packages-by-source)))))

(defn- mapping-package
  [packages-by-id mapping]
  (let [ecosystem (some-> (:ecosystem mapping) keyword)
        package-name (or (:package mapping) (:package-name mapping))]
    (some (fn [package]
            (when (and (= ecosystem (:ecosystem package))
                       (= package-name (:package-name package)))
              package))
          (vals packages-by-id))))

(defn resolve-map-import
  [packages-by-id map-overlay repo-id target]
  (->> (mapping-entries map-overlay)
       (filter #(or (nil? (:repo %))
                    (= repo-id (:repo %))))
       (filter #(common/import-prefix-match? target (:import %)))
       (sort-by (comp - count :import))
       (keep (fn [mapping]
               (when-let [package (mapping-package packages-by-id mapping)]
                 {:package package
                  :import-name (:import mapping)
                  :resolution-source :map-overlay})))
       first))

(defn resolve-import
  [{:keys [files-by-path packages-by-id map-overlay repo-id edge] :as context}]
  (let [target (common/namespace-target (:target-id edge))
        kind (source-kind files-by-path (:path edge))
        context (assoc context :target target :kind kind)]
    (or (resolve-map-import packages-by-id map-overlay repo-id target)
        (cond
          (javascript/source-kind? kind)
          (javascript/resolve-import context)

          (= :rust kind)
          (rust/resolve-import context)

          (= :go kind)
          (go/resolve-import context)

          (= :python kind)
          (python/resolve-import context)

          (= :java kind)
          (java/resolve-import context)

          (= :dotnet kind)
          (dotnet/resolve-import context)

          :else nil))))
