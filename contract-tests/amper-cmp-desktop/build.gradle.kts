import org.gradle.internal.os.OperatingSystem

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

val rendererRuntime by configurations.creating

// Skiko ships a separate per-OS native-runtime artifact carrying `libskiko-<os>-<arch>.so`.
// `org.jetbrains.compose.desktop:desktop` only pulls the API jars (`skiko`, `skiko-awt`)
// transitively, so the daemon JVM hits `org.jetbrains.skiko.LibraryLoadException: Cannot find
// libskiko-linux-x64.so.sha256` the first time Compose Desktop's `ImageComposeScene` triggers
// `Surface.<clinit>`. Compose's Gradle plugin papers this over with `compose.desktop.currentOs`;
// we don't have the plugin, so we declare the platform-specific runtime explicitly.
val skikoClassifier: String =
  OperatingSystem.current().let { os ->
    val arch = System.getProperty("os.arch")
    when {
      os.isLinux && arch == "amd64" -> "linux-x64"
      os.isLinux && arch == "aarch64" -> "linux-arm64"
      os.isMacOsX && arch == "x86_64" -> "macos-x64"
      os.isMacOsX && arch == "aarch64" -> "macos-arm64"
      os.isWindows -> "windows-x64"
      else -> error("unsupported skiko platform: os=$os arch=$arch")
    }
  }

dependencies {
  testImplementation(libs.composeai.daemon.launch.builder)
  testImplementation(libs.composeai.render.session.subprocess)
  testImplementation(libs.composeai.render.session.api)

  rendererRuntime(libs.composeai.daemon.desktop)
  rendererRuntime(libs.composeai.daemon.core)
  rendererRuntime(libs.composeai.data.render.connector)
  rendererRuntime(libs.composeai.data.render.core)
  rendererRuntime("org.jetbrains.skiko:skiko-awt-runtime-$skikoClassifier:${libs.versions.skiko.get()}")

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

tasks.test {
  systemProperty("contrib.rendererClasspath", rendererRuntime.asPath)
  systemProperty(
    "contrib.amperFixtureDir",
    rootProject.layout.projectDirectory.dir("amper-cmp-desktop").asFile.absolutePath,
  )
}

kotlin {
  jvmToolchain(17)
}
