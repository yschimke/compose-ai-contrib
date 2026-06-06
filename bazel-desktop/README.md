# Bazel Compose-Desktop preview bundle

A Bazel sample that compiles a Compose **Desktop** `@Preview` with a plain
`kt_jvm_library` and packs it into a **portable preview bundle** — the
downloadable `compose-preview` artifact described in
[`../docs/portable-bundles.md`](../docs/portable-bundles.md).

## Why desktop (and not the Android `bazel-apk/`)

The Android sample ([`../bazel-apk/`](../bazel-apk/)) drives `rules_android`,
which on Bazel 9 still hits toolchain breakage building its own Go/dexer tools
(tracked in [#14](https://github.com/yschimke/compose-ai-contrib/issues/14)). A
Compose **Desktop** target needs none of that — no `rules_android`, no
AAPT2/dexer, no Android SDK — so it builds cleanly and CI can publish the bundle
as a downloadable artifact.

## What it produces

```
bazel build //:preview_bundle
→ bazel-bin/preview_bundle.png      # PNG + ZIP polyglot
```

`bundle.json` inside is `producer = "bazel"`, `resolution = "coordinates"`,
schema v4: the consumer class is minimized into `classes/app.jar` and every
reachable Compose dependency is recorded as a resolvable `ClasspathEntry.Maven`
coordinate **with sha256** (recovered from the pinned `maven_install.json`). No
render runs under Bazel, so the cover is a stub PNG; the classpath is real.

Inspect it:

```bash
file bazel-bin/preview_bundle.png        # => PNG image data
unzip -l bazel-bin/preview_bundle.png    # => bundle.json, previews.json, classes/app.jar, report.json
```

## Building locally

```bash
# 1. build the shared producer jar and drop it next to this BUILD file
./gradlew :bundle-producer:uberJar
cp ../bundle-producer/build/libs/bundle-producer-all.jar .

# 2. build the bundle
bazel build //:preview_bundle
```

`bundle-producer-all.jar` is git-ignored (a build artifact). CI does these two
steps and uploads `bazel-bin/preview_bundle.png` as the `bazel-desktop-bundle`
artifact.

## Layout

```
bazel-desktop/
├── MODULE.bazel            # bzlmod: rules_kotlin/java + Compose Desktop maven graph (pinned)
├── maven_install.json      # checked-in lock file (regenerate: bazel run @maven//:pin)
├── .bazelversion           # 9.0.0
├── compose_preview.bzl     # the bundle_preview rule (shared shape with bazel-apk/)
├── BUILD.bazel             # kt_jvm_library :app + bundle_preview :preview_bundle
└── app/src/main/kotlin/com/example/bazeldesktop/Greeting.kt   # the @Preview
```

## Pinning

The Maven graph is pinned to `maven_install.json` for deterministic CI. After
changing `artifacts` in `MODULE.bazel`, regenerate it:

```bash
REPIN=1 bazel run @maven//:pin
```
