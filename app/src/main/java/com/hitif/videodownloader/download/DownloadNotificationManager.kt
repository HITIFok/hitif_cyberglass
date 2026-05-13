package com.hitif.videodownloader.download

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hitif.videodownloader.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralised notification manager for all downloads.
 * Shows per-download progress notifications + summary notification.
 * Replaces Android's DownloadManager notifications entirely.
 *
 * All public methods are thread-safe and catch exceptions internally.
 */
@SuppressLint("StaticFieldLeak")
object DownloadNotificationManager {

    private const val CHANNEL_ID = "hitif_download_channel"
    private const val GROUP_KEY = "hitif_downloads"
    private const val SUMMARY_ID = 9001
    private const val NOTIF_BASE_ID = 10000

    @Volatile
    private var context: Context? = null
    private val activeNotifs = ConcurrentHashMap<String, Int>()
    @Volatile
    private var nextNotifId = NOTIF_BASE_ID + 1
    @Volatile
    private var appLargeIcon: Bitmap? = null
    private var appLargeIconReady = false

    fun init(ctx: Context) {
        context = ctx.applicationContext
        createChannel()
        preloadLargeIcon()
    }

    // ── Large icon (full-color app icon) ───────────────────────────────

    private fun preloadLargeIcon() {
        try {
            val ctx = context ?: return
            val icon = ctx.packageManager.getApplicationIcon(ctx.packageName)
            val size = (64 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(64)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
            appLargeIcon = bitmap
            appLargeIconReady = true
        } catch (_: Exception) {
            appLargeIconReady = false
        }
    }

    // ── Channel ────────────────────────────────────────────────────────────

    private fun createChannel() {
        val ctx = context ?: return
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
            NotificationManagerCompat.from(ctx).createNotificationChannel(channel)
        }
    }

    // ── Notification ID per URL ────────────────────────────────────────────

    @Synchronized
    private fun getNotifId(url: String): Int {
        return activeNotifs.getOrPut(url) { nextNotifId++ }
    }

    // ── Progress notification (indeterminate or determinate) ──────────────

