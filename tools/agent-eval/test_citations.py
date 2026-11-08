import unittest

from citations import chance, displayed_lines, fixed_lines, map_line, map_region, parse_hunks, window_distance

DIFF = '''diff --git a/a.py b/a.py
--- a/a.py
+++ b/a.py
@@ -3 +3,2 @@ def f():
-old
+new
+more
@@ -10,2 +11,0 @@
-gone
-gone
@@ -20,0 +20 @@
+inserted
'''


class CitationTest(unittest.TestCase):
    def test_parse_hunks_defaults_counts_to_one(self):
        self.assertEqual(parse_hunks(DIFF), [(3, 1, 3, 2), (10, 2, 11, 0), (20, 0, 20, 1)])

    def test_fixed_lines_marks_the_line_before_a_pure_deletion(self):
        self.assertEqual(fixed_lines(parse_hunks(DIFF)), {3, 4, 11, 20})
        self.assertEqual(fixed_lines([(1, 1, 0, 0)]), {1})

    def test_map_line_shifts_unchanged_lines_and_refuses_changed_ones(self):
        hunks = parse_hunks(DIFF)
        self.assertEqual(map_line(2, hunks), 2)
        self.assertIsNone(map_line(3, hunks))
        self.assertEqual(map_line(5, hunks), 6)
        self.assertIsNone(map_line(11, hunks))
        self.assertEqual(map_line(15, hunks), 14)
        # Line 20 precedes the insertion after it; line 21 follows it.
        self.assertEqual(map_line(20, hunks), 19)
        self.assertEqual(map_line(21, hunks), 21)

    def test_map_region_is_all_or_nothing(self):
        hunks = parse_hunks(DIFF)
        self.assertEqual(map_region({5, 6}, hunks), {6, 7})
        self.assertIsNone(map_region({5, 10}, hunks))
        self.assertEqual(map_region({5}, []), {5})

    def test_distance_and_chance(self):
        self.assertEqual(window_distance(10, 12, [11]), 0)
        self.assertEqual(window_distance(10, 12, [20, 3]), 7)
        # A 1-line window in a 10-line file hits one of two lines 2/10 of the time.
        self.assertAlmostEqual(chance(1, 10, [4, 7]), 0.2)
        self.assertEqual(chance(50, 10, [4]), 1.0)

    def test_displayed_lines_aligns_excerpt_to_blob(self):
        blob = [f'L{number:02d} text' for number in range(1, 44)]
        self.assertEqual(displayed_lines('L03 text\nL04 text', blob, 3, 6), (3, 4))
        self.assertEqual(displayed_lines('L03 text\r\nL04 te\u2026', blob, 3, 6), (3, 4))
        # The excerpt may start later in the range, mid-line, and trimmed.
        self.assertEqual(displayed_lines('5 text\nL06 text', blob, 3, 8), (5, 6))
        indented = ['', '    }', '', 'next']
        self.assertEqual(displayed_lines('}\n\nnext', indented, 1, 4), (2, 4))
        self.assertIsNone(displayed_lines('L03 text\nother', blob, 3, 6))
        self.assertIsNone(displayed_lines('L43 text\nL44 text', blob, 43, 44))
        self.assertIsNone(displayed_lines('', blob, 1, 4))

    def test_displayed_lines_refuses_ambiguous_alignment(self):
        self.assertIsNone(displayed_lines('}', ['  }', '', '  }'], 1, 3))
        self.assertEqual(displayed_lines('}', ['  }', '', '  }'], 1, 2), (1, 1))

    def test_declared_range_can_overlap_where_the_excerpt_does_not(self):
        # A record declares lines 1-43 but its excerpt shows 1-8; the fix is on 42.
        blob = [f'L{number:02d} text' for number in range(1, 44)]
        excerpt = '\n'.join(blob[:7]) + '\nL08 te\u2026'
        shown = displayed_lines(excerpt, blob, 1, 43)
        self.assertEqual(shown, (1, 8))
        self.assertEqual(window_distance(1, 43, [42]), 0)
        self.assertEqual(window_distance(*shown, [42]), 34)

if __name__ == '__main__':
    unittest.main()
