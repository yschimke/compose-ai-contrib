// Standalone `compose-preview-scripting` binary. Lifted from
// `yschimke/compose-ai-tools` PR #1375 (`examples/scripting/`) into this repo as the published
// home for the scripting host. Consumes only the published surface — `preview-data-api` for the
// wire DTOs, `gradle-preview-driver` for the render pipeline, plus the Kotlin scripting host
// libraries. No dependency on `:cli`.
//
// Built via the `application` plugin: `./gradlew :compose-preview-scripting:installDist` produces
// `build/install/compose-preview-scripting/bin/compose-preview-scripting`, the same shape as
// `:render-cli` / `:preview-discovery` / `:daemon-launch-builder` and the other published-API
// consumers this repo exercises.

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

base { archivesName.set("compose-preview-scripting") }

application {
  applicationName = "compose-preview-scripting"
  mainClass.set("ee.schimke.composeai.scripting.MainKt")
}

dependencies {
  // Published wire-format DTOs — `PreviewResult`, `ExtensionPayload`, the v1 a11y mirror types.
  // After upstream's wire-format bump to v2 the a11y mirror disappears and contrib switches to
  // `:data-a11y-core`'s typed entries directly; until then the mirror is the contract.
  implementation(libs.composeai.preview.data.api)

  // Render pipeline as a library — opens Gradle, runs `composePreviewRenderAll`, returns
  // `PreviewResult`s with PNG sha256s populated.
  implementation(libs.composeai.gradle.preview.driver)

  // JSR-223-style scripting host. Pulls `kotlin-compiler-embeddable` transitively (~50 MB);
  // ships inside this standalone binary only, not in the main CLI tarball.
  implementation(libs.kotlin.scripting.common)
  implementation(libs.kotlin.scripting.jvm)
  implementation(libs.kotlin.scripting.jvm.host)

  testImplementation(kotlin("test"))
  testImplementation(libs.junit)
}

kotlin {
  jvmToolchain(17)
}
