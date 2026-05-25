// Pull one preview by id and report its capture detail. Demonstrates the
// `show(id)` lookup and the per-capture metadata exposed on each
// `RenderedPreview` — the same shape `previews().filter { ... }` returns,
// but indexed for the "I know which one I want" path.

val ui = show("demo.PreviewsKt.GreetingPreview_Greeting")

println("Inspecting: ${ui.id}")
println("  className   = ${ui.className}")
println("  function    = ${ui.functionName}")
println("  pngPath     = ${ui.pngPath}")
println("  sha256      = ${ui.sha256}")
println("  changed?    = ${ui.changed}")
println("  captures    = ${ui.captures.size}")
for ((i, capture) in ui.captures.withIndex()) {
  println("  capture[$i]")
  println("    pngPath      = ${capture.pngPath}")
  println("    sha256       = ${capture.sha256}")
  println("    advanceMs    = ${capture.advanceTimeMillis}")
  println("    scroll       = ${capture.scroll}")
}
