#!/usr/bin/env python3
"""Report what a compose-preview target's runtime classpath is made of.

Reads the build-artifacts.json the CLI writes for a Bazel or Buck2 target (under
.compose-preview/outputs/<key>/build/compose-previews/) and opens every runtime jar:

- how many jars are R-class-only (every class is <pkg>.R or <pkg>.R$*), and how many of those
  the linked final-ID R jar (android.rJar) makes redundant;
- how many jars hold no classes at all, and how many hold only a handful;
- how many class names are defined by more than one jar, and how many jars are fully shadowed
  (every class they define is already defined by an earlier jar, so the JVM never loads them).

Usage: classpath-report.py <build-artifacts.json> [--root <dir>] [--annotate]

--root resolves relative jar paths (the Bazel execution root, or the Buck2 project root; default
the current directory). --annotate also prints the summary as GitHub ::notice:: lines.
"""
import argparse
import collections
import json
import os
import re
import sys
import zipfile

R_CLASS = re.compile(r"(^|/)R(\$[^/]*)?\.class$")


def classes(path):
    try:
        with zipfile.ZipFile(path) as jar:
            return [
                n for n in jar.namelist()
                if n.endswith(".class") and not n.startswith("META-INF/")
                and not n.endswith("module-info.class")
            ]
    except (OSError, zipfile.BadZipFile):
        return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("artifacts")
    parser.add_argument("--root", default=".")
    parser.add_argument("--annotate", action="store_true")
    args = parser.parse_args()

    document = json.load(open(args.artifacts))
    resolve = lambda p: p if os.path.isabs(p) else os.path.join(args.root, p)
    jars = document.get("runtimeJars", [])
    android = document.get("android") or {}
    r_final = android.get("rJar")
    final_packages = set()
    if r_final:
        for name in classes(resolve(r_final)) or []:
            if R_CLASS.search(name):
                final_packages.add(name.rsplit("/", 1)[0] if "/" in name else "")

    seen = {}
    overlaps = collections.Counter()
    duplicate_names = 0
    shadowed = []
    r_only = []
    r_redundant = []
    empty = []
    tiny = []
    missing = []
    # What composeai.daemon.userClassDirs carries: every absolute path, joined.
    total_bytes = sum(len(os.path.abspath(resolve(p))) + 1 for p in jars)
    for path in jars:
        names = classes(resolve(path))
        if names is None:
            missing.append(path)
            continue
        if not names:
            empty.append(path)
            continue
        if len(names) <= 3:
            tiny.append(path)
        if all(R_CLASS.search(n) for n in names):
            r_only.append(path)
            packages = {n.rsplit("/", 1)[0] if "/" in n else "" for n in names}
            if final_packages and packages <= final_packages:
                r_redundant.append(path)
        new = 0
        for name in names:
            if name in seen:
                duplicate_names += 1
                overlaps[(path, seen[name])] += 1
            else:
                seen[name] = path
                new += 1
        if new == 0:
            shadowed.append(path)

    names_by_basename = collections.Counter(os.path.basename(p) for p in jars)
    lines = [
        f"{document.get('target')}: {len(jars)} runtime jars ({total_bytes:,} bytes of joined paths), "
        f"{len(seen):,} distinct classes",
        f"R-class-only jars: {len(r_only)}"
        + (f", of which {len(r_redundant)} are covered by the final-ID R jar" if r_final else ""),
        f"jars with no classes: {len(empty)}; with 1-3 classes: {len(tiny)}",
        f"class names defined by more than one jar: {duplicate_names:,}; "
        f"jars fully shadowed by earlier ones: {len(shadowed)}",
        "most common jar file names: "
        + ", ".join(f"{n} x{c}" for n, c in names_by_basename.most_common(5)),
    ]
    if missing:
        lines.append(f"jars not found (not materialized?): {len(missing)}")
    for line in lines:
        print(line)
        if args.annotate:
            print(f"::notice title=classpath::{line}")
    short = lambda p: os.path.basename(p) if os.path.basename(p) not in ("classes.jar",) else "/".join(p.split("/")[-3:])
    for (later, earlier), count in overlaps.most_common(10):
        line = f"overlap: {count:,} classes of {short(later)} already in {short(earlier)}"
        print("  " + line)
        if args.annotate:
            print(f"::notice title=classpath::{line}")
    for label, group in (("fully shadowed", shadowed), ("empty", empty)):
        for path in group[:15]:
            print(f"  {label}: {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
