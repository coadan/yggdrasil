(ns ygg.extract.source-js
  (:require [ygg.extract.common :as common]
            [ygg.hash :as hash]
            [ygg.text :as text]
            [clojure.string :as str]))

(def ^:private js-definition-chunk-lines 80)
(def ^:private typescript-declaration-member-before-lines 32)
(def ^:private typescript-declaration-member-after-lines 8)
(def ^:private typescript-declaration-member-limit 300)
(defn- js-definition-chunk
  [run-id id-scope file-id path lines {:keys [label kind source-line]}]
  (let [chunk-text (->> lines
                        (drop (dec (or source-line 1)))
                        (take js-definition-chunk-lines)
                        (str/join "\n"))
        line-count (count (str/split-lines chunk-text))]
    (cond-> {:xt/id (common/chunk-id id-scope path label (or source-line 1))
             :file-id file-id
             :path path
             :kind :code-definition
             :definition-kind kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line (or source-line 1)
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ (or source-line 1) line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))
(defn- typescript-declaration-path?
  [path]
  (boolean (re-find #"\.d\.(?:ts|mts|cts)$" (str path))))
(defn- typescript-declaration-member-line
  [idx line]
  (let [identifier (common/js-identifier)]
    (when-let [[_ name marker]
               (re-matches
                (re-pattern
                 (str "^\\s*(?:" "readonly" "\\s+)?("
                      identifier
                      ")\\??\\s*(:|\\(|<).*$"))
                line)]
      (let [line (str/trim line)]
        (when-not (or (str/starts-with? line "export ")
                      (str/starts-with? line "import ")
                      (str/starts-with? line "interface ")
                      (str/starts-with? line "type "))
          {:kind (if (and (= ":" marker)
                          (not (str/includes? line "=>"))
                          (not (str/includes? line "(")))
                   :property
                   :method)
           :name name
           :source-line (inc idx)})))))
(defn- nearest-jsdoc-start
  [lines start-idx member-idx]
  (or (->> (range member-idx (dec start-idx) -1)
           (some (fn [idx]
                   (when (re-find #"^\s*/\*\*" (nth lines idx))
                     idx))))
      start-idx))
(defn- typescript-declaration-member-chunk
  [run-id id-scope file-id path module-name lines {:keys [kind name source-line]}]
  (let [member-idx (max 0 (dec (or source-line 1)))
        start-idx (nearest-jsdoc-start
                   lines
                   (max 0 (- member-idx typescript-declaration-member-before-lines))
                   member-idx)
        end-idx (min (count lines)
                     (+ member-idx typescript-declaration-member-after-lines 1))
        chunk-text (->> lines
                        (drop start-idx)
                        (take (- end-idx start-idx))
                        (str/join "\n"))
        line-count (count (str/split-lines chunk-text))
        label (str module-name "/" name)]
    (cond-> {:xt/id (common/chunk-id id-scope path label (or source-line 1))
             :file-id file-id
             :path path
             :kind :code-definition
             :definition-kind kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line (inc start-idx)
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ (inc start-idx) line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))
(defn extract-js-family
  "Extract bounded module facts from JavaScript and TypeScript source files."
  [run-id {:keys [id-scope file-id path content kind]}]
  (let [module-name (common/source-module-name path)
        ns-node (common/namespace-node run-id id-scope file-id path module-name)
        lines (str/split-lines content)
        defs (->> lines
                  (map-indexed common/js-definition-line)
                  (keep identity)
                  (mapv (fn [{:keys [kind name public? source-line]}]
                          (let [label (str module-name "/" name)]
                            {:xt/id (common/node-id id-scope :symbol label)
                             :label label
                             :kind kind
                             :file-id file-id
                             :path path
                             :namespace module-name
                             :name name
                             :public? public?
                             :source-line source-line
                             :tokens (text/tokenize label)
                             :active? true
                             :run-id run-id}))))
        define-edges (mapv #(common/edge-row run-id file-id path
                                             (:xt/id ns-node)
                                             (:xt/id %)
                                             :defines
                                             :extracted
                                             (:source-line %))
                           defs)
        import-edges (->> lines
                          (map-indexed #(common/js-import-targets %1 path %2))
                          (mapcat identity)
                          (mapv #(cond-> (common/edge-row
                                          run-id
                                          file-id
                                          path
                                          (:xt/id ns-node)
                                          (common/node-id id-scope :namespace (:target %))
                                          :imports
                                          :extracted
                                          (:source-line %))
                                   (:import-kind %)
                                   (assoc :import-kind (:import-kind %)))))
        chunk-text (str/join "\n" (take 100 lines))
        chunk-kind (case kind
                     :typescript :typescript-file
                     :javascript-file)
        chunk {:xt/id (common/chunk-id id-scope path module-name 1)
               :file-id file-id
               :path path
               :kind chunk-kind
               :label module-name
               :text chunk-text
               :tokens (text/tokenize (str module-name "\n" chunk-text))
               :source-line 1
               :active? true
               :run-id run-id}
        definition-chunks (mapv #(js-definition-chunk run-id
                                                      id-scope
                                                      file-id
                                                      path
                                                      lines
                                                      %)
                                defs)
        declaration-member-chunks (if (and (= :typescript kind)
                                           (typescript-declaration-path? path))
                                    (->> lines
                                         (map-indexed typescript-declaration-member-line)
                                         (keep identity)
                                         (take typescript-declaration-member-limit)
                                         (mapv #(typescript-declaration-member-chunk
                                                 run-id
                                                 id-scope
                                                 file-id
                                                 path
                                                 module-name
                                                 lines
                                                 %)))
                                    [])]
    {:nodes (into [ns-node] defs)
     :edges (vec (concat define-edges import-edges))
     :chunks (into [chunk] (concat definition-chunks
                                   declaration-member-chunks))
     :diagnostics []}))
