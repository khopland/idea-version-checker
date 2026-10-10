#!/usr/bin/env python3
"""Create and serve a disposable npm workspace for native IDEA validation.

The loopback registry serves metadata only. No install or tarball endpoint exists.
Edit registry-control.json to exercise delay/authentication recovery while running.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import time
from urllib.parse import unquote, urlsplit


PACKAGES = ("@fixture/alpha", "@fixture/beta", "@fixture/gamma")
VERSIONS = ("1.0.0", "1.0.1", "1.1.0", "2.0.0", "3.0.0-beta.1")


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def create_fixture(root, registry):
    if root.exists() and (not root.is_dir() or any(root.iterdir())):
        raise ValueError("fixture directory must be empty")
    root.mkdir(parents=True, exist_ok=True)
    write_json(root / "package.json", {
        "name": "native-npm-workspace", "version": "1.0.0", "private": True,
        "workspaces": ["packages/*"],
        "dependencies": {"@fixture/alpha": "^1.0.0", "alpha-alias": "npm:@fixture/alpha@~1.0.0"},
        "devDependencies": {"@fixture/beta": "1.0.0"},
    })
    write_json(root / "packages/app/package.json", {
        "name": "native-npm-app", "version": "1.0.0", "private": True,
        "dependencies": {"@fixture/alpha": "^1.0.0", "@fixture/gamma": "~1.0.0"},
    })
    write_json(root / "package-lock.json", {
        "name": "native-npm-workspace", "version": "1.0.0", "lockfileVersion": 3,
        "requires": True, "packages": {},
    })
    (root / ".npmrc").write_text(
        f"registry={registry}\n@fixture:registry={registry}\nfetch-retries=0\naudit=false\nfund=false\n",
        encoding="utf-8",
    )
    write_json(root / "registry-control.json", {"delayMs": 0, "deny": False})
    (root / "README.md").write_text(
        "# Disposable native npm validation workspace\n\n"
        "Configure a local Node.js interpreter and npm in IDEA. Open package.json, "
        "then review Patch (1.0.1), Minor (1.1.0), and Major (2.0.0) updates. "
        "Check alias operators, workspace scope, apply/undo and unchanged lockfile bytes. "
        "No npm install is needed. The registry serves metadata only.\n\n"
        "While the server runs, edit registry-control.json: delayMs delays responses; "
        "deny returns HTTP 401. Restore delayMs=0 and deny=false before retrying. "
        "registry-requests.jsonl records paths/status/duration without credentials.\n",
        encoding="utf-8",
    )


def registry_handler(root):
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            started = time.monotonic()
            control = json.loads((root / "registry-control.json").read_text(encoding="utf-8"))
            time.sleep(max(0, min(control.get("delayMs", 0), 60000)) / 1000)
            name = unquote(urlsplit(self.path).path).lstrip("/")
            status = 401 if control.get("deny") else 200 if name in PACKAGES else 404
            value = {"error": "Fixture authentication rejected" if status == 401 else "Not found"}
            if status == 200:
                value = {"name": name, "dist-tags": {"latest": "2.0.0"}, "versions": {
                    version: {"name": name, "version": version} for version in VERSIONS
                }}
            body = json.dumps(value).encode()
            try:
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass  # Cancellation is a recorded incomplete client interaction.
            finally:
                record = {"package": name, "status": status,
                          "elapsedMs": round((time.monotonic() - started) * 1000, 2)}
                with lock, (root / "registry-requests.jsonl").open("a", encoding="utf-8") as log:
                    log.write(json.dumps(record) + "\n")

        def log_message(self, *args):
            pass

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--port", type=int, default=0, help="Loopback port; 0 chooses a free port")
    args = parser.parse_args()
    root = args.directory.resolve()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), registry_handler(root))
    try:
        registry = f"http://127.0.0.1:{server.server_port}/"
        try:
            create_fixture(root, registry)
        except ValueError as error:
            parser.error(str(error))
        print(root, flush=True)
        print(f"Metadata registry: {registry} (Ctrl+C to stop)", flush=True)
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
