import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("trace", Path(__file__).with_name("summarize-performance.py"))
trace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trace)


class TraceSummaryTest(unittest.TestCase):
    def test_plugin_prerequisites_are_distinct_native_work_and_indexing_is_local(self):
        interactions = {(1, 100): [dict(stage=s, start=a, end=b) for s, a, b in [
            ("PREVIEW_INVOKED", 100, 100), ("MAVEN_METADATA_BATCH", 110, 130),
            ("MAVEN_VERSION_INDEX", 130, 140), ("MAVEN_PLUGIN_PREREQUISITES", 140, 180),
            ("PREVIEW_READY", 100, 200)]]}
        report = trace.summarize(interactions)["preview"]
        self.assertEqual(2, report["nativeInvocations"]["owned"])
        self.assertAlmostEqual(10 / 1_000_000, report["interactions"][0]["exclusiveMs"]["preparation"])

    def test_maven_wait_does_not_double_count_native_work(self):
        interactions = {(1, 100): [dict(stage=s, start=a, end=b) for s, a, b in [
            ("PREVIEW_INVOKED", 100, 100), ("MAVEN_METADATA_WAIT", 110, 190),
            ("MAVEN_METADATA_BATCH", 120, 180), ("PREVIEW_READY", 100, 200)]]}
        report = trace.summarize(interactions)["preview"]
        self.assertEqual(1, report["nativeInvocations"]["owned"])
        self.assertAlmostEqual(60 / 1_000_000, report["interactions"][0]["exclusiveMs"]["repository"])
        self.assertAlmostEqual(20 / 1_000_000, report["interactions"][0]["exclusiveMs"]["shared_metadata_wait"])

    def test_batched_maven_metadata_counts_native_work_but_cache_reuse_does_not(self):
        interactions = {(1, 100): [dict(stage=s, start=a, end=b) for s, a, b in [
            ("PREVIEW_INVOKED", 100, 100), ("MAVEN_METADATA_BATCH", 120, 180),
            ("MAVEN_SESSION_POOL", 110, 115), ("MAVEN_METADATA_REUSED", 150, 150), ("PREVIEW_READY", 100, 200)]]}
        report = trace.summarize(interactions)["preview"]
        self.assertEqual(1, report["nativeInvocations"]["owned"])
        self.assertAlmostEqual(60 / 1_000_000, report["interactions"][0]["exclusiveMs"]["repository"])
        self.assertAlmostEqual(5 / 1_000_000, report["interactions"][0]["exclusiveMs"]["native_setup"])

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

    def test_native_queries_distinguish_ownership_starts_and_background_overlap(self):
        interactions = {
            (1, 100): [dict(stage="FIX_INVOKED", start=100, end=100),
                       dict(stage="EDITOR_TEXT_CHANGED", start=100, end=200, count=2)],
            (2, 50): [dict(stage="MAVEN_DEPENDENCY_GOAL", start=50, end=150)],
            (3, 125): [dict(stage="NPM_VIEW", start=125, end=250)],
            (4, 200): [dict(stage="NPM_VIEW", start=200, end=300)],
            (0, 90): [dict(stage="GRADLE_NATIVE", start=90, end=175)],
        }
        sample = trace.summarize(interactions)["local_fix"]["interactions"][0]
        self.assertEqual(2, sample["endpointCount"])
        self.assertEqual(dict(owned=0, startedDuringEndpoint=1, overlappingEndpoint=3,
                              uncorrelatedOverlappingEndpoint=1), sample["nativeInvocations"])
        # Background work is reported separately, not charged to the fix's exclusive stages.
        self.assertEqual(0, sample["exclusiveMs"]["repository"])

    def test_gradle_nested_query_is_not_a_second_native_invocation(self):
        interactions = {(1, 100): [dict(stage=s, start=a, end=b) for s, a, b in [
            ("PREVIEW_INVOKED", 100, 100), ("GRADLE_NATIVE", 120, 180),
            ("GRADLE_QUERY", 140, 170), ("PREVIEW_READY", 100, 200)]]}
        report = trace.summarize(interactions)["preview"]
        self.assertEqual(dict(owned=1, startedDuringEndpoint=1, overlappingEndpoint=1,
                              uncorrelatedOverlappingEndpoint=0), report["nativeInvocations"])

    def test_uncorrelated_native_spans_can_be_retained_without_creating_samples(self):
        line = "version-check stage=NPM_VIEW elapsedNs=20 count=1 interaction=0 startNs=100 endNs=120 originNs=100"
        self.assertFalse(trace.parse([line]))
        parsed = trace.parse([line], include_uncorrelated=True)
        self.assertEqual(1, len(parsed))
        self.assertTrue(all(report["samples"] == 0 for report in trace.summarize(parsed).values()))

    def test_incomplete_and_empty_fix_interactions_do_not_count_as_changed_text(self):
        interactions = {
            (1, 100): [dict(stage="FIX_INVOKED", start=100, end=100)],
            (2, 200): [dict(stage="FIX_INVOKED", start=200, end=200),
                       dict(stage="EDITOR_TEXT_CHANGED", start=200, end=250, count=0)],
        }
        report = trace.summarize(interactions)["local_fix"]
        self.assertEqual(0, report["samples"])
        self.assertEqual(2, report["incompleteInteractions"])

    def test_owned_query_after_endpoint_is_still_visible_without_claiming_overlap(self):
        interactions = {(1, 100): [dict(stage=s, start=a, end=b) for s, a, b in [
            ("PREVIEW_INVOKED", 100, 100), ("PREVIEW_READY", 100, 200), ("NPM_VIEW", 210, 250)]]}
        counts = trace.summarize(interactions)["preview"]["nativeInvocations"]
        self.assertEqual(dict(owned=1, startedDuringEndpoint=0, overlappingEndpoint=0,
                              uncorrelatedOverlappingEndpoint=0), counts)


if __name__ == "__main__":
    unittest.main()
