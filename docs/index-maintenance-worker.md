# Index Maintenance Worker

Yggdrasil can run project-configured maintenance schedules for sync and
sync/check. When a scheduled or manual sync/check enqueues work, the maintenance
worker can claim queue items through the
filesystem queue, writes per-item input/result artifacts, completes the work
item, and validates the returned JSON through the normal `sync work validate`
path.

The default apply policy is conservative:

- `:complete-only` completes and validates work, but does not mutate
  correction facts.
- `:validate-only` additionally moves invalid completed work to `failed`.
- `:apply-valid` is intentionally not supported yet. Add it later only for
  result classes with explicit safety guards.

Example:

```edn
{:maintenance
 {:enabled true
  ;; Omit queue-dir/report-dir to use the central per-project state root:
  ;; <YGG_STORAGE_ROOT>/projects/<project-id>/queue
  ;; <YGG_STORAGE_ROOT>/projects/<project-id>/reports/maintenance
  :schedules
  [{:id "sync"
    :task :sync
    :enabled true
    :every-minutes 10
    :check false
    :enqueue false
    :query-index true}
   {:id "check"
    :task :sync
    :enabled true
    :every-minutes 60
    :check true
    :enqueue true}]

  :worker
  {:enabled true
   :agent-id "ygg-auto"
   :lease-minutes 10
   :max-items-per-run 25
   :max-failures-per-run 3
   :apply {:mode :complete-only}

   :executors
   [{:id "deepseek-openai"
     :type :openai-compatible
     :provider :deepseek
     :model "deepseek-v4-flash"
     :reasoning :medium
     :env "YGG_DEEPSEEK_API_KEY"
     :kinds #{:maintenance-decision :infra-review :dependency-review}}

    {:id "deepseek-anthropic"
     :type :anthropic-compatible
     :provider :deepseek
     :model "deepseek-v4-flash"
     :reasoning :medium
     :env "YGG_DEEPSEEK_API_KEY"
     :kinds #{:maintenance-decision}}

    {:id "openrouter-deepseek-v4"
     :type :openai-compatible
     :provider :openrouter
     :model "deepseek/deepseek-v4-flash"
     :reasoning :medium
     :env "YGG_OPENROUTER_API_KEY"
     :kinds #{:infra-review :dependency-review}}

    {:id "codex"
     :type :command-harness
     :command ["scripts/ygg-maintenance-codex.sh"]
     :reasoning :medium
     :kinds #{:maintenance-decision :infra-review :dependency-review}
     :timeout-ms 600000}

    {:id "claude"
     :type :command-harness
     :command ["scripts/ygg-maintenance-claude.sh"]
     :reasoning :medium
     :kinds #{:maintenance-decision :infra-review :dependency-review}
     :timeout-ms 600000}

    {:id "opencode"
     :type :command-harness
     :command ["scripts/ygg-maintenance-opencode.sh"]
     :reasoning :medium
     :kinds #{:maintenance-decision :infra-review :dependency-review}
     :timeout-ms 600000}]}}}
```

`ygg init` can write the common worker config non-interactively:

```sh
ygg init . --project my-project --out project.edn --maintenance harness
ygg init . --project my-project --out project.edn --maintenance deepseek
ygg init . --project my-project --out project.edn --maintenance openrouter
```

Command harness executors are called with `--work <input.json> --result
<result.json>` appended to the configured command. The harness must write a
valid JSON result to the result path. Executor `:reasoning` defaults to
`:medium` and supports `:low`, `:medium`, `:high`, and `:xhigh`. Command
harnesses receive the normalized value as `YGG_MAINTENANCE_REASONING`; the Codex
wrapper maps it to `model_reasoning_effort` and still allows a last-mile
`YGG_CODEX_MAINTENANCE_REASONING` override.

Maintenance packets carry first-class `:instructions`, `:expectedResultSchema`,
and `:expectedOutput`. Treat the common path as one small correction patch, or a
conservative empty `correctionPatch` result when the packet evidence is not
enough. Do not broaden a packet into a whole-repository review.

Server behavior:

```sh
ygg init . --project my-project --out project.edn
ygg maintenance schedule project.edn --id sync --every-minutes 10 --query-index --no-check --no-enqueue
ygg maintenance schedule project.edn --id check --every-minutes 60 --check --enqueue
ygg maintenance enable project.edn
ygg status
```

When server-backed sync enqueues work and the project has
`[:maintenance :worker]`, the server invokes the worker in-process. Disabled
maintenance, disabled workers, missing API keys, leases, per-run item caps, and
repeated executor failure backoff are handled by the worker.
