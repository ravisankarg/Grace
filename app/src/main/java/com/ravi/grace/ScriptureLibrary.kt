package com.ravi.grace

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.sqrt

private const val LIBRARY_PREFS = "grace-library-index"
private const val INDEX_ASSET = "indexes/scripture-index-v3.jsonl"

data class LibraryIndexSnapshot(
    val stage: String,
    val current: Int,
    val total: Int,
    val book: String,
    val error: String,
    val completed: Boolean,
) {
    val percent: Int get() = if (total > 0) (current * 100 / total).coerceIn(0, 100) else 0
}

/** Status of the index bundled with the app; source PDFs are never embedded on the phone. */
object LibraryIndexStatus {
    fun read(context: Context): LibraryIndexSnapshot {
        val p = context.getSharedPreferences(LIBRARY_PREFS, Context.MODE_PRIVATE)
        return LibraryIndexSnapshot(
            stage = p.getString("stage", "Bundled source index") ?: "Bundled source index",
            current = p.getInt("current", 0),
            total = p.getInt("total", 0),
            book = p.getString("book", "") ?: "",
            error = p.getString("error", "") ?: "",
            completed = p.getBoolean("completed", false),
        )
    }

    fun update(
        context: Context,
        stage: String,
        current: Int = 0,
        total: Int = 0,
        book: String = "",
        error: String = "",
        completed: Boolean = false,
    ) {
        context.getSharedPreferences(LIBRARY_PREFS, Context.MODE_PRIVATE).edit()
            .putString("stage", stage).putInt("current", current).putInt("total", total)
            .putString("book", book).putString("error", error).putBoolean("completed", completed).apply()
        val friendly = when {
            completed -> "$current source passages are already included with Grace"
            error.isNotBlank() -> "Bundled index unavailable: $error"
            else -> stage
        }
        context.getSharedPreferences("grace-practice", Context.MODE_PRIVATE).edit()
            .putString("library-status", friendly).apply()
    }
}

object LibraryIndexScheduler {
    /** Compatibility entry point for existing callers. It never starts document embedding. */
    fun enqueueIfReady(context: Context, replaceExisting: Boolean = false) {
        val total = ScriptureLibrary.count(context)
        if (total > 0) {
            LibraryIndexStatus.update(context, "Bundled source index", total, total, completed = true)
        } else {
            LibraryIndexStatus.update(context, "Bundled index unavailable", error = "The packaged source index is missing")
        }
    }
}

data class ScripturePassage(val book: String, val page: Int, val text: String, val vector: FloatArray)
data class ScriptureMatch(val book: String, val page: Int, val text: String, val score: Float)

/**
 * Precomputed document vectors travel inside the APK. At runtime Grace opens
 * EmbeddingGemma only to encode the reflection query, then cosine-matches it
 * against the bundled index. No PDF extraction or document encoding happens
 * on the phone.
 */
object ScriptureLibrary {
    private const val QUERY_PREFIX = "task: search result | query: "
    private val nativeLock = Any()

    fun isReady(context: Context): Boolean = runCatching {
        context.assets.open(INDEX_ASSET).close()
        true
    }.getOrDefault(false)

    fun count(context: Context): Int = runCatching {
        context.assets.open(INDEX_ASSET).bufferedReader().useLines { it.count() }
    }.getOrDefault(0)

    /** Each user record is embedded separately and must clear this cosine threshold. */
    fun search(context: Context, query: String, limit: Int = 3, minScore: Float = 0.30f): List<ScriptureMatch> {
        if (!isReady(context) || query.isBlank() || !ModelStatus.embeddingInstalled(context)) return emptyList()
        val model = java.io.File(java.io.File(context.filesDir, "models"), PublicModels.embedding.filename)
        val vector = synchronized(nativeLock) {
            if (!EmbeddingNative.open(model.absolutePath)) return emptyList()
            try { normalize(EmbeddingNative.embed(QUERY_PREFIX + query) ?: return emptyList()) }
            finally { EmbeddingNative.close() }
        }
        if (vector.isEmpty()) return emptyList()
        return read(context).asSequence()
            .filter { it.vector.size == vector.size }
            .map { passage -> ScriptureMatch(passage.book, passage.page, passage.text, cosine(vector, passage.vector)) }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    fun read(context: Context): Sequence<ScripturePassage> = sequence {
        try {
            BufferedReader(InputStreamReader(context.assets.open(INDEX_ASSET))).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val passage = decode(line)
                    if (passage != null) yield(passage)
                }
            }
        } catch (_: Throwable) { }
    }

    private fun decode(line: String): ScripturePassage? = runCatching {
        val objectValue = JSONObject(line)
        ScripturePassage(
            objectValue.getString("book"), objectValue.getInt("page"), objectValue.getString("text"),
            bytesToFloats(Base64.decode(objectValue.getString("vector"), Base64.NO_WRAP)),
        )
    }.getOrNull()

    internal fun normalize(input: FloatArray): FloatArray {
        val norm = sqrt(input.fold(0.0) { total, value -> total + value * value })
        return if (norm <= 0.0) FloatArray(0) else FloatArray(input.size) { i -> (input[i] / norm).toFloat() }
    }

    private fun cosine(left: FloatArray, right: FloatArray): Float = left.indices.sumOf { (left[it] * right[it]).toDouble() }.toFloat()

    private fun bytesToFloats(bytes: ByteArray): FloatArray {
        if (bytes.size % 4 != 0) return FloatArray(0)
        val result = FloatArray(bytes.size / 4)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(result)
        return result
    }
}
