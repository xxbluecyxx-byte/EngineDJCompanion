package de.enginecompanion.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import de.enginecompanion.storage.Snapshot
import java.io.File

class LibraryRepository(private val context: Context) {
    data class Library(
        val directory: File,
        val schema: DbSchema,
        val adapter: EngineAdapter?,
        val playlists: List<Playlist>,
        val playlistTracks: Map<Long, List<Long>>,
        val diagnostics: String
    )
    data class Page(val tracks: List<Track>, val warning: String? = null)
    private val preferences = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private fun open(file: File) = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).apply { execSQL("PRAGMA query_only=ON") }
    fun loadSaved(): Library? = preferences.getString("snapshot", null)?.let { File(context.filesDir, "snapshots/$it") }?.takeIf { it.isDirectory }?.let { inspect(it) }
    fun prepare(snapshot: Snapshot): Library {
        File(snapshot.directory,"working").listFiles().orEmpty().filter { it.extension == "db" }.forEach { file ->
            // Recovery/checkpoint can write ONLY this private working copy, never the source.
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { c -> check(c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext()) { "Integritätsprüfung fehlgeschlagen: ${file.name}" } }
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c -> if (c.moveToFirst()) check(c.getInt(0) == 0) { "WAL nicht lokal konsolidiert: ${file.name}" } }
            }
        }
        return inspect(snapshot.directory)
    }
    fun activate(library: Library) {
        check(preferences.edit().putString("snapshot", library.directory.name).commit()) { "Import konnte nicht aktiviert werden." }
        File(context.filesDir, "snapshots").listFiles().orEmpty().filter { it != library.directory }.forEach { it.deleteRecursively() }
    }
    private fun inspect(directory: File): Library {
        val report = StringBuilder(File(directory, "manifest.txt").takeIf { it.exists() }?.readText().orEmpty())
        var main: DbSchema? = null
        File(directory, "working").listFiles().orEmpty().filter { it.extension == "db" }.sortedBy { it.name }.forEach { file ->
            open(file).use { db ->
                val schema = SchemaInspector.inspect(db)
                report.appendLine("\n=== ${file.name} ===\n${schema.report}")
                if (file.name == "m.db") main = schema
            }
        }
        val schema = checkNotNull(main) { "m.db fehlt in der Momentaufnahme." }
        val adapter = AdapterRegistry.resolve(schema)
        report.appendLine("Adapter: ${adapter?.label ?: "UNBEKANNT – kein semantischer Import"}")
        report.appendLine("Gerätevalidierung: ausstehend. Smartlists nicht ausgewertet. Cues/Loops/Beatgrid: Binärdaten nicht dekodiert.")
        val (playlists, playlistTracks) = if (adapter == null) emptyList<Playlist>() to emptyMap() else open(File(directory, "working/m.db")).use { db ->
            val lists = adapter.playlists(db)
            val members = lists.associate { playlist ->
                val local = MembershipResolver.local(adapter.members(db, playlist.id).members, schema.uuid)
                playlist.id to local.map(Membership::trackId).distinct()
            }
            lists to members
        }
        return Library(directory, schema, adapter, playlists, playlistTracks, report.toString())
    }
    fun tracks(library: Library, playlist: Long?, query: String, sort: String, limit: Int): Page {
        val adapter = library.adapter ?: return Page(emptyList())
        return open(File(library.directory, "working/m.db")).use { db ->
            val membership = playlist?.let { adapter.members(db, it) }
            val local = membership?.members?.let { MembershipResolver.local(it, library.schema.uuid) }
            val excluded = (membership?.members?.size ?: 0) - (local?.size ?: 0)
            val ids = local?.map { it.trackId }?.distinct()
            val foundIds = ids?.chunked(500)?.sumOf { chunk -> db.rawQuery("SELECT count(*) FROM Track WHERE id IN (${chunk.joinToString()})", null).use { c -> c.moveToFirst(); c.getInt(0) } }
            val missing = if (ids == null) 0 else ids.size - (foundIds ?: 0)
            val tracks = adapter.tracks(db, library.schema, query, sort, if (sort == "Playlist") 100000 else limit, ids)
            Page(tracks.take(limit), listOfNotNull(membership?.warning, if (missing > 0) "$missing Playlist-Track-IDs fehlen in Track." else null, if (excluded > 0) "$excluded Einträge haben fremde oder nicht auflösbare Datenbank-UUIDs." else null).joinToString("\n").ifEmpty { null })
        }
    }
    fun details(library: Library, track: Track): String = buildString {
        appendLine("Track-ID: ${track.id}")
        open(File(library.directory, "working/m.db")).use { db ->
            val fields = library.schema.tables["Track"].orEmpty().filterNot { it.type.contains("BLOB", true) }.map { it.name }
            db.rawQuery("SELECT ${fields.joinToString { quote(it) }} FROM Track WHERE id=?", arrayOf(track.id.toString())).use { c -> if (c.moveToFirst()) fields.forEach { appendLine("$it: ${c.text(it) ?: "—"}") } }
        }
        appendLine("\nAnalyse-Daten (Rohbestand, nicht dekodiert):")
        File(library.directory, "working").listFiles().orEmpty().filter { it.extension == "db" }.forEach { file ->
            open(file).use { db ->
                val schema = SchemaInspector.inspect(db)
                if (schema.has("PerformanceData", "trackId") && schema.version == library.schema.version) {
                    val blobs = schema.tables.getValue("PerformanceData").filter { it.type.equals("BLOB", true) }
                    if (blobs.isNotEmpty()) db.rawQuery("SELECT ${blobs.joinToString { "length(${quote(it.name)})" }} FROM PerformanceData WHERE trackId=?", arrayOf(track.id.toString())).use { c ->
                        if (c.moveToFirst()) blobs.forEachIndexed { i, col -> appendLine("${file.name} · ${col.name}: ${if (c.isNull(i)) "nicht vorhanden" else "${c.getLong(i)} Bytes"}") }
                    }
                }
            }
        }
        appendLine("\nCue-Zeitpunkte, Loop-Grenzen und Waveforms benötigen weitere Validierung mit echten Daten.")
    }
}
