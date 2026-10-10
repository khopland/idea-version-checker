#!/usr/bin/env python3
"""Summarize anonymous Version Checker traces without exporting other IDEA log content."""
import argparse
import json
import math
import re
import statistics
from collections import defaultdict
from pathlib import Path

LINE = re.compile(r"version-check stage=([A-Z_0-9]+) elapsedNs=(\d+) count=(\d+) interaction=(\d+) startNs=(-?\d+) endNs=(-?\d+) originNs=(-?\d+)")
BOUNDARIES = {
    "editor_hint": ("FILE_ACTIVATED", "DIAGNOSTIC_VISIBLE"),
    "preview": ("PREVIEW_INVOKED", "PREVIEW_READY"),
    "local_fix": ("FIX_INVOKED", "EDITOR_TEXT_CHANGED"),
}
# Count host invocations, rather than both Gradle's invocation and its nested query span.
NATIVE_INVOCATIONS = {"MAVEN_DEPENDENCY_GOAL", "MAVEN_PLUGIN_GOAL", "MAVEN_PARENT_GOAL", "MAVEN_METADATA_BATCH", "NPM_VIEW", "GRADLE_NATIVE"}
# Priorities assign every nanosecond to at most one category, even with nested spans.
CATEGORIES = {
    "repository": {"MAVEN_DEPENDENCY_GOAL", "MAVEN_PLUGIN_GOAL", "MAVEN_PARENT_GOAL", "MAVEN_METADATA_BATCH", "NPM_VIEW", "GRADLE_QUERY"},
    "native_setup": {"MAVEN_SESSION_POOL", "MAVEN_SESSION", "MAVEN_MODEL", "MAVEN_SETTINGS", "MAVEN_METADATA_EXPIRATION", "NPM_RUNTIME_RESOLUTION", "NPM_COMMAND_SETUP", "GRADLE_SETUP", "GRADLE_CONFIGURATION"},
    "queue": {"CHECK_QUEUE", "HIGHLIGHT_QUEUE"},
    "shared_metadata_wait": {"NPM_METADATA_WAIT"},
    "preparation": {"MAVEN_SNAPSHOT", "NPM_SNAPSHOT", "GRADLE_SNAPSHOT", "MAVEN_PROJECT_INPUTS", "MAVEN_DECLARATIONS", "NPM_VERSION_INDEX", "PREVIEW_PLAN", "HIGHLIGHT_RESTART"},
    "idea_highlighting": {"IDEA_HIGHLIGHTING"},
}


def parse(lines, include_uncorrelated=False):
    interactions = defaultdict(list)
    for line in lines:
        match = LINE.search(line)
        if not match:
            continue
        stage, elapsed, count, identity, start, end, origin = match.groups()
        if identity == "0" and not include_uncorrelated:
            continue  # An uncorrelated stage cannot be added to an interaction.
        event = dict(stage=stage, count=int(count), start=int(start), end=int(end), origin=int(origin))
        if event["end"] >= event["start"] and int(elapsed) == event["end"] - event["start"]:
            interactions[(int(identity), int(origin))].append(event)
    return interactions


def partition(events, start, end):
    """Exclusive observed spans; endpoints/overall CHECK never consume their nested stages."""
    spans = [(max(start, e["start"]), min(end, e["end"]), e["stage"]) for e in events
             if e["end"] > start and e["start"] < end]
    points = sorted({start, end} | {p for a, b, _ in spans for p in (a, b)})
    totals = {category: 0 for category in CATEGORIES}
    totals["other_or_unobserved"] = 0
    for left, right in zip(points, points[1:]):
        stages = {stage for a, b, stage in spans if a <= left and b >= right}
        category = next((category for category, members in CATEGORIES.items() if stages & members), "other_or_unobserved")
        totals[category] += right - left
    return {key: value / 1_000_000 for key, value in totals.items()}


