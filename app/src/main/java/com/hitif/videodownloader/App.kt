package com.hitif.videodownloader

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.hitif.videodownloader.download.DownloadHelper
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize notification manager early (no service start yet)
        com.hitif.videodownloader.download.DownloadNotificationManager.init(this)
        // DownloadHelper.init() will be called lazily on first download
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("HITIF_CRASH", "Uncaught exception on thread ${t.name}", e)
            try {
                saveCrashLog(e)
            } catch (_: Exception) {}
            oldHandler?.uncaughtException(t, e)
        }
    }

    private fun saveCrashLog(throwable: Throwable) {
        try {
            val dir = File(filesDir, "crash_logs")
            if (!dir.exists()) dir.mkdirs()
            val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            val file = File(dir, "crash_${fmt.format(Date())}.txt")
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("=== HITIF Crash Log ===")
            pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            pw.println("App version: ${packageManager.getPackageInfo(packageName, 0).versionName}")
            pw.println("Time: ${fmt.format(Date())}")
            pw.println("=========================")
            throwable.printStackTrace(pw)
            file.writeText(sw.toString())
        } catch (_: Exception) {}
    }
}
