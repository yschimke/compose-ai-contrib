# Integration survey (13 October 2026)

## Amper / Kotlin Toolchain

Amper moved to [JetBrains/kotlin-toolchain](https://github.com/JetBrains/kotlin-toolchain).
[0.13.0](https://github.com/JetBrains/kotlin-toolchain/releases/tag/v0.13.0)
is the current release. It uses `./kotlin`, Kotlin 2.4.20, and Compose 1.12.1 by
default. JetBrains now [recommends it for most new KMP projects](https://blog.jetbrains.com/kotlin/2026/10/start-your-next-kmp-app-with-kotlin-toolchain-0-13/).

Concrete Compose UI examples:

- [Official Compose Desktop app](https://github.com/JetBrains/kotlin-toolchain/tree/v0.13.0/examples/compose-desktop): standalone JVM UI with a real `@Preview` and window entrypoint. Vendored in `amper-compose-example`, with renderer-compatible Compose/Java overrides; covered by the discovery + render contract test.
- [Official multiplatform app](https://github.com/JetBrains/kotlin-toolchain/tree/v0.13.0/examples/compose-multiplatform): shared Compose UI with Android, desktop, and iOS launchers. Useful next coverage for shared-module output and Compose resources; the current contract covers JVM output only.
- [VRCompatibilityChecker](https://github.com/pavi2410/VRCompatibilityChecker/blob/master/module.yaml): an independent Android Compose application using an Amper `module.yaml` and Material 3. Evidence of actual application use, not a claim that contrib can render its Android previews today.
- [Compose Stability Analyzer](https://skydoves.github.io/compose-stability-analyzer/kotlin-toolchain/getting-started/): documents Kotlin Toolchain compiler-plugin support and a working sample. Its AGP-discovered Android lint checks do not automatically carry over.

The Desktop integration is useful now for standalone JVM and the desktop leg of
KMP apps: published discovery, descriptor synthesis, and a subprocess render
session need no Gradle in the app. The Android fixture proves building, not preview
rendering: Android rendering still requires the appropriate renderer/runtime setup.
Do not advertise the Desktop contract as Android or iOS rendering support.

## Other integrations

- Scripting is useful for JVM preview audits in Gradle projects. It now consumes
  the published `previewdata` and `previewdriver` packages. It is still a Gradle
  driver, not an Amper driver.
- Portable bundles remain useful for detached preview sharing from non-Gradle
  builds. The producer is build-system independent. The Amper fixture now exports
  the JVM classpath selected by Kotlin Toolchain's `run` task, so unrelated jars
  and older versions in the shared Maven cache cannot enter its bundle. Cache
  paths only provide Maven coordinates for the selected jars.
- Buck2 currently demonstrates resource manifest parity. It does not yet prove
  Compose Kotlin compilation or rendering, so it should remain a resource example.
- Bazel remains outside this modernization pass; its fixture pins are unchanged.

## Version alignment

Tools/plugin, sessions/CLI, driver, daemon, and contracts have independent release
lines. Daemon and contracts BOMs select their module families. Renovate groups
changes for a coherent compatibility check, but should not force identical version
strings. The Compose fixtures pin 1.11.1, the released desktop daemon baseline,
instead of silently inheriting the new Toolchain's 1.12.1 default.
