pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
    google()
    // Gradle Tooling API artifacts (`org.gradle:gradle-tooling-api:*`) — pulled in transitively by
    // `:compose-preview-scripting`'s `gradle-preview-driver` dependency. Lives on Gradle's libs
    // releases repo, not Maven Central.
    maven("https://repo.gradle.org/gradle/libs-releases") {
      name = "gradleLibsReleases"
      content { includeGroup("org.gradle") }
    }
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
      name = "ossSnapshots"
      content { includeGroup("ee.schimke.composeai") }
    }
  }
}

rootProject.name = "compose-ai-contrib"

// Shared portable-bundle producer: the ClassGraph reachability closure walk, the
// PNG+ZIP polyglot writer, and the Maven-coordinate / `maven_install.json`
// recovery the Amper and Bazel drivers use to emit `bundle.json` (schema v3/v4)
// that the upstream `:bundle-viewer` can open. Pure JVM, no Gradle/AGP/Amper/Bazel
// dependency — it just takes class dirs/jars + a coordinate map. See
// `docs/portable-bundles.md`.
include(":bundle-producer")

include(":contract-tests:amper-cmp-desktop")
project(":contract-tests:amper-cmp-desktop").projectDir =
  file("contract-tests/amper-cmp-desktop")

// Standalone `compose-preview-scripting` binary. Lifted from upstream's
// `examples/scripting/` reference (yschimke/compose-ai-tools PR #1375) as the
// published home for the Kotlin scripting host. Consumes only published
// artifacts (`ee.schimke.composeai:preview-data-api` + `:gradle-preview-driver`)
// — see `compose-preview-scripting/README.md` for the version-pin caveat.
include(":compose-preview-scripting")

// Tiny Compose Desktop fixture used to demo `compose-preview-scripting` against a real
// `@Preview`. Applies the published `ee.schimke.composeai.preview` Gradle plugin so the
// scripting binary can discover + render against it. As of composeai 0.11.15 the plugin pulls
// `ee.schimke.composeai:renderer-desktop` from Maven Central by default, so no local
// renderer-wiring is needed here. See `compose-preview-scripting/demo/README.md`.
include(":compose-preview-scripting:demo")
