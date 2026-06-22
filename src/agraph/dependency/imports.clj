(ns agraph.dependency.imports
  "Language-specific dependency import candidate filtering."
  (:require [agraph.dependency.imports.dotnet :as dotnet]
            [agraph.dependency.imports.java :as java]
            [agraph.dependency.imports.javascript :as javascript]
            [agraph.dependency.imports.python :as python]
            [agraph.dependency.imports.rust :as rust]))

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

    :python
    (python/external-package-candidate? target)

    :dotnet
    (dotnet/external-package-candidate? target)

    :java
    (java/external-package-candidate? target)

    true))
