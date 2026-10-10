import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("trace", Path(__file__).with_name("summarize-performance.py"))
trace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trace)


class TraceSummaryTest(unittest.TestCase):
    def test_overlapping_goals_and_setup_are_counted_once(self):
        events = [dict(stage=s, start=a, end=b) for s, a, b in [
            ("PREVIEW_PREPARATION", 0, 100), ("CHECK", 5, 80), ("CHECK_QUEUE", 0, 10),
            ("MAVEN_SESSION", 10, 30), ("MAVEN_DEPENDENCY_GOAL", 20, 70), ("MAVEN_PLUGIN_GOAL", 40, 80)]]
        result = trace.partition(events, 0, 100)
        self.assertAlmostEqual(100 / 1_000_000, sum(result.values()))
        self.assertAlmostEqual(60 / 1_000_000, result["repository"])
        self.assertAlmostEqual(10 / 1_000_000, result["native_setup"])

    def test_missing_endpoints_are_excluded_and_restarted_processes_do_not_merge(self):
        lines = ["Credentials and unrelated IDEA log lines must be discarded"]
        for origin in (1000, 2000):
            for stage, end in (("PREVIEW_INVOKED", origin), ("PREVIEW_READY", origin + 100)):
                lines.append(f"version-check stage={stage} elapsedNs={end-origin} count=1 interaction=1 startNs={origin} endNs={end} originNs={origin}")
        lines.append("version-check stage=FIX_INVOKED elapsedNs=0 count=1 interaction=2 startNs=3000 endNs=3000 originNs=3000")
        interactions = trace.parse(lines)
        result = trace.summarize(interactions)
        self.assertEqual(2, result["preview"]["samples"])
        self.assertEqual(0, result["local_fix"]["samples"])
        self.assertFalse(result["preview"]["minimumSampleCountMet"])

    def test_median_p95_and_minimum_count_use_complete_independent_interactions(self):
        interactions = {}
        for identity in range(1, 101):
            origin = identity * 1_000_000_000
            interactions[(identity, origin)] = [
                dict(stage="PREVIEW_INVOKED", start=origin, end=origin),
                dict(stage="PREVIEW_READY", start=origin, end=origin + identity * 1_000_000)]
        report = trace.summarize(interactions)["preview"]
        self.assertEqual(100, report["samples"])
        self.assertEqual(50.5, report["medianMs"])
        self.assertEqual(95, report["p95Ms"])
        self.assertTrue(report["minimumSampleCountMet"])


if __name__ == "__main__":
    unittest.main()
