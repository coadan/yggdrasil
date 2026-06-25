import contextlib
import importlib.util
import io
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
    def test_server_command_routes_to_matching_server_op(self):
        client = load_client()
        cases = [
            (["ygg", "status", "--json"], ("status", ["--json"])),
            (["ygg", "query", "needle"], ("query", ["needle"])),
        ]

        for argv, expected_request in cases:
            with self.subTest(argv=argv):
                requests = []

                def capture_request(op, args):
                    requests.append((op, args))
                    return {"exit": 0, "out": "ok\n", "err": ""}

                client.request = capture_request
                out = io.StringIO()
                with contextlib.redirect_stdout(out):
                    exit_code = client.main(argv)

                self.assertEqual(0, exit_code)
                self.assertEqual("ok\n", out.getvalue())
                self.assertEqual([expected_request], requests)

    def test_sync_command_routes_to_sync_server_ops(self):
        client = load_client()
        cases = [
            (["ygg", "sync", "project.edn"], ("sync", ["project.edn"])),
            (["ygg", "sync", "work", "pull"], ("sync.work", ["pull"])),
        ]

        for argv, expected_request in cases:
            with self.subTest(argv=argv):
                requests = []

                def capture_request(op, args):
                    requests.append((op, args))
                    return {"exit": 0, "out": "ok\n", "err": ""}

                client.request = capture_request
                with contextlib.redirect_stdout(io.StringIO()):
                    exit_code = client.main(argv)

                self.assertEqual(0, exit_code)
                self.assertEqual([expected_request], requests)

    def test_removed_command_is_rejected_without_server_request(self):
        client = load_client()

        def fail_request(*_args, **_kwargs):
            raise AssertionError("removed command should not contact server")

        client.request = fail_request
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            exit_code = client.main(["ygg", "graph"])

        self.assertEqual(2, exit_code)
        self.assertEqual("Unknown command: graph\n", err.getvalue())

    def test_unknown_command_is_rejected_without_server_request(self):
        client = load_client()

        def fail_request(*_args, **_kwargs):
            raise AssertionError("unknown command should not contact server")

        client.request = fail_request
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            exit_code = client.main(["ygg", "bogus"])

        self.assertEqual(2, exit_code)
        self.assertEqual("Unknown command: bogus\n", err.getvalue())


if __name__ == "__main__":
    unittest.main()
