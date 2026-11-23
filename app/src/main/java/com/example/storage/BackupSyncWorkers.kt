package com.example.storage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.R
import com.example.data.DskLockerRepository
import com.example.data.local.DskLockerDatabase
import com.example.data.remote.BackendConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!BackendConfig.isSqlBackendConfigured) {
            return Result.success()
        }
        val repo = DskLockerRepository(applicationContext)
        val isManual = inputData.getBoolean("manual", false)
        val summary = repo.runNeonCloudBackup(isManual = isManual)

        // If transient offline/network error occurred and attempts remain, ask WorkManager to retry cleanly
        return if (summary.transientRetryNeeded && runAttemptCount < 4) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}

class ExpiryReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = DskLockerDatabase.getInstance(applicationContext)
        val settings = db.profileSettingsDao().getSettingsOnce()
        if (settings?.notificationsEnabled == false) return Result.success()

        val reminders = db.documentReminderDao().getAllOnce()
        val today = LocalDate.now()

        reminders.filter { it.notificationsEnabled }.forEach { rem ->
            val expiry = parseDate(rem.expiryDateIso) ?: return@forEach
            val daysUntil = ChronoUnit.DAYS.between(today, expiry).toInt()
            val shouldNotify = (daysUntil == 30 && rem.remind30Days) ||
                (daysUntil == 15 && rem.remind15Days) ||
                (daysUntil == 7 && rem.remind7Days) ||
                (daysUntil == 0 && rem.remindOnDay) ||
                (daysUntil in 1..6 && rem.remind7Days)

            if (shouldNotify) {
                val label = when {
                    daysUntil <= 0 -> "expires TODAY (${rem.expiryDateIso})"
                    else -> "expires in $daysUntil day(s) (${rem.expiryDateIso})"
                }
                DskNotificationHelper.postExpiryAlert(
                    applicationContext,
                    rem.id.hashCode(),
                    "Document Expiry Alert",
                    "${rem.documentName} $label."
                )
            }
        }
        return Result.success()
    }

    private fun parseDate(raw: String): LocalDate? {
        val formats = listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "MM/dd/yyyy")
        for (fmt in formats) {
            val parsed = runCatching { LocalDate.parse(raw.trim(), DateTimeFormatter.ofPattern(fmt)) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
}

object DskNotificationHelper {
    private const val CHANNEL_ID = "dsk_locker_alerts"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DSK LOCKER Security & Expiry Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Document expiry reminders and cloud backup alerts"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun postExpiryAlert(context: Context, notificationId: Int, title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}

object NetworkStatusHelper {
    fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

class ConnectivityBackupMonitor(
    private val context: Context,
    private val onConnectivityChanged: (Boolean) -> Unit,
    private val onReconnectSyncReady: () -> Unit
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        val manager = cm ?: return
        onConnectivityChanged(NetworkStatusHelper.isNetworkConnected(context))
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onConnectivityChanged(true)
                CoroutineScope(Dispatchers.IO).launch {
                    val db = DskLockerDatabase.getInstance(context)
                    val settings = db.profileSettingsDao().getSettingsOnce()
                    val pending = db.fileItemDao().getPendingBackupFiles()
                    if (settings?.autoBackupEnabled == true && pending.isNotEmpty()) {
                        val wifiOk = !settings.wifiOnly || NetworkStatusHelper.isWifiConnected(context)
                        if (wifiOk) {
                            onReconnectSyncReady()
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                onConnectivityChanged(NetworkStatusHelper.isNetworkConnected(context))
            }
        }
        callback = cb
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(req, cb) }
    }

    fun stop() {
        val manager = cm ?: return
        callback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        callback = null
    }
}

object BackupScheduler {
    fun schedulePeriodicAutoBackup(context: Context, enabled: Boolean, wifiOnly: Boolean) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!enabled) {
            wm.cancelUniqueWork("dsk_periodic_backup")
            return
        }
        val netType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(netType)
            .build()
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(
            "dsk_periodic_backup",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueImmediateBackup(context: Context, wifiOnly: Boolean) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val netType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(netType).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(
            "dsk_manual_backup",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    fun scheduleDailyExpiryCheck(context: Context) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val req = PeriodicWorkRequestBuilder<ExpiryReminderWorker>(12, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(
            "dsk_expiry_reminders",
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }
}
