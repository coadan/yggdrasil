(ns agraph.file-facts
  "Bounded mechanical file facts used by derived system inference."
  (:require [agraph.hash :as hash]
            [clojure.string :as str]))

(def fact-kinds
  #{:url
    :env-var
    :sql-security
    :auth-reference
    :port
    :route
    :container-image-producer
    :container-image-consumer
    :k8s-service
    :k8s-ingress
    :k8s-workload
    :k8s-config
    :yaml-resource})

(defn normalize-token
  [value]
  (-> (str/lower-case (str value))
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-+|-+$)" "")))

(defn- fact-id
  [project-id repo-id file-id kind path line normalized-value]
  (str "file-fact:" (hash/short-hash [project-id repo-id file-id kind path line normalized-value])))

(defn- fact-row
  ([run-id project-id repo-id file kind line label normalized-value confidence]
   (fact-row run-id project-id repo-id file kind line label normalized-value confidence {}))
  ([run-id project-id repo-id file kind line label normalized-value confidence attrs]
   (merge
    {:xt/id (fact-id project-id repo-id (:file-id file) kind (:path file) line normalized-value)
     :project-id project-id
     :repo-id repo-id
     :file-id (:file-id file)
     :path (:path file)
     :file-kind (:kind file)
     :kind kind
     :label label
     :normalized-value normalized-value
     :source-line (long (or line 1))
     :confidence (double confidence)
     :active? true
     :run-id run-id}
    attrs)))

(defn- url-values
  [line]
  (->> (re-seq #"https?://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+" line)
       (map #(str/replace % #"[\"')\],;]+$" ""))
       distinct))

(defn- url-runtime-config-line?
  [line]
  (boolean
   (re-find #"(?i)\b(?:api[-_]?url|base[-_]?url|callback[-_]?url|endpoint|uri|url|webhook[-_]?url)\b.{0,120}https?://"
            line)))

(defn- url-context
  [file line]
  (cond
    (= :doc (:kind file)) :reference
    (url-runtime-config-line? line) :runtime-config
    (contains? #{:config :yaml :helm :compose :kustomize} (:kind file)) :runtime-config
    :else :mention))

(defn- env-values
  [line]
  (->> (re-seq #"\b[A-Z][A-Z0-9_]*(?:URL|URI|HOST|PORT|ENDPOINT|QUEUE|TOPIC|TASK_QUEUE|DATABASE|DB)\b"
               line)
       distinct))

(defn- sql-security-values
  [file line]
  (when (contains? #{:sql :db-migration} (:kind file))
    (->> (re-seq #"(?i)\bSECURITY\s+(DEFINER|INVOKER)\b" line)
         (map first)
         (map str/upper-case)
         distinct)))

(def service-account-type-pattern
  #"(?i)(?:^|[{\s,])['\"]?type['\"]?\s*:\s*['\"]service_account['\"]")

(defn- normalize-identifier
  [value]
  (-> (str value)
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      normalize-token))

(defn- identifier-tokens
  [value]
  (->> (str/split (normalize-identifier value) #"-")
       (remove str/blank?)
       vec))

(defn- edit-distance
  [a b]
  (let [a (str a)
        b (str b)
        alen (count a)
        blen (count b)]
    (loop [i 0
           previous (vec (range (inc blen)))]
      (if (= i alen)
        (previous blen)
        (let [ca (.charAt a i)
              current (reduce
                       (fn [row j]
                         (let [cb (.charAt b (dec j))
                               insert-cost (inc (peek row))
                               delete-cost (inc (previous j))
                               replace-cost (+ (previous (dec j))
                                               (if (= ca cb) 0 1))]
                           (conj row (min insert-cost
                                          delete-cost
                                          replace-cost))))
                       [(inc i)]
                       (range 1 (inc blen)))]
          (recur (inc i) current))))))

(defn- close-token?
  [expected token]
  (let [token (str token)]
    (and (<= 3 (count token))
         (or (<= (edit-distance expected token) 1)
             (and (= (count expected) (count token))
                  (some (fn [idx]
                          (= token
                             (str (subs expected 0 idx)
                                  (.charAt expected (inc idx))
                                  (.charAt expected idx)
                                  (subs expected (+ idx 2)))))
                        (range (dec (count expected)))))))))

(defn- has-close-token?
  [tokens expected]
  (some #(close-token? expected %) tokens))

(defn- has-auth-pair?
  [tokens left right]
  (and (has-close-token? tokens left)
       (has-close-token? tokens right)))

(defn- auth-context
  [identifier]
  (let [normalized (normalize-identifier identifier)
        tokens (identifier-tokens identifier)]
    (cond
      (or (str/includes? normalized "service-account")
          (str/includes? normalized "service-acct")
          (str/includes? normalized "svc-account")
          (str/includes? normalized "svc-acct")
          (= "google-application-credentials" normalized)
          (= "type-service-account" normalized)
          (has-auth-pair? tokens "service" "account")
          (has-auth-pair? tokens "service" "acct")
          (has-auth-pair? tokens "svc" "account")
          (has-auth-pair? tokens "svc" "acct")
          (and (some #{"client"} tokens)
               (some #{"email"} tokens)))
      :service-account

      (or (str/includes? normalized "private-key")
          (has-auth-pair? tokens "private" "key"))
      :private-key

      (or (str/includes? normalized "secret")
          (str/includes? normalized "credentials")
          (str/includes? normalized "credential")
          (str/includes? normalized "creds")
          (has-close-token? tokens "secret")
          (has-close-token? tokens "credential")
          (has-close-token? tokens "creds"))
      :secret

      (or (str/includes? normalized "token")
          (has-close-token? tokens "token"))
      :token

      (or (str/includes? normalized "api-key")
          (str/includes? normalized "apikey")
          (has-auth-pair? tokens "api" "key")
          (has-auth-pair? tokens "access" "key"))
      :api-key

      :else :auth)))

(defn- auth-identifier?
  [identifier]
  (not= :auth (auth-context identifier)))

(defn- line-assignment-identifier
  [line]
  (some->> line
           (re-matches #"^\s*(?:export\s+)?['\"]?([A-Za-z_][A-Za-z0-9_.-]*)['\"]?\s*(?:=|:|=>).*$")
           second))

(defn- env-access-identifiers
  [line]
  (->> (concat
        (map second (re-seq #"(?:^|[{\s,])['\"]([A-Za-z_][A-Za-z0-9_.-]*)['\"]\s*:" line))
        (map second (re-seq #"process\.env\.([A-Za-z_][A-Za-z0-9_]*)" line))
        (map second (re-seq #"\b(?:getenv|System\.getenv)\(\s*['\"]([A-Za-z_][A-Za-z0-9_]*)['\"]" line))
        (map second (re-seq #"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::[-=][^}]*)?\}" line))
        (map second (re-seq #"(?i)^\s*-?\s*(?:name|key):\s*([A-Z][A-Z0-9_]{2,})\s*(?:#.*)?$" line)))
       distinct))

(defn- auth-identifier-candidates
  [line]
  (->> (concat
        (when-let [identifier (line-assignment-identifier line)]
          [identifier])
        (env-access-identifiers line)
        (when (re-find service-account-type-pattern line)
          ["type=service_account"]))
       (remove str/blank?)
       distinct))

(defn- auth-values
  [line]
  (let [identifiers (auth-identifier-candidates line)]
    (->> identifiers
         (filter auth-identifier?)
         distinct
         (map (fn [identifier]
                {:label identifier
                 :normalized-value (str "auth:" (normalize-identifier identifier))
                 :auth-context (auth-context identifier)})))))

(defn- port-values
  [line]
  (->> (re-seq #"\b(?:port|targetPort|containerPort):\s*([0-9]{2,5})\b" line)
       (map second)
       distinct))

(defn- route-values
  [line]
  (->> (re-seq #"['\"](/(?:[A-Za-z0-9._~:-]+/?)*)['\"]" line)
       (map second)
       (filter #(<= 2 (count %)))
       distinct))

(defn- strip-image-digest
  [value]
  (first (str/split (str value) #"@" 2)))

(defn- strip-image-tag
  [value]
  (let [value (strip-image-digest value)
        slash-idx (.lastIndexOf value "/")
        colon-idx (.lastIndexOf value ":")]
    (if (< slash-idx colon-idx)
      (subs value 0 colon-idx)
      value)))

(defn- image-artifact-name
  [value]
  (some-> value
          strip-image-tag
          (str/split #"/")
          last
          not-empty))

(defn- container-image-key
  [value]
  (when-let [name (image-artifact-name value)]
    (str "container-image:" (normalize-token name))))

(defn- concrete-image-ref?
  [value]
  (let [value (str value)]
    (and (seq value)
         (not= "image_placeholder" value)
         (not (str/includes? value "%s"))
         (not (str/includes? value "$"))
         (not (str/includes? value "{"))
         (not (str/includes? value "}"))
         (or (str/includes? value "/")
             (str/includes? value ".")
             (str/includes? value ":")))))

(defn- image-scalar-values
  [line]
  (when-let [value (second (re-matches #"^\s*-?\s*(?:image|newName):\s*['\"]?([^'\"\s#]+).*"
                                       line))]
    (when (concrete-image-ref? value)
      [value])))

(defn- shell-image-values
  [line]
  (->> (concat
        (map second
             (re-seq #"\$\{[A-Z][A-Z0-9_]*IMAGE[A-Z0-9_]*(?::[-=])([^}\"']+)\}"
                     line))
        (map second
             (re-seq #"(?:^|[\s{])(?:[A-Z][A-Z0-9_]*IMAGE[A-Z0-9_]*|IMAGE)(?::=|=)['\"]?([^'\"\s}]+)"
                     line)))
       (filter concrete-image-ref?)
       distinct))

(defn- path-parts
  [path]
  (->> (str/split (str path) #"/")
       (remove str/blank?)
       vec))

(defn- dirname
  [path]
  (let [parts (path-parts path)]
    (when (< 1 (count parts))
      (str/join "/" (butlast parts)))))

(defn- dirname-basename
  [path]
  (some-> path dirname path-parts last))

(defn- dockerfile-producer-facts
  [run-id project-id repo-id file]
  (when (and (= :docker (:kind file))
             (= "dockerfile" (str/lower-case (last (path-parts (:path file))))))
    (when-let [artifact-name (dirname-basename (:path file))]
      (when-let [artifact-key (container-image-key artifact-name)]
        [(fact-row run-id
                   project-id
                   repo-id
                   file
                   :container-image-producer
                   1
                   artifact-name
                   artifact-key
                   0.72)]))))

(defn- shell-build-file?
  [file]
  (boolean (re-find #"(?m)\bdocker\s+build\b" (:content file))))

(defn- line-break-index
  [^String content ^long start]
  (let [length (.length content)]
    (loop [idx start]
      (cond
        (>= idx length) -1
        (let [ch (.charAt content (int idx))]
          (or (= \newline ch)
              (= \return ch))) idx
        :else (recur (inc idx))))))

(defn- next-line-start
  [^String content ^long break-idx]
  (if (and (= \return (.charAt content (int break-idx)))
           (< (inc break-idx) (.length content))
           (= \newline (.charAt content (int (inc break-idx)))))
    (+ break-idx 2)
    (inc break-idx)))

(defn- indexed-lines
  [content]
  (let [^String content (or content "")
        length (.length content)]
    (letfn [(step [start idx]
              (lazy-seq
               (when (< start length)
                 (let [break-idx (line-break-index content start)]
                   (if (neg? break-idx)
                     [[idx (subs content start)]]
                     (cons [idx (subs content start break-idx)]
                           (step (next-line-start content break-idx)
                                 (inc idx))))))))]
      (step 0 0))))

(defn- shell-container-image-facts
  [run-id project-id repo-id file]
  (when (= :shell (:kind file))
    (let [fact-kind (if (shell-build-file? file)
                      :container-image-producer
                      :container-image-consumer)]
      (->> (indexed-lines (:content file))
           (mapcat (fn [[idx line]]
                     (let [line-no (inc idx)]
                       (keep (fn [value]
                               (when-let [artifact-key (container-image-key value)]
                                 (fact-row run-id
                                           project-id
                                           repo-id
                                           file
                                           fact-kind
                                           line-no
                                           value
                                           artifact-key
                                           0.70)))
                             (shell-image-values line)))))
           vec))))

(defn- container-image-consumer-facts
  [run-id project-id repo-id file idx line]
  (when (#{:yaml :helm :compose :kustomize} (:kind file))
    (let [line-no (inc idx)]
      (->> (image-scalar-values line)
           (keep (fn [value]
                   (when-let [artifact-key (container-image-key value)]
                     (fact-row run-id
                               project-id
                               repo-id
                               file
                               :container-image-consumer
                               line-no
                               value
                               artifact-key
                               0.74))))))))

(defn- line-facts
  [run-id project-id repo-id file idx line]
  (let [line-no (inc idx)]
    (concat
     (map #(fact-row run-id
                     project-id
                     repo-id
                     file
                     :url
                     line-no
                     %
                     (normalize-token %)
                     0.70
                     {:url-context (url-context file line)})
          (url-values line))
     (map #(fact-row run-id project-id repo-id file :env-var line-no % (normalize-token %) 0.65)
          (env-values line))
     (map #(fact-row run-id project-id repo-id file :sql-security line-no % (normalize-token %) 0.68)
          (sql-security-values file line))
     (map #(fact-row run-id
                     project-id
                     repo-id
                     file
                     :auth-reference
                     line-no
                     (:label %)
                     (:normalized-value %)
                     0.68
                     {:auth-context (:auth-context %)})
          (auth-values line))
     (map #(fact-row run-id project-id repo-id file :port line-no (str "port " %) % 0.55)
          (port-values line))
     (map #(fact-row run-id project-id repo-id file :route line-no % (normalize-token %) 0.55)
          (route-values line))
     (container-image-consumer-facts run-id
                                     project-id
                                     repo-id
                                     file
                                     idx
                                     line))))

(defn- yaml-docs
  [content]
  (str/split content #"(?m)^---\s*$"))

(defn- yaml-resource
  [doc]
  (let [lines (str/split-lines doc)
        kind (some (fn [line]
                     (second (re-matches #"^\s*kind:\s*([A-Za-z0-9_-]+)\s*$" line)))
                   lines)
        name (some (fn [line]
                     (second (re-matches #"^\s*name:\s*([A-Za-z0-9_.-]+)\s*$" line)))
                   lines)]
    (when (and kind name)
      {:kind kind :name name})))

(defn- yaml-facts
  [run-id project-id repo-id file]
  (when (#{:yaml :helm :compose :kustomize} (:kind file))
    (->> (yaml-docs (:content file))
         (keep yaml-resource)
         (map (fn [{:keys [kind name]}]
                (let [fact-kind (case kind
                                  "Service" :k8s-service
                                  "Ingress" :k8s-ingress
                                  ("Deployment" "StatefulSet" "DaemonSet") :k8s-workload
                                  "ConfigMap" :k8s-config
                                  :yaml-resource)]
                  (fact-row run-id
                            project-id
                            repo-id
                            file
                            fact-kind
                            1
                            (str kind " " name)
                            (normalize-token name)
                            0.80)))))))

(defn facts-for-file
  "Return small, project-agnostic facts extracted from one indexed file."
  [run-id project-id repo-id file]
  (let [file (update file :content #(or % ""))]
    (vec
     (concat
      (mapcat (fn [[idx line]]
                (line-facts run-id project-id repo-id file idx line))
              (indexed-lines (:content file)))
      (dockerfile-producer-facts run-id project-id repo-id file)
      (shell-container-image-facts run-id project-id repo-id file)
      (yaml-facts run-id project-id repo-id file)))))
