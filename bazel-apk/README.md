# Bazel Compose APK sample

A second Bazel sample, sibling to [`contrib/bazel/`](../bazel/). Builds a
tiny Android APK with a `@Composable @Preview` and a
`@NotificationPreview` function through `rules_kotlin` +
`rules_android` + `rules_jvm_external`.

## Status

**Builds green on Bazel 9** via the `bazel-build-apk` job in
[`.github/workflows/bazel.yml`](../../.github/workflows/bazel.yml) (a real
blocking check — no `continue-on-error`). It runs on Kotlin 2.x via
`rules_kotlin` 2.x + the bundled
`org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable` (the post-K2
plugin model), which side-steps the old
[bazelbuild/rules_kotlin#1388](https://github.com/bazelbuild/rules_kotlin/issues/1388)
that blocked the standalone `androidx.compose.compiler:compiler` jar on K2.

Getting here on Bazel 9 took a specific toolchain alignment (see the
`MODULE.bazel` comments and [#14](https://github.com/yschimke/compose-ai-contrib/issues/14)):
`.bazelversion` pinned to 9.0.0, `rules_kotlin` 2.1.10 (loads `JavaPluginInfo`
explicitly), an explicit `rules_java` dep, a pinned `maven_install.json` with the
AndroidX strict-version conflicts forced, the Compose compiler plugin matched to
the bundled Kotlin (2.1.21), and the Java toolchain pinned to 17 in `.bazelrc` so
`rules_android`'s own `record`-using tools compile.

## Build

From this directory:

```
bazel build //:bazel_sample_apk
```

## Layout

```
contrib/bazel-apk/
├── MODULE.bazel       # bzlmod entrypoint with Android/Kotlin/Compose deps
├── .bazelrc           # bzlmod-only, no WORKSPACE fallback
├── BUILD.bazel        # kt_android_library + android_binary wiring
└── app/src/main/
    ├── AndroidManifest.xml
    └── kotlin/com/example/bazelsample/
        ├── Greeting.kt        # @Preview composable
        ├── MainActivity.kt    # ComponentActivity host
        └── Notifications.kt   # @NotificationPreview function
```

## Why a separate module from `contrib/bazel/`

The resources-only sample at [`contrib/bazel/`](../bazel/) keeps its
`MODULE.bazel` clean of Android/Kotlin/Maven toolchain wiring so its
`//:app_resources` job doesn't need an Android SDK on the runner. Mixing
both targets into one module forced the SDK and Maven extensions to
evaluate even when only the resources target was being built — see
PR #1276 for the regression that motivated the split.

## Why `@NotificationPreview` is redeclared locally

[`Notifications.kt`](app/src/main/kotlin/com/example/bazelsample/Notifications.kt)
re-declares the annotation as a file-private class rather than
depending on the in-tree `:preview-annotations` Gradle module. The
fixture's whole point is to demonstrate a project layout that doesn't
know about Gradle; the canonical annotation lives at
[`preview-annotations/src/main/kotlin/ee/schimke/composeai/preview/NotificationPreview.kt`](../../preview-annotations/src/main/kotlin/ee/schimke/composeai/preview/NotificationPreview.kt)
and a real downstream consumer would resolve the published artifact
from Maven (FQN match is what the discovery side keys off).
