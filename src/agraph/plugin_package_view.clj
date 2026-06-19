(ns agraph.plugin-package-view
  "Compact, agent-facing views of installed plugin package summaries.")

(defn compact-source
  "Return bounded source/pin metadata for a package source map."
  [source]
  (select-keys source [:type :url :rev :ref :subdir :path]))

(defn compact-caveat-package
  "Return the compact package shape used in status and context packets."
  [package]
  (cond-> (select-keys package [:id
                                :version
                                :path
                                :visibility
                                :scope
                                :benchmark-status
                                :benchmark-cases
                                :claim-authority
                                :manifest-fingerprint
                                :expected-package-id
                                :expected-manifest-fingerprint
                                :diagnostic-counts
                                :warnings])
    (:source package) (assoc :source (compact-source (:source package)))))

(defn caveats
  "Summarize installed plugin packages for agent-facing evidence packets."
  [packages]
  (let [packages (mapv compact-caveat-package packages)
        by-benchmark (frequencies (map :benchmark-status packages))
        non-authoritative (count (filter #(= :non-authoritative
                                             (get-in % [:claim-authority :status]))
                                         packages))
        warning-count (reduce + 0 (map (comp count :warnings) packages))]
    {:counts {:packages (count packages)
              :warnings warning-count
              :unbenchmarked (get by-benchmark :unbenchmarked 0)
              :benchmarked (get by-benchmark :benchmarked 0)
              :nonAuthoritative non-authoritative}
     :packages packages}))
