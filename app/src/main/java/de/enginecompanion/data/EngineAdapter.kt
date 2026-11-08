package de.enginecompanion.data
import android.database.sqlite.SQLiteDatabase

interface EngineAdapter {
    val label: String
    fun supports(schema: DbSchema): Boolean
    fun playlists(db: SQLiteDatabase): List<Playlist>
    fun members(db: SQLiteDatabase, playlist: Long): OrderedMembers
    fun countTracks(db: SQLiteDatabase, schema: DbSchema, query: String, ids: List<Long>?): Int
    fun tracks(db: SQLiteDatabase, schema: DbSchema, query: String, sort: String, limit: Int, ids: List<Long>?): List<Track>
}
abstract class DirectTrackAdapter(private val versions: Set<String>) : EngineAdapter {
    override fun supports(schema: DbSchema) = schema.version in versions &&
        schema.has("Track", "id", "title", "artist", "album", "length", "originDatabaseUuid", "originTrackId") &&
        schema.has("Playlist", "id", "title", "parentListId", "nextListId") &&
        schema.has("PlaylistEntity", "id", "listId", "trackId", "databaseUuid", "nextEntityId")
    override fun playlists(db: SQLiteDatabase): List<Playlist> = buildList {
        db.rawQuery("SELECT id,title,parentListId,nextListId FROM Playlist ORDER BY id", null).use { c ->
            while (c.moveToNext()) add(Playlist(c.getLong(0), c.getString(1) ?: "Ohne Titel", if (c.isNull(2) || c.getLong(2) == 0L) null else c.getLong(2), if (c.isNull(3) || c.getLong(3) == 0L) null else c.getLong(3)))
        }
    }
    override fun members(db: SQLiteDatabase, playlist: Long): OrderedMembers = PlaylistOrder.order(buildList {
        db.rawQuery("SELECT id,trackId,nextEntityId,databaseUuid FROM PlaylistEntity WHERE listId=?", arrayOf(playlist.toString())).use { c ->
            while (c.moveToNext()) add(Membership(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2), c.getString(3)))
        }
    })
    override fun countTracks(db: SQLiteDatabase, schema: DbSchema, query: String, ids: List<Long>?): Int {
        if (ids != null && ids.isEmpty()) return 0
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val idFilter = ids?.joinToString(",", " AND id IN (", ")") ?: ""
        return db.rawQuery(
            "SELECT count(*) FROM Track WHERE (title LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR album LIKE ? ESCAPE '\\')$idFilter",
            arrayOf("%$escaped%", "%$escaped%", "%$escaped%")
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
    }
    override fun tracks(db: SQLiteDatabase, schema: DbSchema, query: String, sort: String, limit: Int, ids: List<Long>?): List<Track> {
        if (ids != null && ids.isEmpty()) return emptyList()
        val columns = listOf("id", "title", "artist", "album", "length", "bpm", "bpmAnalyzed", "key", "rating").filter { schema.has("Track", it) }
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val where = "(title LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR album LIKE ? ESCAPE '\\')"
        val idFilter = ids?.joinToString(",", " AND id IN (", ")") ?: ""
        val ordering = when (sort) { "Interpret" -> "artist COLLATE NOCASE,title COLLATE NOCASE,id"; "BPM" -> if ("bpmAnalyzed" in columns) "bpmAnalyzed,title COLLATE NOCASE,id" else "id"; else -> "title COLLATE NOCASE,id" }
        val result = buildList {
            db.rawQuery("SELECT ${columns.joinToString { quote(it) }} FROM Track WHERE $where$idFilter ORDER BY $ordering LIMIT ?",
                arrayOf("%$escaped%", "%$escaped%", "%$escaped%", limit.coerceIn(1, 100000).toString())).use { c ->
                while (c.moveToNext()) add(Track(c.getLong(0), c.text("title")?.takeIf { it.isNotBlank() } ?: "Ohne Titel · ${c.getLong(0)}", c.text("artist"), c.text("album"), c.text("length")?.toDoubleOrNull(), c.text("bpmAnalyzed")?.toDoubleOrNull()?.takeIf { it > 0 } ?: c.text("bpm")?.toDoubleOrNull(), c.text("key"), c.text("rating")))
            }
        }
        return if (sort == "Playlist" && ids != null) { val order = ids.withIndex().associate { it.value to it.index }; result.sortedBy { order[it.id] } } else result
    }
}
class Engine2Adapter : DirectTrackAdapter(setOf("2.18.0", "2.20.1", "2.20.2", "2.20.3", "2.21.0", "2.21.1", "2.21.2")) { override val label = "Database2 · Schema 2.x (Referenzprofil)" }
class Engine3Adapter : DirectTrackAdapter(setOf("3.0.0", "3.0.1", "3.0.2")) { override val label = "Database2 · Schema 3.x (Referenzprofil)" }
object AdapterRegistry { fun resolve(schema: DbSchema): EngineAdapter? = listOf(Engine2Adapter(), Engine3Adapter()).firstOrNull { it.supports(schema) } }
