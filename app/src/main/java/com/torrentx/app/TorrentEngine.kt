package com.torrentx.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Snapshot of one torrent, shown in the UI. */
data class TorrentItem(
    val hash: String,
    val name: String,
    val progress: Float,          // 0..1
    val state: String,
    val downSpeed: Long,          // bytes/s
    val upSpeed: Long,            // bytes/s
    val peers: Int,
    val seeds: Int,
    val done: Long,
    val total: Long,
    val paused: Boolean,
    val finished: Boolean,
) {
    val etaSeconds: Long
        get() = if (downSpeed > 0 && total > done) (total - done) / downSpeed else -1
}

data class GlobalStats(val down: Long = 0, val up: Long = 0, val dhtNodes: Long = 0)

/**
 * Wraps a single libtorrent session for the whole app.
 * Torrents are remembered across restarts in SharedPreferences
 * (magnet link, or a copy of the .torrent file in app storage).
 */
object TorrentEngine {
    private const val TAG = "TorrentEngine"

    private val session = SessionManager()
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    private val _torrents = MutableStateFlow<List<TorrentItem>>(emptyList())
    val torrents: StateFlow<List<TorrentItem>> = _torrents

    private val _stats = MutableStateFlow(GlobalStats())
    val stats: StateFlow<GlobalStats> = _stats

    @Volatile var isRunning = false
        private set

    lateinit var saveDir: File
        private set
    private lateinit var torrentDir: File
    private lateinit var sources: SharedPreferences   // hash -> magnet uri
    private lateinit var pausedPrefs: SharedPreferences // hash -> true

    @Synchronized
    fun start(context: Context) {
        if (isRunning) return
        val app = context.applicationContext
        saveDir = (app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(app.filesDir, "downloads"))
            .apply { mkdirs() }
        torrentDir = File(app.filesDir, "torrents").apply { mkdirs() }
        sources = app.getSharedPreferences("torrent_sources", Context.MODE_PRIVATE)
        pausedPrefs = app.getSharedPreferences("torrent_paused", Context.MODE_PRIVATE)

        session.addListener(object : AlertListener {
            override fun types(): IntArray? = null // receive all alerts
            override fun alert(alert: Alert<*>) {
                when (alert) {
                    is AddTorrentAlert -> onAdded(alert)
                    is TorrentFinishedAlert -> Log.i(TAG, "Finished: ${alert.torrentName()}")
                    else -> Unit
                }
            }
        })
        session.start()
        isRunning = true
        restore()

        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(1000)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        pollJob?.cancel()
        session.stop()
        handles.clear()
        _torrents.value = emptyList()
        _stats.value = GlobalStats()
        isRunning = false
    }

    // ---------- Adding ----------

    fun addMagnet(uri: String) {
        val clean = uri.trim()
        require(clean.startsWith("magnet:", ignoreCase = true)) { "Not a magnet link" }
        session.download(clean, saveDir, torrent_flags_t())
    }

    fun addTorrentBytes(bytes: ByteArray) {
        val ti = TorrentInfo(bytes)
        val hash = ti.infoHash().toHex()
        File(torrentDir, "$hash.torrent").writeBytes(bytes)
        session.download(ti, saveDir)
    }

    private fun onAdded(alert: AddTorrentAlert) {
        if (alert.error().isError) {
            Log.w(TAG, "Add failed: ${alert.error()}")
            return
        }
        val h = alert.handle()
        if (!h.isValid) return
        val hash = h.infoHash().toHex()
        handles[hash] = h
        if (!sources.contains(hash)) {
            sources.edit().putString(hash, h.makeMagnetUri()).apply()
        }
        if (pausedPrefs.getBoolean(hash, false)) {
            h.unsetFlags(TorrentFlags.AUTO_MANAGED)
            h.pause()
        }
    }

    private fun restore() {
        for ((hash, value) in sources.all) {
            try {
                val file = File(torrentDir, "$hash.torrent")
                if (file.exists()) {
                    session.download(TorrentInfo(file), saveDir)
                } else if (value is String && value.isNotBlank()) {
                    session.download(value, saveDir, torrent_flags_t())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore $hash", e)
            }
        }
    }

    // ---------- Controls ----------

    fun pause(hash: String) {
        val h = handles[hash] ?: return
        h.unsetFlags(TorrentFlags.AUTO_MANAGED)
        h.pause()
        pausedPrefs.edit().putBoolean(hash, true).apply()
    }

    fun resume(hash: String) {
        val h = handles[hash] ?: return
        h.setFlags(TorrentFlags.AUTO_MANAGED)
        h.resume()
        pausedPrefs.edit().remove(hash).apply()
    }

    fun pauseAll() = handles.keys.forEach { pause(it) }
    fun resumeAll() = handles.keys.forEach { resume(it) }

    fun remove(hash: String, deleteFiles: Boolean) {
        val h = handles.remove(hash) ?: return
        if (deleteFiles) session.remove(h, SessionHandle.DELETE_FILES) else session.remove(h)
        sources.edit().remove(hash).apply()
        pausedPrefs.edit().remove(hash).apply()
        File(torrentDir, "$hash.torrent").delete()
        refresh()
    }

    // ---------- Status polling ----------

    private fun refresh() {
        if (!isRunning) return
        val items = handles.mapNotNull { (hash, h) ->
            try {
                if (!h.isValid) return@mapNotNull null
                val st = h.status()
                val paused = pausedPrefs.getBoolean(hash, false)
                TorrentItem(
                    hash = hash,
                    name = st.name().ifBlank { "Fetching metadata…" },
                    progress = st.progress(),
                    state = if (paused) "Paused" else stateLabel(st.state()),
                    downSpeed = st.downloadPayloadRate().toLong(),
                    upSpeed = st.uploadPayloadRate().toLong(),
                    peers = st.numPeers(),
                    seeds = st.numSeeds(),
                    done = st.totalWantedDone(),
                    total = st.totalWanted(),
                    paused = paused,
                    finished = st.isFinished,
                )
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.name.lowercase() }

        _torrents.value = items
        _stats.value = GlobalStats(
            down = items.sumOf { it.downSpeed },
            up = items.sumOf { it.upSpeed },
            dhtNodes = session.dhtNodes(),
        )
    }

    private fun stateLabel(s: TorrentStatus.State): String = when (s) {
        TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> "Checking"
        TorrentStatus.State.DOWNLOADING_METADATA -> "Getting metadata"
        TorrentStatus.State.DOWNLOADING -> "Downloading"
        TorrentStatus.State.FINISHED -> "Finished"
        TorrentStatus.State.SEEDING -> "Seeding"
        else -> "Queued"
    }
}
