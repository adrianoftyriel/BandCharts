package org.bandcharts.app.data

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.bandcharts.library.LibraryIndex
import org.bandcharts.library.Setlist
import org.bandcharts.library.SetlistCodec
import org.bandcharts.library.SetlistImport
import org.bandcharts.library.SongRef
import org.bandcharts.library.SourceRef

class SettingsRepository(directory: File, scope: CoroutineScope) {
    private val store = JsonStore(
        file = File(directory, "settings.json"),
        serializer = AppSettings.serializer(),
        default = AppSettings(),
        scope = scope,
    )

    val settings: StateFlow<AppSettings> get() = store.state

    suspend fun load(): AppSettings = store.load()

    suspend fun update(transform: (AppSettings) -> AppSettings) = store.update(transform)

    fun updateAsync(transform: (AppSettings) -> AppSettings) = store.updateAsync(transform)
}

class LibraryRepository(directory: File, scope: CoroutineScope) {
    private val store = JsonStore(
        file = File(directory, "library.json"),
        serializer = LibraryIndex.serializer(),
        default = LibraryIndex(),
        scope = scope,
    )

    val index: StateFlow<LibraryIndex> get() = store.state

    suspend fun load(): LibraryIndex = store.load()

    suspend fun addSource(source: SourceRef) = store.update { current ->
        current.copy(sources = current.sources.filterNot { it.id == source.id } + source)
    }

    /**
     * Removes a source and everything indexed from it. The files themselves are
     * left alone - they belong to the user's Drive or their Downloads folder,
     * and forgetting about them is not the same as deleting them.
     */
    suspend fun removeSource(sourceId: String) = store.update { current ->
        current.copy(
            sources = current.sources.filterNot { it.id == sourceId },
            songs = current.songs.filterNot { it.sourceId == sourceId },
        )
    }

    /**
     * Replaces everything known about one source after a rescan.
     *
     * The merge itself is [LibraryIndex.withSongsFrom], in the core, where it can
     * be tested without a device - a rescan that silently discarded a corrected
     * key, a renamed chart or a removed one would look exactly like a successful
     * rescan, and that is the kind of failure the core exists to hold.
     */
    suspend fun replaceSongsFrom(sourceId: String, songs: List<SongRef>, now: Long) =
        store.update { current -> current.withSongsFrom(sourceId, songs, now) }

    suspend fun updateSong(id: String, transform: (SongRef) -> SongRef) = store.update { current ->
        current.copy(songs = current.songs.map { if (it.id == id) transform(it) else it })
    }

    /**
     * Forgets a chart entirely, for use after its file has actually been deleted.
     *
     * Different from hiding it: hiding is remembered so that a rescan does not put
     * the chart back, and there is nothing to remember about a file that no longer
     * exists.
     */
    /**
     * Applies one change to many songs in a single write.
     *
     * A bulk edit that transposed forty charts one at a time would be forty
     * whole-file writes of the index racing each other, and a phone locked
     * halfway through leaves an arbitrary subset changed with nothing on screen
     * to say which.
     */
    suspend fun updateSongs(ids: Set<String>, transform: (SongRef) -> SongRef) =
        store.update { current ->
            current.copy(songs = current.songs.map { if (it.id in ids) transform(it) else it })
        }

    suspend fun dropSong(id: String) = store.update { current ->
        current.copy(songs = current.songs.filterNot { it.id == id })
    }

    /** Brings back every chart the user removed from the library. */
    suspend fun restoreHidden() = store.update { current ->
        current.copy(songs = current.songs.map { if (it.hidden) it.copy(hidden = false) else it })
    }

    /** Replaces the whole index with one read back from a backup. */
    suspend fun restore(index: LibraryIndex) = store.set(index.copy(updatedAt = System.currentTimeMillis()))
}

@kotlinx.serialization.Serializable
data class SetlistBook(val setlists: List<Setlist> = emptyList())

class SetlistRepository(directory: File, scope: CoroutineScope) {
    private val store = JsonStore(
        file = File(directory, "setlists/setlists.json"),
        serializer = SetlistBook.serializer(),
        default = SetlistBook(),
        scope = scope,
    )

    val book: StateFlow<SetlistBook> get() = store.state

    suspend fun load(): SetlistBook = store.load()

    suspend fun save(setlist: Setlist) = store.update { current ->
        current.copy(
            setlists = current.setlists.filterNot { it.id == setlist.id } + setlist,
        )
    }

    suspend fun delete(id: String) = store.update { current ->
        current.copy(setlists = current.setlists.filterNot { it.id == id })
    }

    /**
     * Takes a set list from elsewhere and matches it against the book, in one
     * transaction rather than a read followed later by a [save].
     *
     * The two have to be one operation. A leader's reconnect catch-up can land
     * within milliseconds of the push that prompted it, and a read of [book]
     * taken before either write is a snapshot that is already out of date by
     * the time the second one is decided from it - both calls find nothing
     * already here and both save a fresh copy, and the same running order
     * arrives on the screen twice. Deciding inside [JsonStore.update] means the
     * second call is matched against the *first call's own result*, because
     * that is what the store's mutex guarantees "current" is by the time this
     * runs.
     */
    suspend fun adopt(
        incoming: Setlist,
        library: LibraryIndex,
        now: Long,
        newId: () -> String,
    ): SetlistImport {
        lateinit var taken: SetlistImport
        store.update { current ->
            taken = SetlistCodec.adopt(incoming, current.setlists, library, now, newId)
            current.copy(
                setlists = current.setlists.filterNot { it.id == taken.setlist.id } + taken.setlist,
            )
        }
        return taken
    }

    /**
     * Renames one song everywhere it appears in a running order.
     *
     * The rule itself is [Setlist.withSongRenamed], in the core, where it can be
     * tested. Every list is rewritten in one save rather than one save each,
     * because a song can be in a dozen of them and each save is the whole file.
     * Lists that do not hold the song keep their [Setlist.updatedAt]: the set
     * did not change, and bumping it would reorder a screen sorted by when
     * somebody last touched a list.
     */
    suspend fun renameSong(songId: String, title: String, now: Long) {
        val holdsIt = store.state.value.setlists.any { list ->
            list.withSongRenamed(songId, title) !== list
        }
        if (!holdsIt) return

        store.update { current ->
            current.copy(
                setlists = current.setlists.map { list ->
                    val renamed = list.withSongRenamed(songId, title)
                    if (renamed === list) list else renamed.copy(updatedAt = now)
                },
            )
        }
    }

    fun find(id: String): Setlist? = store.state.value.setlists.firstOrNull { it.id == id }

    /** Replaces every set list with the ones read back from a backup. */
    suspend fun restore(book: SetlistBook) = store.set(book)
}
