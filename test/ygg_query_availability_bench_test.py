import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
BENCH_PATH = ROOT / "scripts" / "query-availability-bench.py"


def load_bench():
    spec = importlib.util.spec_from_file_location("query_availability_bench", BENCH_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class QueryAvailabilityBenchTest(unittest.TestCase):
    def test_percentile_uses_observed_nearest_rank(self):
        bench = load_bench()

        self.assertEqual(2, bench.percentile([1, 2, 3, 4], 0.50))
        self.assertEqual(4, bench.percentile([1, 2, 3, 4], 0.95))
        self.assertIsNone(bench.percentile([], 0.95))

    def test_summary_keeps_tail_and_completion_status(self):
        bench = load_bench()

        summary = bench.summarize([
            {"elapsedMs": 10.0, "completed": True, "timeout": False},
            {"elapsedMs": 12.0, "completed": True, "timeout": False},
            {"elapsedMs": 20.0, "completed": False, "timeout": True},
        ])

        self.assertEqual(3, summary["runs"])
        self.assertEqual(2, summary["completed"])
        self.assertEqual(1, summary["timeouts"])
        self.assertEqual(12.0, summary["p50Ms"])
        self.assertEqual(20.0, summary["p95Ms"])

    def test_comparison_does_not_claim_raw_parity_when_wrapper_is_slower(self):
        bench = load_bench()

        result = bench.comparison(
            {"p95Ms": 10.0, "maxMs": 11.0},
            {"p95Ms": 12.0, "maxMs": 13.0},
            {"p95Ms": 35.0, "maxMs": 40.0},
            {"p95Ms": 140.0, "maxMs": 150.0},
            {"p95Ms": 180.0, "maxMs": 190.0},
            {"p95Ms": 50.0, "maxMs": 55.0},
        )

        self.assertEqual(1.2, result["filesystemLaneToRawP95Ratio"])
        self.assertEqual(25.0, result["coldYggP95OverheadMs"])
        self.assertEqual(14.0, result["stalledYggToRawP95Ratio"])
        self.assertEqual(130.0, result["stalledYggP95OverheadMs"])
        self.assertEqual(
            170.0,
            result["acknowledgedStalledYggP95OverheadMs"],
        )
        self.assertEqual(
            40.0,
            result["activeIndexingHandoffYggP95OverheadMs"],
        )
        self.assertEqual(29.0, result["coldYggMaxOverheadMs"])
        self.assertEqual(44.0, result["activeIndexingHandoffYggMaxOverheadMs"])
        self.assertFalse(result["rawParitySupported"])
        self.assertFalse(result["rawMaxParitySupported"])

    def test_contract_requires_bounded_filesystem_fallback_for_every_stall(self):
        bench = load_bench()
        lanes = {
            "rawRipgrep": {"completed": 3, "timeouts": 0, "p95Ms": 40.0},
            "filesystemLane": {"completed": 3, "timeouts": 0, "p95Ms": 42.0},
            "coldYgg": {
                "completed": 3,
                "timeouts": 0,
                "p95Ms": 130.0,
                "filesystemProcessCounts": {"1": 3},
                "filesystemLauncherCounts": {"posix-spawn": 3},
            },
            "stalledYgg": {
                "completed": 3,
                "timeouts": 0,
                "p95Ms": 320.0,
                "degradationReasons": {"query-hedge": 3},
                "filesystemProcessCounts": {"1": 3},
                "filesystemLauncherCounts": {"posix-spawn": 3},
            },
            "acknowledgedStalledYgg": {
                "completed": 3,
                "timeouts": 0,
                "p95Ms": 420.0,
                "degradationReasons": {"query-hedge": 3},
                "filesystemProcessCounts": {"1": 3},
                "filesystemHandoffCounts": {"true": 3},
                "filesystemLauncherCounts": {"posix-spawn": 3},
            },
            "activeIndexingHandoffYgg": {
                "completed": 3,
                "timeouts": 0,
                "p95Ms": 130.0,
                "degradationReasons": {"active-indexing": 3},
                "filesystemProcessCounts": {"1": 3},
                "filesystemRepoCounts": {"1": 3},
                "filesystemHandoffCounts": {"true": 3},
                "filesystemLauncherCounts": {"posix-spawn": 3},
            },
        }

        contract = bench.availability_contract(lanes, 3, 200, 300)

        self.assertTrue(all(contract.values()))

        lanes["stalledYgg"]["p95Ms"] = 331.0
        lanes["stalledYgg"]["degradationReasons"] = {"query-hedge": 2}
        lanes["acknowledgedStalledYgg"]["p95Ms"] = 431.0
        lanes["acknowledgedStalledYgg"]["degradationReasons"] = {
            "query-hedge": 2,
        }
        lanes["acknowledgedStalledYgg"]["filesystemHandoffCounts"] = {
            "true": 2,
        }
        lanes["activeIndexingHandoffYgg"]["p95Ms"] = 131.0
        lanes["activeIndexingHandoffYgg"]["degradationReasons"] = {
            "active-indexing": 2,
        }
        lanes["activeIndexingHandoffYgg"]["filesystemProcessCounts"] = {
            "1": 2,
        }
        lanes["activeIndexingHandoffYgg"]["filesystemRepoCounts"] = {"1": 2}
        lanes["activeIndexingHandoffYgg"]["filesystemHandoffCounts"] = {
            "true": 2,
        }
        lanes["coldYgg"]["filesystemLauncherCounts"] = {"subprocess": 3}
        lanes["coldYgg"]["filesystemProcessCounts"] = {"2": 3}
        contract = bench.availability_contract(lanes, 3, 200, 300)
        self.assertFalse(contract["stalledP95WithinBound"])
        self.assertFalse(contract["stalledMaxWithinBound"])
        self.assertFalse(contract["stalledQueriesUsedFilesystem"])
        self.assertFalse(contract["acknowledgedStalledP95WithinBound"])
        self.assertFalse(contract["acknowledgedStalledMaxWithinBound"])
        self.assertFalse(contract["acknowledgedStalledQueriesUsedFilesystem"])
        self.assertFalse(contract["acknowledgedStalledUsedAcceptedHandoff"])
        self.assertFalse(contract["activeIndexingHandoffP95WithinBound"])
        self.assertFalse(contract["activeIndexingHandoffMaxWithinBound"])
        self.assertFalse(contract["activeIndexingHandoffUsedFilesystem"])
        self.assertFalse(contract["activeIndexingHandoffUsedOneProcess"])
        self.assertFalse(contract["activeIndexingHandoffUsedRequestedRepoScope"])
        self.assertFalse(contract["activeIndexingHandoffUsedAcceptedOrFinalHandoff"])
        self.assertFalse(contract["filesystemFallbackUsedPosixSpawn"])
        self.assertFalse(contract["oneFilesystemProcessPerFallback"])


if __name__ == "__main__":
    unittest.main()
