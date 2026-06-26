package com.exogroup.qnag.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// ── Enums ─────────────────────────────────────────────────────────────────────

enum class CommandJobType {
    ACK, REMOVE_ACK, RECHECK, DOWNTIME,
}

enum class CommandJobStatus {
    RUNNING, SUCCEEDED, PARTIAL_FAILED, FAILED,
}

enum class CommandTargetStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED,
}

// ── Models ────────────────────────────────────────────────────────────────────

data class CommandTargetResult(
    val id: String,
    val instanceName: String,
    val instanceId: String,
    val hostName: String,
    val serviceName: String?,
    val status: CommandTargetStatus,
    val message: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

data class CommandJob(
    val id: String,
    val type: CommandJobType,
    val title: String,
    val status: CommandJobStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val targets: List<CommandTargetResult>,
)

// ── Tracker ───────────────────────────────────────────────────────────────────

/**
 * In-memory singleton tracking recently submitted Nagios commands and their
 * per-target outcomes.  Holds at most [MAX_JOBS] entries; oldest are dropped.
 * All mutating operations are thread-safe via [StateFlow.update]'s CAS loop.
 */
object CommandActivityTracker {

    private const val MAX_JOBS = 50

    private val _jobs = MutableStateFlow<List<CommandJob>>(emptyList())
    val jobs: StateFlow<List<CommandJob>> = _jobs.asStateFlow()

    fun startJob(
        id: String,
        type: CommandJobType,
        title: String,
        targets: List<CommandTargetResult>,
    ) {
        val job = CommandJob(
            id = id,
            type = type,
            title = title,
            status = CommandJobStatus.RUNNING,
            startedAt = System.currentTimeMillis(),
            targets = targets,
        )
        _jobs.update { current -> (listOf(job) + current).take(MAX_JOBS) }
    }

    fun markTargetRunning(jobId: String, targetId: String) =
        patchTarget(jobId, targetId) {
            it.copy(status = CommandTargetStatus.RUNNING, startedAt = System.currentTimeMillis())
        }

    fun markTargetSucceeded(jobId: String, targetId: String) =
        patchTarget(jobId, targetId) {
            it.copy(status = CommandTargetStatus.SUCCEEDED, finishedAt = System.currentTimeMillis())
        }

    fun markTargetFailed(jobId: String, targetId: String, message: String?) =
        patchTarget(jobId, targetId) {
            it.copy(
                status = CommandTargetStatus.FAILED,
                message = message,
                finishedAt = System.currentTimeMillis(),
            )
        }

    fun markTargetSkipped(jobId: String, targetId: String) =
        patchTarget(jobId, targetId) {
            it.copy(status = CommandTargetStatus.SKIPPED, finishedAt = System.currentTimeMillis())
        }

    /**
     * Finalise a job.  Any targets still in PENDING or RUNNING state are marked
     * SKIPPED (they were not reached due to an early cancellation).
     * Final status: SUCCEEDED / PARTIAL_FAILED / FAILED.
     */
    fun finishJob(jobId: String) {
        _jobs.update { jobs ->
            jobs.map { job ->
                if (job.id != jobId) job
                else {
                    val settled = job.targets.map { t ->
                        if (t.status == CommandTargetStatus.PENDING || t.status == CommandTargetStatus.RUNNING)
                            t.copy(status = CommandTargetStatus.SKIPPED, finishedAt = System.currentTimeMillis())
                        else t
                    }
                    val succeeded = settled.count { it.status == CommandTargetStatus.SUCCEEDED }
                    val failed    = settled.count { it.status == CommandTargetStatus.FAILED }
                    val status = when {
                        failed == 0 && succeeded > 0 -> CommandJobStatus.SUCCEEDED
                        succeeded == 0               -> CommandJobStatus.FAILED
                        else                         -> CommandJobStatus.PARTIAL_FAILED
                    }
                    job.copy(targets = settled, status = status, finishedAt = System.currentTimeMillis())
                }
            }
        }
    }

    fun clearCompleted() {
        _jobs.update { it.filter { job -> job.status == CommandJobStatus.RUNNING } }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun patchTarget(
        jobId: String,
        targetId: String,
        transform: (CommandTargetResult) -> CommandTargetResult,
    ) {
        _jobs.update { jobs ->
            jobs.map { job ->
                if (job.id != jobId) job
                else job.copy(
                    targets = job.targets.map { t -> if (t.id != targetId) t else transform(t) },
                )
            }
        }
    }
}
