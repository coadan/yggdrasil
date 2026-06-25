(ns ygg.extract-contract-test
  (:require [ygg.extract :as extract]
            [ygg.fs :as fs]
            [ygg.hash :as hash]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest extracts-graphql-schema-operations-and-references
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/contracts/panels.graphql")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))]
    (is (= :graphql (:kind file)))
    (is (contains? labels "contracts/panels.graphql"))
    (is (contains? labels "schema"))
    (is (contains? labels "Query"))
    (is (contains? labels "Mutation"))
    (is (contains? labels "Panel"))
    (is (contains? labels "Node"))
    (is (contains? labels "PanelInput"))
    (is (contains? labels "PanelFilter"))
    (is (contains? labels "PanelStatus"))
    (is (contains? labels "Subscription.panelChanged"))
    (is (contains? labels "Panel.status"))
    (is (contains? labels "PanelStatus.DRAFT"))
    (is (contains? labels "PanelSummary"))
    (is (= 1 (:graphql-file kinds)))
    (is (= 4 (:graphql-type kinds)))
    (is (= 1 (:graphql-interface kinds)))
    (is (= 2 (:graphql-input kinds)))
    (is (= 1 (:graphql-enum kinds)))
    (is (= 12 (:graphql-field kinds)))
    (is (= 2 (:graphql-enum-value kinds)))
    (is (= 1 (:graphql-fragment kinds)))
    (is (pos? (get relations :defines 0)))
    (is (pos? (get relations :references 0)))
    (is (contains? reference-targets
                   (extract/node-id :graphql-reference "Panel")))
    (is (contains? reference-targets
                   (extract/node-id :graphql-reference "Node")))
    (is (contains? reference-targets
                   (extract/node-id :graphql-reference "PanelStatus")))
    (is (= :code-definition (get-in chunks-by-label ["Panel" :kind])))
    (is (= :graphql-type (get-in chunks-by-label ["Panel" :definition-kind])))
    (is (str/includes? (get-in chunks-by-label ["Panel" :text]) "owner: User"))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:broken.graphql"
                                      :path "broken.graphql"
                                      :kind :graphql
                                      :content "type Broken {\n  id: ID!\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))
(deftest extracts-protobuf-packages-messages-services-and-references
  (let [file (fs/file-record "test/fixtures/extractor-repo"
                             "test/fixtures/extractor-repo/contracts/panels.proto")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))
        import-targets (set (map :target-id
                                 (filter #(= :imports (:relation %))
                                         (:edges result))))
        reference-targets (set (map :target-id
                                    (filter #(= :references (:relation %))
                                            (:edges result))))
        chunks-by-label (into {} (map (juxt :label identity)) (:chunks result))]
    (is (= :protobuf (:kind file)))
    (is (contains? labels "acme.panels.v1"))
    (is (contains? labels "acme.panels.v1/Panel"))
    (is (contains? labels "acme.panels.v1/User"))
    (is (contains? labels "acme.panels.v1/GetPanelRequest"))
    (is (contains? labels "acme.panels.v1/PanelStatus"))
    (is (contains? labels "acme.panels.v1/PanelService"))
    (is (contains? labels "acme.panels.v1/PanelService.GetPanel"))
    (is (contains? labels "acme.panels.v1/Panel.owner"))
    (is (contains? labels "acme.panels.v1/Panel.status"))
    (is (contains? labels "acme.panels.v1/Panel.reviewers"))
    (is (contains? labels "acme.panels.v1/PanelStatus.PANEL_STATUS_DRAFT"))
    (is (= 1 (:namespace kinds)))
    (is (= 3 (:protobuf-message kinds)))
    (is (= 1 (:protobuf-enum kinds)))
    (is (= 1 (:protobuf-service kinds)))
    (is (= 1 (:protobuf-rpc kinds)))
    (is (= 7 (:protobuf-field kinds)))
    (is (= 3 (:protobuf-enum-value kinds)))
    (is (= 1 (get relations :imports 0)))
    (is (contains? import-targets
                   (extract/node-id :namespace "google/protobuf/timestamp.proto")))
    (is (pos? (get relations :references 0)))
    (is (contains? reference-targets
                   (extract/node-id :protobuf-reference "User")))
    (is (contains? reference-targets
                   (extract/node-id :protobuf-reference "GetPanelRequest")))
    (is (contains? reference-targets
                   (extract/node-id :protobuf-reference "Panel")))
    (is (contains? reference-targets
                   (extract/node-id :protobuf-reference "PanelStatus")))
    (is (= :code-definition
           (get-in chunks-by-label ["acme.panels.v1/Panel" :kind])))
    (is (= :protobuf-message
           (get-in chunks-by-label ["acme.panels.v1/Panel" :definition-kind])))
    (is (str/includes?
         (get-in chunks-by-label ["acme.panels.v1/Panel" :text])
         "google.protobuf.Timestamp"))
    (is (empty? (:diagnostics result))))
  (let [result (extract/extract-file "run/test"
                                     {:file-id "file:broken.proto"
                                      :path "broken.proto"
                                      :kind :protobuf
                                      :content "syntax = \"proto3\";\nmessage Broken {\n"})
        diagnostic (first (:diagnostics result))]
    (is (= :parse (:stage diagnostic)))
    (is (str/includes? (:message diagnostic) "unbalanced curly braces"))))
