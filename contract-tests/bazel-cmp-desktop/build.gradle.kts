plugins {
  alias(libs.plugins.kotlin.jvm)
}

val rendererRuntime by configurations.creating

dependencies {
  testImplementation(libs.composeai.daemon.launch.builder)
  testImplementation(libs.composeai.render.session.subprocess)
  testImplementation(libs.composeai.render.session.api)
  testImplementation(libs.junit)
  testImplementation(libs.truth)
  rendererRuntime(libs.composeai.daemon.desktop)
  rendererRuntime(libs.composeai.daemon.core)
  rendererRuntime(libs.composeai.data.render.connector)
  rendererRuntime(libs.composeai.data.render.core)
}

tasks.test {
  // Explicitly invoked integration test: missing Bazel outputs are a failure.
  systemProperty("contrib.rendererClasspath", rendererRuntime.asPath)
  systemProperty("contrib.bazelFixtureDir", rootProject.file("bazel-desktop").absolutePath)
  providers.gradleProperty("bazelExecutionRoot").orNull?.let {
    systemProperty("contrib.bazelExecutionRoot", it)
  }
}

kotlin { jvmToolchain(17) }
