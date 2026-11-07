package de.enginecompanion.network

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class EngineXSnapshotCache(context: Context) {
    private val file = File(context.filesDir, "enginex/library-snapshot.json")
    fun load(): EngineXSnapshot? = runCatching { if (file.isFile) EngineXClient.parseSnapshot(file.readText()) else null }.getOrNull()
    fun save(snapshot: EngineXSnapshot) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.new")
        temporary.writeText(snapshot.rawJson)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
