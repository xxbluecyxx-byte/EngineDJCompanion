package de.enginecompanion
import android.database.sqlite.SQLiteDatabase
import de.enginecompanion.data.LibraryRepository
import de.enginecompanion.storage.Snapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.security.MessageDigest
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SnapshotDatabaseTest {
    @Test fun walIsRecoveredOnlyInPrivateWorkingCopy() {
        val context=RuntimeEnvironment.getApplication()
        val root=File(context.cacheDir,"wal-test").apply{mkdirs()}
        val source=File(root,"source").apply{mkdirs()};val imported=File(root,"import").apply{mkdirs()}
        val raw=File(imported,"original").apply{mkdirs()};val work=File(imported,"working").apply{mkdirs()}
        SQLiteDatabase.openOrCreateDatabase(File(source,"m.db"),null).use { db ->
            db.enableWriteAheadLogging();db.execSQL("CREATE TABLE Marker(id INTEGER PRIMARY KEY,value TEXT)");db.execSQL("INSERT INTO Marker VALUES(1,'committed in WAL')")
            assertTrue(File(source,"m.db-wal").length()>0)
            val files=source.listFiles()!!.filter{it.name=="m.db"||it.name=="m.db-wal"}
            val before=files.associate{it.name to hash(it)}
            files.forEach{it.copyTo(File(raw,it.name));it.copyTo(File(work,it.name))}
            File(imported,"manifest.txt").writeText("Synthetic WAL test")
            val result=LibraryRepository(context).prepare(Snapshot(imported,"test"));assertNull(result.adapter)
            SQLiteDatabase.openDatabase(File(work,"m.db").path,null,SQLiteDatabase.OPEN_READONLY).use { local -> local.rawQuery("SELECT value FROM Marker",null).use {c->assertTrue(c.moveToFirst());assertEquals("committed in WAL",c.getString(0))} }
            assertEquals(before,files.associate{it.name to hash(it)});assertEquals(before,raw.listFiles()!!.associate{it.name to hash(it)})
        }
        root.deleteRecursively()
    }
    private fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
}
