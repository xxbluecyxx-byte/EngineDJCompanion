package de.enginecompanion

import de.enginecompanion.data.Playlist
import de.enginecompanion.data.PlaylistTree
import de.enginecompanion.data.PlaylistCounts
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

    @Test fun recursiveCountsIncludeLocalPlaylistEntries() {
        val counts = PlaylistCounts.recursive(rows, mapOf(1L to listOf(7), 10L to listOf(8, 9), 11L to listOf(9, 9), 3L to emptyList()))
        assertEquals(4, counts[1])
        assertEquals(2, counts[10])
        assertEquals(1, counts[11])
        assertEquals(0, counts[3])
    }

    @Test fun recursiveCountsSurviveBrokenParentCycles() {
        val cyclic = listOf(Playlist(1, "A", 2), Playlist(2, "B", 1))
        val counts = PlaylistCounts.recursive(cyclic, mapOf(1L to listOf(10), 2L to listOf(20)))
        assertEquals(2, counts[1])
        assertEquals(1, counts[2])
    }
}
