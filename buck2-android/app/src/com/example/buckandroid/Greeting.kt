package com.example.buckandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Greeting() {
  Box(Modifier.background(Color(0xFF1B5E20)).padding(16.dp)) {
    BasicText(stringResource(R.string.greeting), style = TextStyle(color = Color.White, fontSize = 20.sp))
  }
}

@Preview
@Composable
fun GreetingPreview() {
  Greeting()
}
