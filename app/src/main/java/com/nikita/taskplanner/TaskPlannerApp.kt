package com.nikita.taskplanner

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nikita.taskplanner.work.ReminderWorker
import java.util.concurrent.TimeUnit

class TaskPlannerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val wm = WorkManager.getInstance(this)

        // Ежедневная проверка сроков (работает и при закрытом приложении)
        wm.enqueueUniquePeriodicWork(
            "daily-reminders",
            ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build()
        )

        // Быстрая проверка сразу после запуска приложения,
        // чтобы уведомление можно было увидеть не дожидаясь суток
        wm.enqueueUniqueWork(
            "startup-reminders",
            androidx.work.ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build()
        )
    }
}
