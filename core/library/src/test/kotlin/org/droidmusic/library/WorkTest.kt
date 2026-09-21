package org.droidmusic.library

import org.droidmusic.music.Part
import org.droidmusic.music.PartKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping is the half of multi-part songs that can go wrong silently.
 *
 * A wrong part shown to a player is noticed immediately - it is the wrong
 * chart, on the stand, in front of them. A wrong *grouping* hides one chart
 * behind another, and is noticed at the gig where somebody goes looking for a
 * song that is no longer where they left it. So most of what is tested here is
 * the refusals: the cases where two charts look like one song and must be left
 * alone.
 */
class WorkTest {

    private fun song(
        id: String,
        name: String,
        part: Part? = null,
        title: String? = null,
        artist: String? = null,
        workId: String? = null,
        hidden: Boolean = false,
        hash: String? = null,
    ) = SongRef(
        id = id,
        sourceId = "source",
        uri = "content://songs/$id",
        displayName = name,
        kind = FileKind.PDF,
        title = title,
        artist = artist,
        part = part,
        workId = workId,
        hidden = hidden,
        contentHash = hash,
    )

    private fun ids(): () -> String {
        var next = 0
        return { "work-${++next}" }
    }

    // ---- ordering ----------------------------------------------------------

    @Test
    fun `the lead sheet is offered first`() {
        val ordered = Parts.ordered(
            listOf(
                song("d", "Song - Drums.pdf", Part(PartKind.DRUMS)),
                song("b", "Song - Bass.pdf", Part(PartKind.BASS)),
                song("l", "Song.pdf"),
            ),
        )
        assertEquals(listOf("l", "b", "d"), ordered.map { it.id })
    }

    @Test
    fun `numbered parts of one instrument keep their order`() {
        val ordered = Parts.ordered(
            listOf(
                song("e2", "Song - Electric 2.pdf", Part(PartKind.ELECTRIC, "Electric 2")),
                song("e1", "Song - Electric 1.pdf", Part(PartKind.ELECTRIC, "Electric 1")),
            ),
        )
        assertEquals(listOf("e1", "e2"), ordered.map { it.id })
    }

    // ---- choosing a part ---------------------------------------------------

    @Test
    fun `a player gets their own instrument`() {
        val parts = listOf(
            song("l", "Song.pdf"),
            song("b", "Song - Bass.pdf", Part(PartKind.BASS)),
            song("d", "Song - Drums.pdf", Part(PartKind.DRUMS)),
        )
        assertEquals("b", Parts.preferred(parts, listOf(PartKind.BASS))?.id)
        assertEquals("d", Parts.preferred(parts, listOf(PartKind.DRUMS))?.id)
    }

    @Test
    fun `preference is taken in the player's own order`() {
        val parts = listOf(
            song("k", "Song - Keys.pdf", Part(PartKind.KEYS)),
            song("b", "Song - Bass.pdf", Part(PartKind.BASS)),
        )
        assertEquals("b", Parts.preferred(parts, listOf(PartKind.BASS, PartKind.KEYS))?.id)
        assertEquals("k", Parts.preferred(parts, listOf(PartKind.KEYS, PartKind.BASS))?.id)
    }

    @Test
    fun `a second choice is used when the first is not there`() {
        val parts = listOf(song("k", "Song - Keys.pdf", Part(PartKind.KEYS)))
        assertEquals("k", Parts.preferred(parts, listOf(PartKind.BASS, PartKind.KEYS))?.id)
    }

    @Test
    fun `a player with no part of their own falls back to the lead sheet`() {
        val parts = listOf(
            song("l", "Song.pdf"),
            song("d", "Song - Drums.pdf", Part(PartKind.DRUMS)),
        )
        assertEquals("l", Parts.preferred(parts, listOf(PartKind.BASS))?.id)
    }

    @Test
    fun `with no lead sheet either, something readable is still chosen`() {
        val parts = listOf(song("d", "Song - Drums.pdf", Part(PartKind.DRUMS)))
        assertEquals(
            "a chart they can read the arrangement off beats a blank screen",
            "d",
            Parts.preferred(parts, listOf(PartKind.BASS))?.id,
        )
    }

    @Test
    fun `a work with no parts has nothing to choose`() {
        assertNull(Parts.preferred(emptyList(), listOf(PartKind.BASS)))
    }

    // ---- inferring works ---------------------------------------------------

    @Test
    fun `groups charts that name their own parts`() {
        val songs = listOf(
            song("b", "Wonderwall - Bass.pdf", Part(PartKind.BASS)),
            song("d", "Wonderwall - Drums.pdf", Part(PartKind.DRUMS)),
        )
        val grouping = Works.infer(songs, now = 10L, newId = ids())

        assertEquals(1, grouping.works.size)
        assertEquals("Wonderwall", grouping.works.first().title)
        assertEquals(setOf("work-1"), grouping.songs.mapNotNull { it.workId }.toSet())
    }

    @Test
    fun `the plain chart joins the work as its lead sheet`() {
        val songs = listOf(
            song("l", "Wonderwall.pdf"),
            song("b", "Wonderwall - Bass.pdf", Part(PartKind.BASS)),
            song("d", "Wonderwall - Drums.pdf", Part(PartKind.DRUMS)),
        )
        val grouping = Works.infer(songs, now = 10L, newId = ids())

        assertEquals(1, grouping.works.size)
        assertTrue("every chart of the song belongs to it", grouping.songs.all { it.workId == "work-1" })
        assertEquals(PartKind.LEAD_SHEET, grouping.songs.first { it.id == "l" }.partKind)
    }

