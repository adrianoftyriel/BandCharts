package org.bandcharts.library

import org.bandcharts.music.Part
import org.bandcharts.music.PartKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A set list has to name a song in a way that resolves on a phone whose copy of
 * it is a different chart entirely - which is what a part is.
 */
class SetlistPartsTest {

    private fun song(id: String, name: String, part: Part? = null, workId: String? = null, hash: String? = null) =
        SongRef(
            id = id,
            sourceId = "source",
            uri = "content://songs/$id",
            displayName = name,
            kind = FileKind.PDF,
            part = part,
            workId = workId,
            contentHash = hash,
        )

    private val leadersLibrary = LibraryIndex(
        songs = listOf(
            song("l", "Wonderwall.pdf", workId = "w", hash = "hash-lead"),
            song("b", "Wonderwall - Bass.pdf", Part(PartKind.BASS), workId = "w", hash = "hash-bass"),
            song("d", "Wonderwall - Drums.pdf", Part(PartKind.DRUMS), workId = "w", hash = "hash-drums"),
        ),
        works = listOf(Work(id = "w", title = "Wonderwall")),
    )

    @Test
    fun `an entry carries every part of the song`() {
        val entry = SetlistCodec.entryFor(leadersLibrary.findById("l")!!, leadersLibrary)

        assertEquals("Wonderwall", entry.title)
        assertEquals(
            setOf("hash-lead", "hash-bass", "hash-drums"),
            entry.partHashes.toSet(),
        )
    }

    /** The running order is read out loud; it should not say "Wonderwall - Bass". */
    @Test
    fun `an entry built from a part is still named after the song`() {
        val entry = SetlistCodec.entryFor(leadersLibrary.findById("b")!!, leadersLibrary)
        assertEquals("Wonderwall", entry.title)
    }

    @Test
    fun `an ordinary chart still makes an ordinary entry`() {
        val library = LibraryIndex(songs = listOf(song("x", "Live Forever.pdf", hash = "hash-x")))
        val entry = SetlistCodec.entryFor(library.findById("x")!!, library)

        assertEquals("Live Forever", entry.title)
        assertEquals("hash-x", entry.contentHash)
        assertTrue("nothing to carry for a song with one chart", entry.partHashes.isEmpty())
    }

    /**
     * The case the whole field exists for: the drummer holds only the drum
     * chart, and the leader built the list off the chord chart.
     */
    @Test
    fun `a device holding only its own part still resolves the entry`() {
        val drummer = LibraryIndex(
            songs = listOf(song("mine", "Wonderwall - Drums.pdf", Part(PartKind.DRUMS), hash = "hash-drums")),
        )
        val entry = SetlistCodec.entryFor(leadersLibrary.findById("l")!!, leadersLibrary)
        val setlist = Setlist(id = "s", name = "Friday", entries = listOf(entry))

        val resolved = SetlistCodec.resolve(setlist, drummer)
        assertTrue(resolved.allPresent)
        assertEquals("mine", resolved.resolved.single().localSongId)
    }

    @Test
    fun `an entry from before parts existed resolves exactly as it did`() {
        val old = SetlistEntry(songId = "elsewhere", title = "Wonderwall", contentHash = "hash-lead")
        val setlist = Setlist(id = "s", name = "Friday", entries = listOf(old))

        val resolved = SetlistCodec.resolve(setlist, leadersLibrary)
        assertEquals("l", resolved.resolved.single().localSongId)
    }

    @Test
    fun `a set list written before parts existed still decodes`() {
        val json = """
            {
              "formatVersion": 1,
              "setlist": {
                "id": "s",
                "name": "Friday",
                "entries": [
                  { "songId": "a", "title": "Wonderwall", "contentHash": "hash-lead" }
                ]
              }
            }
        """.trimIndent()

        val bundle = SetlistCodec.decode(json)
        assertNotNull(bundle)
        assertTrue(SetlistCodec.canRead(bundle!!))
        assertTrue(bundle.setlist.entries.single().partHashes.isEmpty())
    }

