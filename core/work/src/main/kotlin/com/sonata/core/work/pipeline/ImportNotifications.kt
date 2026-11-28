package com.sonata.core.work.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.sonata.core.domain.model.ImportStage
import com.sonata.core.work.R

/** Ongoing, low-priority progress so a long attach survives and stays visible. */
internal object ImportNotifications {
    private const val CHANNEL = "imports"

    fun foregroundInfo(
        context: Context,
        jobId: String,
        title: String,
        stage: ImportStage,
        progress: Float,
    ): ForegroundInfo {
        ensureChannel(context)
        val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = intent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_import)
            .setContentTitle(title.ifBlank { "Importing a sound" })
            .setContentText(stage.label + " · " + percent + "%")
            .setProgress(100, percent, progress <= 0.001f)
            .setOngoing(!stage.terminal)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .build()
        return ForegroundInfo(jobId.hashCode(), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Imports", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress for sounds being added to Sonata"
                setShowBadge(false)
            },
        )
    }
}