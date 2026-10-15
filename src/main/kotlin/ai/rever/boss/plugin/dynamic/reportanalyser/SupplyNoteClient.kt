package ai.rever.boss.plugin.dynamic.reportanalyser

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

class SupplyNoteException(
    val status: Int,
    val code: String,
    message: String,
    val requestId: String?,
) : Exception(message)

/** One `{ "data", "meta" }` envelope. */
data class SnEnvelope(val data: Any?, val meta: Map<String, Any?>, val requestId: String?)

/** All pages of a report, flattened. */
data class SnResult(
    val rows: List<Map<*, *>>,
    /** Non-row parts of `data` (e.g. `summary`, `thresholds`) from the first page. */
    val extras: Map<String, Any?>,
    val meta: Map<String, Any?>,
    val pages: Int,
    val truncated: Boolean,
    val requestId: String?,
)

/**
 * Client for the SupplyNote Data API v2 (REST · JSON · X-API-Key).
 * Honours Retry-After on 429 and retries 500/503 once, per section 12 of the guide.
 */
class SupplyNoteClient {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    suspend fun get(baseUrl: String, path: String, params: Map<String, String>, apiKey: String): SnEnvelope {
        val uri = buildUri(baseUrl, path, params)
        val bytes = send(apiKey) { HttpRequest.newBuilder(uri).GET() }
        return parseEnvelope(bytes)
    }

