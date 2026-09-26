package com.ravi.grace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class Reflection(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val enteredAt: String = LocalDateTime.now().toString(),
)

data class DayPractice(
    val date: String,
    val wokeBeforeSunrise: Boolean = false,
    val morningMeditationMinutes: Int = 0,
    val eveningMeditationMinutes: Int = 0,
    val noSuccumbing: Boolean = false,
    val lastDayPriority: Boolean = false,
    val reflections: List<Reflection> = emptyList()
) {
    val meditationMinutes: Int get() = morningMeditationMinutes + eveningMeditationMinutes
    val goalCount: Int get() = listOf(wokeBeforeSunrise, meditationMinutes > 0, noSuccumbing, lastDayPriority).count { it }
    val victorious: Boolean get() = goalCount == 4
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
        put("lastDayPriority", day.lastDayPriority)
        put("reflections", JSONArray().apply { day.reflections.forEach { r ->
            put(JSONObject().apply {
                put("id", r.id); put("text", r.text); put("at", r.enteredAt)
            })
        } })
    }.toString()

    private fun decode(raw: String): DayPractice {
        val json = JSONObject(raw); val rows = json.optJSONArray("reflections") ?: JSONArray()
        val reflections = (0 until rows.length()).map { i -> rows.getJSONObject(i).let { r ->
            Reflection(
                r.getString("id"),
                r.getString("text"),
                r.optString("at", LocalDateTime.now().toString()),
            )
        } }
        return DayPractice(
            date = json.getString("date"),
            wokeBeforeSunrise = json.optBoolean("sunrise"),
            morningMeditationMinutes = json.optInt("morning"),
            eveningMeditationMinutes = json.optInt("evening"),
            noSuccumbing = json.optBoolean("steady"),
            lastDayPriority = json.optBoolean("lastDayPriority"),
            reflections = reflections,
        )
    }
}
