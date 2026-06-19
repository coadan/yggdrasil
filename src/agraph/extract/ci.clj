(ns agraph.extract.ci
  (:require [agraph.extract.common :as common]
            [agraph.hash :as hash]
            [agraph.text :as text]
            [clojure.string :as str]))

(defn- ci-workflow-label
  [path lines]
  (or (some (fn [[idx line]]
              (when-let [{:keys [indent key value]} (common/yaml-key-line idx line)]
                (when (and (zero? indent)
                           (= "name" key)
                           (seq value))
                  (common/strip-yaml-scalar value))))
            (map-indexed vector lines))
      path))
(defn- ci-github-job-blocks
  [lines]
  (let [jobs-start (some (fn [[idx line]]
                           (when (re-matches #"^\s*jobs:\s*$" line)
                             idx))
                         (map-indexed vector lines))]
    (if-not jobs-start
      []
      (loop [remaining (drop (inc jobs-start) (map-indexed vector lines))
             current nil
             out []]
        (if-let [[idx line] (first remaining)]
          (let [blank-or-comment? (or (str/blank? line)
                                      (str/starts-with? (str/trim line) "#"))
                top-level? (and (not blank-or-comment?)
                                (zero? (common/leading-spaces line)))
                job-key (when-let [{:keys [indent key source-line]} (common/yaml-key-line idx line)]
                          (when (= 2 indent)
                            {:label key
                             :source-line source-line
                             :lines []}))]
            (cond
              top-level?
              (cond-> out current (conj current))

              job-key
              (recur (rest remaining)
                     (update job-key :lines conj [idx line])
                     (cond-> out current (conj current)))

              current
              (recur (rest remaining)
                     (update current :lines conj [idx line])
                     out)

              :else
              (recur (rest remaining) nil out)))
          (cond-> out current (conj current)))))))
(def ci-gitlab-reserved-keys
  #{"after_script" "before_script" "cache" "default" "image" "include" "pages"
    "services" "stages" "variables" "workflow"})
(defn- ci-gitlab-job-blocks
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)
            top-key (when (and entry
                               (zero? (:indent entry))
                               (not (contains? ci-gitlab-reserved-keys (:key entry))))
                      {:label (:key entry)
                       :source-line (:source-line entry)
                       :lines []})]
        (cond
          top-key
          (recur (rest remaining)
                 (update top-key :lines conj [idx line])
                 (cond-> out current (conj current)))

          current
          (recur (rest remaining)
                 (update current :lines conj [idx line])
                 out)

          :else
          (recur (rest remaining) nil out)))
      (->> (cond-> out current (conj current))
           (filterv (fn [{:keys [lines]}]
                      (some (fn [[_ line]]
                              (re-find #"^\s*(script|stage|needs):(?:\s|$)" line))
                            lines)))))))
(defn- ci-jenkins-stage-blocks
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (if-let [[_ label] (re-matches #"^\s*stage\s*\(\s*['\"]([^'\"]+)['\"]\s*\).*$"
                                     line)]
        (recur (rest remaining)
               {:kind :ci-stage
                :label label
                :source-line (inc idx)
                :lines [[idx line]]}
               (cond-> out current (conj current)))
        (recur (rest remaining)
               (when current
                 (update current :lines conj [idx line]))
               out))
      (cond-> out current (conj current)))))
