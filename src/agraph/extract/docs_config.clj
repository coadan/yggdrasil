(ns agraph.extract.docs-config
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(defn- storybook-quoted-values
  [content]
  (mapv second (re-seq #"['\"]([^'\"]+)['\"]" content)))
(defn- storybook-array-values
  [content key-name]
  (if-let [[_ body] (re-find (re-pattern (str "(?s)" key-name "\\s*:\\s*\\[(.*?)\\]"))
                             content)]
    (storybook-quoted-values body)
    []))
(defn- storybook-main-facts
  [content]
  (let [story-patterns (storybook-array-values content "stories")
        addons (storybook-array-values content "addons")
        framework (or (some->> (re-find #"framework\s*:\s*['\"]([^'\"]+)['\"]"
                                        content)
                               second)
                      (some->> (re-find #"framework\s*:\s*\{[^}]*name\s*:\s*['\"]([^'\"]+)['\"]"
                                        content)
                               second))]
    (vec (concat
          (map (fn [pattern]
                 {:kind :storybook-story-pattern
                  :label pattern
                  :source-line 1
                  :relation :references})
               story-patterns)
          (map (fn [addon]
                 {:kind :storybook-addon
                  :label addon
                  :source-line 1
                  :relation :references})
               addons)
          (when framework
            [{:kind :storybook-framework
              :label framework
              :source-line 1
              :relation :uses}])))))
(defn- storybook-import-facts
  [path content]
  (->> (str/split-lines content)
       (map-indexed #(common/js-import-targets %1 path %2))
       (mapcat identity)
       (map (fn [{:keys [target source-line]}]
              {:kind :storybook-import
               :label target
               :source-line source-line
               :relation :references}))
       distinct
       vec))
(defn- storybook-story-facts
  [path content]
  (let [title (some->> (re-find #"title\s*:\s*['\"]([^'\"]+)['\"]" content)
                       second)
        component (some->> (re-find #"component\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)"
                                    content)
                           second)
        tags (storybook-array-values content "tags")
        stories (mapv second
                      (re-seq #"(?m)^\s*export\s+const\s+([A-Za-z_$][A-Za-z0-9_$]*)\b"
                              content))]
    (vec (concat
          (storybook-import-facts path content)
          (when title
            [{:kind :storybook-title
              :label title
              :source-line 1
              :relation :defines}])
          (when component
            [{:kind :storybook-component
              :label component
              :source-line 1
              :relation :references}])
          (map (fn [tag]
                 {:kind :storybook-tag
                  :label tag
                  :source-line 1
                  :relation :defines})
               tags)
          (map (fn [story]
                 {:kind :storybook-story
                  :label story
                  :source-line 1
                  :relation :defines})
               stories)))))
(defn- storybook-facts
  [{:keys [path content]}]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")
        filename (common/manifest-name path)]
    (cond
      (re-find #"(^|/)\.storybook/(?:main|preview|manager)\.(?:js|cjs|mjs|ts|tsx)$"
               path-lower)
      (storybook-main-facts content)

      (re-matches #".+\.stories\.(?:js|jsx|ts|tsx|mdx)$" filename)
      (storybook-story-facts path content)

      :else [])))
(defn extract-storybook
  "Extract bounded Storybook config and story facts."
  [run-id file]
  (common/extract-format-facts run-id
                        file
                        :storybook-file
                        :storybook-file
                        (storybook-facts file)))
(defn docs-config-property-values
  [content property-name]
  (->> (re-seq (re-pattern (str "\\b"
                                (java.util.regex.Pattern/quote property-name)
                                "\\s*:\\s*['\"]([^'\"]+)['\"]"))
               content)
       (map second)
       (remove str/blank?)
       distinct
       vec))
(defn- docs-config-title-facts
  [content]
  (mapv (fn [value]
          {:kind :docs-title
           :label value
           :source-line 1
           :relation :defines})
        (docs-config-property-values content "title")))
(defn- docs-config-route-facts
  [content]
  (->> (concat (docs-config-property-values content "to")
               (docs-config-property-values content "href")
               (docs-config-property-values content "baseUrl")
               (docs-config-property-values content "id"))
       distinct
       (mapv (fn [value]
               {:kind :docs-route
                :label value
                :source-line 1
                :relation :references}))))
(defn- docs-config-reference-facts
  [content]
  (mapv (fn [value]
          {:kind :docs-config-reference
           :label value
           :source-line 1
           :relation :references})
        (docs-config-property-values content "sidebarPath")))
(defn- docs-config-plugin-facts
  [content]
  (->> (concat (docs-config-property-values content "preset")
               (docs-config-property-values content "plugin")
               (docs-config-property-values content "name"))
       distinct
       (mapv (fn [value]
               {:kind :docs-plugin
                :label value
                :source-line 1
                :relation :uses}))))
(defn docs-config-array-property-values
  [content property-name]
  (->> (re-seq (re-pattern (str "\\b"
                                (java.util.regex.Pattern/quote property-name)
                                "\\s*:\\s*\\[(.*?)\\]"))
               content)
       (mapcat (fn [[_ body]] (storybook-quoted-values body)))
       (remove str/blank?)
       distinct
       vec))
(defn- content-config-import-facts
  [path content]
  (->> (str/split-lines content)
       (map-indexed #(common/js-import-targets %1 path %2))
       (mapcat identity)
       (mapv (fn [{:keys [target source-line]}]
               {:kind :content-import
                :label target
                :source-line source-line
                :relation :imports}))
       distinct
       vec))
(defn- content-config-export-body
  [content]
  (some->> (re-find #"(?s)export\s+const\s+collections\s*=\s*\{(.*?)\}\s*;?"
                    content)
           second))
(defn- content-config-export-collections
  [content]
  (if-let [body (content-config-export-body content)]
    (let [segments (->> (str/split body #",")
                        (map str/trim)
                        (remove str/blank?))]
      (->> segments
           (keep (fn [segment]
                   (let [segment (str/replace segment #"\s+" " ")]
                     (cond
                       (re-find #":" segment)
                       (some->> (re-find #"^['\"]?([A-Za-z0-9_-]+)['\"]?\s*:"
                                         segment)
                                second)

                       (re-matches (re-pattern (common/js-identifier)) segment)
                       segment

                       :else nil))))
           distinct
           vec))
    []))
(defn- content-config-define-collection-forms
  [content]
  (let [line-starts (common/line-start-offsets content)]
    (->> (re-seq (re-pattern (str "\\b(?:const|let|var)\\s+("
                                  (common/js-identifier)
                                  ")\\s*=\\s*defineCollection\\s*\\("))
                 content)
         (keep (fn [[match collection-name]]
                 (when-let [match-idx (str/index-of content match)]
                   (let [start (+ match-idx (str/index-of match "defineCollection"))
                         line (count (take-while #(<= % match-idx)
                                                 line-starts))]
                     {:collection collection-name
                      :source-line line
                      :form (or (common/balanced-form content start) "")}))))
         vec)))
(defn- content-config-loader-facts
  [{:keys [collection source-line form]}]
  (let [loader (some->> (re-find #"\bloader\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*\("
                                 form)
                        second)
        direct-loader-source (some->> (re-find #"\bloader\s*:\s*[A-Za-z_$][A-Za-z0-9_$]*\s*\(\s*['\"]([^'\"]+)['\"]"
                                               form)
                                      second)]
    (vec
     (concat
      (when loader
        [{:kind :content-loader
          :label (str collection ":" loader)
          :source-line source-line
          :relation :uses}])
      (map (fn [value]
             {:kind :content-source
              :label value
              :source-line source-line
              :relation :references})
           (distinct
            (concat (docs-config-property-values form "base")
                    (docs-config-property-values form "pattern")
                    (when direct-loader-source [direct-loader-source]))))))))
(defn- content-config-schema-field-facts
  [{:keys [collection source-line form]}]
  (if-let [body (some->> (re-find #"(?s)\bschema\s*:\s*z\.object\s*\(\s*\{(.*?)\}\s*\)"
                                  form)
                         second)]
    (->> (re-seq #"(?m)^\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*:" body)
         (map second)
         distinct
         (mapv (fn [field-name]
                 {:kind :content-schema-field
                  :label (str collection "." field-name)
                  :source-line source-line
                  :relation :defines})))
    []))
(defn- astro-content-config-facts
  [{:keys [path content]}]
  (let [forms (content-config-define-collection-forms content)
        declared-collections (set (map :collection forms))
        exported-collections (content-config-export-collections content)
        collection-labels (->> (concat declared-collections exported-collections)
                               distinct
                               sort)]
    (vec
     (concat
      (content-config-import-facts path content)
      (map (fn [collection]
             {:kind :content-collection
              :label collection
              :source-line 1
              :relation :defines})
           collection-labels)
      (mapcat content-config-loader-facts forms)
      (mapcat content-config-schema-field-facts forms)))))
(defn- vitepress-config-path?
  [path]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")]
    (boolean
     (or (re-find #"(^|/)\.vitepress/config\.(?:js|mjs|mts|cts|ts)$" path-lower)
         (re-find #"(^|/)\.vitepress/config/index\.(?:js|mjs|mts|cts|ts)$"
                  path-lower)))))
(defn- vitepress-config-import-facts
  [path content]
  (->> (str/split-lines content)
       (map-indexed #(common/js-import-targets %1 path %2))
       (mapcat identity)
       (mapv (fn [{:keys [target source-line]}]
               {:kind :docs-config-import
                :label target
                :source-line source-line
                :relation :imports}))
       distinct
       vec))
(defn- vitepress-config-facts
  [{:keys [path content]}]
  (vec
   (concat
    (vitepress-config-import-facts path content)
    (docs-config-title-facts content)
    (map (fn [value]
           {:kind :docs-nav-entry
            :label value
            :source-line 1
            :relation :defines})
         (docs-config-property-values content "text"))
    (map (fn [value]
           {:kind :docs-route
            :label value
            :source-line 1
            :relation :references})
         (distinct (concat (docs-config-property-values content "base")
                           (docs-config-property-values content "link"))))
    (map (fn [value]
           {:kind :docs-search-provider
            :label value
            :source-line 1
            :relation :uses})
         (docs-config-property-values content "provider")))))
(defn- python-config-property-values
  [content property-name]
  (->> (re-seq (re-pattern (str "(?m)^\\s*"
                                (java.util.regex.Pattern/quote property-name)
                                "\\s*=\\s*['\"]([^'\"]+)['\"]"))
               content)
       (map second)
       (remove str/blank?)
       distinct
       vec))
(defn- python-config-array-values
  [content property-name]
  (if-let [[_ body] (re-find (re-pattern (str "(?ms)^\\s*"
                                              (java.util.regex.Pattern/quote property-name)
                                              "\\s*=\\s*\\[(.*?)\\]"))
                             content)]
    (->> (storybook-quoted-values body)
         (remove str/blank?)
         distinct
         vec)
    []))
(defn- sphinx-config-facts
  [content]
  (vec
   (concat
    (map (fn [value]
           {:kind :docs-title
            :label value
            :source-line 1
            :relation :defines})
         (python-config-property-values content "project"))
    (map (fn [value]
           {:kind :docs-extension
            :label value
            :source-line 1
            :relation :uses})
         (python-config-array-values content "extensions"))
    (map (fn [value]
           {:kind :docs-theme
            :label value
            :source-line 1
            :relation :uses})
         (python-config-property-values content "html_theme"))
    (map (fn [value]
           {:kind :docs-route
            :label value
            :source-line 1
            :relation :references})
         (distinct (concat (python-config-property-values content "root_doc")
                           (python-config-property-values content "master_doc"))))
    (map (fn [value]
           {:kind :docs-config-reference
            :label value
            :source-line 1
            :relation :references})
         (distinct (concat (python-config-array-values content "templates_path")
                           (python-config-array-values content "html_static_path")))))))
(defn- nextra-next-config-facts
  [path content]
  (let [imports (vitepress-config-import-facts path content)
        uses-nextra? (or (some #(= "nextra" (:label %)) imports)
                         (re-find #"(?m)\bnextra\s*\(" content))]
    (vec
     (concat
      imports
      (when uses-nextra?
        [{:kind :docs-plugin
          :label "nextra"
          :source-line 1
          :relation :uses}])
      (map (fn [value]
             {:kind :docs-route
              :label value
              :source-line 1
              :relation :references})
           (docs-config-property-values content "contentDirBasePath"))
      (map (fn [value]
             {:kind :docs-locale
              :label value
              :source-line 1
              :relation :defines})
           (storybook-array-values content "locales"))
      (map (fn [value]
             {:kind :docs-locale-default
              :label value
              :source-line 1
              :relation :defines})
           (docs-config-property-values content "defaultLocale"))))))
(defn- nextra-meta-object-entry-facts
  [key body source-line]
  (vec
   (concat
    [{:kind :docs-meta-entry
      :label key
      :source-line source-line
      :relation :defines}]
    (map (fn [value]
           {:kind :docs-sidebar-entry
            :label value
            :source-line source-line
            :relation :defines})
         (docs-config-property-values body "title"))
    (map (fn [value]
           {:kind :docs-route
            :label value
            :source-line source-line
            :relation :references})
         (docs-config-property-values body "href"))
    (map (fn [value]
           {:kind :docs-meta-type
            :label (str key ":" value)
            :source-line source-line
            :relation :uses})
         (docs-config-property-values body "type"))
    (map (fn [value]
           {:kind :docs-meta-display
            :label (str key ":" value)
            :source-line source-line
            :relation :uses})
         (docs-config-property-values body "display")))))
(defn- nextra-meta-facts
  [content]
  (let [scalar-entries (->> (re-seq #"(?m)^\s*['\"]?([A-Za-z0-9_-]+)['\"]?\s*:\s*['\"]([^'\"]+)['\"]"
                                    content)
                            (remove (fn [[_ key _]]
                                      (contains? #{"display" "href" "theme" "title" "type"}
                                                 key)))
                            (mapcat (fn [[_ key title]]
                                      [{:kind :docs-meta-entry
                                        :label key
                                        :source-line 1
                                        :relation :defines}
                                       {:kind :docs-sidebar-entry
                                        :label title
                                        :source-line 1
                                        :relation :defines}]))
                            vec)
        object-entries (->> (re-seq #"(?ms)^\s*['\"]?([A-Za-z0-9_-]+)['\"]?\s*:\s*\{(.*?)^\s*\},?"
                                    content)
                            (mapcat (fn [[_ key body]]
                                      (nextra-meta-object-entry-facts key body 1)))
                            vec)]
    (vec (distinct (concat scalar-entries object-entries)))))
(defn- docs-sidebar-facts
  [content]
  (vec
   (concat
    (map (fn [value]
           {:kind :docs-sidebar-entry
            :label value
            :source-line 1
            :relation :defines})
         (docs-config-property-values content "label"))
    (map (fn [value]
           {:kind :docs-route
            :label value
            :source-line 1
            :relation :references})
         (docs-config-property-values content "id"))
    (map (fn [value]
           {:kind :docs-route
            :label value
            :source-line 1
            :relation :references})
         (docs-config-array-property-values content "items")))))
(defn- mkdocs-line-facts
  [content]
  (loop [remaining (map-indexed vector (str/split-lines content))
         section nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)
            section* (cond
                       (and entry (zero? (:indent entry)) (str/blank? (:value entry)))
                       (:key entry)

                       (and entry (zero? (:indent entry)))
                       nil

                       :else section)
            site-name (when (and entry
                                 (zero? (:indent entry))
                                 (= "site_name" (:key entry))
                                 (seq (:value entry)))
                        {:kind :docs-title
                         :label (common/strip-yaml-scalar (:value entry))
                         :source-line (:source-line entry)
                         :relation :defines})
            nav-entry (when (and (= "nav" section)
                                 (re-matches #"^\s*-\s+[^:]+:.*$" line))
                        (let [[_ label route]
                              (re-matches #"^\s*-\s+([^:]+):\s*(.*?)\s*$" line)]
                          [{:kind :docs-nav-entry
                            :label (common/strip-yaml-scalar label)
                            :source-line (inc idx)
                            :relation :defines}
                           (when (seq route)
                             {:kind :docs-route
                              :label (common/strip-yaml-scalar route)
                              :source-line (inc idx)
                              :relation :references})]))
            plugin-entry (when (and (= "plugins" section)
                                    (re-matches #"^\s*-\s+.+$" line))
                           {:kind :docs-plugin
                            :label (-> line
                                       (str/replace #"^\s*-\s+" "")
                                       common/strip-yaml-scalar)
                            :source-line (inc idx)
                            :relation :uses})
            theme-entry (when (and (= "theme" section)
                                   entry
                                   (= "name" (:key entry))
                                   (seq (:value entry)))
                          {:kind :docs-theme
                           :label (common/strip-yaml-scalar (:value entry))
                           :source-line (:source-line entry)
                           :relation :uses})]
        (recur (rest remaining)
               section*
               (cond-> out
                 site-name (conj site-name)
                 nav-entry (into (remove nil? nav-entry))
                 plugin-entry (conj plugin-entry)
                 theme-entry (conj theme-entry))))
      (vec (distinct out)))))
(def ^:private nextra-next-config-filenames
  #{"next.config.cjs" "next.config.js" "next.config.mjs"
    "next.config.mts" "next.config.cts" "next.config.ts"})
(def ^:private nextra-meta-filenames
  #{"_meta.js" "_meta.jsx" "_meta.mjs" "_meta.mts" "_meta.cts"
    "_meta.ts" "_meta.tsx"})
(def ^:private js-docs-config-filenames
  #{"config.js" "config.mjs" "config.mts" "config.cts" "config.ts"
    "index.js" "index.mjs" "index.mts" "index.cts" "index.ts"})
(def ^:private astro-content-config-filenames
  #{"content.config.js" "content.config.mjs" "content.config.mts"
    "content.config.cts" "content.config.ts"})
(def ^:private docusaurus-config-filenames
  #{"docusaurus.config.js" "docusaurus.config.cjs" "docusaurus.config.mjs"
    "docusaurus.config.mts" "docusaurus.config.cts" "docusaurus.config.ts"})
(def ^:private sidebar-config-filenames
  #{"sidebars.js" "sidebars.mjs" "sidebars.mts" "sidebars.cts"
    "sidebars.ts"})
(defn- astro-content-config-path?
  [path]
  (boolean
   (re-find #"(^|/)src/content/config\.(?:js|mjs|mts|cts|ts)$"
            (str/replace (str/lower-case (str path)) "\\" "/"))))
(defn- docs-config-facts
  [{:keys [path content]}]
  (let [filename (common/manifest-name path)]
    (cond
      (contains? nextra-next-config-filenames filename)
      (nextra-next-config-facts path content)

      (contains? nextra-meta-filenames filename)
      (nextra-meta-facts content)

      (= "conf.py" filename)
      (sphinx-config-facts content)

      (contains? js-docs-config-filenames filename)
      (cond
        (vitepress-config-path? path)
        (vitepress-config-facts {:path path :content content})

        (astro-content-config-path? path)
        (astro-content-config-facts {:path path :content content})

        :else [])

      (contains? astro-content-config-filenames filename)
      (astro-content-config-facts {:path path :content content})

      (contains? docusaurus-config-filenames filename)
      (vec (concat (docs-config-title-facts content)
                   (docs-config-route-facts content)
                   (docs-config-reference-facts content)
                   (docs-config-plugin-facts content)))

      (contains? sidebar-config-filenames filename)
      (docs-sidebar-facts content)

      (contains? #{"mkdocs.yml" "mkdocs.yaml"} filename)
      (mkdocs-line-facts content)

      :else [])))
(defn extract-docs-config
  "Extract deterministic docs/content-system configuration facts."
  [run-id file]
  (common/extract-format-facts run-id
                        file
                        :docs-config
                        :docs-config-file
                        (docs-config-facts file)))