(deftest extracts-bobr-web-and-wordpress-formats
  (let [astro-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/bobr/pages/index.astro"))
        php-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/bobr/plugin/bobr-wordpress-connector.php"))
        gettext-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/bobr/languages/messages.po"))
        svg-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/bobr/assets/logo.svg"))
        config-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/bobr/config/wrangler.jsonc"))
        env-result (extract/extract-file
                    "run/test"
                    (fs/file-record "test/fixtures/extractor-repo"
                                    "test/fixtures/extractor-repo/bobr/infra/support.env.example"))
        html-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/bobr/public/index.html"))
        text-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/bobr/plugin/readme.txt"))
        astro-labels (set (map :label (:nodes astro-result)))
        astro-imports (set (map :target-id
                                (filter #(= :imports (:relation %))
                                        (:edges astro-result))))
        php-labels (set (map :label (:nodes php-result)))
        php-relations (frequencies (map :relation (:edges php-result)))
        gettext-labels (set (map :label (:nodes gettext-result)))
        svg-labels (set (map :label (:nodes svg-result)))]
    (is (= ["astro-file"] (mapv (comp name :kind) (:chunks astro-result))))
    (is (contains? astro-labels "bobr.pages.index"))
    (is (contains? astro-imports
                   (extract/node-id :namespace "bobr.components.SiteHeader")))
    (is (contains? astro-imports
                   (extract/node-id :namespace "bobr.lib.offers")))
    (is (contains? php-labels "Bobr.WordPress"))
    (is (contains? php-labels "Bobr.WordPress/Connector"))
    (is (contains? php-labels "Bobr.WordPress/Connector.DEFAULT_HOOK"))
    (is (contains? php-labels "Bobr.WordPress/Connector.register"))
    (is (contains? php-labels "Bobr.WordPress/Connector.boot"))
    (is (contains? php-labels "Bobr.WordPress/bobr_connector"))
    (is (= 1 (get php-relations :imports 0)))
    (is (= 1 (get php-relations :uses 0)))
    (is (contains? gettext-labels "Connect Bobr"))
    (is (contains? gettext-labels "Open settings"))
    (is (= [:gettext-file] (mapv :kind (:chunks gettext-result))))
    (is (contains? svg-labels "bobr/assets/logo.svg"))
    (is (contains? svg-labels "svg#logo-root"))
    (is (contains? svg-labels "symbol#logo-mark"))
    (is (contains? svg-labels "path#logo-path"))
    (is (= [:svg-file] (mapv :kind (:chunks svg-result))))
    (is (= :config (:kind (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/bobr/config/wrangler.jsonc"))))
    (is (= [:config] (mapv :kind (:chunks config-result))))
    (is (= :env (:kind (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/bobr/infra/support.env.example"))))
    (is (= [:env-file] (mapv :kind (:chunks env-result))))
    (is (= [:html-file] (mapv :kind (:chunks html-result))))
    (is (= [:text-file] (mapv :kind (:chunks text-result))))))

(deftest svg-chunks-use-bounded-element-summary
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "ygg-svg-summary"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        svg-file (doto (io/file root "assets/large.svg")
                   (io/make-parents))
        path-payload (str/join " "
                               (repeat 2000
                                       "THIS_PATH_PAYLOAD_SHOULD_NOT_BE_INDEXED"))
        svg-content (str "<svg id=\"logo-root\" xmlns=\"http://www.w3.org/2000/svg\">"
                         "<path id=\"logo-path\" d=\"" path-payload "\" />"
                         "</svg>")]
    (spit svg-file svg-content)
    (let [result (extract/extract-file "run/test"
                                       (fs/file-record (.getPath root)
                                                       (.getPath svg-file)))
          labels (set (map :label (:nodes result)))
          chunk-text (:text (first (:chunks result)))]
      (is (contains? labels "svg#logo-root"))
      (is (contains? labels "path#logo-path"))
      (is (str/includes? chunk-text "path#logo-path"))
      (is (not (str/includes? chunk-text "THIS_PATH_PAYLOAD_SHOULD_NOT_BE_INDEXED"))))))

(deftest extracts-binary-asset-metadata-without-text-chunks
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "ygg-binary-assets"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        png-bytes (byte-array [1 2 3 4])
        font-bytes (byte-array [5 6 7 8])
        mo-bytes (byte-array [9 10 11 12])
        key-bytes (byte-array [13 14 15 16])
        video-bytes (byte-array [17 18 19 20])
        archive-bytes (byte-array [21 22 23 24])
        jar-bytes (byte-array [25 26 27 28])
        class-bytes (byte-array [29 30 31 32])
        opaque-bytes (byte-array [33 34 35 36])
        png-file (io/file root "hero.png")
        font-file (io/file root "brand.ttf")
        mo-file (io/file root "messages.mo")
        key-file (io/file root "dev.key")
        video-file (io/file root "clip.mp4")
        archive-file (io/file root "trace.svg.gz")
        jar-file (io/file root "plugin.jar")
        class-file (io/file root "App.class")
        opaque-file (io/file root "template.penpot")]
    (java.nio.file.Files/write (.toPath png-file)
                               png-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath font-file)
                               font-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath mo-file)
                               mo-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath key-file)
                               key-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath video-file)
                               video-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath archive-file)
                               archive-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath jar-file)
                               jar-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath class-file)
                               class-bytes
                               (make-array java.nio.file.OpenOption 0))
    (java.nio.file.Files/write (.toPath opaque-file)
                               opaque-bytes
                               (make-array java.nio.file.OpenOption 0))
    (let [png-record (fs/file-record (.getPath root) (.getPath png-file))
          font-record (fs/file-record (.getPath root) (.getPath font-file))
          mo-record (fs/file-record (.getPath root) (.getPath mo-file))
          key-record (fs/file-record (.getPath root) (.getPath key-file))
          video-record (fs/file-record (.getPath root) (.getPath video-file))
          archive-record (fs/file-record (.getPath root) (.getPath archive-file))
          jar-record (fs/file-record (.getPath root) (.getPath jar-file))
          class-record (fs/file-record (.getPath root) (.getPath class-file))
          opaque-record (fs/file-record (.getPath root) (.getPath opaque-file))
          png-result (extract/extract-file "run/test" png-record)
          font-result (extract/extract-file "run/test" font-record)
          mo-result (extract/extract-file "run/test" mo-record)
          key-result (extract/extract-file "run/test" key-record)
          video-result (extract/extract-file "run/test" video-record)
          archive-result (extract/extract-file "run/test" archive-record)
          jar-result (extract/extract-file "run/test" jar-record)
          class-result (extract/extract-file "run/test" class-record)
          opaque-result (extract/extract-file "run/test" opaque-record)]
      (is (= :image-asset (:kind png-record)))
      (is (= :font-asset (:kind font-record)))
      (is (= :gettext-binary (:kind mo-record)))
      (is (= :secret-material (:kind key-record)))
      (is (= :media-asset (:kind video-record)))
      (is (= :archive-asset (:kind archive-record)))
      (is (= :archive-asset (:kind jar-record)))
      (is (= :compiled-artifact (:kind class-record)))
      (is (= :opaque-asset (:kind opaque-record)))
      (is (= "" (:content png-record)))
      (is (= "" (:content key-record)))
      (is (= "" (:content video-record)))
      (is (= "" (:content archive-record)))
      (is (= "" (:content jar-record)))
      (is (= "" (:content class-record)))
      (is (= "" (:content opaque-record)))
      (is (:binary? png-record))
      (is (:binary? key-record))
      (is (:binary? video-record))
      (is (:binary? archive-record))
      (is (:binary? jar-record))
      (is (:binary? class-record))
      (is (:binary? opaque-record))
      (is (= (str "sha256:" (hash/sha256-bytes-hex png-bytes))
             (:content-sha png-record)))
      (is (= (str "sha256:" (hash/sha256-bytes-hex key-bytes))
             (:content-sha key-record)))
      (is (= (str "sha256:" (hash/sha256-bytes-hex video-bytes))
             (:content-sha video-record)))
      (is (= (str "sha256:" (hash/sha256-bytes-hex archive-bytes))
             (:content-sha archive-record)))
      (is (= (str "sha256:" (hash/sha256-bytes-hex jar-bytes))
             (:content-sha jar-record)))
      (is (= (str "sha256:" (hash/sha256-bytes-hex class-bytes))
             (:content-sha class-record)))
      (is (= (str "sha256:" (hash/sha256-bytes-hex opaque-bytes))
             (:content-sha opaque-record)))
      (is (= [:image-asset] (mapv :kind (:nodes png-result))))
      (is (= [:font-asset] (mapv :kind (:nodes font-result))))
      (is (= [:gettext-binary] (mapv :kind (:nodes mo-result))))
      (is (= [:secret-material] (mapv :kind (:nodes key-result))))
      (is (= [:media-asset] (mapv :kind (:nodes video-result))))
      (is (= [:archive-asset] (mapv :kind (:nodes archive-result))))
      (is (= [:archive-asset] (mapv :kind (:nodes jar-result))))
      (is (= [:compiled-artifact] (mapv :kind (:nodes class-result))))
      (is (= [:opaque-asset] (mapv :kind (:nodes opaque-result))))
      (is (empty? (:chunks png-result)))
      (is (empty? (:chunks font-result)))
      (is (empty? (:chunks mo-result)))
      (is (empty? (:chunks key-result)))
      (is (empty? (:chunks video-result)))
      (is (empty? (:chunks archive-result)))
      (is (empty? (:chunks jar-result)))
      (is (empty? (:chunks class-result)))
      (is (empty? (:chunks opaque-result))))))
