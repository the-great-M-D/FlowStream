package com.downloadx.app

import android.app.Application
import java.io.File

/**
 * Captures any uncaught exception to filesDir/last_crash.txt so the next launch
 * can display it — essential for diagnosing crashes on a real device without adb.
 */
class CrashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                File(filesDir, "last_crash.txt").writeText(
                    "thread=${thread.name}\n\n${throwable.stackTraceToString()}"
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
