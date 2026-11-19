package com.telegramguardian.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.withTimeout

class GuardianWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!GuardianPreferences.isAutoCleanupEnabled(applicationContext)) {
            return Result.success()
        }

        return try {
            val manager = TdlibManager.get(applicationContext)
            manager.initialize()
            withTimeout(120_000) {
                manager.awaitAuthorized(120_000)
                val removed = manager.terminateAllOtherSessions()
                Log.i("TelegramGuardian", "Automatic session check completed; removed=$removed")
            }
            Result.success()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w("TelegramGuardian", "TDLib authorization/connection timed out; retry later.")
            Result.retry()
        } catch (e: Exception) {
            Log.e("TelegramGuardian", "Automatic session check failed: ${e.message}")
            Result.retry()
        }
    }
}