(defn- ci-azure-job-blocks
  [lines]
  (loop [remaining (map-indexed vector lines)
         current nil
         out []]
    (if-let [[idx line] (first remaining)]
      (if-let [[_ indent kind label]
               (re-matches #"^(\s*)-\s*(stage|job):\s*(.+?)\s*$" line)]
        (let [job {:kind (case kind
                           "stage" :ci-stage
                           "job" :ci-job)
                   :label (common/strip-yaml-scalar label)
                   :source-line (inc idx)
                   :indent (count indent)
                   :lines [[idx line]]}]
          (recur (rest remaining)
                 job
                 (cond-> out current (conj current))))
        (recur (rest remaining)
               (when current
                 (update current :lines conj [idx line]))
               out))
      (cond-> out current (conj current)))))
(defn- ci-circleci-job-blocks
  [lines]
  (let [jobs-start (some (fn [[idx line]]
                           (when (re-matches #"^jobs:\s*$" line)
                             idx))
                         (map-indexed vector lines))]
    (if-not jobs-start
      []
      (loop [remaining (drop (inc jobs-start) (map-indexed vector lines))
             current nil
             out []]
        (if-let [[idx line] (first remaining)]
          (let [entry (common/yaml-key-line idx line)
                top-level? (and (seq (str/trim line))
                                (zero? (common/leading-spaces line)))
                job-entry (when (and entry (= 2 (:indent entry)))
                            {:label (:key entry)
                             :source-line (:source-line entry)
                             :lines [[idx line]]})]
            (cond
              top-level?
              (cond-> out current (conj current))

              job-entry
              (recur (rest remaining)
                     job-entry
                     (cond-> out current (conj current)))

              current
              (recur (rest remaining)
                     (update current :lines conj [idx line])
                     out)

              :else
              (recur (rest remaining) nil out)))
          (cond-> out current (conj current)))))))
(defn- ci-buildkite-step-label
  [step]
  (or (some (fn [[idx line]]
              (when-let [{:keys [key value]} (common/yaml-key-line idx line)]
                (when (and (= "key" key) (seq value))
                  (common/strip-yaml-scalar value))))
            (:lines step))
      (:label step)
      (str "step-" (:source-line step))))
(defn- ci-buildkite-step-blocks
  [lines]
  (let [steps-start (some (fn [[idx line]]
                            (when (re-matches #"^\s*steps:\s*$" line)
                              idx))
                          (map-indexed vector lines))]
    (if-not steps-start
      []
      (loop [remaining (drop (inc steps-start) (map-indexed vector lines))
             current nil
             out []]
        (if-let [[idx line] (first remaining)]
          (let [top-level? (and (seq (str/trim line))
                                (zero? (common/leading-spaces line)))
                step-start (or
                            (when-let [[_ label]
                                       (re-matches #"^\s*-\s+label:\s+(.+?)\s*$"
                                                   line)]
                              {:label (common/strip-yaml-scalar label)
                               :source-line (inc idx)
                               :lines [[idx line]]})
                            (when-let [[_ command]
                                       (re-matches #"^\s*-\s+command:\s+(.+?)\s*$"
                                                   line)]
                              {:label (str "command:" (common/strip-yaml-scalar command))
                               :source-line (inc idx)
                               :lines [[idx line]]}))]
            (cond
              top-level?
              (cond-> out current (conj (assoc current
                                               :label
                                               (ci-buildkite-step-label current))))

              step-start
              (recur (rest remaining)
                     step-start
                     (cond-> out
                       current (conj (assoc current
                                            :label
                                            (ci-buildkite-step-label current)))))

              current
              (recur (rest remaining)
                     (update current :lines conj [idx line])
                     out)

              :else
              (recur (rest remaining) nil out)))
          (cond-> out
            current (conj (assoc current
                                 :label
                                 (ci-buildkite-step-label current)))))))))
(defn- ci-list-step-label
  [step]
  (or (some (fn [[idx line]]
              (when-let [{:keys [key value]} (common/yaml-key-line idx line)]
                (when (and (= "name" key) (seq value))
                  (common/strip-yaml-scalar value))))
            (:lines step))
      (:label step)
      (str "step-" (:source-line step))))
(defn- ci-list-step-blocks
  [lines]
  (let [steps-start (some (fn [[idx line]]
                            (when (re-matches #"^\s*steps:\s*$" line)
                              idx))
                          (map-indexed vector lines))]
    (if-not steps-start
      []
      (loop [remaining (drop (inc steps-start) (map-indexed vector lines))
             current nil
             out []]
        (if-let [[idx line] (first remaining)]
          (let [top-level? (and (seq (str/trim line))
                                (zero? (common/leading-spaces line)))
                step-start (when-let [[_ label]
                                      (re-matches #"^\s*-\s+name:\s+(.+?)\s*$"
                                                  line)]
                             {:label (common/strip-yaml-scalar label)
                              :source-line (inc idx)
                              :lines [[idx line]]})]
            (cond
              top-level?
              (cond-> out current (conj (assoc current
                                               :label
                                               (ci-list-step-label current))))

              step-start
              (recur (rest remaining)
                     step-start
                     (cond-> out
                       current (conj (assoc current
                                            :label
                                            (ci-list-step-label current)))))

              current
              (recur (rest remaining)
                     (update current :lines conj [idx line])
                     out)

              :else
              (recur (rest remaining) nil out)))
          (cond-> out
            current (conj (assoc current
                                 :label
                                 (ci-list-step-label current)))))))))
(defn- ci-woodpecker-step-blocks
  [lines]
  (let [mapped-steps (common/yaml-top-section-blocks lines "steps")]
    (if (seq mapped-steps)
      mapped-steps
      (ci-list-step-blocks lines))))
(defn- ci-config-kind
  [path lines]
  (let [filename (str/lower-case (.getName (java.io.File. (str path))))
        path-lower (str/replace (str/lower-case (str path)) "\\" "/")]
    (cond
      (= "jenkinsfile" filename) :jenkins
      (contains? #{"azure-pipelines.yml" "azure-pipelines.yaml"} filename) :azure
      (re-find #"(^|/)\.circleci/config\.ya?ml$" path-lower) :circleci
      (or (re-find #"(^|/)\.buildkite/pipeline\.ya?ml$" path-lower)
          (contains? #{"buildkite.yml" "buildkite.yaml"} filename)) :buildkite
      (contains? #{".drone.yml" ".drone.yaml"} filename) :drone
      (contains? #{".woodpecker.yml" ".woodpecker.yaml"} filename) :woodpecker
      (re-find #"(^|/)\.github/workflows/[^/]+\.ya?ml$" path-lower) :github
      (contains? #{".gitlab-ci.yml" ".gitlab-ci.yaml"} filename) :gitlab
      (seq (ci-github-job-blocks lines)) :github
      :else :gitlab)))
(defn- ci-job-kind
  [job]
  (or (:kind job) :ci-job))
(defn- ci-job-blocks
  [config-kind lines]
  (case config-kind
    :jenkins (ci-jenkins-stage-blocks lines)
    :azure (ci-azure-job-blocks lines)
    :circleci (ci-circleci-job-blocks lines)
    :buildkite (ci-buildkite-step-blocks lines)
    :drone (ci-list-step-blocks lines)
    :woodpecker (ci-woodpecker-step-blocks lines)
    :github (ci-github-job-blocks lines)
    :gitlab (ci-gitlab-job-blocks lines)
    (let [github-jobs (ci-github-job-blocks lines)]
      (if (seq github-jobs)
        github-jobs
        (ci-gitlab-job-blocks lines)))))
(defn- ci-job-needs
  [job]
  (->> (:lines job)
       (mapcat (fn [[idx line]]
                 (if-let [[_ value] (re-matches #"^\s*(?:needs|dependsOn|depends_on):\s*(.+?)\s*$"
                                                line)]
                   (map (fn [target]
                          {:target target
                           :source-line (inc idx)})
                        (common/yaml-scalar-list-values value))
                   [])))
       (remove #(= (:target %) (:label job)))
       distinct
       vec))
(defn- ci-job-declared-facts
  [{:keys [label lines]}]
  (loop [remaining lines
         env-indent nil
         artifact-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)
            jenkins-fact (or
                          (when-let [[_ runner] (re-matches #"^\s*agent\s+([A-Za-z0-9_.-]+)\s*$"
                                                            line)]
                            {:kind :ci-runner
                             :label runner
                             :source-line (inc idx)})
                          (when-let [[_ runner] (re-find #"\blabel\s+['\"]([^'\"]+)['\"]"
                                                         line)]
                            {:kind :ci-runner
                             :label runner
                             :source-line (inc idx)})
                          (when-let [[_ key] (re-matches #"^\s*([A-Z_][A-Z0-9_]*)\s*=\s*['\"].*['\"]\s*$"
                                                         line)]
                            {:kind :ci-env-var
                             :label (str label ":" key)
                             :source-line (inc idx)})
                          (when-let [[_ tool value] (re-matches #"^\s*(jdk|maven|gradle|nodejs)\s+['\"]([^'\"]+)['\"].*$"
                                                                line)]
                            {:kind :ci-tool
                             :label (str tool ":" value)
                             :source-line (inc idx)})
                          (when-let [[_ artifacts] (re-matches #"^\s*archiveArtifacts\s+artifacts:\s*['\"]([^'\"]+)['\"].*$"
                                                               line)]
                            {:kind :ci-artifact
                             :label (str label ":" artifacts)
                             :source-line (inc idx)}))
            artifact-fact (when (and artifact-indent
                                     (> (common/leading-spaces line) artifact-indent))
                            (when-let [[_ artifact]
                                       (re-matches #"^\s*-\s+(.+?)\s*$" line)]
                              {:kind :ci-artifact
                               :label (common/strip-yaml-scalar artifact)
                               :source-line (inc idx)}))
            env-indent* (when (and env-indent
                                   (or (nil? entry)
                                       (> (:indent entry) env-indent)))
                          env-indent)
            artifact-indent* (when (and artifact-indent
                                        (or (nil? entry)
                                            (> (:indent entry) artifact-indent)))
                               artifact-indent)
            fact (when entry
                   (let [{:keys [indent key value source-line]} entry
                         value (common/strip-yaml-scalar value)]
                     (cond
                       (and env-indent* (> indent env-indent*) (seq value))
                       {:kind :ci-env-var
                        :label (str label ":" key)
                        :source-line source-line}

                       (and (contains? #{"env" "variables" "environment"} key)
                            (str/blank? value))
                       nil

                       (and (= "runs-on" key) (seq value))
                       {:kind :ci-runner
                        :label value
                        :source-line source-line}

                       (and (= "vmImage" key) (seq value))
                       {:kind :ci-runner
                        :label value
                        :source-line source-line}

                       (and (contains? #{"container" "image"} key) (seq value))
                       {:kind :container-image
                        :label value
                        :source-line source-line}

                       (and (contains? #{"executor" "queue"} key) (seq value))
                       {:kind :ci-runner
                        :label value
                        :source-line source-line}

                       (and (contains? #{"uses" "task"} key) (seq value))
                       {:kind :ci-action
                        :label value
                        :source-line source-line}

                       (and (= "checkout" key) (seq value))
                       {:kind :ci-action
                        :label (str "checkout:" value)
                        :source-line source-line}

                       (and (contains? #{"artifacts" "store_artifacts"
                                         "artifact_paths"}
                                       key)
                            (str/blank? value))
                       {:kind :ci-artifact
                        :label (str label ":artifacts")
                        :source-line source-line}

                       (and (contains? #{"artifact" "publish" "path"
                                         "artifact_paths"}
                                       key)
                            (seq value))
                       {:kind :ci-artifact
                        :label value
                        :source-line source-line}

                       (and (contains? #{"cache" "restore_cache" "save_cache"} key)
                            (str/blank? value))
                       {:kind :ci-cache
                        :label (str label ":cache")
                        :source-line source-line}

                       (and (= "working-directory" key) (seq value))
                       {:kind :ci-working-directory
                        :label value
                        :source-line source-line})))
            env-indent-next (cond
                              (and entry
                                   (contains? #{"env" "variables" "environment"}
                                              (:key entry))
                                   (str/blank? (:value entry)))
                              (:indent entry)

                              (and env-indent entry (<= (:indent entry) env-indent))
                              nil

                              :else env-indent*)
            artifact-indent-next (cond
                                   (and entry
                                        (contains? #{"artifact_paths"} (:key entry))
                                        (str/blank? (:value entry)))
                                   (:indent entry)

                                   (and artifact-indent
                                        entry
                                        (<= (:indent entry) artifact-indent))
                                   nil

                                   :else artifact-indent*)]
        (recur (rest remaining)
               env-indent-next
               artifact-indent-next
               (cond-> out
                 jenkins-fact (conj jenkins-fact)
                 artifact-fact (conj artifact-fact)
                 (re-matches #"^\s*-\s+checkout\s*$" line)
                 (conj {:kind :ci-action
                        :label "checkout"
                        :source-line (inc idx)})
                 (re-matches #"^\s*-\s+[A-Za-z0-9_.-]+#[A-Za-z0-9_.-]+:\s*$" line)
                 (conj (let [[_ plugin]
                             (re-matches #"^\s*-\s+([A-Za-z0-9_.-]+#[A-Za-z0-9_.-]+):\s*$"
                                         line)]
                         {:kind :ci-plugin
                          :label plugin
                          :source-line (inc idx)}))
                 fact (conj fact))))
      (->> out distinct vec))))
(defn- ci-yaml-top-list-values
  [lines key-name]
  (loop [remaining (map-indexed vector lines)
         in-block? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)]
        (cond
          (and entry
               (zero? (:indent entry))
               (= key-name (:key entry))
               (seq (:value entry)))
          (recur (rest remaining)
                 false
                 (into out
                       (map (fn [value]
                              {:value value
                               :source-line (:source-line entry)})
                            (common/yaml-scalar-list-values (:value entry)))))

          (and entry
               (zero? (:indent entry))
               (= key-name (:key entry))
               (str/blank? (:value entry)))
          (recur (rest remaining) true out)

          (and in-block?
               entry
               (zero? (:indent entry)))
          (recur (rest remaining) false out)

          in-block?
          (if-let [[_ value] (re-matches #"^\s*-\s*(.+?)\s*$" line)]
            (recur (rest remaining)
                   true
                   (conj out {:value (common/strip-yaml-scalar value)
                              :source-line (inc idx)}))
            (recur (rest remaining) true out))

          :else
          (recur (rest remaining) in-block? out)))
      (vec (distinct out)))))
(defn- ci-gitlab-include-facts
  [lines]
  (let [include-keys #{"local" "file" "template" "project" "remote" "component"}]
    (loop [remaining (map-indexed vector lines)
           include-indent nil
           out []]
      (if-let [[idx line] (first remaining)]
        (let [entry (common/yaml-key-line idx line)
              indent (common/leading-spaces line)
              include-indent* (cond
                                (and entry
                                     (zero? (:indent entry))
                                     (= "include" (:key entry))
                                     (str/blank? (:value entry)))
                                (:indent entry)

                                (and include-indent
                                     entry
                                     (<= (:indent entry) include-indent))
                                nil

                                :else include-indent)
              inline-values (when (and entry
                                       (zero? (:indent entry))
                                       (= "include" (:key entry))
                                       (seq (:value entry)))
                              (map (fn [value]
                                     {:kind :ci-template
                                      :label value
                                      :source-line (:source-line entry)
                                      :relation :uses})
                                   (common/yaml-scalar-list-values (:value entry))))
              list-value (when (and include-indent*
                                    (> indent include-indent*))
                           (when-let [[_ value]
                                      (re-matches #"^\s*-\s+(.+?)\s*$" line)]
                             (let [value (common/strip-yaml-scalar value)]
                               (if-let [[_ key scalar]
                                        (re-matches #"([A-Za-z0-9_.-]+):\s*(.+)"
                                                    value)]
                                 (when (contains? include-keys key)
                                   {:kind :ci-template
                                    :label (str key ":" (common/strip-yaml-scalar scalar))
                                    :source-line (inc idx)
                                    :relation :uses})
                                 {:kind :ci-template
                                  :label value
                                  :source-line (inc idx)
                                  :relation :uses}))))
              child-value (when (and include-indent*
                                     (> indent include-indent*)
                                     entry
                                     (contains? include-keys (:key entry))
                                     (seq (:value entry)))
                            {:kind :ci-template
                             :label (str (:key entry) ":"
                                         (common/strip-yaml-scalar (:value entry)))
                             :source-line (:source-line entry)
                             :relation :uses})]
          (recur (rest remaining)
                 include-indent*
                 (cond-> out
                   inline-values (into inline-values)
                   list-value (conj list-value)
                   child-value (conj child-value))))
        (vec (distinct out))))))
(defn- ci-circleci-orb-facts
  [lines]
  (loop [remaining (map-indexed vector lines)
         orbs-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)
            orbs-indent* (cond
                           (and entry
                                (zero? (:indent entry))
                                (= "orbs" (:key entry))
                                (str/blank? (:value entry)))
                           (:indent entry)

                           (and orbs-indent
                                entry
                                (<= (:indent entry) orbs-indent))
                           nil

                           :else orbs-indent)
            fact (when (and orbs-indent*
                            entry
                            (> (:indent entry) orbs-indent*)
                            (seq (:value entry)))
                   {:kind :ci-template
                    :label (common/strip-yaml-scalar (:value entry))
                    :source-line (:source-line entry)
                    :relation :uses})]
        (recur (rest remaining)
               orbs-indent*
               (cond-> out fact (conj fact))))
      (vec (distinct out)))))
(defn- ci-azure-extends-template-facts
  [lines]
  (loop [remaining (map-indexed vector lines)
         extends-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)
            extends-indent* (cond
                              (and entry
                                   (zero? (:indent entry))
                                   (= "extends" (:key entry))
                                   (str/blank? (:value entry)))
                              (:indent entry)

                              (and extends-indent
                                   entry
                                   (<= (:indent entry) extends-indent))
                              nil

                              :else extends-indent)
            fact (when (and extends-indent*
                            entry
                            (> (:indent entry) extends-indent*)
                            (= "template" (:key entry))
                            (seq (:value entry)))
                   {:kind :ci-template
                    :label (common/strip-yaml-scalar (:value entry))
                    :source-line (:source-line entry)
                    :relation :uses})]
        (recur (rest remaining)
               extends-indent*
               (cond-> out fact (conj fact))))
      (vec (distinct out)))))
(defn- ci-template-facts
  [config-kind lines]
  (case config-kind
    :gitlab (ci-gitlab-include-facts lines)
    :circleci (ci-circleci-orb-facts lines)
    :azure (ci-azure-extends-template-facts lines)
    []))
(defn- ci-azure-workflow-facts
  [lines]
  (->> ["trigger" "pr"]
       (mapcat (fn [key-name]
                 (map (fn [{:keys [value source-line]}]
                        {:kind :ci-trigger
                         :label (str key-name ":" value)
                         :source-line source-line
                         :relation :uses})
                      (ci-yaml-top-list-values lines key-name))))
       distinct
       vec))
(defn- ci-jenkins-workflow-facts
  [lines]
  (->> lines
       (map-indexed vector)
       (keep (fn [[idx line]]
               (or (when-let [[_ runner] (re-matches #"^\s*agent\s+([A-Za-z0-9_.-]+)\s*$"
                                                     line)]
                     {:kind :ci-runner
                      :label runner
                      :source-line (inc idx)
                      :relation :uses})
                   (when-let [[_ runner] (re-find #"\blabel\s+['\"]([^'\"]+)['\"]"
                                                  line)]
                     {:kind :ci-runner
                      :label runner
                      :source-line (inc idx)
                      :relation :uses})
                   (when-let [[_ trigger value] (re-matches #"^\s*(cron|pollSCM)\s*\(\s*['\"]([^'\"]+)['\"]\s*\).*$"
                                                            line)]
                     {:kind :ci-trigger
                      :label (str trigger ":" value)
                      :source-line (inc idx)
                      :relation :uses})
                   (when (re-matches #"^\s*githubPush\s*\(\s*\).*$" line)
                     {:kind :ci-trigger
                      :label "githubPush"
                      :source-line (inc idx)
                      :relation :uses}))))
       distinct
       vec))
(defn- ci-workflow-facts
  [lines]
  (loop [remaining (map-indexed vector lines)
         in-on-block? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)]
        (cond
          (and entry
               (zero? (:indent entry))
               (#{"on" "workflow"} (:key entry))
               (seq (:value entry)))
          (let [values (common/yaml-scalar-list-values (:value entry))]
            (recur (rest remaining)
                   false
                   (into out
                         (map (fn [value]
                                {:kind :ci-trigger
                                 :label value
                                 :source-line (:source-line entry)
                                 :relation :uses})
                              values))))

          (and entry
               (zero? (:indent entry))
               (#{"on" "workflow"} (:key entry))
               (str/blank? (:value entry)))
          (recur (rest remaining) true out)

          (and in-on-block?
               entry
               (zero? (:indent entry)))
          (recur (rest remaining) false out)

          (and in-on-block?
               (re-matches #"^\s+([A-Za-z0-9_.-]+):?.*$" line))
          (let [[_ trigger] (re-matches #"^\s+([A-Za-z0-9_.-]+):?.*$" line)]
            (recur (rest remaining)
                   true
                   (conj out {:kind :ci-trigger
                              :label trigger
                              :source-line (inc idx)
                              :relation :uses})))

          :else
          (recur (rest remaining) in-on-block? out)))
      (vec (distinct out)))))
(defn- ci-event-trigger-facts
  [lines]
  (loop [remaining (map-indexed vector lines)
         event-indent nil
         out []]
    (if-let [[idx line] (first remaining)]
      (let [entry (common/yaml-key-line idx line)
            indent (common/leading-spaces line)
            event-indent* (cond
                            (and entry
                                 (= "event" (:key entry))
                                 (str/blank? (:value entry)))
                            (:indent entry)

                            (and event-indent
                                 entry
                                 (<= (:indent entry) event-indent))
                            nil

                            :else event-indent)
            inline-values (when (and entry
                                     (= "event" (:key entry))
                                     (seq (:value entry)))
                            (map (fn [value]
                                   {:kind :ci-trigger
                                    :label value
                                    :source-line (:source-line entry)
                                    :relation :uses})
                                 (common/yaml-scalar-list-values (:value entry))))
            list-value (when (and event-indent*
                                  (> indent event-indent*))
                         (some-> (re-matches #"^\s*-\s+(.+?)\s*$" line)
                                 second
                                 common/strip-yaml-scalar))]
        (recur (rest remaining)
               event-indent*
               (cond-> out
                 inline-values (into inline-values)
                 list-value (conj {:kind :ci-trigger
                                   :label list-value
                                   :source-line (inc idx)
                                   :relation :uses}))))
      (vec (distinct out)))))
(defn- ci-circleci-workflow-facts
  [lines]
  (->> (common/yaml-top-section-blocks lines "workflows")
       (map (fn [{:keys [label source-line]}]
              {:kind :ci-workflow-entry
               :label label
               :source-line source-line
               :relation :defines}))
       distinct
       vec))
(defn- ci-circleci-workflow-needs
  [lines]
  (loop [remaining (map-indexed vector lines)
         in-workflows? false
         current-job nil
         in-requires? false
         out []]
    (if-let [[idx line] (first remaining)]
      (let [trimmed (str/trim line)
            indent (common/leading-spaces line)
            entry (common/yaml-key-line idx line)
            workflow-start? (and entry
                                 (zero? (:indent entry))
                                 (= "workflows" (:key entry)))
            job-entry (when (and in-workflows?
                                 (re-matches #"^-\s+[A-Za-z0-9_.-]+:\s*$"
                                             trimmed))
                        (-> trimmed
                            (str/replace #"^-\s+" "")
                            (str/replace #":$" "")))
            simple-job (when (and in-workflows?
                                  (re-matches #"^-\s+[A-Za-z0-9_.-]+\s*$"
                                              trimmed))
                         (-> trimmed
                             (str/replace #"^-\s+" "")
                             str/trim))
            requires-start? (and current-job
                                 (re-matches #"^requires:\s*$" trimmed))
            required-job (when (and current-job in-requires?)
                           (when-let [[_ target]
                                      (re-matches #"^-\s+([A-Za-z0-9_.-]+)\s*$"
                                                  trimmed)]
                             target))]
        (cond
          workflow-start?
          (recur (rest remaining) true nil false out)

          (and in-workflows? (zero? indent) (seq trimmed))
          (recur (rest remaining) false nil false out)

          job-entry
          (recur (rest remaining) true job-entry false out)

          requires-start?
          (recur (rest remaining) true current-job true out)

          required-job
          (recur (rest remaining)
                 true
                 current-job
                 true
                 (conj out {:source current-job
                            :target required-job
                            :source-line (inc idx)}))

          simple-job
          (recur (rest remaining) true simple-job false out)

          :else
          (recur (rest remaining)
                 in-workflows?
                 current-job
                 (and in-requires?
                      (or (str/blank? trimmed)
                          (> indent 10)))
                 out)))
      (vec (distinct out)))))
(defn- distinct-facts-by-kind-label
  [facts]
  (->> facts
       (reduce (fn [acc {:keys [kind label] :as fact}]
                 (if (contains? acc [kind label])
                   acc
                   (assoc acc [kind label] fact)))
               {})
       vals
       vec))
(defn- ci-command-chunks
  [run-id id-scope file-id path jobs]
  (->> jobs
       (mapcat
        (fn [{:keys [label lines]}]
          (loop [remaining lines
                 command-indent nil
                 out []]
            (if-let [[idx line] (first remaining)]
              (let [entry (common/yaml-key-line idx line)
                    indent (common/leading-spaces line)
                    command-indent* (cond
                                      (and entry
                                           (= "commands" (:key entry))
                                           (str/blank? (:value entry)))
                                      (:indent entry)

                                      (and command-indent
                                           entry
                                           (<= (:indent entry) command-indent))
                                      nil

                                      :else command-indent)
                    command (or (when-let [[_ command]
                                           (re-matches #"^\s*-?\s*(?:run|script|bash|pwsh|powershell|command|sh|bat):\s*(.+?)\s*$"
                                                       line)]
                                  command)
                                (when-let [[_ command]
                                           (re-matches #"^\s*(?:sh|bat|powershell|pwsh)\s+['\"]([^'\"]+)['\"].*$"
                                                       line)]
                                  command)
                                (when (and command-indent*
                                           (> indent command-indent*))
                                  (some-> (re-matches #"^\s*-\s+(.+?)\s*$"
                                                      line)
                                          second)))]
                (recur (rest remaining)
                       command-indent*
                       (cond-> out
                         command
                         (conj (let [chunk-label (str label " command " (inc idx))
                                     text (common/strip-yaml-scalar command)]
                                 {:xt/id (common/chunk-id id-scope
                                                   path
                                                   chunk-label
                                                   (inc idx))
                                  :file-id file-id
                                  :path path
                                  :kind :ci-command
                                  :label chunk-label
                                  :text text
                                  :tokens (text/tokenize (str chunk-label "\n" text))
                                  :source-line (inc idx)
                                  :content-sha (hash/sha256-hex text)
                                  :active? true
                                  :run-id run-id})))))
              out))))
       vec))
(defn extract-ci
  "Extract declared CI workflow jobs and explicit job dependencies."
  [run-id {:keys [id-scope file-id path content] :as file}]
  (let [lines (vec (str/split-lines content))
        config-kind (ci-config-kind path lines)
        workflow-label (ci-workflow-label path lines)
        workflow-node (common/generic-node run-id id-scope file-id path :ci-workflow workflow-label 1)
        jobs (ci-job-blocks config-kind lines)
        workflow-facts (case config-kind
                         :jenkins (concat (ci-jenkins-workflow-facts lines)
                                          (ci-template-facts config-kind lines))
                         :azure (concat (ci-workflow-facts lines)
                                        (ci-azure-workflow-facts lines)
                                        (ci-template-facts config-kind lines))
                         :circleci (concat (ci-workflow-facts lines)
                                           (ci-circleci-workflow-facts lines)
                                           (ci-template-facts config-kind lines))
                         (:drone :woodpecker) (concat (ci-workflow-facts lines)
                                                      (ci-event-trigger-facts lines)
                                                      (ci-template-facts config-kind lines))
                         (concat (ci-workflow-facts lines)
                                 (ci-template-facts config-kind lines)))
        job-nodes (mapv (fn [{:keys [label source-line] :as job}]
                          (common/generic-node run-id
                                        id-scope
                                        file-id
                                        path
                                        (ci-job-kind job)
                                        label
                                        source-line))
                        jobs)
        declared-facts (concat workflow-facts (mapcat ci-job-declared-facts jobs))
        declared-node-facts (distinct-facts-by-kind-label declared-facts)
        declared-nodes (mapv (fn [{:keys [kind label source-line]}]
                               (common/generic-node run-id id-scope file-id path kind label source-line))
                             declared-node-facts)
        define-edges (mapv #(common/edge-row run-id file-id path
                                      (:xt/id workflow-node)
                                      (:xt/id %)
                                      :defines
                                      :extracted
                                      (:source-line %))
                           job-nodes)
        workflow-fact-edges (mapv (fn [{:keys [kind label source-line relation]}]
                                    (common/edge-row run-id
                                              file-id
                                              path
                                              (:xt/id workflow-node)
                                              (common/node-id id-scope kind label)
                                              (or relation :uses)
                                              :extracted
                                              source-line))
                                  workflow-facts)
        need-edges (->> jobs
                        (mapcat (fn [{:keys [label] :as job}]
                                  (map (fn [{:keys [target source-line]}]
                                         (common/edge-row run-id
                                                   file-id
                                                   path
                                                   (common/node-id id-scope (ci-job-kind job) label)
                                                   (common/node-id id-scope (ci-job-kind job) target)
                                                   :requires
                                                   :extracted
                                                   source-line))
                                       (ci-job-needs job))))
                        distinct
                        vec)
        workflow-need-edges (case config-kind
                              :circleci
                              (mapv (fn [{:keys [source target source-line]}]
                                      (common/edge-row run-id
                                                file-id
                                                path
                                                (common/node-id id-scope :ci-job source)
                                                (common/node-id id-scope :ci-job target)
                                                :requires
                                                :extracted
                                                source-line))
                                    (ci-circleci-workflow-needs lines))
                              [])
        declared-edges (->> jobs
                            (mapcat (fn [{job-label :label :as job}]
                                      (map (fn [{:keys [kind label source-line]}]
                                             (common/edge-row run-id
                                                       file-id
                                                       path
                                                       (common/node-id id-scope (ci-job-kind job) job-label)
                                                       (common/node-id id-scope kind label)
                                                       :uses
                                                       :extracted
                                                       source-line))
                                           (ci-job-declared-facts job))))
                            distinct
                            vec)
        chunk-result (common/extract-text-source run-id file :ci-file)
        command-chunks (ci-command-chunks run-id id-scope file-id path jobs)]
    {:nodes (vec (concat [workflow-node] job-nodes declared-nodes))
     :edges (vec (concat define-edges
                         workflow-fact-edges
                         need-edges
                         workflow-need-edges
                         declared-edges))
     :chunks (vec (concat (:chunks chunk-result) command-chunks))
     :diagnostics []}))
