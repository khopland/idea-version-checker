import importlib.util
import json
import base64
import hashlib
import io
from pathlib import Path
import tempfile
import tarfile
import shutil
import subprocess
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import urlopen


spec = importlib.util.spec_from_file_location("fixture", Path(__file__).with_name("create-native-npm-fixture.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class NativeNpmFixtureTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("npm"), "npm is required for installable fixture acceptance")
    def test_installable_registry_supports_aliases_workspaces_and_lockfile_sync(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "workspace"
            server = fixture.ThreadingHTTPServer(("127.0.0.1", 0), fixture.registry_handler(root, installable=True))
            registry = f"http://127.0.0.1:{server.server_port}/"
            fixture.create_fixture(root, registry, installable=True)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with urlopen(registry + "@fixture%2falpha", timeout=5) as response:
                    metadata = json.load(response)["versions"]["1.0.0"]
                with urlopen(metadata["dist"]["tarball"], timeout=5) as response:
                    data = response.read()
                self.assertEqual(metadata["dist"]["integrity"],
                                 "sha512-" + base64.b64encode(hashlib.sha512(data).digest()).decode())
                with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
                    package = json.load(archive.extractfile("package/package.json"))
                    self.assertEqual("@fixture/alpha", package["name"])
                    self.assertNotIn("scripts", package)
                command = [shutil.which("npm"), "install", "--ignore-scripts", "--no-audit", "--no-fund",
                           "--cache", str(Path(directory) / "cache")]
                subprocess.run(command, cwd=root, check=True, capture_output=True, timeout=60)
                lock = json.loads((root / "package-lock.json").read_text())
                self.assertTrue(lock["packages"]["node_modules/native-npm-app"]["link"])
                self.assertEqual("@fixture/alpha", lock["packages"]["node_modules/alpha-alias"]["name"])
                self.assertEqual("1.0.0", lock["packages"]["node_modules/@fixture/beta"]["version"])
                manifest = json.loads((root / "package.json").read_text())
                manifest["devDependencies"]["@fixture/beta"] = "2.0.0"
                fixture.write_json(root / "package.json", manifest)
                subprocess.run(command, cwd=root, check=True, capture_output=True, timeout=60)
                lock = json.loads((root / "package-lock.json").read_text())
                self.assertEqual("2.0.0", lock["packages"]["node_modules/@fixture/beta"]["version"])
                self.assertEqual("2.0.0", json.loads((root / "node_modules/@fixture/beta/package.json").read_text())["version"])
                with self.assertRaises(HTTPError) as failure:
                    urlopen(registry + "@fixture/alpha/-/not-allowed.tgz", timeout=5)
                self.assertEqual(404, failure.exception.code)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=5)

    def test_nonempty_destination_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sentinel = root / "keep.txt"
            sentinel.write_text("keep")
            with self.assertRaisesRegex(ValueError, "must be empty"):
                fixture.create_fixture(root, "http://127.0.0.1:1/")
            self.assertEqual("keep", sentinel.read_text())
            self.assertEqual([sentinel], list(root.iterdir()))

    def test_metadata_only_registry_and_live_authentication_recovery(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            server = fixture.ThreadingHTTPServer(("127.0.0.1", 0), fixture.registry_handler(root))
            registry = f"http://127.0.0.1:{server.server_port}/"
            fixture.create_fixture(root, registry)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                metadata = json.load(urlopen(registry + "@fixture%2falpha", timeout=5))
                self.assertEqual(list(fixture.VERSIONS), list(metadata["versions"]))
                self.assertEqual("2.0.0", metadata["dist-tags"]["latest"])
                with self.assertRaises(HTTPError) as failure:
                    urlopen(registry + "@fixture/alpha/-/alpha-1.0.0.tgz", timeout=5)
                self.assertEqual(404, failure.exception.code)
                fixture.write_json(root / "registry-control.json", {"deny": True})
                with self.assertRaises(HTTPError) as failure:
                    urlopen(registry + "@fixture%2falpha", timeout=5)
                self.assertEqual(401, failure.exception.code)
                fixture.write_json(root / "registry-control.json", {"deny": False})
                self.assertEqual(200, urlopen(registry + "@fixture%2falpha", timeout=5).status)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=5)
            records = [json.loads(line) for line in (root / "registry-requests.jsonl").read_text().splitlines()]
            self.assertEqual([200, 404, 401, 200], [record["status"] for record in records])
            self.assertTrue(all(set(record) == {"package", "status", "elapsedMs"} for record in records))


if __name__ == "__main__":
    unittest.main()
