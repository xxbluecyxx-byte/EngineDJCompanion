package de.enginecompanion.network

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import android.util.Base64
import de.enginecompanion.data.AnalysisDecoder
import de.enginecompanion.data.TrackAnalysis
import de.enginecompanion.data.Membership
import de.enginecompanion.data.Playlist
import de.enginecompanion.data.PlaylistOrder
import de.enginecompanion.data.Track
import de.enginecompanion.data.CuePoint

data class EngineXDrive(
    val name: String,
    val kind: String,
    val free: Long,
    val total: Long,
    val path: String
)

data class EngineXConnection(val baseUrl: String, val drives: List<EngineXDrive>)
data class EngineXTrackMedia(val url: String, val authorization: String)
data class TrackMetadata(
    val trackId: Long, val title: String, val artist: String, val album: String,
    val genre: String, val comment: String, val label: String, val remixer: String,
    val rating: Int, val backup: String? = null
)
data class PlaylistMutationResult(val playlistId: Long, val backup: String?)
data class EngineXSnapshot(
    val drive: String,
    val databaseUuid: String,
    val schemaVersion: String,
    val generatedAt: String,
    val tracks: List<Track>,
    val playlists: List<Playlist>,
    val playlistTracks: Map<Long, List<Long>>,
    val rawJson: String
)

class EngineXClient {
    fun mutatePlaylist(host: String, accessCode: String, drive: String, action: String, playlistId: Long = 0,
                       title: String = "", parentId: Long = 0, trackIds: List<Long> = emptyList()): PlaylistMutationResult {
        val driveName = java.net.URLEncoder.encode(drive, Charsets.UTF_8.name())
        val connection = (URL("${agentBaseUrl(host)}/api/companion/v1/playlists?drive=$driveName").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 5_000; readTimeout = 120_000; doOutput = true; useCaches = false
            setRequestProperty("Authorization", "Bearer ${accessCode.trim()}"); setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val payload = org.json.JSONObject().put("action", action).put("playlistId", playlistId)
                .put("title", title).put("parentId", parentId).put("trackIds", org.json.JSONArray(trackIds))
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException(connection.errorStream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty().ifBlank { "Agent antwortet mit HTTP $code." })
            val result = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return PlaylistMutationResult(result.getLong("playlistId"), result.optString("backup").takeIf(String::isNotBlank))
        } finally { connection.disconnect() }
    }
    fun updateBeatgrid(media: EngineXTrackMedia, bpm: Double, offsetMs: Long): String? {
        val endpoint = media.url.replace(Regex("/audio(?=\\?)"), "/beatgrid")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 5_000; readTimeout = 120_000; doOutput = true; useCaches = false
            setRequestProperty("Authorization", media.authorization); setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.bufferedWriter().use { it.write(org.json.JSONObject().put("bpm", bpm).put("offsetMs", offsetMs).toString()) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException(connection.errorStream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty().ifBlank { "Agent antwortet mit HTTP $code." })
            return org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optString("backup").takeIf(String::isNotBlank)
        } finally { connection.disconnect() }
    }

