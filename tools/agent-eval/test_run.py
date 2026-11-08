import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import run


class BookkeepingTest(unittest.TestCase):
    def test_release_notes_and_lock_files_only(self):
        self.assertTrue(run.bookkeeping_only(['CHANGELOG.md']))
        self.assertTrue(run.bookkeeping_only(['docs/CHANGES.rst', 'go.sum', 'tests/smoke/esm/package-lock.json']))
        self.assertTrue(run.bookkeeping_only(['PRE_RELEASE_CHANGELOG.md', 'Cargo.lock', 'NEWS']))

    def test_code_or_prose_keeps_the_case(self):
        self.assertFalse(run.bookkeeping_only([]))
        self.assertFalse(run.bookkeeping_only(['CHANGELOG.md', 'src/main.rs']))
        self.assertFalse(run.bookkeeping_only(['src/changes.py']))
        self.assertFalse(run.bookkeeping_only(['history.py', 'README.md']))


class PromptTest(unittest.TestCase):
    def test_extra_text_and_identity_stay_fixed_during_run(self):
        case = {'repo': 'gin'}
        args = SimpleNamespace(
            tool_budget=5, prompt_extra=None, split='dev', style='natural', timeout=30,
            max_budget=1, jobs=1, model='test', codex_model='test', codex_effort='low',
        )
        plain = run.task_prompt(args, case, 'Fix it.')
        self.assertIn('Fix it.', plain)
        with tempfile.TemporaryDirectory() as directory:
            extra = Path(directory) / 'extra.txt'
            extra.write_bytes(b'List the tests too.\r\nInclude their paths.\r\n')
            args.prompt_extra = run.PromptInput.read(extra)
            identity = run.run_manifest(args, {}, [], {})['promptExtra']
            extra.write_text('Only list production files.\n')
            expected = plain + '\n\nList the tests too.\nInclude their paths.'
            self.assertEqual(run.task_prompt(args, case, 'Fix it.'), expected)
            self.assertEqual(run.run_manifest(args, {}, [], {})['promptExtra'], identity)
            self.assertNotEqual(run.PromptInput.read(extra).identity(), identity)


if __name__ == '__main__':
    unittest.main()
