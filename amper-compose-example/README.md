# JetBrains Compose Desktop example

`src/main.kt` is copied from the Apache-2.0 licensed
[JetBrains Kotlin Toolchain v0.13.0 example](https://github.com/JetBrains/kotlin-toolchain/blob/abc7a5f7579e9ef267107782048c76777f0bf932/examples/compose-desktop/src/main.kt).
It includes both `@Preview App()` and the window application entrypoint.

The module retains the upstream `jvm/app` product and desktop dependency, with
Compose 1.11.1 and Java 17 overrides matching the released preview daemon.
The wrapper uses the official 0.13.0 distribution and SHA-256.

```bash
./kotlin build
```

From the repository root, build the Material 3 fixture as well, then run
`./gradlew :contract-tests:amper-cmp-desktop:test -Pcontrib.requireAmperOutputs=true`.
The contract test scans this app's real bytecode and renders its discovered App
preview. CI builds it without Gradle before invoking the consumer test.
