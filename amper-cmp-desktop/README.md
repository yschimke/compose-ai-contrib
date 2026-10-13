# Amper / Kotlin Toolchain + Compose Preview

A standalone Material 3 Compose Desktop fixture. Build with `./kotlin build`;
`./amper` remains a compatibility entrypoint. The wrapper pins released Kotlin
Toolchain 0.13.0 with the upstream SHA-256, rather than a development snapshot.

From the repository root, build `amper-compose-example` as well and run:

```bash
./gradlew :contract-tests:amper-cmp-desktop:test -Pcontrib.requireAmperOutputs=true
./gradlew :contract-tests:amper-cmp-desktop:run
```

The parameterized test discovers both fixtures from compiled bytecode using
`preview-discovery`, builds a descriptor with `daemon-launch-builder`, and renders
through `SubprocessRenderSessions`. It decodes the PNG and rejects uniform images.
Missing builds are reported as JUnit skips locally and failures in CI.

Compose is explicitly pinned to 1.11.1 to match the released desktop daemon's
baseline; Kotlin Toolchain 0.13 defaults to 1.12.1. `jvm.release: 17` matches the
consumer test toolchain. Raising either version needs a tested renderer baseline.

Compiled classes are under
`build/artifacts/CompiledJvmArtifact/<module>jvm/kotlin-output/`.
See [the survey](../docs/integration-survey.md) for the upstream example provenance.

For a TLS inspection proxy, pass its trusted Java keystore through
`KOTLIN_CLI_JAVA_OPTIONS`; the wrapper and provisioned JDK both need network access.
