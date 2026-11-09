@file:OptIn(UnstableApi::class)
package de.enginecompanion

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.common.util.UnstableApi
import de.enginecompanion.data.*
import de.enginecompanion.network.EngineXTrackMedia
import de.enginecompanion.network.EngineXClient
import de.enginecompanion.network.TrackMetadata
import de.enginecompanion.ui.ExplorerViewModel
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    private var refreshUsb: (() -> Unit)? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF6BE2CB), background = Color(0xFF101318), surface = Color(0xFF191E25), secondary = Color(0xFFAFA2FF))) { Explorer(onUsbRefreshAvailable = { refreshUsb = it }) } }
    }
    override fun onResume() { super.onResume(); refreshUsb?.invoke() }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Explorer(onUsbRefreshAvailable: (() -> Unit) -> Unit, vm: ExplorerViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val shownPlaylists = if (state.remoteActive) state.remoteLibrary?.playlists.orEmpty() else state.library?.playlists.orEmpty()
    val expandedPlaylists = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf<Long>() }
    var playlistSearch by rememberSaveable { mutableStateOf("") }
    val directPlaylistTracks = if (state.remoteActive) state.remoteLibrary?.playlistTracks.orEmpty() else state.library?.playlistTracks.orEmpty()
    val trackCounts = remember(shownPlaylists, directPlaylistTracks) {
        PlaylistCounts.recursive(shownPlaylists, directPlaylistTracks)
    }
    val playlistRows = remember(shownPlaylists, expandedPlaylists.toList(), playlistSearch) {
        val expanded = expandedPlaylists.toSet()
        val base = PlaylistTree.flatten(shownPlaylists, expanded)
        if (playlistSearch.isBlank()) base
        else {
            val q = playlistSearch.trim().lowercase()
            val matching = base.filter { it.playlist.title.lowercase().contains(q) }.map { it.playlist.id }.toSet()
            val ancestors = mutableSetOf<Long>()
            fun addAncestors(id: Long?) {
                if (id == null || id == 0L) return
                ancestors += id
                shownPlaylists.find { it.id == id }?.parent?.let { addAncestors(it) }
            }
            matching.forEach { addAncestors(it) }
            PlaylistTree.flatten(shownPlaylists, expanded + ancestors).filter { row ->
                row.playlist.id in matching || row.playlist.id in ancestors || row.hasChildren
            }
        }
    }
    val hasShownLibrary = if (state.remoteActive) state.remoteLibrary != null else state.library != null
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var exportText by remember { mutableStateOf("") }
    var showEngineX by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var playlistDialog by remember { mutableStateOf<String?>(null) }
    var editedPlaylistId by remember { mutableStateOf<Long?>(null) }
    var playlistTitle by remember { mutableStateOf("") }
    var playlistParent by remember { mutableStateOf(0L) }
    var trackEditorId by remember { mutableStateOf<Long?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm) { onUsbRefreshAvailable(vm::refreshUsb) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { data -> data.data?.let { vm.import(it, data.flags) } }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { (context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("Exportziel nicht geöffnet")).bufferedWriter().use { it.write(exportText) } }
                exportError = null
            } catch (e: Exception) { if (e is CancellationException) throw e; exportError = "Export fehlgeschlagen: ${e.message}" }
        }
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("ENGINE COMPANION", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Deine Library. Mobil.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Fertig" else "Einstellungen") }
            }
            Text("PHASE 1  /  NUR LESEN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(12.dp))
            if (!showSettings) {
                Surface(onClick = { showSettings = true }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        if (state.remoteActive && state.remoteLibrary != null) "EngineX WLAN · ${state.remoteLibrary!!.tracks.size} Tracks · offline verfügbar"
                        else if (state.library != null) "USB-Momentaufnahme · offline verfügbar"
                        else "Keine Library · Quelle einrichten",
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (showSettings) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        if (state.usbStorageConnected) "USB-Speicher erkannt (${state.usbDeviceCount})"
                        else "Kein USB-Massenspeicher erkannt",
                        color = if (state.usbStorageConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(state.sourceStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    if (state.usbStorageConnected && !state.hasSavedSource) Text(
                        "Android erkennt die SSD. Tippe jetzt auf Ordner freigeben und wähle die SSD-Wurzel oder Engine Library.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
                        }, enabled = !state.busy) { Text(if (state.usbStorageConnected) "USB-Ordner freigeben" else "SSD / Backup wählen") }
                        TextButton(onClick = vm::checkSource, enabled = !state.busy && state.hasSavedSource) { Text("Freigabe prüfen") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("EngineX über WLAN", color = if (state.engineXDrives.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    Text(state.engineXStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    state.engineXDrives.forEach { drive ->
                        Text("${drive.name} · ${drive.kind} · ${bytes(drive.free)} frei von ${bytes(drive.total)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (state.engineXConfigured) vm.checkEngineX() else showEngineX = true }, enabled = !state.engineXBusy) { Text(if (state.engineXConfigured) "EngineX prüfen" else "EngineX verbinden") }
                        if (state.engineXConfigured) TextButton(onClick = { showEngineX = true }, enabled = !state.engineXBusy) { Text("Zugang ändern") }
                    }
                    if (state.engineXDrives.any { it.name == "Engine" }) {
                        OutlinedButton(onClick = { vm.syncEngineX("Engine") }, enabled = !state.engineXBusy, modifier = Modifier.padding(top = 4.dp)) { Text("Library über WLAN aktualisieren") }
                    }
                    if (state.engineXConfigured) TextButton(onClick = vm::forgetEngineX, enabled = !state.engineXBusy) { Text("Gespeicherten Zugang löschen") }
                    state.remoteLibrary?.let { snapshot -> Text(
                        "Offline gespeichert: ${snapshot.generatedAt} · ${snapshot.tracks.size} Tracks",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    ) }
                    if (state.engineXBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                Text(state.progress, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::cancelImport) { Text("Import abbrechen") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            state.warning?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Tracks", "Playlists", "Diagnose").forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }) }
            }
            if (state.library != null && state.remoteLibrary != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                FilterChip(selected = !state.remoteActive, onClick = vm::useLocalLibrary, label = { Text("USB-Momentaufnahme") })
                FilterChip(selected = state.remoteActive, onClick = vm::useRemoteLibrary, label = { Text("EngineX WLAN") })
            }
            when (tab) {
                0 -> {
                    if (!hasShownLibrary) {
                        Column(Modifier.padding(vertical = 26.dp)) {
                            Text("Deine erste Playlist", style = MaterialTheme.typography.titleLarge)
                            Text("Prime in den Computer-Modus versetzen, SSD verbinden und den Ordner freigeben. Ein vollständiger Database2-Backupordner funktioniert ebenfalls.", modifier = Modifier.padding(top = 12.dp))
                        }
                    } else if (!state.remoteActive && state.library?.adapter == null) {
                        Text("Schema ${state.library?.schema?.version ?: "unbekannt"} wird noch nicht unterstützt. Bitte Diagnose exportieren und echte Datenbankdateien zur Validierung bereitstellen.", Modifier.padding(16.dp))
                    } else {
                        state.playlist?.let { selected -> Row {
                            Text(shownPlaylists.find { it.id == selected }?.title.orEmpty(), modifier = Modifier.weight(1f).padding(top = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { vm.playlist(null) }, enabled = !state.busy) { Text("Alle Tracks") }
                        } }
                        OutlinedTextField(value = state.query, onValueChange = vm::query, label = { Text("Titel, Interpret oder Album suchen") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (if (state.playlist != null) listOf("Playlist", "Titel", "Interpret", "BPM") else listOf("Titel", "Interpret", "BPM")).forEach { sort -> FilterChip(selected = state.sort == sort, onClick = { vm.sort(sort) }, enabled = !state.busy, label = { Text(sort) }) }
                        }
                        if (state.querying) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("${state.tracks.size} von ${state.totalMatches} angezeigt · ${if (state.remoteActive) "EngineX-WLAN-Momentaufnahme" else "lokale USB-Momentaufnahme"}", style = MaterialTheme.typography.labelSmall)
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                            items(state.tracks, key = { it.id }) { track -> TrackCard(track, !state.busy) { vm.details(track) } }
                            if (state.tracks.isEmpty() && !state.querying) item { Text("Keine passenden Tracks. Bei Playlists ggf. externe Referenzen oder Diagnose prüfen.", Modifier.padding(16.dp)) }
                            if (state.tracks.size < state.totalMatches && state.limit < 100000) item { OutlinedButton(onClick = vm::more, enabled = !state.busy) { Text("Weitere 200 laden") } }
                            if (state.limit == 100000) item { Text("Sicherheitsgrenze: 100.000 Ergebnisse. Suche eingrenzen.") }
                        }
                    }
                }
                1 -> Column(Modifier.weight(1f)) {
                    if (state.remoteActive) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { playlistDialog = "create"; playlistTitle = ""; playlistParent = 0L }, enabled = !state.engineXBusy) { Text("Neue Playlist") }
                        OutlinedButton(onClick = { playlistDialog = "create"; playlistTitle = ""; playlistParent = shownPlaylists.firstOrNull()?.id ?: 0L }, enabled = !state.engineXBusy && shownPlaylists.isNotEmpty()) { Text("Neuer Unterordner") }
                    }
                    OutlinedTextField(
                        value = playlistSearch,
                        onValueChange = { playlistSearch = it },
                        label = { Text("Playlists und Ordner suchen") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
                    )
                    LazyColumn(contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(playlistRows, key = { it.playlist.id }) { row ->
                            val playlist = row.playlist
                            val directCount = directPlaylistTracks[playlist.id]?.size
                            val totalCount = trackCounts[playlist.id] ?: directCount
                            val isFolder = row.hasChildren
                            val expanded = playlist.id in expandedPlaylists
                            val containerColor = if (isFolder) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                            Card(
                                onClick = {
                                    if (isFolder) {
                                        if (expanded) expandedPlaylists.remove(playlist.id) else expandedPlaylists.add(playlist.id)
                                    } else {
                                        vm.playlist(playlist.id); tab = 0
                                    }
                                },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().padding(start = (row.depth * 16).dp),
                                colors = CardDefaults.cardColors(containerColor = containerColor)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${if (isFolder) if (expanded) "▼" else "▶" else "●"}  ${playlist.title.ifBlank { "Unbenannt" }}",
                                            fontWeight = if (isFolder) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val subtitle = when {
                                            isFolder && totalCount != null && totalCount > 0 -> "Ordner · $totalCount Tracks"
                                            isFolder -> "Ordner"
                                            directCount != null -> "Playlist · $directCount Tracks"
                                            else -> "Playlist"
                                        }
                                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (state.remoteActive) Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                        TextButton(onClick = { trackEditorId = playlist.id }) { Text("Tracks") }
                                        TextButton(onClick = { editedPlaylistId = playlist.id; playlistTitle = playlist.title; playlistParent = playlist.parent ?: 0L; playlistDialog = "rename" }) { Text("Umbenennen") }
                                    } else if (isFolder && (directCount ?: 0) > 0) TextButton(onClick = { vm.playlist(playlist.id); tab = 0 }) { Text("Öffnen") }
                                }
                            }
                        }
                        if (shownPlaylists.isEmpty()) item { Text("Noch keine auslesbaren Playlists importiert.", Modifier.padding(16.dp)) }
                        else if (playlistRows.isEmpty() && playlistSearch.isNotBlank()) item { Text("Keine Playlists oder Ordner passen zur Suche.", Modifier.padding(16.dp)) }
                    }
                }
                2 -> {
                    Text("Schema, Primärschlüssel, Fremdschlüssel und Datei-Hashes. Der Export enthält keine Tracklisten, kann aber Dateinamen enthalten.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    OutlinedButton(onClick = { exportText = state.library?.diagnostics.orEmpty(); exporter.launch("engine-schema-diagnose.txt") }, enabled = state.library != null) { Text("Diagnose exportieren") }
                    exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Text(state.library?.diagnostics ?: "Nach dem Import erscheint hier der tatsächliche SQLite-Schemabericht.", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    if (playlistDialog == "create" || playlistDialog == "rename") {
        val rename = playlistDialog == "rename"
        AlertDialog(onDismissRequest = { playlistDialog = null }, title = { Text(if (rename) "Playlist umbenennen" else "Playlist erstellen") },
            text = { Column {
                OutlinedTextField(playlistTitle, { playlistTitle = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!rename) {
                    Text("Ablage", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(selected = playlistParent == 0L, onClick = { playlistParent = 0L }, label = { Text("Hauptebene") })
                        shownPlaylists.forEach { parent -> Spacer(Modifier.width(6.dp)); FilterChip(selected = playlistParent == parent.id, onClick = { playlistParent = parent.id }, label = { Text(parent.title) }) }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = {
                vm.mutatePlaylist(if (rename) "rename" else "create", editedPlaylistId ?: 0L, playlistTitle, playlistParent) { ok -> if (ok) playlistDialog = null }
            }, enabled = playlistTitle.isNotBlank() && !state.engineXBusy) { Text("Speichern") } },
            dismissButton = { Row { if (rename) TextButton(onClick = { playlistDialog = "delete" }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = { playlistDialog = null }) { Text("Abbrechen") } } })
    }
    if (playlistDialog == "delete") {
        val selected = editedPlaylistId?.let { id -> shownPlaylists.find { it.id == id } }
        AlertDialog(onDismissRequest = { playlistDialog = null }, title = { Text("Playlist löschen?") },
            text = { Text("${selected?.title.orEmpty()} wird aus der Library entfernt. Audiodateien bleiben erhalten. Ordner mit Unterordnern werden abgelehnt.") },
            confirmButton = { TextButton(onClick = { selected?.let { vm.mutatePlaylist("delete", it.id) { ok -> if (ok) playlistDialog = null } } }, enabled = !state.engineXBusy) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { playlistDialog = null }) { Text("Abbrechen") } })
    }
    trackEditorId?.let { id ->
        val playlist = shownPlaylists.find { it.id == id }
        val allTracks = state.remoteLibrary?.tracks.orEmpty()
        val initialIds = directPlaylistTracks[id].orEmpty()
        PlaylistTrackEditor(playlist?.title.orEmpty(), allTracks, initialIds, state.engineXBusy,
            onDismiss = { trackEditorId = null }, onSave = { ids -> vm.mutatePlaylist("replaceTracks", id, trackIds = ids) { ok -> if (ok) trackEditorId = null } })
    }
    if (showEngineX) {
        var host by rememberSaveable { mutableStateOf(state.engineXHost.ifBlank { "192.168.178.67" }) }
        var code by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showEngineX = false },
            title = { Text("EngineX verbinden") },
            text = { Column {
                Text("Adresse und Zugriffscode stehen am Prime unter Einstellungen → Über. Die Verbindung bleibt im privaten WLAN und nutzt die EngineX-API auf Port 8080.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(host, { host = it }, label = { Text("Prime-IP oder sc5000.lan") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                OutlinedTextField(code, { code = it }, label = { Text("EngineX-Zugriffscode") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } },
            confirmButton = { TextButton(onClick = { vm.connectEngineX(host, code); showEngineX = false }, enabled = host.isNotBlank() && code.isNotBlank()) { Text("Verbinden") } },
            dismissButton = { TextButton(onClick = { showEngineX = false }) { Text("Abbrechen") } }
        )
    }
    state.details?.let { details ->
        Dialog(onDismissRequest = vm::closeDetails, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text(state.detailTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { TextButton(onClick = vm::closeDetails) { Text("Zurück") } },
                        actions = { TextButton(onClick = {
                            exportText = details
                            exporter.launch("engine-track-${state.detailTitle.take(32).replace(Regex("[^A-Za-z0-9._-]"), "_")}.txt")
                        }) { Text("Export") } }
                    )
                }) { detailPadding ->
                    Column(Modifier.fillMaxSize().padding(detailPadding).verticalScroll(rememberScrollState()).padding(18.dp)) {
                        if (state.detailBusy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Performance-Daten vom Prime laden …", modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.detailMedia?.let { media ->
                            WlanPlayer(media, state.detailTrack, state.detailAnalysis, state.detailWaveform, state.detailWaveformBusy, vm::refreshRemoteTracks)
                            HorizontalDivider(Modifier.padding(vertical = 20.dp))
                        }
                        state.detailAnalysis?.let { analysis ->
                            AnalysisOverview(analysis)
                            HorizontalDivider(Modifier.padding(vertical = 20.dp))
                            Text("Technischer Bericht", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                        }
                        SelectionContainer { Text(details, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTrackEditor(title: String, tracks: List<Track>, initialIds: List<Long>, busy: Boolean,
                                onDismiss: () -> Unit, onSave: (List<Long>) -> Unit) {
    val selected = remember(initialIds) { initialIds.toMutableStateList() }
    var query by rememberSaveable { mutableStateOf("") }
    val byId = remember(tracks) { tracks.associateBy(Track::id) }
    val candidates = remember(tracks, selected.toList(), query) {
        val chosen = selected.toSet(); val q = query.trim().lowercase()
        tracks.asSequence().filter { it.id !in chosen && (q.isBlank() || it.title.lowercase().contains(q) || it.artist.orEmpty().lowercase().contains(q)) }.take(80).toList()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onDismiss) { Text("Schließen") }
                }
                Text("${selected.size} Tracks · Reihenfolge mit Pfeilen ändern", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(selected.toList(), key = { it }) { id ->
                        val track = byId[id]
                        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(track?.title ?: "Track $id", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val index = selected.indexOf(id)
                            TextButton(onClick = { if (index > 0) { selected.removeAt(index); selected.add(index - 1, id) } }, enabled = index > 0) { Text("↑") }
                            TextButton(onClick = { if (index in 0 until selected.lastIndex) { selected.removeAt(index); selected.add(index + 1, id) } }, enabled = index in 0 until selected.lastIndex) { Text("↓") }
                            TextButton(onClick = { selected.remove(id) }) { Text("×") }
                        }
                    }
                }
                OutlinedTextField(query, { query = it }, label = { Text("Tracks hinzufügen") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(candidates, key = { it.id }) { track -> TextButton(onClick = { selected.add(track.id) }, modifier = Modifier.fillMaxWidth()) { Text("＋ ${track.title}", Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                }
                Button(onClick = { onSave(selected.toList()) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("${selected.size} Tracks auf Prime speichern") }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun WlanPlayer(media: EngineXTrackMedia, track: Track?, analysis: TrackAnalysis?, detailedWaveform: OverviewWaveform?, waveformBusy: Boolean, onTracksChanged: (Collection<Long>) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember(media.url, media.authorization) {
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to media.authorization))
            .setUserAgent("EngineDJCompanion/0.6")
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(media.url)))
            prepare()
        }
    }
    var playing by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var activeLoopNumber by remember(media.url) { mutableStateOf<Int?>(null) }
    var quantizeEnabled by rememberSaveable(media.url) { mutableStateOf(true) }
    val sampleRate = analysis?.beatGrid?.sampleRate?.takeIf { it.isFinite() && it > 0 }
    val activeLoop = analysis?.loops?.firstOrNull { it.number == activeLoopNumber }
    var primeGridBpm by remember(media.url, analysis?.beatGrid) { mutableStateOf(analysis?.beatGrid?.let(AnalysisDecoder::estimatedBpm)) }
    val gridPreferences = remember(context) { context.getSharedPreferences("beatgrid-drafts", 0) }
    val gridDraftKey = remember(media.url) { (media.url.hashCode().toLong() and 0xffffffffL).toString(16) }
    val cuePreferences = remember(context) { context.getSharedPreferences("hot-cue-drafts", 0) }
    val tagPreferences = remember(context) { context.getSharedPreferences("tag-drafts", 0) }
    val queuePreferences = remember(context) { context.getSharedPreferences("tag-write-queue", 0) }
    var primeCues by remember(media.url, analysis?.cues) { mutableStateOf(analysis?.cues.orEmpty()) }
    var playerCues by remember(media.url, primeCues) { mutableStateOf(readCueDraft(cuePreferences.getString(gridDraftKey, null), primeCues)) }
    var cueWriteBusy by remember(media.url) { mutableStateOf(false) }
    var cueWriteStatus by remember(media.url) { mutableStateOf<String?>(null) }
    var tagTitle by remember(media.url, track) { mutableStateOf(tagPreferences.getString("$gridDraftKey.title", null) ?: track?.title.orEmpty()) }
    var tagArtist by remember(media.url, track) { mutableStateOf(tagPreferences.getString("$gridDraftKey.artist", null) ?: track?.artist.orEmpty()) }
    var tagAlbum by remember(media.url, track) { mutableStateOf(tagPreferences.getString("$gridDraftKey.album", null) ?: track?.album.orEmpty()) }
    var tagGenre by remember(media.url) { mutableStateOf(tagPreferences.getString("$gridDraftKey.genre", null).orEmpty()) }
    var tagComment by remember(media.url) { mutableStateOf(tagPreferences.getString("$gridDraftKey.comment", null).orEmpty()) }
    var tagLabel by remember(media.url) { mutableStateOf(tagPreferences.getString("$gridDraftKey.label", null).orEmpty()) }
    var tagRemixer by remember(media.url) { mutableStateOf(tagPreferences.getString("$gridDraftKey.remixer", null).orEmpty()) }
    var tagRating by remember(media.url, track) { mutableIntStateOf(tagPreferences.getInt("$gridDraftKey.rating", track?.rating?.toIntOrNull() ?: 0)) }
    var primeMetadata by remember(media.url) { mutableStateOf<TrackMetadata?>(null) }
    var tagWriteStatus by remember(media.url) { mutableStateOf<String?>(null) }
    var tagWriteBusy by remember(media.url) { mutableStateOf(false) }
    var pendingTagWrites by remember { mutableStateOf(readTagWriteQueue(queuePreferences.getString("queue", null))) }
    val primeTitle = primeMetadata?.title ?: track?.title.orEmpty()
    val primeArtist = primeMetadata?.artist ?: track?.artist.orEmpty()
    val primeAlbum = primeMetadata?.album ?: track?.album.orEmpty()
    val primeRating = primeMetadata?.rating ?: track?.rating?.toIntOrNull() ?: 0
    val tagsChanged = track != null && (tagTitle != primeTitle || tagArtist != primeArtist || tagAlbum != primeAlbum || tagGenre != primeMetadata?.genre.orEmpty() || tagComment != primeMetadata?.comment.orEmpty() || tagLabel != primeMetadata?.label.orEmpty() || tagRemixer != primeMetadata?.remixer.orEmpty() || tagRating != primeRating)
    val cuesChanged = playerCues != primeCues
    val playerAnalysis = analysis?.copy(cues = playerCues)
    var gridBpm by remember(media.url) { mutableStateOf(gridPreferences.getString("$gridDraftKey.bpm", null)?.toDoubleOrNull() ?: primeGridBpm) }
    var gridOffsetMs by remember(media.url) { mutableLongStateOf(gridPreferences.getLong("$gridDraftKey.offset", 0L)) }
    var primeGridOffsetMs by remember(media.url, analysis?.beatGrid) { mutableLongStateOf(0L) }
    var gridWriteBusy by remember(media.url) { mutableStateOf(false) }
    var gridWriteStatus by remember(media.url) { mutableStateOf<String?>(null) }
    LaunchedEffect(gridBpm, gridOffsetMs, primeGridBpm, primeGridOffsetMs, gridDraftKey) {
        val currentBpm = gridBpm
        val originalBpm = primeGridBpm
        val changed = gridOffsetMs != primeGridOffsetMs || (currentBpm != null && originalBpm != null && kotlin.math.abs(currentBpm - originalBpm) > 0.0005)
        gridPreferences.edit().apply {
            if (changed) {
                gridBpm?.let { putString("$gridDraftKey.bpm", it.toString()) }
                putLong("$gridDraftKey.offset", gridOffsetMs)
            } else {
                remove("$gridDraftKey.bpm")
                remove("$gridDraftKey.offset")
            }
        }.apply()
    }
    LaunchedEffect(playerCues, primeCues, gridDraftKey) {
        cuePreferences.edit().apply {
            if (playerCues != primeCues) putString(gridDraftKey, writeCueDraft(playerCues)) else remove(gridDraftKey)
        }.apply()
    }
    LaunchedEffect(media.url) {
        runCatching { withContext(Dispatchers.IO) { EngineXClient().metadata(media) } }.onSuccess { remote ->
            primeMetadata = remote
            if (!tagPreferences.contains("$gridDraftKey.title")) {
                tagTitle = remote.title; tagArtist = remote.artist; tagAlbum = remote.album; tagGenre = remote.genre
                tagComment = remote.comment; tagLabel = remote.label; tagRemixer = remote.remixer; tagRating = remote.rating
            }
        }.onFailure { tagWriteStatus = "Metadaten-Agent: ${it.message}" }
    }
    LaunchedEffect(tagTitle, tagArtist, tagAlbum, tagGenre, tagComment, tagLabel, tagRemixer, tagRating, tagsChanged, gridDraftKey) {
        tagPreferences.edit().apply {
            if (tagsChanged) {
                putString("$gridDraftKey.title", tagTitle)
                putString("$gridDraftKey.artist", tagArtist)
                putString("$gridDraftKey.album", tagAlbum)
                putString("$gridDraftKey.genre", tagGenre); putString("$gridDraftKey.comment", tagComment)
                putString("$gridDraftKey.label", tagLabel); putString("$gridDraftKey.remixer", tagRemixer)
                putInt("$gridDraftKey.rating", tagRating)
            } else {
                remove("$gridDraftKey.title"); remove("$gridDraftKey.artist"); remove("$gridDraftKey.album"); remove("$gridDraftKey.genre"); remove("$gridDraftKey.comment"); remove("$gridDraftKey.label"); remove("$gridDraftKey.remixer"); remove("$gridDraftKey.rating")
            }
        }.apply()
    }
    LaunchedEffect(pendingTagWrites) {
        queuePreferences.edit().putString("queue", writeTagWriteQueue(pendingTagWrites)).apply()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onPlaybackStateChanged(state: Int) {
                ready = state == Player.STATE_READY
                duration = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player, playing, activeLoopNumber, sampleRate) {
        while (true) {
            if (playing) {
                withFrameNanos {
                    position = player.currentPosition.coerceAtLeast(0L)
                    duration = player.duration.coerceAtLeast(0L)
                    if (activeLoop != null && sampleRate != null) {
                        val start = sampleToMs(activeLoop.startSample, sampleRate)
                        val end = sampleToMs(activeLoop.endSample, sampleRate)
                        if (end > start && position >= end) {
                            player.seekTo(start)
                            position = start
                        }
                    }
                }
            } else {
                position = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.coerceAtLeast(0L)
                delay(100)
            }
        }
    }
    Text("WLAN Player", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text("Audio wird direkt und schreibgeschützt vom Prime gestreamt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    playerAnalysis?.waveform?.let { waveform ->
        val seek: (Long) -> Unit = { requested ->
            val target = requested.coerceIn(0L, duration.coerceAtLeast(0L))
            position = target
            player.seekTo(target)
        }
        PlayerWaveforms(
            overviewWaveform = waveform,
            detailedWaveform = detailedWaveform,
            analysis = playerAnalysis,
            positionMs = position,
            durationMs = duration,
            detailLoading = waveformBusy,
            gridBpm = gridBpm,
            gridOffsetMs = gridOffsetMs,
            quantizeEnabled = quantizeEnabled,
            onSeek = seek
        )
    } ?: Surface(
        Modifier.fillMaxWidth().height(150.dp).padding(top = 10.dp),
        color = Color(0xFF05090D),
        shape = RoundedCornerShape(10.dp)
    ) { Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Text("Waveform wird geladen …", color = Color(0xFF78909C)) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(playerTime(position), style = MaterialTheme.typography.labelMedium)
        Text(playerTime(duration), style = MaterialTheme.typography.labelMedium)
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        OutlinedButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }, enabled = ready) { Text("−10 s") }
        Button(onClick = { if (player.isPlaying) player.pause() else player.play() }, enabled = ready) { Text(if (playing) "Pause" else "Play") }
        OutlinedButton(onClick = { player.seekTo((player.currentPosition + 10_000).coerceAtMost(duration)) }, enabled = ready) { Text("+10 s") }
    }
    playerAnalysis?.let { currentAnalysis ->
        PlayerPerformancePads(
            analysis = currentAnalysis,
            activeLoopNumber = activeLoopNumber,
            gridBpm = gridBpm,
            primeGridBpm = primeGridBpm,
            gridOffsetMs = gridOffsetMs,
            primeGridOffsetMs = primeGridOffsetMs,
            gridWriteBusy = gridWriteBusy,
            gridWriteStatus = gridWriteStatus,
            playheadMs = position,
            cuesChanged = cuesChanged,
            cueWriteBusy = cueWriteBusy,
            cueWriteStatus = cueWriteStatus,
            track = track,
            tagTitle = tagTitle,
            tagArtist = tagArtist,
            tagAlbum = tagAlbum,
            tagGenre = tagGenre,
            tagComment = tagComment,
            tagLabel = tagLabel,
            tagRemixer = tagRemixer,
            tagRating = tagRating,
            tagsChanged = tagsChanged,
            tagWriteBusy = tagWriteBusy,
            tagWriteStatus = tagWriteStatus,
            pendingTagWrites = pendingTagWrites,
            quantizeEnabled = quantizeEnabled,
            enabled = ready,
            onCue = { samples ->
                sampleRate?.let { rate ->
                    val target = sampleToMs(samples, rate).coerceIn(0L, duration)
                    player.seekTo(target)
                    position = target
                    player.play()
                }
            },
            onLoop = { loop ->
                if (activeLoopNumber == loop.number) activeLoopNumber = null
                else {
                    activeLoopNumber = loop.number
                    sampleRate?.let { rate ->
                        val start = sampleToMs(loop.startSample, rate)
                        val end = sampleToMs(loop.endSample, rate)
                        if (player.currentPosition !in start until end) {
                            player.seekTo(start)
                            position = start
                        }
                        player.play()
                    }
                }
            },
            onSetCue = { number, quantize ->
                sampleRate?.let { rate ->
                    val colors = listOf("#F4D338", "#EF8130", "#3D9BFF", "#36D67E", "#E85DAD", "#38D9D9", "#9B72FF", "#EF5350")
                    var cueMs = position.toDouble()
                    val firstMarker = currentAnalysis.beatGrid?.let { it.adjustedMarkers.ifEmpty { it.defaultMarkers }.firstOrNull() }
                    if (quantize && firstMarker != null && gridBpm != null) {
                        val anchorMs = firstMarker.sampleOffset / rate * 1000.0 + gridOffsetMs
                        val beatMs = 60_000.0 / gridBpm!!
                        cueMs = anchorMs + kotlin.math.round((cueMs - anchorMs) / beatMs) * beatMs
                    }
                    val cue = CuePoint(number, "Cue $number", cueMs.coerceAtLeast(0.0) / 1000.0 * rate, colors[(number - 1).coerceIn(0, colors.lastIndex)])
                    playerCues = (playerCues.filterNot { it.number == number } + cue).sortedBy(CuePoint::number)
                }
            },
            onDeleteCue = { number -> playerCues = playerCues.filterNot { it.number == number } },
            onResetCues = { playerCues = primeCues },
            onApplyCues = {
                cueWriteBusy = true; cueWriteStatus = "Cues werden gesichert und übertragen …"
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { EngineXClient().updateCues(media, playerCues) } }
                        .onSuccess { backup -> primeCues = playerCues; cueWriteStatus = "Cues auf Prime gespeichert · Sicherung: ${backup?.substringAfterLast('/') ?: "vorhanden"}" }
                        .onFailure { cueWriteStatus = "Cue-Übertragung fehlgeschlagen: ${it.message}" }
                    cueWriteBusy = false
                }
            },
            onTags = { title, artist, album, genre, comment, label, remixer, rating -> tagTitle = title; tagArtist = artist; tagAlbum = album; tagGenre = genre; tagComment = comment; tagLabel = label; tagRemixer = remixer; tagRating = rating },
            onResetTags = { tagTitle = primeTitle; tagArtist = primeArtist; tagAlbum = primeAlbum; tagGenre = primeMetadata?.genre.orEmpty(); tagComment = primeMetadata?.comment.orEmpty(); tagLabel = primeMetadata?.label.orEmpty(); tagRemixer = primeMetadata?.remixer.orEmpty(); tagRating = primeRating },
            onQueueTags = {
                track?.let { selected ->
                    val before = primeMetadata ?: TrackMetadata(selected.id, primeTitle, primeArtist, primeAlbum, "", "", "", "", primeRating)
                    val after = TrackMetadata(selected.id, tagTitle, tagArtist, tagAlbum, tagGenre, tagComment, tagLabel, tagRemixer, tagRating)
                    pendingTagWrites = (pendingTagWrites.filterNot { it.trackId == selected.id } + PendingTagWrite(selected.id, gridDraftKey, before, after)).sortedBy(PendingTagWrite::title)
                    tagWriteStatus = "Zur Warteschlange hinzugefügt"
                }
            },
            onRemoveQueuedTag = { id -> pendingTagWrites = pendingTagWrites.filterNot { it.trackId == id } },
            onApplyTagQueue = {
                if (pendingTagWrites.isNotEmpty()) {
                    tagWriteBusy = true
                    scope.launch {
                        var remaining = pendingTagWrites
                        var lastBackup: String? = null
                        val changedTrackIds = linkedSetOf<Long>()
                        for ((index, queued) in pendingTagWrites.withIndex()) {
                            tagWriteStatus = "${index + 1} von ${pendingTagWrites.size}: ${queued.title}"
                            val queuedMedia = media.copy(url = media.url.replace(Regex("/tracks/\\d+/audio"), "/tracks/${queued.trackId}/audio"))
                            val result = runCatching { withContext(Dispatchers.IO) { EngineXClient().updateMetadata(queuedMedia, queued.after) } }
                            if (result.isFailure) { tagWriteStatus = "Schreiben fehlgeschlagen bei ${queued.title}: ${result.exceptionOrNull()?.message}"; break }
                            val saved = result.getOrThrow(); lastBackup = saved.backup ?: lastBackup
                            changedTrackIds += queued.trackId
                            if (queued.trackId == track?.id) primeMetadata = saved
                            clearTagDraft(tagPreferences, queued.draftKey)
                            remaining = remaining.filterNot { it.trackId == queued.trackId }
                            pendingTagWrites = remaining
                        }
                        if (changedTrackIds.isNotEmpty()) onTracksChanged(changedTrackIds)
                        if (remaining.isEmpty()) tagWriteStatus = "Alle Änderungen gespeichert · Sicherung: ${lastBackup?.substringAfterLast('/') ?: "erstellt"}"
                        tagWriteBusy = false
                    }
                }
            },
            onQuantize = { quantizeEnabled = it },
            onGridBpm = { gridBpm = it.coerceIn(20.0, 400.0) },
            onGridOffset = { gridOffsetMs = it.coerceIn(-30_000L, 30_000L) },
            onGridReset = { gridBpm = primeGridBpm; gridOffsetMs = primeGridOffsetMs },
            onApplyGrid = {
                gridBpm?.let { bpm ->
                    gridWriteBusy = true; gridWriteStatus = "Beatgrid wird gesichert und übertragen …"
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { EngineXClient().updateBeatgrid(media, bpm, gridOffsetMs) } }
                            .onSuccess { backup -> primeGridBpm = bpm; primeGridOffsetMs = gridOffsetMs; gridWriteStatus = "Beatgrid auf Prime gespeichert · Sicherung: ${backup?.substringAfterLast('/') ?: "vorhanden"}" }
                            .onFailure { gridWriteStatus = "Beatgrid-Übertragung fehlgeschlagen: ${it.message}" }
                        gridWriteBusy = false
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerPerformancePads(
    analysis: TrackAnalysis,
    activeLoopNumber: Int?,
    gridBpm: Double?,
    primeGridBpm: Double?,
    gridOffsetMs: Long,
    primeGridOffsetMs: Long,
    gridWriteBusy: Boolean,
    gridWriteStatus: String?,
    playheadMs: Long,
    cuesChanged: Boolean,
    cueWriteBusy: Boolean,
    cueWriteStatus: String?,
    track: Track?,
    tagTitle: String,
    tagArtist: String,
    tagAlbum: String,
    tagGenre: String,
    tagComment: String,
    tagLabel: String,
    tagRemixer: String,
    tagRating: Int,
    tagsChanged: Boolean,
    tagWriteBusy: Boolean,
    tagWriteStatus: String?,
    pendingTagWrites: List<PendingTagWrite>,
    quantizeEnabled: Boolean,
    enabled: Boolean,
    onCue: (Double) -> Unit,
    onLoop: (SavedLoop) -> Unit,
    onSetCue: (Int, Boolean) -> Unit,
    onDeleteCue: (Int) -> Unit,
    onResetCues: () -> Unit,
    onApplyCues: () -> Unit,
    onTags: (String, String, String, String, String, String, String, Int) -> Unit,
    onResetTags: () -> Unit,
    onQueueTags: () -> Unit,
    onRemoveQueuedTag: (Long) -> Unit,
    onApplyTagQueue: () -> Unit,
    onQuantize: (Boolean) -> Unit,
    onGridBpm: (Double) -> Unit,
    onGridOffset: (Long) -> Unit,
    onGridReset: () -> Unit,
    onApplyGrid: () -> Unit
) {
    var selectedTool by rememberSaveable { mutableIntStateOf(0) }
    SecondaryTabRow(selectedTabIndex = selectedTool, modifier = Modifier.padding(top = 16.dp)) {
        Tab(selected = selectedTool == 0, onClick = { selectedTool = 0 }, text = { Text("Hot Cues") })
        Tab(selected = selectedTool == 1, onClick = { selectedTool = 1 }, text = { Text("Beatgrid") })
        Tab(selected = selectedTool == 2, onClick = { selectedTool = 2 }, text = { Text("Tags") })
    }
    if (selectedTool == 1) {
        BeatgridControls(
            analysis = analysis,
            bpm = gridBpm,
            primeBpm = primeGridBpm,
            offsetMs = gridOffsetMs,
            primeOffsetMs = primeGridOffsetMs,
            playheadMs = playheadMs,
            writeBusy = gridWriteBusy,
            writeStatus = gridWriteStatus,
            onBpm = onGridBpm,
            onOffset = onGridOffset,
            onReset = onGridReset,
            onApply = onApplyGrid
        )
        return
    }
    if (selectedTool == 2) {
        TrackTagEditor(track, tagTitle, tagArtist, tagAlbum, tagGenre, tagComment, tagLabel, tagRemixer, tagRating, tagsChanged, tagWriteBusy, tagWriteStatus, pendingTagWrites, onTags, onResetTags, onQueueTags, onRemoveQueuedTag, onApplyTagQueue)
        return
    }
    var cueMode by rememberSaveable { mutableIntStateOf(0) }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Hot-Cue-Werkzeug", fontWeight = FontWeight.SemiBold)
        FilterChip(selected = quantizeEnabled, onClick = { onQuantize(!quantizeEnabled) }, label = { Text(if (quantizeEnabled) "Q Quantize" else "Quantize aus") })
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Auslösen", "Setzen", "Löschen").forEachIndexed { index, label ->
            FilterChip(selected = cueMode == index, onClick = { cueMode = index }, label = { Text(label) }, modifier = Modifier.weight(1f))
        }
    }
    if (cuesChanged) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Lokaler Cue-Entwurf", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onResetCues, enabled = !cueWriteBusy) { Text("Prime-Cues") }
            }
            Button(onClick = onApplyCues, enabled = !cueWriteBusy, modifier = Modifier.fillMaxWidth()) { Text(if (cueWriteBusy) "Cues übertragen …" else "Cues auf Prime übertragen") }
        }
    }
    cueWriteStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (it.startsWith("Cue-Übertragung fehlgeschlagen")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary) }
    val cues = analysis.cues.associateBy(CuePoint::number)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { column ->
                    val number = row * 4 + column + 1
                    val cue = cues[number]
                    val accent = cue?.let { analysisColor(it.color) } ?: MaterialTheme.colorScheme.surfaceVariant
                    Button(
                        onClick = {
                            when (cueMode) {
                                1 -> onSetCue(number, quantizeEnabled)
                                2 -> onDeleteCue(number)
                                else -> cue?.let { onCue(it.sampleOffset) }
                            }
                        },
                        enabled = enabled && (cue != null || cueMode == 1),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.78f))
                    ) { Text(cue?.label?.takeIf(String::isNotBlank) ?: number.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
    analysis.mainCueSample?.takeIf { it >= 0 }?.let { mainCue ->
        OutlinedButton(onClick = { onCue(mainCue) }, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Main Cue") }
    }
    if (analysis.loops.isNotEmpty()) {
        Text("Gespeicherte Loops", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            analysis.loops.take(4).forEach { loop ->
                FilterChip(
                    selected = activeLoopNumber == loop.number,
                    onClick = { onLoop(loop) },
                    enabled = enabled,
                    label = { Text(loop.label.ifBlank { "Loop ${loop.number}" }, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TrackTagEditor(
    track: Track?,
    title: String,
    artist: String,
    album: String,
    genre: String,
    comment: String,
    label: String,
    remixer: String,
    rating: Int,
    changed: Boolean,
    writeBusy: Boolean,
    writeStatus: String?,
    pendingWrites: List<PendingTagWrite>,
    onChange: (String, String, String, String, String, String, String, Int) -> Unit,
    onReset: () -> Unit,
    onQueue: () -> Unit,
    onRemoveQueued: (Long) -> Unit,
    onApplyQueue: () -> Unit
) {
    var confirmWrite by remember { mutableStateOf(false) }
    fun change(newTitle: String = title, newArtist: String = artist, newAlbum: String = album, newGenre: String = genre, newComment: String = comment, newLabel: String = label, newRemixer: String = remixer, newRating: Int = rating) =
        onChange(newTitle, newArtist, newAlbum, newGenre, newComment, newLabel, newRemixer, newRating)
    if (confirmWrite) AlertDialog(
        onDismissRequest = { confirmWrite = false },
        title = { Text("Track auf dem Prime ändern?") },
        text = { Text("${pendingWrites.size} vorgemerkte Tracks werden nacheinander geschrieben. Agent v4 verwendet dafür eine geprüfte Ausgangssicherung.") },
        confirmButton = { TextButton(onClick = { confirmWrite = false; onApplyQueue() }) { Text("Alle anwenden") } },
        dismissButton = { TextButton(onClick = { confirmWrite = false }) { Text("Abbrechen") } }
    )
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column {
                Text("Trackdaten", fontWeight = FontWeight.SemiBold)
                Text(if (changed) "Lokaler Entwurf gespeichert" else "Unverändert vom Prime", color = if (changed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            if (changed) TextButton(onClick = onReset) { Text("Prime-Tags") }
        }
        OutlinedTextField(title, { change(newTitle = it) }, label = { Text("Titel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(artist, { change(newArtist = it) }, label = { Text("Interpret") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(album, { change(newAlbum = it) }, label = { Text("Album") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(genre, { change(newGenre = it) }, label = { Text("Genre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(label, { change(newLabel = it) }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(remixer, { change(newRemixer = it) }, label = { Text("Remixer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(comment, { change(newComment = it) }, label = { Text("Kommentar") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Text("Bewertung", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            repeat(5) { index ->
                val starValue = (index + 1) * 20
                TextButton(onClick = { change(newRating = if (rating == starValue) 0 else starValue) }) {
                    Text(if (rating >= starValue) "★" else "☆", style = MaterialTheme.typography.headlineMedium, color = if (rating >= starValue) Color(0xFFFFD54F) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("${rating / 20} von 5 Sternen", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onQueue, enabled = changed && !writeBusy && title.isNotBlank() && track != null, modifier = Modifier.fillMaxWidth()) { Text("Änderung vormerken") }
        if (pendingWrites.isNotEmpty()) {
            Text("Warteschlange (${pendingWrites.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            pendingWrites.forEach { pending ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pending.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text("${pending.changedFields} Felder geändert", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onRemoveQueued(pending.trackId) }, enabled = !writeBusy) { Text("Entfernen") }
                    }
                }
            }
            Button(onClick = { confirmWrite = true }, enabled = !writeBusy, modifier = Modifier.fillMaxWidth()) { Text(if (writeBusy) "Übertragung läuft …" else "${pendingWrites.size} Änderungen anwenden") }
        }
        writeStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Schreiben fehlgeschlagen") || it.startsWith("Metadaten-Agent")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary) }
        Text("Agent v4 verarbeitet die Warteschlange mit einer geprüften Ausgangssicherung pro Sitzung.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (track == null) Text("Für diesen Track sind keine WLAN-Metadaten verfügbar.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BeatgridControls(
    analysis: TrackAnalysis,
    bpm: Double?,
    primeBpm: Double?,
    offsetMs: Long,
    primeOffsetMs: Long,
    playheadMs: Long,
    writeBusy: Boolean,
    writeStatus: String?,
    onBpm: (Double) -> Unit,
    onOffset: (Long) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit
) {
    val grid = analysis.beatGrid
    val markers = grid?.let { it.adjustedMarkers.ifEmpty { it.defaultMarkers } }.orEmpty()
    var taps by remember { mutableStateOf<List<Long>>(emptyList()) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Quelle: Prime · beatData", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text("${markers.size} Marker · ${bpm?.let { String.format(Locale.ROOT, "%.3f BPM", it) } ?: "BPM unbekannt"}", fontWeight = FontWeight.Bold)
                if (offsetMs != primeOffsetMs || (bpm != null && primeBpm != null && kotlin.math.abs(bpm - primeBpm) > 0.0005)) {
                    Text("Lokal gespeichert · Offset ${if (offsetMs >= 0) "+" else ""}$offsetMs ms", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                } else Text("Unverändert vom Prime", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Grid verschieben", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-10L, -1L, 1L, 10L).forEach { delta ->
                OutlinedButton(onClick = { onOffset(offsetMs + delta) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) {
                    Text(if (delta > 0) "+$delta" else "$delta")
                }
            }
        }
        Text("BPM korrigieren", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { bpm?.let { onBpm(it / 2.0) } }, enabled = bpm != null, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("÷2") }
            OutlinedButton(onClick = { bpm?.let { onBpm(it - 0.01) } }, enabled = bpm != null, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("−0.01") }
            OutlinedButton(onClick = { bpm?.let { onBpm(it + 0.01) } }, enabled = bpm != null, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("+0.01") }
            OutlinedButton(onClick = { bpm?.let { onBpm(it * 2.0) } }, enabled = bpm != null, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("×2") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val now = android.os.SystemClock.elapsedRealtime()
                val next = if (taps.lastOrNull()?.let { now - it > 2_500 } != false) listOf(now) else (taps + now).takeLast(9)
                taps = next
                if (next.size >= 2) {
                    val intervals = next.zipWithNext { a, b -> b - a }
                    onBpm(60_000.0 / intervals.average())
                }
            }, modifier = Modifier.weight(1f)) { Text("TAP BPM") }
            OutlinedButton(onClick = { taps = emptyList(); onReset() }, modifier = Modifier.weight(1f)) { Text("Prime-Werte") }
        }
        val firstMarker = markers.firstOrNull()
        val rate = grid?.sampleRate?.takeIf { it.isFinite() && it > 0 }
        Button(
            onClick = {
                if (firstMarker != null && rate != null && bpm != null) {
                    val anchorMs = firstMarker.sampleOffset / rate * 1000.0 + offsetMs
                    val beatMs = 60_000.0 / bpm
                    val nearestBeat = anchorMs + kotlin.math.round((playheadMs - anchorMs) / beatMs) * beatMs
                    onOffset(offsetMs + (playheadMs - nearestBeat).toLong())
                }
            },
            enabled = firstMarker != null && rate != null && bpm != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Beat auf Playhead setzen · ${playerTime(playheadMs)}") }
        val changed = offsetMs != primeOffsetMs || (bpm != null && primeBpm != null && kotlin.math.abs(bpm - primeBpm) > 0.0005)
        Button(onClick = onApply, enabled = changed && !writeBusy, modifier = Modifier.fillMaxWidth()) { Text(if (writeBusy) "Beatgrid übertragen …" else "Beatgrid auf Prime übertragen") }
        writeStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Beatgrid-Übertragung fehlgeschlagen")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary) }
        markers.take(4).forEachIndexed { index, marker ->
            Text("Marker ${index + 1}: ${analysisPosition(marker.sampleOffset, grid?.sampleRate)} · Beat ${marker.beatNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Änderungen bleiben lokal, bis du sie mit dem Button auf den Prime überträgst.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class PendingTagWrite(val trackId: Long, val draftKey: String, val before: TrackMetadata, val after: TrackMetadata) {
    val title: String get() = after.title
    val changedFields: Int get() = listOf(
        before.title != after.title, before.artist != after.artist, before.album != after.album,
        before.genre != after.genre, before.comment != after.comment, before.label != after.label,
        before.remixer != after.remixer, before.rating != after.rating
    ).count { it }
}

private fun metadataJSON(value: TrackMetadata) = org.json.JSONObject().apply {
    put("trackId", value.trackId); put("title", value.title); put("artist", value.artist); put("album", value.album)
    put("genre", value.genre); put("comment", value.comment); put("label", value.label); put("remixer", value.remixer); put("rating", value.rating)
}

private fun metadataFromJSON(value: org.json.JSONObject) = TrackMetadata(
    value.getLong("trackId"), value.optString("title"), value.optString("artist"), value.optString("album"),
    value.optString("genre"), value.optString("comment"), value.optString("label"), value.optString("remixer"), value.optInt("rating")
)

private fun readTagWriteQueue(json: String?): List<PendingTagWrite> = runCatching {
    if (json.isNullOrBlank()) emptyList() else {
        val array = org.json.JSONArray(json)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PendingTagWrite(item.getLong("trackId"), item.getString("draftKey"), metadataFromJSON(item.getJSONObject("before")), metadataFromJSON(item.getJSONObject("after")))
        }.distinctBy(PendingTagWrite::trackId)
    }
}.getOrDefault(emptyList())

private fun writeTagWriteQueue(queue: List<PendingTagWrite>): String = org.json.JSONArray().apply {
    queue.forEach { pending -> put(org.json.JSONObject().apply {
        put("trackId", pending.trackId); put("draftKey", pending.draftKey)
        put("before", metadataJSON(pending.before)); put("after", metadataJSON(pending.after))
    }) }
}.toString()

private fun clearTagDraft(preferences: android.content.SharedPreferences, key: String) {
    preferences.edit().remove("$key.title").remove("$key.artist").remove("$key.album").remove("$key.genre")
        .remove("$key.comment").remove("$key.label").remove("$key.remixer").remove("$key.rating").apply()
}

private fun readCueDraft(json: String?, fallback: List<CuePoint>): List<CuePoint> = runCatching {
    if (json.isNullOrBlank()) fallback else {
        val array = org.json.JSONArray(json)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            CuePoint(
                number = item.getInt("number").also { require(it in 1..8) },
                label = item.optString("label"),
                sampleOffset = item.getDouble("sampleOffset").also { require(it.isFinite() && it >= 0) },
                color = item.optString("color", "#F4D338")
            )
        }.distinctBy(CuePoint::number).sortedBy(CuePoint::number)
    }
}.getOrDefault(fallback)

private fun writeCueDraft(cues: List<CuePoint>): String = org.json.JSONArray().apply {
    cues.sortedBy(CuePoint::number).forEach { cue ->
        put(org.json.JSONObject().apply {
            put("number", cue.number)
            put("label", cue.label)
            put("sampleOffset", cue.sampleOffset)
            put("color", cue.color)
        })
    }
}.toString()

private fun sampleToMs(samples: Double, sampleRate: Double): Long = (samples / sampleRate * 1000.0).toLong().coerceAtLeast(0L)

private fun playerTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun AnalysisOverview(analysis: TrackAnalysis) {
    val rate = analysis.beatGrid?.sampleRate?.takeIf { it.isFinite() && it > 0 }
    Text("Performance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(rate?.let { String.format(Locale.ROOT, "%.0f Hz", it) } ?: "Sample-Rate unbekannt", color = MaterialTheme.colorScheme.onSurfaceVariant)

    Text("Hot Cues", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
    if (analysis.cues.isEmpty()) Text("Keine Hot Cues gespeichert.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    analysis.cues.forEach { cue ->
        val accent = analysisColor(cue.color)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = accent.copy(alpha = 0.18f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.8f))
        ) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${cue.number}  ${cue.label.ifBlank { "Cue ${cue.number}" }}", fontWeight = FontWeight.SemiBold)
                Text(analysisPosition(cue.sampleOffset, rate), color = accent, fontWeight = FontWeight.Bold)
            }
        }
    }
    analysis.mainCueSample?.takeIf { it >= 0 }?.let {
        Text("Main Cue  ·  ${analysisPosition(it, rate)}", modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.secondary)
    }

    Text("Gespeicherte Loops", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
    if (analysis.loops.isEmpty()) Text("Keine gespeicherten Loops.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    analysis.loops.forEach { loop ->
        val accent = analysisColor(loop.color)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = accent.copy(alpha = 0.18f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.8f))
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("${loop.number}  ${loop.label.ifBlank { "Loop ${loop.number}" }}", fontWeight = FontWeight.SemiBold)
                Text("${analysisPosition(loop.startSample, rate)}  →  ${analysisPosition(loop.endSample, rate)}", color = accent, fontWeight = FontWeight.Bold)
            }
        }
    }

    analysis.beatGrid?.let { grid ->
        val markers = grid.adjustedMarkers.ifEmpty { grid.defaultMarkers }
        val inferredBpm = AnalysisDecoder.estimatedBpm(grid)
        Text("Beatgrid", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("${if (grid.isSet) "Gesetzt" else "Nicht gesetzt"} · ${markers.size} Marker${inferredBpm?.let { " · ${String.format(Locale.ROOT, "%.3f BPM", it)}" } ?: ""}")
                markers.take(8).forEachIndexed { index, marker ->
                    Text("${index + 1}. ${analysisPosition(marker.sampleOffset, rate)} · Beat ${marker.beatNumber} · ${marker.beats} Beats", style = MaterialTheme.typography.bodySmall)
                }
                if (markers.size > 8) Text("… ${markers.size - 8} weitere Marker", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun analysisColor(value: String): Color = runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(Color(0xFF6BE2CB))
private fun analysisPosition(samples: Double, rate: Double?): String = if (rate != null && samples.isFinite()) String.format(Locale.ROOT, "%.3f s", samples / rate) else String.format(Locale.ROOT, "%.0f Samples", samples)

@Composable
private fun TrackCard(track: Track, enabled: Boolean, click: () -> Unit) {
    Card(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(track.artist, track.album).joinToString(" · ").ifEmpty { "Keine Interpret-/Albumangabe" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${MetadataDisplay.duration(track.seconds)}  ·  ${MetadataDisplay.bpm(track.bpm)}  ·  ${MetadataDisplay.key(track.key)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                Text(MetadataDisplay.rating(track.rating), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
private fun bytes(value: Long): String {
    if (value < 1024) return "$value B"
    val unit = (ln(value.toDouble()) / ln(1024.0)).toInt().coerceIn(1, 4)
    return String.format(Locale.ROOT, "%.1f %s", value / 1024.0.pow(unit), listOf("KiB", "MiB", "GiB", "TiB")[unit - 1])
}

@Composable
private fun PlayerWaveforms(
    overviewWaveform: OverviewWaveform,
    detailedWaveform: OverviewWaveform?,
    analysis: TrackAnalysis,
    positionMs: Long,
    durationMs: Long,
    detailLoading: Boolean,
    gridBpm: Double?,
    gridOffsetMs: Long,
    quantizeEnabled: Boolean,
    onSeek: (Long) -> Unit
) {
    val total = detailedWaveform?.points?.size ?: overviewWaveform.points.size
    val safeDuration = durationMs.coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    val visible = minOf(total, (total * 0.04f).toInt().coerceAtLeast(48))
    val centerIndex = (progress * (total - 1)).toInt()
    val startIndex = centerIndex - visible / 2
    val endIndex = startIndex + visible
    val rate = analysis.beatGrid?.sampleRate?.takeIf { it > 0 && it.isFinite() }
    val latestPosition by rememberUpdatedState(positionMs)
    val overviewRaster by produceState<ImageBitmap?>(null, overviewWaveform) {
        value = withContext(Dispatchers.Default) { renderWaveform(overviewWaveform) }
    }
    val tileSize = 1024
    val detailTiles by produceState<List<ImageBitmap>>(emptyList(), detailedWaveform) {
        value = detailedWaveform?.let { detailed ->
            withContext(Dispatchers.Default) {
                detailed.points.chunked(tileSize).map { points -> renderWaveform(detailed.copy(points = points)) }
            }
        }.orEmpty()
    }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(62.dp)
                .background(Color(0xFF04090D), RoundedCornerShape(8.dp))
                .pointerInput(safeDuration) {
                    fun seekAt(x: Float) = onSeek((x / size.width.toFloat() * safeDuration.toFloat()).toLong().coerceIn(0L, safeDuration))
                    detectDragGestures(
                        onDragStart = { seekAt(it.x) },
                        onDrag = { change, _ -> change.consume(); seekAt(change.position.x) }
                    )
                }
        ) {
            overviewRaster?.let { image ->
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Low
                )
            }
            analysis.cues.forEach { cue ->
                val cueMs = if (rate != null) cue.sampleOffset / rate * 1000.0 else return@forEach
                val cueX = (cueMs / safeDuration * size.width).toFloat()
                val cueColor = analysisColor(cue.color)
                drawLine(cueColor, Offset(cueX, 0f), Offset(cueX, size.height), 3f)
                drawCircle(cueColor, 5f, Offset(cueX, 7f))
                drawCircle(Color.Black, 2f, Offset(cueX, 7f))
            }
            drawLine(Color(0xFF00E5FF), Offset(progress * size.width, 0f), Offset(progress * size.width, size.height), 2.5f)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(142.dp).background(Color(0xFF020609), RoundedCornerShape(8.dp))
        ) {
        Canvas(
            Modifier.matchParentSize()
                .pointerInput(safeDuration, total, visible) {
                    var jogTarget = 0L
                    detectHorizontalDragGestures(
                        onDragStart = { jogTarget = latestPosition },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val windowMs = safeDuration.toDouble() * visible / total
                            jogTarget = (jogTarget - dragAmount / size.width * windowMs).toLong().coerceIn(0L, safeDuration)
                            onSeek(jogTarget)
                        }
                    )
                }
        ) {
            val count = (endIndex - startIndex).coerceAtLeast(1)
            val windowStartMs = startIndex.toDouble() / total * safeDuration.toDouble()
            val windowEndMs = endIndex.toDouble() / total * safeDuration.toDouble()
            val grid = analysis.beatGrid
            val bpm = gridBpm ?: grid?.let(AnalysisDecoder::estimatedBpm)
            val anchor = grid?.let { it.adjustedMarkers.ifEmpty { it.defaultMarkers }.firstOrNull() }
            val sourceStart = startIndex.coerceAtLeast(0)
            val sourceEnd = endIndex.coerceAtMost(total)
            if (sourceEnd > sourceStart && detailTiles.isNotEmpty()) {
                val firstTile = sourceStart / tileSize
                val lastTile = (sourceEnd - 1) / tileSize
                for (tileIndex in firstTile..lastTile) {
                    val image = detailTiles.getOrNull(tileIndex) ?: continue
                    val tileStart = tileIndex * tileSize
                    val overlapStart = maxOf(sourceStart, tileStart)
                    val overlapEnd = minOf(sourceEnd, tileStart + image.width)
                    if (overlapEnd <= overlapStart) continue
                    val destinationStart = ((overlapStart - startIndex).toFloat() / count * size.width).toInt()
                    val destinationWidth = ((overlapEnd - overlapStart).toFloat() / count * size.width).toInt().coerceAtLeast(1)
                    drawImage(
                        image = image,
                        srcOffset = IntOffset(overlapStart - tileStart, 0),
                        srcSize = IntSize(overlapEnd - overlapStart, image.height),
                        dstOffset = IntOffset(destinationStart, 0),
                        dstSize = IntSize(destinationWidth, size.height.toInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.Low
                    )
                }
            }
            // Draw the grid over the waveform so transients cannot hide it.
            if (rate != null && bpm != null && anchor != null) {
                val beatMs = 60_000.0 / bpm
                val anchorMs = anchor.sampleOffset / rate * 1000.0 + gridOffsetMs
                var n = kotlin.math.floor((windowStartMs - anchorMs) / beatMs).toInt()
                while (anchorMs + n * beatMs <= windowEndMs) {
                    val beatTime = anchorMs + n * beatMs
                    val x = ((beatTime - windowStartMs) / (windowEndMs - windowStartMs) * size.width).toFloat()
                    val major = (anchor.beatNumber + n.toLong()) % 4L == 0L
                    val gridColor = if (major) Color(0xFFFF5252) else Color.White.copy(alpha = 0.62f)
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), if (major) 2.5f else 1.25f)
                    drawCircle(gridColor, if (major) 4.5f else 2.5f, Offset(x, 7f))
                    drawCircle(gridColor, if (major) 4.5f else 2.5f, Offset(x, size.height - 7f))
                    n++
                }
            }
            analysis.cues.forEach { cue ->
                val cueMs = if (rate != null) cue.sampleOffset / rate * 1000.0 else return@forEach
                if (cueMs in windowStartMs..windowEndMs) {
                    val x = ((cueMs - windowStartMs) / (windowEndMs - windowStartMs) * size.width).toFloat()
                    val cueColor = analysisColor(cue.color)
                    drawLine(cueColor, Offset(x, 0f), Offset(x, size.height), 4f)
                    drawCircle(cueColor, 8f, Offset(x, 11f))
                    drawCircle(Color.Black, 3f, Offset(x, 11f))
                }
            }
            drawLine(Color.White, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 3f)
            drawLine(Color(0xFF00E5FF), Offset(size.width / 2f - 2f, 0f), Offset(size.width / 2f - 2f, size.height), 1f)
        }
            if (detailLoading || detailTiles.isEmpty()) {
                Column(Modifier.align(androidx.compose.ui.Alignment.Center), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                    Text("Audio analysieren …", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text("${if (quantizeEnabled) "Q · " else ""}Oben Marker ziehen · unten wie ein Jog wischen", style = MaterialTheme.typography.labelSmall, color = if (quantizeEnabled) MaterialTheme.colorScheme.primary else Color(0xFF78909C), modifier = Modifier.padding(top = 4.dp))
    }
}

private fun renderWaveform(waveform: OverviewWaveform): ImageBitmap {
    val width = waveform.points.size.coerceAtLeast(1)
    val height = 256
    val center = height / 2f
    val extent = height * 0.48f
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    fun normalized(value: Int, maximum: Int): Float =
        (value.toDouble() / maximum.coerceAtLeast(1)).coerceIn(0.0, 1.0).pow(0.58).toFloat()

    val outer = FloatArray(width)
    val middle = FloatArray(width)
    val core = FloatArray(width)
    waveform.points.forEachIndexed { index, point ->
        val low = normalized(point.low, waveform.maxLow)
        val mid = normalized(point.mid, waveform.maxMid)
        val high = normalized(point.high, waveform.maxHigh)
        outer[index] = maxOf(low, mid, high) * extent
        middle[index] = maxOf(low * 0.55f, mid * 0.85f) * extent
        core[index] = low * 0.28f * extent
    }

    fun area(amplitudes: FloatArray): AndroidPath = AndroidPath().apply {
        moveTo(0f, center - amplitudes[0])
        for (x in 1 until width) lineTo(x.toFloat(), center - amplitudes[x])
        for (x in width - 1 downTo 0) lineTo(x.toFloat(), center + amplitudes[x])
        close()
    }
    fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    canvas.drawPath(area(outer), paint(android.graphics.Color.rgb(0, 126, 230)))
    canvas.drawPath(area(middle), paint(android.graphics.Color.rgb(25, 232, 101)))
    canvas.drawPath(area(core), paint(android.graphics.Color.rgb(229, 255, 255)))
    return bitmap.asImageBitmap()
}
