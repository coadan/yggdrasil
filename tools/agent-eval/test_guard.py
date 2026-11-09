import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from guard import allowed


class GuardTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        self.repo = root / 'repo'
        self.repo.mkdir()
        self.outside = root / 'outside.txt'
        self.outside.write_text('outside fixture')
        (self.repo / 'local.txt').write_text('local fixture')

    def test_ygg_chains_and_literal_filters(self):
        for command in (
            'ygg search first && ygg search second 2>/dev/null | head -20',
            "ygg search 'literal; pipe | and `tick`' || echo 'none'",
            r'ygg search -E "literal\$symbol$" 2>&1 | grep -iE "part|owner$" | cut -c1-200',
            "ygg search -F 'empty($x)' | sort -u | uniq -c | wc -l",
            'ygg search needle | grep -E "first$|second$"',
            "ygg search 'quoted > path' | grep -e 'needle' -- -",
        ):
            with self.subTest(command=command):
                self.assertTrue(allowed(command, 'ygg', self.repo))

    def test_jump_mode_adds_one_symbol_lookups(self):
        for command in ('ygg-def validateToken', "ygg-refs 'parse_args' | head -20",
                        'ygg search token && ygg-def TokenError'):
            with self.subTest(command=command):
                self.assertTrue(allowed(command, 'ygg-jump', self.repo))
        for command in ('ygg-def', 'ygg-def a b', 'ygg search x | ygg-def y', f'ygg-refs x > {self.outside}'):
            with self.subTest(command=command):
                self.assertFalse(allowed(command, 'ygg-jump', self.repo))
        self.assertFalse(allowed('ygg-def validateToken', 'ygg', self.repo))

    def test_ygg_guard_refuses_file_arguments_and_hidden_commands(self):
        for command in (
            f'ygg search needle | sort -o{self.outside}',
            f'ygg search needle | grep -f{self.outside}',
            'ygg search needle | grep -r needle',
            'ygg search needle | grep needle 123',
            'ygg search needle | head 123',
            'ygg search needle | head -n 2 local.txt',
            'ygg search needle | uniq local.txt output.txt',
            'ygg search needle | grep -e pattern *',
            f'ygg search needle\ncat {self.outside}',
            f'ygg search "literal quote\' $(cat {self.outside}) \'"',
            'ygg search "empty($x)"',
            'ygg search "$[1+1]"',
            f'ygg search needle >{self.outside}',
            'ygg search needle & echo second',
        ):
            with self.subTest(command=command):
                self.assertFalse(allowed(command, 'ygg', self.repo))

    def test_shell_reads_and_globs_stay_in_the_checkout(self):
        child = self.repo / 'src' / 'nested'
        child.mkdir(parents=True)
        (child / 'owner.go').write_text('needle')
        for command in (
            'grep -rn "needle" src --include=*.go -l 2>/dev/null | head -30',
            'grep -n needle -A 2 src/*/*.go',
            "find src \\( -name '*.go' -o -name '*.txt' \\) -print",
            'ls src/*/*.go && sed -n "1,5p" src/nested/owner.go',
            f'cat "{self.repo / "local.txt"}"',
        ):
            with self.subTest(command=command):
                self.assertTrue(allowed(command, 'shell', self.repo))
        (self.repo / 'link.txt').symlink_to(self.outside)
        (self.repo / 'link-dir').symlink_to(self.repo.parent, target_is_directory=True)
        (self.repo / '--include=linked').symlink_to(self.repo.parent, target_is_directory=True)
        (self.repo / '-f.txt').write_text('option-like filename')
        (self.repo / '-f.go').write_text('option-like filename')
        for command in (
            f'cat "{self.outside}"',
            'cat "$TMPDIR/outside.txt"',
            'cat ../outside.txt',
            'cat link.txt',
            'cat *.txt',
            'cat link-dir/*',
            'ls link-*/outside.txt',
            'grep -r pattern link-dir',
            'grep -rn --include=*/*.txt needle .',
            f'grep needle -e "{self.outside}" local.txt',
            'cat ./*.txt',
            'cat *.go',
            'ls **/owner.go',
            'find -L . -type f',
            'find . -exec echo {} \\;',
            f'sed -n "1w {self.outside}" local.txt',
            f'sort -o{self.outside} local.txt',
            'uniq missing* output.txt',
        ):
            with self.subTest(command=command):
                self.assertFalse(allowed(command, 'shell', self.repo))
        self.assertTrue(allowed('ygg search needle | grep -n needle', 'shell-ygg', self.repo))
        self.assertFalse(allowed('ygg search needle', 'shell', self.repo))

    def test_hook_exit_status(self):
        event = {'tool_name': 'Bash', 'cwd': str(self.repo),
                 'tool_input': {'command': 'ygg search first && ygg search second 2>/dev/null'}}
        for expected, command in ((0, event['tool_input']['command']), (2, 'ygg search first | sort -ooutput')):
            event['tool_input']['command'] = command
            result = subprocess.run(
                [sys.executable, '-B', str(Path(__file__).with_name('guard.py')), 'ygg'],
                input=json.dumps(event), capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, expected, result.stderr)


if __name__ == '__main__':
    unittest.main()
