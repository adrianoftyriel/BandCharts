package org.bandcharts.library

import kotlinx.serialization.Serializable
import org.bandcharts.music.Key
import org.bandcharts.music.Part
import org.bandcharts.music.PartKind

/**
 * Where a song's file physically lives.
 *
 * The distinction the app cares about is not "which cloud provider" but "can I
 * open this without a network". A managed file has been copied into the app's
 * own storage and will open on a stage with no signal; an external one is a
 * pointer into someone else's document provider and may not.
 */
@Serializable
enum class SourceKind {
    /** Copied into the app's storage. Always available. */
    MANAGED,

    /**
     * A document tree the user granted through the system file picker. This is
     * how every cloud provider is reached - see [SourceRef].
     */
    EXTERNAL_TREE,

    /** A single file granted individually rather than as part of a tree. */
    EXTERNAL_FILE,
}

/**
 * A reference to a place files come from.
 *
 * [uri] is deliberately an opaque string rather than anything provider-specific.
 * Google Drive, OneDrive, Dropbox, Box and Proton Drive all publish a
 * DocumentsProvider on Android, so all of them are reachable through one
 * system-granted tree URI and none of them needs its own SDK, its own OAuth
 * client, or its own API key baked into the app. See docs/DESIGN.md for why
 * that trade is the right one.
 */
@Serializable
data class SourceRef(
    val id: String,
    val kind: SourceKind,
    val uri: String,
    /** What to call it in the UI: "Band Drive", "Downloads", "On this device". */
    val label: String,
    /** The authority of the provider, kept for display: "com.google.android.apps.docs". */
    val authority: String? = null,
    val addedAt: Long = 0L,
)

@Serializable
enum class FileKind {
    PDF,
    IMAGE,
    CHORDPRO,
    TEXT,

    /**
     * A Word document. Read as text and treated as a chart from there on - see
     * [DocxText] for what is and is not recovered from one.
     */
    DOCX,

    UNKNOWN,
}

/**
 * One chart in the library.
 *
 * [contentHash] is what makes a set list portable. Sending a set list to another
 * device sends a list of hashes and titles, and the receiving device matches
 * them against its own library; without a content hash the only thing to match
 * on is a file path, which is meaningless on a different phone.
 */
