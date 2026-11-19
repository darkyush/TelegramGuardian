package com.telegramguardian.app

import android.content.Context

object GuardianPreferences {
    private const val PREFS = "guardian_settings"
    private const val AUTO_CLEANUP = "auto_cleanup_enabled"

    fun isAutoCleanupEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(AUTO_CLEANUP, false)

    fun setAutoCleanupEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(AUTO_CLEANUP, enabled).apply()
    }
}
