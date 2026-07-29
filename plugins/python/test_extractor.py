import importlib.machinery
import importlib.util
import pathlib
import unittest


def load_extractor():
    path = pathlib.Path(__file__).with_name("ygg-extract-python")
    loader = importlib.machinery.SourceFileLoader("ygg_extract_python", str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


extractor = load_extractor()


class ExtractorTest(unittest.TestCase):
    def test_definitions_methods_and_imports(self):
        records, diagnostics = extractor.extract(
            """import os
from .panels import Panel

class PanelService:
    def load_panel(self):
        return Panel()

    async def refresh(self):
        return None

def build_service():
    return PanelService()
""",
            "services/panels.py",
        )
        self.assertEqual([], diagnostics)
        facts = {(item["kind"], item["title"]): item for item in records}
        self.assertEqual(1, facts[("python-import", "os")]["startLine"])
        self.assertEqual(2, facts[("python-import", ".panels")]["startLine"])
        self.assertEqual(4, facts[("python-class", "PanelService")]["startLine"])
        self.assertEqual(5, facts[("python-method", "PanelService.load_panel")]["startLine"])
        self.assertTrue(facts[("python-method", "PanelService.refresh")]["metadata"]["async"])
        self.assertEqual(11, facts[("python-function", "build_service")]["startLine"])

    def test_syntax_diagnostic(self):
        records, diagnostics = extractor.extract("def broken(:\n", "broken.py")
        self.assertEqual([], records)
        self.assertIn("line 1", diagnostics[0]["message"])


if __name__ == "__main__":
    unittest.main()
