package com.example.bazeldesktop

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

// This belongs to a dependency and must never be listed as one of app's previews.
@Composable
@Preview
fun DependencyPreview() {
  BasicText("Dependency preview must not be discovered")
}
