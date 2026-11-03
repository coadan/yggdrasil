import importlib.machinery
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import unittest


def load_worker():
    path = pathlib.Path(__file__).with_name("ygg-rerank-local")
    loader = importlib.machinery.SourceFileLoader("ygg_rerank_local", str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


worker = load_worker()


def run(messages, backend="term-overlap-test"):
    environment = dict(os.environ)
    environment["YGG_LOCAL_RERANK_BACKEND"] = backend
    return subprocess.run(
        [sys.executable, str(pathlib.Path(__file__).with_name("ygg-rerank-local"))],
        input="\n".join(json.dumps(message) for message in messages) + "\n",
        text=True,
        capture_output=True,
        env=environment,
    )


HELLO = {"type": "hello", "schema": worker.SCHEMA, "model": "protocol-fixture"}


class LocalRerankWorkerTest(unittest.TestCase):
    def test_protocol_lifecycle_is_dependency_free_with_test_backend(self):
        process = run(
            [
                HELLO,
                {
                    "type": "rerank",
                    "requestId": "1",
                    "model": "protocol-fixture",
                    "query": "retry budget",
                    "inputs": [
                        {"id": "a", "text": "retry the budget"},
                        {"id": "b", "text": "unrelated"},
                    ],
                },
                {"type": "end"},
            ]
        )
        self.assertEqual(process.returncode, 0, process.stderr)
        lines = [json.loads(line) for line in process.stdout.splitlines()]
        self.assertEqual(lines[0], {"type": "ready", "schema": worker.SCHEMA})
        self.assertEqual(
            lines[1],
            {
                "type": "result",
                "requestId": "1",
                "scores": [{"id": "a", "score": 2.0}, {"id": "b", "score": 0.0}],
            },
        )
        self.assertEqual(lines[2], {"type": "summary", "requests": 1, "inputs": 2})

    def test_rejects_invalid_requests(self):
        for request in [
            {"type": "rerank", "requestId": "1", "model": "other", "query": "q", "inputs": []},
            {"type": "rerank", "requestId": "1", "model": "protocol-fixture", "query": "", "inputs": []},
            {
                "type": "rerank",
                "requestId": "1",
                "model": "protocol-fixture",
                "query": "q",
                "inputs": [{"id": "a", "text": "x"}, {"id": "a", "text": "y"}],
            },
        ]:
            process = run([HELLO, request])
            self.assertEqual(process.returncode, 3)
            self.assertIn("ygg-rerank-local:", process.stderr)

    def test_unknown_backend_fails_before_ready(self):
        process = run([HELLO], backend="missing")
        self.assertEqual(process.returncode, 3)
        self.assertEqual(process.stdout, "")


if __name__ == "__main__":
    unittest.main()
