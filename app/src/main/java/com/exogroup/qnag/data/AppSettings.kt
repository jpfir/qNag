package com.exogroup.qnag.data

data class AppSettings(
    val filterSettings: FilterSettings = FilterSettings(),
    val notificationSettings: NotificationSettings = NotificationSettings(),
    val commandSettings: CommandSettings = CommandSettings(),
    // Persisted dashboard scope selection.
    // "ALL"              → show all enabled instances merged
    // "INSTANCE:<uuid>"  → show a single instance
    // Defaults to "ALL" so existing users with multiple instances get the merged view.
    val selectedDashboardScope: String = "ALL",
)
