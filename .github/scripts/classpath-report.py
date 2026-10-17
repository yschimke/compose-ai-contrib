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
                           [--require-final-r PACKAGE...]

--root resolves relative jar paths (the Bazel execution root, or the Buck2 project root; default
the current directory). --annotate also prints the summary as GitHub ::notice:: lines.
--require-final-r fails the run unless the final-ID R jar has R classes for every package named:
a library's own R jar carries IDs that name nothing in the linked resource APK.
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
    parser.add_argument(
        "--require-final-r",
        nargs="*",
        default=[],
        metavar="PACKAGE",
        help="fail unless the final-ID R jar has R classes for each package",
    )
    args = parser.parse_args()

    document = json.load(open(args.artifacts))
    resolve = lambda p: p if os.path.isabs(p) else os.path.join(args.root, p)
    jars = document.get("runtimeJars", [])
    android = document.get("android") or {}
    r_final = android.get("rJar")
    final_classes = set(classes(resolve(r_final)) or []) if r_final else set()

    seen = {}
    overlaps = collections.Counter()
    duplicate_names = set()
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
            if final_classes and set(names) <= final_classes:
                r_redundant.append(path)
        new = 0
        for name in names:
            if name in seen:
                duplicate_names.add(name)
                overlaps[(path, seen[name])] += 1
            else:
                seen[name] = path
                new += 1
        if new == 0:
            shadowed.append(path)

    short = lambda p: (
        "/".join(p.split("/")[-3:])
        if os.path.basename(p) in ("classes.jar", "classes_and_libs_merged.jar")
        else os.path.basename(p)
    )
    names_by_basename = collections.Counter(os.path.basename(p) for p in jars)
    lines = [
        f"{document.get('target')}: {len(jars)} runtime jars ({total_bytes:,} bytes of joined paths), "
        f"{len(seen):,} distinct classes",
        f"R-class-only jars: {len(r_only)}"
        + (f", of which {len(r_redundant)} are covered by the final-ID R jar" if r_final else ""),
        f"jars with no classes: {len(empty)}; with 1-3 classes: {len(tiny)}",
        f"class names defined by more than one jar: {len(duplicate_names):,}; "
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
    for (later, earlier), count in overlaps.most_common(3):
        line = f"overlap: {count:,} classes of {short(later)} already in {short(earlier)}"
        print("  " + line)
        if args.annotate:
            print(f"::notice title=classpath::{line}")
    final_r = {n for n in final_classes if R_CLASS.search(n)}
    final_pkgs = {n.rsplit("/", 1)[0] for n in final_r if "/" in n}
    uncovered = sorted(
        {
            n.rsplit("/", 1)[0]
            for p in r_only
            if p not in r_redundant
            for n in classes(resolve(p))
            if "/" in n
        }
    )
    # GitHub keeps ten annotations per step, so the detail goes first, one line each.
    detail = [
        f"final R jar: {len(final_r)} R classes in {len(final_pkgs)} packages; "
        f"R packages only in per-library jars ({len(uncovered)}): "
        + ", ".join(q.replace("/", ".") for q in uncovered[:60]),
        "empty sample: " + ", ".join(short(p) for p in empty[:25]),
    ]
    for line in detail:
        print("  " + line)
        if args.annotate:
            print(f"::notice title=classpath::{line}")
    for label, group in (("fully shadowed", shadowed), ("empty", empty)):
        for path in group[:15]:
            print(f"  {label}: {path}")
    missing_final = [q for q in args.require_final_r if q.replace(".", "/") not in final_pkgs]
    if missing_final:
        line = "final R jar lacks R classes for: " + ", ".join(missing_final)
        print(line)
        if args.annotate:
            print(f"::error title=classpath::{line}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
