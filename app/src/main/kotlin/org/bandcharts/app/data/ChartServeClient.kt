package org.bandcharts.app.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.bandcharts.chartserve.Catalogue
import org.bandcharts.chartserve.ChartRecord
import org.bandcharts.chartserve.PairRequest
import org.bandcharts.chartserve.PairResponse
import org.bandcharts.chartserve.SetlistSummary

/**
 * Talking to a band's own ChartServe - see
 * [the server's docs](https://github.com/adrianoftyriel/ChartServe/blob/main/docs/API.md).
 *
 * **Why `HttpURLConnection` and not an HTTP client.** The same reasoning as
 * [org.bandcharts.app.update.Updater] and [WebChart]: this is a handful of GETs
 * behind a settings screen and a manual "refresh the library" action, not a
 * stream of requests that would earn its keep with connection pooling or
 * interceptors. Every call here is the direct result of somebody asking for it.
 *
 * **Why plain HTTP is allowed, unlike [WebChart] and [Updater].** Those two
 * fetch from wherever a link or a GitHub release happens to be, on whatever
 * network the phone is on, so refusing anything but `https` is refusing to be
 * fooled by a captive portal or a compromised network. ChartServe is a band's
 * own server, on an address they typed in themselves - often the bare LAN
 * address of a box in the room, with no certificate to have. Trusting that
 * address is exactly as much trust as pairing already requires: the pairing
 * code came from the same server, over the same connection.
 */
object ChartServeClient {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /** A catalogue or set-list JSON body. Generous, because a set list is opaque JSON. */
    private const val MAX_JSON_BYTES = 16 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface PairResult {
        data class Ok(val response: PairResponse) : PairResult
        data class Failed(val reason: String) : PairResult
    }

    sealed interface CatalogueResult {
        data class Ok(val catalogue: Catalogue) : CatalogueResult
        data class Failed(val reason: String) : CatalogueResult
    }

    sealed interface SetlistsResult {
        data class Ok(val setlists: List<SetlistSummary>) : SetlistsResult
        data class Failed(val reason: String) : SetlistsResult
    }

    sealed interface SetlistBodyResult {
        /** The `.bcset` file, exactly as ChartServe holds it. */
        data class Ok(val json: String) : SetlistBodyResult
        data class Failed(val reason: String) : SetlistBodyResult
    }

    sealed interface DownloadResult {
        data class Ok(val file: File) : DownloadResult
        data class Failed(val reason: String) : DownloadResult
    }

    /** What rides in the query string of a `PUT /v1/charts/{hash}` - see the server's docs. */
    data class UploadMeta(
        val displayName: String,
        val title: String?,
        val artist: String?,
        val kind: String?,
        val keyText: String?,
        val part: String?,
        val workTitle: String?,
    )

    sealed interface UploadResult {
        data class Ok(val record: ChartRecord, val created: Boolean) : UploadResult
        data class Failed(val reason: String) : UploadResult
    }

