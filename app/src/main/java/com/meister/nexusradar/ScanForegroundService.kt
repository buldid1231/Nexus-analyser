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
import com.meister.nexusradar.browser.NexusPageParser
import com.meister.nexusradar.data.AppDatabase
import com.meister.nexusradar.domain.ListingAdvanceResult
import com.meister.nexusradar.domain.ListingScanReason
import com.meister.nexusradar.domain.Repository
import com.meister.nexusradar.domain.VisibleLink
import com.meister.nexusradar.domain.VisibleLinksResult
import com.meister.nexusradar.scan.NexusModScanner
import com.meister.nexusradar.scan.PersistedScanState
import com.meister.nexusradar.scan.QueueOrdering
import com.meister.nexusradar.scan.QueueItem
import com.meister.nexusradar.scan.ScanAttemptOutcome
import com.meister.nexusradar.scan.ScanRetryPolicy
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.coroutines.resume

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
        when (intent?.action ?: ACTION_RESUME) {
            ACTION_PAUSE -> requestPause()
            ACTION_START_FULL -> startFullScan(intent?.getStringExtra(EXTRA_LISTING_URL))
            ACTION_RESUME -> resumePersistedScan()
        }
        return START_STICKY
    }

    private fun startFullScan(listingUrl: String?) {
        if (scanJob?.isActive == true) return
        val url = listingUrl?.trim().orEmpty()
        val emptyState = PersistedScanState()
        startInForeground(emptyState, "Komplettscan wird vorbereitet …")
        if (!isSkyrimListingUrl(url)) {
            finishInterrupted(IllegalArgumentException("Bitte eine Skyrim-Modliste öffnen"))
            return
        }

        val settings = settingsStore.load()
        val started = PersistedScanState(
            running = true,
            collecting = true,
            collectionPending = true,
            currentListingUrl = url,
            lastUrl = url,
            delayMs = settings.delayMs,
            startedAt = Instant.now().toString(),
            statusMessage = "Phase 1/2 • Nexus-Listenseiten werden gesammelt"
        )
        scanStore.save(started)
        updateProgressNotification(started)
        launchScan(settings)
    }

    private fun resumePersistedScan() {
        if (scanJob?.isActive == true) return

        val initial = scanStore.load()
        startInForeground(initial, "Scan wird fortgesetzt …")
        if (!initial.collectionPending && initial.queue.isEmpty()) {
            finishWithoutQueue()
            return
        }

        val settings = settingsStore.load()
        val resumed = initial.copy(
            queue = QueueOrdering.newestFirst(initial.queue),
            running = true,
            collecting = initial.collectionPending,
            delayMs = settings.delayMs,
            startedAt = initial.startedAt ?: Instant.now().toString(),
            statusMessage = if (initial.collectionPending) {
                "Phase 1/2 • Listensammlung wird fortgesetzt"
            } else {
                "Phase 2/2 • Mod-Queue wird fortgesetzt"
            }
        )
        scanStore.save(resumed)
        updateProgressNotification(resumed)
        launchScan(settings)
    }

    private fun launchScan(settings: ScanSettings) {
        scanJob = serviceScope.launch {
            try {
                if (scanStore.load().collectionPending) {
                    val collected = collectListingPages(settings)
                    if (!collected) {
                        finishPaused(scanStore.load())
                        return@launch
                    }
                }
                runQueue(settings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                finishInterrupted(error)
            }
        }
    }

    private suspend fun collectListingPages(settings: ScanSettings): Boolean {
        val web = ensureWebView()
        var state = scanStore.load()
        val resumeUrl = state.currentListingUrl ?: state.lastUrl
            ?: error("Keine Nexus-Listenadresse gespeichert")
        publishStatus("Phase 1/2 • Lade Nexus-Modliste", resumeUrl)
        web.loadUrl(resumeUrl)
        delay(settings.delayMs.coerceAtLeast(1500L))

        val seenListingIds = state.listingSeenIds.toMutableSet()
        val visitedUrls = mutableSetOf(resumeUrl)
        var stalledRounds = 0
        var reachedLimit = false

        while (currentCoroutineContext().isActive) {
            state = scanStore.load()
            if (!state.running || !state.collectionPending) return false
            if (state.listingBatches >= settings.pageLimit) {
                reachedLimit = true
                break
            }

            val parsed = readVisibleLinksWithRetry(web, seenListingIds)
                ?: error("Nexus-Liste konnte nicht gelesen werden; später fortsetzen")
            parsed.url?.let { visitedUrls += it }
            val freshLinks = parsed.links.filter { seenListingIds.add(it.mod_id) }

            if (freshLinks.isNotEmpty()) {
                val plan = repository.planListingScan(freshLinks)
                val latest = scanStore.load()
                if (!latest.running || !latest.collectionPending) return false
                val alreadyPlanned = (latest.queue.map { it.modId } + latest.processedIds).toSet()
                val added = plan.candidates.filter { it.link.mod_id !in alreadyPlanned }
                val queueItems = added.map { candidate ->
                    QueueItem(
                        modId = candidate.link.mod_id,
                        url = candidate.link.url,
                        name = candidate.link.name,
                        listedUpdatedAt = candidate.link.updated_at,
                        listedVersion = candidate.link.version,
                        reason = candidate.reason.name
                    )
                }
                state = latest.copy(
                    queue = QueueOrdering.newestFirst(latest.queue + queueItems),
                    lastUrl = parsed.url ?: latest.lastUrl,
                    currentListingUrl = parsed.url ?: latest.currentListingUrl,
                    listingSeenIds = seenListingIds.toSet(),
                    discoveredCount = latest.discoveredCount + freshLinks.size,
                    queuedNewCount = latest.queuedNewCount +
                        added.count { it.reason == ListingScanReason.NEW },
                    queuedUpdateCount = latest.queuedUpdateCount +
                        added.count { it.reason == ListingScanReason.UPDATED },
                    skippedUnchangedCount = latest.skippedUnchangedCount + plan.unchangedCount,
                    listingBatches = latest.listingBatches + 1,
                    statusMessage = "Phase 1/2 • Liste ${latest.listingBatches + 1}/${settings.pageLimit}: " +
                        "+${added.size} Queue • ${plan.unchangedCount} aktuell"
                )
                scanStore.save(state)
                updateProgressNotification(state)
                stalledRounds = 0
            } else {
                stalledRounds++
            }

            if (state.listingBatches >= settings.pageLimit) {
                reachedLimit = true
                break
            }
            if (stalledRounds >= 3) break

            val directNext = parsed.next_url?.takeIf { next ->
                next != parsed.url && next !in visitedUrls
            }
            if (directNext != null) {
                visitedUrls += directNext
                saveListingTarget(directNext)
                web.loadUrl(directNext)
                delay(settings.delayMs.coerceAtLeast(1500L))
                continue
            }

            val advance = requestNextListingBatch(web)
            when (advance?.action) {
                "navigate" -> {
                    val target = advance.next_url?.takeIf { it !in visitedUrls }
                    if (target != null) {
                        visitedUrls += target
                        saveListingTarget(target)
                        web.loadUrl(target)
                    } else {
                        stalledRounds++
                    }
                }
                "clicked", "scrolled" -> Unit
                else -> stalledRounds++
            }
            delay(settings.delayMs.coerceAtLeast(1500L))
        }

        val latest = scanStore.load()
        if (!latest.running) return false
        val suffix = if (reachedLimit) " • Listenlimit erreicht" else ""
        val collected = latest.copy(
            collecting = false,
            collectionPending = false,
            startedWith = latest.queue.size + latest.processedIds.size,
            statusMessage = "Phase 1/2 fertig • ${latest.discoveredCount} entdeckt • " +
                "${latest.queue.size} zu prüfen$suffix"
        )
        scanStore.save(collected)
        updateProgressNotification(collected)
        return true
    }

    private fun saveListingTarget(url: String) {
        val current = scanStore.load()
        scanStore.save(current.copy(currentListingUrl = url, lastUrl = url))
    }

    private suspend fun readVisibleLinksWithRetry(
        web: WebView,
        alreadySeen: Set<Long>
    ): VisibleLinksResult? {
        var best: VisibleLinksResult? = null
        repeat(10) {
            val result = runCatching {
                val raw = evaluateJavascript(web, NexusPageParser.collectVisibleModLinks)
                json.decodeFromString<VisibleLinksResult>(decodeJsString(raw))
            }.getOrNull()
            if (result != null) {
                best = result
                if (result.links.any { it.mod_id !in alreadySeen }) return result
            }
            delay(500)
        }
        return best
    }

    private suspend fun requestNextListingBatch(web: WebView): ListingAdvanceResult? =
        runCatching {
            val raw = evaluateJavascript(web, NexusPageParser.advanceListing)
            json.decodeFromString<ListingAdvanceResult>(decodeJsString(raw))
        }.getOrNull()

    private suspend fun evaluateJavascript(web: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            web.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }

    private fun decodeJsString(raw: String): String = runCatching {
        json.decodeFromString<String>(raw)
    }.getOrElse {
        raw.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun isSkyrimListingUrl(url: String): Boolean =
        url.startsWith("https://www.nexusmods.com/", ignoreCase = true) &&
            url.contains("skyrimspecialedition", ignoreCase = true) &&
            !Regex("/skyrimspecialedition/mods/\\d+", RegexOption.IGNORE_CASE)
                .containsMatchIn(url)

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

            val result = scanner.scan(
                web = web,
                settings = settings,
                setStatus = ::publishStatus,
                expectedId = item.modId
            )

            val latest = scanStore.load()
            if (!latest.running) break
            if (result.outcome == ScanAttemptOutcome.FAILED) {
                val failedAttempts = item.retryCount + 1
                if (ScanRetryPolicy.shouldRetry(failedAttempts)) {
                    val retryItem = item.copy(retryCount = failedAttempts)
                    val retrying = latest.copy(
                        queue = latest.queue.replaceHead(retryItem),
                        retryAttemptCount = latest.retryAttemptCount + 1,
                        statusMessage = "Temporärer Fehler bei #${item.modId} • " +
                            "Versuch ${failedAttempts + 1}/${ScanRetryPolicy.MAX_RETRIES + 1} folgt"
                    )
                    scanStore.save(retrying)
                    updateProgressNotification(retrying)
                    delay(ScanRetryPolicy.backoffMs(failedAttempts))
                    continue
                }
            }

            val stored = result.outcome == ScanAttemptOutcome.STORED
            val excluded = result.outcome == ScanAttemptOutcome.EXCLUDED
            val processed = latest.copy(
                queue = latest.queue.removeHead(item.modId),
                processedIds = latest.processedIds + item.modId,
                failedIds = if (result.outcome == ScanAttemptOutcome.FAILED) {
                    latest.failedIds + item.modId
                } else {
                    latest.failedIds - item.modId
                },
                failedMessages = if (result.outcome == ScanAttemptOutcome.FAILED) {
                    latest.failedMessages + (item.modId to result.detail)
                } else {
                    latest.failedMessages - item.modId
                },
                excludedCount = latest.excludedCount + if (excluded) 1 else 0,
                statusMessage = when {
                    stored -> "Gespeichert: ${item.name.ifBlank { "Mod #${item.modId}" }}"
                    excluded -> "Ausgeschlossen: ${item.name.ifBlank { "Mod #${item.modId}" }}"
                    else -> "Nach 3 Versuchen fehlgeschlagen: ${item.name.ifBlank { "Mod #${item.modId}" }}"
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

    private fun List<QueueItem>.removeHead(modId: Long) =
        if (firstOrNull()?.modId == modId) drop(1) else filterNot { it.modId == modId }

    private fun List<QueueItem>.replaceHead(item: QueueItem): List<QueueItem> =
        if (isEmpty()) listOf(item) else listOf(item) + drop(1)

    private fun requestPause() {
        val current = scanStore.load()
        if (!current.running) return
        val pausing = current.copy(
            running = false,
            statusMessage = if (current.collecting) {
                "Listensammlung wird sicher pausiert …"
            } else {
                "Scan wird nach dem aktuellen Mod pausiert …"
            }
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
        val builder = Notification.Builder(this, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (state.collecting) "Nexus Radar sammelt Listen"
                else "Nexus Radar scannt Mods"
            )
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_notification,
                    "Pausieren",
                    pausePendingIntent()
                ).build()
            )
        if (state.collecting) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(total, completed, false)
        }
        return builder.build()
    }

    private fun finishCompleted(state: PersistedScanState) {
        val finalState = state.copy(
            running = false,
            collecting = false,
            collectionPending = false,
            statusMessage = "Scan abgeschlossen • ${state.processedCount} geprüft • " +
                "${state.retryAttemptCount} Wiederholungen • ${state.failedIds.size} Fehler"
        )
        scanStore.save(finalState)
        shuttingDownNormally = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.notify(
            COMPLETE_NOTIFICATION_ID,
            completionNotification(
                title = "Scan abgeschlossen",
                text = "${finalState.processedCount} geprüft • ${finalState.retryAttemptCount} Wiederholungen • " +
                    "${finalState.failedIds.size} Fehler"
            )
        )
        stopSelf()
    }

    private fun finishPaused(state: PersistedScanState) {
        val finalState = state.copy(
            running = false,
            collecting = false,
            statusMessage = if (state.collectionPending) {
                "Scan pausiert • Listensammlung und ${state.queue.size} Queue-Mods gespeichert"
            } else {
                "Scan pausiert • ${state.queue.size} Mods bleiben in der Queue"
            }
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
            if (current.running || current.collecting) {
                scanStore.save(
                    current.copy(
                        running = false,
                        collecting = false,
                        statusMessage = "Hintergrundscan unterbrochen • Fortschritt kann fortgesetzt werden"
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
        private const val ACTION_START_FULL = "com.meister.nexusradar.action.START_FULL_SCAN"
        private const val ACTION_RESUME = "com.meister.nexusradar.action.RESUME_SCAN"
        private const val ACTION_PAUSE = "com.meister.nexusradar.action.PAUSE_SCAN"
        private const val EXTRA_LISTING_URL = "listing_url"
        private const val PROGRESS_CHANNEL_ID = "scanner_progress_v1"
        private const val COMPLETE_CHANNEL_ID = "scanner_complete_v1"
        private const val ONGOING_NOTIFICATION_ID = 1101
        private const val COMPLETE_NOTIFICATION_ID = 1102

        @Volatile
        var isActive: Boolean = false
            private set

        fun startFull(context: Context, listingUrl: String) {
            context.startForegroundService(
                Intent(context, ScanForegroundService::class.java)
                    .setAction(ACTION_START_FULL)
                    .putExtra(EXTRA_LISTING_URL, listingUrl)
            )
        }

        fun resume(context: Context) {
            context.startForegroundService(
                Intent(context, ScanForegroundService::class.java).setAction(ACTION_RESUME)
            )
        }

        fun pause(context: Context) {
            context.startService(
                Intent(context, ScanForegroundService::class.java).setAction(ACTION_PAUSE)
            )
        }
    }
}
