pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
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
