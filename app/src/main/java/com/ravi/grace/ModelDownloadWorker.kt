package com.ravi.grace

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class PublicModelArtifact(
    val label: String,
    val filename: String,
    val expectedBytes: Long,
    val sha256: String,
    val url: String,
)

object PublicModels {
    val embedding = PublicModelArtifact(
        label = "EmbeddingGemma 300M",
        filename = "embeddinggemma-300M-Q8_0.gguf",
        expectedBytes = 333_590_944L,
        sha256 = "b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63",
        url = "https://huggingface.co/ggml-org/embeddinggemma-300M-GGUF/resolve/0f741b5a6585bd53aeb15cd1372c56f2a0f65e12/embeddinggemma-300M-Q8_0.gguf?download=true",
    )
    val gemma = PublicModelArtifact(
        label = "Gemma 4 E4B IT",
        filename = "gemma-4-E4B-it.litertlm",
        expectedBytes = 3_659_530_240L,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/f7ad3343bd6ebc9607f4dc3bc4f2398bd5749bc5/gemma-4-E4B-it.litertlm?download=true",
    )
}

object ModelDownloads {
    private const val UNIQUE_WORK = "grace-public-model-install"
    private const val PREFS = "grace-model-download"
    private const val VERIFIED_PREFIX = "verified-"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>().setConstraints(
            // This is an explicit user action. CONNECTED also works on Wi-Fi
            // networks Android has classified as metered, which otherwise leave
            // the request queued forever. The UI warns about the 4.1 GB transfer.
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).build()
        save(context, "Queued", PublicModels.embedding.filename, 0, PublicModels.embedding.expectedBytes)
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun status(context: Context): DownloadSnapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DownloadSnapshot(
            p.getString("stage", "Idle") ?: "Idle",
            p.getString("file", "") ?: "",
            p.getLong("current", 0),
            p.getLong("total", 0),
            p.getString("error", "") ?: "",
        )
    }

    internal fun save(
        context: Context,
        stage: String,
        file: String = "",
        current: Long = 0,
        total: Long = 0,
        error: String = "",
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("stage", stage)
            .putString("file", file)
            .putLong("current", current)
            .putLong("total", total)
            .putString("error", error)
            .apply()
    }

    internal fun isVerified(context: Context, artifact: PublicModelArtifact): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(VERIFIED_PREFIX + artifact.filename, "") == artifact.sha256

    internal fun markVerified(context: Context, artifact: PublicModelArtifact) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(VERIFIED_PREFIX + artifact.filename, artifact.sha256)
            .apply()
    }
}

data class DownloadSnapshot(val stage: String, val filename: String, val current: Long, val total: Long, val error: String) {
    val active get() = stage == "Downloading" || stage == "Queued"
    val percent get() = if (total > 0) ((current * 100L) / total).toInt().coerceIn(0, 100) else 0
}

