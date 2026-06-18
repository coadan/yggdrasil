(ns agraph.system.candidate
  "Deterministic structural candidate generation for project systems."
  (:require [clojure.string :as str]))

(def manifest-filenames
  #{"androidmanifest.xml"
    "app.config.json"
    "app.json"
    "build.sbt"
    "build.gradle"
    "build.gradle.kts"
    "cargo.toml"
    "cpanfile"
    "deno.json"
    "description"
    "directory.build.props"
    "directory.build.targets"
    "deps.edn"
    "eas.json"
    "gemfile"
    "global.json"
    "go.mod"
    "gradle.properties"
    "info.plist"
    "mix.exs"
    "nuget.config"
    "package.json"
    "package.swift"
    "packages.config"
    "pnpm-workspace.yaml"
    "pnpm-workspace.yml"
    "podfile"
    "pom.xml"
    "pubspec.yaml"
    "project.toml"
    "pyproject.toml"
    "rebar.config"
    "rush.json"
    "settings.gradle"
    "settings.gradle.kts"
    "turbo.json"
    "workspace.json"
    "nx.json"
    "lerna.json"})

(def source-like-kinds
  #{:apple-config :astro :build :ci :code :codegen-config :cpp :dart
    :db-config :dotnet :elixir :env :erlang :gettext :go :graphql :haskell
    :html :java :julia :php :kotlin :lua :perl :prisma :protobuf :python
    :r :ruby :rust :scala :svelte :swift :svg :test-config :text
    :tool-config :vue :zig :edn :config :xml :yaml :helm :compose :docker
    :manifest :shell :notebook :devcontainer :kustomize :pre-commit-config
    :codeowners :task-runner :starlark :tool-version-config :storybook})

(defn path-parts
  [path]
  (->> (str/split (str path) #"/")
       (remove str/blank?)
       vec))

(defn file-name
  [path]
  (str/lower-case (last (path-parts path))))

(defn dirname
  [path]
  (let [idx (.lastIndexOf (str path) "/")]
    (when (pos? idx)
      (subs path 0 idx))))

(defn path-under-prefix?
  [path prefix]
  (and (seq prefix)
       (or (= path prefix)
           (str/starts-with? path (str prefix "/")))))

(defn source-like-file?
  [file]
  (contains? source-like-kinds (:kind file)))

(defn repo-candidate
  [{:keys [id repo-id role]}]
  (let [repo-id (or repo-id id "repo")]
    {:system-key "repo"
     :label repo-id
     :kind :repo-boundary
     :path-prefix nil
     :source :deterministic
     :candidate-types [:repo]
     :evidence [{:type :repo
                 :repo-id repo-id
                 :role role}]}))

(defn- path-candidate
  [prefix candidate-type evidence]
  {:system-key (str "path/" prefix)
   :label prefix
   :kind :candidate-system
   :path-prefix prefix
   :source :deterministic
   :candidate-types [candidate-type]
   :evidence [evidence]})

(defn- manifest-candidate
  [file]
  (when (or (= :manifest (:kind file))
            (contains? manifest-filenames (file-name (:path file))))
    (when-let [prefix (dirname (:path file))]
      (path-candidate prefix
                      :manifest-root
                      {:type :manifest
                       :path (:path file)
                       :kind (:kind file)}))))

(defn- structural-prefix
  [path]
  (let [parts (path-parts path)]
    (when (<= 3 (count parts))
      (let [[root child] parts]
        (when-not (str/starts-with? root ".")
          (str root "/" child))))))

(defn- path-cluster-candidates
  [files]
  (->> files
       (filter source-like-file?)
       (keep (fn [file]
               (when-let [prefix (structural-prefix (:path file))]
                 (path-candidate prefix
                                 :path-cluster
                                 {:type :path-cluster
                                  :path (:path file)
                                  :kind (:kind file)}))))))

(defn- merge-candidate
  [a b]
  (-> (or a b)
      (assoc :candidate-types
             (->> (concat (:candidate-types a) (:candidate-types b))
                  (remove nil?)
                  distinct
                  sort
                  vec))
      (assoc :evidence
             (->> (concat (:evidence a) (:evidence b))
                  (remove nil?)
                  distinct
                  vec))))

(defn candidates-for-repo
  "Return neutral structural system candidates for one repo."
  [repo files]
  (let [repo-node (repo-candidate repo)]
    (->> (concat [repo-node]
                 (keep manifest-candidate files)
                 (path-cluster-candidates files))
         (reduce (fn [by-key candidate]
                   (update by-key (:system-key candidate) merge-candidate candidate))
                 {})
         vals
         (sort-by (fn [candidate]
                    [(if (= "repo" (:system-key candidate)) 0 1)
                     (count (or (:path-prefix candidate) ""))
                     (:label candidate)]))
         vec)))

(defn candidates-by-repo
  "Return {repo-id [candidate ...]} for project repos and indexed files."
  [repo-by-id files]
  (let [files-by-repo (group-by :repo-id files)]
    (into {}
          (map (fn [[repo-id repo]]
                 [repo-id (candidates-for-repo repo (get files-by-repo repo-id []))]))
          repo-by-id)))

(defn candidate-for-path
  "Return the most specific neutral candidate for a repo-relative path."
  [repo candidates path]
  (or (->> candidates
           (filter :path-prefix)
           (filter #(path-under-prefix? path (:path-prefix %)))
           (sort-by (comp - count :path-prefix))
           first)
      (some #(when (= "repo" (:system-key %)) %) candidates)
      (repo-candidate repo)))
