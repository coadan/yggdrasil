import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from discovery import events, measure, mentions, opening, reads_source, summarize, trace, writes

LABEL = 'lib/core/a.js'


class MentionTest(unittest.TestCase):
    def test_matches_relative_dotted_absolute_and_grep_output(self):
        for text in ('cat lib/core/a.js', "sed -n '1,9p' ./lib/core/a.js", 'cat /tmp/x/repo/lib/core/a.js',
                     'lib/core/a.js:12:  eject(id)', "cat 'lib/core/a.js'"):
            self.assertTrue(mentions(text, LABEL), text)

    def test_rejects_longer_names_and_other_directories(self):
        for text in ('cat lib/core/a.jsx', 'cat lib/core/a.js.map', 'cat xlib/core/a.js', 'ls lib/core'):
            self.assertFalse(mentions(text, LABEL), text)


class WriteTest(unittest.TestCase):
    def test_recognised_writes(self):
        for command in ("sed -i '' 's/a/b/' lib/core/a.js", "sed -n 1p x && perl -pi -e 's/a/b/' lib/core/a.js",
                        'cat > lib/core/a.js <<EOF', 'echo x >> ./lib/core/a.js', 'echo x | tee lib/core/a.js',
                        "apply_patch <<'EOF'\n*** Update File: lib/core/a.js", "open('lib/core/a.js', 'w')"):
            self.assertTrue(writes(command, LABEL), command)

    def test_reads_are_not_writes(self):
        for command in ('cat lib/core/a.js', 'cat lib/core/a.js | head > /tmp/part', "sed -n '1,4p' lib/core/a.js",
                        "open('lib/core/a.js', 'r')", "sed -i '' s/a/b/ other.js"):
            self.assertFalse(writes(command, LABEL), command)


class TraceTest(unittest.TestCase):
    def test_indices_count_from_one_and_edit_needs_exit_zero(self):
        commands = ['ls', 'rg -l eject', 'cat lib/core/a.js', "sed -i 1d lib/core/a.js", "sed -i 2d lib/core/a.js"]
        outputs = ['lib\n', 'lib/core/a.js\n', 'x', '', '']
        row = trace(commands, outputs, [0, 0, 0, 1, 0], [LABEL])
        self.assertEqual(row, {'commands': 5, 'exposure': 2, 'read': 3, 'editAttempt': 4, 'edit': 5})

    def test_unknown_outputs_leave_exposure_and_edit_unknown(self):
        row = trace(['cat lib/core/a.js'], [None], [None], [LABEL])
        self.assertEqual((row['exposure'], row['read'], row['editAttempt'], row['edit']), (None, 1, None, None))


class SourceReadTest(unittest.TestCase):
    def test_readers_with_the_owner_as_file_operand(self):
        for command in ("/bin/zsh -lc 'cd /x/repo && cat lib/core/a.js'", 'rg -n eject lib/core/a.js',
                        "sed -n '1,5p' ./lib/core/a.js", 'cat -n lib/core/a.js | sed -n 2p', 'head -n 40 lib/core/a.js',
                        'grep -e x -e y lib/core/a.js', 'tail -20 /abs/repo/lib/core/a.js', 'rg -t js foo lib/core/a.js'):
            self.assertTrue(reads_source(command, LABEL), command)

    def test_patterns_listings_writes_and_untokenisable_commands_are_not_reads(self):
        for command in ('rg lib/core/a.js', 'rg -n "lib/core/a.js" .', 'ls lib/core/a.js', "sed -n '1p",
                        "sed -i '' 1d lib/core/a.js", 'cat lib/core/a.jsx', 'find . -name a.js'):
            self.assertFalse(reads_source(command, LABEL), command)


class OpeningTest(unittest.TestCase):
    def test_owner_and_same_stem_sibling_ranks(self):
        cards = [{'path': 'README.md'}, {'path': 'tests/unit/combineURLs.test.js'}, {'path': 'lib/combineURLs.js'}]
        self.assertEqual(opening(cards, 'lib/combineURLs.js'),
                         {'openingOwnerRank': 3, 'openingSiblingRank': 2,
                          'openingSibling': 'tests/unit/combineURLs.test.js'})
        self.assertEqual(opening(cards[:1], 'lib/combineURLs.js')['openingOwnerRank'], None)


class EventsTest(unittest.TestCase):
    def test_truncated_final_line_is_dropped_only_after_timeout(self):
        complete = json.dumps({'type': 'item.completed', 'item': {
            'type': 'command_execution', 'command': 'ls', 'aggregated_output': '', 'exit_code': 0}})
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / 'c.ygg.jsonl'
            log.write_text(complete + '\n{"type": "item.comp')
            self.assertEqual(events(log, timed_out=True)[0], ['ls'])
            with self.assertRaises(json.JSONDecodeError):
                events(log)
            log.write_text('{"type": "item.comp\n' + complete + '\n')
            with self.assertRaises(json.JSONDecodeError):
                events(log, timed_out=True)


class MeasureTest(unittest.TestCase):
    def test_labels_come_from_base_to_reference_and_logs_are_used(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo = root / 'repo'
            git = ['git', '-C', str(repo), '-c', 'user.name=t', '-c', 'user.email=t@t']
            subprocess.run(['git', 'init', '-q', str(repo)], check=True)
            (repo / 'a.js').write_text('one\n')
            (repo / 'b.js').write_text('two\n')
            subprocess.run([*git, 'add', '.'], check=True)
            subprocess.run([*git, 'commit', '-qm', 'base'], check=True)
            base = subprocess.run([*git, 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
            (repo / 'b.js').write_text('fixed\n')
            subprocess.run([*git, 'commit', '-qam', 'fix'], check=True)
            (root / 'suite.json').write_text(json.dumps(
                {'cases': [{'id': 'c', 'repo': 'repo', 'base': base, 'reference': 'HEAD'}]}))
            runs = root / 'out'
            runs.mkdir()
            commands = ['cat a.js', 'cat b.js']
            (runs / 'runs.jsonl').write_text(json.dumps({'case': 'c', 'arm': 'ygg', 'commands': commands}) + '\n')
            (runs / 'c.ygg.jsonl').write_text(''.join(json.dumps(
                {'type': 'item.completed', 'item': {'type': 'command_execution', 'command': command,
                                                    'aggregated_output': 'out', 'exit_code': 0}}) + '\n'
                for command in commands))
            rows = measure([runs / 'runs.jsonl'], root / 'suite.json', {'c': 'b.js'},
                           {('c', 'ygg'): [{'path': 'b.js'}]})
            self.assertEqual(rows[0]['labels'], ['b.js'])
            self.assertEqual((rows[0]['read'], rows[0]['exposure'], rows[0]['outputsLogged']), (2, None, True))
            self.assertEqual((rows[0]['ownerRead'], rows[0]['openingOwnerRank']), (2, 1))
            self.assertEqual(summarize(rows)['ygg']['read'], 1)


if __name__ == '__main__':
    unittest.main()
