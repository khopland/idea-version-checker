from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("create-native-gradle-fixture.py")


class NativeGradleFixtureTest(unittest.TestCase):
    def test_dense_repeated_coordinates_and_destination_protection(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            repository = base / "repo/fixture/alpha"
            repository.mkdir(parents=True)
            (repository / "maven-metadata.xml").write_text("<metadata/>")
            root = base / "dense"
            command = [sys.executable, str(SCRIPT), str(root), "--repository", str(base / "repo"),
                       "--declarations-per-module", "1000", "--repeat-artifact", "alpha"]
            subprocess.run(command, check=True, capture_output=True)
            build = root / "build.gradle"
            original = build.read_bytes()
            self.assertEqual(1000, original.count(b"implementation 'fixture:alpha:1.0.0'"))
            self.assertTrue((root / "gradle/wrapper/gradle-wrapper.jar").is_file())
            second = subprocess.run(command, capture_output=True)
            self.assertNotEqual(0, second.returncode)
            self.assertIn(b"must be empty", second.stderr)
            self.assertEqual(original, build.read_bytes())

    def test_repeated_artifact_cannot_escape_repository_or_script_literal(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ("../alpha", "alpha'", "alpha/other", "alpha\nother"):
                root = Path(directory) / "fixture"
                result = subprocess.run([sys.executable, str(SCRIPT), str(root), "--repository", directory,
                                         "--repeat-artifact", name], capture_output=True)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(root.exists())


if __name__ == "__main__":
    unittest.main()
