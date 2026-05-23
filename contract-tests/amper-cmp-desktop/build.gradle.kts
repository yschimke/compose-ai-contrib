plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

val rendererRuntime by configurations.creating

dependencies {
  testImplementation(libs.composeai.daemon.launch.builder)
  testImplementation(libs.composeai.render.session.subprocess)
  testImplementation(libs.composeai.render.session.api)

  rendererRuntime(libs.composeai.daemon.desktop)
  rendererRuntime(libs.composeai.daemon.core)
  rendererRuntime(libs.composeai.data.render.connector)
  rendererRuntime(libs.composeai.data.render.core)

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
