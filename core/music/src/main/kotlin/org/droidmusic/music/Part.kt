package org.droidmusic.music

import kotlinx.serialization.Serializable

/**
 * Which player's chart this is.
 *
 * **Not a list of the instruments that exist.** Like [SectionKind], this names
 * only the parts the app does something about - offers in a picker, matches a
 * player's stated preference against, falls back from - and [OTHER] carries
 * everything else as the label the chart itself gave. A band with a fiddle, a
 * pedal steel or a second tenor sax is not a band this enum has to be taught
 * about, because the name that matters to them is the one written on their own
 * chart, and that name travels in [Part.label].
 *
 * Adding a constant here is therefore a statement that the app treats that part
 * differently, not that the part has become real.
 */
@Serializable
enum class PartKind {
    /**
     * The chords-and-words chart everybody can read.
     *
     * This is what every song in a library that has never heard of parts
     * already is, which is why it is first: a chart with no part declared is a
     * lead sheet, and that has to stay true for every library in the field.
     *
     * It is also the fallback for every other part rather than a peer of them -
     * see `Parts.preferred` in the library module - because a bass player handed the lead sheet can
     * play the song, and a bass player handed the drum chart cannot.
     */
    LEAD_SHEET,

    VOCALS,
    ELECTRIC,
    ACOUSTIC,
    BASS,
    DRUMS,
    KEYS,

    /** Any part this app has no particular behaviour for. Keeps its own name. */
    OTHER,
    ;

    /** The kind's name made readable: `LEAD_SHEET` shows as "Lead Sheet". */
    val displayName: String
        get() = name.split('_').joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
}

/**
 * One player's chart within a song.
 *
 * [label] is null when the part is exactly what its [kind] is called, and set
 * when the chart said something more specific - "Electric 2", "Bari Sax". That
 * distinction is the same one [Line.SectionHeader] draws between a section with
 * no label and a section labelled after itself, and it exists for the same
 * reason: both draw the same heading, but only one of them should be written
 * back out, and conflating them means every export invents text the author
 * never typed.
 *
 * A band really does have two electric guitar charts, so a label has to be able
 * to sit alongside a recognised kind rather than forcing the part to [OTHER].
 * Otherwise "Electric 1" and "Electric 2" would both stop matching a guitarist
 * who said they play electric.
 */
@Serializable
data class Part(val kind: PartKind, val label: String? = null) {

    /** What to show on a tab, a chip or a picker row. */
    val displayLabel: String get() = label?.takeIf { it.isNotBlank() } ?: kind.displayName


    /** A part read out of a file name, and the title that was wrapped around it. */
    data class InName(val part: Part, val title: String)

    companion object {

        /** The plain lead sheet, which is what a song with no parts at all is. */
        val LEAD_SHEET = Part(PartKind.LEAD_SHEET)

        /**
         * The directives a chart may declare its part with, in the order they
         * are believed.
         *
         * `part` and `x_part` first because they are unambiguous and are what
         * this app writes. `instrument` is accepted because it is the word a
         * person reaches for and several editors already emit it, but it is
         * last: ChordPro uses `{instrument}` for the *configuration* of an
         * instrument rather than for naming a chart's part, so a file may carry
         * one meaning something else entirely.
         */
        val DIRECTIVES = listOf("part", "x_part", "instrument", "x_instrument")

        /**
         * How a part is fenced off at the end of a file name.
         *
         * Hoisted to constants rather than built inside the function because
         * these are compiled once here and once per call there, and this runs
         * over every file in a library of several hundred on every rescan.
         */
        private val TRAILING_ROUND = Regex("\\(([^()]*)\\)\\s*$")
        private val TRAILING_SQUARE = Regex("\\[([^\\[\\]]*)]\\s*$")

        /**
         * The separators a part is hung off, longest first.
         *
         * A bare space is deliberately absent. It is the commonest separator in
         * a title and the most dangerous one here: "Amazing Grace" would offer
         * "Grace", and every chart whose last word happened to be a part name
         * would be filed as somebody's instrument.
         */
        private val SEPARATORS = listOf(" - ", " – ", " — ", "_", " -")

        /**
         * What a chart calls each part, normalised.
         *
         * Written out rather than derived from the enum names because what
         * people actually type is not the enum name: nobody writes `LEAD_SHEET`
         * on a chart, they write "Lead Sheet", "chart", "EGtr" or "Pno".
         */
        private val SYNONYMS: Map<String, PartKind> = buildMap {
            listOf("lead sheet", "leadsheet", "lead", "chart", "chords", "sheet")
                .forEach { put(it, PartKind.LEAD_SHEET) }
            listOf("vocals", "vocal", "voice", "vox", "lyrics", "words", "melody")
                .forEach { put(it, PartKind.VOCALS) }
            listOf("electric", "electric guitar", "elec guitar", "elec", "egtr", "egt", "lead guitar", "lead gtr")
                .forEach { put(it, PartKind.ELECTRIC) }
            listOf("acoustic", "acoustic guitar", "acoustic gtr", "agtr", "agt", "ac gtr")
                .forEach { put(it, PartKind.ACOUSTIC) }
            listOf("bass", "bass guitar", "bass gtr", "bgtr", "double bass", "upright bass")
                .forEach { put(it, PartKind.BASS) }
            listOf("drums", "drum", "drum kit", "drumkit", "kit", "percussion", "perc")
                .forEach { put(it, PartKind.DRUMS) }
            listOf("keys", "key", "keyboard", "keyboards", "piano", "pno", "organ", "synth", "rhodes")
                .forEach { put(it, PartKind.KEYS) }
        }

        /**
         * The part a piece of text names, or null when it names nothing.
         *
         * **"Lead" is read as a lead sheet and "lead guitar" as an electric,**
         * which is a guess and worth stating as one. Both readings are common -
         * a parts folder holding "Lead" and "Rhythm" means guitars, and a folder
         * holding "Lead" and "Bass" means the chord chart - and no rule gets
         * both right. The chord chart is chosen because it is the part every
         * other player falls back to, so being wrong about it degrades to
         * showing somebody a chart they can still read.
         *
         * A recognised name with something extra around it keeps the whole text
         * as its label, so "Electric 2" is an electric that still says which
         * one. Anything unrecognised becomes [PartKind.OTHER] wearing its own
         * name, which is what makes the set open.
         */
        fun of(text: String): Part? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null

            val normalised = normalise(trimmed)
            if (normalised.isEmpty()) return null

            SYNONYMS[normalised]?.let { return Part(it) }

            // A longer phrase built around a name the app knows: "electric 2",
            // "bass (verse only)". The longest synonym is tried first so
            // "bass guitar" is not matched as "bass" and then labelled with
            // text that says nothing the kind did not already.
            val matched = SYNONYMS.entries
                .filter { (synonym, _) -> containsWord(normalised, synonym) }
                .maxByOrNull { it.key.length }
            if (matched != null) return Part(matched.value, trimmed)

            return Part(PartKind.OTHER, trimmed)
        }

