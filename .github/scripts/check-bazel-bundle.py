"""Validate the portable bundle's wire contract, not merely its ZIP listing."""
import io
import json
import re
import sys
import zipfile
from pathlib import Path


def check(path):
    data = Path(path).read_bytes()
    assert data.startswith(b"\x89PNG\r\n\x1a\n"), "missing PNG cover"
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        assert archive.testzip() is None, "corrupt ZIP member"
        bundle = json.loads(archive.read("bundle.json"))
        previews = json.loads(archive.read("previews.json"))
        assert bundle["schemaVersion"] == 4
        assert bundle["producer"] == "bazel"
        assert bundle["backend"] == "desktop"
        assert bundle["modulePath"] == "//:app"
        assert bundle["resolution"] == "coordinates"
        assert bundle["previewIds"] == ["GreetingKt.Greeting"]
        assert [p["id"] for p in previews["previews"]] == bundle["previewIds"]
        entries = bundle["classpath"]
        assert entries[0] == {"kind": "module", "path": "classes/app.jar"}
        assert len(entries) > 1, "missing Compose runtime closure"
        for entry in entries[1:]:
            assert entry["kind"] == "maven", f"unexpected embedded dep: {entry}"
            assert all(entry[k] for k in ("group", "artifact", "version", "type"))
            assert re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]), entry
        with zipfile.ZipFile(io.BytesIO(archive.read("classes/app.jar"))) as app:
            assert "com/example/bazeldesktop/GreetingKt.class" in app.namelist()
        assert not any(n.startswith("libs/") for n in archive.namelist())
    print(f"Validated {path}: {len(entries) - 1} verified Maven coordinates")


if __name__ == "__main__":
    check(sys.argv[1])
