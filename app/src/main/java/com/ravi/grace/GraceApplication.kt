package com.ravi.grace

import android.app.Application
import java.io.File

class GraceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        File(filesDir, "models").deleteRecursively()
        getSharedPreferences("grace-model-download", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("grace-library-index", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("grace-practice", MODE_PRIVATE).edit().remove("library-status").apply()
        MorningReminder.schedule(this)
    }
}
