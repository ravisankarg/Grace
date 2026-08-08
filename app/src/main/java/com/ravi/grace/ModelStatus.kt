package com.ravi.grace

import android.content.Context
import java.io.File

data class LocalModelStatus(val title: String, val detail: String, val ready: Boolean)

object ModelStatus {
    private fun modelFiles(context: Context) = File(context.filesDir, "models")
    private fun installed(context: Context, artifact: PublicModelArtifact) = File(modelFiles(context), artifact.filename).length() == artifact.expectedBytes
    fun embeddingInstalled(context: Context) = installed(context, PublicModels.embedding)
    private fun detailFor(context: Context, artifact: PublicModelArtifact, unavailable: String): LocalModelStatus {
        if (installed(context, artifact)) return LocalModelStatus(artifact.label, "Downloaded locally · ${formatBytes(artifact.expectedBytes)}", true)
        val snapshot = ModelDownloads.status(context)
        if (snapshot.filename == artifact.filename && snapshot.active) return LocalModelStatus(artifact.label, "Downloading ${snapshot.percent}% · ${formatBytes(snapshot.current)} of ${formatBytes(snapshot.total)}", false)
        val partial = File(modelFiles(context), "${artifact.filename}.part").length()
        if (partial > 0) return LocalModelStatus(artifact.label, "Ready to resume · ${formatBytes(partial)} of ${formatBytes(artifact.expectedBytes)}", false)
        if (snapshot.filename == artifact.filename && snapshot.error.isNotBlank()) return LocalModelStatus(artifact.label, "Download paused: ${snapshot.error}", false)
        return LocalModelStatus(artifact.label, unavailable, false)
    }

    fun gemma(context: Context) = detailFor(context, PublicModels.gemma, "Not downloaded · ${formatBytes(PublicModels.gemma.expectedBytes)}")
    fun embedding(context: Context) = detailFor(context, PublicModels.embedding, "Not downloaded · ${formatBytes(PublicModels.embedding.expectedBytes)}")
    fun library(context: Context): LocalModelStatus {
        val books = context.assets.list("books")?.count { it.endsWith(".pdf", true) } ?: 0
        return when {
            ScriptureLibrary.isReady(context) -> LocalModelStatus("Source library", "$books PDFs · ${ScriptureLibrary.count(context)} prebuilt passages in this app", true)
            else -> LocalModelStatus("Source library", "$books PDFs discovered · packaged index unavailable", false)
        }
    }

    private fun formatBytes(value: Long) = "%.1f GB".format(value / 1_073_741_824.0)
}
