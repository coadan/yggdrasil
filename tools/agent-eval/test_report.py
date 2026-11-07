import json
from pathlib import Path
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


if __name__ == '__main__':
    unittest.main()
