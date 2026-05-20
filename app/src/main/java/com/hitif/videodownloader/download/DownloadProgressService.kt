package com.hitif.videodownloader.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hitif.videodownloader.R
import com.hitif.videodownloader.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps download notifications alive.
 * Polls Room DB every 1.5s for active downloads and updates the summary notification.
 *
 * Stale detection logic (simple & correct):
 *   A DB record marked DOWNLOADING or QUEUED is truly failed if and only if
 *   its URL is NOT present in TurboDownloadEngine.getActiveUrls() nor
 *   in HlsDownloader.getActiveUrls().
 *   No time threshold needed — the engines are the single source of truth.
 */
class DownloadProgressService : Service() {

    companion object {
        const val CHANNEL_ID       = "hitif_download_channel"
        private const val GRACE_PERIOD_MS  = 20_000L
        private const val POLL_INTERVAL_MS = 2_000L
        /** Number of consecutive stale cycles before marking FAILED.
         *  Set to 10 (~20 s) to tolerate slow connections where a download
         *  may briefly stall while the engine retries a timed-out chunk. */
        private const val STALE_THRESHOLD  = 10

        @Volatile private var isRunning = false

        fun start(context: Context) {
            if (isRunning) return
            isRunning = true
            val intent = Intent(context, DownloadProgressService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadProgressService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var pollingJob: Job? = null
    private var graceJob:   Job? = null
    private var databaseReady = false
    @Volatile private var serviceWakeLock: PowerManager.WakeLock? = null

    /** URL → consecutive stale cycle count. Marked FAILED only after STALE_THRESHOLD cycles. */
    private val staleCycleCount = mutableMapOf<String, Int>()
    /** URLs still warming up — seen as DOWNLOADING but not yet in engine (give insert time) */
    private val warmingUp = mutableSetOf<String>()

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    override fun onCreate() {
        super.onCreate()
        // Notification channel is already created by DownloadNotificationManager.init()
        // in App.onCreate(), no need to create it again here.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hitif)
            .setContentTitle("HITIF Downloader")
            .setContentText("Prêt")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            val appIcon = packageManager.getApplicationIcon(packageName)
            val size = (64 * resources.displayMetrics.density).toInt().coerceAtLeast(64)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            appIcon.setBounds(0, 0, size, size)
            appIcon.draw(canvas)
            builder.setLargeIcon(bitmap)
        } catch (_: Exception) {}

        startForegroundCompat(1000, builder.build())
        acquireServiceWakeLock()
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        databaseReady = false
        pollingJob?.cancel()
        graceJob?.cancel()
        releaseServiceWakeLock()
        try { DownloadNotificationManager.dismissAll() } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ //
    //  Service-level WakeLock
    // ------------------------------------------------------------------ //

    private fun acquireServiceWakeLock() {
        if (serviceWakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            serviceWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "hitif:progress_service_wakelock"
            ).apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L /* 30 min max */)
            }
        } catch (_: Exception) {}
    }

