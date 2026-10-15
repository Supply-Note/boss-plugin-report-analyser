package ai.rever.boss.plugin.dynamic.reportanalyser

import ai.rever.boss.plugin.api.SecretEntryData
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

enum class AuthMode(val label: String) {
    BEARER("Bearer token"),
    BASIC("Basic (user + password)"),
    HEADER("API-key header"),
}

/** Credentials resolved from a BOSS secret; the password is never logged or shown. */
data class FetchAuth(val secret: SecretEntryData, val mode: AuthMode, val headerName: String = "X-API-Key")

data class FetchedReport(
    val bytes: ByteArray,
    val fileName: String,
    val contentType: String?,
    /** Final URL after redirects, or the absolute file path. */
    val location: String,
)

class ReportFetchException(message: String) : Exception(message)

/** Fetches report bytes over HTTP(S) or from local disk (read-only). */
class ReportFetcher {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        // Redirects are followed manually so credentials never leak to another host.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    suspend fun fetchUrl(url: String, auth: FetchAuth?): FetchedReport {
        var uri = parseHttpUri(url)
        val originalHost = uri.host.lowercase()
        repeat(MAX_REDIRECTS + 1) {
            val sameHost = uri.host.lowercase() == originalHost
            val request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "BOSS-ReportAnalyser/1.0")
                .header("Accept", "*/*")
                .GET()
                .apply { if (auth != null && sameHost) applyAuth(this, auth) }
                .build()
            val response = try {
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
            } catch (e: Exception) {
                throw ReportFetchException("Couldn't reach ${uri.host}: ${e.message ?: e.javaClass.simpleName}")
            }
            val status = response.statusCode()
            if (status in 300..399) {
                response.body().close()
                val location = response.headers().firstValue("location").orElse(null)
                    ?: throw ReportFetchException("HTTP $status redirect without a Location header")
                uri = parseHttpUri(uri.resolve(location).toString())
                return@repeat
            }
            val body = withContext(Dispatchers.IO) { response.body().use { readLimited(it) } }
            if (status !in 200..299) {
                val snippet = String(body, Charsets.UTF_8).take(300).replace(Regex("\\s+"), " ")
                val hint = when (status) {
                    401, 403 -> " — check the authentication secret"
                    404 -> " — report not found"
                    else -> ""
                }
                throw ReportFetchException("HTTP $status$hint${if (snippet.isNotBlank()) ": $snippet" else ""}")
            }
            val contentType = response.headers().firstValue("content-type").orElse(null)
            val disposition = response.headers().firstValue("content-disposition").orElse(null)
            return FetchedReport(body, fileNameFor(uri, disposition, contentType), contentType, uri.toString())
        }
        throw ReportFetchException("Too many redirects")
    }

    suspend fun readFile(path: String): FetchedReport = withContext(Dispatchers.IO) {
        val file = File(expandHome(path)).absoluteFile
        if (!file.exists()) throw ReportFetchException("File not found: ${file.path}")
        if (file.isDirectory) throw ReportFetchException("${file.path} is a directory")
        if (file.length() > MAX_BYTES) throw ReportFetchException("File is larger than ${MAX_BYTES / (1024 * 1024)} MB")
        FetchedReport(file.readBytes(), file.name, null, file.path)
    }

    private fun applyAuth(builder: HttpRequest.Builder, auth: FetchAuth) {
        val secret = auth.secret.password
        when (auth.mode) {
            AuthMode.BEARER -> builder.header("Authorization", "Bearer $secret")
            AuthMode.BASIC -> {
                val token = Base64.getEncoder().encodeToString("${auth.secret.username}:$secret".toByteArray())
                builder.header("Authorization", "Basic $token")
            }
            AuthMode.HEADER -> builder.header(auth.headerName.ifBlank { "X-API-Key" }, secret)
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw ReportFetchException("Report is larger than ${MAX_BYTES / (1024 * 1024)} MB")
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_BYTES = 25 * 1024 * 1024
        private const val MAX_REDIRECTS = 5

        fun isUrl(source: String): Boolean =
            source.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }

        fun parseHttpUri(url: String): URI {
            val uri = try {
                URI(url.trim())
            } catch (e: Exception) {
                throw ReportFetchException("Invalid URL: ${e.message}")
            }
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                throw ReportFetchException("Only http(s) URLs are supported")
            }
            return uri
        }

        fun expandHome(path: String): String =
            if (path.startsWith("~")) System.getProperty("user.home") + path.removePrefix("~") else path

        /** Host of a secret's `website` field, tolerant of bare domains. */
        fun secretHost(secret: SecretEntryData): String? {
            val site = secret.website.trim()
            if (site.isEmpty()) return null
            val withScheme = if (site.contains("://")) site else "https://$site"
            return runCatching { URI(withScheme).host?.lowercase()?.removePrefix("www.") }.getOrNull()?.takeIf { it.contains('.') }
        }

        /** True when [url]'s host is the secret's host or one of its subdomains. */
        fun secretMatchesUrl(secret: SecretEntryData, url: String): Boolean {
            val secretHost = secretHost(secret) ?: return false
            val host = runCatching { URI(url.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return false
            return host == secretHost || host.endsWith(".$secretHost")
        }

        private fun fileNameFor(uri: URI, disposition: String?, contentType: String?): String {
            disposition?.let { d ->
                Regex("filename\\*=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(d)?.let {
                    return URLDecoder.decode(it.groupValues[1], Charsets.UTF_8)
                }
                Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(d)?.let { return it.groupValues[1] }
            }
            val fromPath = uri.path.orEmpty().substringAfterLast('/').takeIf { it.isNotBlank() }
            if (fromPath != null && fromPath.contains('.')) return fromPath
            val ext = when {
                contentType == null -> ""
                contentType.contains("csv") -> ".csv"
                contentType.contains("json") -> ".json"
                contentType.contains("html") -> ".html"
                contentType.contains("pdf") -> ".pdf"
                contentType.contains("spreadsheetml") -> ".xlsx"
                contentType.contains("text/plain") -> ".txt"
                else -> ""
            }
            return (fromPath ?: uri.host) + ext
        }
    }
}
