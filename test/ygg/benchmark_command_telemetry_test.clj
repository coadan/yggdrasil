(ns ygg.benchmark-command-telemetry-test
  (:require [clojure.test :refer [deftest is]]
            [ygg.benchmark-command-telemetry :as telemetry]))

(deftest splits-broad-scoped-and-exact-file-searches
  (let [summary (telemetry/command-telemetry
                 ["rg broken src"
                  "rg --hidden --fixed-strings broken src"
                  "git grep broken -- src"
                  "rg -n broken src/app.clj"
                  "rg --glob=*.clj broken ./src/app.clj"
                  "fd -t f broken src"]
                 {:candidate-paths ["src/app.clj"]})]
    (is (= {:commandCount 6
            :searchCommandCount 6
            :broadSearchCommandCount 3
            :scopedSearchCommandCount 3
            :exactFileSearchCommandCount 2}
           (select-keys summary
                        [:commandCount
                         :searchCommandCount
                         :broadSearchCommandCount
                         :scopedSearchCommandCount
                         :exactFileSearchCommandCount])))))

(deftest aggregate-command-telemetry-tolerates-older-rows
  (let [summary (telemetry/aggregate-command-telemetry
                 [{:commandTelemetry {:commandCount 1
                                      :yggCommandCount 0
                                      :searchCommandCount 1
                                      :fileReadCommandCount 0
                                      :shellCommandCount 0}}
                  {:commandTelemetry {:commandCount 1
                                      :yggCommandCount 0
                                      :searchCommandCount 1
                                      :broadSearchCommandCount 1
                                      :scopedSearchCommandCount 0
                                      :exactFileSearchCommandCount 0
                                      :fileReadCommandCount 0
                                      :shellCommandCount 0}}])]
    (is (= {:commandCount 2
            :yggCommandCount 0
            :searchCommandCount 2
            :broadSearchCommandCount 1
            :scopedSearchCommandCount 0
            :exactFileSearchCommandCount 0
            :fileReadCommandCount 0
            :shellCommandCount 0}
           summary))))

(deftest counts-ygg-artifact-projection-commands
  (let [summary (telemetry/command-telemetry
                 ["jq '.topFiles' \"$YGG_BENCH_YGG_HINTS\""
                  "cat /tmp/run/codex.ygg-context.json | rg connector"
                  "rg connector src/app.clj"]
                 {:candidate-paths ["src/app.clj"]})]
    (is (= {:commandCount 3
            :searchCommandCount 2
            :broadSearchCommandCount 1
            :scopedSearchCommandCount 1
            :exactFileSearchCommandCount 1
            :yggArtifactProjectionCommandCount 2
            :yggArtifactProjectionSegmentCount 2}
           (select-keys summary
                        [:commandCount
                         :searchCommandCount
                         :broadSearchCommandCount
                         :scopedSearchCommandCount
                         :exactFileSearchCommandCount
                         :yggArtifactProjectionCommandCount
                         :yggArtifactProjectionSegmentCount])))))

(deftest internal-ripgrep-telemetry-is-separate-from-shell-search
  (is (= {:internalRipgrepSearchCount 3
          :internalRipgrepElapsedMs 42
          :internalRipgrepMatchCount 17}
         (telemetry/internal-ripgrep-telemetry
          {:search {:instrumentation {:grep-searches 3
                                      :grep-search-ms 42
                                      :grep-raw-matches 17}}}))))

(deftest aggregate-command-telemetry-includes-optional-ripgrep-and-projection-fields
  (let [summary (telemetry/aggregate-command-telemetry
                 [{:commandTelemetry {:commandCount 0
                                      :yggCommandCount 0
                                      :searchCommandCount 0
                                      :fileReadCommandCount 0
                                      :shellCommandCount 0
                                      :internalRipgrepSearchCount 2
                                      :internalRipgrepElapsedMs 9
                                      :internalRipgrepMatchCount 5}}
                  {:commandTelemetry {:commandCount 1
                                      :yggCommandCount 0
                                      :searchCommandCount 0
                                      :fileReadCommandCount 1
                                      :shellCommandCount 0
                                      :yggArtifactProjectionCommandCount 1
                                      :yggArtifactProjectionSegmentCount 1}}])]
    (is (= {:internalRipgrepSearchCount 2
            :internalRipgrepElapsedMs 9
            :internalRipgrepMatchCount 5
            :yggArtifactProjectionCommandCount 1
            :yggArtifactProjectionSegmentCount 1}
           (select-keys summary
                        [:internalRipgrepSearchCount
                         :internalRipgrepElapsedMs
                         :internalRipgrepMatchCount
                         :yggArtifactProjectionCommandCount
                         :yggArtifactProjectionSegmentCount])))))
