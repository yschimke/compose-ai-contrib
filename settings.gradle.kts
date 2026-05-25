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
// scripting binary can discover + render against it. See
// `compose-preview-scripting/demo/README.md` for the walkthrough.
include(":compose-preview-scripting:demo")
