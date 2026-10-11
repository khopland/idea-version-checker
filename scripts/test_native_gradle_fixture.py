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
            for dsl, suffix, literal in (
                    ("groovy", ".gradle", b"implementation 'fixture:alpha:1.0.0'"),
                    ("kotlin", ".gradle.kts", b'implementation("fixture:alpha:1.0.0")')):
                with self.subTest(dsl=dsl):
                    root = base / ("dense-" + dsl)
                    command = [sys.executable, str(SCRIPT), str(root), "--repository", str(base / "repo"),
                               "--declarations-per-module", "10000", "--repeat-artifact", "alpha",
                               "--dsl", dsl]
                    subprocess.run(command, check=True, capture_output=True)
                    build = root / ("build" + suffix)
                    original = build.read_bytes()
                    self.assertEqual(10000, original.count(literal))
                    self.assertEqual(100, original.count(b"dependencies {"))
                    for block in original.split(b"dependencies {\n")[1:]:
                        self.assertEqual(100, block.split(b"}\n", 1)[0].count(literal))
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
