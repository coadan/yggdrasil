(ns agraph.extract.governance
  (:require [agraph.extract.common :as common]
            [clojure.string :as str]))

(defn- license-document-facts
  [content]
  (let [lines (str/split-lines content)
        first-title (->> lines
                         (map-indexed vector)
                         (keep (fn [[idx line]]
                                 (let [trimmed (str/trim line)]
                                   (when (and (seq trimmed)
                                              (not (str/starts-with? trimmed "SPDX-"))
                                              (re-find #"(?i)\blicen[sc]e\b" trimmed))
                                     {:kind :license-title
                                      :label trimmed
                                      :source-line (inc idx)
                                      :relation :defines}))))
                         first)]
    (vec
     (concat
      (when first-title [first-title])
      (->> lines
           (map-indexed vector)
           (mapcat
            (fn [[idx line]]
              (let [source-line (inc idx)]
                (concat
                 (when-let [[_ id]
                            (re-matches #"(?i)^\s*SPDX-License-Identifier:\s*(.+?)\s*$"
                                        line)]
                   [{:kind :license-id
                     :label (str/trim id)
                     :source-line source-line
                     :relation :defines}])
                 (when-let [[_ text]
                            (or (re-matches #"(?i)^\s*SPDX-FileCopyrightText:\s*(.+?)\s*$"
                                            line)
                                (re-matches #"(?i)^\s*(copyright\s+.+?)\s*$"
                                            line))]
                   [{:kind :copyright-notice
                     :label (str/trim text)
                     :source-line source-line
                     :relation :defines}])))))
           distinct)))))
(defn- governance-facts
  [{:keys [path content]}]
  (let [path-lower (str/lower-case path)
        file-kind (cond
                    (str/includes? path-lower ".github/issue_template/")
                    "issue-template"
                    (str/includes? path-lower "pull_request_template")
                    "pull-request-template"
                    (str/ends-with? path-lower "funding.yml")
                    "funding"
                    (str/ends-with? path-lower "funding.yaml")
                    "funding"
                    (str/ends-with? path-lower "security.md")
                    "security"
                    (str/ends-with? path-lower "contributing.md")
                    "contributing"
                    (contains? #{"license" "copying"} (common/manifest-name path))
                    "license"
                    (= "notice" (common/manifest-name path))
                    "notice"
                    :else "governance")
        line-facts (->> (str/split-lines content)
                        (map-indexed vector)
                        (mapcat
                         (fn [[idx line]]
                           (let [source-line (inc idx)]
                             (concat
                              (when-let [[_ heading]
                                         (re-matches #"^\s{0,3}#{1,6}\s+(.+?)\s*$" line)]
                                [{:kind :governance-section
                                  :label heading
                                  :source-line source-line
                                  :relation :defines}])
                              (when-let [{:keys [key value]} (common/yaml-key-line idx line)]
                                (when (seq value)
                                  (if (= "funding" file-kind)
                                    [{:kind :funding-platform
                                      :label (str key "=" (common/strip-yaml-scalar value))
                                      :source-line source-line
                                      :relation :defines}]
                                    [{:kind :governance-field
                                      :label (str key "=" (common/strip-yaml-scalar value))
                                      :source-line source-line
                                      :relation :defines}])))
                              (when-let [[_ item]
                                         (re-matches #"^\s*-\s+\[[ xX]\]\s+(.+?)\s*$" line)]
                                [{:kind :governance-check
                                  :label item
                                  :source-line source-line
                                  :relation :defines}])))))
                        distinct)]
    (vec
     (concat
      [{:kind :governance-file
        :label file-kind
        :source-line 1
        :relation :defines}]
      line-facts
      (when (contains? #{"license" "notice"} file-kind)
        (license-document-facts content))))))
(defn extract-governance
  "Extract bounded repository governance and GitHub template facts."
  [run-id file]
  (common/extract-format-facts run-id
                        file
                        :governance-config-file
                        :governance-config-file
                        (governance-facts file)))
