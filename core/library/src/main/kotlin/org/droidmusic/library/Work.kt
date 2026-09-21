package org.droidmusic.library

import kotlinx.serialization.Serializable
import org.droidmusic.music.Part
import org.droidmusic.music.PartKind

/**
 * A song the band plays, as distinct from any one player's chart of it.
 *
 * A work is the row in the library; the [SongRef]s carrying its id are the
 * parts behind that row. "Wonderwall" is one work, and the chord chart, the
 * bass part and the drum chart are three charts within it.
 *
 * **Membership is not held here.** There is no list of part ids on a work: a
 * part knows which work it belongs to, through [SongRef.workId], and that is
 * the only place it is written down. Holding it at both ends would be tidier to
 * read and is the kind of redundancy that goes wrong quietly - a rescan that
 * drops a file, a part moved between works, an import that adds one - and every
 * one of those bugs presents as a work listing a part that is not there, or a
 * part that belongs to a work which has never heard of it. One end can be
 * stale; two ends can disagree, and disagreeing is worse.
 *
 * **A chart with no work is still a song.** Everything in a library that has
 * never heard of parts has a null [SongRef.workId], and is treated as a work of
 * exactly one part. That is what lets this be added without migrating anybody's
 * library, and it has to stay true.
 */
@Serializable
data class Work(
    val id: String,
    val title: String,
    val artist: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * Choosing which part of a work a given player should be looking at.
 *
 * The rule this implements is the same one [org.droidmusic.session.Arrangement]
 * draws for the capo, and it is worth stating in the same terms: **the song is
 * the band's and the part is the player's.** Everybody is on Wonderwall, page
 * two, in B flat - that travels. Which of the five charts of it is on the glass
 * is a fact about who is holding the phone, and it never travels.
 */
object Parts {

    /**
     * The parts of a work in the order they should be offered.
     *
     * Ordered by [PartKind]'s own declaration order, which is why that order is
     * not alphabetical and should not be made so: the lead sheet comes first
     * because it is the part anybody can read, and the rest follow roughly down
     * a stage from the front. Ties fall back to the label so that "Electric 1"
     * sits above "Electric 2" rather than wherever the file system offered them.
     */
    fun ordered(parts: List<SongRef>): List<SongRef> = parts.sortedWith(
        compareBy({ it.partKind.ordinal }, { it.bestPart?.label ?: "" }, { it.bestTitle }),
    )

    /**
     * The part this player wants, given the instruments they said they play.
     *
     * [preference] is in the player's own order, so a bass player who also
     * covers keys gets the bass part when there is one and the keys part when
     * there is not.
     *
     * **The fallback is the lead sheet, and then anything at all.** A bass
     * player whose band has not transcribed a bass part is better served by the
     * chord chart than by an empty screen, and better served by the drum chart
     * than by nothing - they can at least see the arrangement. Returning null
     * for "your part is not here" would be the literal answer and the useless
     * one, and the only case that genuinely has no answer is a work with no
     * parts at all.
     *
     * Matching is on [PartKind] rather than on the whole [Part]. A guitarist
     * says they play electric; they do not say they play "Electric 2", and a
     * band that numbers its guitar charts should not thereby stop matching
     * anybody.
     */
    fun preferred(parts: List<SongRef>, preference: List<PartKind>): SongRef? {
        if (parts.isEmpty()) return null
        val ordered = ordered(parts)

        for (kind in preference) {
            ordered.firstOrNull { it.partKind == kind }?.let { return it }
        }
        ordered.firstOrNull { it.partKind == PartKind.LEAD_SHEET }?.let { return it }
        return ordered.first()
    }
}

/**
 * Working out which charts in a library are parts of the same song.
 *
 * This runs over what a scan found, and it is deliberately timid. Grouping two
 * charts that are not the same song hides one of them behind the other, and the
 * person it happens to finds out when the chart they wanted is not where they
 * left it - which is a worse failure than leaving two rows that should have
 * been one, because that one is visible and fixable by hand.
 */
object Works {

    /** What [infer] worked out: the new works, and the songs that now point at them. */
    data class Grouping(val works: List<Work>, val songs: List<SongRef>) {
        val isEmpty: Boolean get() = works.isEmpty()
    }

    /**
     * Groups charts that plainly belong together, and leaves everything else
     * alone.
     *
     * A group is formed only when **two or more charts share a title and carry
     * two or more different detected parts.** Both halves of that are load
     * bearing:
     *
     *  - *Two different parts*, not two charts, because two charts with the same
     *    title and no parts between them are the case `docs/DESIGN.md` §17 is
     *    explicit about - the bass player's transcription and the guitarist's,
     *    which differ in a repeat and must stay two rows. Collapsing those is
     *    precisely the surprise the Backstage check exists to prevent.
     *  - *Detected parts*, meaning a part the chart declared or a part written
     *    into its file name. A part invented here would group by title alone,
     *    which is the same mistake wearing a hat.
     *
     * Once a group has qualified, charts of the same title carrying **no** part
     * join it as the lead sheet. That is the common shape of a real folder -
     * `Wonderwall.pdf` beside `Wonderwall - Bass.pdf` - and the chart everyone
     * reads should not be the one left outside the work it belongs to.
     *
     * Two different songs that happen to share a title are kept apart by the
     * artist: if the charts under one title disagree about who wrote it, the
     * whole group is abandoned rather than guessed at.
     *
     * Charts that already belong to a work are not touched, so a grouping the
     * user made or corrected by hand survives every later rescan.
     *
     * [newId] is passed in rather than generated here so this stays a pure
     * function - the same library gives the same grouping - which is the same
     * reason `SetlistCodec.adopt` takes one.
     */
    fun infer(songs: List<SongRef>, now: Long, newId: () -> String): Grouping {
        val candidates = songs.filter { !it.hidden && it.workId == null }
        if (candidates.isEmpty()) return Grouping(emptyList(), songs)

        val works = mutableListOf<Work>()
        val assigned = mutableMapOf<String, String>()

        for ((_, group) in candidates.groupBy { it.workTitle.normaliseForMatching() }) {
            if (group.size < 2) continue

            // Two songs of one name by two different people are two songs.
            val artists = group.mapNotNull { it.artist?.takeIf { name -> name.isNotBlank() } }
                .map { it.normaliseForMatching() }
                .toSet()
            if (artists.size > 1) continue

            val declared = group.filter { it.bestPart != null }
            if (declared.map { it.partKind }.toSet().size < 2) continue

            val work = Work(
                id = newId(),
                title = titleFor(group),
                artist = group.firstNotNullOfOrNull { it.artist?.takeIf { a -> a.isNotBlank() } },
                createdAt = now,
                updatedAt = now,
            )
            works += work
            group.forEach { assigned[it.id] = work.id }
        }

        if (works.isEmpty()) return Grouping(emptyList(), songs)
        return Grouping(
            works = works,
            songs = songs.map { song ->
                assigned[song.id]?.let { song.copy(workId = it) } ?: song
            },
        )
    }

    /**
     * What to call the work.
     *
     * The chart that declared a title wins over one named after its file, for
     * the same reason `Catalogue` picks the best-described copy to name a row:
     * a folder scanned with chart reading turned off contributes a title that
     * is really a filename, and naming the whole work after it would bury a
     * perfectly good `{title:}` that another part of the same song carries.
     */
    private fun titleFor(group: List<SongRef>): String =
        group.firstNotNullOfOrNull { it.title?.takeIf { title -> title.isNotBlank() } }
            ?: group.first().workTitle
}
