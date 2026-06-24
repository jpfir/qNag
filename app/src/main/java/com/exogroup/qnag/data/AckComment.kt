package com.exogroup.qnag.data

/** Acknowledgement comment fetched from Nagios commentlist endpoint. */
data class AckComment(
    val author: String,
    val comment: String,
    val entryTime: Long?,
)
