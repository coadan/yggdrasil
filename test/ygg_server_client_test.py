import contextlib
import importlib.util
import io
import json
import os
import pathlib
import tempfile
import threading
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
CLIENT_PATH = ROOT / "scripts" / "ygg-server-client.py"


def load_client():
    spec = importlib.util.spec_from_file_location("ygg_server_client", CLIENT_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ServerClientRoutingTest(unittest.TestCase):
    def setUp(self):
        self._env_snapshot = os.environ.copy()
        self._cwd_snapshot = os.getcwd()

    def tearDown(self):
        os.chdir(self._cwd_snapshot)
        os.environ.clear()
        os.environ.update(self._env_snapshot)

    @contextlib.contextmanager
    def temporary_cwd(self):
        with tempfile.TemporaryDirectory() as root:
            os.chdir(root)
            yield pathlib.Path(root)

    def test_timeout_env_parsing_keeps_distinct_zero_semantics(self):
        client = load_client()

        os.environ["YGG_SERVER_REQUEST_TIMEOUT_MS"] = "0"
        os.environ["YGG_SERVER_CONNECT_TIMEOUT_MS"] = "0"
        os.environ["YGG_QUERY_FALLBACK_AFTER_MS"] = "0"
        self.assertIsNone(client.request_timeout_seconds())
        self.assertIsNone(client.response_timeout_seconds("query", []))
        self.assertEqual(0, client.connect_timeout_ms())

        os.environ["YGG_SERVER_REQUEST_TIMEOUT_MS"] = "2500"
        os.environ["YGG_SERVER_CONNECT_TIMEOUT_MS"] = "2500"
        os.environ["YGG_QUERY_FALLBACK_AFTER_MS"] = "900"
        self.assertEqual(2.5, client.request_timeout_seconds())
        self.assertEqual(0.9, client.response_timeout_seconds("query", []))
        self.assertEqual(2500, client.connect_timeout_ms())

    def test_query_latency_bound_is_larger_for_expanded_output(self):
        client = load_client()

        self.assertEqual(
            client.DEFAULT_QUERY_FALLBACK_AFTER_MS,
            client.query_fallback_after_ms([]),
        )
        self.assertEqual(
            client.DEFAULT_EXPANDED_QUERY_FALLBACK_AFTER_MS,
            client.query_fallback_after_ms(["--output", "full"]),
        )
        self.assertEqual(
            client.DEFAULT_EXPANDED_QUERY_FALLBACK_AFTER_MS / 1000.0,
            client.response_timeout_seconds("query", ["--output", "evidence"]),
        )
        self.assertEqual(
            client.DEFAULT_QUERY_HEDGE_AFTER_MS,
            client.query_hedge_after_ms([]),
        )
        self.assertEqual(
            client.DEFAULT_EXPANDED_QUERY_HEDGE_AFTER_MS,
            client.query_hedge_after_ms(["--output", "full"]),
        )
        self.assertEqual(
            client.DEFAULT_ACKNOWLEDGED_QUERY_HEDGE_AFTER_MS,
            client.acknowledged_query_hedge_after_ms([]),
        )
        self.assertEqual(
            client.DEFAULT_ACKNOWLEDGED_EXPANDED_QUERY_HEDGE_AFTER_MS,
            client.acknowledged_query_hedge_after_ms(["--output", "evidence"]),
        )

    def test_invalid_timeout_env_values_use_defaults(self):
        client = load_client()

        os.environ["YGG_SERVER_REQUEST_TIMEOUT_MS"] = "invalid"
        os.environ["YGG_SERVER_CONNECT_TIMEOUT_MS"] = "invalid"
        self.assertEqual(client.DEFAULT_REQUEST_TIMEOUT_MS / 1000.0,
                         client.request_timeout_seconds())
        self.assertEqual(client.DEFAULT_CONNECT_TIMEOUT_MS,
                         client.connect_timeout_ms())

    def test_bench_agent_baseline_uses_longer_default_request_timeout(self):
        client = load_client()

        self.assertEqual(
            client.DEFAULT_BENCH_AGENT_BASELINE_REQUEST_TIMEOUT_MS / 1000.0,
            client.request_timeout_seconds(
                "bench",
                ["agent-baseline", "benchmarks/historical-replay-full.edn"],
            ),
        )
        self.assertEqual(
            client.DEFAULT_REQUEST_TIMEOUT_MS / 1000.0,
            client.request_timeout_seconds("bench", ["agent-check"]),
        )

        os.environ["YGG_SERVER_REQUEST_TIMEOUT_MS"] = "2500"
        self.assertEqual(
            2.5,
            client.request_timeout_seconds(
                "bench",
                ["agent-baseline", "benchmarks/historical-replay-full.edn"],
            ),
        )

    def test_unavailable_message_points_to_existing_cwd_project_config(self):
        client = load_client()

        with self.temporary_cwd() as root:
            os.environ.pop("YGG_PROJECT_ID", None)
            os.environ["YGG_PROJECTS_FILE"] = str(root / "missing-registry.edn")
            (root / "project.edn").write_text("{:id \"demo\" :repos []}\n", encoding="utf-8")

            message = client.unavailable_message("status", [])

        self.assertIn("Yggdrasil server is not reachable at 127.0.0.1:62121.", message)
        self.assertIn("Project config exists at `project.edn`.", message)
        self.assertIn("Run `ygg start`, then retry `ygg status`.", message)

    def test_unavailable_message_points_to_repo_local_project_ref(self):
        client = load_client()

        with self.temporary_cwd() as root:
            os.environ.pop("YGG_PROJECT_ID", None)
            os.environ["YGG_PROJECTS_FILE"] = str(root / "missing-registry.edn")
            ref_dir = root / ".ygg"
            ref_dir.mkdir()
            (ref_dir / "project.edn").write_text(
                "{:schema \"ygg.project-ref/v1\" :project-id \"demo\"}\n",
                encoding="utf-8",
            )

            message = client.unavailable_message("query", ["needle"])

        self.assertIn("Repo-local project reference exists at `.ygg/project.edn`.", message)
        self.assertIn("Run `ygg start`, then retry `ygg query`.", message)

    def test_unavailable_message_uses_selected_project_id(self):
        client = load_client()

        with self.temporary_cwd() as root:
            os.environ["YGG_PROJECT_ID"] = "env-demo"
            os.environ["YGG_PROJECTS_FILE"] = str(root / "missing-registry.edn")

            env_message = client.unavailable_message("status", [])
            arg_message = client.unavailable_message("status", ["--project", "arg-demo"])

        self.assertIn("Project `env-demo` was selected via `YGG_PROJECT_ID`.", env_message)
        self.assertIn("Project `arg-demo` was selected via `--project`.", arg_message)

    def test_unavailable_message_reports_requested_missing_config(self):
        client = load_client()

        with self.temporary_cwd() as root:
            os.environ.pop("YGG_PROJECT_ID", None)
            os.environ["YGG_PROJECTS_FILE"] = str(root / "missing-registry.edn")

            message = client.unavailable_message("sync", ["missing.edn", "--check"])

        self.assertIn("Project config `missing.edn` was requested but does not exist.", message)
        self.assertIn(
            "Create it with `ygg init <repo-root> --project <id> --out missing.edn`",
            message,
        )

    def test_unavailable_message_without_local_state_recommends_init(self):
        client = load_client()

        with self.temporary_cwd() as root:
            os.environ.pop("YGG_PROJECT_ID", None)
            os.environ["YGG_PROJECTS_FILE"] = str(root / "missing-registry.edn")

            message = client.unavailable_message("status", [])

        self.assertIn("No project config or repo-local `.ygg/project.edn` was found", message)
        self.assertIn("`ygg init <repo-root> --project <id> --sync`", message)

    def test_server_request_writes_state_aware_unavailable_message(self):
        client = load_client()

        def unavailable_request(op, args, **kwargs):
            return None

        client.request = unavailable_request
        with self.temporary_cwd() as root:
            os.environ.pop("YGG_PROJECT_ID", None)
            os.environ["YGG_PROJECTS_FILE"] = str(root / "missing-registry.edn")
            (root / "project.edn").write_text("{:id \"demo\" :repos []}\n", encoding="utf-8")
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                exit_code = client.main(["ygg", "status"])

        self.assertEqual(client.UNAVAILABLE, exit_code)
        self.assertIn("Project config exists at `project.edn`.", err.getvalue())

    def test_mcp_unavailable_json_hint_matches_state_aware_hint(self):
        client = load_client()
        old_stdin = client.sys.stdin

        def unavailable_request(op, args, **kwargs):
            return None

        client.request = unavailable_request
        with self.temporary_cwd() as root:
            os.environ.pop("YGG_PROJECT_ID", None)
            os.environ["YGG_PROJECTS_FILE"] = str(root / "missing-registry.edn")
            client.sys.stdin = io.StringIO(
                json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}) + "\n"
            )
            try:
                out = io.StringIO()
                err = io.StringIO()
                with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                    exit_code = client.main(["ygg", "mcp"])
            finally:
                client.sys.stdin = old_stdin

        self.assertEqual(client.UNAVAILABLE, exit_code)
        body = json.loads(out.getvalue())
        self.assertEqual("Yggdrasil server is not reachable.", body["error"]["message"])
        self.assertIn(
            "No project config or repo-local `.ygg/project.edn` was found",
            body["error"]["data"]["hint"],
        )
        self.assertIn("No project config or repo-local `.ygg/project.edn` was found",
                      err.getvalue())

    def test_server_command_routes_to_matching_server_op(self):
        client = load_client()
        os.environ["YGG_QUERY_HEDGE_AFTER_MS"] = "0"
        cases = [
            (["ygg", "status", "--json"], ("status", ["--json"]),
             {"stream": False, "render_progress": False}),
            (["ygg", "query", "needle"], ("query", ["needle"]),
             {"stream": True, "render_progress": True}),
            (["ygg", "graph"], ("graph", []),
             {"stream": False, "render_progress": False}),
        ]

        for argv, expected_request, expected_options in cases:
            with self.subTest(argv=argv):
                requests = []

                def capture_request(op, args, **kwargs):
                    requests.append((op, args, kwargs))
                    return {"exit": 0, "out": "ok\n", "err": ""}

                client.request = capture_request
                out = io.StringIO()
                with contextlib.redirect_stdout(out):
                    exit_code = client.main(argv)

                self.assertEqual(0, exit_code)
                self.assertEqual("ok\n", out.getvalue())
                self.assertEqual([(expected_request[0], expected_request[1],
                                   expected_options)],
                                 requests)

    def test_query_json_streams_progress_unless_explicitly_suppressed(self):
        client = load_client()
        os.environ["YGG_QUERY_HEDGE_AFTER_MS"] = "0"
        cases = [
            (["needle", "--json"], True),
            (["needle", "--json", "--no-progress"], False),
        ]

        for args, expected_render_progress in cases:
            with self.subTest(args=args):
                requests = []

                def capture_request(op, request_args, **kwargs):
                    requests.append((op, request_args, kwargs))
                    return {"exit": 0, "out": "{}\n", "err": ""}

                client.request = capture_request
                with contextlib.redirect_stdout(io.StringIO()):
                    exit_code = client.main(["ygg", "query", *args])

                self.assertEqual(0, exit_code)
                self.assertTrue(requests[0][2]["stream"])
                self.assertEqual(expected_render_progress,
                                 requests[0][2]["render_progress"])

    def test_slow_query_hedges_to_filesystem_without_waiting_for_server_timeout(self):
        client = load_client()
        release = threading.Event()
        request_started = threading.Event()
        fallback_reasons = []
        os.environ["YGG_QUERY_HEDGE_AFTER_MS"] = "10"

        def slow_request(*_args, **_kwargs):
            request_started.set()
            release.wait(1)
            return {"exit": 0, "out": "late enriched\n", "err": ""}

        client.request = slow_request
        client.filesystem_query_response = lambda _args, reason: (
            fallback_reasons.append(reason)
            or {"exit": 0, "out": "hedged filesystem\n", "err": ""}
        )
        started = client.time.monotonic()
        try:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                exit_code = client.main(["ygg", "query", "needle", "--no-progress"])
        finally:
            release.set()

        self.assertTrue(request_started.is_set())
        self.assertEqual(0, exit_code)
        self.assertEqual("hedged filesystem\n", out.getvalue())
        self.assertEqual(["query-hedge"], fallback_reasons)
        self.assertLess(client.time.monotonic() - started, 0.5)

    def test_early_hedge_uses_filesystem_scope_from_accepted_frame(self):
        client = load_client()
        release = threading.Event()
        os.environ["YGG_QUERY_HEDGE_AFTER_MS"] = "5"
        with tempfile.TemporaryDirectory() as root:
            repo = pathlib.Path(root) / "repo"
            repo.mkdir()

            def acknowledged_request(*_args, **kwargs):
                kwargs["on_frame"]({
                    "type": "accepted",
                    "operation": "query",
                    "filesystemHandoff": {
                        "schema": client.FILESYSTEM_HANDOFF_SCHEMA,
                        "reason": "query-hedge",
                        "repos": [{"id": "app", "root": str(repo)}],
                    },
                })
                release.wait(1)
                return {"exit": 0, "out": "late enriched\n", "err": ""}

            client.request = acknowledged_request
            fallback_calls = []
            client.filesystem_query_response = (
                lambda args, reason, repositories: fallback_calls.append(
                    (args, reason, repositories)
                ) or {"exit": 0, "out": "scoped filesystem\n", "err": ""}
            )
            try:
                out = io.StringIO()
                with contextlib.redirect_stdout(out):
                    exit_code = client.main([
                        "ygg", "query", "needle", "--project", "demo", "--no-progress"
                    ])
            finally:
                release.set()

        self.assertEqual(0, exit_code)
        self.assertEqual("scoped filesystem\n", out.getvalue())
        self.assertEqual("query-hedge", fallback_calls[0][1])
        self.assertEqual(
            [("app", repo.resolve())],
            [(row["id"], row["root"]) for row in fallback_calls[0][2]],
        )

    def test_early_hedge_preserves_active_operation_reason(self):
        client = load_client()
        pending = {
            "state": {
                "filesystem-handoff": {
                    "schema": client.FILESYSTEM_HANDOFF_SCHEMA,
                    "reason": "active-indexing",
                }
            }
        }

        self.assertEqual(
            "active-indexing",
            client.pending_query_filesystem_reason(pending),
        )

    def test_query_prefers_enriched_response_that_finishes_during_hedge_search(self):
        client = load_client()
        os.environ["YGG_QUERY_HEDGE_AFTER_MS"] = "5"

        def nearly_ready_request(*_args, **_kwargs):
            client.time.sleep(0.02)
            return {"exit": 0, "out": "enriched\n", "err": ""}

        def slower_filesystem(_args, _reason):
            client.time.sleep(0.04)
            return {"exit": 0, "out": "filesystem\n", "err": ""}

        client.request = nearly_ready_request
        client.filesystem_query_response = slower_filesystem
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            exit_code = client.main(["ygg", "query", "needle", "--no-progress"])

        self.assertEqual(0, exit_code)
        self.assertEqual("enriched\n", out.getvalue())

    def test_fast_hedged_query_renders_buffered_server_progress(self):
        client = load_client()
        os.environ["YGG_QUERY_HEDGE_AFTER_MS"] = "100"

        def ready_request(*_args, **kwargs):
            kwargs["on_frame"]({
                "type": "accepted",
                "operation": "query",
            })
            kwargs["on_frame"]({
                "type": "progress",
                "operation": "query",
                "message": "ready evidence",
            })
            return {"exit": 0, "out": "enriched\n", "err": ""}

        client.request = ready_request
        out = io.StringIO()
        err = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            exit_code = client.main(["ygg", "query", "needle"])

        self.assertEqual(0, exit_code)
        self.assertEqual("enriched\n", out.getvalue())
        self.assertIn("ready evidence", err.getvalue())

    def test_acknowledged_query_gets_longer_grace_without_duplicate_search(self):
        client = load_client()
        os.environ["YGG_QUERY_HEDGE_AFTER_MS"] = "5"
        os.environ["YGG_QUERY_ACKNOWLEDGED_HEDGE_AFTER_MS"] = "100"

        def acknowledged_request(*_args, **kwargs):
            kwargs["on_frame"]({"type": "accepted", "operation": "query"})
            client.time.sleep(0.03)
            return {"exit": 0, "out": "acknowledged enriched\n", "err": ""}

        client.request = acknowledged_request
        client.filesystem_query_response = lambda *_args: self.fail(
            "acknowledged request should finish inside its grace period"
        )
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            exit_code = client.main(["ygg", "query", "needle", "--no-progress"])

        self.assertEqual(0, exit_code)
        self.assertEqual("acknowledged enriched\n", out.getvalue())

    def test_query_connection_is_non_blocking_when_server_is_cold(self):
        client = load_client()
        calls = []

        def fake_request_once(op, args, **kwargs):
            calls.append((op, args, kwargs))
            return {"exit": 0, "out": "ok\n", "err": ""}

        client.request_once = fake_request_once
        response = client.request("query", ["needle"])

        self.assertEqual("ok\n", response["out"])
        self.assertEqual(0, calls[0][2]["connect_timeout_override_ms"])

    def test_query_request_declares_client_owned_filesystem_fallback(self):
        client = load_client()
        sent = []

        class ResponseSocket:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def settimeout(self, _timeout):
                return None

            def sendall(self, payload):
                sent.append(json.loads(payload))

            def makefile(self, *_args, **_kwargs):
                return iter(['{"exit":0,"out":"ok\\n","err":""}\n'])

        client.connect_socket = lambda *_args, **_kwargs: ResponseSocket()

        response = client.request_once("query", ["needle"])

        self.assertEqual("ok\n", response["out"])
        self.assertEqual("client", sent[0]["filesystemFallbackOwner"])

    def test_active_indexing_handoff_routes_registered_repositories_to_fallback(self):
        client = load_client()
        os.environ["YGG_QUERY_HEDGE_AFTER_MS"] = "0"
        with tempfile.TemporaryDirectory() as root:
            repo_a = pathlib.Path(root) / "repo-a"
            repo_b = pathlib.Path(root) / "repo-b"
            repo_a.mkdir()
            repo_b.mkdir()
            client.request = lambda *_args, **_kwargs: {
                "ok": True,
                "exit": 0,
                "out": "",
                "err": "",
                "data": {
                    "schema": client.FILESYSTEM_HANDOFF_SCHEMA,
                    "reason": "active-indexing",
                    "repos": [
                        {"id": "a", "root": str(repo_a)},
                        {"id": "b", "root": str(repo_b)},
                    ],
                },
            }
            fallback_calls = []
            client.filesystem_query_response = (
                lambda args, reason, repositories: fallback_calls.append(
                    (args, reason, repositories)
                ) or {"exit": 0, "out": "handoff fallback\n", "err": ""}
            )
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                exit_code = client.main(["ygg", "query", "needle"])

        self.assertEqual(0, exit_code)
        self.assertEqual("handoff fallback\n", out.getvalue())
        self.assertEqual("active-indexing", fallback_calls[0][1])
        self.assertEqual(
            [("a", repo_a.resolve()), ("b", repo_b.resolve())],
            [(row["id"], row["root"]) for row in fallback_calls[0][2]],
        )

    def test_query_socket_timeout_returns_structured_fallback_reason(self):
        client = load_client()

        class TimeoutReader:
            def __iter__(self):
                return self

            def __next__(self):
                raise client.socket.timeout()

        class TimeoutSocket:
            timeout = None

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def settimeout(self, timeout):
                self.timeout = timeout

            def sendall(self, _payload):
                return None

            def makefile(self, *_args, **_kwargs):
                return TimeoutReader()

        sock = TimeoutSocket()
        client.connect_socket = lambda *_args, **_kwargs: sock
        os.environ["YGG_QUERY_FALLBACK_AFTER_MS"] = "125"

        response = client.request_once("query", ["needle"])

        self.assertEqual(0.125, sock.timeout)
        self.assertEqual("query-timeout", response["data"]["reason"])
        self.assertEqual(125, response["data"]["timeoutMs"])
        self.assertEqual("", response["err"])

    def test_filesystem_query_patterns_match_mechanical_server_contract(self):
        client = load_client()

        patterns = client.filesystem_query_patterns(
            "where is auth handled src/auth_handler.clj",
            ["--literal", "AUTH_URL", "--symbol", "AuthHandler"],
        )

        self.assertEqual(
            ["AUTH_URL", "AuthHandler", "src/auth_handler.clj", "handled", "where", "auth"],
            patterns,
        )

    def test_filesystem_pipe_capture_drains_output_and_keeps_complete_rows(self):
        client = load_client()
        with tempfile.TemporaryDirectory() as root:
            fake_rg = pathlib.Path(root) / "fake-rg"
            fake_rg.write_text(
                "#!/usr/bin/env python3\n"
                "for index in range(10000):\n"
                "    print(f'file-{index}:1')\n",
                encoding="utf-8",
            )
            fake_rg.chmod(0o755)
            os.environ["YGG_RG_BIN"] = str(fake_rg)
            os.environ["YGG_FILESYSTEM_QUERY_MAX_STDOUT_BYTES"] = "12"

            result = client.run_filesystem_search(pathlib.Path(root), ["needle"])

        self.assertEqual([{"path": "file-0", "count": 1}], result["matches"])
        self.assertTrue(result["truncated?"])
        self.assertFalse(result["timeout?"])
        self.assertIn(
            "stdout-truncated",
            {diagnostic["kind"] for diagnostic in result["diagnostics"]},
        )

    def test_filesystem_pipe_capture_kills_process_at_deadline(self):
        client = load_client()
        with tempfile.TemporaryDirectory() as root:
            fake_rg = pathlib.Path(root) / "fake-rg"
            fake_rg.write_text(
                "#!/usr/bin/env python3\nimport time\ntime.sleep(1)\n",
                encoding="utf-8",
            )
            fake_rg.chmod(0o755)
            os.environ["YGG_RG_BIN"] = str(fake_rg)
            os.environ["YGG_FILESYSTEM_QUERY_TIMEOUT_MS"] = "20"

            started = client.time.monotonic()
            result = client.run_filesystem_search(pathlib.Path(root), ["needle"])
            elapsed = client.time.monotonic() - started

        self.assertTrue(result["timeout?"])
        self.assertLess(elapsed, 0.5)
        self.assertIn(
            "timeout",
            {diagnostic["kind"] for diagnostic in result["diagnostics"]},
        )

    def test_filesystem_query_packet_is_explicitly_degraded(self):
        client = load_client()
        client.filesystem_query_root = lambda: pathlib.Path("/workspace/demo")
        client.run_filesystem_search = lambda root, patterns: {
            "elapsed-ms": 8,
            "matches": [
                {"path": "src/auth.py", "count": 4},
                {"path": "src/routes.py", "count": 1},
            ],
            "diagnostics": [{"kind": "stdout-truncated"}],
            "timeout?": False,
            "truncated?": True,
        }

        packet = client.filesystem_query_packet(
            ["where is auth handled", "--project", "demo", "--json"],
            "server-starting",
        )

        self.assertEqual(client.FILESYSTEM_QUERY_SCHEMA, packet["schema"])
        self.assertEqual("filesystem", packet["retrieval"]["effective"])
        self.assertTrue(packet["retrieval"]["fallback?"])
        self.assertEqual("server-starting", packet["degradation"]["reason"])
        self.assertEqual("filesystem", packet["degradation"]["fallback"])
        self.assertEqual(["src/auth.py", "src/routes.py"], [
            result["resolvedPath"] for result in packet["results"]
        ])
        instrumentation = packet["search"]["instrumentation"]
        self.assertEqual(1, instrumentation["filesystem-processes"])
        self.assertEqual(8, instrumentation["filesystem-search-ms"])
        self.assertEqual(
            {"stdout-truncated": 1},
            instrumentation["filesystem-diagnostic-kinds"],
        )
        self.assertTrue(instrumentation["filesystem-incomplete?"])
        self.assertEqual(1, instrumentation["filesystem-repos"])
        self.assertFalse(instrumentation["filesystem-handoff?"])
        self.assertIn(client.FILESYSTEM_INCOMPLETE_WARNING, packet["warnings"])

    def test_filesystem_handoff_searches_registered_roots_in_one_process(self):
        client = load_client()
        with tempfile.TemporaryDirectory() as root:
            repo_a = pathlib.Path(root) / "repo-a"
            repo_b = repo_a / "nested-repo"
            repo_b.mkdir(parents=True)
            calls = []

            def fake_search(cwd, patterns, search_paths):
                calls.append((cwd, patterns, search_paths))
                return {
                    "elapsed-ms": 4,
                    "matches": [
                        {
                            "path": str((repo_a / "src" / "auth.py").resolve()),
                            "count": 3,
                        },
                        {
                            "path": str((repo_b / "lib" / "auth.py").resolve()),
                            "count": 2,
                        },
                        {
                            "path": str((repo_b / "lib" / "auth.py").resolve()),
                            "count": 2,
                        },
                    ],
                    "diagnostics": [],
                    "process-attempted?": True,
                    "timeout?": False,
                    "truncated?": False,
                }

            client.filesystem_query_root = lambda: pathlib.Path(root)
            client.run_filesystem_search = fake_search
            repositories = [
                {"id": "a", "root": repo_a},
                {"id": "b", "root": repo_b},
            ]

            packet = client.filesystem_query_packet(
                ["auth", "--json"],
                "active-indexing",
                repositories,
            )

        self.assertEqual(
            [("a", "src/auth.py"), ("b", "lib/auth.py")],
            [(row["repo"], row["resolvedPath"]) for row in packet["results"]],
        )
        self.assertEqual(
            [str(repo_b.resolve()), str(repo_a.resolve())],
            calls[0][2],
        )
        instrumentation = packet["search"]["instrumentation"]
        self.assertEqual(1, instrumentation["filesystem-processes"])
        self.assertEqual(2, instrumentation["filesystem-repos"])
        self.assertTrue(instrumentation["filesystem-handoff?"])

    def test_plain_filesystem_timeout_warns_that_results_are_incomplete(self):
        client = load_client()
        client.filesystem_query_root = lambda: pathlib.Path("/workspace/demo")
        client.run_filesystem_search = lambda root, patterns: {
            "elapsed-ms": 1500,
            "matches": [],
            "diagnostics": [{"kind": "timeout"}],
            "process-attempted?": True,
            "timeout?": True,
            "truncated?": False,
        }

        response = client.filesystem_query_response(["needle"], "server-unavailable")

        self.assertEqual(0, response["exit"])
        self.assertIn("No filesystem query results.", response["out"])
        self.assertIn(client.FILESYSTEM_INCOMPLETE_WARNING, response["err"])

    def test_query_routes_to_filesystem_when_server_is_unavailable(self):
        client = load_client()
        client.request = lambda *args, **kwargs: None
        fallback_calls = []
        start_calls = []

        def fake_fallback(args, reason):
            fallback_calls.append((args, reason))
            return {"exit": 0, "out": "filesystem result\n", "err": "degraded\n"}

        client.filesystem_query_response = fake_fallback
        client.start_server_in_background = lambda: start_calls.append(True)
        out = io.StringIO()
        err = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            exit_code = client.main(["ygg", "query", "needle"])

        self.assertEqual(0, exit_code)
        self.assertEqual("filesystem result\n", out.getvalue())
        self.assertEqual("degraded\n", err.getvalue())
        self.assertEqual([(["needle"], "server-unavailable")], fallback_calls)
        self.assertEqual([True], start_calls)

    def test_background_server_start_is_deduplicated_during_cooldown(self):
        client = load_client()
        starts = []

        class StartedProcess:
            pass

        with tempfile.TemporaryDirectory() as root:
            os.environ["YGG_SERVER_LOG"] = str(pathlib.Path(root) / "server.log")
            with mock.patch.object(
                client.subprocess,
                "Popen",
                side_effect=lambda *args, **kwargs: starts.append(
                    (args, kwargs)
                ) or StartedProcess(),
            ):
                first = client.start_server_in_background()
                second = client.start_server_in_background()

        self.assertEqual("requested", first["status"])
        self.assertEqual("already-requested", second["status"])
        self.assertEqual(1, len(starts))
        self.assertEqual([str(client.YGG_BIN), "start"], list(starts[0][0][0]))
        self.assertTrue(starts[0][1]["start_new_session"])

    def test_background_server_start_can_be_disabled_for_diagnostics(self):
        client = load_client()
        os.environ["YGG_QUERY_AUTO_START"] = "0"
        client.claim_server_start = lambda: self.fail("start should remain disabled")

        self.assertEqual("disabled", client.start_server_in_background()["status"])

    def test_query_routes_to_filesystem_without_retrying_starting_server(self):
        client = load_client()
        request_calls = []

        def fake_request(op, args, **kwargs):
            request_calls.append((op, args, kwargs))
            return {"ok": False, "data": {"reason": "server-starting"}}

        client.request = fake_request
        client.filesystem_query_response = lambda args, reason: {
            "exit": 0,
            "out": f"{reason}\n",
            "err": "",
        }
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            exit_code = client.main(["ygg", "query", "needle"])

        self.assertEqual(0, exit_code)
        self.assertEqual("server-starting\n", out.getvalue())
        self.assertEqual(1, len(request_calls))

    def test_query_routes_to_filesystem_when_enrichment_exceeds_latency_bound(self):
        client = load_client()
        client.request = lambda *args, **kwargs: {
            "exit": 124,
            "out": "",
            "err": "",
            "data": {
                "reason": "query-timeout",
                "elapsedMs": 1501,
                "timeoutMs": 1500,
            },
        }
        fallback_calls = []

        def fake_fallback(args, reason):
            fallback_calls.append((args, reason))
            return {"exit": 0, "out": "bounded fallback\n", "err": ""}

        client.filesystem_query_response = fake_fallback
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            exit_code = client.main(["ygg", "query", "needle"])

        self.assertEqual(0, exit_code)
        self.assertEqual("bounded fallback\n", out.getvalue())
        self.assertEqual([(["needle"], "query-timeout")], fallback_calls)

    def test_query_routes_to_filesystem_when_graph_storage_is_unavailable(self):
        client = load_client()
        client.request = lambda *args, **kwargs: {
            "ok": False,
            "exit": 75,
            "out": "",
            "err": "Yggdrasil graph storage is unavailable.\n",
            "data": {
                "reason": "storage-unavailable",
                "storagePath": "/storage/demo",
            },
        }
        fallback_calls = []
        client.filesystem_query_response = lambda args, reason: fallback_calls.append(
            (args, reason)
        ) or {"exit": 0, "out": "storage fallback\n", "err": ""}
        client.start_server_in_background = lambda: self.fail(
            "reachable service should not be restarted"
        )

        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            exit_code = client.main(["ygg", "query", "needle"])

        self.assertEqual(0, exit_code)
        self.assertEqual("storage fallback\n", out.getvalue())
        self.assertEqual([(["needle"], "storage-unavailable")], fallback_calls)

    def test_query_timeout_packet_explains_that_enrichment_continues(self):
        client = load_client()
        client.filesystem_query_root = lambda: pathlib.Path("/workspace/demo")
        client.run_filesystem_search = lambda _root, _patterns: {
            "elapsed-ms": 2,
            "matches": [],
            "diagnostics": [],
            "timeout?": False,
            "truncated?": False,
        }

        packet = client.filesystem_query_packet(["needle", "--json"], "query-timeout")

        self.assertEqual("query-timeout", packet["degradation"]["reason"])
        self.assertIn("latency bound", packet["degradation"]["message"])
        self.assertIn("continues", packet["degradation"]["message"])

    def test_request_retries_starting_health_response(self):
        client = load_client()
        calls = []
        responses = [
            {"ok": True, "data": {"status": "starting"}},
            {"ok": False, "data": {"reason": "server-starting"}},
            {"ok": True, "data": {"status": "running"}},
        ]

        def fake_request_once(op, args, **kwargs):
            calls.append((op, args, kwargs))
            return responses.pop(0)

        client.request_once = fake_request_once
        client.STARTING_RETRY_INTERVAL_SECONDS = 0.0

        response = client.request("status", ["--json"])

        self.assertEqual({"ok": True, "data": {"status": "running"}}, response)
        self.assertEqual(["status", "status", "status"],
                         [call[0] for call in calls])
        self.assertEqual(["--json"], calls[0][1])

    def test_sync_command_routes_to_sync_server_ops(self):
        client = load_client()
        cases = [
            (["ygg", "sync", "project.edn"],
             ("sync", ["project.edn"], {"stream": True, "render_progress": True})),
            (["ygg", "sync", "work", "pull"],
             ("sync", ["work", "pull"], {"stream": True, "render_progress": True})),
        ]

        for argv, expected_request in cases:
            with self.subTest(argv=argv):
                requests = []

                def capture_request(op, args, **kwargs):
                    requests.append((op, args, kwargs))
                    return {"exit": 0, "out": "ok\n", "err": ""}

                client.request = capture_request
                with contextlib.redirect_stdout(io.StringIO()):
                    exit_code = client.main(argv)

                self.assertEqual(0, exit_code)
                self.assertEqual([expected_request], requests)

    def test_sync_json_and_no_progress_suppress_client_rendering(self):
        client = load_client()
        cases = [
            ["ygg", "sync", "project.edn", "--json"],
            ["ygg", "sync", "project.edn", "--no-progress"],
        ]

        for argv in cases:
            with self.subTest(argv=argv):
                requests = []

                def capture_request(op, args, **kwargs):
                    requests.append((op, args, kwargs))
                    return {"exit": 0, "out": "", "err": ""}

                client.request = capture_request
                exit_code = client.main(argv)

                self.assertEqual(0, exit_code)
                self.assertEqual(False, requests[0][2]["render_progress"])

    def test_streaming_progress_frames_render_to_stderr(self):
        client = load_client()
        final_response = {"exit": 0, "out": "done\n", "err": ""}
        stream = io.StringIO(
            json.dumps({
                "schema": client.SERVER_FRAME_SCHEMA,
                "type": "progress",
                "operation": "sync",
                "message": "app scanned 2 files",
            }) + "\n" +
            json.dumps(final_response) + "\n"
        )

        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            response = client.read_server_response(stream, render_progress=True)

        self.assertEqual(final_response, response)
        self.assertEqual("# Sync Progress\n- app scanned 2 files\n", err.getvalue())

    def test_query_progress_frames_use_query_header(self):
        client = load_client()
        final_response = {"exit": 0, "out": "{}\n", "err": ""}
        stream = io.StringIO(
            json.dumps({
                "schema": client.SERVER_FRAME_SCHEMA,
                "type": "progress",
                "operation": "query",
                "message": "demo preparing context",
            }) + "\n" +
            json.dumps(final_response) + "\n"
        )

        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            response = client.read_server_response(stream, render_progress=True)

        self.assertEqual(final_response, response)
        self.assertEqual("# Query Progress\n- demo preparing context\n", err.getvalue())

    def test_streaming_progress_frames_can_be_ignored(self):
        client = load_client()
        final_response = {"exit": 0, "out": "done\n", "err": ""}
        stream = io.StringIO(
            json.dumps({
                "schema": client.SERVER_FRAME_SCHEMA,
                "type": "progress",
                "message": "app scanned 2 files",
            }) + "\n" +
            json.dumps(final_response) + "\n"
        )

        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            response = client.read_server_response(stream, render_progress=False)

        self.assertEqual(final_response, response)
        self.assertEqual("", err.getvalue())

    def test_unknown_server_response_is_printed(self):
        client = load_client()

        def unknown_request(op, args, **kwargs):
            return {"exit": 2, "out": "", "err": f"Unknown server op: {op}\n"}

        client.request = unknown_request
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            exit_code = client.main(["ygg", "bogus"])

        self.assertEqual(2, exit_code)
        self.assertEqual("Unknown server op: bogus\n", err.getvalue())

    def test_init_starts_server_when_unavailable_then_retries(self):
        client = load_client()
        calls = []
        started = []

        def fake_request(op, args, **kwargs):
            calls.append((op, args, kwargs))
            if op == "init" and not started:
                return None
            return {"exit": 0, "out": json.dumps({
                "schema": "ygg.init/v1",
                "project-id": "demo",
            }) + "\n", "err": ""}

        def fake_start():
            started.append(True)
            return {"status": "started", "log": "/tmp/ygg.log"}

        client.request = fake_request
        client.start_server_for_init = fake_start

        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            exit_code = client.main(["ygg", "init", "repo", "--project", "demo"])

        self.assertEqual(0, exit_code)
        self.assertEqual(["init", "init"], [call[0] for call in calls])
        self.assertEqual(["repo", "--project", "demo"], calls[0][1])
        body = json.loads(out.getvalue())
        self.assertEqual({"status": "started", "log": "/tmp/ygg.log"}, body["service"])

    def test_init_sync_requests_streamed_progress(self):
        client = load_client()
        calls = []

        def fake_request(op, args, **kwargs):
            calls.append((op, args, kwargs))
            return {"exit": 0, "out": json.dumps({
                "schema": "ygg.init/v1",
                "project-id": "demo",
            }) + "\n", "err": ""}

        client.request = fake_request
        with contextlib.redirect_stdout(io.StringIO()):
            exit_code = client.main(["ygg", "init", "repo", "--project", "demo", "--sync"])

        self.assertEqual(0, exit_code)
        self.assertEqual("init", calls[0][0])
        self.assertEqual(["repo", "--project", "demo", "--sync"], calls[0][1])
        self.assertTrue(calls[0][2]["stream"])
        self.assertTrue(calls[0][2]["render_progress"])

    def test_guided_init_translates_answers_to_noninteractive_flags(self):
        client = load_client()
        calls = []
        old_stdin = client.sys.stdin

        def fake_request(op, args, **kwargs):
            calls.append((op, args, kwargs))
            return {"exit": 0, "out": json.dumps({
                "schema": "ygg.init/v1",
                "project-id": "demo",
            }) + "\n", "err": ""}

        client.request = fake_request
        client.start_at_login = lambda args, emit=True: {"status": "enabled"}
        os.environ["YGG_INIT_INTERACTIVE"] = "1"
        client.sys.stdin = io.StringIO("\ncodex\ny\nopenrouter\ny\n")
        try:
            out = io.StringIO()
            err = io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                exit_code = client.main(["ygg", "init", "--project", "demo"])
        finally:
            client.sys.stdin = old_stdin

        self.assertEqual(0, exit_code)
        self.assertIn("Use the current directory", err.getvalue())
        self.assertEqual([
            ".",
            "--project", "demo",
            "--harness", "codex",
            "--hooks",
            "--skill",
            "--mcp",
            "--maintenance", "openrouter",
            "--sync",
        ], calls[0][1])
        self.assertTrue(calls[0][2]["stream"])
        body = json.loads(out.getvalue())
        self.assertEqual({"status": "enabled"}, body["startup"])
        self.assertEqual("ygg.init.guided/v1", body["guided"]["schema"])
        self.assertEqual("interactive", body["guided"]["mode"])

    def test_init_no_input_suppresses_guided_prompts(self):
        client = load_client()
        calls = []
        old_stdin = client.sys.stdin

        def fake_request(op, args, **kwargs):
            calls.append((op, args, kwargs))
            return {"exit": 0, "out": "{}\n", "err": ""}

        client.request = fake_request
        os.environ["YGG_INIT_INTERACTIVE"] = "1"
        client.sys.stdin = io.StringIO("codex\ny\nopenrouter\ny\n")
        try:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                exit_code = client.main(["ygg", "init", "repo", "--no-input"])
        finally:
            client.sys.stdin = old_stdin

        self.assertEqual(0, exit_code)
        self.assertEqual(["repo"], calls[0][1])

    def test_init_no_start_server_keeps_unavailable_contract(self):
        client = load_client()
        calls = []

        def fake_request(op, args, **kwargs):
            calls.append((op, args, kwargs))
            return None

        client.request = fake_request
        client.start_server_for_init = lambda: self.fail("server should not start")

        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            exit_code = client.main(["ygg", "init", "repo", "--no-start-server"])

        self.assertEqual(client.UNAVAILABLE, exit_code)
        self.assertIn("Server startup was disabled by `--no-start-server`", err.getvalue())
        self.assertIn("Run `ygg start`, then retry `ygg init`.", err.getvalue())
        self.assertEqual(["repo"], calls[0][1])

    def test_service_start_at_login_status_reports_unsupported_off_macos(self):
        client = load_client()
        client.platform.system = lambda: "Linux"

        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            with self.assertRaises(SystemExit) as raised:
                client.main(["ygg", "service", "start-at-login", "status", "--json"])

        self.assertEqual(2, raised.exception.code)
        body = json.loads(out.getvalue())
        self.assertEqual("unsupported", body["status"])
        self.assertFalse(body["supported"])


if __name__ == "__main__":
    unittest.main()
