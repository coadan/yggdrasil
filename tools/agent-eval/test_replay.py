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
            (root / 'suite' / 'repos' / 'repo').mkdir(parents=True)
            (run / 'results.json').write_text(json.dumps([
                {'case': 'case', 'arm': 'native', 'repo': 'repo', 'expectedPaths': ['owner.go']},
            ]))
            events = []
            for index, (pattern, options, content, error) in enumerate([
                ('overflow', {}, 'Found 1 file\nowner.go.bak', False),
                ('-leading-dash', {}, [{'type': 'text', 'text': './owner.go'}], False),
                ('filtered', {'glob': '*.go'}, 'owner.go', False),
                ('scoped', {'path': 'pkg'}, 'owner.go', False),
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
import sys

assert sys.argv[-2] == '--'
pattern = sys.argv[-1]
records, overflow = [], []
if pattern == 'overflow':
    records = [{'path': f'file{i}.go'} for i in range(10)]
    overflow = ['file0.go', 'owner.go', 'owner.go']
elif pattern == '-leading-dash':
    records = [{'path': 'other.go'}, {'path': 'other.go'}, {'path': 'owner.go'}]
elif pattern == 'process-error':
    print(json.dumps({'ok': False, 'error': {'message': 'invalid regexp'}}))
    sys.exit(2)
else:
    assert pattern == 'empty', pattern
print(json.dumps({'ok': True, 'data': {'records': records, 'morePaths': overflow}}))
''')
            binary.chmod(0o755)
            result = subprocess.run([
                sys.executable, str(Path(__file__).with_name('replay.py')), '--run', str(run),
                '--suite', str(root / 'suite'), '--ygg-binary', str(binary), '--storage', str(root / 'store'),
            ], check=True, capture_output=True, text=True)
            rows = json.loads((run / 'replay.json').read_text())
            self.assertEqual([row['status'] for row in rows], [
                'paired', 'paired', 'unmatched', 'unmatched', 'native_error', 'native_missing', 'ygg_error', 'paired',
            ])
            self.assertFalse(rows[0]['native']['hit'])
            self.assertEqual(rows[0]['ygg'], {
                'rank': 11, 'paths': 11, 'excerptRank': None, 'hitAtLimit': False, 'overflowHit': True,
            })
            self.assertEqual(rows[1]['ygg']['excerptRank'], 2)
            self.assertTrue(rows[1]['native']['hit'])
            self.assertEqual(rows[6]['ygg']['returncode'], 2)
            self.assertIn('8 Grep, 3 paired', result.stdout)
            self.assertIn('native file hits 1, ygg top 10 hits 1, overflow-only hits 1', result.stdout)
            self.assertIn('earlier ygg top-10 hit among paired calls: 0/1', result.stdout)


if __name__ == '__main__':
    unittest.main()
