import importlib.machinery
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import types
import unittest
from unittest import mock


def load_worker():
    path = pathlib.Path(__file__).with_name("ygg-embed-local")
    loader = importlib.machinery.SourceFileLoader("ygg_embed_local", str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


worker = load_worker()


class LocalEmbeddingWorkerTest(unittest.TestCase):
    def test_protocol_lifecycle_is_dependency_free_until_first_nonempty_batch(self):
        executable = pathlib.Path(__file__).with_name("ygg-embed-local")
        environment = dict(os.environ)
        environment["YGG_LOCAL_EMBEDDING_BACKEND"] = "deterministic-test"
        process = subprocess.run(
            [sys.executable, str(executable)],
            input="\n".join(
                [
                    json.dumps(
                        {
                            "type": "hello",
                            "schema": worker.SCHEMA,
                            "model": "protocol-fixture",
                            "dimensions": 8,
                        }
                    ),
                    json.dumps(
                        {
                            "type": "embed",
                            "requestId": "request-1",
                            "model": "protocol-fixture",
                            "inputs": [
                                {"id": "a", "text": "alpha"},
                                {"id": "b", "text": "beta"},
                            ],
                        }
                    ),
                    json.dumps({"type": "end"}),
                ]
            )
            + "\n",
            text=True,
            capture_output=True,
            env=environment,
            check=True,
        )
        messages = [json.loads(line) for line in process.stdout.splitlines()]
        self.assertEqual(
            {"type": "ready", "schema": worker.SCHEMA},
            messages[0],
        )
        self.assertEqual("request-1", messages[1]["requestId"])
        self.assertEqual(["a", "b"], [value["id"] for value in messages[1]["values"]])
        self.assertTrue(
            all(len(value["vector"]) == 8 for value in messages[1]["values"])
        )
        self.assertEqual(
            {"type": "summary", "batches": 1, "inputs": 2},
            messages[2],
        )

    def test_sentence_transformer_is_lazy_normalized_and_dimension_checked(self):
        calls = []

        class FakeModel:
            def __init__(self, model_name):
                calls.append(("init", model_name))

            def encode(self, texts, **options):
                calls.append(("encode", texts, options))
                return [[1.0, 0.0] for _ in texts]

        fake_module = types.ModuleType("sentence_transformers")
        fake_module.SentenceTransformer = FakeModel
        with mock.patch.dict(sys.modules, {"sentence_transformers": fake_module}):
            backend = worker.SentenceTransformerBackend("local/test-model", 2)
            self.assertEqual([], calls)
            vectors = backend.encode(["first", "second"])
            backend.encode(["third"])

        self.assertEqual([[1.0, 0.0], [1.0, 0.0]], vectors)
        self.assertEqual(("init", "local/test-model"), calls[0])
        self.assertEqual(1, sum(call[0] == "init" for call in calls))
        self.assertTrue(calls[1][2]["normalize_embeddings"])
        self.assertFalse(calls[1][2]["show_progress_bar"])

    def test_rejects_model_mismatch_and_duplicate_ids(self):
        mismatch = {
            "type": "embed",
            "requestId": "1",
            "model": "different",
            "inputs": [],
        }
        with self.assertRaisesRegex(ValueError, "does not match"):
            worker.validate_inputs(mismatch, "expected")

        duplicate = {
            "type": "embed",
            "requestId": "1",
            "model": "expected",
            "inputs": [{"id": "a", "text": "one"}, {"id": "a", "text": "two"}],
        }
        with self.assertRaisesRegex(ValueError, "duplicate"):
            worker.validate_inputs(duplicate, "expected")


if __name__ == "__main__":
    unittest.main()
