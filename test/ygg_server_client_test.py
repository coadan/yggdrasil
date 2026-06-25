import contextlib
import importlib.util
import io
import json
import os
import pathlib
import unittest


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

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._env_snapshot)

    def test_timeout_env_parsing_keeps_distinct_zero_semantics(self):
        client = load_client()

        os.environ["YGG_SERVER_REQUEST_TIMEOUT_MS"] = "0"
        os.environ["YGG_SERVER_CONNECT_TIMEOUT_MS"] = "0"
        self.assertIsNone(client.request_timeout_seconds())
        self.assertEqual(0, client.connect_timeout_ms())

        os.environ["YGG_SERVER_REQUEST_TIMEOUT_MS"] = "2500"
        os.environ["YGG_SERVER_CONNECT_TIMEOUT_MS"] = "2500"
        self.assertEqual(2.5, client.request_timeout_seconds())
        self.assertEqual(2500, client.connect_timeout_ms())

    def test_invalid_timeout_env_values_use_defaults(self):
        client = load_client()

        os.environ["YGG_SERVER_REQUEST_TIMEOUT_MS"] = "invalid"
        os.environ["YGG_SERVER_CONNECT_TIMEOUT_MS"] = "invalid"
        self.assertEqual(client.DEFAULT_REQUEST_TIMEOUT_MS / 1000.0,
                         client.request_timeout_seconds())
        self.assertEqual(client.DEFAULT_CONNECT_TIMEOUT_MS,
                         client.connect_timeout_ms())

    def test_server_command_routes_to_matching_server_op(self):
        client = load_client()
        cases = [
            (["ygg", "status", "--json"], ("status", ["--json"])),
            (["ygg", "query", "needle"], ("query", ["needle"])),
            (["ygg", "graph"], ("graph", [])),
        ]

        for argv, expected_request in cases:
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
                                   {"stream": False, "render_progress": False})],
                                 requests)

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
                "message": "app scanned 2 files",
            }) + "\n" +
            json.dumps(final_response) + "\n"
        )

        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            response = client.read_server_response(stream, render_progress=True)

        self.assertEqual(final_response, response)
        self.assertEqual("# Sync Progress\n- app scanned 2 files\n", err.getvalue())

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
        self.assertIn("Run `ygg init` first", err.getvalue())
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
