package demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Greeting")
@Composable
fun GreetingPreview() {
  MaterialTheme {
    Column(
      modifier = Modifier.padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text("Hello, world", style = MaterialTheme.typography.headlineMedium)
      Text("compose-preview-scripting demo", style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Preview(name = "Red Box", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun RedBoxPreview() {
  Box(
    modifier = Modifier.size(120.dp).background(Color.Red),
    contentAlignment = Alignment.Center,
  ) {
    Text("Red", color = Color.White)
  }
}

@Preview(name = "Blue Box", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun BlueBoxPreview() {
  Box(
    modifier = Modifier.size(120.dp).background(Color.Blue),
    contentAlignment = Alignment.Center,
  ) {
    Text("Blue", color = Color.White)
  }
}
