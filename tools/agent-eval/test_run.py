import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

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


class RawCaptureTest(unittest.TestCase):
    def test_codex_capture_is_case_local_and_collected_after_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            logs = root / 'logs'
            logs.mkdir()
            sibling = logs / 'other.codex-ygg.ygg-raw.jsonl'
            sibling.write_text('other case')
            log = logs / 'case.codex-ygg.jsonl'
            collected = log.with_suffix('.ygg-raw.jsonl')
            args = SimpleNamespace(
                storage=root / 'storage', ygg_format='compact', codex_model='test',
                codex_effort='low', timeout=30, tool_budget=5,
            )
            case = {'repo': 'gin'}
            paths = []
            captured = '{"schema":"ygg.cli/v1","ok":false}\n'

            def command(argv, **kwargs):
                if argv[0] == 'git':
                    return SimpleNamespace(stdout='')
                writable = {Path(argv[index + 1]) for index, value in enumerate(argv) if value == '--add-dir'}
                raw = Path(kwargs['env']['YGG_AGENT_RAW'])
                self.assertEqual(Path(kwargs['env']['TMPDIR']), raw.parent)
                self.assertEqual(raw.parent.parent, logs)
                self.assertIn('sandbox_workspace_write.exclude_slash_tmp=true', argv)
                self.assertIn('sandbox_workspace_write.exclude_tmpdir_env_var=true', argv)
                self.assertEqual(writable, {args.storage, raw.parent})
                self.assertNotIn(logs, writable)
                self.assertFalse(collected.exists())
                paths.append(raw)
                if len(paths) == 1:
                    raw.write_text(captured)
                    raise subprocess.TimeoutExpired(argv, args.timeout)
                return SimpleNamespace(returncode=0)

            with patch.object(run.subprocess, 'run', side_effect=command):
                _, timed_out, _ = run.run_codex(case, 'Fix it.', 'codex-ygg', args, root, log,
                                                {'YGG_AGENT_RAW': str(collected)})
                self.assertTrue(timed_out)
                self.assertEqual(collected.read_text(), captured)
                self.assertEqual(run.observed_searches(log)['errors'], 1)
                self.assertFalse(paths[0].exists())
                run.run_codex(case, 'Fix it.', 'codex-ygg', args, root, log, {})
            self.assertNotEqual(paths[0].parent, paths[1].parent)
            self.assertFalse(collected.exists())
            self.assertEqual(sibling.read_text(), 'other case')


if __name__ == '__main__':
    unittest.main()