@Serializable
data class SongRef(
    val id: String,
    val sourceId: String,
    /** Provider URI for the document itself. */
    val uri: String,
    val displayName: String,
    val kind: FileKind,
    val sizeBytes: Long = 0L,
    val modifiedAt: Long = 0L,
    val contentHash: String? = null,

    // Filled in by the indexer for text charts, left null for PDFs and images.
    val title: String? = null,
    val artist: String? = null,
    val keyText: String? = null,
    val pageCount: Int = 0,
    val tags: List<String> = emptyList(),
    /** Set by the user; overrides anything detected. */
    val userKeyText: String? = null,
    val favourite: Boolean = false,

    /**
     * What the user renamed this chart to, if they did.
     *
     * A rename changes what BandCharts calls the chart and nothing else. The file
     * is not touched and its own `{title:}` is not rewritten, which means the
     * name here can differ from the name every other app shows - a real cost,
     * and the one worth paying: the alternative is writing to a file in somebody
     * else's synced folder, on a grant this app does not even hold.
     */
    val userTitle: String? = null,

    /**
     * Removed from the library without being deleted.
     *
     * The chart stays in the index rather than being dropped from it, because
     * dropping it would only work until the next rescan found the file again and
     * put it straight back. So it is remembered, and remembered as hidden.
     */
    val hidden: Boolean = false,

    /**
     * The key the band plays this in, as a number of semitones from the key the
     * chart is written in. Zero means "as written".
     *
     * Remembered on the chart rather than applied once, because a song's key is
     * a property of the singer, not of the evening. A set list entry carries its
     * own and wins where it has one - a running order is a decision about one
     * night - but a chart opened straight out of the library comes up in the key
     * it is actually played in.
     */
    val userTransposeSemitones: Int = 0,

    /**
     * The capo position the user chose for this chart.
     *
     * Deliberately not called `capo`. A ChordPro file can declare `{capo: 2}` of
     * its own, which lives on [org.bandcharts.music.SongMeta] and means "this
     * chart was written for a capo" - a statement about the chart. This one is
     * the player's choice about how to finger it, and confusing the two would
     * transpose somebody's chart by five frets they never asked for.
     */
    val userCapo: Int = 0,

    /**
     * The work this chart is one part of, or null when it stands alone.
     *
     * Null is not a degraded state. Every chart in every library that predates
     * parts has a null here and is a work of one part, which is what lets this
     * whole feature arrive without migrating anybody's library - see [Work].
     *
     * Preserved across a rescan, because grouping is a decision rather than
     * something read off the disk, and a rescan that quietly ungrouped a band's
     * parts would look exactly like a successful rescan.
     */
    val workId: String? = null,

    /**
     * Which player's chart this is, as detected from the file.
     *
     * Detected, so it is worked out again on every rescan - which is the point.
     * A chart whose `{x_part:}` is corrected, or a file renamed from
     * `Wonderwall.pdf` to `Wonderwall - Bass.pdf`, should say something
     * different afterwards. This is the same split as [title] against
     * [userTitle], and it exists for the same reason.
     */
    val part: Part? = null,

    /**
     * The part the user said this is, overriding whatever was detected.
     *
     * Set by hand when the detection is wrong or when there was nothing to
     * detect, and preserved across a rescan for the same reason [userTitle] is.
     */
    val userPart: Part? = null,
) {
    val key: Key? get() = (userKeyText ?: keyText)?.let { Key.parse(it) }

    /**
     * The key this chart will actually open in, which is what the library should
     * show. Null when nothing knows what key it is in.
     */
    val soundingKey: Key? get() = key?.let { written ->
        if (userTransposeSemitones == 0) written else written.transposedTo(userTransposeSemitones)
    }

    /** Whether the user has asked for this chart in anything but its written key. */
    val isTransposed: Boolean get() = userTransposeSemitones != 0 || userCapo != 0

    /** The part this chart is: what the user said, else what was detected. */
    val bestPart: Part? get() = userPart ?: part

    /**
     * The part this chart counts as when choosing between parts.
     *
     * A chart with no part at all is a lead sheet rather than an unknown, which
     * is what makes a library that has never heard of parts behave exactly as
     * it always did: every chart in it is the part everybody reads.
     */
    val partKind: PartKind get() = bestPart?.kind ?: PartKind.LEAD_SHEET

    /**
     * What the *song* is called, as opposed to what this chart is called.
     *
     * The two differ exactly when the part is written into the file name:
     * `Wonderwall - Bass.pdf` is a chart called "Wonderwall - Bass" and a song
     * called "Wonderwall". Grouping needs the second, because the whole point
     * is that it is the same for every part of the song.
     *
     * A declared `{title:}` is believed ahead of the file name, since a chart
     * that says what song it is has already answered this.
     */
    val workTitle: String get() = userTitle?.takeIf { it.isNotBlank() }
        ?: title?.takeIf { it.isNotBlank() }
        ?: Part.inFileName(displayName)?.title
        ?: displayName.substringBeforeLast('.')

    /**
     * What to call this chart: the user's name for it, then the one the file
     * declared, then the filename.
     */
    val bestTitle: String get() = userTitle?.takeIf { it.isNotBlank() }
        ?: title?.takeIf { it.isNotBlank() }
        ?: displayName.substringBeforeLast('.')

    /** The name the file itself gives, ignoring any rename - shown when renaming. */
    val detectedTitle: String get() = title?.takeIf { it.isNotBlank() }
        ?: displayName.substringBeforeLast('.')

    /**
     * Applies a rename, or takes one away.
     *
     * A blank name clears the override, and so does a name that matches what the
     * file already says it is called. That second case is the one worth having a
     * rule for: without it, renaming a chart to the title it already has would
     * store a redundant override that then quietly outlived every later
     * correction to the file itself, so fixing a typo in a chart's `{title:}`
     * would appear to do nothing.
     */
    /** Records the key and capo this chart is played in. */
    fun withTranspose(semitones: Int, capo: Int): SongRef = copy(
        userTransposeSemitones = Key.foldSemitones(semitones),
        userCapo = capo.coerceIn(0, MAX_CAPO),
    )

    fun withRename(name: String): SongRef {
        val wanted = name.trim()
        return copy(userTitle = wanted.takeIf { it.isNotEmpty() && it != detectedTitle })
    }

    /**
     * Whether the chart is text the app can rewrite.
     *
     * A Word document counts: what comes out of it is characters, and once it is
     * characters it goes through the same parser, the same key detection and the
     * same transposer as a `.txt`. A PDF does not, because there is nothing in it
     * to rewrite.
     */
    val isTransposable: Boolean get() =
        kind == FileKind.CHORDPRO || kind == FileKind.TEXT || kind == FileKind.DOCX

    companion object {
        /** Frets a capo is offered on. Past this it is not a capo, it is a different guitar. */
        const val MAX_CAPO = 11

        fun kindOf(displayName: String, mimeType: String? = null): FileKind {
            val ext = displayName.substringAfterLast('.', "").lowercase()
            return when {
                ext == "pdf" || mimeType == "application/pdf" -> FileKind.PDF
                ext in IMAGE_EXTENSIONS || mimeType?.startsWith("image/") == true -> FileKind.IMAGE
                ext in CHORDPRO_EXTENSIONS -> FileKind.CHORDPRO
                ext in DOCUMENT_EXTENSIONS || mimeType == DOCX_MIME_TYPE -> FileKind.DOCX
                ext in TEXT_EXTENSIONS || mimeType?.startsWith("text/") == true -> FileKind.TEXT
                else -> FileKind.UNKNOWN
            }
        }

        /**
         * Whether the start of a file looks like text rather than a binary blob.
         *
         * Needed because a chart's extension is not a reliable thing to ask.
         * ChordPro has six extensions in common use and no registered MIME type
         * at all, so a provider hands one over as `application/octet-stream` -
         * the same answer it gives for a firmware image. Every editor that
         * writes them picks its own favourite, and somebody's own convention
         * (`.song`, `.cp`, no extension at all) is not wrong, it is just not on
         * a list this app happened to write down.
         *
         * So when the name says nothing, the content is asked. A NUL byte is
         * the giveaway - text does not contain them and nearly every binary
         * format does within its first few bytes - with a small tolerance for
         * stray control characters, because a chart exported from a word
         * processor sometimes carries one.
         */
        fun looksLikeText(bytes: ByteArray, length: Int = bytes.size): Boolean {
            val end = length.coerceIn(0, bytes.size)
            if (end == 0) return false
            var control = 0
            for (i in 0 until end) {
                val byte = bytes[i].toInt() and 0xFF
                if (byte == 0) return false
                // Tab, newline, carriage return and form feed are text.
                val printable = byte >= 0x20 || byte == 0x09 || byte == 0x0A ||
                    byte == 0x0D || byte == 0x0C
                if (!printable) control++
            }
            return control * 100 < end
        }

        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
        val CHORDPRO_EXTENSIONS = setOf("cho", "chopro", "chord", "chordpro", "crd", "pro")
        val TEXT_EXTENSIONS = setOf("txt", "text", "tab", "md")

        /**
         * Word documents. `.doc` is deliberately absent: the old binary format
         * is not a zip and not XML, and claiming to open one only to fail on the
         * stand is worse than not offering it.
         */
        val DOCUMENT_EXTENSIONS = setOf("docx")

        const val DOCX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        /** Everything the viewer can open, for filtering a document picker. */
        val ALL_EXTENSIONS =
            IMAGE_EXTENSIONS + CHORDPRO_EXTENSIONS + TEXT_EXTENSIONS + DOCUMENT_EXTENSIONS + "pdf"
    }
}

