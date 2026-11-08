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
    def test_extra_text_follows_the_task_prompt(self):
        case = {'repo': 'gin'}
        args = SimpleNamespace(tool_budget=5, prompt_extra=None)
        plain = run.task_prompt(args, case, 'Fix it.')
        self.assertIn('Fix it.', plain)
        with tempfile.TemporaryDirectory() as directory:
            extra = Path(directory) / 'extra.txt'
            extra.write_text('List the tests too.\n')
            args.prompt_extra = extra
            self.assertEqual(run.task_prompt(args, case, 'Fix it.'), plain + '\n\nList the tests too.')


if __name__ == '__main__':
    unittest.main()
