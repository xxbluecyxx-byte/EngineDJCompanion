package de.enginecompanion.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
fun Cursor.text(name: String): String? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }
data class Column(val name: String, val type: String, val primaryKey: Int)
data class DbSchema(val tables: Map<String, List<Column>>, val version: String?, val uuid: String?, val report: String) {
    fun has(table: String, vararg columns: String) = tables[table]?.map { it.name }?.containsAll(columns.toList()) == true
}
object SchemaInspector {
    fun inspect(db: SQLiteDatabase): DbSchema {
        val tables = linkedMapOf<String, List<Column>>()
        val report = StringBuilder()
        db.rawQuery("PRAGMA user_version", null).use { if (it.moveToFirst()) report.appendLine("SQLite user_version=${it.getInt(0)}") }
        db.rawQuery("SELECT name, type, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                report.appendLine("${cursor.getString(1)} $name: ${cursor.getString(2)}")
                if (cursor.getString(1) == "table") {
                    val cols = mutableListOf<Column>()
                    db.rawQuery("PRAGMA table_info(${quote(name)})", null).use { c -> while (c.moveToNext()) cols += Column(c.getString(1), c.getString(2), c.getInt(5)) }
                    tables[name] = cols
                    cols.forEach { report.appendLine("  ${it.name} ${it.type} pk=${it.primaryKey}") }
                    db.rawQuery("PRAGMA foreign_key_list(${quote(name)})", null).use { c -> while (c.moveToNext()) report.appendLine("  FK ${c.text("from")} -> ${c.text("table")}.${c.text("to")}") }
                }
            }
        }
        var version: String? = null
        var uuid: String? = null
        val wanted = listOf("schemaVersionMajor", "schemaVersionMinor", "schemaVersionPatch", "uuid").filter { name -> tables["Information"]?.any { it.name == name } == true }
        if (wanted.isNotEmpty()) db.rawQuery("SELECT ${wanted.joinToString { quote(it) }} FROM Information LIMIT 1", null).use { c ->
            if (c.moveToFirst()) {
                val parts = listOf("schemaVersionMajor", "schemaVersionMinor", "schemaVersionPatch").map { c.text(it) }
                if (parts.all { it != null }) version = parts.joinToString(".")
                uuid = c.text("uuid")
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(report.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        report.insert(0, "Schema-Version: ${version ?: "unbekannt"}\nSchema-SHA256: $hash\n")
        return DbSchema(tables, version, uuid, report.toString())
    }
}
data class Track(val id: Long, val title: String, val artist: String?, val album: String?, val seconds: Double?, val bpm: Double?, val key: String?, val rating: String?)
data class Playlist(val id: Long, val title: String, val parent: Long?, val next: Long? = null)
data class PlaylistTreeRow(val playlist: Playlist, val depth: Int, val hasChildren: Boolean)
object PlaylistTree {
    fun flatten(playlists: List<Playlist>, expanded: Set<Long>): List<PlaylistTreeRow> {
        val normalized = playlists.map { it.copy(parent = it.parent?.takeIf { value -> value != 0L }, next = it.next?.takeIf { value -> value != 0L }) }
        val byParent = normalized.groupBy(Playlist::parent)
        fun ordered(rows: List<Playlist>): List<Playlist> {
            if (rows.size < 2) return rows
            val byId = rows.associateBy(Playlist::id)
            val pointed = rows.mapNotNull(Playlist::next).filter(byId::containsKey).toSet()
            val heads = rows.filter { it.id !in pointed }
            if (heads.size != 1) return rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            val result = mutableListOf<Playlist>()
            val visited = hashSetOf<Long>()
            var current: Playlist? = heads.single()
            while (current != null && visited.add(current.id)) { result += current; current = current.next?.let(byId::get) }
            return if (result.size == rows.size) result else rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }
        val result = mutableListOf<PlaylistTreeRow>()
        val visited = hashSetOf<Long>()
        fun append(parent: Long?, depth: Int) {
            ordered(byParent[parent].orEmpty()).forEach { playlist ->
                if (!visited.add(playlist.id)) return@forEach
                val hasChildren = byParent[playlist.id].orEmpty().isNotEmpty()
                result += PlaylistTreeRow(playlist, depth, hasChildren)
                if (hasChildren && playlist.id in expanded) append(playlist.id, depth + 1)
            }
        }
        append(null, 0)
        val allIds = normalized.map(Playlist::id).toSet()
        normalized.filter { it.id !in visited && it.parent != null && it.parent !in allIds }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }).forEach {
            result += PlaylistTreeRow(it, 0, byParent[it.id].orEmpty().isNotEmpty())
        }
        return result
    }
}
data class Membership(val id: Long, val trackId: Long, val next: Long?, val databaseUuid: String?)
data class OrderedMembers(val members: List<Membership>, val warning: String?)
object PlaylistOrder {
    fun order(rows: List<Membership>): OrderedMembers {
        if (rows.isEmpty()) return OrderedMembers(emptyList(), null)
        fun invalid() = OrderedMembers(rows.sortedBy { it.id }, "Playlist-Verkettung ungültig: Anzeige nach Eintrags-ID.")
        val byId = rows.associateBy { it.id }
        if (byId.size != rows.size || rows.any { (it.next ?: 0L) != 0L && it.next !in byId }) return invalid()
        val pointed = rows.mapNotNull { it.next }.filter { it in byId }.toSet()
        val heads = rows.filter { it.id !in pointed }
        if (heads.size != 1) return invalid()
        val result = mutableListOf<Membership>()
        val visited = hashSetOf<Long>()
        var current: Membership? = heads.single()
        while (current != null && visited.add(current.id)) { result += current; current = byId[current.next] }
        return if (result.size == rows.size && current == null) OrderedMembers(result, null) else invalid()
    }
}
object MembershipResolver {
    fun local(rows: List<Membership>, uuid: String?): List<Membership> = if (uuid.isNullOrEmpty()) emptyList() else rows.filter { it.databaseUuid == uuid }
}
