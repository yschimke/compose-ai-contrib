package com.example.buckdesktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A Compose Desktop `kotlin_library` built by Buck2 with the prelude's own Kotlin toolchain. */
@Composable
fun Greeting(name: String) {
  Box(Modifier.background(Color(0xFFE8F0FE)).padding(16.dp)) {
    BasicText(
      "Hello $name from Buck2",
      style = TextStyle(fontSize = 20.sp, color = Color(0xFF1A3A6B)),
    )
  }
}

@Preview
@Composable
fun GreetingPreview() {
  Greeting("Compose")
}