    /** docs/DESIGN.md §17: two transcriptions of one song are two rows. */
    @Test
    fun `two copies of one song with no parts are left alone`() {
        val songs = listOf(
            song("a", "Wonderwall.pdf"),
            song("b", "Wonderwall.pdf"),
        )
        val grouping = Works.infer(songs, now = 10L, newId = ids())

        assertTrue(grouping.isEmpty)
        assertTrue(grouping.songs.all { it.workId == null })
    }

    @Test
    fun `two charts of the same part are not a work`() {
        val songs = listOf(
            song("a", "Wonderwall - Bass.pdf", Part(PartKind.BASS)),
            song("b", "Wonderwall - Bass.pdf", Part(PartKind.BASS)),
        )
        assertTrue(
            "two people's bass transcriptions are two charts, not a song with two parts",
            Works.infer(songs, now = 10L, newId = ids()).isEmpty,
        )
    }

    @Test
    fun `two songs of one name by different people stay apart`() {
        val songs = listOf(
            song("a", "Alone - Bass.pdf", Part(PartKind.BASS), artist = "Heart"),
            song("b", "Alone - Drums.pdf", Part(PartKind.DRUMS), artist = "Alan Walker"),
        )
        assertTrue(Works.infer(songs, now = 10L, newId = ids()).isEmpty)
    }

    @Test
    fun `a grouping made by hand survives a rescan`() {
        val songs = listOf(
            song("b", "Wonderwall - Bass.pdf", Part(PartKind.BASS), workId = "mine"),
            song("d", "Wonderwall - Drums.pdf", Part(PartKind.DRUMS), workId = "mine"),
        )
        val grouping = Works.infer(songs, now = 10L, newId = ids())

        assertTrue("nothing left to infer", grouping.isEmpty)
        assertTrue(grouping.songs.all { it.workId == "mine" })
    }

    @Test
    fun `a removed chart is not grouped`() {
        val songs = listOf(
            song("b", "Wonderwall - Bass.pdf", Part(PartKind.BASS), hidden = true),
            song("d", "Wonderwall - Drums.pdf", Part(PartKind.DRUMS)),
        )
        assertTrue(Works.infer(songs, now = 10L, newId = ids()).isEmpty)
    }

    @Test
    fun `a declared title names the work rather than a file name`() {
        val songs = listOf(
            song("b", "wonderwall-bass-final-v2.pdf", Part(PartKind.BASS), title = "Wonderwall"),
            song("d", "wonderwall-bass-final-v2.pdf", Part(PartKind.DRUMS), title = "Wonderwall"),
        )
        assertEquals("Wonderwall", Works.infer(songs, now = 10L, newId = ids()).works.single().title)
    }

    // ---- the index -------------------------------------------------------

    private fun index(songs: List<SongRef>, works: List<Work> = emptyList()) =
        LibraryIndex(songs = songs, works = works)

    @Test
    fun `a chart in no work is its own only part`() {
        val index = index(listOf(song("a", "Wonderwall.pdf")))
        assertEquals(listOf("a"), index.partsOfSong("a").map { it.id })
        assertNull(index.workOf("a"))
    }

    @Test
    fun `the index resolves a player's part from any part of the song`() {
        val index = index(
            songs = listOf(
                song("l", "Wonderwall.pdf", workId = "w"),
                song("b", "Wonderwall - Bass.pdf", Part(PartKind.BASS), workId = "w"),
                song("d", "Wonderwall - Drums.pdf", Part(PartKind.DRUMS), workId = "w"),
            ),
            works = listOf(Work(id = "w", title = "Wonderwall")),
        )

        assertEquals(listOf("l", "b", "d"), index.partsOfSong("d").map { it.id })
        assertEquals("b", index.preferredPart("d", listOf(PartKind.BASS))?.id)
        assertEquals("Wonderwall", index.workOf("b")?.title)
    }

    @Test
    fun `a library list shows one row per song`() {
        val index = index(
            songs = listOf(
                song("l", "Wonderwall.pdf", workId = "w"),
                song("b", "Wonderwall - Bass.pdf", Part(PartKind.BASS), workId = "w"),
                song("x", "Live Forever.pdf"),
            ),
            works = listOf(Work(id = "w", title = "Wonderwall")),
        )

        val rows = index.representatives(listOf(PartKind.BASS))
        assertEquals(setOf("b", "x"), rows.map { it.id }.toSet())
    }

    @Test
    fun `a song is found by any of its parts' hashes`() {
        val index = index(
            songs = listOf(
                song("l", "Wonderwall.pdf", workId = "w", hash = "hash-lead"),
                song("b", "Wonderwall - Bass.pdf", Part(PartKind.BASS), workId = "w", hash = "hash-bass"),
            ),
            works = listOf(Work(id = "w", title = "Wonderwall")),
        )

        assertEquals("b", index.matchAny(listOf("hash-drums", "hash-bass"), "Wonderwall")?.id)
        assertEquals(
            "falls back to the title when no part's bytes are shared",
            "l",
            index.matchAny(listOf("hash-nothing"), "Wonderwall")?.id,
        )
    }

    @Test
    fun `a rescan keeps the grouping and the corrected part`() {
        val before = index(
            listOf(
                song("b", "Wonderwall - Bass.pdf", Part(PartKind.BASS), workId = "w")
                    .copy(userPart = Part(PartKind.KEYS)),
            ),
        )
        val rescanned = before.withSongsFrom(
            sourceId = "source",
            fresh = listOf(song("b", "Wonderwall - Bass.pdf", Part(PartKind.BASS))),
            now = 20L,
        )

        val song = rescanned.songs.single()
        assertEquals("w", song.workId)
        assertEquals(PartKind.KEYS, song.bestPart?.kind)
    }
}
