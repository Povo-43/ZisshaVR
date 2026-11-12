package dev.zissha.vr.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logcat + rotating file logger (spec 2). Keeps up to [maxFiles] files of [maxFileBytes].
 * Never log secrets (RTMP stream keys, BLE tokens).
 */
object AppLog {

    private const val TAG = "ZisshaVR"
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MAX_FILES = 5
    private const val PREFIX = "app"

    private var logDir: File? = null
    private var writer: FileWriter? = null
    private var currentFile: File? = null
    private var currentSize = 0L
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (logDir != null) return
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            logDir = dir
            rotateIfNeeded(force = true)
        }
    }

    fun i(msg: String) = log(Log.INFO, msg)
    fun w(msg: String) = log(Log.WARN, msg)
    fun e(msg: String, e: Throwable? = null) = log(Log.ERROR, msg, e)

    private fun log(priority: Int, msg: String, t: Throwable? = null) {
        when (priority) {
            Log.INFO -> Log.i(TAG, msg, t)
            Log.WARN -> Log.w(TAG, msg, t)
            Log.ERROR -> Log.e(TAG, msg, t)
        }
        val line = synchronized(lock) {
            buildString {
                append(dateFormat.format(Date()))
                append(' ')
                append(
                    when (priority) {
                        Log.INFO -> 'I'
                        Log.WARN -> 'W'
                        else -> 'E'
                    },
                )
                append(' ')
                append(msg)
                if (t != null) {
                    append(" :: ").append(t.javaClass.simpleName).append(": ").append(t.message)
                }
                append('\n')
            }
        }
        synchronized(lock) {
            try {
                val w = writer ?: return
                w.write(line)
                w.flush()
                currentSize += line.length
                rotateIfNeeded(force = false)
            } catch (e: Exception) {
                Log.w(TAG, "file log write failed", e)
            }
        }
    }

    private fun rotateIfNeeded(force: Boolean) {
        val dir = logDir ?: return
        if (!force && currentSize < MAX_FILE_BYTES && writer != null) return
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        // prune old files
        val files = dir.listFiles { f -> f.name.startsWith(PREFIX) }?.sortedBy { it.name } ?: emptyList()
        if (files.size >= MAX_FILES) {
            files.first().delete()
        }
        val name = "$PREFIX-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.log"
        val file = File(dir, name)
        writer = FileWriter(file, true)
        currentFile = file
        currentSize = 0
    }

    fun logFiles(): List<File> = logDir?.listFiles { f -> f.name.startsWith(PREFIX) }?.sortedBy { it.name } ?: emptyList()
}
