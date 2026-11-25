package com.torrentx.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        // Start the engine right away (the service keeps it alive in the background).
        TorrentEngine.start(this)
        TorrentService.start(this)

        setContent { AppTheme { MainScreen(onExit = { exitApp() }) } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Handles magnet: links and .torrent files opened from other apps. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        if (!TorrentEngine.isRunning) {
            TorrentEngine.start(this)
            TorrentService.start(this)
        }
        if (data.scheme.equals("magnet", ignoreCase = true)) {
            addMagnetSafely(this, data.toString())
        } else {
            addTorrentUri(this, data)
        }
        setIntent(Intent(this, MainActivity::class.java)) // don't re-add on rotation
    }

    private fun exitApp() {
        TorrentService.exit(this)
        finishAndRemoveTask()
    }
}

private fun addMagnetSafely(context: android.content.Context, uri: String) {
    try {
        TorrentEngine.addMagnet(uri)
        Toast.makeText(context, "Magnet added", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Invalid magnet link", Toast.LENGTH_LONG).show()
    }
}

private fun addTorrentUri(context: android.content.Context, uri: Uri) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        val result = runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read file")
            TorrentEngine.addTorrentBytes(bytes)
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                if (result.isSuccess) "Torrent added" else "Not a valid .torrent file",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

// ---------------- UI ----------------

private val Green = Color(0xFF1F8A4C)

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme())
        darkColorScheme(primary = Color(0xFF5BD48C))
    else
        lightColorScheme(primary = Green)
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Filter(val label: String) { ALL("All"), ACTIVE("Downloading"), DONE("Completed") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val torrents by TorrentEngine.torrents.collectAsStateWithLifecycle()
    val stats by TorrentEngine.stats.collectAsStateWithLifecycle()

    var filter by remember { mutableIntStateOf(0) }
    var showMagnetDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<TorrentItem?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) addTorrentUri(context, uri)
    }

    val shown = when (Filter.entries[filter]) {
        Filter.ALL -> torrents
        Filter.ACTIVE -> torrents.filter { !it.finished }
        Filter.DONE -> torrents.filter { it.finished }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("TorrentX", fontWeight = FontWeight.Bold)
                            Text(
                                "↓ ${formatSpeed(stats.down)}   ↑ ${formatSpeed(stats.up)}   DHT ${stats.dhtNodes}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showMagnetDialog = true }) {
                            Icon(Icons.Default.Link, "Add magnet link")
                        }
                        IconButton(onClick = { pickFile.launch("*/*") }) {
                            Icon(Icons.Default.Add, "Open .torrent file")
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "More")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("Resume all") },
                                onClick = { TorrentEngine.resumeAll(); showMenu = false })
                            DropdownMenuItem(text = { Text("Pause all") },
                                onClick = { TorrentEngine.pauseAll(); showMenu = false })
                            DropdownMenuItem(text = { Text("Download folder") }, onClick = {
                                showMenu = false
                                Toast.makeText(context, TorrentEngine.saveDir.absolutePath, Toast.LENGTH_LONG).show()
                            })
                            DropdownMenuItem(text = { Text("Exit") }, onClick = { showMenu = false; onExit() })
                        }
                    }
                )
                TabRow(selectedTabIndex = filter) {
                    Filter.entries.forEachIndexed { i, f ->
                        Tab(selected = filter == i, onClick = { filter = i }, text = { Text(f.label) })
                    }
                }
            }
        }
    ) { padding ->
        if (shown.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No torrents", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(4.dp))
                Text(
                    "Tap the link icon to paste a magnet link, or + to open a .torrent file.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.hash }) { t ->
                    TorrentCard(
                        t,
                        onToggle = { if (t.paused) TorrentEngine.resume(t.hash) else TorrentEngine.pause(t.hash) },
                        onDelete = { toDelete = t }
                    )
                }
            }
        }
    }

    if (showMagnetDialog) MagnetDialog(
        onDismiss = { showMagnetDialog = false },
        onAdd = { addMagnetSafely(context, it); showMagnetDialog = false }
    )

    toDelete?.let { t -> DeleteDialog(t, onDismiss = { toDelete = null }) { deleteFiles ->
        TorrentEngine.remove(t.hash, deleteFiles)
        toDelete = null
    } }
}

@Composable
private fun TorrentCard(t: TorrentItem, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.name, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onToggle) {
                    Icon(
                        if (t.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        if (t.paused) "Resume" else "Pause"
                    )
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove") }
            }
            LinearProgressIndicator(
                progress = { t.progress },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
            Row {
                Text(
                    "${t.state} • ${"%.1f".format(t.progress * 100)}%",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f)
                )
                Text(
                    "${formatBytes(t.done)} / ${formatBytes(t.total)}",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Row {
                Text(
                    "↓ ${formatSpeed(t.downSpeed)}  ↑ ${formatSpeed(t.upSpeed)}",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)
                )
                Text(
                    "Peers ${t.peers} (${t.seeds} seeds)" +
                        if (!t.finished && !t.paused) "  ETA ${formatEta(t.etaSeconds)}" else "",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun MagnetDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember {
        val clip = clipboard.getText()?.text.orEmpty()
        mutableStateOf(if (clip.startsWith("magnet:", true)) clip else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add magnet link") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("magnet:?xt=urn:btih:…") },
                modifier = Modifier.fillMaxWidth(), maxLines = 4
            )
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DeleteDialog(t: TorrentItem, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var deleteFiles by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove torrent?") },
        text = {
            Column {
                Text(t.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                    Spacer(Modifier.width(4.dp))
                    Text("Also delete downloaded files")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(deleteFiles) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
