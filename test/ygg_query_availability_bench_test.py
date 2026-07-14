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
            {"p95Ms": 10.0},
            {"p95Ms": 12.0},
            {"p95Ms": 35.0},
            {"p95Ms": 140.0},
        )

        self.assertEqual(1.2, result["filesystemLaneToRawP95Ratio"])
        self.assertEqual(25.0, result["coldYggP95OverheadMs"])
        self.assertEqual(14.0, result["stalledYggToRawP95Ratio"])
        self.assertEqual(130.0, result["stalledYggP95OverheadMs"])
        self.assertFalse(result["rawParitySupported"])

    def test_contract_requires_bounded_filesystem_fallback_for_every_stall(self):
        bench = load_bench()
        lanes = {
            "rawRipgrep": {"completed": 3, "timeouts": 0, "p95Ms": 40.0},
            "filesystemLane": {"completed": 3, "timeouts": 0, "p95Ms": 42.0},
            "coldYgg": {"completed": 3, "timeouts": 0, "p95Ms": 130.0},
            "stalledYgg": {
                "completed": 3,
                "timeouts": 0,
                "p95Ms": 320.0,
                "degradationReasons": {"query-timeout": 3},
            },
        }

        contract = bench.availability_contract(lanes, 3, 200)

        self.assertTrue(all(contract.values()))

        lanes["stalledYgg"]["p95Ms"] = 331.0
        lanes["stalledYgg"]["degradationReasons"] = {"query-timeout": 2}
        contract = bench.availability_contract(lanes, 3, 200)
        self.assertFalse(contract["stalledP95WithinBound"])
        self.assertFalse(contract["stalledQueriesUsedFilesystem"])


if __name__ == "__main__":
    unittest.main()
