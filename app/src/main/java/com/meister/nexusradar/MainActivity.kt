package com.meister.nexusradar

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.meister.nexusradar.browser.NexusPageParser
import com.meister.nexusradar.data.AppDatabase
import com.meister.nexusradar.data.ModEntity
import com.meister.nexusradar.domain.*
import com.meister.nexusradar.scan.*
import com.meister.nexusradar.settings.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

private val RadarColors = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF241006),
    secondary = Color(0xFFC6A8FF),
    background = Color(0xFF101116),
    surface = Color(0xFF191A20),
    surfaceVariant = Color(0xFF24252D),
    outline = Color(0xFF777985)
)

class MainActivity : ComponentActivity() {
    private lateinit var repo: Repository
    private lateinit var scanStore: ScanStateStore
    private lateinit var settingsStore: ScanSettingsStore
    private lateinit var exportStore: ExportDestinationStore
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repository(AppDatabase.get(this).modDao())
        scanStore = ScanStateStore(this)
        settingsStore = ScanSettingsStore(this)
        exportStore = ExportDestinationStore(this)
        setContent {
            MaterialTheme(colorScheme = RadarColors) {
                RadarApp()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RadarApp() {
        var tab by remember { mutableIntStateOf(0) }
        var scanStatus by remember { mutableStateOf("Bereit") }
        var exportStatus by remember { mutableStateOf("Noch kein Export ausgeführt") }
        var address by remember {
            mutableStateOf("https://www.nexusmods.com/skyrimspecialedition/mods/")
        }
        var scanState by remember { mutableStateOf(scanStore.load()) }
        var settings by remember { mutableStateOf(settingsStore.load()) }
        var exportUri by remember { mutableStateOf(exportStore.load()) }
        val mods by repo.observeMods().collectAsState(initial = emptyList())

        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    runCatching {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                            repo.importJson(it.readText())
                        } ?: error("Datei konnte nicht geöffnet werden")
                    }.onSuccess {
                        exportStatus = "Importiert: ${it.accepted} • ausgeschlossen: ${it.rejected}"
                    }.onFailure {
                        exportStatus = "Importfehler: ${it.message ?: "unbekannt"}"
                    }
                }
            }
        }

        val exportDirectoryPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val persisted = runCatching {
                    contentResolver.takePersistableUriPermission(uri, flags)
                }.isSuccess
                exportStore.save(uri)
                exportUri = uri
                val name = DocumentFile.fromTreeUri(this, uri)?.getName() ?: "gewählter Ordner"
                exportStatus = if (persisted) {
                    "Exportordner gespeichert: $name"
                } else {
                    "Exportordner für diese Sitzung gewählt: $name"
                }
            }
        }

        val exportFolderName = remember(exportUri) {
            exportUri?.let { DocumentFile.fromTreeUri(this, it)?.getName() }
                ?: "Kein Exportordner gewählt"
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Nexus Skyrim Radar", maxLines = 1)
                            Text("v0.8", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                val tabs = listOf("Scanner", "Katalog", "Setup", "Export")
                ScrollableTabRow(
                    selectedTabIndex = tab,
                    edgePadding = 0.dp,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    tabs.forEachIndexed { index, label ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            modifier = Modifier.widthIn(min = 88.dp),
                            text = { Text(label, maxLines = 1) }
                        )
                    }
                }
                when (tab) {
                    0 -> BrowserPane(
                        address = address,
                        setAddress = { address = it },
                        status = scanStatus,
                        setStatus = { scanStatus = it },
                        scanState = scanState,
                        setScanState = { state ->
                            scanState = state
                            scanStore.save(state)
                        },
                        settings = settings
                    )
                    1 -> CatalogPane(mods)
                    2 -> SettingsPane(settings) {
                        settings = it.normalized()
                        settingsStore.save(settings)
                    }
                    else -> ExportPane(
                        mods = mods,
                        status = exportStatus,
                        settings = settings,
                        updateSettings = {
                            settings = it.normalized()
                            settingsStore.save(settings)
                        },
                        folderName = exportFolderName,
                        chooseFolder = { exportDirectoryPicker.launch(exportUri) },
                        importClick = {
                            importer.launch(arrayOf("application/json", "text/plain"))
                        },
                        exportClick = {
                            val target = exportUri
                            if (target == null) {
                                exportStatus = "Bitte zuerst einen Exportordner wählen"
                            } else {
                                lifecycleScope.launch {
                                    exportStatus = "Export läuft …"
                                    runCatching { export(target, settings) }
                                        .onSuccess { exportStatus = it }
                                        .onFailure {
                                            exportStatus = "Exportfehler: ${it.message ?: "Schreibzugriff fehlgeschlagen"}"
                                        }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun BrowserPane(
        address: String,
        setAddress: (String) -> Unit,
        status: String,
        setStatus: (String) -> Unit,
        scanState: PersistedScanState,
        setScanState: (PersistedScanState) -> Unit,
        settings: ScanSettings
    ) {
        var localWeb by remember { mutableStateOf<WebView?>(null) }
        var confirmReset by remember { mutableStateOf(false) }
        val denominator = scanState.totalForRun.coerceAtLeast(1)
        val progress = (scanState.processedCount.toFloat() / denominator.toFloat())
            .coerceIn(0f, 1f)

        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text("Scanstand zurücksetzen?") },
                text = {
                    Text("Queue, Fortschritt und Fehlerliste werden geleert. Der Katalog bleibt erhalten und kann danach mit den verbesserten Metadaten neu gescannt werden.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmReset = false
                        scanStore.clear()
                        setScanState(PersistedScanState())
                        setStatus("Scanstand zurückgesetzt")
                    }) { Text("Zurücksetzen") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmReset = false }) { Text("Abbrechen") }
                }
            )
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = address,
                    onValueChange = setAddress,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Nexus-Adresse") }
                )
                Button(
                    onClick = { localWeb?.loadUrl(address) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) { Text("Öffnen") }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        localWeb?.let {
                            collectListingPages(
                                it, scanState, setScanState, settings, setStatus
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !scanState.running
                ) { Text("Liste sammeln", maxLines = 1) }
                Button(
                    onClick = {
                        localWeb?.let {
                            runQueue(it, scanState, setScanState, settings, setStatus)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = scanState.queue.isNotEmpty() && !scanState.running
                ) { Text("Queue starten", maxLines = 1) }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        localWeb?.let { parseCurrentPage(it, settings, setStatus) }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !scanState.running
                ) { Text("Einzelmod", maxLines = 1) }
                OutlinedButton(
                    onClick = {
                        if (scanState.running) {
                            val paused = scanState.copy(running = false)
                            setScanState(paused)
                            setStatus("Scan pausiert")
                        } else {
                            confirmReset = true
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (scanState.running) "Pausieren" else "Neu scannen", maxLines = 1)
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Queue ${scanState.queue.size}")
                        Text("Fertig ${scanState.processedIds.size}")
                        Text("Fehler ${scanState.failedIds.size}")
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${settings.rangeDays} Tage • $status",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        this.settings.javaScriptEnabled = true
                        this.settings.domStorageEnabled = true
                        this.settings.databaseEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                setAddress(url)
                                setStatus("Nexus-Seite geladen")
                            }
                        }
                        loadUrl(address)
                        localWeb = this
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                update = { localWeb = it }
            )
        }
    }

    private fun parseCurrentPage(
        web: WebView,
        settings: ScanSettings,
        setStatus: (String) -> Unit,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        if (!isModDetailsPage(web.url)) {
            setStatus("Bitte zuerst eine einzelne Skyrim-Modseite öffnen")
            onDone?.invoke(false)
            return
        }
        lifecycleScope.launch {
            val success = scanCurrentPage(web, settings, setStatus, expectedId = null)
            onDone?.invoke(success)
        }
    }

    private fun collectListingPages(
        web: WebView,
        initial: PersistedScanState,
        setState: (PersistedScanState) -> Unit,
        settings: ScanSettings,
        setStatus: (String) -> Unit
    ) {
        lifecycleScope.launch {
            var state = initial
            var page = 0
            var nextUrl: String? = web.url
            while (page < settings.pageLimit && !nextUrl.isNullOrBlank()) {
                if (page > 0) {
                    web.loadUrl(nextUrl)
                    delay(settings.delayMs.coerceAtLeast(1500L))
                }
                val parsed = readVisibleLinksWithRetry(web)
                if (parsed == null) {
                    setStatus("Listing konnte nicht gelesen werden")
                    break
                }
                val existing = (state.queue.map { it.modId } + state.processedIds).toSet()
                val added = parsed.links
                    .filter { it.mod_id !in existing }
                    .map { QueueItem(it.mod_id, it.url, it.name) }
                state = state.copy(queue = state.queue + added, lastUrl = parsed.url)
                setState(state)
                page++
                setStatus("Listing $page/${settings.pageLimit}: +${added.size} Mods")
                nextUrl = parsed.next_url
            }
            setStatus("Gesammelt: ${state.queue.size} Mods in der Queue")
        }
    }

    private fun runQueue(
        web: WebView,
        initial: PersistedScanState,
        setState: (PersistedScanState) -> Unit,
        settings: ScanSettings,
        setStatus: (String) -> Unit
    ) {
        if (initial.running || initial.queue.isEmpty()) return
        var state = initial.copy(
            running = true,
            delayMs = settings.delayMs,
            startedWith = initial.queue.size + initial.processedIds.size
        )
        setState(state)
        lifecycleScope.launch {
            while (state.running && state.queue.isNotEmpty()) {
                if (!scanStore.load().running) {
                    state = state.copy(running = false)
                    setState(state)
                    break
                }
                val item = state.queue.first()
                state = state.copy(queue = state.queue.drop(1), lastUrl = item.url)
                setState(state)
                setStatus("Lade #${item.modId}: ${item.name.ifBlank { "Mod" }}")
                web.loadUrl(item.url)
                delay(settings.delayMs.coerceAtLeast(1500L))

                val ok = scanCurrentPage(
                    web = web,
                    settings = settings,
                    setStatus = setStatus,
                    expectedId = item.modId
                )
                state = if (ok) {
                    state.copy(processedIds = state.processedIds + item.modId)
                } else {
                    state.copy(
                        processedIds = state.processedIds + item.modId,
                        failedIds = state.failedIds + item.modId
                    )
                }
                setState(state)
                delay(350)
            }
            state = state.copy(running = false)
            setState(state)
            setStatus(if (state.queue.isEmpty()) "Queue abgeschlossen" else "Queue pausiert")
        }
    }

    private suspend fun scanCurrentPage(
        web: WebView,
        settings: ScanSettings,
        setStatus: (String) -> Unit,
        expectedId: Long?
    ): Boolean {
        return try {
            val record = parseRecordWithRetry(web, expectedId)
                ?: error("Nexus-Metadaten wurden nicht rechtzeitig geladen")
            val (state, inRange) = RangeClassifier.classify(
                record.published_at,
                record.updated_at,
                settings.rangeDays
            )
            val enriched = record.copy(
                collection_state = state,
                in_selected_range = inRange
            )
            val result = repo.importSingle(enriched)
            val missing = record.diagnostics.size
            val detail = if (missing == 0) "" else " • $missing Metadaten offen"
            setStatus(
                if (result.accepted == 1) "$state: ${record.name}$detail"
                else "Ausgeschlossen: ${record.name}"
            )
            result.accepted == 1
        } catch (error: Exception) {
            setStatus("Parserfehler: ${error.message ?: "unbekannt"}")
            false
        }
    }

    private suspend fun parseRecordWithRetry(
        web: WebView,
        expectedId: Long?
    ): NexusModRecord? {
        var best: NexusModRecord? = null
        var bestScore = -1
        repeat(10) {
            val candidate = runCatching {
                val raw = evaluateJavascript(web, NexusPageParser.parseCurrentMod)
                json.decodeFromString<NexusModRecord>(decodeJsString(raw))
            }.getOrNull()
            if (candidate != null && (expectedId == null || candidate.mod_id == expectedId)) {
                val score = metadataScore(candidate)
                if (score > bestScore) {
                    best = candidate
                    bestScore = score
                }
                if (
                    candidate.mod_id > 0 &&
                    candidate.name.isNotBlank() &&
                    candidate.version != null &&
                    candidate.category != null &&
                    candidate.published_at != null &&
                    candidate.updated_at != null
                ) {
                    return candidate
                }
            }
            delay(700)
        }
        return best
    }

    private fun metadataScore(record: NexusModRecord): Int =
        listOf(
            record.mod_id > 0,
            record.name.isNotBlank(),
            !record.version.isNullOrBlank(),
            !record.category.isNullOrBlank(),
            !record.published_at.isNullOrBlank(),
            !record.updated_at.isNullOrBlank()
        ).count { it }

    private suspend fun readVisibleLinksWithRetry(web: WebView): VisibleLinksResult? {
        var best: VisibleLinksResult? = null
        repeat(10) {
            val result = runCatching {
                val raw = evaluateJavascript(web, NexusPageParser.collectVisibleModLinks)
                json.decodeFromString<VisibleLinksResult>(decodeJsString(raw))
            }.getOrNull()
            if (result != null) {
                best = result
                if (result.links.isNotEmpty()) return result
            }
            delay(500)
        }
        return best
    }

    private suspend fun evaluateJavascript(web: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            web.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }

    private fun isModDetailsPage(url: String?): Boolean =
        Regex("/skyrimspecialedition/mods/\\d+", RegexOption.IGNORE_CASE)
            .containsMatchIn(url.orEmpty())

    @Composable
    private fun CatalogPane(mods: List<ModEntity>) {
        var query by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf("ALL") }
        val newCount = mods.count { it.collectionState == "NEW" && it.inSelectedRange }
        val updatedCount = mods.count {
            it.collectionState == "UPDATED" && it.inSelectedRange
        }
        val adultCount = mods.count { it.adult }

        val filtered = remember(mods, query, filter) {
            mods.filter { mod ->
                val matchesState = when (filter) {
                    "NEW" -> mod.collectionState == "NEW" && mod.inSelectedRange
                    "UPDATED" -> mod.collectionState == "UPDATED" && mod.inSelectedRange
                    "ADULT" -> mod.adult
                    else -> true
                }
                val haystack = listOf(
                    mod.name,
                    mod.author.orEmpty(),
                    mod.category.orEmpty(),
                    mod.modId.toString()
                ).joinToString(" ").lowercase()
                matchesState && (query.isBlank() || query.lowercase() in haystack)
            }
        }
        val byCategory = remember(filtered) {
            filtered.groupBy {
                it.category?.takeIf(String::isNotBlank) ?: "Kategorie noch nicht erkannt"
            }
        }
        val categoryNames = remember(byCategory) {
            byCategory.keys.sortedWith(
                compareBy<String> { if (it == "Kategorie noch nicht erkannt") 1 else 0 }
                    .thenBy { it.lowercase() }
            )
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Mod-Katalog",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { StatBadge("Gesamt", mods.size) }
                item { StatBadge("NEW", newCount) }
                item { StatBadge("UPDATED", updatedCount) }
                item { StatBadge("Adult", adultCount) }
                item { StatBadge("Kategorien", byCategory.size) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Mods oder Kategorien durchsuchen") }
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(
                    listOf(
                        "ALL" to "Alle",
                        "NEW" to "Neu",
                        "UPDATED" to "Aktualisiert",
                        "ADULT" to "Adult"
                    )
                ) { (value, label) ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { filter = value },
                        label = { Text(label) }
                    )
                }
            }
            if (filtered.isEmpty()) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (mods.isEmpty()) "Noch keine Mods gescannt"
                        else "Für diesen Filter wurden keine Mods gefunden",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    categoryNames.forEach { category ->
                        val categoryMods = byCategory[category].orEmpty()
                        item(key = "category:$category") {
                            CategoryHeader(category, categoryMods.size)
                        }
                        items(categoryMods, key = { "mod:${it.modId}" }) { mod ->
                            ModCard(mod)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatBadge(label: String, count: Int) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                "$label $count",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }

    @Composable
    private fun CategoryHeader(category: String, count: Int) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                category,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    count.toString(),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }

    @Composable
    private fun ModCard(mod: ModEntity) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    mod.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "#${mod.modId} • v${mod.version ?: "?"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        mod.collectionState + if (mod.adult) " • ADULT" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = when (mod.collectionState) {
                            "NEW" -> MaterialTheme.colorScheme.primary
                            "UPDATED" -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Text(
                    "Update: ${displayDate(mod.updatedAt)}" +
                        (mod.author?.let { " • $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (mod.diagnostics.isNotBlank()) {
                    Text(
                        "Metadaten unvollständig – bitte nach dem Update neu scannen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SettingsPane(current: ScanSettings, update: (ScanSettings) -> Unit) {
        var delayText by remember(current.delayMs) {
            mutableStateOf(current.delayMs.toString())
        }
        var pageText by remember(current.pageLimit) {
            mutableStateOf(current.pageLimit.toString())
        }
        val ranges = listOf(1, 7, 14, 30, 90, 180, 365, 730, 1095, 1460, 1825, 2190)

        Column(
            Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Scan-Einstellungen",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            SectionCard("Zeitraum") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    maxItemsInEachRow = 4
                ) {
                    ranges.forEach { days ->
                        FilterChip(
                            selected = current.rangeDays == days,
                            onClick = { update(current.copy(rangeDays = days)) },
                            label = {
                                Text(if (days < 365) "${days}T" else "${days / 365}J")
                            }
                        )
                    }
                }
            }
            SectionCard("Scan-Geschwindigkeit") {
                OutlinedTextField(
                    value = delayText,
                    onValueChange = {
                        delayText = it.filter(Char::isDigit)
                        it.toLongOrNull()?.let { value ->
                            update(current.copy(delayMs = value))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Pause je Mod: 1500–15000 ms") }
                )
                OutlinedTextField(
                    value = pageText,
                    onValueChange = {
                        pageText = it.filter(Char::isDigit)
                        it.toIntOrNull()?.let { value ->
                            update(current.copy(pageLimit = value))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Listing-Seiten: 1–500") }
                )
                Text(
                    "3000 ms sind ein sicherer Startwert. Große Läufe zuerst mit wenigen Seiten testen.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            SectionCard("Aktive Filter") {
                Text("✓ Adult-Mods bleiben erhalten")
                Text("✓ Nexus-Kategorien werden übernommen")
                Text("✓ Requirements und Required-by werden erfasst")
                Text("× Übersetzungen, Bilder, Videos und Savegames")
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ExportPane(
        mods: List<ModEntity>,
        status: String,
        settings: ScanSettings,
        updateSettings: (ScanSettings) -> Unit,
        folderName: String,
        chooseFolder: () -> Unit,
        importClick: () -> Unit,
        exportClick: () -> Unit
    ) {
        var chunkText by remember(settings.chunkSize) {
            mutableStateOf(settings.chunkSize.toString())
        }
        val exportableCount = when (settings.exportMode) {
            ExportMode.CHANGED -> mods.count {
                it.inSelectedRange && it.collectionState in setOf("NEW", "UPDATED")
            }
            ExportMode.RANGE -> mods.count { it.inSelectedRange }
            ExportMode.ALL -> mods.size
        }

        Column(
            Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "JSON-Dateien",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            SectionCard("1. Exportumfang") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    ExportMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.exportMode == mode,
                            onClick = { updateSettings(settings.copy(exportMode = mode)) },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text(
                    "Exportierbar: $exportableCount von ${mods.size} Mods",
                    color = if (exportableCount == 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (exportableCount == 0 && mods.isNotEmpty()) {
                    Text(
                        "Wähle „Gesamter Katalog“ oder scanne die Mods nach dem Update erneut.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            SectionCard("2. Dateigröße") {
                OutlinedTextField(
                    value = chunkText,
                    onValueChange = {
                        chunkText = it.filter(Char::isDigit)
                        it.toIntOrNull()?.let { value ->
                            updateSettings(settings.copy(chunkSize = value))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Mods pro JSON-Datei: 10–500") }
                )
            }
            SectionCard("3. Zielordner") {
                Text(folderName, fontWeight = FontWeight.SemiBold)
                OutlinedButton(
                    onClick = chooseFolder,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Ordner wählen oder ändern") }
            }
            Button(
                onClick = exportClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = exportableCount > 0 && folderName != "Kein Exportordner gewählt"
            ) {
                Text("Jetzt $exportableCount Mods exportieren")
            }
            OutlinedButton(
                onClick = importClick,
                modifier = Modifier.fillMaxWidth()
            ) { Text("JSON-Datei importieren") }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(status, modifier = Modifier.padding(12.dp))
            }
        }
    }

    @Composable
    private fun SectionCard(
        title: String,
        content: @Composable ColumnScope.() -> Unit
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                content()
            }
        }
    }

    private suspend fun export(tree: Uri, settings: ScanSettings): String {
        val onlyInRange = settings.exportMode != ExportMode.ALL
        val onlyChanged = settings.exportMode == ExportMode.CHANGED
        val chunks = repo.exportChunks(
            chunkSize = settings.chunkSize,
            onlyInRange = onlyInRange,
            onlyChanged = onlyChanged
        )
        require(chunks.isNotEmpty()) {
            "Für den gewählten Exportumfang sind keine Mods vorhanden"
        }

        val directory = DocumentFile.fromTreeUri(this, tree)
            ?: error("Der Zielordner ist nicht mehr verfügbar")
        require(directory.exists() && directory.isDirectory) {
            "Der Zielordner existiert nicht mehr"
        }
        require(directory.canWrite()) {
            "Für den Zielordner fehlt die Schreibberechtigung"
        }

        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val modeName = settings.exportMode.name.lowercase()
        var written = 0
        chunks.forEachIndexed { index, contents ->
            val name = "skyrimse_${modeName}_${settings.rangeDays}d_${timestamp}_chunk_" +
                (index + 1).toString().padStart(4, '0') + ".json"
            val file = directory.createFile("application/json", name)
                ?: error("Datei $name konnte nicht angelegt werden")
            val stream = contentResolver.openOutputStream(file.uri, "w")
                ?: error("Datei $name konnte nicht geöffnet werden")
            stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(contents)
            }
            written++
        }
        return "$written JSON-Dateien gespeichert in ${directory.getName() ?: "Zielordner"}"
    }

    private fun displayDate(value: String?): String =
        value?.substringBefore('T')?.takeIf(String::isNotBlank) ?: "unbekannt"

    private fun decodeJsString(raw: String): String = runCatching {
        json.decodeFromString<String>(raw)
    }.getOrElse {
        raw.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
