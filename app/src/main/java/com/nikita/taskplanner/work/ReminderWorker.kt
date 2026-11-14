package com.nikita.taskplanner.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.nikita.taskplanner.R
import com.nikita.taskplanner.db.DbHelper
import com.nikita.taskplanner.ui.MainActivity
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Раз в сутки проверяет задачи: если до срока выполнения осталось
 * 7 дней или меньше и задача не выполнена — показывает уведомление.
 * Не повторяет уведомление чаще одного раза в сутки.
 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

        val today = startOfDay(System.currentTimeMillis())
        val lastNotifiedDay = prefs.getLong("last_notified_day", -1L)
        if (lastNotifiedDay == today) return Result.success() // уже уведомляли сегодня

        val deadline = today + TimeUnit.DAYS.toMillis(7)
        val soon = DbHelper(context).tasks(null)
            .filter { !it.done && it.dueDate in today..deadline }
            .sortedBy { it.dueDate }

        if (soon.isNotEmpty()) {
            if (canNotify(context)) {
                val n = if (soon.size == 1) {
                    val t = soon[0]
                    val days = daysLeft(today, t.dueDate)
                    makeBuilder(context)
                        .setContentTitle(context.getString(R.string.notif_title))
                        .setContentText("«${t.title}» — ${daysText(days)}")
                        .setStyle(NotificationCompat.BigTextStyle()
                            .bigText("«${t.title}» — ${daysText(days)}\n${t.description}"))
                        .build()
                } else {
                    val lines = soon.take(5).joinToString("\n") {
                        "• ${it.title} — ${daysText(daysLeft(today, it.dueDate))}"
                    }
                    val more = if (soon.size > 5) "\n…и ещё ${soon.size - 5}" else ""
                    makeBuilder(context)
                        .setContentTitle("Скоро срок у ${soon.size} ${pluralTasks(soon.size)}")
                        .setContentText(soon[0].title)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(lines + more))
                        .build()
                }
                notifyCompat(context, 1001, n)
            }
            prefs.edit().putLong("last_notified_day", today).apply()
        }
        return Result.success()
    }

    private fun makeBuilder(ctx: Context): NotificationCompat.Builder {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)

        val intent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(intent)
    }

    private fun canNotify(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun notifyCompat(ctx: Context, id: Int, n: android.app.Notification) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, n)
    }

    companion object {
        const val CHANNEL_ID = "reminders"

        fun startOfDay(ms: Long): Long = Calendar.getInstance().run {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            timeInMillis
        }

        fun daysLeft(today: Long, due: Long): Long =
            TimeUnit.MILLISECONDS.toDays(due - today)

        fun daysText(days: Long): String = when {
            days <= 0L -> "срок уже наступил"
            days == 1L -> "остался 1 день"
            days < 5L -> "осталось $days дня"
            else -> "осталось $days дней"
        }

        fun pluralTasks(n: Int): String = when {
            n % 10 == 1 && n % 100 != 11 -> "задачи"
            n % 10 in 2..4 && (n % 100 < 12 || n % 100 > 14) -> "задачи"
            else -> "задач"
        }
    }
}
