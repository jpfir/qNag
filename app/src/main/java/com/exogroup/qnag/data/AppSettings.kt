package com.exogroup.qnag.data

enum class AlertListStyle {
    MODERN_CARDS,
    DETAILED_CARDS,
    CLASSIC_ROWS,
    CLASSIC_EXPANDED_ROWS;

    val displayName: String get() = when (this) {
        MODERN_CARDS          -> "Modern cards"
        DETAILED_CARDS        -> "Detailed cards"
        CLASSIC_ROWS          -> "Classic rows"
        CLASSIC_EXPANDED_ROWS -> "Classic expanded"
    }
}

enum class AlertGroupingMode {
    GROUPED_BY_TYPE,
    UNGROUPED_SEVERITY;

    val displayName: String get() = when (this) {
        GROUPED_BY_TYPE    -> "Grouped by alarm type"
        UNGROUPED_SEVERITY -> "Ungrouped, severity order"
    }
}

data class AppSettings(
    val filterSettings: FilterSettings = FilterSettings(),
    val notificationSettings: NotificationSettings = NotificationSettings(),
    val commandSettings: CommandSettings = CommandSettings(),
    // Persisted dashboard scope selection.
    // "ALL"              → show all enabled instances merged
    // "INSTANCE:<uuid>"  → show a single instance
    // Defaults to "ALL" so existing users with multiple instances get the merged view.
    val selectedDashboardScope: String = "ALL",
    val summaryExpanded: Boolean = true,
    val alertListStyle: AlertListStyle = AlertListStyle.CLASSIC_ROWS,
    val alertGroupingMode: AlertGroupingMode = AlertGroupingMode.GROUPED_BY_TYPE,
)