    suspend fun post(baseUrl: String, path: String, body: Any?, apiKey: String): SnEnvelope {
        val uri = buildUri(baseUrl, path, emptyMap())
        val json = MiniJson.write(body)
        val bytes = send(apiKey) {
            HttpRequest.newBuilder(uri).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json))
        }
        return parseEnvelope(bytes)
    }

    /**
     * Fetches every page of [path] up to [maxRows]. Datasets walk `meta.next_cursor`;
     * offset-paged insights walk `offset` by the `limit` sent while `meta.has_more` is true.
     */
    suspend fun fetchAll(
        baseUrl: String,
        path: String,
        params: Map<String, String>,
        apiKey: String,
        maxRows: Int,
        offsetPaged: Boolean,
    ): SnResult {
        val rows = ArrayList<Map<*, *>>()
        var extras: Map<String, Any?> = emptyMap()
        var firstMeta: Map<String, Any?> = emptyMap()
        var requestId: String? = null
        var pages = 0
        var truncated = false
        val pageLimit = params["limit"]?.toIntOrNull() ?: DEFAULT_OFFSET_LIMIT
        var query = if (offsetPaged && "limit" !in params) params + ("limit" to pageLimit.toString()) else params
        var offset = params["offset"]?.toIntOrNull() ?: 0

        while (true) {
            val page = get(baseUrl, path, query, apiKey)
            pages++
            val (pageRows, pageExtras) = splitData(page.data)
            if (pages == 1) {
                extras = pageExtras
                firstMeta = page.meta
                requestId = page.requestId
            }
            rows += pageRows
            val cursor = page.meta["next_cursor"] as? String
            val hasMore = page.meta["has_more"] == true
            val more = !cursor.isNullOrBlank() || (offsetPaged && hasMore)
            if (!more) break
            if (rows.size >= maxRows || pages >= MAX_PAGES) {
                truncated = true
                break
            }
            query = if (!cursor.isNullOrBlank()) {
                params + ("cursor" to cursor)
            } else {
                offset += pageLimit
                params + ("limit" to pageLimit.toString()) + ("offset" to offset.toString())
            }
        }
        if (rows.size > maxRows) {
            truncated = true
        }
        return SnResult(rows.take(maxRows), extras, firstMeta, pages, truncated, requestId)
    }

    /** Raw GET for file-shaped responses (feed data). Follows redirects, dropping the key off-host. */
    suspend fun getFile(baseUrl: String, path: String, params: Map<String, String>, apiKey: String): FetchedReport {
        var uri = buildUri(baseUrl, path, params)
        val host = uri.host
        repeat(6) {
            val sendKey = uri.host == host
            val response = request(HttpRequest.newBuilder(uri).GET(), if (sendKey) apiKey else null)
            val status = response.statusCode()
            if (status in 300..399) {
                response.body().close()
                val location = response.headers().firstValue("location").orElse(null)
                    ?: throw SupplyNoteException(status, "redirect", "Redirect without Location", null)
                uri = uri.resolve(location)
                return@repeat
            }
            val bytes = withContext(Dispatchers.IO) { response.body().use { readLimited(it) } }
            if (status !in 200..299) throw errorFrom(status, bytes, response)
            val ctype = response.headers().firstValue("content-type").orElse(null)
            val name = path.trimEnd('/').substringBeforeLast("/data").substringAfterLast('/') + when {
                ctype?.contains("csv") == true -> ".csv"
                ctype?.contains("json") == true -> ".json"
                else -> ""
            }
            return FetchedReport(bytes, name, ctype, uri.toString())
        }
        throw SupplyNoteException(0, "redirect", "Too many redirects", null)
    }

    // ---- internals ---------------------------------------------------------------------

    private suspend fun send(apiKey: String, build: () -> HttpRequest.Builder): ByteArray {
        var attempt = 0
        while (true) {
            attempt++
            val response = request(build(), apiKey)
            val status = response.statusCode()
            val bytes = withContext(Dispatchers.IO) { response.body().use { readLimited(it) } }
            if (status in 200..299) return bytes
            val retryable = status == 429 || status == 500 || status == 503
            if (retryable && attempt < MAX_ATTEMPTS) {
                val retryAfter = response.headers().firstValue("retry-after").orElse(null)?.toLongOrNull()
                val waitMs = when (status) {
                    429 -> ((retryAfter ?: 5L).coerceIn(1L, MAX_RETRY_AFTER_SECONDS)) * 1000
                    else -> 2_000L * attempt
                }
                delay(waitMs)
                continue
            }
            throw errorFrom(status, bytes, response)
        }
    }

    private suspend fun request(builder: HttpRequest.Builder, apiKey: String?): HttpResponse<InputStream> {
        builder.timeout(Duration.ofSeconds(120))
            .header("Accept", "application/json, text/csv;q=0.9, */*;q=0.8")
            .header("User-Agent", "BOSS-ReportAnalyser/1.0")
        if (apiKey != null) builder.header("X-API-Key", apiKey)
        return try {
            client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream()).await()
        } catch (e: Exception) {
            throw SupplyNoteException(0, "network", "Couldn't reach SupplyNote: ${e.message ?: e.javaClass.simpleName}", null)
        }
    }

    private fun parseEnvelope(bytes: ByteArray): SnEnvelope {
        val root = try {
            MiniJson.parse(String(bytes, Charsets.UTF_8)) as? Map<*, *>
        } catch (e: Exception) {
            null
        } ?: throw SupplyNoteException(200, "bad_response", "SupplyNote returned a response that isn't a JSON envelope", null)
        @Suppress("UNCHECKED_CAST")
        val meta = (root["meta"] as? Map<String, Any?>).orEmpty()
        return SnEnvelope(root["data"], meta, meta["request_id"] as? String)
    }

    private fun errorFrom(status: Int, bytes: ByteArray, response: HttpResponse<*>): SupplyNoteException {
        val err = runCatching { (MiniJson.parse(String(bytes, Charsets.UTF_8)) as Map<*, *>)["error"] as Map<*, *> }.getOrNull()
        val code = err?.get("code") as? String ?: "http_$status"
        val requestId = err?.get("requestId") as? String ?: response.headers().firstValue("x-request-id").orElse(null)
        val serverMessage = err?.get("message") as? String
        val hint = when (code) {
            "missing_key", "invalid_key", "expired_key", "revoked_key", "unauthorized" ->
                "the API key was rejected ($code) — check the secret in BOSS Secrets"
            "missing_scope" -> "the API key lacks the scope this call needs"
            "outlet_not_permitted" -> "the key isn't allowed to read one of the requested outlets"
            "date_range_required" -> "this report needs start_date and end_date"
            "date_window_unused" -> "this report doesn't take a date range — clear the dates"
            "category_required" -> "this grouping needs a category filter"
            "rate_limited" -> "rate limited (60 requests/minute per key) — try again shortly"
            "exports_not_configured", "exports_not_provisioned" -> "exports aren't enabled for this business"
            else -> null
        }
        val text = buildString {
            append("SupplyNote ")
            append(if (status > 0) "HTTP $status" else "error")
            append(": ")
            append(hint ?: serverMessage ?: String(bytes, Charsets.UTF_8).take(200).ifBlank { code })
            if (hint != null && serverMessage != null) append(" — $serverMessage")
            if (requestId != null) append(" (request id $requestId)")
        }
        return SupplyNoteException(status, code, text, requestId)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw SupplyNoteException(0, "too_large", "Response larger than ${MAX_BYTES / (1024 * 1024)} MB", null)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val MAX_RETRY_AFTER_SECONDS = 60L
        private const val MAX_PAGES = 25
        private const val MAX_BYTES = 40 * 1024 * 1024
        const val DEFAULT_OFFSET_LIMIT = 200

        fun buildUri(baseUrl: String, path: String, params: Map<String, String>): URI {
            val base = ReportFetcher.parseHttpUri(baseUrl).toString().trimEnd('/')
            val query = params.filterValues { it.isNotBlank() }.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8)
            }
            return URI(base + path + if (query.isNotEmpty()) "?$query" else "")
        }

        /** Splits `data` into row records and everything else. */
        fun splitData(data: Any?): Pair<List<Map<*, *>>, Map<String, Any?>> {
            fun records(v: Any?): List<Map<*, *>>? =
                (v as? List<*>)?.takeIf { l -> l.isNotEmpty() && l.all { it is Map<*, *> } }?.map { it as Map<*, *> }
            records(data)?.let { return it to emptyMap() }
            val obj = data as? Map<*, *> ?: return emptyList<Map<*, *>>() to emptyMap()
            val rowsKey = obj.entries.filter { records(it.value) != null }.maxByOrNull { (it.value as List<*>).size }?.key
            val rows = rowsKey?.let { records(obj[it]) }
                // A single-resource endpoint returns one object: treat it as one row.
                ?: if (obj.values.none { it is Map<*, *> || it is List<*> }) listOf(obj) else emptyList()
            val extras = obj.entries.filter { it.key != rowsKey && !(rows.size == 1 && rows[0] === obj) }
                .associate { it.key.toString() to it.value }
            return rows to extras
        }

        /** Pulls the download link out of `meta.export` (string or object). */
        fun exportUrl(meta: Map<String, Any?>): String? {
            fun find(v: Any?): String? = when (v) {
                is String -> v.takeIf { ReportFetcher.isUrl(it) }
                is Map<*, *> -> listOf("url", "download_url", "href", "link").firstNotNullOfOrNull { find(v[it]) }
                    ?: v.values.firstNotNullOfOrNull { find(it) }
                else -> null
            }
            return find(meta["export"])
        }
    }
}
