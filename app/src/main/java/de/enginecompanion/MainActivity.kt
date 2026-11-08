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
                                    if (isFolder && (directCount ?: 0) > 0) {
                                        TextButton(onClick = { vm.playlist(playlist.id); tab = 0 }) { Text("Öffnen") }
                                    }
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
                            WlanPlayer(media, state.detailAnalysis)
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

@OptIn(UnstableApi::class)
@Composable
private fun WlanPlayer(media: EngineXTrackMedia, analysis: TrackAnalysis?) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
    LaunchedEffect(player, playing) {
        while (true) {
            if (playing) {
                withFrameNanos {
                    position = player.currentPosition.coerceAtLeast(0L)
                    duration = player.duration.coerceAtLeast(0L)
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
    analysis?.waveform?.let { waveform ->
        val seek: (Long) -> Unit = { requested ->
            val target = requested.coerceIn(0L, duration.coerceAtLeast(0L))
            position = target
            player.seekTo(target)
        }
        PlayerWaveforms(
            waveform = waveform,
            analysis = analysis,
            positionMs = position,
            durationMs = duration,
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
}

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
    waveform: OverviewWaveform,
    analysis: TrackAnalysis,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    val total = waveform.points.size
    val safeDuration = durationMs.coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    val visible = minOf(total, (total * 0.04f).toInt().coerceAtLeast(48))
    val centerIndex = (progress * (total - 1)).toInt()
    val startIndex = centerIndex - visible / 2
    val endIndex = startIndex + visible
    val rate = analysis.beatGrid?.sampleRate?.takeIf { it > 0 && it.isFinite() }
    val latestPosition by rememberUpdatedState(positionMs)
    val raster by produceState<ImageBitmap?>(null, waveform) {
        value = withContext(Dispatchers.Default) { renderWaveform(waveform) }
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
            raster?.let { image ->
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
                drawLine(analysisColor(cue.color), Offset(cueX, 0f), Offset(cueX, size.height), 2f)
            }
            drawLine(Color(0xFF00E5FF), Offset(progress * size.width, 0f), Offset(progress * size.width, size.height), 2.5f)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(142.dp)
                .background(Color(0xFF020609), RoundedCornerShape(8.dp))
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
            val bpm = grid?.let(AnalysisDecoder::estimatedBpm)
            val anchor = grid?.let { it.adjustedMarkers.ifEmpty { it.defaultMarkers }.firstOrNull() }
            if (rate != null && bpm != null && anchor != null) {
                val beatMs = 60_000.0 / bpm
                val anchorMs = anchor.sampleOffset / rate * 1000.0
                var n = kotlin.math.floor((windowStartMs - anchorMs) / beatMs).toInt()
                while (anchorMs + n * beatMs <= windowEndMs) {
                    val beatTime = anchorMs + n * beatMs
                    val x = ((beatTime - windowStartMs) / (windowEndMs - windowStartMs) * size.width).toFloat()
                    val major = (anchor.beatNumber + n.toLong()) % 4L == 0L
                    drawLine(if (major) Color(0xFF607D8B) else Color(0xFF263238), Offset(x, 0f), Offset(x, size.height), if (major) 1.5f else 1f)
                    n++
                }
            }
            raster?.let { image ->
                val sourceStart = startIndex.coerceAtLeast(0)
                val sourceEnd = endIndex.coerceAtMost(total)
                if (sourceEnd > sourceStart) {
                    val destinationStart = ((sourceStart - startIndex).toFloat() / count * size.width).toInt()
                    val destinationWidth = ((sourceEnd - sourceStart).toFloat() / count * size.width).toInt().coerceAtLeast(1)
                    drawImage(
                        image = image,
                        srcOffset = IntOffset(sourceStart, 0),
                        srcSize = IntSize(sourceEnd - sourceStart, image.height),
                        dstOffset = IntOffset(destinationStart, 0),
                        dstSize = IntSize(destinationWidth, size.height.toInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.Low
                    )
                }
            }
            analysis.cues.forEach { cue ->
                val cueMs = if (rate != null) cue.sampleOffset / rate * 1000.0 else return@forEach
                if (cueMs in windowStartMs..windowEndMs) {
                    val x = ((cueMs - windowStartMs) / (windowEndMs - windowStartMs) * size.width).toFloat()
                    drawLine(analysisColor(cue.color), Offset(x, 0f), Offset(x, size.height), 3f)
                }
            }
            drawLine(Color.White, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 3f)
            drawLine(Color(0xFF00E5FF), Offset(size.width / 2f - 2f, 0f), Offset(size.width / 2f - 2f, size.height), 1f)
        }
        Text("Oben Marker ziehen · unten wie ein Jog nach links oder rechts wischen", style = MaterialTheme.typography.labelSmall, color = Color(0xFF78909C), modifier = Modifier.padding(top = 4.dp))
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
        middle[index] = maxOf(low * 0.78f, mid * 0.9f) * extent
        core[index] = maxOf(low * 0.38f, high * 0.32f) * extent
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
