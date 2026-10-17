import java.io.File

/** Run with `./kotlin run --no-compose-hot-reload --main-class RuntimeClasspathKt -- build/runtime-classpath.txt`. */
fun main(args: Array<String>) {
  require(args.size == 1) { "Expected the output file path" }
  val entries = System.getProperty("java.class.path").split(File.pathSeparator)
  require(entries.isNotEmpty() && entries.none(String::isBlank)) { "Empty JVM runtime classpath" }
  File(args.single()).apply {
    parentFile?.mkdirs()
    writeText(entries.joinToString("\n", postfix = "\n"))
  }
}
