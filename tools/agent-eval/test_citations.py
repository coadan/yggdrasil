import unittest

from citations import chance, fixed_lines, map_line, map_region, parse_hunks, window_distance

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


if __name__ == '__main__':
    unittest.main()
