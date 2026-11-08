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

data class EngineXDrive(
    val name: String,
    val kind: String,
    val free: Long,
    val total: Long,
    val path: String
)

data class EngineXConnection(val baseUrl: String, val drives: List<EngineXDrive>)
data class EngineXTrackMedia(val url: String, val authorization: String)
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
    }
}
