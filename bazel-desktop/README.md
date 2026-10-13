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
├── .bazelversion           # 9.2.0
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

## Live CMP render contract

Build `//:runtime_classpath` alongside the bundle, then from the repo root:

```bash
execution_root="$(cd bazel-desktop && bazel info execution_root)"
./gradlew :contract-tests:bazel-cmp-desktop:test -PbazelExecutionRoot="$execution_root"
```

This renders the Bazel-built packaged Greeting with the published daemon and
checks its decoded PNG is nonblank. Missing outputs fail. See
[`docs/bazel.md`](../docs/bazel.md) for dependency versions, public project
references, and the limits of Desktop JVM versus full CMP/KMP support.

## Automatic CLI discovery via an aspect

The separate `//:aspect_app` fixture includes a generated preview, a dependency-owned preview, and a
runtime-only class that reads a packaged resource. The CLI integration test must
find only the two application previews and render actual text from that runtime
resource. It uses binary-retained `androidx.compose.ui.tooling.preview.Preview`;
the old Desktop annotation has source-only retention and cannot be found in
compiled jars.

With a CLI containing automatic build selection, run from `bazel-desktop/`:

```sh
compose-preview list
compose-preview show --filter RuntimeGreeting --json
compose-preview render --filter RuntimeGreeting --output greeting.png
```

The shared `.compose-preview/bazel.json` selects `//:aspect_app` for everyone.
The CLI detects the Bazel workspace and installs its aspect on first use; generated
aspects and render outputs stay ignored. No Gradle preview plugin is applied.
Bazel/Bazelisk is selected from PATH, with an explicit build-command override
available for local wrappers.

The explicit form is also available:

```sh
compose-preview bazel init
compose-preview bazel list --target //:aspect_app
compose-preview bazel render --target //:aspect_app --filter RuntimeGreetingKt.RuntimeGreeting --output greeting.png
```

No `bundle_preview` or `runtime_classpath` target is required for this path.
See the [CLI configuration and artifact contract](https://github.com/yschimke/compose-ai-tools/blob/main/docs/BAZEL.md)
for provider overrides, build flags, internal executable wrappers and limitations.

## Native Bazel build/run targets

Reusable rules now live in [compose-preview-bazel](https://github.com/yschimke/compose-preview-bazel),
consumed as the independently pinned `rules_compose_preview` module. Stage a CLI
distribution containing the Bazel adapter into `.compose-preview/cli/`, then:

```sh
bazel run //:ui-preview -- --filter RuntimeGreeting --output greeting.png
bazel build //:ui-preview
```

Select `--output_groups=png` to build actual PNG captures under
`bazel-bin/ui-preview.renders/`. The optional render actions are
local and uncached while renderer/native provisioning uses the host. Neither
entry point starts a nested build. For external repositories, the rule repo
documents immutable commits, checksum-pinned release archives and CLI provisioning.

### Prepared runnable bundles and optional rendering

Stage the independently built compose-ai-tools producer distribution at
`.compose-preview/producer/` (its `lib/*.jar` files). Build it with
`scripts/agent-gradle.sh :bundle-producer:installDist`; the full CLI is needed only
for rendering and goes in `.compose-preview/cli/`.

```sh
bazel build //:ui-source-bundle  # no CLI/daemon distribution required
bazel build //:ui-preview       # default is also renderer-free preparation
bazel run //:ui-preview -- render --filter RuntimeGreeting --output greeting.png
bazel build //:ui-preview --output_groups=png
bazel build //:ui-preview --output_groups=rendered_bundle
```

Preparation is offline and normally cached. The `.bundle.png` has a valid stub
cover, own compiled classes/resources, Maven coordinates and embedded local
runtime-only dependencies. Rendering resolves coordinates through the existing
CLI bundle renderer. `.rendered-bundle.png` is a separate product with real cover
and baked preview images; `.renders/` contains loose images. Rendering remains
local and uncached pending a fully declared renderer/native/font toolchain.
The explicitly declared `ui-source-bundle` example omits `cli` entirely. The
producer uses shared JVM discovery/format contracts without daemon client/core/BTA,
renderer or full CLI dependencies.
