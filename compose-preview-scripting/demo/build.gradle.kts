// Tiny Compose Desktop fixture exercised by `compose-preview-scripting`. The
// `ee.schimke.composeai.preview` Gradle plugin registers `composePreviewDiscover` and
// `composePreviewRender` tasks; the scripting binary opens this build via Tooling API,
// invokes them through `GradlePreviewDriver`, and evaluates a `.composepreview.kts` script
// against the resulting `PreviewResult`s.

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.composeai.preview)
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.material3)
  implementation(compose.foundation)
  // `androidx.compose.ui.tooling.preview.Preview` — discovery scans by FQN against this
  // ui-tooling-preview artifact. The CMP-bundled `compose.components.uiToolingPreview`
  // publishes under `org.jetbrains.compose.ui.tooling.preview.Preview`, which discovery
  // doesn't recognise. See cmp-shared upstream sample for the rationale.
  implementation("org.jetbrains.compose.ui:ui-tooling-preview:${libs.versions.compose.get()}")
}
