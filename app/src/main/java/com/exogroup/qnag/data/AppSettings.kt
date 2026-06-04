package com.exogroup.qnag.data

data class AppSettings(
    val filterSettings: FilterSettings = FilterSettings(),
    val notificationSettings: NotificationSettings = NotificationSettings(),
    val commandSettings: CommandSettings = CommandSettings(),
)
