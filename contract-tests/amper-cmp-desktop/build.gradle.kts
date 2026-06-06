plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

val rendererRuntime by configurations.creating

dependencies {
  // Producer (`PreviewProducer.main`) needs these at runtime too, not just
  // the test. Moved from `testImplementation` to `implementation` so the
  // `application` plugin's `run` task picks them up; the test still gets
  // them via the main-classpath flow-through.
  implementation(libs.composeai.daemon.launch.builder)
  implementation(libs.composeai.render.session.subprocess)
  implementation(libs.composeai.render.session.api)

  // Portable-bundle producer: after rendering, `PreviewProducer` packs a
  // schema-v4 `bundle.png` (coordinates from Amper's m2 cache + sha256, or
  // embedded) the upstream `:bundle-viewer` can open. See
  // `docs/portable-bundles.md`.
  implementation(project(":bundle-producer"))

  rendererRuntime(libs.composeai.daemon.desktop)
  rendererRuntime(libs.composeai.daemon.core)
  rendererRuntime(libs.composeai.data.render.connector)
  rendererRuntime(libs.composeai.data.render.core)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

application {
  // `PreviewProducer` is a Kotlin `object` with a `@JvmStatic main` — the
  // generated synthetic class is `PreviewProducerKt`-free since the entry
  // point lives on the object's companion-equivalent (the object itself).
  mainClass.set("ee.schimke.composeai.contrib.PreviewProducer")
}

tasks.test {
  systemProperty("contrib.rendererClasspath", rendererRuntime.asPath)
  systemProperty(
    "contrib.amperFixtureDir",
    rootProject.layout.projectDirectory.dir("amper-cmp-desktop").asFile.absolutePath,
  )
}

tasks.named<JavaExec>("run") {
  // Same sysprops the test gets, plus an explicit output path so CI can run
  // `./gradlew :contract-tests:amper-cmp-desktop:run` from anywhere and find
  // `_previews.json` at the repo root rather than buried under the JavaExec's
  // default working directory.
  systemProperty("contrib.rendererClasspath", rendererRuntime.asPath)
  systemProperty(
    "contrib.amperFixtureDir",
    rootProject.layout.projectDirectory.dir("amper-cmp-desktop").asFile.absolutePath,
  )
  systemProperty(
    "contrib.previewsOutputFile",
    rootProject.layout.projectDirectory.file("_previews.json").asFile.absolutePath,
  )
  // Portable bundle output (`docs/portable-bundles.md`). The coordinates pack is
  // always written; pass `-Dcontrib.bundleEmbed=true` for the offline embedded
  // pack alongside it.
  systemProperty(
    "contrib.bundleOutputFile",
    rootProject.layout.projectDirectory.file("_preview-bundle.png").asFile.absolutePath,
  )
  System.getProperty("contrib.bundleEmbed")?.let { systemProperty("contrib.bundleEmbed", it) }
  System.getProperty("contrib.amperM2Cache")?.let { systemProperty("contrib.amperM2Cache", it) }
  // Opt-in: SHA-1-check any coordinate-less embedded jar against Maven Central and warn if it's
  // actually published (a recovery miss). See `docs/portable-bundles.md`.
  System.getProperty("contrib.verifyEmbedded")?.let { systemProperty("contrib.verifyEmbedded", it) }
}

kotlin {
  jvmToolchain(17)
}
