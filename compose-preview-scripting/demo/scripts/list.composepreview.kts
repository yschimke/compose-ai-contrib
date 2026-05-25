// List every rendered preview with its PNG path and sha256. Demonstrates the
// `previews()` DSL — see `compose-preview-scripting/README.md`.

println("Rendered previews:")
for (ui in previews()) {
  println("  ${ui.id}")
  println("    module    = ${ui.module}")
  println("    function  = ${ui.functionName}")
  println("    source    = ${ui.sourceFile}")
  println("    pngPath   = ${ui.pngPath}")
  println("    sha256    = ${ui.sha256?.take(16)}...")
  println("    captures  = ${ui.captures.size}")
}
println("Total: ${previews().size}")
