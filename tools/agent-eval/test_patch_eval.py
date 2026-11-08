import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class PatchEvalTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.repo = self.root / 'source'
        self.repo.mkdir()
        self.git(self.repo, 'init', '-q')
        (self.repo / 'app.py').write_text('def answer():\n    return 0\n')
        self.base = self.commit('base')
        (self.repo / 'app.py').write_text('def answer():\n    return 42\n')
        self.reference = self.commit('fix')
        self.verifier = self.root / 'verify.py'
        self.verifier.write_text('''import json, runpy, sys
from pathlib import Path
answer = runpy.run_path(str(Path(sys.argv[1]) / 'app.py'))['answer']()
print(json.dumps({'schema': 'ygg.patch-verification/v1',
                  'status': 'pass' if answer == 42 else 'fail'}))
''')
        self.suite = self.root / 'suite.json'
        self.suite.write_text(json.dumps({'schema': 'ygg.patch-eval/v1', 'cases': [{
            'id': 'task', 'repo': 'source', 'base': self.base, 'reference': self.reference,
            'verifier': 'verify.py', 'prompt': 'Return the expected answer.',
        }]}))

    def git(self, root, *args):
        return subprocess.check_output(['git', '-C', str(root), *args], text=True)

    def commit(self, message):
        self.git(self.repo, 'add', '.')
        self.git(self.repo, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.test',
                 '-c', 'commit.gpgsign=false', 'commit', '-qm', message)
        return self.git(self.repo, 'rev-parse', 'HEAD').strip()

    def run_eval(self, *args):
        return subprocess.run(
            [sys.executable, '-B', str(Path(__file__).with_name('patch_eval.py')),
             '--suite', str(self.suite), *map(str, args)], capture_output=True, text=True,
        )

    def test_prepared_checkout_and_paired_patch_outcomes(self):
        prepared = self.root / 'prepared'
        setup = self.run_eval('prepare', '--case', 'task', '--output', prepared)
        self.assertEqual(setup.returncode, 0, setup.stderr)
        self.assertEqual((prepared / 'app.py').read_text(), 'def answer():\n    return 0\n')
        self.assertEqual(self.git(prepared, 'rev-list', '--count', 'HEAD').strip(), '1')
        self.assertEqual(self.git(prepared, 'remote'), '')
        reference = subprocess.run(['git', '-C', str(prepared), 'cat-file', '-e', self.reference], capture_output=True)
        self.assertNotEqual(reference.returncode, 0)

        arms = {}
        for label, patch in {
            'fixed': self.git(self.repo, 'diff', '--binary', self.base, self.reference),
            'empty': '',
            'invalid': 'invalid patch',
            'missing': None,
        }.items():
            directory = self.root / label
            directory.mkdir()
            if patch is not None:
                (directory / 'task.patch').write_text(patch)
            arms[label] = directory
        output = self.root / 'report.json'
        result = self.run_eval('score', '--output', output,
                               *(part for label, path in arms.items() for part in ('--arm', f'{label}={path}')))
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(output.read_text())
        case = report['cases'][0]
        self.assertEqual({name: row['status'] for name, row in case['controls'].items()},
                         {'base': 'fail', 'reference': 'pass'})
        self.assertEqual({name: row['status'] for name, row in case['submissions'].items()},
                         {'fixed': 'pass', 'empty': 'fail', 'invalid': 'invalid-patch', 'missing': 'missing'})
        self.assertEqual(report['paired'][0], {
            'left': 'fixed', 'right': 'empty', 'leftOnly': 1, 'rightOnly': 0, 'both': 0, 'neither': 0,
        })
        self.assertEqual(self.git(self.repo, 'status', '--porcelain'), '')

    def test_invalid_control_prevents_scoring(self):
        self.verifier.write_text("print('{\"schema\":\"ygg.patch-verification/v1\",\"status\":\"pass\"}')\n")
        output = self.root / 'report.json'
        result = self.run_eval('score', '--arm', f'candidate={self.root}', '--output', output)
        self.assertEqual(result.returncode, 1, result.stderr)
        report = json.loads(output.read_text())
        self.assertEqual(report['validCases'], 0)
        self.assertEqual(report['invalidCases'], 1)
        self.assertEqual(report['cases'][0]['submissions']['candidate']['status'], 'unscored')


if __name__ == '__main__':
    unittest.main()
