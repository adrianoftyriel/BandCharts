package org.droidmusic.app.data

import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.Setlist
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two pushes of the same running order landing close together must end up as
 * one set list, not two.
 *
 * A leader's reconnect catch-up can arrive within milliseconds of the push
 * that prompted it. [SetlistRepository.adopt] used to be a read of the book
 * followed later by a save, and two calls that both read before either had
 * written both decided "not here yet" and both saved a fresh copy - the same
 * running order landing on the screen twice. Deciding inside the store's own
 * update, under its mutex, is what makes the second call see the first
 * call's result rather than the book as it stood before either ran.
 */
class SetlistRepositoryAdoptTest {

    private val fromLeader = Setlist(id = "leader-list", name = "Friday at the Anchor")

    @Test
    fun `pushes racing each other still land as one set list`() = runBlocking {
        val directory = Files.createTempDirectory("setlist-repo-test").toFile()
        val repository = SetlistRepository(directory, CoroutineScope(Dispatchers.Default))

        // Twenty calls, all handed the identical incoming list and launched
        // together on a dispatcher that actually runs them in parallel - as
        // close as a test gets to two network messages arriving within a
        // millisecond of each other.
        coroutineScope {
            List(20) { n ->
                async(Dispatchers.Default) {
                    repository.adopt(
                        incoming = fromLeader,
                        library = LibraryIndex(),
                        now = n.toLong(),
                        newId = { "local-${System.nanoTime()}-$n" },
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, repository.book.value.setlists.size)
        assertEquals("leader-list", repository.book.value.setlists.single().originId)
    }
}