        /**
         * The part a chart declared for itself, if it declared one.
         *
         * Read from [SongMeta.extra] rather than from a field of its own,
         * because that is where every directive this app does not have a field
         * for already lands, and adding a field would mean the parser had to
         * know about parts to keep a chart that mentions one intact.
         */
        fun declaredIn(meta: SongMeta): Part? = DIRECTIVES
            .firstNotNullOfOrNull { directive -> meta.first(directive) }
            ?.let { of(it) }

        /**
         * The part a file name gives away, if it plainly gives one away.
         *
         * How a band that has never used this app already organises parts is
         * with file names: `Wonderwall - Bass.pdf`, `Wonderwall (Drums).pdf`.
         * Reading those is the difference between a feature somebody has to
         * enter by hand for four hundred songs and one that is simply already
         * true of their folder.
         *
         * **This never returns [PartKind.OTHER], and that restriction is the
         * whole reason it is safe.** [of] turns any unrecognised text into a
         * part named after itself, which is right for a directive somebody
         * typed deliberately and catastrophic here: every ordinary chart in the
         * library would acquire a part called after its own title, and the
         * library would fill with one-part works that mean nothing. So a name
         * only produces a part when it names one this app recognises.
         */
        fun fromFileName(displayName: String): Part? = inFileName(displayName)?.part

        /**
         * The part a file name gives away, together with the song title left
         * behind once it is taken off.
         *
         * The title half is what makes grouping possible at all.
         * `Wonderwall - Bass.pdf` and `Wonderwall - Drums.pdf` have nothing in
         * common as file names and everything in common once each has had its
         * own part removed, so this returns both halves rather than making
         * every caller work the second one out again.
         */
        fun inFileName(displayName: String): InName? {
            val stem = displayName.substringBeforeLast('.').trim()
            if (stem.isEmpty()) return null

            val candidates = buildList {
                // "Wonderwall (Drums)" - a trailing bracket, of either shape.
                TRAILING_ROUND.find(stem)
                    ?.let { add(it.groupValues[1] to stem.take(it.range.first).trim()) }
                TRAILING_SQUARE.find(stem)
                    ?.let { add(it.groupValues[1] to stem.take(it.range.first).trim()) }
                // "Wonderwall - Bass", "Wonderwall_bass". A bare space is not a
                // separator here: "Amazing Grace" would offer "Grace".
                for (separator in SEPARATORS) {
                    val index = stem.lastIndexOf(separator)
                    if (index > 0) {
                        add(stem.substring(index + separator.length) to stem.take(index).trim())
                    }
                }
            }

            return candidates
                .asSequence()
                .mapNotNull { (text, rest) ->
                    if (rest.isEmpty()) null else of(text)?.let { InName(it, rest) }
                }
                .firstOrNull { it.part.kind != PartKind.OTHER }
        }

        /** Case, punctuation and runs of space are all noise when matching a name. */
        private fun normalise(text: String): String = text
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

        /**
         * Whether [haystack] contains [needle] as whole words.
         *
         * Whole words rather than `contains`, so "keys" is not found inside
         * "monkeys" and a song called "Brass in Pocket" does not become a bass
         * part.
         *
         * Scanned rather than matched with a regex built around the needle.
         * Both inputs have been through [normalise], so the only separator that
         * can occur is a single space and an index walk is exact - and building
         * a pattern here would put an interpolated brace inside a `Regex`,
         * which `AndroidRegexTest` refuses on sight for reasons worth keeping.
         */
        private fun containsWord(haystack: String, needle: String): Boolean {
            var from = 0
            while (from <= haystack.length - needle.length) {
                val at = haystack.indexOf(needle, from)
                if (at < 0) return false
                val startsWord = at == 0 || haystack[at - 1] == ' '
                val endsWord = at + needle.length == haystack.length ||
                    haystack[at + needle.length] == ' '
                if (startsWord && endsWord) return true
                from = at + 1
            }
            return false
        }
    }
}
