pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
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
