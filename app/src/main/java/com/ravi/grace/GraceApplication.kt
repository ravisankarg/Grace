package com.ravi.grace

import android.app.Application
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class GraceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MorningReminder.schedule(this)
        WorkManager.getInstance(this).enqueueUniqueWork(
            "grace-library-discovery",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LibraryDiscoveryWorker>().build()
        )
    }
}