    /** Exchanges a pairing code for this device's own token. No token is sent - there isn't one yet. */
    suspend fun pair(serverUrl: String, code: String, deviceName: String?): PairResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.encodeToString(
                    PairRequest.serializer(),
                    PairRequest(code = code, deviceName = deviceName?.trim()?.ifEmpty { null }),
                )
                val connection = open(serverUrl, "/v1/pair", token = null, method = "POST")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                readResult(connection) { text -> json.decodeFromString(PairResponse.serializer(), text) }
            }.fold(
                onSuccess = { result ->
                    when (result) {
                        is CallResult.Ok -> PairResult.Ok(result.value)
                        is CallResult.Failed -> PairResult.Failed(result.reason)
                    }
                },
                onFailure = { PairResult.Failed(describe(it)) },
            )
        }

    suspend fun fetchCatalogue(serverUrl: String, token: String): CatalogueResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = open(serverUrl, "/v1/catalogue", token, method = "GET")
                readResult(connection) { text -> json.decodeFromString(Catalogue.serializer(), text) }
            }.fold(
                onSuccess = { result ->
                    when (result) {
                        is CallResult.Ok -> CatalogueResult.Ok(result.value)
                        is CallResult.Failed -> CatalogueResult.Failed(result.reason)
                    }
                },
                onFailure = { CatalogueResult.Failed(describe(it)) },
            )
        }

    suspend fun fetchSetlists(serverUrl: String, token: String): SetlistsResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = open(serverUrl, "/v1/setlists", token, method = "GET")
                readResult(connection) { text ->
                    json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SetlistSummary.serializer()), text)
                }
            }.fold(
                onSuccess = { result ->
                    when (result) {
                        is CallResult.Ok -> SetlistsResult.Ok(result.value)
                        is CallResult.Failed -> SetlistsResult.Failed(result.reason)
                    }
                },
                onFailure = { SetlistsResult.Failed(describe(it)) },
            )
        }

    /** The `.bcset` file's exact bytes, as text - the server never re-serialises it. */
    suspend fun fetchSetlistBody(serverUrl: String, token: String, id: String): SetlistBodyResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = open(serverUrl, "/v1/setlists/${id.encodeSegment()}", token, method = "GET")
                readTextResult(connection)
            }.fold(
                onSuccess = { result ->
                    when (result) {
                        is CallResult.Ok -> SetlistBodyResult.Ok(result.value)
                        is CallResult.Failed -> SetlistBodyResult.Failed(result.reason)
                    }
                },
                onFailure = { SetlistBodyResult.Failed(describe(it)) },
            )
        }

    /** Streams a chart's bytes straight to [destination], the same shape as [org.bandcharts.app.update.Updater.download]. */
    suspend fun downloadChart(
        serverUrl: String,
        token: String,
        contentHash: String,
        destination: File,
    ): DownloadResult = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open(serverUrl, "/v1/charts/${contentHash.encodeSegment()}", token, method = "GET")
            try {
                val code = connection.responseCode
                if (code !in 200..299) error(describeHttp(code, apiError(connection)))
                destination.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            } finally {
                connection.disconnect()
            }
            destination
        }.fold(
            onSuccess = { DownloadResult.Ok(it) },
            onFailure = {
                destination.delete()
                DownloadResult.Failed(describe(it))
            },
        )
    }

    /**
     * Publishes a chart, streaming [openStream]'s bytes straight to the
     * server rather than holding them in memory - a scanned songbook is
     * exactly the file this app should not be reading whole into a `ByteArray`.
     *
     * Only succeeds when this device was paired with publish rights - see
     * `org.chartserve.Auth.canPublish` on the server - or holds the admin
     * token, which this app never does.
     */
    suspend fun uploadChart(
        serverUrl: String,
        token: String,
        contentHash: String,
        meta: UploadMeta,
        contentLength: Long,
        openStream: () -> InputStream,
    ): UploadResult = withContext(Dispatchers.IO) {
        runCatching {
            val query = buildString {
                append("displayName=").append(encodeQuery(meta.displayName))
                meta.title?.let { append("&title=").append(encodeQuery(it)) }
                meta.artist?.let { append("&artist=").append(encodeQuery(it)) }
                meta.kind?.let { append("&kind=").append(encodeQuery(it)) }
                meta.keyText?.let { append("&keyText=").append(encodeQuery(it)) }
                meta.part?.let { append("&part=").append(encodeQuery(it)) }
                meta.workTitle?.let { append("&workTitle=").append(encodeQuery(it)) }
            }
            val connection = open(
                serverUrl,
                "/v1/charts/${contentHash.encodeSegment()}?$query",
                token,
                method = "PUT",
            )
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            if (contentLength > 0) {
                connection.setFixedLengthStreamingMode(contentLength)
            } else {
                connection.setChunkedStreamingMode(64 * 1024)
            }
            openStream().use { input ->
                connection.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
            }
            val code = connection.responseCode
            val text = readTextResult(connection)
            code to text
        }.fold(
            onSuccess = { (code, text) ->
                when (text) {
                    is CallResult.Failed -> UploadResult.Failed(text.reason)
                    is CallResult.Ok -> runCatching {
                        json.decodeFromString(ChartRecord.serializer(), text.value)
                    }.fold(
                        onSuccess = { UploadResult.Ok(it, created = code == 201) },
                        onFailure = {
                            UploadResult.Failed("ChartServe answered with something this app could not read.")
                        },
                    )
                }
            },
            onFailure = { UploadResult.Failed(describe(it)) },
        )
    }

    // ---------------------------------------------------------------- plumbing

    private sealed interface CallResult<T> {
        data class Ok<T>(val value: T) : CallResult<T>
        data class Failed<T>(val reason: String) : CallResult<T>
    }

    private fun <T> readResult(connection: HttpURLConnection, decode: (String) -> T): CallResult<T> {
        val text = readTextResult(connection)
        return when (text) {
            is CallResult.Failed -> CallResult.Failed(text.reason)
            is CallResult.Ok -> runCatching { decode(text.value) }
                .fold(
                    onSuccess = { CallResult.Ok(it) },
                    onFailure = { CallResult.Failed("ChartServe answered with something this app could not read.") },
                )
        }
    }

    private fun readTextResult(connection: HttpURLConnection): CallResult<String> = try {
        val code = connection.responseCode
        if (code !in 200..299) {
            CallResult.Failed(describeHttp(code, apiError(connection)))
        } else {
            val text = connection.inputStream.use { String(it.readBytesUpTo(MAX_JSON_BYTES), Charsets.UTF_8) }
            CallResult.Ok(text)
        }
    } finally {
        connection.disconnect()
    }

    /** The `{"error":..., "detail":...}` body ChartServe sends back on a failure, if it parses. */
    private fun apiError(connection: HttpURLConnection): String? = runCatching {
        val stream = connection.errorStream ?: return@runCatching null
        val text = stream.use { String(it.readBytesUpTo(64 * 1024), Charsets.UTF_8) }
        json.decodeFromString(org.bandcharts.chartserve.ApiError.serializer(), text).detail
    }.getOrNull()

    private fun open(serverUrl: String, path: String, token: String?, method: String): HttpURLConnection {
        val base = serverUrl.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "That doesn't look like a server address - it should start with http:// or https://"
        }
        val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        return connection
    }

    private fun String.encodeSegment(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    /** `+` for space is the query-string convention, and Ktor decodes it that way. */
    private fun encodeQuery(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /**
     * Written out rather than using `readNBytes`, which is a Java 9 API and does
     * not reach Android until API 33 - on a minSdk 26 build that is a crash on
     * most phones in the field rather than a compile error.
     */
    private fun InputStream.readBytesUpTo(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (total < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun describeHttp(code: Int, detail: String?): String = when (code) {
        401 -> "This device isn't paired with that server yet."
        403 -> detail ?: "That wasn't allowed."
        404 -> "Not found on that server."
        413 -> detail ?: "That was too large for the server to accept."
        in 500..599 -> "The server returned an error ($code). Try again shortly."
        else -> detail ?: "The server answered with $code."
    }

    private fun describe(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "Could not reach that server. Check the address and the network."
        is java.net.ConnectException -> "Could not connect to that server. Check the address and that it's running."
        is java.net.SocketTimeoutException -> "That server took too long to answer."
        is javax.net.ssl.SSLException -> "The secure connection to that server failed."
        is IllegalArgumentException -> error.message ?: "That address isn't valid."
        else -> error.message ?: "Something went wrong talking to that server."
    }
}