/** Downloads only public, revision-pinned model files. No user credential is read, stored, or transmitted. */
class ModelDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            ModelDownloads.save(applicationContext, "Queued", PublicModels.embedding.filename, 0, PublicModels.embedding.expectedBytes)
            checkStorage()
            download(PublicModels.embedding)
            download(PublicModels.gemma)
            ModelDownloads.save(applicationContext, "Complete")
            LibraryIndexStatus.update(applicationContext, "Ready to index · open Grace")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val snapshot = ModelDownloads.status(applicationContext)
            ModelDownloads.save(applicationContext, "Failed", snapshot.filename, snapshot.current, snapshot.total, error.message ?: error.javaClass.simpleName)
            if ((error is HttpDownloadException && error.code in 400..499) || error is InsufficientStorageException) Result.failure() else Result.retry()
        }
    }

    private suspend fun download(artifact: PublicModelArtifact) {
        val directory = File(applicationContext.filesDir, "models").apply { mkdirs() }
        val destination = File(directory, artifact.filename)
        val part = File(directory, "${artifact.filename}.part")

        if (destination.length() == artifact.expectedBytes) {
            if (ModelDownloads.isVerified(applicationContext, artifact) || sha256(destination) == artifact.sha256) {
                ModelDownloads.markVerified(applicationContext, artifact)
                return
            }
            if (!destination.delete()) throw IOException("Could not replace invalid ${artifact.label}")
        }

        var completed = part.length().coerceAtLeast(0L)
        if (completed > artifact.expectedBytes) {
            if (!part.delete()) throw IOException("Could not reset invalid ${artifact.label} partial file")
            completed = 0L
        }
        if (completed == artifact.expectedBytes) {
            if (sha256(part) == artifact.sha256) {
                finalizePart(part, destination, artifact)
                publish(artifact, artifact.expectedBytes)
                return
            }
            if (!part.delete()) throw IOException("Could not reset invalid ${artifact.label} partial file")
            completed = 0L
        }

        // Put the worker in the foreground before opening the network stream.
        publish(artifact, completed)
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(artifact.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                if (completed > 0L) setRequestProperty("Range", "bytes=$completed-")
            }
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode == 416 && completed > 0L) {
                connection.disconnect()
                if (!part.delete()) throw IOException("Could not reset ${artifact.label} partial file")
                return download(artifact)
            }
            if (responseCode !in 200..299) throw HttpDownloadException(artifact, responseCode)

            val contentRange = connection.getHeaderField("Content-Range")
            val append = completed > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL &&
                contentRangeMatches(contentRange, completed, artifact.expectedBytes)
            if (completed > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL && !append) {
                // Restart safely if a CDN returns a partial response for a
                // different offset. Never append bytes with unknown provenance.
                connection.disconnect()
                if (!part.delete()) throw IOException("Could not restart ${artifact.label} download")
                return download(artifact)
            }
            if (!append) completed = 0L

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var lastReported = completed
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        completed += read
                        if (completed - lastReported >= 1_048_576L) {
                            publish(artifact, completed)
                            lastReported = completed
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection?.disconnect()
        }

        if (completed != artifact.expectedBytes) throw IllegalStateException("${artifact.label} was incomplete (${formatBytes(completed)} of ${formatBytes(artifact.expectedBytes)})")
        if (sha256(part) != artifact.sha256) throw IllegalStateException("${artifact.label} failed SHA-256 verification")
        finalizePart(part, destination, artifact)
        publish(artifact, artifact.expectedBytes)
    }

    private fun finalizePart(part: File, destination: File, artifact: PublicModelArtifact) {
        if (destination.exists() && !destination.delete()) throw IOException("Could not replace ${artifact.label}")
        if (!part.renameTo(destination)) throw IOException("Could not finalize ${artifact.label}")
        ModelDownloads.markVerified(applicationContext, artifact)
    }

    private fun checkStorage() {
        val directory = File(applicationContext.filesDir, "models").apply { mkdirs() }
        val missing = listOf(PublicModels.embedding, PublicModels.gemma).sumOf { artifact ->
            val destination = File(directory, artifact.filename)
            val partial = File(directory, "${artifact.filename}.part")
            when {
                destination.length() == artifact.expectedBytes -> 0L
                partial.length() in 1 until artifact.expectedBytes -> artifact.expectedBytes - partial.length()
                else -> artifact.expectedBytes
            }
        }
        val safetyBytes = 64L * 1024L * 1024L
        if (directory.usableSpace < missing + safetyBytes) {
            throw InsufficientStorageException("Not enough free storage; Grace needs about ${formatBytes(missing + safetyBytes)}")
        }
    }

    private fun contentRangeMatches(value: String?, start: Long, total: Long): Boolean {
        val match = Regex("bytes (\\d+)-\\d+/(\\d+)").matchEntire(value.orEmpty()) ?: return false
        return match.groupValues[1].toLongOrNull() == start && match.groupValues[2].toLongOrNull() == total
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private suspend fun publish(artifact: PublicModelArtifact, current: Long) {
        ModelDownloads.save(applicationContext, "Downloading", artifact.filename, current, artifact.expectedBytes)
        setProgress(workDataOf("file" to artifact.filename, "current" to current, "total" to artifact.expectedBytes))
        setForeground(ForegroundInfo(1207, progressNotification(artifact, current), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
    }

    private fun progressNotification(artifact: PublicModelArtifact, current: Long): android.app.Notification {
        val channel = "grace-model-download"
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel, "Grace model downloads", NotificationManager.IMPORTANCE_LOW))
        val percent = ((current * 100L) / artifact.expectedBytes).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.ic_grace_launcher)
            .setContentTitle("Downloading ${artifact.label}")
            .setContentText("$percent% · ${formatBytes(current)} of ${formatBytes(artifact.expectedBytes)}")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .build()
    }

    private fun ensureActive() { if (isStopped) throw InterruptedException("Download paused") }
    private fun formatBytes(value: Long): String = "%.1f GB".format(value / 1_073_741_824.0)
}

private class HttpDownloadException(artifact: PublicModelArtifact, val code: Int) : IOException("${artifact.label} download returned HTTP $code")
private class InsufficientStorageException(message: String) : IOException(message)
