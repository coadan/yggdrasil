import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from report import inspect_trajectory, search_paths, ygg_paths


def use(call_id, name, **request):
    return {'type': 'tool_use', 'id': call_id, 'name': name, 'input': request}


def result(call_id, content, error=False):
    return {'type': 'tool_result', 'tool_use_id': call_id, 'content': content, 'is_error': error}


def event(kind, *items):
    return {'type': kind, 'message': {'content': list(items)}}


def envelope(records, more=()):
    return json.dumps({'schema': 'ygg.cli/v1', 'ok': True, 'data': {'records': records, 'morePaths': list(more)}})


class ReportTest(unittest.TestCase):
    def test_exposure_and_reads_respect_tool_result_order(self):
        trace = [
            {'type': 'system', 'subtype': 'init', 'cwd': '/repo'},
            event('assistant', use('failed-search', 'Grep', pattern='x'),
                  use('failed-read', 'Read', file_path='/repo/failure.go')),
            event('user', result('failed-search', 'failure.go', True), result('failed-read', 'failure.go', True)),
            event('assistant', use('near-miss', 'Grep', pattern='x'), use('unknown', 'Bash', command='rg x')),
            event('user', result('near-miss', 'Found 1 file\nowner.go.bak'), result('unknown', 'owner.go: x')),
            event('assistant', use('search', 'Bash', command='ygg search x'),
                  use('parallel-read', 'Read', file_path='/repo/parallel.go')),
            event('user', result('search', envelope([{'path': 'parallel.go', 'excerpt': 'failure.go'},
                                                    {'path': 'owner.go'}], ['failure.go'])),
                  result('parallel-read', 'contents')),
            event('assistant', use('read', 'Read', file_path='/repo/owner.go'),
                  use('outside', 'Read', file_path='/other/owner.go')),
            event('user', result('read', 'contents'), result('outside', 'contents')),
        ]
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'trace.jsonl'
            log.write_text(''.join(json.dumps(item) + '\n' for item in trace))
            evidence = inspect_trajectory(log, {'failure.go', 'owner.go', 'parallel.go'})
        self.assertEqual(evidence['searches'], [False, None, True])
        self.assertEqual(evidence['shown'], {'owner.go', 'parallel.go'})
        self.assertEqual(evidence['read'], {'owner.go', 'parallel.go'})
        self.assertEqual(evidence['readAfterShown'], {'owner.go'})

    def test_paths_come_from_displayed_headers(self):
        self.assertEqual(ygg_paths(envelope([{'path': 'a.go'}]) + '\n' + envelope([{'path': 'b.go'}], ['c.go'])),
                         ['a.go', 'b.go'])
        self.assertEqual(ygg_paths('2 results (hybrid)\na.go:1-4,7-9\n    fake.go:3-9\nmore: b.go, c.go'), ['a.go'])
        self.assertIsNone(ygg_paths(envelope([{}])))
        self.assertEqual(search_paths('Grep', {'output_mode': 'content'}, 'a.go:9:b.go\n10:only text'), ['a.go'])

    def test_cli_requires_each_planned_arm_and_a_successful_result_event(self):
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            logs = run / 'logs'
            logs.mkdir()
            (run / 'manifest.json').write_text(json.dumps({
                'schema': 1, 'split': 'holdout', 'style': 'symptom', 'arms': ['shell', 'shell-ygg'],
                'cases': [{'id': 'demo', 'repo': 'repo', 'expectedPaths': ['owner.go']}],
                'models': {'model': 'fixture'}, 'limits': {'tool_budget': 8},
                'sources': {'guard.py': 'a' * 64},
                'ygg': {'format': 'compact', 'binary': {'sha256': 'b' * 64}},
            }))
            row = {'case': 'demo', 'repo': 'repo', 'arm': 'shell', 'expectedPaths': ['owner.go'],
                   'model': 'fixture', 'hit': True, 'firstHitRank': 1, 'recall': 1, 'precision': 1,
                   'toolCalls': 1, 'inputTokens': 2000, 'costUsd': None, 'durationMs': 1000,
                   'answer': ['owner.go']}
            completed = json.dumps({'type': 'result', 'structured_output': {'files': ['owner.go']}}) + '\n'
            (logs / 'demo.shell.jsonl').write_text(completed)
            results = run / 'results.json'
            command = [sys.executable, '-B', str(Path(__file__).with_name('report.py')),
                       '--run', str(run), '--require-complete']

            def report(*rows):
                results.write_text(json.dumps(rows))
                return subprocess.run(command, capture_output=True, text=True)

            missing_arm = report(row)
            self.assertEqual(missing_arm.returncode, 1, missing_arm.stderr)
            self.assertIn('shell-ygg 0/1 rows, 1 missing', missing_arm.stdout)
            self.assertIn('0 cases with every arm', missing_arm.stdout)

            ygg = dict(row, arm='shell-ygg', costUsd=0.1, priorFailures=[{'costUsd': 0.05}])
            (logs / 'demo.shell-ygg.jsonl').touch()
            incomplete_log = report(row, ygg)
            self.assertEqual(incomplete_log.returncode, 1, incomplete_log.stderr)
            self.assertIn('1 unverified success logs', incomplete_log.stdout)

            (logs / 'demo.shell-ygg.jsonl').write_text(completed)
            (logs / 'demo.shell-ygg.ygg-raw.jsonl').write_text(json.dumps({
                'schema': 'ygg.cli/v1', 'ok': True, 'data': {'requestedMode': 'auto', 'activeMode': 'lexical',
                    'semantic': {'coverage': 0.25}, 'fallbackReason': 'semantic-below-threshold'},
            }) + '\n')
            paired = report(row, dict(ygg, timedOut=True))
            self.assertEqual(paired.returncode, 0, paired.stderr)
            self.assertIn('shell-ygg   0/1', paired.stdout)
            self.assertIn('1 timeouts', paired.stdout)
            self.assertIn('n/a', paired.stdout)
            self.assertIn('$0.15', paired.stdout)
            self.assertIn('1 successful, 0 errors, 0 unreadable, 0 runs without raw capture', paired.stdout)
            self.assertIn('active=lexical coverage=0.25 fallback=semantic-below-threshold', paired.stdout)

            mismatch = report(row, dict(ygg, expectedPaths=['other.go']))
            self.assertEqual(mismatch.returncode, 2)
            self.assertIn('different repository or expected paths', mismatch.stderr)
            duplicate = report(row, row)
            self.assertEqual(duplicate.returncode, 2)
            self.assertIn('duplicate result', duplicate.stderr)


if __name__ == '__main__':
    unittest.main()
