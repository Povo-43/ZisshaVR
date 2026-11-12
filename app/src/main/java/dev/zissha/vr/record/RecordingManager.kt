package dev.zissha.vr.record

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.zissha.vr.core.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recording management (spec 8): file naming, app-private storage, MediaStore registration so
 * recordings appear in the gallery, listing and deletion.
 */
class RecordingManager(private val context: Context) {

    data class Recording(val file: File, val sizeBytes: Long, val lastModified: Long)

    fun recordingDir(): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "recordings")
            .apply { mkdirs() }

    fun newRecordingFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(recordingDir(), "ZisshaVR-$stamp.mp4")
    }

    fun list(): List<Recording> =
        recordingDir().listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.map { Recording(it, it.length(), it.lastModified()) }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

    fun delete(file: File): Boolean = file.delete()

    /** Register a finished recording with MediaStore so gallery apps see it. */
    fun registerInMediaStore(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ZisshaVR")
                    put(MediaStore.Video.Media.SIZE, file.length())
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                AppLog.i("recording published to MediaStore: ${file.name}")
            } else {
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
            }
        } catch (e: Exception) {
            AppLog.w("MediaStore registration failed: ${e.message}")
        }
    }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
            else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
        }

        fun formatTimestamp(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }
}
