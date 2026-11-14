package dev.ide.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an app update inside the app, with progress, into the app's cache, and hands the file to the
 * system installer. Only https links are fetched.
 */
internal object UpdateDownloader {
    suspend fun download(context: Context, url: String, fileName: String, onProgress: (Float) -> Unit): String? =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://")) return@withContext null
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val part = File(dir, "$fileName.part")
            val target = File(dir, fileName)
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val total = conn.contentLengthLong
                    var done = 0L
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                onProgress(if (total > 0) done.toFloat() / total else -1f)
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                // An APK is a zip archive, so anything that does not start with "PK" is not one.
                val head = part.inputStream().use { s -> ByteArray(2).also { s.read(it) } }
                if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
                    part.delete()
                    return@runCatching null
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) return@runCatching null
                target.absolutePath
            }.getOrNull()
        }

    /** Opens the system installer for a file [download] fetched. Its own path, so the build-only check does not apply. */
    fun install(context: Context, path: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }
}
