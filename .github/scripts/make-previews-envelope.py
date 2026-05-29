#!/usr/bin/env python3
"""
Convert the compose-preview Gradle plugin's previews.json discovery manifest
into a wire-compatible _previews.json envelope for the upstream apply action's
skip-render path.  Adds pngPath (absolute) and sha256 to each capture entry.

Usage: python3 make-previews-envelope.py <previews_dir> <module_path> [output]
  previews_dir  directory containing previews.json and renders/
  module_path   Gradle module path, e.g. ':compose-preview-scripting:demo'
  output        output file path (default: _previews.json in cwd)
"""
import hashlib
import json
import os
import sys


def main() -> None:
    previews_dir = sys.argv[1]
    module_path = sys.argv[2]
    out_path = sys.argv[3] if len(sys.argv) > 3 else "_previews.json"

    renders_dir = os.path.join(previews_dir, "renders")
    with open(os.path.join(previews_dir, "previews.json")) as fh:
        manifest = json.load(fh)

    variant = manifest.get("variant", "desktop")
    out_previews = []
    for p in manifest["previews"]:
        captures = []
        for cap in p.get("captures", []):
            render_output = cap["renderOutput"]
            png_path = os.path.abspath(
                os.path.join(renders_dir, os.path.basename(render_output))
            )
            with open(png_path, "rb") as fh:
                sha256 = hashlib.sha256(fh.read()).hexdigest()
            captures.append(
                {
                    "renderOutput": render_output,
                    "pngPath": png_path,
                    "sha256": sha256,
                    "cost": float(cap.get("cost", 1.0)),
                }
            )
        out_previews.append(
            {
                "id": p["id"],
                "module": module_path,
                "className": p["className"],
                "functionName": p["functionName"],
                "sourceFile": p["sourceFile"],
                "captures": captures,
            }
        )

    envelope = {
        "schema": "compose-previews/v1",
        "module": module_path,
        "variant": variant,
        "previews": out_previews,
    }
    with open(out_path, "w") as fh:
        json.dump(envelope, fh, indent=2)

    print(f"Wrote {out_path}: {len(out_previews)} preview(s) for {module_path}")


if __name__ == "__main__":
    main()
