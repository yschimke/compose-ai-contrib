# Driving Compose previews from Bazel

The maintained path is Kotlin 2.1.21 + its matching Compose compiler plugin,
`rules_kotlin` 2.1.10, `rules_jvm_external` 6.10, `rules_android` 0.7.2, and Bazel
9.0.0. All three fixtures pin Bazel via `.bazelversion`. The Kotlin compiler
and `org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable` must move
in lockstep; the old standalone AndroidX compiler is not the K2 recipe.

## Fixtures and checks

| Fixture | What is exercised | Check |
| --- | --- | --- |
| [`bazel/`](../bazel/) | Android resource manifest discovery | `bazel build //:app_resources` |
| [`bazel-desktop/`](../bazel-desktop/) | Real CMP UI bytecode, portable bundle, published desktop renderer | Commands below |
| [`bazel-apk/`](../bazel-apk/) | Compose Android library and dexed APK | `bazel build //:bazel_sample_apk` |

From the repo root:

```bash
./gradlew :bundle-producer:uberJar
cp bundle-producer/build/libs/bundle-producer-all.jar bazel-desktop/
(cd bazel-desktop && bazel build //:preview_bundle //:runtime_classpath)
python3 .github/scripts/check-bazel-bundle.py bazel-desktop/bazel-bin/preview_bundle.png
execution_root="$(cd bazel-desktop && bazel info execution_root)"
./gradlew :contract-tests:bazel-cmp-desktop:test -PbazelExecutionRoot="$execution_root"
```

The fixture's application bytecode is built exclusively by Bazel. Gradle builds
the shared producer and runs a consumer-side contract test using the published
`DaemonLaunchBuilder` and `SubprocessRenderSessions` libraries. It does not
compile the fixture or apply the Compose preview Gradle plugin to it.

`runtime_classpath` exports the module output jars and full `JavaInfo` runtime
closure as execution-root-relative paths. The contract test checks every jar
exists, launches the desktop daemon, renders the actual packaged
`com.example.bazeldesktop.GreetingKt#Greeting`, and decodes a nonblank PNG.
Missing Bazel outputs fail the test; they are never counted as a skipped success.
The daemon uses a temporary home for native extraction and runs headlessly.

The portable-bundle action uses Bazel's execution-platform Java runtime with
its files declared as tools. Its classpath automatically includes the module's
transitive runtime jars; callers need `deps` only for additional dependencies.
The bundle check verifies schema, producer, backend, preview IDs, the compiled
entry class, and every detached Maven coordinate's SHA-256. The default bundle
still has a stub cover: the render contract proves live rendering separately;
pass `render_files` to `bundle_preview` to bake PNGs into a bundle.

For APK builds install Android platform 36 and build-tools 36.0.0, set
`ANDROID_HOME`, then build from `bazel-apk/`. The optional APK bundle records
`backend = "android"`, not the desktop default. Producing Android bytecode and
bundling it does not prove Android rendering; that needs a Robolectric/resource
classpath and a separate render contract.

## Dependency updates

Each Compose fixture checks in `maven_install.json`. After changing artifacts,
run `REPIN=1 bazel run @maven//:pin` in that fixture and commit the resulting pin.
`fail_if_repin_required` prevents an edited declaration from silently using the
old graph. Pin files carry both artifact checksums and repository URLs.

Desktop deliberately uses explicit `-desktop` Maven variants: POM-only
resolution of unsuffixed multiplatform coordinates can choose metadata or the
wrong platform. The compatibility fixture is pinned to CMP 1.7.3. A separate dependency
upgrade targets Kotlin/compiler 2.4.20 and CMP 1.11.1 (Material3 1.9.0),
with a regenerated Maven pin. The published compose-preview
renderer currently uses the CMP 1.11.1/Skiko ABI. CMP 1.12 changes Skiko APIs, so
moving this fixture beyond that requires upgrading and verifying the renderer
as well. A latest-version-only bump is not sufficient.

## Public project survey (2026-10-13)

| Project | Evidence and relevance | Integration decision |
| --- | --- | --- |
| [`bazel-contrib/rules_kotlin/examples/jetpack_compose`](https://github.com/bazel-contrib/rules_kotlin/tree/02f96a1144527e46724f17381a11cd2cd8b36980/examples/jetpack_compose) | Maintained Android Compose example with the Kotlin compiler plugin and real APK target. Its module overrides `rules_kotlin` to the enclosing checkout. | Pinned upstream build smoke test; build the complete checkout, not a copied module. |
| [Android Studio `adt-ui-compose`](https://android.googlesource.com/platform/tools/adt/idea/+/mirror-goog-studio-main/adt-ui-compose/) | Compose Desktop/Jewel UI inside a large Bazel-built tooling project; useful evidence that Desktop Compose is relevant beyond toy apps. | Relevant future preview consumer. Its Studio-specific toolchains and prebuilts make a full external build unsuitable for this small fixture suite. |
| [`albertocavalcante/gvy`](https://github.com/albertocavalcante/gvy/blob/main/MODULE.bazel) | Declares Compose Desktop dependencies in a Bazel module, with a Kotlin fork and a Coursier metadata patch. | Useful JVM variant-resolution reference; declaration alone is not proof of a passing UI build/render. No claim of tested integration here. |
| [`Bencodes/bazel_jetpack_compose_example`](https://github.com/Bencodes/bazel_jetpack_compose_example) | Historical Android Compose recipe. | Superseded as the primary reference by the maintained upstream example. |

These are build-system consumers worth targeting. This survey does not claim
all support compose-preview already, nor that the older Slack engineering
articles establish the state of Slack's current private build.

## CMP support boundaries

| Target | Status here |
| --- | --- |
| Desktop JVM (Linux) | Bazel compiles CMP UI; the published daemon renders it in the contract test. |
| Android | Bazel compiles Compose UI and builds an APK; preview rendering remains separate work. |
| Desktop macOS/Windows | JVM rule shape is reusable, but native Skiko artifacts and execution platforms need their own CI coverage. |
| iOS/Kotlin Native | Not supplied by these `kt_jvm_library` rules. Requires Native compilation, klibs, Apple framework linking, and macOS tests. |
| Web/Wasm | Not supplied by the Desktop JVM fixture. Requires a Kotlin/Wasm toolchain and browser packaging/testing. |
| Shared `commonMain`/`expect`/`actual` | A Desktop JVM artifact is not a complete KMP source-set build. No KMP rule or common-source contract is claimed. |

For a Bazel project needing previews today, start with a JVM Desktop target for
shared UI and a manifest + daemon descriptor built from declared runtime inputs.
Avoid the previous untested `genrule` snippets: an imported Maven target's
single output jar does not represent its complete transitive Java classpath,
and runfiles paths are not action execution-root paths.

See [portable bundles](portable-bundles.md) and the upstream
[non-Gradle contract](https://github.com/yschimke/compose-ai-tools/blob/main/docs/NON_GRADLE_INTEGRATION.md).
