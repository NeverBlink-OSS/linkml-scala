#!/usr/bin/env python3
"""Check a built or installed Ohi executable against a local publication and optional version."""

from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
from threading import Thread

binary = str(Path(sys.argv[1]).resolve())
if len(sys.argv) > 2:
    version = subprocess.run(
        [binary, "version", "--format", "json"],
        check=True, capture_output=True, text=True, timeout=60,
    )
    assert json.loads(version.stdout)["linkml_scala_version"] == sys.argv[2], version.stdout

examples = Path(__file__).resolve().parent.parent / "ohi" / "examples"
handler = partial(SimpleHTTPRequestHandler, directory=str(examples))
with ThreadingHTTPServer(("127.0.0.1", 0), handler) as server:
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        result = subprocess.run(
            [binary, "inspect", "--json",
             f"http://127.0.0.1:{server.server_port}/sensors-package.json"],
            check=True, capture_output=True, text=True, timeout=60,
        )
        publication = json.loads(result.stdout)
        assert publication["namespace"] == "example", result.stdout
        assert publication["name"] == "sensors", result.stdout
        assert publication["version"] == "1.0.0", result.stdout
        assert publication["artifacts"][0]["format"] == "owl-turtle", result.stdout
        print("Ohi inspected example/sensors 1.0.0 over HTTP.")
    finally:
        server.shutdown()
        thread.join()

# Project commands load a separate declaration schema and the YAML/lock codecs.
with TemporaryDirectory(prefix="ohi-check-") as directory:
    project = Path(directory) / "project"
    subprocess.run([binary, "init", str(project)], check=True, timeout=60)
    result = subprocess.run(
        [binary, "lock", "--project", str(project), "--json"],
        check=True, capture_output=True, text=True, timeout=60,
    )
    assert json.loads(result.stdout)["lock_changed"] is True, result.stdout
    saved = json.loads((project / "ohi.lock.json").read_text(encoding="utf-8"))
    assert saved == {"format_version": 2, "selections": [], "files": []}, saved
    print("Ohi initialized a project and wrote its empty lock.")
