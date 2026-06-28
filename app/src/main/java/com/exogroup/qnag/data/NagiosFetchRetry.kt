package com.exogroup.qnag.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Retry helper for Nagios instance fetches.
 *
 * Retries up to [maxAttempts] times total (not N retries after the first attempt).
 * Uses exponential backoff: 1s before attempt 2, 2s before attempt 3, etc.
 *
 * CancellationException is rethrown immediately so coroutine cancellation is never swallowed.
 * All other exceptions are retried until exhausted, then the last exception is rethrown.
 *
 * The [onRetry] callback fires after each failed non-final attempt and is safe to use for
 * EventLog or logcat output. It receives (attempt, maxAttempts, error) so callers can produce
 * messages like "Poll retry — Production: attempt 2/3 after connection error".
 */
object NagiosFetchRetry {

    suspend fun fetchProblems(
        api: NagiosApi,
        instance: NagiosInstance,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 1_000L,
        backoffMultiplier: Double = 2.0,
        onRetry: ((attempt: Int, maxAttempts: Int, error: Throwable) -> Unit)? = null,
    ): List<NagiosProblem> {
        var lastError: Throwable? = null
        var delayMs = initialDelayMs

        repeat(maxAttempts) { index ->
            val attempt = index + 1
            try {
                return api.fetchProblems(instance)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt >= maxAttempts) throw e

                onRetry?.invoke(attempt, maxAttempts, e)
                delay(delayMs)
                delayMs = (delayMs * backoffMultiplier).toLong()
            }
        }

        throw lastError ?: IllegalStateException("Unknown fetch failure")
    }
}
