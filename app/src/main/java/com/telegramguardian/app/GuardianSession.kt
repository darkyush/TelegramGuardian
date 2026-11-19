package com.telegramguardian.app

data class GuardianSession(
    val id: Long,
    val isCurrent: Boolean,
    val applicationName: String,
    val applicationVersion: String,
    val deviceModel: String,
    val platform: String,
    val systemVersion: String,
    val ipAddress: String,
    val location: String,
    val lastActiveDate: Int,
    val logInDate: Int
)
