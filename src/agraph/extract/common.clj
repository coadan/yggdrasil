(ns agraph.extract.common
  "Shared mechanical row helpers for extractor implementation namespaces."
  (:require [agraph.hash :as hash]
            [agraph.text :as text]
            [clojure.string :as str]))

(def ^:private source-definition-chunk-lines 120)

(defn node-id
  "Return stable node id for kind/name."
  ([kind value] (node-id nil kind value))
  ([id-scope kind value]
   (str (when (seq id-scope) (str id-scope ":"))
        "node:" (name kind) ":" value)))

(defn generic-node
  [run-id id-scope file-id path kind label source-line]
  {:xt/id (node-id id-scope kind label)
   :label label
   :kind kind
   :file-id file-id
   :path path
   :name label
   :source-line (or source-line 1)
   :tokens (text/tokenize label)
   :active? true
   :run-id run-id})

(defn edge-id
  "Return stable edge id."
  [source-id target-id relation _path _source-line]
  (str "edge:" (hash/short-hash [source-id relation target-id])))

(defn edge-row
  [run-id file-id path source-id target-id relation confidence source-line]
  {:xt/id (edge-id source-id target-id relation path source-line)
   :source-id source-id
   :target-id target-id
   :relation relation
   :confidence confidence
   :file-id file-id
   :path path
   :source-line (or source-line 1)
   :active? true
   :run-id run-id})

(defn chunk-id
  "Return stable chunk id."
  ([path label source-line] (chunk-id nil path label source-line))
  ([id-scope path label source-line]
   (str "chunk:" (hash/short-hash [id-scope path label source-line]))))

(defn extract-text-source
  "Extract a supported text source file as one searchable chunk."
  [run-id {:keys [id-scope file-id path content kind]} chunk-kind]
  {:nodes []
   :edges []
   :chunks [{:xt/id (chunk-id id-scope path path 1)
             :file-id file-id
             :path path
             :kind chunk-kind
             :file-kind kind
             :label path
             :text content
             :tokens (text/tokenize content)
             :source-line 1
             :active? true
             :run-id run-id}]
   :diagnostics []})

(defn bounded-lines
  [text line-limit]
  (str/join "\n" (take line-limit (str/split-lines (or text "")))))

(defn line-start-offsets
  [content]
  (loop [idx 0
         starts [0]]
    (if-let [newline-idx (str/index-of content "\n" idx)]
      (recur (inc newline-idx) (conj starts (inc newline-idx)))
      starts)))

(defn balanced-curly-block
  [^String content ^long start]
  (let [content (or content "")
        length (count content)
        open-idx (str/index-of content "{" start)]
    (when open-idx
      (loop [idx open-idx
             depth 0
             in-string? false
             string-delim nil
             escaped? false]
        (when (< idx length)
          (let [ch (.charAt content (int idx))
                escaped-next? (and in-string?
                                   (not escaped?)
                                   (= \\ ch)
                                   (not= \` string-delim))
                closing-string? (and in-string?
                                     (not escaped?)
                                     (not escaped-next?)
                                     (= ch string-delim))
                opening-string? (and (not in-string?)
                                     (or (= \" ch) (= \' ch) (= \` ch)))
                in-string-next? (cond
                                  closing-string? false
                                  escaped-next? true
                                  opening-string? true
                                  :else in-string?)
                string-delim-next (cond
                                    closing-string? nil
                                    opening-string? ch
                                    :else string-delim)
                depth-next (cond
                             in-string-next? depth
                             (= \{ ch) (inc depth)
                             (= \} ch) (dec depth)
                             :else depth)]
            (if (and (not in-string-next?) (zero? depth-next) (pos? depth))
              (subs content start (inc idx))
              (recur (inc idx)
                     depth-next
                     in-string-next?
                     string-delim-next
                     escaped-next?))))))))

(defn source-definition-chunk
  [run-id id-scope file-id path label definition-kind source-line text]
  (let [chunk-text (bounded-lines text source-definition-chunk-lines)
        line-count (count (str/split-lines chunk-text))]
    (cond-> {:xt/id (chunk-id id-scope path label source-line)
             :file-id file-id
             :path path
             :kind :code-definition
             :definition-kind definition-kind
             :label label
             :text chunk-text
             :tokens (text/tokenize (str label "\n" chunk-text))
             :source-line (or source-line 1)
             :active? true
             :run-id run-id}
      (pos? line-count) (assoc :end-line (+ (or source-line 1) line-count -1))
      (seq chunk-text) (assoc :content-sha (hash/sha256-hex chunk-text)))))
