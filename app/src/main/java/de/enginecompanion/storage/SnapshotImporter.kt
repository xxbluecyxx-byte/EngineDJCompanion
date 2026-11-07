package de.enginecompanion.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class ImportFailure(message: String) : IOException(message)
data class Snapshot(val directory: File, val manifest: String)
/** External storage is exclusively read. SQLite only opens private copies. */
class SnapshotImporter(private val context: Context) {
    private fun children(dir: DocumentFile): List<DocumentFile> {
        if (!dir.exists() || !dir.canRead()) throw ImportFailure("Ordner nicht lesbar. SSD anschließen und Freigabe erneuern.")
        return dir.listFiles().toList()
    }
    private fun findDb(root: DocumentFile): DocumentFile {
        val direct = children(root)
        if (direct.any { it.name == "m.db" && it.isFile }) return root
        val engine = direct.firstOrNull { it.name == "Engine Library" && it.isDirectory } ?: root
        val database = children(engine).firstOrNull { it.name == "Database2" && it.isDirectory }
        if (database != null && children(database).any { it.name == "m.db" }) return database
        throw ImportFailure("Keine Engine Library/Database2/m.db gefunden. SSD-Wurzel, Engine Library oder vollständigen Database2-Backupordner auswählen.")
    }
    private fun inventory(dir: DocumentFile): Map<String, DocumentFile> {
        val files = children(dir).filter { it.isFile && it.name?.let { n -> n.endsWith(".db") || n.endsWith(".db-wal") || n.endsWith(".db-journal") } == true }
        val result = files.associateBy { requireNotNull(it.name) }
        if (result.size != files.size || "m.db" !in result) throw ImportFailure("Dateiliste unvollständig oder doppelte Dateinamen.")
        result.keys.forEach { if (it.contains('/') || it.contains('\\') || it == "..") throw ImportFailure("Ungültiger Dateiname.") }
        if (result.any { it.key.endsWith("-journal") && it.value.length() > 0 }) throw ImportFailure("Nichtleeres Rollback-Journal. Bitte einen sauber geschlossenen vollständigen Datenbankstand verwenden. Keine Journaldatei manuell löschen.")
        if (result.any { it.key.endsWith("-wal") && it.key.removeSuffix("-wal") !in result }) throw ImportFailure("WAL ohne zugehörige Datenbank gefunden.")
        return result.toSortedMap()
    }
    suspend fun import(tree: Uri, progress: (String) -> Unit): Snapshot {
        val root = DocumentFile.fromTreeUri(context, tree) ?: throw ImportFailure("Ungültige Ordnerfreigabe.")
        progress("Engine-Bibliothek suchen …")
        val source = findDb(root)
        val files = inventory(source)
        val base = File(context.filesDir, "snapshots").apply { mkdirs() }
        val stage = File(base, "import-${UUID.randomUUID()}").apply { mkdirs() }
        val raw = File(stage, "original").apply { mkdirs() }
        try {
            val hashes = linkedMapOf<String, String>()
            var bytes = 0L
            for ((name, document) in files) {
                progress("Kopiere $name (${hashes.size + 1}/${files.size}) …")
                hashes[name] = digest(document, File(raw, name)) { count ->
                    bytes += count
                    if (bytes > 4L * 1024 * 1024 * 1024) throw ImportFailure("Import größer als 4 GiB.")
                    if (stage.usableSpace < 64L * 1024 * 1024) throw ImportFailure("Zu wenig interner Speicher.")
                }
            }
            if (inventory(source).keys != files.keys) throw ImportFailure("Dateiliste hat sich geändert. Import wiederholen.")
            for ((name, document) in inventory(source)) {
                progress("Prüfe unveränderte Quelle: $name …")
                if (digest(document, null) {} != hashes[name]) throw ImportFailure("$name wurde während des Imports verändert. Import verworfen.")
            }
            if (inventory(source).keys != files.keys) throw ImportFailure("Quelle wurde während der Prüfung verändert.")
            val manifest = buildString {
                appendLine("Import UTC: ${java.time.Instant.now()}")
                appendLine("Zwei vollständige Lesevorgänge mit SHA-256; keine atomare SAF-Sperre.")
                appendLine("SHM wird lokal neu aufgebaut. Quelle ausschließlich gelesen.")
                hashes.forEach { (name, hash) -> appendLine("$name | ${File(raw, name).length()} Bytes | SHA256 $hash") }
            }
            File(stage, "manifest.txt").writeText(manifest)
            val work = File(stage, "working").apply { mkdirs() }
            raw.listFiles().orEmpty().forEach { currentCoroutineContext().ensureActive(); it.copyTo(File(work, it.name)) }
            return Snapshot(stage, manifest)
        } catch (error: Throwable) { stage.deleteRecursively(); throw error }
    }
    private suspend fun digest(document: DocumentFile, destination: File?, counted: (Long) -> Unit): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(document.uri) ?: throw ImportFailure("Nicht lesbar: ${document.name}")
        input.use { stream ->
            val output = destination?.outputStream()
            try {
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = stream.read(buffer)
                    if (n < 0) break
                    counted(n.toLong()); hash.update(buffer, 0, n); output?.write(buffer, 0, n)
                }
                output?.fd?.sync()
            } finally { output?.close() }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
