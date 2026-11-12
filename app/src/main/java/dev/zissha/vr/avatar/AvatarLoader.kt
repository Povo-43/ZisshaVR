package dev.zissha.vr.avatar

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.zissha.vr.avatar.gltf.GlbParser
import dev.zissha.vr.avatar.gltf.GltfDocument
import dev.zissha.vr.core.AppLog
import java.io.File
import java.io.FileOutputStream

/**
 * Avatar Loader (spec 3): imports .vrm / .glb / .gltf files into the app-private avatar store
 * and parses them. Loading is fully offline.
 */
class AvatarLoader(private val context: Context) {

    companion object {
        private const val TAG = "AvatarLoader"
        private const val AVATAR_DIR = "avatars"
    }

    data class ImportedAvatar(val name: String, val file: File, val sizeBytes: Long)

    fun avatarDir(): File = File(context.filesDir, AVATAR_DIR).apply { mkdirs() }

    fun listAvatars(): List<ImportedAvatar> {
        return avatarDir().listFiles { f ->
            f.isFile && (f.name.endsWith(".vrm", true) || f.name.endsWith(".glb", true) || f.name.endsWith(".gltf", true))
        }?.map { ImportedAvatar(it.name, it, it.length()) }?.sortedBy { it.name } ?: emptyList()
    }

    fun deleteAvatar(name: String): Boolean {
        if (!isSafeName(name)) return false
        return File(avatarDir(), name).delete()
    }

    /**
     * Copy a content-URI picked via SAF into the avatar store.
     * @return stored file name or null on failure
     */
    fun importFromUri(uri: Uri, displayName: String?): String? {
        var name = sanitizeFileName(displayName ?: "avatar.vrm")
        if (!name.endsWith(".vrm", true) && !name.endsWith(".glb", true) && !name.endsWith(".gltf", true)) {
            name += ".vrm"
        }
        // avoid overwrites
        var candidate = name
        var n = 1
        while (File(avatarDir(), candidate).exists()) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) {
                name.substring(0, dot) + "_$n" + name.substring(dot)
            } else {
                "${name}_$n"
            }
            n++
        }
        return try {
            val out = File(avatarDir(), candidate)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            } ?: return null
            AppLog.i("avatar imported: $candidate (${out.length()} bytes)")
            candidate
        } catch (e: Exception) {
            Log.e(TAG, "avatar import failed", e)
            AppLog.e("avatar import failed: ${e.message}")
            null
        }
    }

    /** Parse an avatar file. Expensive: run off the UI thread; the result is handed to the GL thread. */
    fun parse(file: File): GltfDocument {
        val bytes = file.readBytes()
        val doc = GlbParser.parse(bytes)
        AppLog.i(
            "avatar parsed: ${file.name} nodes=${doc.nodes.size} meshes=${doc.meshes.size} " +
                "skins=${doc.skins.size} vrm=${doc.vrm != null}",
        )
        return doc
    }

    private fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && !name.contains("..") && !name.contains('/') && !name.contains('\\')

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._\\- ]"), "_")
        return if (cleaned.isBlank()) "avatar.vrm" else cleaned
    }
}