    fun updateCues(media: EngineXTrackMedia, cues: List<CuePoint>): String? {
        val endpoint = media.url.replace(Regex("/audio(?=\\?)"), "/cues")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 5_000; readTimeout = 120_000; doOutput = true; useCaches = false
            setRequestProperty("Authorization", media.authorization); setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val payload = org.json.JSONObject().put("cues", org.json.JSONArray().apply { cues.sortedBy(CuePoint::number).forEach { cue ->
                put(org.json.JSONObject().put("number", cue.number).put("label", cue.label).put("sampleOffset", cue.sampleOffset).put("color", cue.color))
            } })
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException(connection.errorStream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty().ifBlank { "Agent antwortet mit HTTP $code." })
            return org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optString("backup").takeIf(String::isNotBlank)
        } finally { connection.disconnect() }
    }

    fun metadata(media: EngineXTrackMedia): TrackMetadata = metadataRequest(media, null)

    fun updateMetadata(media: EngineXTrackMedia, value: TrackMetadata): TrackMetadata = metadataRequest(media, value)

    private fun metadataRequest(media: EngineXTrackMedia, value: TrackMetadata?): TrackMetadata {
        val endpoint = media.url.replace(Regex("/audio(?=\\?)"), "/metadata")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = if (value == null) "GET" else "POST"
            connectTimeout = 5_000; readTimeout = 120_000
            setRequestProperty("Authorization", media.authorization)
            setRequestProperty("Accept", "application/json")
            if (value != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            useCaches = false
        }
        try {
            if (value != null) connection.outputStream.bufferedWriter().use { writer ->
                writer.write(org.json.JSONObject().apply {
                    put("trackId", value.trackId); put("title", value.title); put("artist", value.artist)
                    put("album", value.album); put("genre", value.genre); put("comment", value.comment)
                    put("label", value.label); put("remixer", value.remixer); put("rating", value.rating)
                }.toString())
            }
            val code = connection.responseCode
            if (code == 401) throw SecurityException("EngineX-Zugriffscode wurde abgelehnt.")
            if (code !in 200..299) throw IOException(connection.errorStream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty().ifBlank { "Agent antwortet mit HTTP $code." })
            val json = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return TrackMetadata(json.getLong("trackId"), json.optString("title"), json.optString("artist"), json.optString("album"),
                json.optString("genre"), json.optString("comment"), json.optString("label"), json.optString("remixer"),
                json.optInt("rating"), json.optString("backup").takeIf(String::isNotBlank))
        } finally { connection.disconnect() }
    }

    fun connect(host: String, accessCode: String): EngineXConnection {
        require(accessCode.isNotBlank()) { "EngineX-Zugriffscode fehlt." }
        val base = normalizeBaseUrl(host)
        val connection = (URL("$base/api/drives").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer ${accessCode.trim()}")
            setRequestProperty("Accept", "application/json")
            useCaches = false
        }
        try {
            val code = connection.responseCode
            if (code == 401) throw SecurityException("EngineX-Zugriffscode wurde abgelehnt.")
            if (code !in 200..299) throw IOException("EngineX antwortet mit HTTP $code.")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return EngineXConnection(base, parseDrives(body))
        } finally {
            connection.disconnect()
        }
    }

    fun snapshot(host: String, accessCode: String, drive: String = "Engine"): EngineXSnapshot {
        require(accessCode.isNotBlank()) { "EngineX-Zugriffscode fehlt." }
        val base = normalizeBaseUrl(host)
        val encodedDrive = java.net.URLEncoder.encode(drive, Charsets.UTF_8.name())
        val body = request("$base/api/companion/snapshot?drive=$encodedDrive", accessCode)
        return parseSnapshot(body)
    }

    fun analysis(host: String, accessCode: String, trackId: Long, drive: String = "Engine"): TrackAnalysis {
        val driveName = java.net.URLEncoder.encode(drive, Charsets.UTF_8.name())
        val body = request("${agentBaseUrl(host)}/api/companion/v1/tracks/$trackId/analysis?drive=$driveName", accessCode)
        val json = org.json.JSONObject(body)
        require(json.optLong("trackId") == trackId) { "Agent lieferte eine abweichende Track-ID." }
        fun blob(name: String): ByteArray = json.optString(name).takeIf(String::isNotBlank)
            ?.let { Base64.decode(it, Base64.DEFAULT) } ?: byteArrayOf()
        return AnalysisDecoder.decode(blob("beatData"), blob("quickCues"), blob("loops"), blob("overviewWaveformData"))
    }

    fun trackMedia(host: String, accessCode: String, trackId: Long, drive: String = "Engine"): EngineXTrackMedia {
        require(accessCode.isNotBlank()) { "EngineX-Zugriffscode fehlt." }
        val driveName = java.net.URLEncoder.encode(drive, Charsets.UTF_8.name())
        return EngineXTrackMedia(
            "${agentBaseUrl(host)}/api/companion/v1/tracks/$trackId/audio?drive=$driveName",
            "Bearer ${accessCode.trim()}"
        )
    }

    private fun request(url: String, accessCode: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 5_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer ${accessCode.trim()}")
            setRequestProperty("Accept", "application/json"); useCaches = false
        }
        try {
            val code = connection.responseCode
            if (code == 401) throw SecurityException("EngineX-Zugriffscode wurde abgelehnt.")
            if (code == 404) throw IOException("Die installierte EngineX-Version besitzt noch keine Companion-Library-API.")
            if (code !in 200..299) throw IOException("EngineX antwortet mit HTTP $code.")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    companion object {
        fun agentBaseUrl(input: String): String {
            val engine = URI(normalizeBaseUrl(input))
            val displayHost = if (':' in engine.host) "[${engine.host}]" else engine.host
            return "http://$displayHost:8081"
        }
        fun normalizeBaseUrl(input: String): String {
            val raw = input.trim().removeSuffix("/")
            require(raw.isNotEmpty()) { "Prime-Adresse fehlt." }
            val withScheme = if ("://" in raw) raw else "http://$raw"
            val uri = URI(withScheme)
            require(uri.scheme.equals("http", true)) { "EngineX 5.3.0.2 verwendet lokales HTTP." }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "Ungültige Prime-Adresse." }
            require(uri.path.isNullOrEmpty() || uri.path == "/") { "Nur Hostname oder IP eingeben." }
            val host = uri.host ?: throw IllegalArgumentException("Ungültige Prime-Adresse.")
            require(isLocalHost(host)) { "Nur private LAN-Adressen oder lokale Hostnamen sind erlaubt." }
            val port = if (uri.port == -1) 8080 else uri.port
            require(port in 1..65535) { "Ungültiger Port." }
            val displayHost = if (':' in host) "[$host]" else host
            return "http://$displayHost:$port"
        }

        internal fun isLocalHost(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h.endsWith(".lan") || h.endsWith(".local")) return true
            val p = h.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4 || p.any { it !in 0..255 }) return false
            return p[0] == 10 || p[0] == 127 || p[0] == 192 && p[1] == 168 ||
                p[0] == 172 && p[1] in 16..31 || p[0] == 169 && p[1] == 254
        }

        fun parseDrives(json: String): List<EngineXDrive> {
            val array = JSONArray(json)
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                EngineXDrive(
                    name = item.getString("name"),
                    kind = item.optString("kind", "drive"),
                    free = item.getLong("free"),
                    total = item.getLong("total"),
                    path = item.getString("path")
                )
            }
        }

        fun parseSnapshot(json: String): EngineXSnapshot {
            val root = org.json.JSONObject(json)
            val uuid = root.getString("databaseUuid")
            val tracksJson = root.getJSONArray("tracks")
            val tracks = (0 until tracksJson.length()).map { i ->
                val v = tracksJson.getJSONObject(i)
                Track(v.getLong("id"), v.optString("title").ifBlank { "Ohne Titel · ${v.getLong("id")}" },
                    v.optString("artist").takeIf { it.isNotBlank() }, v.optString("album").takeIf { it.isNotBlank() },
                    v.optDouble("length").takeIf { !it.isNaN() }, v.optDouble("bpm").takeIf { !it.isNaN() && it > 0 },
                    if (v.has("key")) v.getLong("key").toString() else null,
                    if (v.has("rating")) v.getLong("rating").toString() else null)
            }
            val playlistsJson = root.getJSONArray("playlists")
            val playlists = (0 until playlistsJson.length()).map { i ->
                val v = playlistsJson.getJSONObject(i)
                Playlist(v.getLong("id"), v.optString("title"), v.optLong("parent").takeIf { it != 0L }, v.optLong("next").takeIf { it != 0L })
            }
            val byList = linkedMapOf<Long, MutableList<Membership>>()
            val members = root.getJSONArray("memberships")
            for (i in 0 until members.length()) {
                val v = members.getJSONObject(i)
                val databaseUuid = v.optString("databaseUuid")
                byList.getOrPut(v.getLong("listId")) { mutableListOf() } += Membership(
                    v.getLong("id"), v.getLong("trackId"), v.optLong("next").takeIf { it != 0L }, databaseUuid)
            }
            return EngineXSnapshot(root.getString("drive"), uuid, root.getString("schemaVersion"), root.getString("generatedAt"), tracks, playlists,
                byList.mapValues { PlaylistOrder.order(it.value).members.map(Membership::trackId) }, json)
        }

        fun applyMetadata(snapshot: EngineXSnapshot, updates: Collection<TrackMetadata>): EngineXSnapshot {
            if (updates.isEmpty()) return snapshot
            val byId = updates.associateBy(TrackMetadata::trackId)
            val root = org.json.JSONObject(snapshot.rawJson)
            val tracks = root.getJSONArray("tracks")
            for (index in 0 until tracks.length()) {
                val row = tracks.getJSONObject(index)
                val value = byId[row.getLong("id")] ?: continue
                row.put("title", value.title)
                row.put("artist", value.artist)
                row.put("album", value.album)
                row.put("rating", value.rating)
            }
            return parseSnapshot(root.toString())
        }
    }
}