    @Test
    fun `a set list naming every part is still readable by this build`() {
        val entry = SetlistCodec.entryFor(leadersLibrary.findById("l")!!, leadersLibrary)
        val bundle = SetlistCodec.bundle(
            setlist = Setlist(id = "s", name = "Friday", entries = listOf(entry)),
            exportedBy = "leader",
            producer = "test",
            now = 1L,
        )

        val round = SetlistCodec.decode(SetlistCodec.encode(bundle))
        assertNotNull(round)
        assertEquals(entry.partHashes, round!!.setlist.entries.single().partHashes)
        assertEquals(
            "an older build must still accept this file",
            1,
            round.formatVersion,
        )
    }
}

/** The backup format made the opposite call to the set list format; both are held here. */
class LibraryBackupVersionTest {

    @Test
    fun `a backup written before parts existed is still restored`() {
        val old = LibraryBackupManifest(formatVersion = 1, library = LibraryIndex())
        assertTrue(LibraryBackupCodec.canRead(old))
    }

    @Test
    fun `a backup carrying works is refused by a build that would drop them`() {
        assertEquals(
            "an older build must refuse rather than restore a library with its parts scattered",
            2,
            LibraryBackupManifest.FORMAT_VERSION,
        )
        val current = LibraryBackupManifest(library = LibraryIndex())
        assertTrue(LibraryBackupCodec.canRead(current))
        assertTrue(
            "and this build still reads its own",
            current.formatVersion == LibraryBackupManifest.FORMAT_VERSION,
        )
    }

    @Test
    fun `a backup round trips the grouping`() {
        val library = LibraryIndex(
            songs = listOf(
                SongRef(
                    id = "b",
                    sourceId = "s",
                    uri = "content://b",
                    displayName = "Wonderwall - Bass.pdf",
                    kind = FileKind.PDF,
                    part = Part(PartKind.BASS),
                    workId = "w",
                ),
            ),
            works = listOf(Work(id = "w", title = "Wonderwall")),
        )
        val manifest = LibraryBackupCodec.bundle(library, emptyList(), null, null, 1L)
        val round = LibraryBackupCodec.decode(LibraryBackupCodec.encode(manifest))

        assertEquals("Wonderwall", round?.library?.works?.single()?.title)
        assertEquals(PartKind.BASS, round?.library?.songs?.single()?.bestPart?.kind)
        assertEquals("w", round?.library?.songs?.single()?.workId)
    }
}

/**
 * TEMPORARY - see LibraryBackupManifest.LEGACY_DROIDMUSIC_EXTENSION. Delete
 * this class alongside that constant.
 */
class LegacyDroidMusicImportTest {

    @Test
    fun `a DroidMusic backup decodes exactly as a BandCharts one does`() {
        // Shaped like a real DroidMusic export: formatVersion 1, no works -
        // the manifest as it looked before the rename and before parts.
        val legacy = """
            {"formatVersion":1,"library":{"sources":[],"songs":[],"updatedAt":5},
             "setlists":[],"exportedBy":"Jim's Phone","exportedAt":5,
             "producer":"DroidMusic 0.1.0"}
        """.trimIndent()

        val manifest = LibraryBackupCodec.decode(legacy)
        assertNotNull(manifest)
        assertTrue(
            "the rename moved a Kotlin package, not a JSON field; nothing here should refuse",
            LibraryBackupCodec.canRead(manifest!!),
        )
        assertEquals("Jim's Phone", manifest.exportedBy)
    }

    @Test
    fun `the legacy extension is what DroidMusic actually wrote`() {
        assertEquals("dmlib", LibraryBackupManifest.LEGACY_DROIDMUSIC_EXTENSION)
        assertNotEquals(
            "a DroidMusic file must be recognisably not a BandCharts one by name",
            LibraryBackupManifest.EXTENSION,
            LibraryBackupManifest.LEGACY_DROIDMUSIC_EXTENSION,
        )
    }
}