/** The whole library index, as persisted. */
@Serializable
data class LibraryIndex(
    val sources: List<SourceRef> = emptyList(),
    val songs: List<SongRef> = emptyList(),
    /**
     * The songs whose parts are grouped. Empty in every library that has not
     * grouped anything, which decodes correctly from every index ever written.
     */
    val works: List<Work> = emptyList(),
    val updatedAt: Long = 0L,
) {
    fun songsFrom(sourceId: String): List<SongRef> = songs.filter { it.sourceId == sourceId }

    /**
     * Looks up a chart by id, ignoring anything removed from the library.
     *
     * Removed means removed. The library list is not the only way to reach a
     * chart - a set list entry and a follower in a band session both arrive by
     * id or by hash - and a chart that cannot be seen or found should not be
     * openable through a side door either. What those callers get instead is the
     * same "missing" they already get for a folder that has been taken away.
     */
    fun findById(id: String): SongRef? = visible.firstOrNull { it.id == id }

    /**
     * The charts to show. Anything the user removed from the library is still in
     * [songs] - so that a rescan does not resurrect it - and is not here.
     */
    val visible: List<SongRef> get() = songs.filterNot { it.hidden }

    val hiddenCount: Int get() = songs.count { it.hidden }

    /**
     * Replaces everything known about one source after a rescan, keeping what the
     * user set.
     *
     * This lives here rather than in the repository that calls it because it is
     * the one piece of the library's bookkeeping where being wrong is silent: a
     * rescan that quietly forgot a corrected key, a renamed chart or a removed
     * one would look exactly like a successful rescan. Silent failures belong in
     * the core, where a test can reach them without a device.
     *
     * Matching is by URI rather than by id. An id is derived from the document id
     * the provider hands out, and a provider is free to hand out a different one
     * for the same file; the URI is what the app opened last time and what it
     * will open next time.
     */
    fun withSongsFrom(sourceId: String, fresh: List<SongRef>, now: Long): LibraryIndex {
        val previous = songsFrom(sourceId).associateBy { it.uri }
        val merged = fresh.map { song ->
            val old = previous[song.uri] ?: return@map song
            song.copy(
                userKeyText = old.userKeyText,
                userTitle = old.userTitle,
                hidden = old.hidden,
                userTransposeSemitones = old.userTransposeSemitones,
                userCapo = old.userCapo,
                favourite = old.favourite,
                tags = old.tags,
                workId = old.workId,
                userPart = old.userPart,
            )
        }
        return copy(
            songs = songs.filterNot { it.sourceId == sourceId } + merged,
            updatedAt = now,
        )
    }

    /** The work a chart belongs to, or null when it stands on its own. */
    fun workOf(songId: String): Work? {
        val workId = findById(songId)?.workId ?: return null
        return works.firstOrNull { it.id == workId }
    }

    /** Every visible part of one work, in the order they should be offered. */
    fun partsOfWork(workId: String): List<SongRef> =
        Parts.ordered(visible.filter { it.workId == workId })

    /**
     * Every part of the song this chart belongs to, including this one.
     *
     * A chart in no work is its own only part, so a caller never has to ask
     * whether parts are involved before asking what they are. That is what
     * keeps the viewer and the session layer free of "if this song has parts"
     * branching, which is where this kind of feature usually rots.
     */
    fun partsOfSong(songId: String): List<SongRef> {
        val song = findById(songId) ?: return emptyList()
        val workId = song.workId ?: return listOf(song)
        return partsOfWork(workId).ifEmpty { listOf(song) }
    }

    /** The part of this song that a player with these instruments should see. */
    fun preferredPart(songId: String, preference: List<PartKind>): SongRef? =
        Parts.preferred(partsOfSong(songId), preference)

    /**
     * One row per song rather than one per chart, for a library list.
     *
     * A work collapses to whichever part this player would open, so a band that
     * has five charts of Wonderwall sees Wonderwall once - and the drummer and
     * the bass player each see their own when they tap it. Charts in no work
     * are passed through untouched, which is every chart in a library that has
     * never grouped anything.
     */
    fun representatives(preference: List<PartKind>): List<SongRef> =
        visible.groupBy { it.workId }.flatMap { (workId, parts) ->
            if (workId == null) parts else listOfNotNull(Parts.preferred(parts, preference))
        }

    /**
     * Finds this device's copy of a song that another device described by all
     * of its parts.
     *
     * A set list entry and a leader's position both carry every part's hash
     * rather than only the one the sender happens to be reading, because the
     * sender is on the drum chart and the receiver wants the bass part. Matching
     * on the sender's single hash would find the drum chart or nothing, and
     * "nothing" is a follower whose screen stays blank for that song.
     */
    fun matchAny(hashes: List<String>, title: String): SongRef? {
        for (hash in hashes) {
            visible.firstOrNull { it.contentHash == hash }?.let { return it }
        }
        return match(null, title)
    }

    /**
     * Finds the local copy of a song described by another device.
     *
     * Content hash first, because it is exact. Title second, because two bands
     * will have the same chart from different sources with different bytes -
     * different scans of the same page, or a PDF against a ChordPro of the same
     * song - and matching those is the whole point of the fallback.
     */
    fun match(hash: String?, title: String): SongRef? {
        val candidates = visible
        if (hash != null) {
            candidates.firstOrNull { it.contentHash == hash }?.let { return it }
        }
        val wanted = title.normaliseForMatching()
        return candidates.firstOrNull { it.bestTitle.normaliseForMatching() == wanted }
    }
}

/**
 * Loose title matching: case, punctuation and a leading article are all things
 * that differ between two people's copies of the same chart without meaning
 * anything.
 */
fun String.normaliseForMatching(): String =
    lowercase()
        .replace(Regex("^(the|a|an)\\s+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
