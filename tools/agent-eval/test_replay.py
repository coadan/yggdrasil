import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class ReplayTest(unittest.TestCase):
    def test_cli_counts_only_paired_excerpt_hits(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / 'run'
            (run / 'logs').mkdir(parents=True)
            repo = root / 'suite' / 'repos' / 'repo'
            (repo / 'pkg').mkdir(parents=True)
            (repo / 'owner.go').write_text('fact')
            (repo / 'pkg' / 'owner.go').write_text('fact')
            (run / 'results.json').write_text(json.dumps([
                {'case': 'case', 'arm': 'native', 'repo': 'repo', 'expectedPaths': ['owner.go', 'pkg/owner.go']},
            ]))
            events = []
            for index, (pattern, options, content, error) in enumerate([
                ('overflow', {}, 'Found 1 file\nowner.go.bak', False),
                ('-leading-dash', {}, [{'type': 'text', 'text': './owner.go'}], False),
                ('filtered', {'glob': '*.go'}, 'owner.go', False),
                ('scope-dir', {'path': 'pkg', 'output_mode': 'content', '-n': True, '-i': True, 'head_limit': 1},
                 'pkg/owner.go:7:Fact', False),
                ('scope-file', {'path': 'owner.go', 'output_mode': 'content', '-n': True}, '7:fact', False),
                ('outside', {'path': '..'}, 'owner.go', False),
                ('count', {'output_mode': 'count'}, 'owner.go:3', False),
                ('read-error', {}, 'error searching owner.go', True),
                ('missing', {}, None, False),
                ('process-error', {}, 'owner.go', False),
                ('empty', {}, 'No files found', False),
            ]):
                call_id = str(index)
                events.append({'type': 'assistant', 'message': {'content': [
                    {'type': 'tool_use', 'name': 'Grep', 'id': call_id, 'input': {'pattern': pattern, **options}},
                ]}})
                if content is not None:
                    events.append({'type': 'user', 'message': {'content': [
                        {'type': 'tool_result', 'tool_use_id': call_id, 'content': content, 'is_error': error},
                    ]}})
            (run / 'logs' / 'case.native.jsonl').write_text(''.join(json.dumps(event) + '\n' for event in events))

            binary = root / 'ygg'
            binary.write_text(f'#!{sys.executable}\n' + '''
import json
from pathlib import Path
import sys

assert sys.argv[-2] == '--'
pattern = sys.argv[-1]
records, overflow = [], []
if pattern == 'overflow':
    records = [{'path': f'file{i}.go'} for i in range(10)]
    overflow = ['file0.go', 'owner.go', 'owner.go']
elif pattern == '-leading-dash':
    records = [{'path': 'other.go'}, {'path': 'other.go'}, {'path': 'owner.go'}]
elif pattern == '(?i)scope-dir':
    assert Path(sys.argv[sys.argv.index('--root') + 1]).name == 'pkg'
    records = [{'path': 'pkg/owner.go'}]
elif pattern == 'scope-file':
    assert Path(sys.argv[sys.argv.index('--root') + 1]).name == 'owner.go'
    records = [{'path': 'owner.go'}]
elif pattern == 'count':
    records = [{'path': 'owner.go'}]
elif pattern == 'process-error':
    print(json.dumps({'ok': False, 'error': {'message': 'invalid regexp'}}))
    sys.exit(2)
else:
    assert pattern == 'empty', pattern
print(json.dumps({'ok': True, 'data': {'records': records, 'morePaths': overflow}}))
''')
            binary.chmod(0o755)
            result = subprocess.run([
                sys.executable, '-B', str(Path(__file__).with_name('replay.py')), '--run', str(run),
                '--suite', str(root / 'suite'), '--ygg-binary', str(binary), '--storage', str(root / 'store'),
            ], check=True, capture_output=True, text=True)
            rows = json.loads((run / 'replay.json').read_text())
            by_pattern = {row['input']['pattern']: row for row in rows}
            self.assertEqual({key: row['status'] for key, row in by_pattern.items()}, {
                'overflow': 'paired', '-leading-dash': 'paired', 'filtered': 'unmatched', 'scope-dir': 'paired',
                'scope-file': 'paired', 'outside': 'unmatched', 'count': 'paired', 'read-error': 'native_error',
                'missing': 'native_missing', 'process-error': 'ygg_error', 'empty': 'paired',
            })
            self.assertFalse(by_pattern['overflow']['native']['hit'])
            self.assertEqual(by_pattern['overflow']['ygg'], {
                'rank': 11, 'paths': 11, 'excerptRank': None, 'hitAtLimit': False, 'overflowHit': True,
            })
            self.assertEqual(by_pattern['-leading-dash']['ygg']['excerptRank'], 2)
            self.assertTrue(by_pattern['-leading-dash']['native']['hit'])
            self.assertTrue(by_pattern['scope-dir']['native']['shown'])
            self.assertFalse(by_pattern['scope-file']['native']['shown'])
            self.assertTrue(by_pattern['scope-file']['native']['matchedScope'])
            self.assertEqual(by_pattern['process-error']['ygg']['returncode'], 2)
            self.assertIn('11 Grep, 6 paired', result.stdout)
            self.assertIn('native file hits 4, ygg top 10 hits 4, overflow-only hits 1', result.stdout)
            self.assertIn('earlier ygg top-10 hit among paired calls: 0/1', result.stdout)


if __name__ == '__main__':
    unittest.main()
