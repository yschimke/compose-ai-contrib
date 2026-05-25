// Filter and audit. Every "Box" preview in this module should have rendered to a
// PNG that's at least 1 KB on disk — the rendered output should actually contain
// pixels, not be a zero-byte placeholder. Demonstrates the `fail(...)`
// accumulating-failure path: the script keeps going after each `fail` so a
// real audit reports every offender in one pass rather than aborting on the
// first.

import java.io.File

val boxes = previews().filter { it.id.contains("Box") }
println("Auditing ${boxes.size} Box preview(s)...")

for (ui in boxes) {
  val path = ui.pngPath
  if (path == null) {
    fail("${ui.id}: no pngPath set")
    continue
  }
  val file = File(path)
  if (!file.exists()) {
    fail("${ui.id}: PNG missing at $path")
    continue
  }
  val sizeKb = file.length() / 1024
  println("  ${ui.id}: ${sizeKb} KB")
  if (sizeKb < 1) fail("${ui.id}: PNG suspiciously small (${file.length()} bytes)")
}