(deftest detects-license-template-and-shebang-files
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "ygg-text-format-coverage"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        license-file (io/file root "LICENSE")
        vendor-license-file (io/file root "cytoscape.LICENSE")
        template-file (doto (io/file root "demo.rb.template")
                        (io/make-parents))
        redirects-file (io/file root "_redirects")
        proc-file (doto (io/file root "fixtures/memory.max")
                    (io/make-parents))
        wasm-file (doto (io/file root "assets/module.wasm")
                    (io/make-parents))
        script-file (doto (io/file root "tool")
                      (io/make-parents))]
    (spit license-file "MIT\n")
    (spit vendor-license-file "Vendor license\n")
    (spit template-file "class Demo < Formula\nend\n")
    (spit redirects-file "/old /new 301\n")
    (spit proc-file "max\n")
    (spit wasm-file "wasm\n")
    (spit script-file "#!/usr/bin/env bash\nexec demo\n")
    (let [coverage (:files (fs/scan-file-coverage (.getPath root)))
          kind-by-path (into {} (keep #(when (:supported? %)
                                         [(:path %) (:kind %)])
                                      coverage))
          skipped-by-path (into {} (keep #(when-not (:supported? %)
                                            [(:path %) (:skip-reason %)])
                                         coverage))
          script-record (fs/file-record (.getPath root) (.getPath script-file))
          redirects-record (fs/file-record (.getPath root) (.getPath redirects-file))
          proc-record (fs/file-record (.getPath root) (.getPath proc-file))
          template-result (extract/extract-file
                           "run/test"
                           (fs/file-record (.getPath root)
                                           (.getPath template-file)))
          redirects-result (extract/extract-file "run/test" redirects-record)
          proc-result (extract/extract-file "run/test" proc-record)
          script-result (extract/extract-file "run/test" script-record)]
      (is (= :governance (fs/file-kind "LICENSE")))
      (is (= :doc (fs/file-kind "cytoscape.LICENSE")))
      (is (= :ruby (fs/file-kind "demo.rb.template")))
      (is (= :shell (:kind script-record)))
      (is (= :unknown (:kind redirects-record)))
      (is (= :unknown (:kind proc-record)))
      (is (= {"LICENSE" :governance
              "cytoscape.LICENSE" :doc
              "demo.rb.template" :ruby
              "_redirects" :unknown
              "fixtures/memory.max" :unknown
              "tool" :shell}
             kind-by-path))
      (is (= {"assets/module.wasm" :unsupported-extension}
             skipped-by-path))
      (is (contains? (set (map :label (:nodes template-result))) "demo"))
      (is (= [:unknown-file] (mapv :kind (:chunks redirects-result))))
      (is (= [:unknown-file] (mapv :kind (:chunks proc-result))))
      (is (= [:shell-file] (mapv :kind (:chunks script-result)))))))