    private fun releaseServiceWakeLock() {
        try { serviceWakeLock?.release() } catch (_: Exception) {}
        serviceWakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Téléchargements HITIF",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progression des téléchargements"
                setShowBadge(true)
                setSound(null, null)
            }
            NotificationManagerCompat.from(this).createNotificationChannel(channel)
        }
    }

    // ------------------------------------------------------------------ //
    //  Lazy DB init
    // ------------------------------------------------------------------ //

    private fun getDatabase(): AppDatabase? {
        if (!databaseReady) {
            try { AppDatabase.getInstance(this); databaseReady = true } catch (_: Exception) {}
        }
        return if (databaseReady) AppDatabase.getInstance(this) else null
    }

    // ------------------------------------------------------------------ //
    //  Polling
    // ------------------------------------------------------------------ //

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                try { pollFromDatabase() } catch (_: Exception) {}
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollFromDatabase() {
        val db = getDatabase() ?: return
        val now = System.currentTimeMillis()

        // ── Source-of-truth stale detection (with extended grace period) ──────
        //
        // Rule: a record is DOWNLOADING/QUEUED in the DB but the engine no
        //       longer has it → POTENTIALLY stale.
        //       We wait 10 poll cycles (~20 s) before marking FAILED to give
        //       the onComplete callback time to update the DB to COMPLETED,
        //       AND to tolerate slow connections where the engine is retrying
        //       a timed-out chunk/segment.
        //       This fixes the race condition where onComplete fires an async
        //       DB update (scope.launch) but the engine removes the URL from
        //       activeJobs immediately in its finally block.
        //
        //       Also: newly inserted DOWNLOADING records get a 2-cycle warm-up
        //       to prevent false positives on slow devices.
        //
        //       IMPORTANT: After a process kill+restart (START_STICKY), all
        //       in-memory engine state is lost. DOWNLOADING records from the
        //       DB may still be valid — the user needs to see them. We do NOT
        //       mark them FAILED immediately. The extended grace period gives
        //       the user time to return to the app and take action.
        //
        val activeUrls = TurboDownloadEngine.getActiveUrls() + HlsDownloader.getActiveUrls()

        val allRecords = try {
            db.downloadDao().getRecent(200)
        } catch (_: Exception) { return }

        // Step 1: find records that appear stale THIS cycle
        val newlyStaleUrls = allRecords
            .filter { record ->
                (record.state == "DOWNLOADING" || record.state == "QUEUED") &&
                record.url !in activeUrls &&
                record.url !in warmingUp
            }
            .map { it.url }
            .toSet()

        // Step 2: add newly-seen DOWNLOADING URLs to warm-up set (2 cycles grace)
        val activeInDb = allRecords
            .filter { it.state == "DOWNLOADING" || it.state == "QUEUED" }
            .map { it.url }
            .toSet()
        val newInEngine = activeUrls.filter { it in activeInDb && it !in warmingUp }
        warmingUp.addAll(newInEngine)

        // Step 3: increment stale counters, confirm FAILED only after STALE_THRESHOLD
        val confirmedFailed = mutableListOf<String>()
        for (url in newlyStaleUrls) {
            val count = (staleCycleCount[url] ?: 0) + 1
            staleCycleCount[url] = count
            if (count >= STALE_THRESHOLD) {
                confirmedFailed.add(url)
            }
        }
        confirmedFailed.forEach { url ->
            try {
                db.downloadDao().updateStateByUrl(
                    url = url, state = "FAILED", ts = now
                )
            } catch (_: Exception) {}
            staleCycleCount.remove(url)
        }

        // Step 4: reset counters for URLs no longer stale (re-appeared in engine)
        for (url in staleCycleCount.keys.toList()) {
            if (url !in newlyStaleUrls) staleCycleCount.remove(url)
        }

        // Step 5: clean warm-up URLs that are now in the engine
        warmingUp.removeAll(activeUrls)

        // ── Summary notification ────────────────────────────────────────────
        val activeRecords     = allRecords.filter { it.state == "DOWNLOADING" || it.state == "QUEUED" }
        val downloadingRecords = activeRecords.filter { it.state == "DOWNLOADING" }

        if (activeRecords.isEmpty()) {
            scheduleGrace()
            return
        }
        cancelGrace()

        val totalSpeed      = downloadingRecords.sumOf { it.speedBps }
        val totalDownloaded = downloadingRecords.sumOf { it.progressBytes }
        val totalSize       = downloadingRecords.sumOf { it.totalBytes }
        val totalPercent    = if (totalSize > 0)
            ((totalDownloaded * 100) / totalSize).toInt().coerceIn(0, 100)
        else 0

        try {
            DownloadNotificationManager.showSummary(
                activeCount       = downloadingRecords.size,
                aggregateSpeedBps = totalSpeed,
                totalPercent      = totalPercent
            )
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ //
    //  Grace period (auto-stop when idle)
    // ------------------------------------------------------------------ //

    private fun scheduleGrace() {
        if (graceJob?.isActive == true) return
        graceJob = serviceScope.launch {
            delay(GRACE_PERIOD_MS)
            val db = getDatabase()
            if (db == null) { stopSelf(); return@launch }

            val hasActive = try {
                db.downloadDao().getRecent(50).any {
                    it.state == "DOWNLOADING" || it.state == "QUEUED"
                }
            } catch (_: Exception) { false }

            if (!hasActive) {
                try { DownloadNotificationManager.showSummary(0, 0L, 0) } catch (_: Exception) {}
                stopSelf()
            }
        }
    }

    private fun cancelGrace() {
        graceJob?.cancel()
        graceJob = null
    }

    // ------------------------------------------------------------------ //
    //  Foreground compat
    // ------------------------------------------------------------------ //

    private fun startForegroundCompat(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, 16 /* FOREGROUND_SERVICE_TYPE_DATA_SYNC */)
        } else {
            startForeground(id, notification)
        }
    }
}
