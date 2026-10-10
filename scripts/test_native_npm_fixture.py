import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import urlopen


spec = importlib.util.spec_from_file_location("fixture", Path(__file__).with_name("create-native-npm-fixture.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class NativeNpmFixtureTest(unittest.TestCase):
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