def summarize(interactions):
    samples = {name: [] for name in BOUNDARIES}
    native = [(key, event) for key, events in interactions.items() for event in events
              if event["stage"] in NATIVE_INVOCATIONS]
    for (identity, origin), events in interactions.items():
        if identity == 0:
            continue  # Retain these spans for overlap checks, never as endpoint samples.
        for name, (begin, finish) in BOUNDARIES.items():
            starts = [e["start"] for e in events if e["stage"] == begin]
            endpoints = [e for e in events if e["stage"] == finish and
                         (name != "local_fix" or e.get("count", 1) > 0)]
            if not starts or not endpoints:
                continue
            endpoint = min(endpoints, key=lambda e: e["end"])
            start, end = min(starts), endpoint["end"]
            if end < start:
                continue
            owned = [event for key, event in native if key == (identity, origin)]
            overlapping = [(key, event) for key, event in native if event["end"] > start and event["start"] < end]
            invocation_counts = dict(owned=len(owned),
                                     startedDuringEndpoint=sum(start <= event["start"] < end for _, event in native),
                                     overlappingEndpoint=len(overlapping),
                                     uncorrelatedOverlappingEndpoint=sum(key[0] == 0 for key, _ in overlapping))
            samples[name].append(dict(interaction=identity, originNs=origin, elapsedMs=(end-start)/1_000_000,
                                      endpointCount=endpoint.get("count", 1), nativeInvocations=invocation_counts,
                                      exclusiveMs=partition(events, start, end)))
    report = {}
    for name, values in samples.items():
        elapsed = sorted(v["elapsedMs"] for v in values)
        report[name] = dict(samples=len(values), medianMs=statistics.median(elapsed) if elapsed else None,
                            p95Ms=elapsed[math.ceil(len(elapsed)*0.95)-1] if elapsed else None,
                            incompleteInteractions=sum(any(e["stage"] == BOUNDARIES[name][0] for e in events)
                                                       for key, events in interactions.items() if key[0] != 0) - len(values),
                            nativeInvocations={metric: sum(v["nativeInvocations"][metric] for v in values)
                                               for metric in ("owned", "startedDuringEndpoint", "overlappingEndpoint", "uncorrelatedOverlappingEndpoint")},
                            minimumSampleCountMet=len(values) >= 100, interactions=values)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--output", type=Path, required=True, help="Sanitized JSON report")
    parser.add_argument("--case", required=True, help="Non-sensitive label for a single project/scope/mode/cache condition")
    parser.add_argument("--ecosystem", choices=("maven", "npm", "gradle", "mixed"), required=True)
    parser.add_argument("--scope", choices=("current-file", "project"), required=True)
    parser.add_argument("--mode", choices=("patch", "minor", "major"), required=True)
    parser.add_argument("--cache", choices=("warm", "cold", "setup"), required=True)
    parser.add_argument("--modules", type=int, required=True)
    parser.add_argument("--declarations", type=int, required=True)
    parser.add_argument("--idea-version", choices=("2025.3.6.1", "2026.1.4"), required=True)
    parser.add_argument("--repository-case", choices=("fast-local", "remote", "private-delayed", "slow-package", "offline", "auth-failure"), required=True)
    args = parser.parse_args()
    if args.modules < 1 or args.declarations < 0:
        parser.error("modules must be positive and declarations non-negative")
    with args.log.open(errors="replace") as source:
        interactions = parse(source, include_uncorrelated=True)
    stages = defaultdict(list)
    for events in interactions.values():
        for event in events:
            stages[event["stage"]].append((event["end"] - event["start"]) / 1_000_000)
    stage_summary = {name: {"samples": len(values), "medianMs": statistics.median(values),
                            "p95Ms": sorted(values)[math.ceil(len(values) * .95) - 1]}
                     for name, values in stages.items()}
    result = {"case": args.case, "conditions": {"ecosystem": args.ecosystem, "scope": args.scope, "mode": args.mode,
              "cache": args.cache, "modules": args.modules, "declarations": args.declarations,
              "ideaVersion": args.idea_version, "repositoryCase": args.repository_case},
              "interactionsObserved": sum(key[0] != 0 for key in interactions),
              "uncorrelatedEvents": sum(len(events) for key, events in interactions.items() if key[0] == 0),
              "measurements": summarize(interactions), "stages": stage_summary}
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    for name, measurement in result["measurements"].items():
        print(f"{name}: n={measurement['samples']} median={measurement['medianMs']} ms p95={measurement['p95Ms']} ms")
        print(f"  incomplete={measurement['incompleteInteractions']} native invocation observations={measurement['nativeInvocations']}")
    print("Complete-check and first-result spans remain separate from visible endpoints. Keep cold/setup cases separate.")


if __name__ == "__main__":
    main()
