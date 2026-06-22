(ns ygg.extract.web-framework-entry
  (:require [ygg.extract.common :as common]
            [ygg.extract.source-js :as source-js]
            [ygg.extract.web-framework :as web-framework]
            [ygg.extract.web-source :as web-source]
            [ygg.fs :as fs]))

(defn- web-framework-base-kind
  [path]
  (case (fs/extension path)
    (".ts" ".tsx" ".mts" ".cts") :typescript
    (".js" ".jsx" ".mjs" ".cjs") :javascript
    ".svelte" :svelte
    ".astro" :astro
    ".vue" :vue
    nil))
(defn- web-framework-base-result
  [run-id {:keys [path content] :as file}]
  (when (or (web-framework/web-route-info path)
            (web-framework/angular-router-source? content)
            (web-framework/ember-router-source? content)
            (web-framework/ember-config-source? content))
    (case (web-framework-base-kind path)
      :typescript (source-js/extract-js-family run-id (assoc file :kind :typescript))
      :javascript (source-js/extract-js-family run-id (assoc file :kind :javascript))
      :svelte (web-source/extract-sfc run-id (assoc file :kind :svelte))
      :astro (web-source/extract-astro run-id (assoc file :kind :astro))
      :vue (web-source/extract-sfc run-id (assoc file :kind :vue))
      nil)))
(defn extract-web-framework
  "Extract deterministic web framework config and file-backed route facts."
  [run-id file]
  (let [web-result (common/extract-format-facts run-id
                                                file
                                                :web-framework-file
                                                :web-framework-file
                                                (web-framework/web-framework-facts file))
        base-result (web-framework-base-result run-id file)]
    (if base-result
      {:nodes (vec (distinct (concat (:nodes base-result) (:nodes web-result))))
       :edges (vec (distinct (concat (:edges base-result) (:edges web-result))))
       :chunks (vec (distinct (concat (:chunks base-result) (:chunks web-result))))
       :diagnostics (vec (concat (:diagnostics base-result)
                                 (:diagnostics web-result)))}
      web-result)))
