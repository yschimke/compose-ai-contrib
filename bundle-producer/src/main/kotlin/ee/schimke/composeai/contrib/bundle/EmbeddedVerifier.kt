package ee.schimke.composeai.contrib.bundle

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Guards the invariant that **embedding is a fallback for genuinely coordinate-less jars** — not for
 * a dependency we simply failed to recover a coordinate for. A jar we embed should not be one that
 * actually exists in a Maven repository (embedding a published artifact bloats the bundle and
 * forfeits the detached-coordinate design).
 *
 * Maven Central indexes artifacts by checksum, so a jar can be looked up by its **SHA-1** even with
 * no known coordinate ([ChecksumSearch]). [verify] runs that lookup over the jars a producer is
 * about to embed and reports any that are in fact published — the caller then warns (or fails) and
 * the operator records a coordinate instead.
 *
 * Note the producers' inputs make this especially worth checking: every jar in **Amper's m2 cache**
 * was resolved *from* a repo by Amper, so a coordinate-recovery miss there is, by construction, a
 * published artifact. For Bazel a `maven_install.json` miss is ambiguous (a vendored `//third_party`
 * jar vs. a basename mismatch); the checksum lookup disambiguates it.
 */
object EmbeddedVerifier {

  /** Resolves a jar's SHA-1 to a `group:artifact:version` if a Maven repo publishes those bytes. */
  fun interface ChecksumSearch {
    /** Coordinate (`g:a:v`) for the artifact whose bytes hash to [sha1], or null if none/unknown. */
    fun coordinateForSha1(sha1: String): String?
  }

  /**
   * @param jar the embedded jar in question.
   * @param publishedAs `group:artifact:version` when the registry knows these bytes (⇒ should be a
   *   coordinate, not embedded), else null.
   * @param checked false when the lookup itself could not run (offline / endpoint error) — the jar
   *   is then neither cleared nor flagged, only "unverified".
   */
  data class Finding(val jar: File, val publishedAs: String?, val checked: Boolean) {
    val resolvable: Boolean get() = publishedAs != null
  }

  /** Look up each of [jars] by SHA-1 through [search]. */
  fun verify(jars: List<File>, search: ChecksumSearch): List<Finding> =
    jars.map { jar ->
      val sha1 = runCatching { sha1Hex(jar) }.getOrNull()
        ?: return@map Finding(jar, publishedAs = null, checked = false)
      val hit = runCatching { search.coordinateForSha1(sha1) }
      Finding(jar, publishedAs = hit.getOrNull(), checked = hit.isSuccess)
    }

  fun sha1Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-1")
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /**
   * Maven Central's checksum search (`solrsearch/select?q=1:"<sha1>"`). The base URL is overridable
   * (`-Dcontrib.checksumSearchUrl=…`, or pass [endpoint]) so a network-restricted environment can
   * point it at a mirror that serves the same Solr API. Any non-200, timeout, or parse error yields
   * null — verification is best-effort and never blocks bundle production.
   */
  class MavenCentralChecksumSearch(
    private val endpoint: String =
      System.getProperty("contrib.checksumSearchUrl") ?: "https://search.maven.org/solrsearch/select",
    private val timeoutMs: Int = 8_000,
  ) : ChecksumSearch {
    private val json = Json { ignoreUnknownKeys = true }

    override fun coordinateForSha1(sha1: String): String? {
      val url = "$endpoint?q=1:%22$sha1%22&rows=1&wt=json"
      val body =
        try {
          val conn = URI(url).toURL().openConnection() as HttpURLConnection
          conn.connectTimeout = timeoutMs
          conn.readTimeout = timeoutMs
          conn.requestMethod = "GET"
          conn.setRequestProperty("Accept", "application/json")
          if (conn.responseCode != 200) {
            conn.disconnect()
            return null
          }
          conn.inputStream.use { it.readBytes().decodeToString() }
        } catch (_: Exception) {
          return null
        }
      val doc =
        runCatching { json.decodeFromString(SolrResponse.serializer(), body) }
          .getOrNull()
          ?.response
          ?.docs
          ?.firstOrNull() ?: return null
      val g = doc.g ?: return null
      val a = doc.a ?: return null
      val v = doc.v ?: doc.latestVersion ?: return null
      return "$g:$a:$v"
    }

    @Serializable private data class SolrResponse(val response: SolrBody? = null)

    @Serializable private data class SolrBody(val docs: List<SolrDoc> = emptyList())

    @Serializable
    private data class SolrDoc(
      val g: String? = null,
      val a: String? = null,
      val v: String? = null,
      val latestVersion: String? = null,
    )
  }
}
