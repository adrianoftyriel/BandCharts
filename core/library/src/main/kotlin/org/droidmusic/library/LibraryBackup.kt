package org.droidmusic.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything needed to rebuild the library and its set lists on another day,
 * or another phone.
 *
 * [library] carries every source and song, including the ones the app cannot
 * copy the bytes for - a Drive folder's [SourceRef] is metadata worth keeping
 * even though the permission grant behind it cannot travel with the file. The
 * actual chart bytes for [SourceKind.MANAGED] songs travel separately, as
 * entries in the zip this manifest is packed into; this class only ever holds
 * the index that says which bytes belong to which song.
 *
 * [formatVersion] is checked on restore for the same reason as
 * [SetlistBundle.formatVersion]: a backup is exactly the kind of file that
 * gets opened months later by whatever version of the app happens to be
 * installed then, and a silent partial restore is worse than a plain refusal.
 */
@Serializable
data class LibraryBackupManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val library: LibraryIndex,
    val setlists: List<Setlist> = emptyList(),
    val exportedBy: String? = null,
    val exportedAt: Long = 0L,
    /** App version that wrote the file, for support rather than for logic. */
    val producer: String? = null,
) {
    companion object {
        /**
         * Raised to 2 when a song gained parts.
         *
         * A backup written now carries [LibraryIndex.works] and the grouping on
         * each [SongRef]. An older build decodes with unknown keys ignored, so
         * it would restore every chart perfectly and every *grouping* not at
         * all - a library that comes back with the band's parts silently
         * scattered back into separate rows, months later, with nothing said.
         * That is the partial restore this check exists to refuse.
         *
         * The set list format deliberately did *not* move for the same feature,
         * and the difference is worth stating: a `.dmset` is handed between
         * phones at a gig, where a refusal breaks a band running two versions,
         * and an older build reading one loses nothing it ever had. A backup is
         * archival and read once, long afterwards, by whatever is installed
         * then - so here the refusal is the kind answer.
         */
        const val FORMAT_VERSION = 2

        /** The extension and MIME type a shared backup uses. */
        const val EXTENSION = "dmlib"
        const val MIME_TYPE = "application/zip"

        /** The manifest's entry name inside the backup zip. */
        const val MANIFEST_ENTRY = "manifest.json"

        /** The prefix every managed chart's bytes are stored under in the zip. */
        const val MANAGED_ENTRY_PREFIX = "managed/"
    }
}

object LibraryBackupCodec {

    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(manifest: LibraryBackupManifest): String =
        json.encodeToString(LibraryBackupManifest.serializer(), manifest)

    /**
     * Reads a backup's manifest. Returns null rather than throwing on anything
     * malformed, because this is fed by a file that may have sat in a Drive
     * folder for two years or been hand-edited, and a crash on a bad one is not
     * an acceptable failure mode.
     */
    fun decode(text: String): LibraryBackupManifest? = runCatching {
        json.decodeFromString(LibraryBackupManifest.serializer(), text)
    }.getOrNull()

    /**
     * True if this build can be trusted to restore the file without losing
     * information. A newer major format is refused rather than half-read.
     */
    fun canRead(manifest: LibraryBackupManifest): Boolean =
        manifest.formatVersion <= LibraryBackupManifest.FORMAT_VERSION

    /** Builds the manifest for a backup of everything held on this device. */
    fun bundle(
        library: LibraryIndex,
        setlists: List<Setlist>,
        exportedBy: String?,
        producer: String?,
        now: Long,
    ): LibraryBackupManifest = LibraryBackupManifest(
        library = library,
        setlists = setlists,
        exportedBy = exportedBy,
        exportedAt = now,
        producer = producer,
    )

    /** A filesystem-safe name for the exported file, dated so backups sort by age. */
    fun fileName(deviceName: String?, now: Long): String {
        // UTC, so the date in the file name does not depend on the exporting
        // device's timezone.
        val date = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val label = deviceName
            ?.replace(Regex("[^A-Za-z0-9 _-]"), " ")
            ?.trim()
            ?.replace(Regex("[\\s_-]+"), "-")
            ?.ifEmpty { null }
        val base = listOfNotNull("DroidMusic-backup", label, date.toString()).joinToString("-")
        return "$base.${LibraryBackupManifest.EXTENSION}"
    }
}
