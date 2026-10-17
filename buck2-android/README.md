# Buck2 sample: Jetpack Compose Android previews

A Jetpack Compose `@Preview` in a plain Buck2 `android_library`, with a string
from its `android_resource`, rendered by `compose-preview` with no BUCK
changes. The CLI detects the project (`.buckroot`), runs its BXL script against
the library's Java and Android providers, links the library's resource closure
into a standalone resource APK with final-ID R classes, and renders on the
Android (Robolectric) daemon. How that works:
[compose-ai-tools `docs/BUCK.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/BUCK.md#android).

```
cd buck2-android/app
compose-preview list
compose-preview render --filter Greeting --output greeting.png
```

## Layout

```
buck2-android/
├── .buckconfig         # root cell + the prelude bundled with buck2
├── .buckroot
├── toolchains/BUCK     # system_demo_toolchains(): Java, Kotlin and the system Android toolchain
├── third_party/BUCK    # the Compose Android closure as android_prebuilt_aar / prebuilt_jar
└── app/
    ├── BUCK            # android_resource + android_library with the Compose compiler plugin
    ├── AndroidManifest.xml
    ├── res/values/strings.xml
    └── src/com/example/buckandroid/Greeting.kt
```

## Setup

This is the project's own Buck2 setup, as any Buck2 project on the system demo
toolchains needs: per-machine paths go in a git-ignored `.buckconfig.local`, which
the CI writes from the runner. compose-preview itself needs no setup; nothing under
`.compose-preview/` is committed.

- **buck2**: a dated release (the CI pins one in `.github/workflows/buck2.yml`).
- **An Android SDK** with a platform and build tools, named in `ANDROID_HOME`
  (rendering reads it) and in `.buckconfig.local` (git-ignored) for the
  prelude's system Android toolchain, with **JDK 21** for the prelude's own
  compiler daemons:

  ```
  [java]
    java_home = /usr/lib/jvm/temurin-21-jdk-amd64
  [android]
    sdk_path = /opt/android-sdk
    build_tools_version = 36.0.0
    compile_sdk_version = android-36
  ```

## Versions

- The bundled prelude's `kotlincd_toolchain` ships **kotlinc 2.2.0**, so the
  Compose compiler plugin is `kotlin-compose-compiler-plugin-embeddable:2.2.0`.
- Jetpack Compose is **1.12.1**, the Compose the Android render daemon carries:
  its classes win over the target's while the R IDs come from the target's
  resources, so an older Compose fails at `onAttachedToWindow` with a
  `NoSuchFieldError` on `androidx.compose.ui.R$id`.

`third_party/BUCK` is the runtime closure Gradle resolves for an Android
consumer of `androidx.compose.foundation:foundation` and
`androidx.compose.ui:ui-tooling-preview` at 1.12.1 (preferring the
`jvm.environment=android` variant, accepting plain JVM libraries), one
`remote_file` per artifact (`androidx.*` from `maven.google.com`, the rest from
Maven Central) as an `android_prebuilt_aar` or `prebuilt_jar`, gathered by
`compose_android`. To move versions, resolve the same closure and regenerate
the entries with their SHA-1s.
