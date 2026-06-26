package com.exogroup.qnag.data

enum class WearableNotifDetail {
    COMPACT_SUMMARY,           // text is mostly counts
    TOP_PROBLEM_PLUS_SUMMARY,  // top failing host/service first, then summary counts
    TOP_PROBLEMS_LIST,         // expanded text includes top 3-5 problems
}
