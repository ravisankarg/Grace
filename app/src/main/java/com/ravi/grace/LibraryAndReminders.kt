package com.ravi.grace

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar

/** Records bundled source availability; LibraryIndexWorker performs the actual local semantic index. */
class LibraryDiscoveryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val books = applicationContext.assets.list("books")?.filter { it.endsWith(".pdf", true) }.orEmpty()
        if (books.isEmpty()) LibraryIndexStatus.update(applicationContext, "No source books found")
        else if (!ModelStatus.embeddingInstalled(applicationContext)) LibraryIndexStatus.update(applicationContext, "${books.size} source books ready · waiting for EmbeddingGemma")
        Result.success()
    }
}

object MorningReminder {
    private const val morningRequestCode = 701
    private const val eveningRequestCode = 702
    private const val morningAction = "com.ravi.grace.reminder.MORNING"
    private const val eveningAction = "com.ravi.grace.reminder.EVENING"

    fun schedule(context: Context) {
        // Upgrade cleanup: the previous build used request 701 with no action.
        // Its identity differs from the new morning alarm, so remove it once.
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        PendingIntent.getBroadcast(context, morningRequestCode, Intent(context, MorningCheckReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { legacy ->
            alarm.cancel(legacy)
            legacy.cancel()
        }
        scheduleAt(context, 6, morningRequestCode, morningAction)
        scheduleAt(context, 20, eveningRequestCode, eveningAction)
    }

    private fun scheduleAt(context: Context, hour: Int, requestCode: Int, action: String) {
        val intent = Intent(context, MorningCheckReceiver::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, flags)
        val calendar = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1) }
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pending)
    }
}

class MorningCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channel = "grace-daily-return"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(channel, "Daily returns", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val evening = intent.action == "com.ravi.grace.reminder.EVENING"
        manager.notify(if (evening) 702 else 701, NotificationCompat.Builder(context, channel)
            .setSmallIcon(com.ravi.grace.R.drawable.ic_grace_launcher)
            .setContentTitle("Get back to the source")
            .setContentText(if (evening) "Before rest, return quietly to what is aware." else "Begin quietly: return to what is aware.")
            .setContentIntent(open).setAutoCancel(true).build())
        MorningReminder.schedule(context)
    }
}

class BootReceiver : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) = MorningReminder.schedule(context) }
