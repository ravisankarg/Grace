package com.ravi.grace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

enum class InnerState(val display: String, val tint: Long) {
    KAMA("Kāma", 0xFFC85C5CL),
    KRODHA("Krodha", 0xFFD5673CL),
    MADA("Mada", 0xFF9A73BE),
    MATSARYA("Mātsarya", 0xFF3E9D93),
    LOBHA("Lobha", 0xFFC89233),
    MOHA("Moha", 0xFF5579B5)
}

data class Reflection(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    /** Assigned only by the Gemma 4 GPU pass in Wash with Jñāna. */
    val state: InnerState? = null,
    val enteredAt: String = LocalDateTime.now().toString(),
    val washed: Boolean = false,
    /** Immutable result of the completed Jnana pass; never regenerated until a new record is added. */
    val guidance: JnanaGuidance? = null,
)

data class GuidancePassage(
    val book: String,
    val page: Int,
    val text: String,
    val score: Float,
)

data class JnanaGuidance(
    val answer: String,
    val passages: List<GuidancePassage>,
    /** False means no passage reached the strict 0.30 cutoff; Gemma answered from the record alone. */
    val sourceGrounded: Boolean = true,
    val completedAt: String = LocalDateTime.now().toString(),
)

data class DayPractice(
    val date: String,
    val wokeBeforeSunrise: Boolean = false,
    val morningMeditationMinutes: Int = 0,
    val eveningMeditationMinutes: Int = 0,
    val noSuccumbing: Boolean = false,
    val reflections: List<Reflection> = emptyList()
) {
    val gnanaPractised: Boolean get() = reflections.any { it.washed }
    val victorious: Boolean get() = noSuccumbing || (reflections.isNotEmpty() && reflections.all { it.washed })
    val meditationMinutes: Int get() = morningMeditationMinutes + eveningMeditationMinutes
    val goalCount: Int get() = listOf(wokeBeforeSunrise, meditationMinutes > 0, gnanaPractised).count { it }
}

class GraceStore(context: Context) {
    private val preferences = context.getSharedPreferences("grace-practice", Context.MODE_PRIVATE)
    private fun key(date: String) = "day-$date"

    fun day(date: LocalDate = LocalDate.now()): DayPractice {
        val raw = preferences.getString(key(date.toString()), null) ?: return DayPractice(date.toString())
        return runCatching { decode(raw) }.getOrElse { DayPractice(date.toString()) }
    }

    fun save(day: DayPractice) {
        preferences.edit().putString(key(day.date), encode(day)).apply()
        preferences.edit().putString("first-day", preferences.getString("first-day", day.date) ?: day.date).apply()
    }

    fun allDays(): List<DayPractice> = preferences.all.keys
        .filter { it.startsWith("day-") }
        .map { day(LocalDate.parse(it.removePrefix("day-"))) }

    private fun encode(day: DayPractice): String = JSONObject().apply {
        put("date", day.date); put("sunrise", day.wokeBeforeSunrise)
        put("morning", day.morningMeditationMinutes); put("evening", day.eveningMeditationMinutes)
        put("steady", day.noSuccumbing)
        put("reflections", JSONArray().apply { day.reflections.forEach { r ->
            put(JSONObject().apply {
                put("id", r.id); put("text", r.text); put("state", r.state?.name ?: JSONObject.NULL); put("at", r.enteredAt); put("washed", r.washed)
                put("guidance", r.guidance?.let { guidance -> JSONObject().apply {
                    put("answer", guidance.answer); put("sourceGrounded", guidance.sourceGrounded); put("completedAt", guidance.completedAt)
                    put("passages", JSONArray().apply { guidance.passages.forEach { passage ->
                        put(JSONObject().apply { put("book", passage.book); put("page", passage.page); put("text", passage.text); put("score", passage.score.toDouble()) })
                    } })
                } } ?: JSONObject.NULL)
            })
        } })
    }.toString()

    private fun decode(raw: String): DayPractice {
        val json = JSONObject(raw); val rows = json.optJSONArray("reflections") ?: JSONArray()
        val reflections = (0 until rows.length()).map { i -> rows.getJSONObject(i).let { r ->
            Reflection(
                r.getString("id"),
                r.getString("text"),
                r.optString("state").takeIf { it.isNotBlank() && it != "null" }?.let(InnerState::valueOf),
                r.getString("at"),
                r.optBoolean("washed"),
                r.optJSONObject("guidance")?.let { guidance -> JnanaGuidance(
                    answer = guidance.optString("answer"),
                    passages = guidance.optJSONArray("passages")?.let { passages -> (0 until passages.length()).mapNotNull { index ->
                        passages.optJSONObject(index)?.let { passage -> GuidancePassage(passage.optString("book"), passage.optInt("page"), passage.optString("text"), passage.optDouble("score").toFloat()) }
                    } } ?: emptyList(),
                    sourceGrounded = guidance.optBoolean("sourceGrounded", true),
                    completedAt = guidance.optString("completedAt", r.getString("at")),
                ) },
            )
        } }
        return DayPractice(json.getString("date"), json.optBoolean("sunrise"), json.optInt("morning"), json.optInt("evening"), json.optBoolean("steady"), reflections)
    }
}
