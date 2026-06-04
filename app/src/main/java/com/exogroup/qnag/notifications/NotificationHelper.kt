package com.exogroup.qnag.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.exogroup.qnag.MainActivity
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.fetchFailureNotificationId
import com.exogroup.qnag.data.notificationId
import com.exogroup.qnag.data.notificationStatusLabel

object NotificationHelper {

    const val CHANNEL_ALERTS = "qnag_alerts"
    const val CHANNEL_FETCH_FAIL = "qnag_fetch_fail"
    const val CHANNEL_MONITORING = "qnag_monitoring"

    // Notification ID reserved for the foreground service persistent notification
    const val MONITORING_SERVICE_NOTIF_ID = 9001

    /** Create notification channels (safe to call multiple times; Android deduplicates). */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "qNag Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Nagios problem notifications" }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_FETCH_FAIL,
                    "qNag Fetch Failures",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Notifications when a Nagios polling call fails" }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MONITORING,
                    "qNag Monitoring Service",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Persistent notification shown while foreground monitoring is active"
                    setShowBadge(false)
                }
            )
        }
    }

    /** Returns true if the POST_NOTIFICATIONS permission is currently granted. */
    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyProblem(context: Context, instanceId: String, instanceName: String, problem: NagiosProblem) {
        if (!hasPermission(context)) return

        val statusLabel = notificationStatusLabel(problem)
        val title = when (problem) {
            is NagiosProblem.ServiceProblem ->
                "$statusLabel: ${problem.hostName} / ${problem.serviceName}"
            is NagiosProblem.HostProblem ->
                "$statusLabel: ${problem.hostName}"
        }
        val text = problem.pluginOutput.take(256)

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(mainActivityIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context)
            .notify(notificationId(instanceId, problem), notification)
    }

    @SuppressLint("MissingPermission")
    fun notifyFetchFailure(context: Context, instanceId: String, instanceName: String, safeError: String) {
        if (!hasPermission(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_FETCH_FAIL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("qNag: failed to refresh $instanceName")
            .setContentText(safeError.take(256))
            .setContentIntent(mainActivityIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        NotificationManagerCompat.from(context)
            .notify(fetchFailureNotificationId(instanceId), notification)
    }

    fun cancelFetchFailure(context: Context, instanceId: String) {
        NotificationManagerCompat.from(context)
            .cancel(fetchFailureNotificationId(instanceId))
    }

    private fun mainActivityIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
