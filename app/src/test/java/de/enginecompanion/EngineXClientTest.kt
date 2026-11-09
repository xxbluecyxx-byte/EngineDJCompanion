package de.enginecompanion

import de.enginecompanion.network.EngineXClient
import de.enginecompanion.network.TrackMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineXClientTest {
    @Test fun normalizesPrivatePrimeAddress() {
        assertEquals("http://192.168.178.67:8080", EngineXClient.normalizeBaseUrl("192.168.178.67"))
        assertEquals("http://sc5000.lan:8080", EngineXClient.normalizeBaseUrl("http://sc5000.lan"))
        assertEquals("http://192.168.178.67:8081", EngineXClient.agentBaseUrl("http://192.168.178.67:8080"))
    }

    @Test fun rejectsPublicAndCredentialBearingUrls() {
        assertThrows(IllegalArgumentException::class.java) { EngineXClient.normalizeBaseUrl("https://example.com") }
        assertThrows(IllegalArgumentException::class.java) { EngineXClient.normalizeBaseUrl("http://code@192.168.1.2") }
    }

    @Test fun parsesEngineXDriveResponse() {
        val drives = EngineXClient.parseDrives("""[{"name":"Engine","kind":"hd","free":405337276416,"total":511530811392,"path":"/media/Engine"}]""")
        assertEquals("Engine", drives.single().name)
        assertEquals("hd", drives.single().kind)
    }

    @Test fun parsesLibraryAndNativePlaylistOrder() {
        val json = """{"drive":"Engine","databaseUuid":"u","schemaVersion":"3.0.2","generatedAt":"now","tracks":[{"id":5,"title":"A"},{"id":6,"title":"B"}],"playlists":[{"id":33,"title":"Techno","parent":0,"next":0}],"memberships":[{"id":10,"listId":33,"trackId":5,"databaseUuid":"origin-u","next":20},{"id":20,"listId":33,"trackId":6,"databaseUuid":"origin-u","next":0}]}"""
        val snapshot = EngineXClient.parseSnapshot(json)
        assertEquals(listOf(5L, 6L), snapshot.playlistTracks[33])
        assertEquals("Techno", snapshot.playlists.single().title)
    }

    @Test fun appliesVerifiedMetadataWithoutChangingPlaylistMemberships() {
        val json = """{"drive":"Engine","databaseUuid":"u","schemaVersion":"3.0.2","generatedAt":"now","tracks":[{"id":5,"title":"Alt","artist":"A","album":"","rating":0},{"id":6,"title":"Bleibt"}],"playlists":[{"id":33,"title":"Techno","parent":0,"next":0}],"memberships":[{"id":10,"listId":33,"trackId":5,"databaseUuid":"u","next":20},{"id":20,"listId":33,"trackId":6,"databaseUuid":"u","next":0}]}"""
        val snapshot = EngineXClient.parseSnapshot(json)
        val updated = EngineXClient.applyMetadata(snapshot, listOf(
            TrackMetadata(5, "Neu", "B", "Album", "Genre", "Kommentar", "Label", "Remixer", 60)
        ))

        assertEquals("Neu", updated.tracks.first { it.id == 5L }.title)
        assertEquals("B", updated.tracks.first { it.id == 5L }.artist)
        assertEquals("60", updated.tracks.first { it.id == 5L }.rating)
        assertEquals("Bleibt", updated.tracks.first { it.id == 6L }.title)
        assertEquals(listOf(5L, 6L), updated.playlistTracks[33])
        assertEquals("Neu", org.json.JSONObject(updated.rawJson).getJSONArray("tracks").getJSONObject(0).getString("title"))
    }
}
