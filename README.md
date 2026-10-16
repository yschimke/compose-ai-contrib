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
| [`amper-android/`](amper-android/) | Kotlin Toolchain 0.13 (formerly Amper) | Android APK with `@Composable @Preview` and `@NotificationPreview`, no Gradle |
| [`amper-cmp-desktop/`](amper-cmp-desktop/) | Kotlin Toolchain 0.13 (formerly Amper) | Compose Desktop module driving a real `RenderSession` end-to-end |
| [`amper-compose-example/`](amper-compose-example/) | Kotlin Toolchain 0.13 | JetBrains Compose Desktop example discovered and rendered through the published APIs |
| [`bazel/`](bazel/) | Bazel (bzlmod) | Resources discovery rule producing `resources.json` |
| [`bazel-desktop/`](bazel-desktop/) | Bazel (bzlmod) | Compose **Desktop** `kt_jvm_library` packed into a portable preview bundle (`bundle_preview`); bundle published in CI, live CMP render contract |
| [`bazel-apk/`](bazel-apk/) | Bazel (bzlmod) | Compose APK via `rules_kotlin` + `rules_android` on Bazel 9 |
| [`buck2/`](buck2/) | Buck2 (bundled prelude) | Resources discovery rule producing `resources.json` — byte-identical to the Bazel sample |
| [`buck2-desktop/`](buck2-desktop/) | Buck2 (bundled prelude) | Compose **Desktop** `kotlin_library`, rendered by `compose-preview` through its BXL script with no BUCK changes |

Each directory is self-contained — see its README for build
instructions.

## Standalone binaries

| Module | Description |
| --- | --- |
| [`compose-preview-scripting/`](compose-preview-scripting/) | `compose-preview-scripting <path.composepreview.kts>` — Kotlin scripting host that renders previews via `gradle-preview-driver` and evaluates a user script against the result set. Lifted from upstream's `examples/scripting/` reference (yschimke/compose-ai-tools PR #1375). Builds against `preview-data-api` + `gradle-preview-driver`, published to Maven Central since `composeai` 0.11.15. |
| [`bundle-producer/`](bundle-producer/) | Build-system-agnostic portable-bundle producer — the ClassGraph reachability closure, the PNG+ZIP polyglot writer, and Maven-coordinate / `maven_install.json` recovery the Amper and Bazel drivers use to emit a schema-v4 `bundle.json` the upstream `:bundle-viewer` can open. See [`docs/portable-bundles.md`](docs/portable-bundles.md). |

## Documentation

- [`docs/integration-survey.md`](docs/integration-survey.md) — current applicability and Compose project examples.
- [`docs/amper.md`](docs/amper.md) — Amper integration walkthrough.
- [`docs/bazel.md`](docs/bazel.md) — Bazel integration walkthrough.
- [`docs/portable-bundles.md`](docs/portable-bundles.md) — portable preview
  bundles: what the Amper and Bazel producers emit (`coordinates` / `embedded` /
  `mixed`), and the contrib-side confirmation of the
  [tools-repo design](https://github.com/yschimke/compose-ai-tools/blob/main/docs/portable-bundles.md).

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
cd amper-cmp-desktop && ./kotlin build && cd ..
cd amper-compose-example && ./kotlin build && cd ..

# run the contract test
./gradlew :contract-tests:amper-cmp-desktop:test -Pcontrib.requireAmperOutputs=true
```

The version of `compose-ai-tools` under test is pinned in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml) under
the tools, sessions, daemon BOM, contracts BOM, and driver pins. They publish independently.

## CI

### Build / test workflows

- [`.github/workflows/contract-tests.yml`](.github/workflows/contract-tests.yml)
  builds the Amper Desktop fixture and runs the contract test.
- [`.github/workflows/amper-android.yml`](.github/workflows/amper-android.yml)
  builds the Amper Android fixture.
- [`.github/workflows/bazel.yml`](.github/workflows/bazel.yml) builds the
  Bazel resources-discovery target, the Compose Desktop preview bundle
  (uploaded as the `bazel-desktop-bundle` artifact), and the Compose APK target.
- [`.github/workflows/buck2.yml`](.github/workflows/buck2.yml) builds
  the Buck2 resources-discovery target and verifies byte-for-byte
  parity with the Bazel sample's output, then builds `buck2-desktop/` and renders
  its preview through the `compose-preview` CLI (uploaded as `buck2-desktop-render`).
- [`.github/workflows/compose-preview-scripting.yml`](.github/workflows/compose-preview-scripting.yml)
  builds the scripting binary, renders the demo previews, and runs example scripts.

### Preview / resource publish workflows

Each build system that can produce renders or resource manifests has a matching
`*-apply.yml` workflow that runs on PRs touching that project and on pushes to
`main`. On PRs it posts a sticky comment with changed renders/resources; on
`main` it updates the `compose-preview/main` baseline branch.

| Workflow | Publishes | Branch path |
| --- | --- | --- |
| [`amper-apply.yml`](.github/workflows/amper-apply.yml) | `@Preview` renders from Amper Desktop fixture | `compose-preview/amper/{main,pr}` |
| [`scripting-apply.yml`](.github/workflows/scripting-apply.yml) | `@Preview` renders from compose-preview-scripting demo | `compose-preview/scripting/{main,pr}` |
| [`bazel-apply.yml`](.github/workflows/bazel-apply.yml) | `app_resources.json` from Bazel resources-discovery | `compose-preview/bazel/{main,pr}` |
| [`buck2-apply.yml`](.github/workflows/buck2-apply.yml) | `app_resources.json` from Buck2 resources-discovery | `compose-preview/buck2/{main,pr}` |

`amper-android` is excluded — Android preview rendering requires the Gradle
plugin + Robolectric, neither of which is available in a pure Amper module.

## Versioning

This repo doesn't publish artifacts; it consumes them. `gradle/libs.versions.toml` pins the tools/plugin, session libraries, driver, daemon BOM, and contracts BOM separately. The BOMs align their module families; do not assign one tools version to every Maven artifact. CI proves the selected released versions work together.

## License

[Apache 2.0](LICENSE).
