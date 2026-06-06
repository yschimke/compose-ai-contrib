# Portable preview bundles — the Amper & Bazel producers

This is the **contrib-side confirmation** for the portable-bundle work tracked in
[`yschimke/compose-ai-tools#1632`](https://github.com/yschimke/compose-ai-tools/issues/1632).
The tools repo owns the format and the readers; the upstream design note
[`compose-ai-tools/docs/portable-bundles.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/portable-bundles.md)
ends with three questions to confirm against this repo. Those answers — and the
producers that emit the new fields — live here.

## The format (owned upstream — recap)

A bundle is a **PNG + ZIP polyglot**: leading bytes are a cover PNG (every image
viewer shows it), trailing bytes a ZIP (`unzip foo.png` works). The ZIP carries:

```
bundle.json           # BundleManifest: schemaVersion, producer, resolution, classpath[]
previews.json         # selected previews (id / class / function / params)
previews/<id>.png     # one baked render per preview (Tier 0 — universally viewable)
classes/app.jar       # consumer bytecode, minimized to the previews' reachable closure
libs/<name>.jar       # third-party jars carried in-bundle (embedded / mixed only)
report.json           # minimization report
```

Schema layers, all additive (`ignoreUnknownKeys`, so a v2 reader opens a v4
bundle and a v4 reader opens a v2 one):

| v | adds |
|---|---|
| v2 | `previews/<id>.png` baked images |
| v3 | `BundleManifest.producer` / `resolution`, `ClasspathEntry.Embedded` (`libs/`) |
| v4 | `ClasspathEntry.Maven.sha256` (content-addressed, verify-after-resolve) |

Three carriage modes the readers support — and which this repo's producers emit:

| `resolution` | carries | player path |
|---|---|---|
| `coordinates` (default) | `Maven(g,a,v,type,sha256?)`, no bytes | resolver fetches each coord from a local cache / Maven Central / Google Maven, verifies sha256 (warn-never-fail) |
| `embedded` | reachable jars inlined under `libs/` as `Embedded(inlinedAs)` | loader appends every `libs/*.jar` to the child classloader — zero network |
| `mixed` | coords where possible, embed the rest | both |

## The producers in this repo

The shared engine is [`bundle-producer/`](../bundle-producer/) — a pure-JVM
module (no Gradle/AGP/Amper/Bazel dependency) that reuses the **same ClassGraph
reachability closure** as the upstream `BundlePreviewTask.closureWalk` over a flat
`module sources + dependency jars` scan, then writes the polyglot. It recovers
Maven coordinates two ways (`Coordinates.kt`): from a **Maven-layout path**
(Amper's m2 cache) and from a **`maven_install.json`** pin (Bazel's
`rules_jvm_external`). `BundleWriter` derives `resolution` honestly from the
result. Wire compatibility with the upstream `:bundle-viewer` reader is pinned by
mirroring its `bundle.json` field names and the `kind` discriminator exactly.

- **Amper** — wired into [`contract-tests/amper-cmp-desktop`](../contract-tests/amper-cmp-desktop/)
  (`AmperBundleEmitter` + `PreviewProducer`): after rendering, it packs
  `_preview-bundle.png` (and, with `-Dcontrib.bundleEmbed=true`,
  `_preview-bundle-embedded.png`).
- **Bazel** — the `bundle_preview` Starlark rule drives the producer's runnable
  `bundle-producer-all.jar` over a Bazel library's outputs. It's used in two
  samples: [`bazel-desktop/`](../bazel-desktop/) — a Compose **Desktop**
  `kt_jvm_library` that builds cleanly and whose bundle CI publishes — and
  [`bazel-apk/`](../bazel-apk/) — the Android target, currently blocked on the
  `rules_android`/Bazel-9 toolchain ([#14](https://github.com/yschimke/compose-ai-contrib/issues/14)).

## Downloadable artifacts in CI

Each producer's bundle is uploaded as a CI artifact so you can grab and open it:

| Build system | Workflow | Artifact | Contents |
| --- | --- | --- | --- |
| Amper | `contract-tests.yml` | `amper-preview-bundles` | `_preview-bundle.png` (coordinates, rendered cover) + `_preview-bundle-embedded.png` (offline) |
| Bazel (desktop) | `bazel.yml` | `bazel-desktop-bundle` | `preview_bundle.png` (coordinates + sha256, stub cover) |

Download from the run's **Artifacts** section, then `unzip -l <bundle>.png` /
open it in `compose-preview bundle open` or the `:bundle-viewer`.

## The three questions — confirmed

### Q1. Do the producers emit a real `classpath[]`, or PNGs only (Tier 0)?

**Both emit a real `classpath[]`** with this change.

- **Amper** — `AmperBundleEmitter` writes a full `bundle.json` with
  `producer = "amper"`, a minimized `classes/app.jar`, and a `classpath[]` of
  `Maven` coordinates for every reachable runtime jar. It *also* bakes the
  rendered `previews/<id>.png` (Tier 0), so the bundle is both **statically
  viewable** in any PNG viewer **and live-re-renderable**. (The pre-existing
  `_previews.json` envelope — Tier 0, baked PNGs for the upstream `skip-render`
  apply action — is unchanged and still emitted alongside.)
- **Bazel** — `bundle_preview` writes a `bundle.json` with `producer = "bazel"`
  and a real `classpath[]`. Rendering is **not** invocable under Bazel today
  (the Robolectric/Desktop render path isn't wired here), so absent a supplied
  PNG the bundle carries a **stub gray cover** plus the real `classpath[]` — it
  is live-re-renderable, but not statically pre-rendered. Pass `render_files`
  once a Bazel render lands to bake images.

### Q2. Can the driver recover `maven_install` coordinates per jar, or only paths?

**Yes — coordinates are recoverable for both, with sha256.**

- **Bazel** — `rules_jvm_external`'s pinned `maven_install.json` records
  `group:artifact:version` and per-packaging `shasums` for every artifact.
  `Coordinates.parseMavenInstall` indexes them by the runtime jar's basename, so
  each pinned dep becomes a `Maven` coordinate (`resolution = "coordinates"`).
  The producer hashes the on-disk jar for the entry's `sha256` (authoritative,
  and equivalent to the pin's hash). A jar with **no** pin — a vendored
  `//third_party` jar, or any jar when `maven_install.json` hasn't been pinned —
  has no coordinate and is **embedded** under `libs/` (`embedded` / `mixed`).
  *Caveat:* coordinate recovery keys on the `<artifact>-<version>.<type>`
  basename, which is clean for JVM jars; Android `aar`-derived classes jars don't
  always keep that basename, so the Android `bazel-apk` sample may land on
  `embedded` for some deps until pinned — a JVM-desktop Bazel target recovers
  coordinates cleanly.
- **Amper** — Amper resolves into a standard Maven-layout cache
  (`~/.cache/JetBrains/Amper/.m2.cache/<group>/<artifact>/<version>/…`), so
  `Coordinates.recoverFromMavenLayout` reads the coordinate straight back from a
  resolved jar's path. The default Amper pack is therefore `coordinates` with a
  `sha256` per dep; a jar with no recoverable coordinate is embedded.

### Q3. Does the contrib host resolve coordinates, or assume a pre-resolved `-cp`?

**The contrib *render/scripting* hosts hand the daemon a fully-resolved `-cp`** —
the Tier-3 coordinate resolver is **not** exercised on the producer side here:

- the scripting host (`compose-preview-scripting`) shells out to
  `GradlePreviewDriver`, so Gradle resolves the classpath;
- the Amper `PreviewProducer` builds the daemon classpath from the resolved
  `rendererRuntime` jars + Amper's `kotlin-output/`.

That is exactly why the bundle producer recovers coordinates from build-system
metadata (m2 layout / `maven_install.json`) rather than from a resolver: the
**producer** records detached coordinates; the **player** (`compose-preview
bundle open` / `:bundle-viewer`) is where the Tier-3 resolver runs at open time.
For an offline hand-off, `embedded` mode sidesteps the resolver entirely.

## Guarding against embedding a published jar

Embedding is the fallback for a jar with **no recoverable coordinate** — it should
not catch a dependency that actually lives in a Maven repo (that bloats the bundle
and forfeits the detached-coordinate design). But "coordinate recovery failed" is
not the same as "not in any repo": an Amper m2-layout quirk or a Bazel basename
mismatch could embed a published artifact.

`EmbeddedVerifier` closes that gap. Maven Central indexes artifacts by checksum,
so every jar the producer is about to embed is looked up by its **SHA-1** through
Central's `solrsearch` API; any that resolves to a `group:artifact:version` is
flagged (it should be a coordinate, not embedded). It's best-effort — an
unreachable registry leaves the jar embedded and reports it as "unverified",
never failing the build by itself.

This matters most for **Amper**: every jar in its m2 cache was resolved *from* a
repo, so a flagged embed there is by construction a recovery miss, not a vendored
jar. For **Bazel** it disambiguates a genuine `//third_party` vendored jar from a
`maven_install.json` basename miss.

```bash
# Bazel (CLI): warn on, or fail on, embedded-but-published jars
java -jar bundle-producer-all.jar … --verify-embedded            # warn
java -jar bundle-producer-all.jar … --verify-embedded --fail-on-resolvable-embed  # exit 3

# Amper:
./gradlew :contract-tests:amper-cmp-desktop:run -Dcontrib.verifyEmbedded=true
```

The endpoint is overridable (`-Dcontrib.checksumSearchUrl=…`) for environments
whose allowlist doesn't include `search.maven.org`. The verifier's logic is unit
tested (`EmbeddedVerifierTest`) with a fake registry; the live Central lookup runs
where the host is reachable.

## Producing & verifying

### Amper (coordinates + embedded)

```bash
cd amper-cmp-desktop && ./amper build && cd ..
# coordinates pack (default) + offline embedded pack:
./gradlew :contract-tests:amper-cmp-desktop:run -Dcontrib.bundleEmbed=true
file _preview-bundle.png                  # => PNG image data  (opens in any viewer)
unzip -l _preview-bundle.png              # => bundle.json, previews/<id>.png, classes/app.jar
unzip -l _preview-bundle-embedded.png     # => additionally libs/*.jar  (no network needed)
```

- **coordinates** bundle: `resolution = "coordinates"`, `classpath[]` of `Maven`
  entries with `sha256`; opens with network — the player resolves + hash-checks
  each coord.
- **embedded** bundle: `resolution = "embedded"`, `unzip -l` shows `libs/*.jar`;
  opens on a box with **no Gradle/Maven and no network**.

### Bazel (coordinates from `maven_install.json`, else embedded)

```bash
./gradlew :bundle-producer:uberJar
cp bundle-producer/build/libs/bundle-producer-all.jar bazel-apk/
cd bazel-apk
# optional, for resolution=coordinates: pin the Maven graph, then uncomment
# `maven_install = "maven_install.json"` in BUILD.bazel
bazel run @maven//:pin
bazel build //:preview_bundle
unzip -l bazel-bin/preview_bundle.png     # bundle.json (producer=bazel) + classpath[]
```

The coordinate-recovery and embed logic are covered by the `:bundle-producer`
unit tests (`CoordinatesTest`, `BundleWriterTest`, `BundleProducerCliTest`), so
the producer path is exercised in CI even though the full Bazel build stays
opt-in / known-fragile (see [`bazel-apk/README.md`](../bazel-apk/README.md)).

## Out of scope (done upstream)

Schema v3/v4, `--embed-deps`, the `libs/`-aware loader, the runnable
`compose-preview-viewer` uber jar / jpackage installers, and the Tier-3
coordinate resolver all live in `compose-ai-tools`. This repo only **confirms the
contrib producers and emits the new fields**.
