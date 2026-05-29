# compose-ai-contrib

Non-Gradle integrations of [`compose-preview`](https://github.com/yschimke/compose-ai-tools)
— fixtures and contract tests that exercise the published
`ee.schimke.composeai:*` artifacts from JetBrains Amper and Bazel
builds.

The published artifacts (`preview-discovery`, `daemon-launch-builder`,
`render-cli`) ship as plain Maven coordinates with `java -jar`
entrypoints. This repo proves they're consumable without AGP or
Gradle, and pins the wire formats — `previews.json` and
`daemon-launch.json` — that downstream build-system integrations key
off.

## Fixtures

| Directory | Build system | Demonstrates |
| --- | --- | --- |
| [`amper-android/`](amper-android/) | Amper 0.10 | Android APK with `@Composable @Preview` and `@NotificationPreview`, no Gradle |
| [`amper-cmp-desktop/`](amper-cmp-desktop/) | Amper 0.10 | Compose Desktop module driving a real `RenderSession` end-to-end |
| [`bazel/`](bazel/) | Bazel (bzlmod) | Resources discovery rule producing `resources.json` |
| [`bazel-apk/`](bazel-apk/) | Bazel (bzlmod) | Compose APK via `rules_kotlin` + `rules_android` (opt-in, known-fragile) |
| [`buck2/`](buck2/) | Buck2 (bundled prelude) | Resources discovery rule producing `resources.json` — byte-identical to the Bazel sample |

Each directory is self-contained — see its README for build
instructions.

## Standalone binaries

| Module | Description |
| --- | --- |
| [`compose-preview-scripting/`](compose-preview-scripting/) | `compose-preview-scripting <path.composepreview.kts>` — Kotlin scripting host that renders previews via `gradle-preview-driver` and evaluates a user script against the result set. Lifted from upstream's `examples/scripting/` reference (yschimke/compose-ai-tools PR #1375). Builds against `preview-data-api` + `gradle-preview-driver`, published to Maven Central since `composeai` 0.11.15. |

## Documentation

- [`docs/amper.md`](docs/amper.md) — Amper integration walkthrough.
- [`docs/bazel.md`](docs/bazel.md) — Bazel integration walkthrough.

## Repo Gradle build

A thin Gradle project at the root drives one job: the contract test in
[`contract-tests/amper-cmp-desktop/`](contract-tests/amper-cmp-desktop/),
which resolves the published `ee.schimke.composeai:*` artifacts from
Maven Central, builds a `daemon-launch.json` descriptor against the
Amper fixture's compiled output, and renders the fixture's `@Preview`
through `SubprocessRenderSessions`. End-to-end proof that the released
artifacts work for a non-Gradle consumer.

```bash
# build the Amper fixture (produces kotlin-output/)
cd amper-cmp-desktop && ./amper build && cd ..

# run the contract test
./gradlew :contract-tests:amper-cmp-desktop:test
```

The version of `compose-ai-tools` under test is pinned in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml) under
`composeai`. Bump in lockstep with upstream releases.

## CI

- [`.github/workflows/contract-tests.yml`](.github/workflows/contract-tests.yml)
  builds the Amper Desktop fixture and runs the contract test on every PR.
- [`.github/workflows/amper-android.yml`](.github/workflows/amper-android.yml)
  builds the Amper Android fixture.
- [`.github/workflows/bazel.yml`](.github/workflows/bazel.yml) builds the
  Bazel resources-discovery target and (opt-in, `continue-on-error`)
  the Compose APK target.
- [`.github/workflows/buck2.yml`](.github/workflows/buck2.yml) builds
  the Buck2 resources-discovery target and verifies byte-for-byte
  parity with the Bazel sample's output.

## Versioning

This repo doesn't publish artifacts; it consumes them. The single
`composeai` version in `gradle/libs.versions.toml` is the only knob —
when [`yschimke/compose-ai-tools`](https://github.com/yschimke/compose-ai-tools)
cuts a release, bump that line and let CI prove the new version still
works for non-Gradle consumers.

## License

[Apache 2.0](LICENSE).
