package de.enginecompanion
import android.database.sqlite.SQLiteDatabase
import de.enginecompanion.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
/** Synthetic fixtures test behavior, not Prime compatibility. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DatabaseTest {
    private fun fixture() = SQLiteDatabase.create(null).apply {
        execSQL("CREATE TABLE Information(uuid TEXT,schemaVersionMajor INTEGER,schemaVersionMinor INTEGER,schemaVersionPatch INTEGER)")
        execSQL("INSERT INTO Information VALUES('local',3,0,0)")
        execSQL("CREATE TABLE Track(id INTEGER PRIMARY KEY,title TEXT,artist TEXT,album TEXT,length INTEGER,bpmAnalyzed REAL,rating INTEGER)")
        execSQL("CREATE TABLE Playlist(id INTEGER PRIMARY KEY,title TEXT,parentListId INTEGER,nextListId INTEGER)")
        execSQL("CREATE TABLE PlaylistEntity(id INTEGER PRIMARY KEY,listId INTEGER,trackId INTEGER,databaseUuid TEXT,nextEntityId INTEGER,FOREIGN KEY(listId) REFERENCES Playlist(id))")
        execSQL("INSERT INTO Track VALUES(9,'100%_mix','Artist','Album',120,123.5,80)")
        execSQL("INSERT INTO Track VALUES(4,'100XXmix','Other','Album',60,99,0)")
        execSQL("INSERT INTO Playlist VALUES(1,'Test',0,0)")
        execSQL("INSERT INTO PlaylistEntity VALUES(1,1,9,'local',0)")
    }
    @Test fun introspectsActualColumnsAndForeignKeys() { fixture().use { db ->
        val s=SchemaInspector.inspect(db);assertEquals("3.0.0",s.version);assertTrue(s.has("Track","title"));assertFalse(s.has("Track","invented"));assertTrue(s.report.contains("FK listId -> Playlist.id"));assertNotNull(AdapterRegistry.resolve(s))
    } }
    @Test fun searchTreatsPercentAndUnderscoreLiterally() { fixture().use { db ->
        val rows=Engine3Adapter().tracks(db,SchemaInspector.inspect(db),"%_","Titel",200,null)
        assertEquals(listOf(9L),rows.map{it.id});assertEquals(123.5,rows.single().bpm!!,0.001)
    } }
    @Test fun missingOptionalColumnDoesNotCrash() { fixture().use { db ->
        val rows=Engine3Adapter().tracks(db,SchemaInspector.inspect(db),"","Titel",200,null);assertEquals(2,rows.size);assertNull(rows.first().key)
    } }
    @Test fun membershipUsesStoredTrackId() { fixture().use { db -> assertEquals(9L,Engine3Adapter().members(db,1).members.single().trackId);assertEquals("local",Engine3Adapter().members(db,1).members.single().databaseUuid) } }
    @Test fun missingRequiredColumnRejectsAdapter() { fixture().use { db -> db.execSQL("ALTER TABLE Track RENAME COLUMN title TO unexpected");assertNull(AdapterRegistry.resolve(SchemaInspector.inspect(db))) } }
    @Test fun queryOnlyPreventsAccidentalMutation() { fixture().use { db -> db.execSQL("PRAGMA query_only=ON");assertThrows(android.database.sqlite.SQLiteException::class.java) {db.execSQL("DELETE FROM Track")} } }
}
