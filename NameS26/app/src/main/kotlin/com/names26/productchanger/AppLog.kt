package com.names26.productchanger

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Small diagnostic logger: writes to logcat, to a rotating file in the app's private storage
 * (exportable from the UI) and keeps the most recent lines in memory for the on-screen log card.
 */
object AppLog {
    private const val TAG = "NameS26"
    private const val FILE_NAME = "names26.log"
    private const val MAX_FILE_BYTES = 768L * 1024
    private const val MAX_MEMORY_LINES = 400

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    private val listeners = mutableListOf<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var logFile: File? = null

    @Synchronized
    fun init(context: Context) {
        if (logFile != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        if (f.exists() && f.length() > MAX_FILE_BYTES) {
            val old = File(f.parentFile, "$FILE_NAME.1")
            old.delete()
            f.renameTo(old)
        }
        logFile = f
        i("log", "---- NameS26 session started ----")
    }

    fun i(scope: String, msg: String) = write("I", scope, msg)
    fun w(scope: String, msg: String) = write("W", scope, msg)
    fun e(scope: String, msg: String, t: Throwable? = null) =
        write("E", scope, if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}")

    private fun write(level: String, scope: String, msg: String) {
        val line = "${formatter.format(Date())} $level/$scope: $msg"
        when (level) {
            "E" -> Log.e(TAG, "$scope: $msg")
            "W" -> Log.w(TAG, "$scope: $msg")
            else -> Log.i(TAG, "$scope: $msg")
        }
        synchronized(this) {
            lines.addLast(line)
            while (lines.size > MAX_MEMORY_LINES) lines.removeFirst()
            val f = logFile
            if (f != null) {
                try {
                    FileOutputStream(f, true).use { out -> out.write((line + "\n").toByteArray(Charsets.UTF_8)) }
                } catch (_: Exception) {
                    // Logging must never break the app.
                }
            }
        }
        mainHandler.post {
            val snapshot = synchronized(this) { listeners.toList() }
            snapshot.forEach { it() }
        }
    }

    @Synchronized
    fun recent(): String = lines.joinToString("\n")

    fun file(): File? = logFile

    @Synchronized
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
