package org.droidmusic.session

import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.SongRef
import org.droidmusic.music.PartKind

/**
 * Finding this device's copy of the chart the leader is on.
 *
 * A [Position] names a song three ways, and only the last two mean anything
 * here. `songId` is derived from the source the chart was indexed from, and a
 * source id is a UUID generated on the device that added the folder - so the
 * leader's id for a song never matches the follower's, even when both are
 * reading byte-identical copies of the same file. A follower that looks up only
 * the id therefore fails on every song the leader opens, which presents as the
 * chart simply not arriving.
 *
 * So the id is tried first, because on the leader's own device it is exact and
 * free, and then the content hash and the title - the same two things a set list
 * entry carries across, and for the same reason.
 */
fun LibraryIndex.songFor(position: Position): SongRef? {
    val byId = position.songId?.let { findById(it) }
    if (byId != null) return byId

    val title = position.songTitle ?: return null
    return matchAny(listOfNotNull(position.contentHash) + position.partHashes, title)
}
/**
 * The chart this device should actually put on the glass.
 *
 * [songFor] finds the *song* the leader is on; this picks the part of it that
 * belongs to whoever is holding this phone. The two are separate because they
 * answer different questions, and one caller genuinely wants each: the leader's
 * own screen follows the leader's own choice of chart, and everybody else's
 * follows their own instrument.
 *
 * Falls back to the chart [songFor] found, so a song with no parts - which is
 * every song in a library that has grouped nothing - resolves exactly as it
 * always did.
 */
fun LibraryIndex.partFor(position: Position, preference: List<PartKind>): SongRef? {
    val song = songFor(position) ?: return null
    return preferredPart(song.id, preference) ?: song
}

/**
 * The arrangement a follower plays when the leader announces a position.
 *
 * Two numbers that look alike and are not the same kind of thing at all.
 *
 * **The key is the band's.** Transposing is the singer saying tonight this one
 * is in B flat, and a band where that reached only one phone is a band playing
 * two different songs. So a leader's transposition applies everywhere, and
 * arrives the moment they choose it rather than at the next page turn.
 *
 * **The capo is one player's.** It changes nothing anybody hears - it is how a
 * guitarist chooses to finger the same key, and it means nothing at all to the
 * keyboard player, the horn player, or the guitarist who capos somewhere else.
 * A leader's capo travelling with the position put the leader's fingering on
 * everybody's screen, which for half a band is shapes for an instrument they are
 * not holding.
 *
 * So the capo in a [Position] is advisory: it says what the leader is fingering,
 * and every device keeps its own.
 *
 * **The part is one player's as well, and more so.** Which of a song's charts
 * is on the glass - the chord chart, the bass part, the drum chart - is a fact
 * about who is holding the phone, and a leader who pushed theirs would put a
 * drum chart in front of the bass player. So a [Position] names every part of
 * the song, by hash, and says nothing at all about which of them to open; see
 * [partFor].
 */
data class Arrangement(val transposeSemitones: Int, val capo: Int)

/** What this device should play, given [localCapo] as it already had it. */
fun Position.arrangementFor(localCapo: Int): Arrangement =
    Arrangement(transposeSemitones = transposeSemitones, capo = localCapo)