    fun showProgress(
        url: String,
        title: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        speedBps: Long,
        percent: Int
    ) {
        try {
            val ctx = context ?: return
            val notifId = getNotifId(url)
            val nm = NotificationManagerCompat.from(ctx)

            val sizeText = formatSize(bytesDownloaded)
            val speedText = formatSpeed(speedBps)
            val percentText = if (percent > 0) " — $percent%" else ""
            val etaText = if (speedBps > 0 && totalBytes > bytesDownloaded) {
                val remaining = totalBytes - bytesDownloaded
                val etaSec = remaining / speedBps
                " — ${formatEta(etaSec)}"
            } else ""
            val contentText = "$sizeText$percentText — $speedText$etaText"

            val indeterminate = percent <= 0

            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_hitif)
                .setContentTitle(title)
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setGroup(GROUP_KEY)
                .setGroupSummary(false)
                .setOngoing(true)
                .setSilent(true)
                .setProgress(100, percent.coerceIn(0, 100), indeterminate)
                .setOnlyAlertOnce(true)
            if (appLargeIconReady) appLargeIcon?.let { builder.setLargeIcon(it) }
            val notification = builder.build()

            nm.notify(notifId, notification)
        } catch (_: Exception) {}
    }

    // ── Complete notification ──────────────────────────────────────────────

    fun showComplete(url: String, title: String, fileSize: Long) {
        try {
            val ctx = context ?: return
            val notifId = getNotifId(url)
            val nm = NotificationManagerCompat.from(ctx)

            val sizeText = formatSize(fileSize)
            val contentText = "Terminé — $sizeText"

            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_hitif)
                .setContentTitle(title)
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setGroup(GROUP_KEY)
                .setGroupSummary(false)
                .setOngoing(false)
                .setAutoCancel(true)
            if (appLargeIconReady) appLargeIcon?.let { builder.setLargeIcon(it) }
            val notification = builder.build()

            nm.notify(notifId, notification)
        } catch (_: Exception) {}
    }

    // ── Error notification ─────────────────────────────────────────────────

    fun showError(url: String, title: String, message: String) {
        try {
            val ctx = context ?: return
            val notifId = getNotifId(url)
            val nm = NotificationManagerCompat.from(ctx)

            // Translate technical errors into user-friendly messages
            val userMessage = when {
                message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("No address found", ignoreCase = true) ||
                message.contains("UnknownHostException", ignoreCase = true) ||
                message.contains("No addr", ignoreCase = true) ->
                    "Serveur introuvable. Reessayez ou verifiez votre connexion."
                message.contains("timeout", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) ->
                    "Delai depasse. Le serveur ne repond pas."
                message.contains("403", ignoreCase = true) ||
                message.contains("Forbidden", ignoreCase = true) ->
                    "Acces refuse. Le lien peut avoir expire."
                message.contains("404", ignoreCase = true) ||
                message.contains("Not Found", ignoreCase = true) ->
                    "Fichier introuvable. Le lien est peut-etre invalide."
                else -> message.take(60)
            }
            val contentText = "Echec — $userMessage"

            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_hitif)
                .setContentTitle(title)
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setGroup(GROUP_KEY)
                .setGroupSummary(false)
                .setOngoing(false)
                .setAutoCancel(true)
            if (appLargeIconReady) appLargeIcon?.let { builder.setLargeIcon(it) }
            val notification = builder.build()

            nm.notify(notifId, notification)
        } catch (_: Exception) {}
    }

    // ── Summary notification ───────────────────────────────────────────────

    fun showSummary(activeCount: Int, aggregateSpeedBps: Long, totalPercent: Int) {
        try {
            val ctx = context ?: return
            val nm = NotificationManagerCompat.from(ctx)

            val contentText = if (activeCount > 0) {
                "$activeCount telechargement(s) — ${formatSpeed(aggregateSpeedBps)}${if (totalPercent > 0) " — $totalPercent%" else ""}"
            } else {
                "Telechargement termine"
            }

            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_hitif)
                .setContentTitle("HITIF Downloader")
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setOngoing(activeCount > 0)
                .setSilent(true)
                .setProgress(100, totalPercent.coerceIn(0, 100), totalPercent <= 0 && activeCount > 0)
                .setOnlyAlertOnce(true)
            if (appLargeIconReady) appLargeIcon?.let { builder.setLargeIcon(it) }
            val notification = builder.build()

            nm.notify(SUMMARY_ID, notification)
        } catch (_: Exception) {}
    }

    // ── Cleanup ────────────────────────────────────────────────────────────

    fun dismiss(url: String) {
        try {
            val ctx = context ?: return
            val notifId = activeNotifs.remove(url) ?: return
            NotificationManagerCompat.from(ctx).cancel(notifId)
        } catch (_: Exception) {}
    }

    fun dismissAll() {
        try {
            val ctx = context ?: return
            val nm = NotificationManagerCompat.from(ctx)
            activeNotifs.values.forEach { nm.cancel(it) }
            activeNotifs.clear()
            nm.cancel(SUMMARY_ID)
        } catch (_: Exception) {}
    }

    // ── Formatting ─────────────────────────────────────────────────────────

    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f Ko", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f Mo", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f Go", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    fun formatSpeed(bps: Long): String = when {
        bps <= 0 -> "0 B/s"
        bps < 1024 -> "$bps B/s"
        bps < 1024 * 1024 -> String.format("%.1f Ko/s", bps / 1024.0)
        else -> String.format("%.2f Mo/s", bps / (1024.0 * 1024.0))
    }

    private fun formatEta(seconds: Long): String {
        if (seconds <= 0) return ""
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    }

    /** Called from DownloadProgressService to keep notification channel alive */
    const val CHANNEL = CHANNEL_ID
}
