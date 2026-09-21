package org.droidmusic.session

import org.droidmusic.library.FileKind
import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.Setlist
import org.droidmusic.library.SetlistEntry
import org.droidmusic.library.SongRef
import org.droidmusic.library.Work
import org.droidmusic.music.Part
import org.droidmusic.music.PartKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The leader is on the chord chart and the drummer wants the drum part. What a
 * [Position] has to achieve is that both of those are true at once.
 */
class RemoteSongPartsTest {

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

    private val band = LibraryIndex(
        songs = listOf(
            song("l", "Wonderwall.pdf", workId = "w", hash = "hash-lead"),
            song("b", "Wonderwall - Bass.pdf", Part(PartKind.BASS), workId = "w", hash = "hash-bass"),
            song("d", "Wonderwall - Drums.pdf", Part(PartKind.DRUMS), workId = "w", hash = "hash-drums"),
        ),
        works = listOf(Work(id = "w", title = "Wonderwall")),
    )

    /** The leader is reading the chord chart, on a device where ids mean nothing here. */
    private val leaderIsOnTheChordChart = Position(
        seq = 1,
        setlistIndex = 0,
        songId = "leaders-own-id",
        songTitle = "Wonderwall",
        contentHash = "hash-lead",
        page = 0,
        partHashes = listOf("hash-lead", "hash-bass", "hash-drums"),
    )

    @Test
    fun `every player lands on their own part`() {
        assertEquals("b", band.partFor(leaderIsOnTheChordChart, listOf(PartKind.BASS))?.id)
        assertEquals("d", band.partFor(leaderIsOnTheChordChart, listOf(PartKind.DRUMS))?.id)
        assertEquals("l", band.partFor(leaderIsOnTheChordChart, listOf(PartKind.LEAD_SHEET))?.id)
    }

    @Test
    fun `a player with no part of their own gets the chord chart`() {
        assertEquals("l", band.partFor(leaderIsOnTheChordChart, listOf(PartKind.KEYS))?.id)
    }

    /**
     * The failure this replaced: the follower held only the drum chart, the
     * leader sent the chord chart's hash, and nothing matched.
     */
    @Test
    fun `a device holding only one part still finds the song`() {
        val drummer = LibraryIndex(
            songs = listOf(song("mine", "Wonderwall - Drums.pdf", Part(PartKind.DRUMS), hash = "hash-drums")),
        )
        assertEquals("mine", drummer.songFor(leaderIsOnTheChordChart)?.id)
        assertEquals("mine", drummer.partFor(leaderIsOnTheChordChart, listOf(PartKind.DRUMS))?.id)
    }

    @Test
    fun `a position from a build that never heard of parts still resolves`() {
        val old = Position(
            seq = 1,
            setlistIndex = 0,
            songId = "leaders-own-id",
            songTitle = "Wonderwall",
            contentHash = "hash-lead",
            page = 0,
        )
        assertEquals("l", band.songFor(old)?.id)
        assertEquals("b", band.partFor(old, listOf(PartKind.BASS))?.id)
    }

    @Test
    fun `a song nobody has resolves to nothing`() {
        val unknown = leaderIsOnTheChordChart.copy(
            songTitle = "Some Other Song",
            contentHash = "hash-nothing",
            partHashes = emptyList(),
        )
        assertNull(band.partFor(unknown, listOf(PartKind.BASS)))
    }

    @Test
    fun `a song with one chart is unaffected by any of this`() {
        val solo = LibraryIndex(songs = listOf(song("x", "Live Forever.pdf", hash = "hash-x")))
        val position = Position(
            seq = 1,
            setlistIndex = 0,
            songId = null,
            songTitle = "Live Forever",
            contentHash = "hash-x",
            page = 0,
        )
        assertEquals("x", solo.partFor(position, listOf(PartKind.BASS))?.id)
    }

    // ---- asking for what is missing ----------------------------------------

    @Test
    fun `a missing part is asked for even when the song is already here`() {
        val guitarist = LibraryIndex(
            songs = listOf(song("mine", "Wonderwall.pdf", hash = "hash-lead")),
        )
        val setlist = Setlist(
            id = "s",
            name = "Friday",
            entries = listOf(
                SetlistEntry(
                    songId = "leaders",
                    title = "Wonderwall",
                    contentHash = "hash-lead",
                    partHashes = listOf("hash-lead", "hash-bass", "hash-drums"),
                ),
            ),
        )

        val wanted = ChartShare.wanted(setlist, guitarist)
        assertEquals(
            "the chord chart is here; the other two parts are not",
            setOf("hash-bass", "hash-drums"),
            wanted.mapNotNull { it.contentHash }.toSet(),
        )
    }

    @Test
    fun `nothing is asked for when every part is already here`() {
        val setlist = Setlist(
            id = "s",
            name = "Friday",
            entries = listOf(
                SetlistEntry(
                    songId = "leaders",
                    title = "Wonderwall",
                    contentHash = "hash-lead",
                    partHashes = listOf("hash-lead", "hash-bass", "hash-drums"),
                ),
            ),
        )
        assertTrue(ChartShare.wanted(setlist, band).isEmpty())
    }

    @Test
    fun `the catalogue says which part each chart is`() {
        val catalogue = ChartShare.catalogueOf(band)
        val bass = catalogue.single { it.contentHash == "hash-bass" }
        val lead = catalogue.single { it.contentHash == "hash-lead" }

        assertEquals(PartKind.BASS, bass.part?.kind)
        assertNull("an ordinary chart claims no part", lead.part)
    }
}
