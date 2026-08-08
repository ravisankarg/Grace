package com.ravi.grace

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

enum class WashStage {
    STARTING_GPU, GPU_READY, TAGGING, SEARCHING, ANSWERING, COMPLETE
}

data class WashProgress(
    val stage: WashStage,
    val record: Int,
    val total: Int,
    val passages: List<GuidancePassage> = emptyList(),
    val usingFallback: Boolean = false,
)
data class WashResult(val reflectionId: String, val state: InnerState, val guidance: JnanaGuidance)

/** One deliberate, GPU-only pass for newly recorded reflections. */
object JnanaWash {
    suspend fun run(
        context: Context,
        reflections: List<Reflection>,
        onProgress: (WashProgress) -> Unit,
    ): List<WashResult> = withContext(Dispatchers.IO) {
        require(reflections.isNotEmpty()) { "There are no new reflections to wash." }
        report(onProgress, WashProgress(WashStage.STARTING_GPU, 0, reflections.size))
        GemmaWashEngine(context).use { gemma ->
            gemma.initialize()
            report(onProgress, WashProgress(WashStage.GPU_READY, 0, reflections.size))
            val results = reflections.mapIndexed { index, reflection ->
                val number = index + 1
                report(onProgress, WashProgress(WashStage.TAGGING, number, reflections.size))
                val state = gemma.tag(reflection.text)
                report(onProgress, WashProgress(WashStage.SEARCHING, number, reflections.size))
                val matches = ScriptureLibrary.search(context, reflection.text, limit = 3, minScore = 0.30f)
                val passages = matches.map { match ->
                    GuidancePassage(match.book, match.page, excerpt(match.text), match.score)
                }
                val fallback = passages.isEmpty()
                report(onProgress, WashProgress(WashStage.ANSWERING, number, reflections.size, passages, fallback))
                val answer = if (fallback) gemma.answerWithoutPassages(reflection.text, state) else gemma.answer(reflection.text, state, passages)
                WashResult(reflection.id, state, JnanaGuidance(answer, passages, sourceGrounded = !fallback))
            }
            report(onProgress, WashProgress(WashStage.COMPLETE, reflections.size, reflections.size))
            results
        }
    }

    private suspend fun report(listener: (WashProgress) -> Unit, progress: WashProgress) {
        withContext(Dispatchers.Main.immediate) { listener(progress) }
    }

    private fun excerpt(text: String): String {
        val maximum = 850
        if (text.length <= maximum) return text
        val end = text.lastIndexOf(' ', maximum).takeIf { it > 500 } ?: maximum
        return text.substring(0, end).trim() + "…"
    }
}

private class GemmaWashEngine(context: Context) : Closeable {
    private val engine: Engine

    init {
        val model = File(File(context.filesDir, "models"), PublicModels.gemma.filename)
        check(model.length() == PublicModels.gemma.expectedBytes) { "Gemma 4 E4B is not downloaded yet." }
        val cache = File(context.filesDir, "models/cache").apply { check(mkdirs() || isDirectory) }
        // GPU is intentional: a failed GPU initialization is shown to the practitioner,
        // never silently replaced by CPU or a rule-based classifier.
        engine = Engine(EngineConfig(model.absolutePath, Backend.GPU(), Backend.GPU(), null, 2048, 8, cache.absolutePath))
    }

    fun initialize() = engine.initialize()

    fun tag(reflection: String): InnerState {
        val output = talk(
            system = "Classify this private reflection with exactly one label: KAMA, KRODHA, MADA, MATSARYA, LOBHA, or MOHA. Return only that label.",
            prompt = reflection,
            temperature = 0.0,
        ).trim().uppercase()
        return InnerState.entries.firstOrNull { output.contains(it.name) }
            ?: error("Gemma did not return a clear state label. Please try again.")
    }

    fun answer(reflection: String, state: InnerState, passages: List<GuidancePassage>): String {
        val sources = passages.mapIndexed { index, passage ->
            "Passage ${index + 1} (${passage.book}, p.${passage.page}, cosine ${"%.2f".format(passage.score)}): ${passage.text}"
        }.joinToString("\n\n")
        val answer = talk(
            system = "Give private spiritual guidance in the simple, direct, inward-looking tradition of Sri Ramana Maharshi. Do not claim to be Bhagavan or invent quotations. Ground the guidance only in the supplied passages. Address the recorded emotion kindly and point attention toward the one aware of it. Return one natural sentence of at most 25 words, with no heading, source list, or explanation.",
            prompt = "Recorded reflection: $reflection\nRecognized state: ${state.display}\n\nClosest source passages:\n$sources",
            temperature = 0.15,
        )
        return capWords(answer)
    }

    fun answerWithoutPassages(reflection: String, state: InnerState): String {
        val answer = talk(
            system = "Give simple, compassionate spiritual counsel in the direct, inward-looking tradition of Sri Ramana Maharshi. Do not claim to be Bhagavan, mention sources, or say that search failed. Address the recorded emotion and gently turn attention toward the one aware of it. Return one natural sentence of at most 25 words, with no heading or explanation.",
            prompt = "Recorded reflection: $reflection\nRecognized state: ${state.display}",
            temperature = 0.15,
        )
        return capWords(answer)
    }

    private fun talk(system: String, prompt: String, temperature: Double): String {
        val conversation = engine.createConversation(ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 8, topP = 0.8, temperature = temperature, seed = 41),
        ))
        return try {
            conversation.sendMessage(Contents.of(prompt)).contents.contents
                .filterIsInstance<Content.Text>().joinToString(" ") { it.text }
        } finally {
            conversation.close()
        }
    }

    private fun capWords(text: String): String {
        val words = text.replace(Regex("\\s+"), " ").trim().split(' ').filter { it.isNotBlank() }
        check(words.isNotEmpty()) { "Gemma returned no guidance. Please try again." }
        return words.take(25).joinToString(" ")
    }

    override fun close() = engine.close()
}
