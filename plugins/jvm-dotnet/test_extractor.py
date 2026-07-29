import importlib.machinery
import importlib.util
import pathlib
import unittest


def load_extractor():
    path = pathlib.Path(__file__).with_name("ygg-extract-jvm-dotnet")
    loader = importlib.machinery.SourceFileLoader("ygg_extract_jvm_dotnet", str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


extractor = load_extractor()


class ExtractorTest(unittest.TestCase):
    def require_parser(self):
        try:
            import tree_sitter_language_pack  # noqa: F401
        except ImportError:
            self.skipTest("optional tree-sitter-language-pack is not installed")

    def test_java_facts(self):
        self.require_parser()
        records, diagnostics = extractor.extract(
            "Rich.java",
            """package demo;
import java.util.List;
record Item(String id) {}
interface Port { Result load(Input input); }
class App implements Port {
  App() {}
  public Result load(Input input) { return new Result(); }
  static class Result {}
}
class Input {}
""",
        )
        self.assertEqual([], diagnostics)
        facts = {(item["kind"], item["title"]): item for item in records}
        self.assertIn(("java-package", "demo"), facts)
        self.assertIn(("java-import", "java.util.List"), facts)
        self.assertEqual(
            "record", facts[("java-definition", "Item")]["metadata"]["definitionKind"]
        )
        self.assertIn(("java-definition", "App.load"), facts)
        self.assertIn(("java-definition", "App.Result"), facts)

    def test_csharp_facts(self):
        self.require_parser()
        records, diagnostics = extractor.extract(
            "Rich.cs",
            """namespace Demo;
using System.Collections.Generic;
public interface IPort { Result Load(Input input); }
public record Item(string Id);
public class App : IPort {
  public App() {}
  public Result Load(Input input) => new Result();
  public string Name { get; init; }
  public class Result {}
}
public class Input {}
""",
        )
        self.assertEqual([], diagnostics)
        facts = {(item["kind"], item["title"]): item for item in records}
        self.assertIn(("dotnet-namespace", "Demo"), facts)
        self.assertIn(("dotnet-using", "System.Collections.Generic"), facts)
        self.assertIn(("dotnet-definition", "App.Load"), facts)
        self.assertEqual(
            "property",
            facts[("dotnet-definition", "App.Name")]["metadata"]["definitionKind"],
        )
        self.assertIn(("dotnet-definition", "App.Result"), facts)


if __name__ == "__main__":
    unittest.main()
