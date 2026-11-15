package com.nikita.taskplanner

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nikita.taskplanner.work.ReminderWorker
import java.util.concurrent.TimeUnit

class TaskPlannerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Тема: тёмная по умолчанию, выбор сохраняется
        val dark = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("dark", true)
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        val wm = WorkManager.getInstance(this)

        // Ежедневная проверка сроков (работает и при закрытом приложении)
        wm.enqueueUniquePeriodicWork(
            "daily-reminders",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build()
        )

        // Быстрая проверка сразу после запуска приложения,
        // чтобы уведомление можно было увидеть не дожидаясь суток
        wm.enqueueUniqueWork(
            "startup-reminders",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build()
        )
    }
}
