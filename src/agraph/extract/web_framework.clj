(ns agraph.extract.web-framework
  (:require [agraph.extract.common :as common]
            [agraph.extract.docs-config :as extract.docs-config]
            [clojure.string :as str]))

(defn- web-framework-config-kind
  [filename]
  (cond
    (re-matches #"next\.config\.(?:js|cjs|mjs|mts|cts|ts)" filename) "next"
    (re-matches #"remix\.config\.(?:js|cjs|mjs|mts|cts|ts)" filename) "remix"
    (re-matches #"vite\.config\.(?:js|cjs|mjs|mts|cts|ts)" filename) "vite"
    (re-matches #"svelte\.config\.(?:js|cjs|mjs|mts|cts|ts)" filename) "sveltekit"
    (re-matches #"nuxt\.config\.(?:js|cjs|mjs|mts|cts|ts)" filename) "nuxt"
    (re-matches #"astro\.config\.(?:js|cjs|mjs|mts|cts|ts)" filename) "astro"
    (= "angular.json" filename) "angular"
    :else nil))
(defn- web-config-import-facts
  [path content]
  (let [imports (->> (str/split-lines content)
                     (map-indexed #(common/js-import-targets %1 path %2))
                     (mapcat identity)
                     distinct
                     vec)]
    (vec
     (concat
      (map (fn [{:keys [target source-line]}]
             {:kind :web-framework-import
              :label target
              :source-line source-line
              :relation :imports})
           imports)
      (map (fn [{:keys [target source-line]}]
             {:kind :web-framework-plugin
              :label target
              :source-line source-line
              :relation :uses})
           (filter #(common/package-reference? (:target %)) imports))))))
(defn- web-config-string-array-facts
  [content property-name kind relation]
  (mapv (fn [value]
          {:kind kind
           :label value
           :source-line 1
           :relation relation})
        (extract.docs-config/docs-config-array-property-values content property-name)))
(defn- angular-project-facts
  [content]
  (if-let [m (common/read-json-map content)]
    (let [projects (:projects m)]
      (if (map? projects)
        (->> projects
             (mapcat
              (fn [[project-name project]]
                (let [label (common/json-key-label project-name)
                      architect (when (map? project) (:architect project))]
                  (concat
                   [{:kind :web-framework-project
                     :label label
                     :source-line 1
                     :relation :defines}]
                   (when (string? (:root project))
                     [{:kind :web-framework-root
                       :label (str label ":" (:root project))
                       :source-line 1
                       :relation :references}])
                   (when (string? (:sourceRoot project))
                     [{:kind :web-framework-source-root
                       :label (str label ":" (:sourceRoot project))
                       :source-line 1
                       :relation :references}])
                   (when (map? architect)
                     (mapcat (fn [[target-name target]]
                               (when (and (map? target) (string? (:builder target)))
                                 [{:kind :web-framework-builder
                                   :label (str label ":" (common/json-key-label target-name) ":" (:builder target))
                                   :source-line 1
                                   :relation :uses}]))
                             architect))))))
             distinct
             vec)
        []))
    []))
(defn- web-config-facts
  [{:keys [path content]}]
  (let [filename (common/manifest-name path)
        framework (web-framework-config-kind filename)]
    (vec
     (concat
      (when framework
        [{:kind :web-framework
          :label framework
          :source-line 1
          :relation :defines}])
      (if (= "angular" framework)
        (angular-project-facts content)
        (web-config-import-facts path content))
      (case framework
        "next"
        (map (fn [value]
               {:kind :web-framework-route
                :label value
                :source-line 1
                :relation :references})
             (distinct (concat (extract.docs-config/docs-config-property-values content "basePath")
                               (extract.docs-config/docs-config-property-values content "assetPrefix"))))

        "vite"
        (map (fn [value]
               {:kind :web-framework-route
                :label value
                :source-line 1
                :relation :references})
             (extract.docs-config/docs-config-property-values content "base"))

        "sveltekit"
        (map (fn [value]
               {:kind :web-framework-adapter
                :label value
                :source-line 1
                :relation :uses})
             (extract.docs-config/docs-config-property-values content "adapter"))

        "nuxt"
        (web-config-string-array-facts content "modules" :web-framework-module :uses)

        "astro"
        (vec (concat
              (web-config-string-array-facts content "integrations" :web-framework-plugin :uses)
              (map (fn [value]
                     {:kind :web-framework-route
                      :label value
                      :source-line 1
                      :relation :references})
                   (extract.docs-config/docs-config-property-values content "base"))))

        [])))))
(defn- strip-route-extension
  [value]
  (str/replace value #"\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts|svelte|vue|astro)$" ""))
(defn- route-segment-label
  [segment]
  (cond
    (= "index" segment) nil
    (or (str/starts-with? segment "(")
        (str/starts-with? segment "@")) nil
    (re-matches #"\[\[\.\.\..+\]\]" segment)
    (str "{..." (subs segment 5 (- (count segment) 2)) "}")
    (re-matches #"\[\.\.\..+\]" segment)
    (str "{..." (subs segment 4 (dec (count segment))) "}")
    (re-matches #"\[.+\]" segment)
    (str "{" (subs segment 1 (dec (count segment))) "}")
    :else segment))
(defn- route-path-from-segments
  [segments]
  (let [segments (->> segments
                      (map route-segment-label)
                      (remove str/blank?)
                      vec)]
    (if (seq segments)
      (str "/" (str/join "/" segments))
      "/")))
(defn- remix-route-segment-label
  [segment]
  (cond
    (or (= "_index" segment)
        (str/blank? segment)) nil
    (= "$" segment) "{...splat}"
    (str/starts-with? segment "$") (str "{" (subs segment 1) "}")
    (str/starts-with? segment "_") nil
    (str/ends-with? segment "_") (subs segment 0 (dec (count segment)))
    :else segment))
(defn- remix-route-path
  [route-part]
  (let [segments (->> (str/split route-part #"\.")
                      (map remix-route-segment-label)
                      (remove str/blank?)
                      vec)]
    (if (seq segments)
      (str "/" (str/join "/" segments))
      "/")))
(defn angular-router-source?
  [content]
  (and (re-find #"(?m)^\s*import\s+\{?[^;\n]*\bRoutes\b[^;\n]*\}?\s+from\s+['\"]@angular/router['\"]" content)
       (or (re-find #"(?m)\bRoutes\s*=" content)
           (re-find #"(?m)\bprovideRouter\s*\(" content))
       (re-find #"(?m)\bpath\s*:\s*['\"]" content)))
(defn- angular-route-label
  [path-value]
  (let [path-value (str/replace (str/trim (str path-value)) #"^/+" "")]
    (cond
      (str/blank? path-value) "/"
      (= "**" path-value) "/{...wildcard}"
      :else (str "/"
                 (->> (str/split path-value #"/")
                      (map (fn [segment]
                             (if (str/starts-with? segment ":")
                               (str "{" (subs segment 1) "}")
                               segment)))
                      (str/join "/"))))))
(defn- char-count
  [s ch]
  (count (filter #(= ch %) s)))
(defn- angular-route-blocks
  [content]
  (let [lines (vec (str/split-lines content))]
    (loop [remaining (map-indexed vector lines)
           current nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [starts-route? (and (nil? current)
                                 (re-find #"\bpath\s*:\s*['\"]" line))
              current* (cond
                         starts-route?
                         {:source-line (inc idx)
                          :depth (let [delta (- (char-count line \{)
                                                (char-count line \}))]
                                   (if (and (zero? delta)
                                            (not (str/includes? line "}")))
                                     1
                                     delta))
                          :lines [line]}

                         current
                         (-> current
                             (update :depth + (- (char-count line \{)
                                                 (char-count line \})))
                             (update :lines conj line))

                         :else nil)]
          (if (and current* (<= (:depth current*) 0))
            (recur (rest remaining) nil (conj out current*))
            (recur (rest remaining) current* out)))
        (cond-> out current (conj current))))))
(defn- angular-route-facts
  [content path]
  (when (angular-router-source? content)
    (vec
     (concat
      [{:kind :web-framework
        :label "angular"
        :source-line 1
        :relation :defines}]
      (mapcat
       (fn [{:keys [source-line lines]}]
         (let [block (str/join "\n" lines)
               route-label (some->> (re-find #"\bpath\s*:\s*['\"]([^'\"]*)['\"]" block)
                                    second
                                    angular-route-label)
               component (some->> (re-find #"\bcomponent\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)" block)
                                  second)
               redirect (some->> (re-find #"\bredirectTo\s*:\s*['\"]([^'\"]+)['\"]" block)
                                 second)
               imports (->> lines
                            (map-indexed #(common/js-import-targets (+ source-line %1 -1) path %2))
                            (mapcat identity)
                            distinct)]
           (when route-label
             (concat
              [{:kind :web-framework-route
                :label route-label
                :source-line source-line
                :relation :defines}]
              (when component
                [{:kind :web-framework-component
                  :label (str route-label ":" component)
                  :source-line source-line
                  :relation :references}])
              (when redirect
                [{:kind :web-framework-route-redirect
                  :label (str route-label ":" (angular-route-label redirect))
                  :source-line source-line
                  :relation :references}])
              (map (fn [{:keys [target source-line]}]
                     {:kind :web-framework-import
                      :label target
                      :source-line source-line
                      :relation :imports})
                   imports)))))
       (angular-route-blocks content))))))
(defn ember-router-source?
  [content]
  (and (re-find #"(?m)^\s*import\s+.+\s+from\s+['\"]@ember/routing/router['\"]" content)
       (re-find #"(?m)\bRouter\.map\s*\(" content)
       (re-find #"(?m)\bthis\.route\s*\(" content)))
(defn ember-config-source?
  [content]
  (and (re-find #"(?m)\bmodulePrefix\s*:" content)
       (re-find #"(?m)\brootURL\s*:" content)
       (re-find #"(?m)\blocationType\s*:" content)))
(defn- ember-route-facts
  [content path]
  (when (ember-router-source? content)
    (let [lines (str/split-lines content)]
      (vec
       (concat
        [{:kind :web-framework
          :label "ember"
          :source-line 1
          :relation :defines}]
        (->> lines
             (map-indexed vector)
             (keep
              (fn [[idx line]]
                (when-let [[_ route-name path-option]
                           (re-find #"\bthis\.route\s*\(\s*['\"]([^'\"]+)['\"](?:\s*,\s*\{[^}]*\bpath\s*:\s*['\"]([^'\"]+)['\"])?"
                                    line)]
                  {:kind :web-framework-route
                   :label (angular-route-label (or path-option route-name))
                   :source-line (inc idx)
                   :relation :defines})))
             distinct)
        (->> lines
             (map-indexed #(common/js-import-targets %1 path %2))
             (mapcat identity)
             (map (fn [{:keys [target source-line]}]
                    {:kind :web-framework-import
                     :label target
                     :source-line source-line
                     :relation :imports}))
             distinct))))))
(defn- ember-config-facts
  [content]
  (when (ember-config-source? content)
    (vec
     (concat
      [{:kind :web-framework
        :label "ember"
        :source-line 1
        :relation :defines}]
      (map (fn [value]
             {:kind :web-framework-project
              :label value
              :source-line 1
              :relation :defines})
           (extract.docs-config/docs-config-property-values content "modulePrefix"))
      (map (fn [value]
             {:kind :web-framework-route
              :label value
              :source-line 1
              :relation :references})
           (extract.docs-config/docs-config-property-values content "rootURL"))
      (map (fn [value]
             {:kind :web-framework-setting
              :label (str "locationType:" value)
              :source-line 1
              :relation :defines})
           (extract.docs-config/docs-config-property-values content "locationType"))))))
(defn web-route-info
  [path]
  (let [path-lower (str/replace (str/lower-case (str path)) "\\" "/")]
    (cond
      (re-find #"(?:^|/)app/(?:.+/)?(?:page|layout|route)\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$"
               path-lower)
      (let [[_ route-part file-role] (re-find #"(?:^|/)app/(.*)/(page|layout|route)\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$"
                                              path-lower)
            [_ file-role-root] (re-find #"(?:^|/)app/(page|layout|route)\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$"
                                        path-lower)
            file-role (or file-role file-role-root)
            route-part (or route-part "")
            segments (if (seq route-part) (str/split route-part #"/") [])]
        {:framework "next"
         :route (route-path-from-segments segments)
         :role file-role})

      (re-find #"(?:^|/)pages/.+\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$" path-lower)
      (let [[_ route-part] (re-find #"(?:^|/)pages/(.+)\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$"
                                    path-lower)]
        {:framework "next"
         :route (route-path-from-segments (str/split (strip-route-extension route-part) #"/"))
         :role "page"})

      (re-find #"(?:^|/)src/routes/(?:.+/)?\+(?:page|layout|server)\.svelte$" path-lower)
      (let [[_ route-part file-role] (re-find #"(?:^|/)src/routes/(.*)/\+(page|layout|server)\.svelte$"
                                              path-lower)
            [_ file-role-root] (re-find #"(?:^|/)src/routes/\+(page|layout|server)\.svelte$"
                                        path-lower)
            file-role (or file-role file-role-root)
            route-part (or route-part "")
            segments (if (seq route-part) (str/split route-part #"/") [])]
        {:framework "sveltekit"
         :route (route-path-from-segments segments)
         :role file-role})

      (re-find #"(?:^|/)pages/.+\.vue$" path-lower)
      (let [[_ route-part] (re-find #"(?:^|/)pages/(.+)\.vue$" path-lower)]
        {:framework "nuxt"
         :route (route-path-from-segments (str/split (strip-route-extension route-part) #"/"))
         :role "page"})

      (re-find #"(?:^|/)src/pages/.+\.astro$" path-lower)
      (let [[_ route-part] (re-find #"(?:^|/)src/pages/(.+)\.astro$" path-lower)]
        {:framework "astro"
         :route (route-path-from-segments (str/split (strip-route-extension route-part) #"/"))
         :role "page"})

      (re-find #"(?:^|/)app/routes/.+\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$" path-lower)
      (let [[_ route-part] (re-find #"(?:^|/)app/routes/(.+)\.(?:js|jsx|ts|tsx|mjs|cjs|mts|cts)$"
                                    path-lower)]
        {:framework "remix"
         :route (remix-route-path (strip-route-extension route-part))
         :role "route-module"})

      :else nil)))
(defn- web-route-facts
  [{:keys [path content]}]
  (if-let [{:keys [framework route role]} (web-route-info path)]
    (vec
     (concat
      [{:kind :web-framework
        :label framework
        :source-line 1
        :relation :defines}
       {:kind :web-framework-route
        :label route
        :source-line 1
        :relation :defines}
       {:kind (case role
                "layout" :web-framework-layout
                "route" :web-framework-route-handler
                "server" :web-framework-route-handler
                "route-module" :web-framework-page
                :web-framework-page)
        :label (str route ":" role)
        :source-line 1
        :relation :defines}]
      (when (= "remix" framework)
        (->> (str/split-lines content)
             (map-indexed vector)
             (mapcat
              (fn [[idx line]]
                (cond
                  (re-find #"^\s*export\s+(?:async\s+)?function\s+loader\b|^\s*export\s+const\s+loader\b"
                           line)
                  [{:kind :web-framework-loader
                    :label (str route ":loader")
                    :source-line (inc idx)
                    :relation :defines}]

                  (re-find #"^\s*export\s+(?:async\s+)?function\s+action\b|^\s*export\s+const\s+action\b"
                           line)
                  [{:kind :web-framework-action
                    :label (str route ":action")
                    :source-line (inc idx)
                    :relation :defines}]

                  :else [])))))
      (map (fn [{:keys [target source-line]}]
             {:kind :web-framework-import
              :label target
              :source-line source-line
              :relation :imports})
           (->> (str/split-lines content)
                (map-indexed #(common/js-import-targets %1 path %2))
                (mapcat identity)
                distinct))))
    []))
(defn web-framework-facts
  [{:keys [path content] :as file}]
  (cond
    (web-route-info path) (web-route-facts file)
    (angular-router-source? content) (angular-route-facts content path)
    (ember-router-source? content) (ember-route-facts content path)
    (ember-config-source? content) (ember-config-facts content)
    :else (web-config-facts file)))
