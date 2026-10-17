# Buck2 sample: Compose Desktop previews

A Compose Multiplatform **Desktop** `@Preview` in a plain Buck2 `kotlin_library`,
rendered by `compose-preview` with no BUCK changes. The CLI detects the project
(`.buckroot`), runs its BXL script against the library's JVM providers and
renders on the desktop daemon. How that works:
[compose-ai-tools `docs/BUCK.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/BUCK.md).

```
cd buck2-desktop/app
compose-preview list
compose-preview render --filter Greeting --output greeting.png
```

## Layout

```
buck2-desktop/
├── .buckconfig         # root cell + the prelude bundled with buck2
├── .buckroot
├── toolchains/BUCK     # system_demo_toolchains(): the prelude's Java/Kotlin toolchains
├── third_party/BUCK    # Compose Desktop jars + the Compose compiler plugin
└── app/
    ├── BUCK            # kotlin_library with the Compose compiler plugin
    └── src/com/example/buckdesktop/Greeting.kt
```

## Setup

- **buck2**: a dated release (the CI pins one in `.github/workflows/buck2.yml`).
- **JDK 21**, named in `.buckconfig.local` (git-ignored). The prelude compiles its
  own Kotlin and Java compiler daemons from source, which needs 21:

  ```
  [java]
    java_home = /usr/lib/jvm/temurin-21-jdk-amd64
  ```

## Versions

The bundled prelude's `kotlincd_toolchain` ships **kotlinc 2.2.0**, so:

- the Compose compiler plugin is `kotlin-compose-compiler-plugin-embeddable:2.2.0`
  (it is versioned with the compiler and refuses any other); and
- Compose Multiplatform is **1.10.3**, the newest built with Kotlin 2.2 metadata,
  which kotlinc 2.2.0 can read. 1.11 is built with a newer Kotlin.

`third_party/BUCK` is the runtime closure Gradle resolves for
`foundation-desktop`, `ui-tooling-preview-desktop` and `desktop-jvm-linux-x64`
at 1.10.3, one `remote_file` + `prebuilt_jar` per jar (`androidx.*` from
`maven.google.com`, the rest from Maven Central), gathered by `compose_desktop`.
To move versions, resolve the same three coordinates in a scratch Gradle
project's `runtimeClasspath` and regenerate the entries with their SHA-1s.
