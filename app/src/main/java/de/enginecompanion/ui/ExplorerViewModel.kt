package de.enginecompanion.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.enginecompanion.data.*
import de.enginecompanion.storage.SnapshotImporter
import de.enginecompanion.network.EngineXClient
import de.enginecompanion.network.EngineXDrive
import de.enginecompanion.network.EngineXSecretStore
import de.enginecompanion.network.EngineXSnapshot
import de.enginecompanion.network.EngineXSnapshotCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ExplorerState(
    val library: LibraryRepository.Library? = null, val tracks: List<Track> = emptyList(),
    val busy: Boolean = false, val progress: String = "", val error: String? = null,
    val sourceStatus: String = "SSD-Ordner noch nicht freigegeben", val warning: String? = null,
    val usbStorageConnected: Boolean = false, val usbDeviceCount: Int = 0, val hasSavedSource: Boolean = false,
    val engineXHost: String = "", val engineXStatus: String = "Nicht verbunden",
    val engineXDrives: List<EngineXDrive> = emptyList(), val engineXBusy: Boolean = false,
    val engineXConfigured: Boolean = false,
    val remoteLibrary: EngineXSnapshot? = null, val remoteActive: Boolean = false,
    val query: String = "", val sort: String = "Titel", val playlist: Long? = null,
    val limit: Int = 200, val totalMatches: Int = 0, val details: String? = null, val detailTitle: String = "", val querying: Boolean = false
)
class ExplorerViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = LibraryRepository(app)
    private val importer = SnapshotImporter(app)
    private val mutex = Mutex()
    private val preferences = app.getSharedPreferences("source", 0)
    private val usbMonitor = UsbStorageMonitor(app)
    private val engineXClient = EngineXClient()
    private val engineXSecrets = EngineXSecretStore(app)
    private val engineXCache = EngineXSnapshotCache(app)
    private val mutable = MutableStateFlow(ExplorerState(busy = true, progress = "Lokale Bibliothek laden …"))
    val state = mutable.asStateFlow()
    private var importJob: Job? = null
    private var queryJob: Job? = null
    init {
        mutable.update { it.copy(engineXHost = engineXSecrets.host(), engineXConfigured = engineXSecrets.accessCode() != null, remoteLibrary = engineXCache.load()) }
        viewModelScope.launch {
            usbMonitor.state.collect { usb ->
                mutable.update { it.copy(usbStorageConnected = usb.connected, usbDeviceCount = usb.deviceCount) }
            }
        }
        viewModelScope.launch {
        try { val saved = withContext(Dispatchers.IO) { mutex.withLock { repository.loadSaved() } }; mutable.update { it.copy(library = saved, remoteActive = saved == null && it.remoteLibrary != null) } }
        catch (e: Exception) { fail(e) }
        finally { mutable.update { it.copy(busy = false) }; refresh() }
        checkSource()
        engineXSecrets.accessCode()?.let { connectEngineX(engineXSecrets.host(), it, remember = false) }
        }
    }

    override fun onCleared() {
        usbMonitor.close()
        super.onCleared()
    }

    fun refreshUsb() { usbMonitor.refresh() }

    fun connectEngineX(host: String, accessCode: String, remember: Boolean = true) {
        if (state.value.engineXBusy) return
        mutable.update { it.copy(engineXBusy = true, engineXStatus = "EngineX wird geprüft …", error = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { engineXClient.connect(host, accessCode) }
                if (remember) engineXSecrets.save(host, accessCode)
                mutable.update { it.copy(
                    engineXHost = result.baseUrl,
                    engineXStatus = "Verbunden · ${result.drives.size} beschreibbare Laufwerke",
                    engineXDrives = result.drives,
                    engineXConfigured = remember || it.engineXConfigured
                ) }
            } catch (e: Exception) {
                mutable.update { it.copy(
                    engineXStatus = when (e) {
                        is SecurityException -> e.message ?: "Zugriffscode abgelehnt."
                        is IllegalArgumentException -> e.message ?: "Ungültige Prime-Adresse."
                        else -> "EngineX nicht erreichbar: ${e.message ?: e.javaClass.simpleName}"
                    },
                    engineXDrives = emptyList()
                ) }
            } finally { mutable.update { it.copy(engineXBusy = false) } }
        }
    }

    fun checkEngineX() {
        val code = engineXSecrets.accessCode()
        if (code == null) mutable.update { it.copy(engineXStatus = "Zugriffscode fehlt.") }
        else connectEngineX(engineXSecrets.host(), code, remember = false)
    }

    fun forgetEngineX() {
        engineXSecrets.clear()
        mutable.update { it.copy(engineXConfigured = false, engineXDrives = emptyList(), engineXStatus = "Nicht verbunden") }
    }

    fun syncEngineX(drive: String = "Engine") {
        val code = engineXSecrets.accessCode()
        if (code == null) {
            mutable.update { it.copy(engineXStatus = "Zuerst EngineX verbinden und Zugangscode speichern.") }
            return
        }
        if (state.value.engineXBusy) return
        queryJob?.cancel()
        mutable.update { it.copy(engineXBusy = true, engineXStatus = "Library $drive wird schreibgeschützt gelesen …", error = null) }
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { engineXClient.snapshot(engineXSecrets.host(), code, drive).also(engineXCache::save) }
                mutable.update { it.copy(
                    remoteLibrary = snapshot, remoteActive = true, playlist = null, limit = 200,
                    engineXStatus = "Library synchronisiert · ${snapshot.tracks.size} Tracks · ${snapshot.playlists.size} Playlists"
                ) }
                refresh()
            } catch (e: Exception) {
                mutable.update { it.copy(engineXStatus = e.message ?: "EngineX-Library konnte nicht gelesen werden.") }
            } finally { mutable.update { it.copy(engineXBusy = false) } }
        }
    }

    fun useLocalLibrary() { mutable.update { it.copy(remoteActive = false, playlist = null, limit = 200) }; refresh() }
    fun useRemoteLibrary() {
        if (state.value.remoteLibrary != null) { mutable.update { it.copy(remoteActive = true, playlist = null, limit = 200) }; refresh() }
    }
    private fun fail(e: Exception) {
        if (e is CancellationException) throw e
        mutable.update { it.copy(error = when (e) {
            is SecurityException -> "Ordnerberechtigung fehlt oder wurde entzogen. SSD-Ordner erneut auswählen."
            is java.io.IOException -> "${e.message}. Verbindung und freien internen Speicher prüfen."
            else -> "${e.javaClass.simpleName}: ${e.message ?: "Unbekannter Fehler"}"
        }) }
    }
    fun import(uri: Uri, flags: Int) {
        if (state.value.busy) return
        queryJob?.cancel()
        var note: String? = null
        try {
            if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                preferences.edit().putString("uri", uri.toString()).apply()
                mutable.update { it.copy(hasSavedSource = true) }
            } else note = "Provider gewährt nur Zugriff für diese Sitzung."
        } catch (_: SecurityException) { note = "Dauerhafte Freigabe nicht möglich; erneute Auswahl kann nötig sein." }
        mutable.update { it.copy(busy = true, querying = false, error = null, warning = note, progress = "Import starten …", details = null) }
        importJob = viewModelScope.launch {
            var stage: java.io.File? = null
            try {
                val library = withContext(Dispatchers.IO) { mutex.withLock {
                    val snapshot = importer.import(uri) { message -> mutable.update { it.copy(progress = message) } }
                    stage = snapshot.directory
                    mutable.update { it.copy(progress = "SQLite prüfen und Schema analysieren …") }
                    repository.prepare(snapshot).also { ensureActive() }
                } }
                ensureActive()
                withContext(NonCancellable + Dispatchers.IO) { mutex.withLock {
                    repository.activate(library); stage = null
                    mutable.update { it.copy(library = library, tracks = emptyList(), playlist = null, limit = 200, sourceStatus = "Quelle beim Import lesbar · lokale Momentaufnahme", warning = note) }
                } }
            } catch (e: CancellationException) { mutable.update { it.copy(progress = "Import abgebrochen") }; throw e }
            catch (e: Exception) { fail(e) }
            finally { withContext(NonCancellable + Dispatchers.IO) { stage?.deleteRecursively() }; mutable.update { it.copy(busy = false) }; refresh() }
        }
    }
    fun cancelImport() { importJob?.cancel() }
    fun checkSource() { viewModelScope.launch {
        val uri = preferences.getString("uri", null)
        val readable = withContext(Dispatchers.IO) { runCatching { uri != null && DocumentFile.fromTreeUri(getApplication(), Uri.parse(uri))?.canRead() == true }.getOrDefault(false) }
        mutable.update { it.copy(
            hasSavedSource = uri != null,
            sourceStatus = if (readable) "Ordnerfreigabe erreichbar · Datenquelle kann geprüft werden"
            else if (uri == null) "Noch keine Ordnerfreigabe · USB-SSD unten auswählen"
            else "Gespeicherte Ordnerfreigabe nicht erreichbar · SSD erneut auswählen"
        ) }
    } }
    fun query(value: String) { mutable.update { it.copy(query = value, limit = 200) }; refresh(true) }
    fun sort(value: String) { mutable.update { it.copy(sort = value, limit = 200) }; refresh() }
    fun playlist(id: Long?) { mutable.update { it.copy(playlist = id, sort = if (id == null) "Titel" else "Playlist", limit = 200) }; refresh() }
    fun more() { mutable.update { it.copy(limit = (it.limit + 200).coerceAtMost(100000)) }; refresh() }
    fun closeDetails() { mutable.update { it.copy(details = null) } }
    fun details(track: Track) { viewModelScope.launch {
        try {
            if (state.value.remoteActive) {
                val remote = state.value.remoteLibrary ?: return@launch
                val playlistNames = remote.playlistTracks.filterValues { track.id in it }.keys.mapNotNull { id -> remote.playlists.find { it.id == id }?.title }
                val seconds = track.seconds?.toLong()?.takeIf { it >= 0 }?.let { "%d:%02d".format(it / 60, it % 60) } ?: "—"
                mutable.update { it.copy(details = "EngineX WLAN · offline verfügbar\n\nTrack-ID: ${track.id}\nTitel: ${track.title}\nInterpret: ${track.artist ?: "—"}\nAlbum: ${track.album ?: "—"}\nDauer: $seconds\nBPM: ${track.bpm ?: "—"}\nTonart: ${MetadataDisplay.keyWithName(track.key)} (Rohwert ${track.key ?: "—"})\nBewertung: ${MetadataDisplay.rating(track.rating)}\n\nPlaylists (${playlistNames.size}):\n${playlistNames.ifEmpty { listOf("—") }.joinToString("\n")}", detailTitle = track.title) }
                return@launch
            }
            val library = state.value.library ?: return@launch
            val result = withContext(Dispatchers.IO) { mutex.withLock { if (state.value.library != library) null else repository.details(library, track) } }
            if (result != null && state.value.library == library) mutable.update { it.copy(details = result, detailTitle = track.title) }
        } catch (e: Exception) { fail(e) }
    } }
    private fun refresh(debounce: Boolean = false) {
        queryJob?.cancel()
        if (state.value.busy) return
        queryJob = viewModelScope.launch {
            if (debounce) delay(250)
            val snapshot = state.value
            if (snapshot.remoteActive) {
                val remote = snapshot.remoteLibrary ?: return@launch
                val query = snapshot.query.trim().lowercase()
                var rows = remote.tracks
                snapshot.playlist?.let { list ->
                    val order = remote.playlistTracks[list].orEmpty()
                    val map = rows.associateBy(Track::id)
                    rows = order.mapNotNull(map::get)
                }
                if (query.isNotEmpty()) rows = rows.filter { listOfNotNull(it.title, it.artist, it.album).any { value -> query in value.lowercase() } }
                rows = when (snapshot.sort) {
                    "Playlist" -> rows
                    "Interpret" -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist.orEmpty() })
                    "BPM" -> rows.sortedBy { it.bpm ?: Double.MAX_VALUE }
                    else -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                }
                mutable.update { it.copy(tracks = rows.take(snapshot.limit), totalMatches = rows.size, querying = false) }
                return@launch
            }
            val library = snapshot.library ?: return@launch
            mutable.update { it.copy(querying = true) }
            try {
                val page = withContext(Dispatchers.IO) { mutex.withLock { repository.tracks(library, snapshot.playlist, snapshot.query, snapshot.sort, snapshot.limit) } }
                ensureActive(); mutable.update { it.copy(tracks = page.tracks, totalMatches = page.total, warning = page.warning) }
            } catch (e: Exception) { fail(e) }
            finally { mutable.update { it.copy(querying = false) } }
        }
    }
}
