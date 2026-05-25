// Minimal `:renderer-desktop` for the `compose-preview-scripting` demo. The published
// `ee.schimke.composeai.preview` plugin hard-codes looking for a Gradle project at
// `:renderer-desktop` and adds it to its `composePreviewRenderer` configuration; when missing,
// it falls back to a stub that writes blank PNGs (which is why the original demo PNGs were
// byte-identical). This module supplies `ee.schimke.composeai.renderer.DesktopRendererMainKt`
// — the entry point the plugin's `composePreviewRender` task `java -cp` shells out to — with a
// minimal single-frame `ImageComposeScene` render path that covers the basic `@Preview` shape.
// `@ScrollingPreview`, `@PreviewParameter`, `@PreviewWrapper`, pseudolocale and display-filter
// variants are deliberately out of scope here; they need internal modules upstream keeps
// unpublished. The demo previews don't use them.

plugins {
  alias(libs.plugins.kotlin.jvm)
  id("org.jetbrains.compose") version "1.10.3"
  id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.foundation)
  implementation(compose.runtime)
  implementation(compose.ui)
}
