package com.example.bazelsample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview
@Composable
fun Greeting() {
    Text(
        text = stringResource(R.string.greeting),
        modifier = Modifier.background(colorResource(R.color.proof_background)).padding(16.dp),
    )
}
