package com.example.bazeldesktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.desktop.ui.tooling.preview.Preview

/**
 * Compose-Desktop `@Preview` for the Bazel portable-bundle demo. Mirrors the Amper desktop
 * fixture (`amper-cmp-desktop/src/Greeting.kt`) so the two build systems pack the same shape of
 * preview. Built by a `kt_jvm_library` (no `rules_android`), then packed into a portable bundle by
 * the `bundle_preview` rule — see `BUILD.bazel` and `../docs/portable-bundles.md`.
 */
@Composable
@Preview
fun Greeting() {
  MaterialTheme {
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      BasicText("Hello from Bazel Desktop!")
    }
  }
}
