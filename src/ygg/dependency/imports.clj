(ns ygg.dependency.imports
  "Language-specific dependency import candidate filtering."
  (:require [ygg.dependency.imports.common :as import-common]
            [ygg.dependency.imports.dotnet :as dotnet]
            [ygg.dependency.imports.go :as go]
            [ygg.dependency.imports.java :as java]
            [ygg.dependency.imports.javascript :as javascript]
            [ygg.dependency.imports.python :as python]
            [ygg.dependency.imports.rust :as rust]))

(def source-kinds
  #{:javascript :typescript :astro :vue :svelte
    :rust :go :python :java :dotnet})

(defn supported-source-kind?
  [kind]
  (contains? source-kinds kind))

(defn external-package-candidate?
  [kind target]
  (case kind
    (:javascript :typescript :astro :vue :svelte)
    (javascript/external-package-candidate? target)

    :rust
    (rust/external-package-candidate? target)

    :go
    (go/external-package-candidate? target)

    :python
    (python/external-package-candidate? target)

    :dotnet
    (dotnet/external-package-candidate? target)

    :java
    (java/external-package-candidate? target)

    true))

(defn- local-import?
  [kind context]
  (case kind
    (:javascript :typescript :astro :vue :svelte)
    (javascript/local-import? context)

    :go
    (go/local-import? context)

    :java
    (java/local-import? context)

    false))

(defn source-kind
  [files-by-path path]
  (:kind (get files-by-path path)))

(defn module-nodes
  [nodes]
  (go/module-nodes nodes))

(defn package-import-candidate?
  [{:keys [files-by-path alias-nodes module-nodes nodes-by-id edge]}]
  (let [target (import-common/namespace-target (:target-id edge))
        kind (source-kind files-by-path (:path edge))
        context {:files-by-path files-by-path
                 :alias-nodes alias-nodes
                 :module-nodes module-nodes
                 :nodes-by-id nodes-by-id
                 :edge edge
                 :kind kind
                 :target target}]
    (and target
         (not (import-common/local-namespace-import? nodes-by-id edge))
         (not (import-common/local-path-alias-import? alias-nodes edge target))
         (not (local-import? kind context))
         (supported-source-kind? kind)
         (external-package-candidate? kind target))))
