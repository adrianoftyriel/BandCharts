package org.droidmusic.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackupTest {

    private val source = SourceRef(
        id = "managed",
        kind = SourceKind.MANAGED,
        uri = "",
        label = "On this device",
    )

    private val song = SongRef(
        id = "song-1",
        sourceId = "managed",
        uri = "file:///data/user/0/org.droidmusic.app/files/managed/song-1_Wagon Wheel.pdf",
        displayName = "Wagon Wheel.pdf",
        kind = FileKind.PDF,
        favourite = true,
    )

    private val library = LibraryIndex(sources = listOf(source), songs = listOf(song))
    private val setlists = listOf(Setlist(id = "s1", name = "Friday", archived = true))

    @Test
    fun `a backup round trips through json with everything intact`() {
        val manifest = LibraryBackupCodec.bundle(
            library = library,
            setlists = setlists,
            exportedBy = "Jim's phone",
            producer = "DroidMusic 0.1.0",
            now = 1_000L,
        )
        val decoded = LibraryBackupCodec.decode(LibraryBackupCodec.encode(manifest))
        assertNotNull(decoded)
        assertEquals(manifest, decoded)
        assertEquals(1, decoded!!.library.songs.size)
        assertTrue(decoded.library.songs.single().favourite)
        assertTrue(decoded.setlists.single().archived)
    }

    @Test
    fun `malformed input decodes to null instead of throwing`() {
        assertNull(LibraryBackupCodec.decode(""))
        assertNull(LibraryBackupCodec.decode("not json at all"))
        assertNull(LibraryBackupCodec.decode("{\"formatVersion\": 1}"))
        assertNull(LibraryBackupCodec.decode("[1,2,3]"))
    }

    @Test
    fun `unknown fields from a newer writer are tolerated`() {
        val text = """
            {"formatVersion":1,"library":{"sources":[],"songs":[]},"somethingNew":42}
        """.trimIndent()
        assertNotNull(LibraryBackupCodec.decode(text))
    }

    @Test
    fun `a newer format version is refused rather than half read`() {
        val manifest = LibraryBackupManifest(formatVersion = 99, library = library)
        assertFalse(LibraryBackupCodec.canRead(manifest))
        assertTrue(LibraryBackupCodec.canRead(LibraryBackupManifest(library = library)))
    }

    @Test
    fun `file names are dated and safe, with or without a device name`() {
        val named = LibraryBackupCodec.fileName("Jim's Phone", 1_700_000_000_000L)
        assertEquals("DroidMusic-backup-Jim-s-Phone-2023-11-14.dmlib", named)

        val unnamed = LibraryBackupCodec.fileName(null, 1_700_000_000_000L)
        assertEquals("DroidMusic-backup-2023-11-14.dmlib", unnamed)

        val blankName = LibraryBackupCodec.fileName("///", 1_700_000_000_000L)
        assertEquals("DroidMusic-backup-2023-11-14.dmlib", blankName)
    }
}