(deftest extracts-ci-workflows-jobs-needs-and-command-chunks
  (let [github-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/ci/.github/workflows/ci.yml"))
        gitlab-result (extract/extract-file
                       "run/test"
                       (fs/file-record "test/fixtures/extractor-repo"
                                       "test/fixtures/extractor-repo/ci/.gitlab-ci.yml"))
        jenkins-result (extract/extract-file
                        "run/test"
                        (fs/file-record "test/fixtures/extractor-repo"
                                        "test/fixtures/extractor-repo/ci/Jenkinsfile"))
        azure-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/azure-pipelines.yml"))
        circleci-result (extract/extract-file
                         "run/test"
                         (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/ci/.circleci/config.yml"))
        buildkite-result (extract/extract-file
                          "run/test"
                          (fs/file-record "test/fixtures/extractor-repo"
                                          "test/fixtures/extractor-repo/ci/.buildkite/pipeline.yml"))
        drone-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/.drone.yml"))
        woodpecker-result (extract/extract-file
                           "run/test"
                           (fs/file-record "test/fixtures/extractor-repo"
                                           "test/fixtures/extractor-repo/ci/.woodpecker.yml"))
        github-labels (set (map :label (:nodes github-result)))
        github-kinds (frequencies (map :kind (:nodes github-result)))
        github-relations (frequencies (map :relation (:edges github-result)))
        github-command-chunks (filter #(= :ci-command (:kind %))
                                      (:chunks github-result))
        gitlab-labels (set (map :label (:nodes gitlab-result)))
        gitlab-kinds (frequencies (map :kind (:nodes gitlab-result)))
        gitlab-relations (frequencies (map :relation (:edges gitlab-result)))
        gitlab-command-chunks (filter #(= :ci-command (:kind %))
                                      (:chunks gitlab-result))
        jenkins-labels (set (map :label (:nodes jenkins-result)))
        jenkins-kinds (frequencies (map :kind (:nodes jenkins-result)))
        jenkins-relations (frequencies (map :relation (:edges jenkins-result)))
        jenkins-command-chunks (filter #(= :ci-command (:kind %))
                                       (:chunks jenkins-result))
        azure-labels (set (map :label (:nodes azure-result)))
        azure-kinds (frequencies (map :kind (:nodes azure-result)))
        azure-relations (frequencies (map :relation (:edges azure-result)))
        azure-command-chunks (filter #(= :ci-command (:kind %))
                                     (:chunks azure-result))
        circleci-labels (set (map :label (:nodes circleci-result)))
        circleci-kinds (frequencies (map :kind (:nodes circleci-result)))
        circleci-relations (frequencies (map :relation (:edges circleci-result)))
        circleci-command-chunks (filter #(= :ci-command (:kind %))
                                        (:chunks circleci-result))
        buildkite-labels (set (map :label (:nodes buildkite-result)))
        buildkite-kinds (frequencies (map :kind (:nodes buildkite-result)))
        buildkite-relations (frequencies (map :relation (:edges buildkite-result)))
        buildkite-command-chunks (filter #(= :ci-command (:kind %))
                                         (:chunks buildkite-result))
        drone-labels (set (map :label (:nodes drone-result)))
        drone-kinds (frequencies (map :kind (:nodes drone-result)))
        drone-relations (frequencies (map :relation (:edges drone-result)))
        drone-command-chunks (filter #(= :ci-command (:kind %))
                                     (:chunks drone-result))
        woodpecker-labels (set (map :label (:nodes woodpecker-result)))
        woodpecker-kinds (frequencies (map :kind (:nodes woodpecker-result)))
        woodpecker-relations (frequencies (map :relation (:edges woodpecker-result)))
        woodpecker-command-chunks (filter #(= :ci-command (:kind %))
                                          (:chunks woodpecker-result))]
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/.github/workflows/ci.yml"))))
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/Jenkinsfile"))))
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/azure-pipelines.yml"))))
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/.circleci/config.yml"))))
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/.buildkite/pipeline.yml"))))
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/.drone.yml"))))
    (is (= :ci (:kind (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/ci/.woodpecker.yml"))))
    (is (contains? github-labels "CI"))
    (is (contains? github-labels "push"))
    (is (contains? github-labels "pull_request"))
    (is (contains? github-labels "test"))
    (is (contains? github-labels "deploy"))
    (is (contains? github-labels "ubuntu-latest"))
    (is (contains? github-labels "ghcr.io/acme/deploy:latest"))
    (is (contains? github-labels "actions/checkout@v4"))
    (is (contains? github-labels "actions/cache@v4"))
    (is (contains? github-labels "actions/upload-artifact@v4"))
    (is (contains? github-labels "test:TEST_DB"))
    (is (contains? github-labels "apps/web"))
    (is (= 2 (:ci-trigger github-kinds)))
    (is (= 3 (:ci-action github-kinds)))
    (is (= 1 (:ci-runner github-kinds)))
    (is (= 1 (:container-image github-kinds)))
    (is (= 1 (:ci-env-var github-kinds)))
    (is (= 1 (:ci-working-directory github-kinds)))
    (is (= 2 (get github-relations :defines 0)))
    (is (= 1 (get github-relations :requires 0)))
    (is (= 10 (get github-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text github-command-chunks))))
    (is (contains? gitlab-labels "ci/.gitlab-ci.yml"))
    (is (contains? gitlab-labels "test"))
    (is (contains? gitlab-labels "deploy"))
    (is (contains? gitlab-labels "clojure:tools-deps"))
    (is (contains? gitlab-labels "test:TEST_DB"))
    (is (contains? gitlab-labels "test:cache"))
    (is (contains? gitlab-labels "deploy:artifacts"))
    (is (contains? gitlab-labels "local:ci/common.yml"))
    (is (contains? gitlab-labels "template:Security/SAST.gitlab-ci.yml"))
    (is (= 1 (:container-image gitlab-kinds)))
    (is (= 2 (:ci-template gitlab-kinds)))
    (is (= 1 (:ci-env-var gitlab-kinds)))
    (is (= 1 (:ci-cache gitlab-kinds)))
    (is (= 1 (:ci-artifact gitlab-kinds)))
    (is (= 2 (get gitlab-relations :defines 0)))
    (is (= 1 (get gitlab-relations :requires 0)))
    (is (= 6 (get gitlab-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text gitlab-command-chunks))))
    (is (contains? jenkins-labels "ci/Jenkinsfile"))
    (is (contains? jenkins-labels "Test"))
    (is (contains? jenkins-labels "Publish"))
    (is (contains? jenkins-labels "cron:H 4 * * *"))
    (is (contains? jenkins-labels "any"))
    (is (contains? jenkins-labels "Test:TEST_DB"))
    (is (contains? jenkins-labels "jdk:temurin-21"))
    (is (contains? jenkins-labels "Publish:target/report"))
    (is (= 2 (:ci-stage jenkins-kinds)))
    (is (= 1 (:ci-trigger jenkins-kinds)))
    (is (= 1 (:ci-runner jenkins-kinds)))
    (is (= 1 (:ci-env-var jenkins-kinds)))
    (is (= 1 (:ci-tool jenkins-kinds)))
    (is (= 1 (:ci-artifact jenkins-kinds)))
    (is (= 2 (get jenkins-relations :defines 0)))
    (is (= 5 (get jenkins-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text jenkins-command-chunks))))
    (is (contains? azure-labels "ci/azure-pipelines.yml"))
    (is (contains? azure-labels "trigger:main"))
    (is (contains? azure-labels "pr:main"))
    (is (contains? azure-labels "Test"))
    (is (contains? azure-labels "Publish"))
    (is (contains? azure-labels "unit"))
    (is (contains? azure-labels "deploy"))
    (is (contains? azure-labels "ubuntu-latest"))
    (is (contains? azure-labels "unit:TEST_DB"))
    (is (contains? azure-labels "checkout:self"))
    (is (contains? azure-labels "PublishPipelineArtifact@1"))
    (is (contains? azure-labels "report"))
    (is (contains? azure-labels "templates/base.yml"))
    (is (= 2 (:ci-stage azure-kinds)))
    (is (= 2 (:ci-job azure-kinds)))
    (is (= 2 (:ci-trigger azure-kinds)))
    (is (= 1 (:ci-runner azure-kinds)))
    (is (= 1 (:ci-env-var azure-kinds)))
    (is (= 2 (:ci-action azure-kinds)))
    (is (= 1 (:ci-template azure-kinds)))
    (is (= 1 (:ci-artifact azure-kinds)))
    (is (= 4 (get azure-relations :defines 0)))
    (is (= 1 (get azure-relations :requires 0)))
    (is (= 8 (get azure-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text azure-command-chunks))))
    (is (contains? circleci-labels "ci/.circleci/config.yml"))
    (is (contains? circleci-labels "build"))
    (is (contains? circleci-labels "test"))
    (is (contains? circleci-labels "deploy"))
    (is (contains? circleci-labels "clojure"))
    (is (contains? circleci-labels "checkout"))
    (is (contains? circleci-labels "test:cache"))
    (is (contains? circleci-labels "target/report"))
    (is (contains? circleci-labels "circleci/node@5.1.0"))
    (is (= 2 (:ci-job circleci-kinds)))
    (is (= 1 (:ci-workflow-entry circleci-kinds)))
    (is (= 1 (:ci-template circleci-kinds)))
    (is (pos? (get circleci-relations :requires 0)))
    (is (pos? (get circleci-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text circleci-command-chunks))))
    (is (contains? buildkite-labels "ci/.buildkite/pipeline.yml"))
    (is (contains? buildkite-labels "test"))
    (is (contains? buildkite-labels "deploy"))
    (is (contains? buildkite-labels "default"))
    (is (contains? buildkite-labels "docker#v5.9.0"))
    (is (contains? buildkite-labels "clojure:tools-deps"))
    (is (contains? buildkite-labels "target/report"))
    (is (= 2 (:ci-job buildkite-kinds)))
    (is (= 1 (:ci-plugin buildkite-kinds)))
    (is (pos? (get buildkite-relations :requires 0)))
    (is (pos? (get buildkite-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text buildkite-command-chunks))))
    (is (contains? drone-labels "default"))
    (is (contains? drone-labels "push"))
    (is (contains? drone-labels "test"))
    (is (contains? drone-labels "deploy"))
    (is (contains? drone-labels "clojure:tools-deps"))
    (is (contains? drone-labels "alpine:3.20"))
    (is (contains? drone-labels "test:TEST_DB"))
    (is (= 1 (:ci-trigger drone-kinds)))
    (is (= 2 (:ci-job drone-kinds)))
    (is (= 2 (:container-image drone-kinds)))
    (is (= 1 (:ci-env-var drone-kinds)))
    (is (= 2 (get drone-relations :defines 0)))
    (is (= 1 (get drone-relations :requires 0)))
    (is (= 4 (get drone-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"} (set (map :text drone-command-chunks))))
    (is (contains? woodpecker-labels "ci/.woodpecker.yml"))
    (is (contains? woodpecker-labels "push"))
    (is (contains? woodpecker-labels "test"))
    (is (contains? woodpecker-labels "deploy"))
    (is (contains? woodpecker-labels "clojure:tools-deps"))
    (is (contains? woodpecker-labels "alpine:3.20"))
    (is (contains? woodpecker-labels "test:TEST_DB"))
    (is (= 1 (:ci-trigger woodpecker-kinds)))
    (is (= 2 (:ci-job woodpecker-kinds)))
    (is (= 2 (:container-image woodpecker-kinds)))
    (is (= 1 (:ci-env-var woodpecker-kinds)))
    (is (= 2 (get woodpecker-relations :defines 0)))
    (is (= 1 (get woodpecker-relations :requires 0)))
    (is (= 4 (get woodpecker-relations :uses 0)))
    (is (= #{"bb test" "bb deploy"}
           (set (map :text woodpecker-command-chunks))))))
(deftest extracts-build-targets-and-dependencies
  (let [make-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/build/Makefile"))
        cmake-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/build/CMakeLists.txt"))
        cmake-module-result (extract/extract-file
                             "run/test"
                             (fs/file-record "test/fixtures/extractor-repo"
                                             "test/fixtures/extractor-repo/build/toolchain.cmake"))
        bazel-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/build/BUILD.bazel"))
        buck-result (extract/extract-file
                     "run/test"
                     (fs/file-record "test/fixtures/extractor-repo"
                                     "test/fixtures/extractor-repo/build/BUCK"))
        pants-result (extract/extract-file
                      "run/test"
                      (fs/file-record "test/fixtures/extractor-repo"
                                      "test/fixtures/extractor-repo/build/pants/BUILD"))
        pants-toml-result (extract/extract-file
                           "run/test"
                           (fs/file-record
                            "test/fixtures/extractor-repo"
                            "test/fixtures/extractor-repo/build/pants.toml"))
        make-labels (set (map :label (:nodes make-result)))
        cmake-labels (set (map :label (:nodes cmake-result)))
        cmake-module-labels (set (map :label (:nodes cmake-module-result)))
        bazel-labels (set (map :label (:nodes bazel-result)))
        buck-labels (set (map :label (:nodes buck-result)))
        pants-labels (set (map :label (:nodes pants-result)))
        pants-toml-labels (set (map :label (:nodes pants-toml-result)))
        bazel-kinds (frequencies (map :kind (:nodes bazel-result)))
        buck-kinds (frequencies (map :kind (:nodes buck-result)))
        pants-kinds (frequencies (map :kind (:nodes pants-result)))
        pants-toml-kinds (frequencies (map :kind (:nodes pants-toml-result)))
        bazel-relations (frequencies (map :relation (:edges bazel-result)))
        buck-relations (frequencies (map :relation (:edges buck-result)))
        pants-relations (frequencies (map :relation (:edges pants-result)))
        pants-toml-relations (frequencies (map :relation (:edges pants-toml-result)))
        requires-targets (fn [result]
                           (set (map :target-id
                                     (filter #(= :requires (:relation %))
                                             (:edges result)))))]
    (is (= :build (:kind (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/build/Makefile"))))
    (is (contains? make-labels "build/Makefile"))
    (is (contains? make-labels "test"))
    (is (contains? make-labels "deps"))
    (is (contains? (requires-targets make-result)
                   (extract/node-id :build-target "deps")))
    (is (contains? cmake-labels "build/CMakeLists.txt"))
    (is (contains? cmake-labels "core"))
    (is (contains? cmake-labels "app"))
    (is (contains? (requires-targets cmake-result)
                   (extract/node-id :build-target "core")))
    (is (= :build (:kind (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/build/toolchain.cmake"))))
    (is (contains? cmake-module-labels "build/toolchain.cmake"))
    (is (contains? cmake-module-labels "shared"))
    (is (contains? (requires-targets cmake-module-result)
                   (extract/node-id :build-target "shared")))
    (is (contains? bazel-labels "build/BUILD.bazel"))
    (is (contains? bazel-labels "core"))
    (is (contains? bazel-labels "app"))
    (is (contains? bazel-labels "core:cc_library"))
    (is (contains? bazel-labels "app:cc_binary"))
    (is (contains? bazel-labels "core.cc"))
    (is (contains? bazel-labels "app.cc"))
    (is (contains? bazel-labels "//assets:panels"))
    (is (contains? bazel-labels "//visibility:public"))
    (is (contains? bazel-labels "@rules_cc//cc:defs"))
    (is (= 2 (:build-rule bazel-kinds)))
    (is (= 2 (:build-source bazel-kinds)))
    (is (= 1 (:build-data bazel-kinds)))
    (is (= 1 (:build-visibility bazel-kinds)))
    (is (= 1 (:build-reference bazel-kinds)))
    (is (= {:defines 2 :uses 3 :references 3 :requires 2}
           (select-keys bazel-relations [:defines :uses :references :requires])))
    (is (contains? (requires-targets bazel-result)
                   (extract/node-id :build-target "core")))
    (is (= :build (:kind (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/build/BUCK"))))
    (is (contains? buck-labels "build/BUCK"))
    (is (contains? buck-labels "core"))
    (is (contains? buck-labels "app"))
    (is (contains? buck-labels "core:cxx_library"))
    (is (contains? buck-labels "app:cxx_binary"))
    (is (contains? buck-labels "core.cpp"))
    (is (contains? buck-labels "app.cpp"))
    (is (contains? buck-labels "//assets:panels"))
    (is (contains? buck-labels "PUBLIC"))
    (is (contains? buck-labels "//third_party/fmt:fmt"))
    (is (= 2 (:build-rule buck-kinds)))
    (is (= 2 (:build-source buck-kinds)))
    (is (= 1 (:build-data buck-kinds)))
    (is (= 1 (:build-visibility buck-kinds)))
    (is (= 1 (:build-reference buck-kinds)))
    (is (= {:defines 2 :uses 3 :references 3 :requires 2}
           (select-keys buck-relations [:defines :uses :references :requires])))
    (is (contains? (requires-targets buck-result)
                   (extract/node-id :build-target "core")))
    (is (= :build (:kind (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/build/pants/BUILD"))))
    (is (contains? pants-labels "build/pants/BUILD"))
    (is (contains? pants-labels "lib"))
    (is (contains? pants-labels "resources"))
    (is (contains? pants-labels "tests"))
    (is (contains? pants-labels "lib:python_sources"))
    (is (contains? pants-labels "resources:resources"))
    (is (contains? pants-labels "tests:python_tests"))
    (is (contains? pants-labels "*.py"))
    (is (contains? pants-labels "templates/*.html"))
    (is (contains? pants-labels "3rdparty/python#requests"))
    (is (= 3 (:build-target pants-kinds)))
    (is (= 3 (:build-rule pants-kinds)))
    (is (= 2 (:build-source pants-kinds)))
    (is (= 1 (:build-reference pants-kinds)))
    (is (= {:defines 3 :uses 3 :references 2 :requires 3}
           (select-keys pants-relations [:defines :uses :references :requires])))
    (is (contains? (requires-targets pants-result)
                   (extract/node-id :build-target "resources")))
    (is (contains? (requires-targets pants-result)
                   (extract/node-id :build-target "lib")))
    (is (= :build (:kind (fs/file-record "test/fixtures/extractor-repo"
                                         "test/fixtures/extractor-repo/build/pants.toml"))))
    (is (contains? pants-toml-labels "build/pants.toml"))
    (is (contains? pants-toml-labels "pants.backend.python"))
    (is (contains? pants-toml-labels "pants.backend.python.lint.ruff"))
    (is (contains? pants-toml-labels "/src/python"))
    (is (contains? pants-toml-labels "/tests/python"))
    (is (contains? pants-toml-labels "GLOBAL.pants_version=2.24.0"))
    (is (contains? pants-toml-labels
                   "python.interpreter_constraints=CPython>=3.11,<3.13"))
    (is (= 2 (:build-plugin pants-toml-kinds)))
    (is (= 2 (:build-source-root pants-toml-kinds)))
    (is (= 2 (:build-setting pants-toml-kinds)))
    (is (= {:uses 2 :references 2 :defines 2}
           (select-keys pants-toml-relations [:uses :references :defines])))))
(deftest extracts-sql-tables-views-and-foreign-keys
  (let [result (extract/extract-file
                "run/test"
                (fs/file-record "test/fixtures/sample-repo"
                                "test/fixtures/sample-repo/db/schema.sql"))
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        references (filter #(= :references (:relation %)) (:edges result))]
    (is (= [:sql-file] (mapv :kind (:chunks result))))
    (is (contains? labels "panels"))
    (is (contains? labels "panel_events"))
    (is (contains? labels "active_panels"))
    (is (= 2 (:table kinds)))
    (is (= 1 (:view kinds)))
    (is (= 1 (count references)))
    (is (= (extract/node-id :table "panels") (:target-id (first references))))))
(deftest extracts-terraform-blocks-and-explicit-references
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/infra/main.tf")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :terraform (:kind file)))
    (is (contains? labels "infra/main.tf"))
    (is (contains? labels "aws_s3_bucket.assets"))
    (is (contains? labels "aws_s3_bucket_policy.assets"))
    (is (contains? labels "module.cdn"))
    (is (contains? labels "provider.aws"))
    (is (contains? labels "provider.aws.edge"))
    (is (contains? labels "data.aws_caller_identity.current"))
    (is (contains? labels "var.region"))
    (is (contains? labels "output.bucket_name"))
    (is (contains? labels "./modules/cdn"))
    (is (= 1 (:terraform-file kinds)))
    (is (= 2 (:terraform-resource kinds)))
    (is (= 1 (:terraform-data-source kinds)))
    (is (= 1 (:terraform-module kinds)))
    (is (= 1 (:terraform-provider kinds)))
    (is (= 1 (:terraform-variable kinds)))
    (is (= 1 (:terraform-output kinds)))
    (is (= 1 (:terraform-provider-alias kinds)))
    (is (= 1 (:terraform-module-source kinds)))
    (is (= 8 (:defines relations)))
    (is (= 5 (:references relations)))
    (is (= 2 (:uses relations)))
    (is (some #(= :terraform-block (:kind %)) (:chunks result)))))

(deftest extracts-terraform-blocks-with-path-scoped-node-ids
  (let [root-result (extract/extract-file
                     "run/test"
                     {:file-id "file:root-flow"
                      :path "vpc-flow-logs.tf"
                      :kind :terraform
                      :content "resource \"aws_flow_log\" \"this\" {\n  vpc_id = var.vpc_id\n}\n"})
        module-result (extract/extract-file
                       "run/test"
                       {:file-id "file:module-flow"
                        :path "modules/flow-log/main.tf"
                        :kind :terraform
                        :content "resource \"aws_flow_log\" \"this\" {\n  eni_id = var.eni_id\n}\n"})
        flow-node (fn [result]
                    (some #(when (= "aws_flow_log.this" (:label %)) %)
                          (:nodes result)))
        root-flow (flow-node root-result)
        module-flow (flow-node module-result)
        define-targets (fn [result]
                         (set (map :target-id
                                   (filter #(= :defines (:relation %))
                                           (:edges result)))))]
    (is (= "aws_flow_log.this" (:label root-flow)))
    (is (= "aws_flow_log.this" (:label module-flow)))
    (is (= "vpc-flow-logs.tf" (:path root-flow)))
    (is (= "modules/flow-log/main.tf" (:path module-flow)))
    (is (not= (:xt/id root-flow) (:xt/id module-flow)))
    (is (contains? (define-targets root-result) (:xt/id root-flow)))
    (is (contains? (define-targets module-result) (:xt/id module-flow)))))

(deftest extracts-openapi-paths-operations-and-schemas
  (let [file (fs/file-record "test/fixtures/sample-repo"
                             "test/fixtures/sample-repo/api/openapi.yaml")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :openapi (:kind file)))
    (is (contains? labels "api/openapi.yaml"))
    (is (contains? labels "https://api.example.test"))
    (is (contains? labels "/panels"))
    (is (contains? labels "/panels/{id}"))
    (is (contains? labels "GET /panels listPanels"))
    (is (contains? labels "POST /panels createPanel"))
    (is (contains? labels "GET /panels/{id} getPanel"))
    (is (contains? labels "Panel"))
    (is (contains? labels "PanelCreate"))
    (is (= 1 (:api-spec kinds)))
    (is (= 1 (:api-server kinds)))
    (is (= 2 (:api-path kinds)))
    (is (= 3 (:api-operation kinds)))
    (is (= 2 (:api-schema kinds)))
    (is (= 8 (:defines relations)))
    (is (= 4 (:references relations)))
    (is (= [:openapi-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result)))))
(deftest extracts-asyncapi-channels-operations-messages-and-schemas
  (let [file (fs/file-record
              "test/fixtures/extractor-repo"
              "test/fixtures/extractor-repo/contracts/events.asyncapi.json")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :asyncapi (:kind file)))
    (is (contains? labels "contracts/events.asyncapi.json"))
    (is (contains? labels "panel.created"))
    (is (contains? labels "publishPanelCreated"))
    (is (contains? labels "PanelCreated"))
    (is (contains? labels "Panel"))
    (is (contains? labels "EventHeaders"))
    (is (contains? labels "production"))
    (is (contains? labels "panel.created:amqp"))
    (is (contains? labels "PanelCreated:amqp"))
    (is (contains? labels "secured"))
    (is (contains? labels "PanelCreated"))
    (is (contains? labels "PanelCreated:$message.header#/correlationId"))
    (is (= 1 (:asyncapi-spec kinds)))
    (is (= 1 (:asyncapi-server kinds)))
    (is (= 1 (:asyncapi-channel kinds)))
    (is (= 1 (:asyncapi-operation kinds)))
    (is (= 1 (:asyncapi-message kinds)))
    (is (= 2 (:asyncapi-schema kinds)))
    (is (= 1 (:asyncapi-operation-trait kinds)))
    (is (= 2 (:asyncapi-binding kinds)))
    (is (= 1 (:asyncapi-header kinds)))
    (is (= 1 (:asyncapi-correlation-id kinds)))
    (is (= 11 (:defines relations)))
    (is (= 4 (:references relations)))
    (is (= [:asyncapi-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result)))))
(deftest extracts-json-schema-definitions-properties-and-refs
  (let [file (fs/file-record
              "test/fixtures/extractor-repo"
              "test/fixtures/extractor-repo/contracts/panel.schema.json")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :json-schema (:kind file)))
    (is (contains? labels "https://example.com/schemas/panel"))
    (is (contains? labels "User"))
    (is (contains? labels "id"))
    (is (contains? labels "owner"))
    (is (contains? labels "#/$defs/User"))
    (is (= 1 (:json-schema kinds)))
    (is (= 1 (:json-schema-definition kinds)))
    (is (= 2 (:json-schema-property kinds)))
    (is (= 1 (:json-schema-reference kinds)))
    (is (= 3 (:defines relations)))
    (is (= 1 (:references relations)))
    (is (= [:json-schema-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result)))))
(deftest extracts-avro-schemas-fields-and-type-references
  (let [file (fs/file-record
              "test/fixtures/extractor-repo"
              "test/fixtures/extractor-repo/contracts/panel.avsc")
        result (extract/extract-file "run/test" file)
        labels (set (map :label (:nodes result)))
        kinds (frequencies (map :kind (:nodes result)))
        relations (frequencies (map :relation (:edges result)))]
    (is (= :avro (:kind file)))
    (is (contains? labels "contracts/panel.avsc"))
    (is (contains? labels "acme.panels.PanelEvent"))
    (is (contains? labels "acme.panels.PanelEvent.id"))
    (is (contains? labels "acme.panels.PanelEvent.owner"))
    (is (contains? labels "User"))
    (is (= 1 (:avro-file kinds)))
    (is (= 1 (:avro-record kinds)))
    (is (= 2 (:avro-field kinds)))
    (is (= 1 (:avro-reference kinds)))
    (is (= 3 (:defines relations)))
    (is (= 1 (:references relations)))
    (is (= [:avro-file] (mapv :kind (:chunks result))))
    (is (empty? (:diagnostics result)))))
