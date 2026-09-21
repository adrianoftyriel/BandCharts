package org.droidmusic.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The names here are the ones a band actually writes on a chart, which is the
 * only reason this class has any behaviour worth testing. Recognising the word
 * "bass" is trivial; recognising "EGtr", "Electric 2" and
 * `Wonderwall - Drums.pdf` without also deciding that "Brass in Pocket" is a
 * bass part is the job.
 */
class PartTest {

    // ---- reading a name ----------------------------------------------------

    @Test
    fun `recognises the plain names`() {
        assertEquals(PartKind.BASS, Part.of("Bass")?.kind)
        assertEquals(PartKind.DRUMS, Part.of("Drums")?.kind)
        assertEquals(PartKind.KEYS, Part.of("Keys")?.kind)
        assertEquals(PartKind.VOCALS, Part.of("Vocals")?.kind)
        assertEquals(PartKind.ELECTRIC, Part.of("Electric")?.kind)
        assertEquals(PartKind.ACOUSTIC, Part.of("Acoustic")?.kind)
    }

    @Test
    fun `ignores case punctuation and spacing`() {
        assertEquals(PartKind.ELECTRIC, Part.of("  ELECTRIC   GUITAR ")?.kind)
        assertEquals(PartKind.DRUMS, Part.of("drum-kit")?.kind)
        assertEquals(PartKind.KEYS, Part.of("Piano")?.kind)
    }

    @Test
    fun `recognises the short forms a chart is actually labelled with`() {
        assertEquals(PartKind.ELECTRIC, Part.of("EGtr")?.kind)
        assertEquals(PartKind.ACOUSTIC, Part.of("AGtr")?.kind)
        assertEquals(PartKind.KEYS, Part.of("Pno")?.kind)
        assertEquals(PartKind.DRUMS, Part.of("Perc")?.kind)
    }

    @Test
    fun `a recognised name on its own carries no label`() {
        val part = Part.of("Bass")
        assertEquals(PartKind.BASS, part?.kind)
        assertNull("the kind already says Bass; a label would be noise", part?.label)
        assertEquals("Bass", part?.displayLabel)
    }

    /** A band with two guitarists needs both charts to still match a guitarist. */
    @Test
    fun `a numbered part keeps its name and its kind`() {
        val part = Part.of("Electric 2")
        assertEquals(PartKind.ELECTRIC, part?.kind)
        assertEquals("Electric 2", part?.label)
        assertEquals("Electric 2", part?.displayLabel)
    }

    @Test
    fun `an unknown part keeps its own name`() {
        val part = Part.of("Pedal Steel")
        assertEquals(PartKind.OTHER, part?.kind)
        assertEquals("Pedal Steel", part?.displayLabel)
    }

    @Test
    fun `blank names are not parts`() {
        assertNull(Part.of(""))
        assertNull(Part.of("   "))
        assertNull(Part.of("!!!"))
    }

    /**
     * The documented guess: bare "lead" is the chord chart, "lead guitar" is an
     * electric. Held by a test because it is a decision rather than a fact, and
     * the next person to change it should have to change this too.
     */
    @Test
    fun `lead is the chord chart and lead guitar is not`() {
        assertEquals(PartKind.LEAD_SHEET, Part.of("Lead")?.kind)
        assertEquals(PartKind.LEAD_SHEET, Part.of("Lead Sheet")?.kind)
        assertEquals(PartKind.ELECTRIC, Part.of("Lead Guitar")?.kind)
    }

    /** The longest name wins, so a label never restates its own kind. */
    @Test
    fun `bass guitar is matched whole rather than as bass`() {
        val part = Part.of("Bass Guitar")
        assertEquals(PartKind.BASS, part?.kind)
        assertNull(part?.label)
    }

    @Test
    fun `a part name is matched as words rather than as letters`() {
        assertEquals(PartKind.OTHER, Part.of("Monkeys")?.kind)
        assertEquals(PartKind.OTHER, Part.of("Brass")?.kind)
    }

    // ---- what a chart declares ---------------------------------------------

    @Test
    fun `reads the part a chart declares`() {
        val meta = SongMeta(extra = mapOf("x_part" to listOf("Bass")))
        assertEquals(PartKind.BASS, Part.declaredIn(meta)?.kind)
    }

    @Test
    fun `reads part before instrument`() {
        val meta = SongMeta(
            extra = mapOf(
                "instrument" to listOf("Keys"),
                "part" to listOf("Drums"),
            ),
        )
        assertEquals(
            "`part` is unambiguous and `instrument` is not, so `part` is believed",
            PartKind.DRUMS,
            Part.declaredIn(meta)?.kind,
        )
    }

    @Test
    fun `a chart that declares nothing has no part`() {
        assertNull(Part.declaredIn(SongMeta()))
        assertNull(Part.declaredIn(SongMeta(title = "Wonderwall", artist = "Oasis")))
    }

    // ---- what a file name gives away ---------------------------------------

    @Test
    fun `reads a part off a file name`() {
        assertEquals(PartKind.BASS, Part.fromFileName("Wonderwall - Bass.pdf")?.kind)
        assertEquals(PartKind.DRUMS, Part.fromFileName("Wonderwall (Drums).pdf")?.kind)
        assertEquals(PartKind.KEYS, Part.fromFileName("Wonderwall_keys.cho")?.kind)
        assertEquals(PartKind.ELECTRIC, Part.fromFileName("Wonderwall [Electric].pdf")?.kind)
    }

    /**
     * The restriction the whole heuristic rests on. Without it every chart in
     * the library acquires a part named after its own title.
     */
    @Test
    fun `an ordinary file name has no part`() {
        assertNull(Part.fromFileName("Wonderwall.pdf"))
        assertNull(Part.fromFileName("Amazing Grace.cho"))
        assertNull(Part.fromFileName("scan-2024-11-03.pdf"))
        assertNull(Part.fromFileName("Oasis - Wonderwall.pdf"))
        assertNull(Part.fromFileName(""))
    }

    @Test
    fun `a part in a file name is kept with its number`() {
        val part = Part.fromFileName("Wonderwall - Electric 2.pdf")
        assertEquals(PartKind.ELECTRIC, part?.kind)
        assertEquals("Electric 2", part?.label)
    }

    // ---- display -----------------------------------------------------------

    @Test
    fun `an underscored kind reads as words`() {
        assertEquals("Lead Sheet", PartKind.LEAD_SHEET.displayName)
        assertEquals("Lead Sheet", Part.LEAD_SHEET.displayLabel)
    }

    @Test
    fun `reports the song title left once the part is taken off`() {
        assertEquals("Wonderwall", Part.inFileName("Wonderwall - Bass.pdf")?.title)
        assertEquals("Wonderwall", Part.inFileName("Wonderwall (Drums).pdf")?.title)
        assertEquals("Amazing Grace", Part.inFileName("Amazing Grace_keys.cho")?.title)
    }

    @Test
    fun `a name that is nothing but a part names no song`() {
        assertNull("there would be no song left to call it", Part.inFileName("Bass.pdf"))
    }
}
