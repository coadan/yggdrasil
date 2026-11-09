import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('cross_artifact', Path(__file__).with_name('cross-artifact.py'))
cross = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cross)


class KindTest(unittest.TestCase):
    def test_classifies_by_file_name_only(self):
        self.assertEqual(cross.kind('src/app.py'), 'code')
        self.assertEqual(cross.kind('docs/guide.md'), 'docs')
        self.assertEqual(cross.kind('infra/main.tf'), 'config')
        self.assertEqual(cross.kind('deploy/Dockerfile'), 'config')
        self.assertEqual(cross.kind('Dockerfile.dev'), 'config')
        self.assertIsNone(cross.kind('package-lock.json'))
        self.assertIsNone(cross.kind('Cargo.lock'))
        self.assertIsNone(cross.kind('assets/logo.png'))


class CoverageTest(unittest.TestCase):
    def test_counts_files_within_depth_per_kind(self):
        expected = ['a.go', 'README.md', 'main.tf']
        result = cross.coverage(['x.go', 'a.go', 'main.tf', 'README.md'], expected, depth=3)
        self.assertEqual(result['found'], 2)
        self.assertFalse(result['complete'])
        self.assertEqual(result['kinds'], {'code': [1, 1], 'config': [1, 1], 'docs': [0, 1]})
        self.assertEqual(result['firstRank'], 2)
        self.assertTrue(cross.coverage(['README.md', 'main.tf', 'a.go'], expected, depth=3)['complete'])

    def test_terms_drop_short_words_stopwords_and_repeats(self):
        self.assertEqual(cross.terms('Fix the AST cache for the cache_key in v2'), ['fix', 'ast', 'cache', 'cache_key'])


class GrepRankTest(unittest.TestCase):
    def test_ranks_by_distinct_terms_then_lines(self):
        with tempfile.TemporaryDirectory() as tree:
            root = Path(tree)
            (root / 'both.py').write_text('retry budget\n')
            (root / 'many.md').write_text('retry\nretry\nretry\n')
            (root / 'one.md').write_text('retry\n')
            (root / '.github').mkdir()
            (root / '.github' / 'ci.yml').write_text('budget\n')
            self.assertEqual(cross.grep_rank(tree, 'retry budget'), ['both.py', 'many.md', '.github/ci.yml', 'one.md'])


class DrawTest(unittest.TestCase):
    def test_draws_multi_kind_commits_pinned_to_parent(self):
        with tempfile.TemporaryDirectory() as scratch:
            repo = Path(scratch) / 'repo'
            repo.mkdir()
            run = lambda *args: subprocess.run(['git', '-C', str(repo), *args], check=True, capture_output=True)
            run('init', '-q')
            run('config', 'user.email', 't@example.com')
            run('config', 'user.name', 'T')
            (repo / 'app.go').write_text('package app\n')
            (repo / 'README.md').write_text('# App\n')
            (repo / 'only.go').write_text('package only\n')
            run('add', '.')
            run('commit', '-q', '-m', 'initial import of the app')
            (repo / 'app.go').write_text('package app\n// retry\n')
            (repo / 'README.md').write_text('# App\nretry\n')
            run('commit', '-qam', 'add retry budget to the client')
            (repo / 'only.go').write_text('package only\n// x\n')
            run('commit', '-qam', 'touch a single code file only')
            output = Path(scratch) / 'suite'
            args = type('Args', (), dict(output=str(output), repo=[str(repo)], repos='', exclude=[],
                                         scan=50, max_files=8, per_repo=10))
            cross.draw(args)
            import json
            cases = json.loads((output / 'dev.json').read_text()) + json.loads((output / 'holdout.json').read_text())
            self.assertEqual(len(cases), 1)
            case = cases[0]
            self.assertEqual(case['query'], 'add retry budget to the client')
            self.assertEqual(case['expectedPaths'], ['README.md', 'app.go'])
            self.assertEqual(case['kinds'], ['code', 'docs'])
            parent = subprocess.run(['git', '-C', str(repo), 'rev-parse', f"{case['commit']}^"],
                                    capture_output=True, text=True).stdout.strip()
            self.assertEqual(case['base'], parent)


if __name__ == '__main__':
    unittest.main()
