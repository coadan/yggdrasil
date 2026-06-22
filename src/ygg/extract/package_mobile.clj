(ns ygg.extract.package-mobile
  (:require [ygg.extract.common :as common]
            [charred.api :as json]
            [clojure.string :as str]))

(defn android-manifest-package
  [content]
  (some-> (re-find #"(?is)<manifest\b[^>]*>" content)
          (common/xml-attr-value "package")))
(defn android-permissions
  [content]
  (->> (re-seq #"(?is)<uses-permission\b[^>]*(?:/>|>.*?</uses-permission>)"
               content)
       (keep (fn [element]
               (when-let [permission (common/xml-attr-value element "android:name")]
                 {:kind :android-permission
                  :label permission
                  :source-line 1
                  :relation :uses})))
       distinct
       vec))
(defn android-components
  [content]
  (->> (re-seq #"(?is)<(activity|service|receiver|provider)\b[^>]*(?:/>|>.*?</\1>)"
               content)
       (keep (fn [[element tag]]
               (when-let [component-name (common/xml-attr-value element "android:name")]
                 {:kind :android-component
                  :label (str tag ":" component-name)
                  :source-line 1
                  :relation :defines})))
       distinct
       vec))
(defn plist-string-value
  [content key-name]
  (some-> (re-find (re-pattern (str "(?is)<key>\\s*"
                                    (java.util.regex.Pattern/quote key-name)
                                    "\\s*</key>\\s*<string>(.*?)</string>"))
                   content)
          second
          str/trim))
(defn plist-facts
  [content]
  (->> [{:kind :mobile-bundle
         :label (plist-string-value content "CFBundleIdentifier")
         :source-line 1
         :relation :defines}
        {:kind :mobile-display-name
         :label (or (plist-string-value content "CFBundleDisplayName")
                    (plist-string-value content "CFBundleName"))
         :source-line 1
         :relation :defines}]
       (filterv (comp seq :label))))
(defn plist-key-facts
  [content kind]
  (->> (re-seq #"(?is)<key>\s*([^<]+?)\s*</key>\s*(?:<string>\s*([^<]+?)\s*</string>|<(true|false)\s*/>|<array>\s*(.*?)\s*</array>)"
               content)
       (map-indexed
        (fn [idx [_ key string-value bool-value array-value]]
          (let [array-items (->> (or array-value "")
                                 (re-seq #"(?is)<string>\s*([^<]+?)\s*</string>")
                                 (map second)
                                 (map str/trim)
                                 (remove str/blank?))
                value (or (some-> string-value str/trim)
                          bool-value
                          (when (seq array-items)
                            (str/join "," array-items))
                          "present")]
            {:kind kind
             :label (str (str/trim key) "=" value)
             :source-line (inc idx)
             :relation :defines})))
       distinct
       vec))
(defn podfile-facts
  [content]
  (let [lines (str/split-lines content)
        targets (->> lines
                     (map-indexed vector)
                     (keep (fn [[idx line]]
                             (when-let [[_ target-name]
                                        (re-matches #"^\s*target\s+['\"]([^'\"]+)['\"]\s+do\s*$"
                                                    line)]
                               {:kind :ios-target
                                :label target-name
                                :source-line (inc idx)
                                :relation :defines})))
                     vec)
        pods (->> lines
                  (map-indexed vector)
                  (keep (fn [[idx line]]
                          (when-let [[_ pod-name]
                                     (re-matches #"^\s*pod\s+['\"]([^'\"]+)['\"].*"
                                                 line)]
                            (common/package-fact {:ecosystem :cocoapods
                                                  :package-name pod-name
                                                  :source-line (inc idx)}))))
                  vec)]
    (vec (concat targets pods))))
(defn swift-package-name
  [content]
  (some-> (re-find #"(?s)Package\s*\(\s*name:\s*\"([^\"]+)\"" content)
          second))
(defn swift-package-facts
  [content]
  (let [lines (str/split-lines content)
        package-deps (->> lines
                          (map-indexed vector)
                          (keep (fn [[idx line]]
                                  (when-let [[_ url]
                                             (re-find #"\.package\s*\(\s*url:\s*\"([^\"]+)\""
                                                      line)]
                                    (common/package-fact {:ecosystem :swiftpm
                                                          :package-name url
                                                          :source-line (inc idx)}))))
                          vec)
        targets (->> lines
                     (map-indexed vector)
                     (keep (fn [[idx line]]
                             (when-let [[_ target-name]
                                        (re-find #"\.(?:target|testTarget|executableTarget)\s*\(\s*name:\s*\"([^\"]+)\""
                                                 line)]
                               {:kind :swift-target
                                :label target-name
                                :source-line (inc idx)
                                :relation :defines})))
                     vec)]
    (vec (concat package-deps targets))))
(defn xcode-project-facts
  [content]
  (let [lines (str/split-lines content)
        products (->> lines
                      (map-indexed vector)
                      (keep (fn [[idx line]]
                              (when-let [[_ product-name]
                                         (re-matches #"^\s*productName\s*=\s*([^;]+);\s*$"
                                                     line)]
                                {:kind :xcode-product
                                 :label (str/replace product-name #"^\"|\"$" "")
                                 :source-line (inc idx)
                                 :relation :defines})))
                      distinct
                      vec)
        package-urls (->> lines
                          (map-indexed vector)
                          (keep (fn [[idx line]]
                                  (when-let [[_ url]
                                             (re-matches #"^\s*repositoryURL\s*=\s*\"([^\"]+)\";\s*$"
                                                         line)]
                                    (common/package-fact {:ecosystem :swiftpm
                                                          :package-name url
                                                          :source-line (inc idx)}))))
                          distinct
                          vec)]
    (vec (concat products package-urls))))
(defn- json-string-at
  [m path]
  (let [value (get-in m path)]
    (when (string? value)
      value)))
(defn expo-json-facts
  [content]
  (try
    (let [parsed (json/read-json content :key-fn keyword)
          expo (or (:expo parsed) parsed)
          android-package (json-string-at expo [:android :package])
          ios-bundle (json-string-at expo [:ios :bundleIdentifier])
          plugins (:plugins expo)
          plugin-labels (->> plugins
                             (keep (fn [plugin]
                                     (cond
                                       (string? plugin) plugin
                                       (vector? plugin) (first plugin)
                                       :else nil)))
                             (filter string?)
                             distinct)]
      (vec (concat
            (when android-package
              [{:kind :android-package
                :label android-package
                :source-line 1
                :relation :defines}])
            (when ios-bundle
              [{:kind :mobile-bundle
                :label ios-bundle
                :source-line 1
                :relation :defines}])
            (map (fn [plugin]
                   {:kind :expo-plugin
                    :label plugin
                    :source-line 1
                    :relation :uses})
                 plugin-labels))))
    (catch Exception _
      [])))
(defn expo-project-label
  [content path]
  (try
    (let [parsed (json/read-json content :key-fn keyword)
          expo (or (:expo parsed) parsed)]
      (or (json-string-at expo [:name])
          (json-string-at expo [:slug])
          path))
    (catch Exception _
      path)))
(defn- js-config-string-value
  [content key-name]
  (or (some-> (re-find (re-pattern (str "(?m)\\b" key-name "\\s*:\\s*['\"]([^'\"]+)['\"]"))
                       content)
              second)
      (some-> (re-find (re-pattern (str "(?m)\"" key-name "\"\\s*:\\s*\"([^\"]+)\""))
                       content)
              second)))
(defn- json-or-js-string-at
  [content path key-name]
  (or (some-> (common/read-json-map content)
              (json-string-at path))
      (js-config-string-value content key-name)))
(defn- object-key-facts
  [m kind relation source-line]
  (when (map? m)
    (mapv (fn [[k _]]
            {:kind kind
             :label (common/json-key-label k)
             :source-line source-line
             :relation relation})
          m)))
(defn- capacitor-plugin-facts
  [content]
  (if-let [plugins (:plugins (common/read-json-map content))]
    (object-key-facts plugins :capacitor-plugin :uses 1)
    (loop [remaining (map-indexed vector (str/split-lines content))
           in-plugins? false
           depth 0
           out []]
      (if-let [[idx line] (first remaining)]
        (let [starts? (and (not in-plugins?)
                           (re-find #"\bplugins\s*:\s*\{" line))
              depth-before (if starts? 1 depth)
              plugin (when (and (or in-plugins? starts?)
                                (= 1 depth-before))
                       (some-> (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_-]*)\s*:\s*\{?.*$" line)
                               second))
              opens (count (re-seq #"\{" line))
              closes (count (re-seq #"\}" line))
              depth* (cond
                       starts? (+ opens (- closes))
                       in-plugins? (+ depth opens (- closes))
                       :else depth)
              in-plugins* (or (and starts? (pos? depth*))
                              (and in-plugins? (pos? depth*)))]
          (recur (rest remaining)
                 in-plugins*
                 depth*
                 (cond-> out
                   (and plugin (not= "plugins" plugin))
                   (conj {:kind :capacitor-plugin
                          :label plugin
                          :source-line (inc idx)
                          :relation :uses}))))
        (->> out distinct vec)))))
(defn capacitor-config-facts
  [content]
  (let [app-id (json-or-js-string-at content [:appId] "appId")
        app-name (json-or-js-string-at content [:appName] "appName")
        web-dir (json-or-js-string-at content [:webDir] "webDir")
        server-url (json-or-js-string-at content [:server :url] "url")]
    (vec (concat
          (when app-id
            [{:kind :mobile-bundle
              :label app-id
              :source-line 1
              :relation :defines}])
          (when app-name
            [{:kind :mobile-display-name
              :label app-name
              :source-line 1
              :relation :defines}])
          (when web-dir
            [{:kind :mobile-web-dir
              :label web-dir
              :source-line 1
              :relation :references}])
          (when server-url
            [{:kind :mobile-entry-url
              :label server-url
              :source-line 1
              :relation :references}])
          (capacitor-plugin-facts content)))))
(defn capacitor-project-label
  [content path]
  (or (json-or-js-string-at content [:appName] "appName")
      (json-or-js-string-at content [:appId] "appId")
      path))
(defn- tauri-config-value
  [content paths key-name]
  (or (some (fn [path]
              (some-> (common/read-json-map content)
                      (json-string-at path)))
            paths)
      (js-config-string-value content key-name)))
(defn- tauri-plugin-facts
  [content]
  (let [parsed (common/read-json-map content)
        plugins (:plugins parsed)]
    (object-key-facts plugins :tauri-plugin :uses 1)))
(defn- tauri-window-facts
  [content]
  (let [parsed (common/read-json-map content)
        windows (or (get-in parsed [:app :windows])
                    (get-in parsed [:tauri :windows]))]
    (when (vector? windows)
      (->> windows
           (keep (fn [window]
                   (when (map? window)
                     (let [label (or (:label window) (:title window))]
                       (when label
                         {:kind :tauri-window
                          :label (if-let [title (:title window)]
                                   (str label ":" title)
                                   label)
                          :source-line 1
                          :relation :defines})))))
           vec))))
(defn tauri-config-facts
  [content]
  (let [identifier (tauri-config-value content
                                       [[:identifier] [:tauri :bundle :identifier]]
                                       "identifier")
        product-name (tauri-config-value content
                                         [[:productName] [:package :productName]]
                                         "productName")
        frontend-dist (tauri-config-value content
                                          [[:build :frontendDist] [:build :distDir]]
                                          "frontendDist")
        dev-url (tauri-config-value content
                                    [[:build :devUrl] [:build :devPath]]
                                    "devUrl")
        before-dev-command (tauri-config-value content
                                               [[:build :beforeDevCommand]]
                                               "beforeDevCommand")]
    (vec (concat
          (when identifier
            [{:kind :mobile-bundle
              :label identifier
              :source-line 1
              :relation :defines}])
          (when product-name
            [{:kind :mobile-display-name
              :label product-name
              :source-line 1
              :relation :defines}])
          (when frontend-dist
            [{:kind :mobile-web-dir
              :label frontend-dist
              :source-line 1
              :relation :references}])
          (when dev-url
            [{:kind :mobile-entry-url
              :label dev-url
              :source-line 1
              :relation :references}])
          (when before-dev-command
            [{:kind :task-command
              :label before-dev-command
              :source-line 1
              :relation :uses}])
          (tauri-window-facts content)
          (tauri-plugin-facts content)))))
(defn tauri-project-label
  [content path]
  (or (tauri-config-value content [[:productName] [:package :productName]] "productName")
      (tauri-config-value content [[:identifier] [:tauri :bundle :identifier]] "identifier")
      path))
