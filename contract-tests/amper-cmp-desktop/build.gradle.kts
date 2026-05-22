plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

val rendererRuntime by configurations.creating

dependencies {
  testImplementation(libs.composeai.daemon.launch.builder)
  testImplementation(libs.composeai.render.session.subprocess) {
    // 0.11.0's POM declares a runtime-scope dep on `mcp:0.11.0`, which isn't
    // published. The contract test doesn't exercise the MCP server path, so
    // we can resolve without it; revisit once upstream ships the artifact (or
    // drops the dep from render-session-subprocess).
    exclude(group = "ee.schimke.composeai", module = "mcp")
  }
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
