package de.enginecompanion

import de.enginecompanion.data.Playlist
import de.enginecompanion.data.PlaylistTree
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistTreeTest {
    private val rows = listOf(
        Playlist(3, "Second", null, null), Playlist(1, "Folder", null, 3),
        Playlist(11, "Child B", 1, null), Playlist(10, "Child A", 1, 11)
    )

    @Test fun collapsedTreeUsesNativeSiblingOrder() {
        val tree = PlaylistTree.flatten(rows, emptySet())
        assertEquals(listOf(1L, 3L), tree.map { it.playlist.id })
        assertEquals(listOf(0, 0), tree.map { it.depth })
    }

    @Test fun expandedFolderShowsOrderedChildren() {
        val tree = PlaylistTree.flatten(rows, setOf(1))
        assertEquals(listOf(1L, 10L, 11L, 3L), tree.map { it.playlist.id })
        assertEquals(listOf(0, 1, 1, 0), tree.map { it.depth })
    }

    @Test fun invalidChainFallsBackToTitle() {
        val broken = listOf(Playlist(2, "Zulu", null, 99), Playlist(1, "Alpha", null, 99))
        assertEquals(listOf("Alpha", "Zulu"), PlaylistTree.flatten(broken, emptySet()).map { it.playlist.title })
    }
}
