import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from release_metadata import metadata


SCRIPTS = Path(__file__).resolve().parent


class MetadataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def prepare(self, version="1.0.1", notes="- Fixed a bug."):
        (self.root / "gradle.properties").write_text(f"group=example\nversion={version}\n")
        (self.root / "CHANGELOG.md").write_text(f"# Changelog\n\n## [Unreleased]\n\n## [{version}] - 2026-10-07\n\n### Fixed\n\n{notes}\n")

    def test_stable_and_prefixed_tags(self):
        self.prepare()
        for tag in ("1.0.1", "v1.0.1"):
            self.assertEqual(metadata(self.root, tag, False)["channel"], "default")

    def test_prerelease_channel(self):
        self.prepare("1.1.0-rc.1")
        self.assertEqual(metadata(self.root, "1.1.0-rc.1", True)["channel"], "eap")

    def test_tag_mismatch(self):
        self.prepare()
        with self.assertRaisesRegex(ValueError, "does not match"):
            metadata(self.root, "1.0.0", False)

    def test_release_status_mismatch(self):
        for version, status in (("1.0.1", True), ("1.1.0-beta.1", False)):
            self.prepare(version)
            with self.assertRaisesRegex(ValueError, "prerelease status"):
                metadata(self.root, version, status)

    def test_snapshot_not_releasable(self):
        self.prepare("1.0.1-SNAPSHOT")
        self.assertEqual(metadata(self.root)["releasable"], "false")
        with self.assertRaises(ValueError):
            metadata(self.root, "1.0.1-SNAPSHOT")

    def test_missing_or_empty_notes(self):
        self.prepare(notes="")
        self.assertEqual(metadata(self.root)["releasable"], "false")
        self.prepare()
        (self.root / "CHANGELOG.md").write_text("## [Unreleased]\n\n- New work.\n")
        with self.assertRaisesRegex(ValueError, "Finalize"):
            metadata(self.root, "1.0.1")

    def test_invalid_versions(self):
        for version in ("01.0.0", "1.0.0-rc.01", "1.0", "1.0.0+build.1", "bad/version"):
            self.prepare(version)
            with self.assertRaises(ValueError):
                metadata(self.root)


class DraftTest(unittest.TestCase):
    def run_draft(self, releases=(), remote_tag="", api_failure=False):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            binaries = root / "bin"
            binaries.mkdir()
            # Fake only external side effects; execute the real draft script and jq.
            gh = binaries / "gh"
            gh.write_text("""#!/usr/bin/env python3
import json, os, sys
with open(os.environ['CALL_LOG'], 'a') as log:
    log.write(json.dumps(sys.argv[1:]) + '\\n')
if sys.argv[1] == 'api':
    if os.environ['API_FAILURE'] == 'true':
        sys.exit(1)
    print(os.environ['RELEASES'])
""")
            git = binaries / "git"
            git.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$REMOTE_TAG"\n')
            gradle = root / "gradlew"
            gradle.write_text("#!/usr/bin/env bash\nexit 0\n")
            for path in (gh, git, gradle):
                path.chmod(0o755)
            (root / "build/distributions").mkdir(parents=True)
            (root / "build/distributions/version-checker-1.0.1.zip").write_text("test zip")
            log = root / "calls.jsonl"
            env = dict(os.environ, PATH=f"{binaries}:{os.environ['PATH']}",
                       CALL_LOG=str(log), RELEASES=json.dumps(releases), REMOTE_TAG=remote_tag,
                       API_FAILURE=str(api_failure).lower(), VERSION="1.0.1", CHANNEL="default",
                       GITHUB_REPOSITORY="example/plugin", GITHUB_SHA="tested-commit")
            result = subprocess.run(["bash", str(SCRIPTS / "prepare_release_draft.sh")],
                                    cwd=root, env=env, capture_output=True, text=True)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            return result, calls

    def test_published_release_is_unchanged(self):
        result, calls = self.run_draft([{"tag_name": "1.0.1", "draft": False}])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 1)

    def test_published_alias_takes_precedence_over_a_draft(self):
        result, calls = self.run_draft([{"tag_name": "1.0.1", "draft": True},
                                       {"tag_name": "v1.0.1", "draft": False}])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 1)

    def test_existing_draft_is_pinned_and_unrelated_drafts_are_preserved(self):
        result, calls = self.run_draft([{"tag_name": "1.0.1", "draft": True},
                                       {"tag_name": "2.0.0", "draft": True}])
        self.assertEqual(result.returncode, 0, result.stderr)
        edit = calls[1]
        self.assertEqual(edit[:3], ["release", "edit", "1.0.1"])
        self.assertEqual(edit[edit.index("--target") + 1], "tested-commit")
        self.assertEqual(calls[-1][:3], ["release", "upload", "1.0.1"])
        self.assertNotIn("DELETE", str(calls))

    def test_new_draft_is_pinned(self):
        result, calls = self.run_draft()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[1][:3], ["release", "create", "1.0.1"])
        self.assertEqual(calls[1][calls[1].index("--target") + 1], "tested-commit")

    def test_existing_annotated_tag_for_another_commit_is_unchanged(self):
        result, calls = self.run_draft(remote_tag="tag-object\trefs/tags/1.0.1\nold-commit\trefs/tags/1.0.1^{}")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 1)

    def test_api_failure_stops_before_mutation(self):
        result, calls = self.run_draft(api_failure=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(calls), 1)


if __name__ == "__main__":
    unittest.main()
