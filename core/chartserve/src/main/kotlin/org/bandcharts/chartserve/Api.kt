package org.bandcharts.chartserve

import kotlinx.serialization.Serializable

/**
 * ChartServe's own wire types, kept as a byte-for-byte match of the server's
 * `org.chartserve.Model` rather than reusing anything from [org.bandcharts.library].
 *
 * That duplication is deliberate, not an oversight. ChartServe does not know
 * what a [org.bandcharts.library.SongRef] is and never will - it stores bytes
 * under a hash and hands them back, and the only thing shared between the two
 * sides is the string in [ChartRecord.contentHash]. Reusing the library's own
 * model here would mean a change to it - a new field, a renamed one - silently
 * changing what this module sends and parses over the wire, for a server this
 * app does not control the release of.
 */
@Serializable
data class ChartRecord(
    val contentHash: String,
    val blobSha256: String,
    val sizeBytes: Long,
    val displayName: String,
    val title: String,
    val artist: String? = null,
    val kind: String? = null,
    val keyText: String? = null,
    val part: String? = null,
    val workTitle: String? = null,
    val uploadedAt: Long = 0L,
)

@Serializable
data class Catalogue(
    val updatedAt: Long,
    val count: Int,
    val charts: List<ChartRecord>,
)

@Serializable
data class SetlistSummary(
    val id: String,
    val name: String,
    val updatedAt: Long = 0L,
    val entryCount: Int = 0,
    val sizeBytes: Long = 0L,
)

@Serializable
data class PairRequest(val code: String, val deviceName: String? = null)

@Serializable
data class PairResponse(val token: String, val deviceId: String, val deviceName: String? = null)

@Serializable
data class Health(val status: String, val version: String, val charts: Int, val setlists: Int)

@Serializable
data class ApiError(val error: String, val detail: String? = null)

/**
 * Pairing codes as ChartServe mints them: `XXXX-XXXX` from an alphabet with no
 * `I`, `O`, `0` or `1` - see `org.chartserve.Auth` on the server for why those
 * four are excluded.
 *
 * This is pure formatting, not validation the server relies on: ChartServe
 * itself is the only authority on whether a code is live, and it answers a
 * wrong, used or expired code identically on purpose. What this is for is the
 * app being able to say "that doesn't look like a pairing code" the instant
 * somebody mistypes one, rather than waiting on a round trip to find out.
 */
object PairingCode {
    private val SHAPE = Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}$")

    /** Upper-cased and trimmed, which is also exactly what the server compares against. */
    fun normalise(raw: String): String = raw.trim().uppercase()

    fun looksValid(raw: String): Boolean = SHAPE.matches(normalise(raw))
}
