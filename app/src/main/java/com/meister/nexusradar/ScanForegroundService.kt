package com.meister.nexusradar

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.meister.nexusradar.data.AppDatabase
import com.meister.nexusradar.domain.ListingScanReason
import com.meister.nexusradar.domain.Repository
import com.meister.nexusradar.domain.VisibleLink
import com.meister.nexusradar.scan.NexusModScanner
import com.meister.nexusradar.scan.PersistedScanState
import com.meister.nexusradar.scan.ScanStateStore
import com.meister.nexusradar.settings.ScanSettings
import com.meister.nexusradar.settings.ScanSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Foreground service that owns its WebView and therefore keeps the scan alive
 * when MainActivity is covered by another app or removed from the recent-apps UI.
 */
class ScanForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var repository: Repository
    private lateinit var scanStore: ScanStateStore
    private lateinit var settingsStore: ScanSettingsStore
    private lateinit var notificationManager: NotificationManager
    private var scanJob: Job? = null
    private var scannerWebView: WebView? = null
    private var shuttingDownNormally = false

    override fun onCreate() {
        super.onCreate()
        repository = Repository(AppDatabase.get(this).modDao())
        scanStore = ScanStateStore(this)
        settingsStore = ScanSettingsStore(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannels()
        isActive = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_PAUSE -> requestPause()
            ACTION_START -> startQueue()
        }
        return START_STICKY
    }

    private fun startQueue() {
        if (scanJob?.isActive == true) return

        val initial = scanStore.load()
        startInForeground(initial, "Hintergrundscan wird vorbereitet …")
        if (initial.queue.isEmpty()) {
            finishWithoutQueue()
            return
        }

        val settings = settingsStore.load()
        val started = initial.copy(
            running = true,
            collecting = false,
            delayMs = settings.delayMs,
            startedWith = if (initial.startedWith > 0) {
                initial.startedWith
            } else {
                initial.queue.size + initial.processedIds.size
            },
            startedAt = initial.startedAt ?: Instant.now().toString(),
            statusMessage = "Hintergrundscan gestartet • ${initial.queue.size} Mods offen"
        )
        scanStore.save(started)
        updateProgressNotification(started)

        scanJob = serviceScope.launch {
            try {
                runQueue(settings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                finishInterrupted(error)
            }
        }
    }

    private suspend fun runQueue(settings: ScanSettings) {
        val scanner = NexusModScanner(repository, json)
        val web = ensureWebView()

        while (currentCoroutineContext().isActive) {
            val current = scanStore.load()
            if (!current.running || current.queue.isEmpty()) break
            val item = current.queue.first()

            val stillNeeded = repository.planListingScan(
                listOf(
                    VisibleLink(
                        mod_id = item.modId,
                        url = item.url,
                        name = item.name,
                        updated_at = item.listedUpdatedAt,
                        version = item.listedVersion
                    )
                )
            ).candidates.isNotEmpty()

            if (!stillNeeded) {
                val latest = scanStore.load()
                val skipped = latest.copy(
                    queue = latest.queue.removeHead(item.modId),
                    processedIds = latest.processedIds + item.modId,
                    skippedUnchangedCount = latest.skippedUnchangedCount + 1,
                    statusMessage = "Übersprungen: #${item.modId} ist bereits aktuell"
                )
                scanStore.save(skipped)
                updateProgressNotification(skipped)
                delay(150)
                continue
            }

            val reasonLabel = if (item.reason == ListingScanReason.UPDATED.name) "Update" else "Neu"
            publishStatus(
                "$reasonLabel • Lade #${item.modId}: ${item.name.ifBlank { "Mod" }}",
                lastUrl = item.url
            )
            web.loadUrl(item.url)
            delay(settings.delayMs.coerceAtLeast(1500L))

            val succeeded = scanner.scan(
                web = web,
                settings = settings,
                setStatus = ::publishStatus,
                expectedId = item.modId
            )

            val latest = scanStore.load()
            val processed = latest.copy(
                queue = latest.queue.removeHead(item.modId),
                processedIds = latest.processedIds + item.modId,
                failedIds = if (succeeded) latest.failedIds else latest.failedIds + item.modId,
                statusMessage = if (succeeded) {
                    "Gespeichert: ${item.name.ifBlank { "Mod #${item.modId}" }}"
                } else {
                    "Fehler bei ${item.name.ifBlank { "Mod #${item.modId}" }}"
                }
            )
            scanStore.save(processed)
            updateProgressNotification(processed)
            delay(350)
        }

        val latest = scanStore.load()
        if (latest.queue.isEmpty()) {
            finishCompleted(latest)
        } else {
            finishPaused(latest)
        }
    }

    private fun List<com.meister.nexusradar.scan.QueueItem>.removeHead(modId: Long) =
        if (firstOrNull()?.modId == modId) drop(1) else filterNot { it.modId == modId }

    private fun requestPause() {
        val current = scanStore.load()
        if (!current.running) return
        val pausing = current.copy(
            running = false,
            statusMessage = "Scan wird nach dem aktuellen Mod pausiert …"
        )
        scanStore.save(pausing)
        updateProgressNotification(pausing)
    }

    private fun publishStatus(message: String) {
        publishStatus(message, lastUrl = null)
    }

    private fun publishStatus(message: String, lastUrl: String?) {
        val current = scanStore.load()
        val updated = current.copy(
            lastUrl = lastUrl ?: current.lastUrl,
            statusMessage = message
        )
        scanStore.save(updated)
        updateProgressNotification(updated)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView = scannerWebView ?: WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webChromeClient = WebChromeClient()
        webViewClient = WebViewClient()
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
    }.also { scannerWebView = it }

    private fun startInForeground(state: PersistedScanState, message: String) {
        val notification = progressNotification(state.copy(statusMessage = message))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ONGOING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, notification)
        }
    }

    private fun updateProgressNotification(state: PersistedScanState) {
        notificationManager.notify(ONGOING_NOTIFICATION_ID, progressNotification(state))
    }

    private fun progressNotification(state: PersistedScanState): Notification {
        val total = state.totalForRun.coerceAtLeast(state.queue.size + state.processedCount)
            .coerceAtLeast(1)
        val completed = state.processedCount.coerceIn(0, total)
        val text = state.statusMessage.ifBlank {
            "${state.queue.size} Mods offen • ${state.failedIds.size} Fehler"
        }
        return Notification.Builder(this, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nexus Radar scannt im Hintergrund")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(total, completed, false)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_notification,
                    "Pausieren",
                    pausePendingIntent()
                ).build()
            )
            .build()
    }

    private fun finishCompleted(state: PersistedScanState) {
        val finalState = state.copy(
            running = false,
            collecting = false,
            statusMessage = "Scan abgeschlossen • ${state.processedCount} geprüft • ${state.failedIds.size} Fehler"
        )
        scanStore.save(finalState)
        shuttingDownNormally = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.notify(
            COMPLETE_NOTIFICATION_ID,
            completionNotification(
                title = "Scan abgeschlossen",
                text = "${finalState.processedCount} Mods geprüft • ${finalState.failedIds.size} Fehler"
            )
        )
        stopSelf()
    }

    private fun finishPaused(state: PersistedScanState) {
        val finalState = state.copy(
            running = false,
            collecting = false,
            statusMessage = "Scan pausiert • ${state.queue.size} Mods bleiben in der Queue"
        )
        scanStore.save(finalState)
        shuttingDownNormally = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishWithoutQueue() {
        val current = scanStore.load().copy(
            running = false,
            collecting = false,
            statusMessage = "Keine Mods in der Queue"
        )
        scanStore.save(current)
        shuttingDownNormally = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishInterrupted(error: Exception) {
        val detail = error.message?.take(120) ?: "unbekannter Fehler"
        val current = scanStore.load().copy(
            running = false,
            collecting = false,
            statusMessage = "Hintergrundscan unterbrochen • $detail"
        )
        scanStore.save(current)
        shuttingDownNormally = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.notify(
            COMPLETE_NOTIFICATION_ID,
            completionNotification(
                title = "Scan unterbrochen",
                text = "${current.queue.size} Mods bleiben gespeichert. $detail"
            )
        )
        stopSelf()
    }

    private fun completionNotification(title: String, text: String): Notification =
        Notification.Builder(this, COMPLETE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun pausePendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, ScanForegroundService::class.java).setAction(ACTION_PAUSE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createNotificationChannels() {
        val progress = NotificationChannel(
            PROGRESS_CHANNEL_ID,
            "Scanner-Fortschritt",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Zeigt einen laufenden Hintergrundscan an"
            setShowBadge(false)
        }
        val complete = NotificationChannel(
            COMPLETE_CHANNEL_ID,
            "Scan abgeschlossen",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Meldet sichtbar, wenn ein Hintergrundscan fertig ist"
            enableVibration(true)
            enableLights(true)
            lightColor = Color.rgb(255, 138, 61)
        }
        notificationManager.createNotificationChannels(listOf(progress, complete))
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val current = scanStore.load().copy(
            running = false,
            collecting = false,
            statusMessage = "Android-Hintergrundlimit erreicht • Scan kann fortgesetzt werden"
        )
        scanStore.save(current)
        scanJob?.cancel()
        shuttingDownNormally = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.notify(
            COMPLETE_NOTIFICATION_ID,
            completionNotification(
                title = "Scan pausiert",
                text = "Androids Hintergrundlimit wurde erreicht. ${current.queue.size} Mods bleiben gespeichert."
            )
        )
        stopSelf(startId)
    }

    override fun onDestroy() {
        if (!shuttingDownNormally) {
            val current = scanStore.load()
            if (current.running) {
                scanStore.save(
                    current.copy(
                        running = false,
                        collecting = false,
                        statusMessage = "Hintergrundscan unterbrochen • Queue kann fortgesetzt werden"
                    )
                )
            }
        }
        scanJob?.cancel()
        scannerWebView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        scannerWebView = null
        serviceScope.cancel()
        isActive = false
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.meister.nexusradar.action.START_SCAN"
        private const val ACTION_PAUSE = "com.meister.nexusradar.action.PAUSE_SCAN"
        private const val PROGRESS_CHANNEL_ID = "scanner_progress_v1"
        private const val COMPLETE_CHANNEL_ID = "scanner_complete_v1"
        private const val ONGOING_NOTIFICATION_ID = 1101
        private const val COMPLETE_NOTIFICATION_ID = 1102

        @Volatile
        var isActive: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, ScanForegroundService::class.java).setAction(ACTION_START)
            )
        }

        fun pause(context: Context) {
            context.startService(
                Intent(context, ScanForegroundService::class.java).setAction(ACTION_PAUSE)
            )
        }
    }
}
