import importlib.machinery
import importlib.util
import pathlib
import unittest


def load_extractor():
    path = pathlib.Path(__file__).with_name("ygg-extract-clojure")
    loader = importlib.machinery.SourceFileLoader("ygg_extract_clojure", str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


extractor = load_extractor()


class ExtractorTest(unittest.TestCase):
    def require_parser(self):
        try:
            from tree_sitter_language_pack import get_parser

            get_parser("clojure")
        except (ImportError, LookupError):
            self.skipTest("optional Clojure parser is not installed")

    def test_clojure_facts(self):
        self.require_parser()
        records, diagnostics = extractor.extract(
            "src/demo/core.clj",
            """(ns demo.core
  (:require
    [clojure.string :as str]
    [demo.store :as store]))

(def ^:private default-limit 20)
(defn load-user
  [id]
  (store/load id))
(defrecord User [id name])
(defmethod render :user [value] (str value))
""",
        )
        self.assertEqual([], diagnostics)
        self.assertEqual(1, len(records))
        summary = records[0]
        self.assertEqual("clojure-file", summary["kind"])
        self.assertEqual("demo.core", summary["title"])
        self.assertEqual(
            ["clojure.string", "demo.store"], summary["metadata"]["requires"]
        )
        definitions = {
            item["name"]: item["definitionKind"]
            for item in summary["metadata"]["definitions"]
        }
        self.assertEqual(
            "value",
            definitions["default-limit"],
        )
        self.assertEqual(
            "function",
            definitions["load-user"],
        )
        self.assertEqual("record", definitions["User"])
        self.assertEqual("method", definitions["render"])
        self.assertEqual(1, summary["startLine"])
        self.assertEqual(11, summary["endLine"])
        self.assertFalse(summary["metadata"]["truncated"])

    def test_parser_diagnostic_is_explicit(self):
        self.require_parser()
        _, diagnostics = extractor.extract("broken.clj", "(defn broken [")
        self.assertEqual(
            [{"message": "tree-sitter reported parse errors"}], diagnostics
        )

    def test_file_summary_bounds_parser_facts(self):
        self.require_parser()
        requires = "\n".join(
            f"    [demo.dependency-{index}]" for index in range(130)
        )
        definitions = "\n".join(
            f"(def value-{index} {index})" for index in range(130)
        )
        records, diagnostics = extractor.extract(
            "large.clj",
            f"(ns demo.large\n  (:require\n{requires}))\n{definitions}\n",
        )
        self.assertEqual([], diagnostics)
        self.assertEqual(1, len(records))
        metadata = records[0]["metadata"]
        self.assertEqual(128, len(metadata["requires"]))
        self.assertEqual(128, len(metadata["definitions"]))
        self.assertTrue(metadata["truncated"])


if __name__ == "__main__":
    unittest.main()
