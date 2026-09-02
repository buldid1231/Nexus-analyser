package com.meister.nexusradar

import android.annotation.SuppressLint
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.meister.nexusradar.browser.NexusPageParser
import com.meister.nexusradar.data.AppDatabase
import com.meister.nexusradar.domain.*
import com.meister.nexusradar.scan.*
import com.meister.nexusradar.settings.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {
    private lateinit var repo: Repository
    private lateinit var scanStore: ScanStateStore
    private lateinit var settingsStore: ScanSettingsStore
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repository(AppDatabase.get(this).modDao())
        scanStore = ScanStateStore(this)
        settingsStore = ScanSettingsStore(this)
        setContent { MaterialTheme { RadarApp() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RadarApp() {
        var tab by remember { mutableIntStateOf(0) }
        var status by remember { mutableStateOf("Bereit") }
        var address by remember { mutableStateOf("https://www.nexusmods.com/skyrimspecialedition/mods/") }
        var scanState by remember { mutableStateOf(scanStore.load()) }
        var settings by remember { mutableStateOf(settingsStore.load()) }
        val mods by repo.observeMods().collectAsState(initial = emptyList())

        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) lifecycleScope.launch {
                runCatching { contentResolver.openInputStream(uri)!!.bufferedReader().use { repo.importJson(it.readText()) } }
                    .onSuccess { status = "Importiert: ${it.accepted}, ausgeschlossen: ${it.rejected}" }
                    .onFailure { status = "Importfehler: ${it.message}" }
            }
        }
        val exportDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) lifecycleScope.launch { export(uri, settings) { status = it } }
        }

        Scaffold(topBar = { TopAppBar(title = { Text("Nexus Skyrim Radar v0.7") }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                TabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Scanner") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Katalog") })
                    Tab(tab == 2, { tab = 2 }, text = { Text("Einstellungen") })
                    Tab(tab == 3, { tab = 3 }, text = { Text("Export") })
                }
                when (tab) {
                    0 -> BrowserPane(address, { address = it }, status, { status = it }, scanState, { s -> scanState = s; scanStore.save(s) }, settings)
                    1 -> CatalogPane(mods)
                    2 -> SettingsPane(settings) { settings = it.normalized(); settingsStore.save(settings) }
                    else -> ExportPane(mods.size, status, settings, { importer.launch(arrayOf("application/json", "text/plain")) }, { exportDir.launch(null) })
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
        val denominator = scanState.totalForRun.coerceAtLeast(1)
        val progress = (scanState.processedCount.toFloat() / denominator.toFloat()).coerceIn(0f, 1f)

        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(address, setAddress, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Nexus URL") })
                Button(onClick = { localWeb?.loadUrl(address) }) { Text("Öffnen") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { localWeb?.let { parseCurrentPage(it, settings, setStatus) } }) { Text("Mod scannen") }
                Button(onClick = { localWeb?.let { collectListingPages(it, scanState, setScanState, settings, setStatus) } }) { Text("Listen sammeln") }
                Button(onClick = { localWeb?.let { runQueue(it, scanState, setScanState, settings, setStatus) } }, enabled = scanState.queue.isNotEmpty() && !scanState.running) { Text("Queue") }
                OutlinedButton(onClick = {
                    val paused = scanStore.load().copy(running = false)
                    scanStore.save(paused)
                    setScanState(scanState.copy(running = false))
                    setStatus("Scan pausiert")
                }, enabled = scanState.running) { Text("Pause") }
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Text(
                "Queue ${scanState.queue.size} • verarbeitet ${scanState.processedIds.size} • Fehler ${scanState.failedIds.size} • ${settings.rangeDays} Tage • $status",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                setAddress(url)
                                setStatus("Geladen")
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

    private fun parseCurrentPage(web: WebView, settings: ScanSettings, setStatus: (String) -> Unit, onDone: ((Boolean) -> Unit)? = null) {
        if (!web.url.orEmpty().contains("/skyrimspecialedition/mods/")) {
            setStatus("Keine Skyrim-Modseite geöffnet")
            onDone?.invoke(false)
            return
        }
        web.evaluateJavascript(NexusPageParser.parseCurrentMod) { raw ->
            val decoded = decodeJsString(raw)
            lifecycleScope.launch {
                runCatching { json.decodeFromString<NexusModRecord>(decoded) }
                    .onSuccess { record ->
                        val (state, inRange) = RangeClassifier.classify(record.published_at, record.updated_at, settings.rangeDays)
                        val enriched = record.copy(collection_state = state, in_selected_range = inRange)
                        val result = repo.importSingle(enriched)
                        setStatus(if (result.accepted == 1) "${state}: ${record.name}" else "Ausgeschlossen: ${record.name}")
                        onDone?.invoke(result.accepted == 1)
                    }
                    .onFailure {
                        setStatus("Parserfehler: ${it.message}")
                        onDone?.invoke(false)
                    }
            }
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
                var result: VisibleLinksResult? = null
                web.evaluateJavascript(NexusPageParser.collectVisibleModLinks) { raw ->
                    result = runCatching { json.decodeFromString<VisibleLinksResult>(decodeJsString(raw)) }.getOrNull()
                }
                var waited = 0L
                while (result == null && waited < 8000L) { delay(200); waited += 200 }
                val parsed = result ?: break
                val existing = (state.queue.map { it.modId } + state.processedIds).toSet()
                val added = parsed.links.filter { it.mod_id !in existing }.map { QueueItem(it.mod_id, it.url, it.name) }
                state = state.copy(queue = state.queue + added, lastUrl = parsed.url)
                setState(state)
                page++
                setStatus("Listing $page/${settings.pageLimit}: +${added.size} Mods")
                nextUrl = parsed.next_url
            }
            setStatus("Listen gesammelt: ${state.queue.size} Mods in Queue")
        }
    }

    private fun runQueue(
        web: WebView,
        initial: PersistedScanState,
        setState: (PersistedScanState) -> Unit,
        settings: ScanSettings,
        setStatus: (String) -> Unit
    ) {
        if (initial.running) return
        var state = initial.copy(running = true, delayMs = settings.delayMs, startedWith = initial.queue.size + initial.processedIds.size)
        setState(state)
        lifecycleScope.launch {
            while (state.running && state.queue.isNotEmpty()) {
                if (!scanStore.load().running) { state = state.copy(running = false); setState(state); break }
                val item = state.queue.first()
                state = state.copy(queue = state.queue.drop(1), lastUrl = item.url)
                setState(state)
                web.loadUrl(item.url)
                delay(settings.delayMs.coerceAtLeast(1500L))
                var done = false
                var ok = false
                parseCurrentPage(web, settings, setStatus) { success -> ok = success; done = true }
                var waited = 0L
                while (!done && waited < 12_000L) { delay(200); waited += 200 }
                state = if (ok) {
                    state.copy(processedIds = state.processedIds + item.modId)
                } else {
                    state.copy(processedIds = state.processedIds + item.modId, failedIds = state.failedIds + item.modId)
                }
                setState(state)
                delay(350)
            }
            state = state.copy(running = false)
            setState(state)
            setStatus(if (state.queue.isEmpty()) "Queue abgeschlossen" else "Queue pausiert")
        }
    }

    @Composable
    private fun CatalogPane(mods: List<com.meister.nexusradar.data.ModEntity>) {
        val newCount = mods.count { it.collectionState == "NEW" && it.inSelectedRange }
        val updatedCount = mods.count { it.collectionState == "UPDATED" && it.inSelectedRange }
        val adultCount = mods.count { it.adult }
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("Katalog ${mods.size} • NEW $newCount • UPDATED $updatedCount • Adult $adultCount", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(mods, key = { it.modId }) { mod ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(mod.name, style = MaterialTheme.typography.titleSmall)
                            Text("#${mod.modId} • ${mod.version ?: "?"} • ${mod.collectionState}${if (mod.adult) " • ADULT" else ""}")
                            Text("Update: ${mod.updatedAt ?: "?"} • ${mod.category ?: "Kategorie ?"}", style = MaterialTheme.typography.bodySmall)
                            if (mod.diagnostics.isNotBlank()) Text("Parser: ${mod.diagnostics}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsPane(current: ScanSettings, update: (ScanSettings) -> Unit) {
        var delayText by remember(current.delayMs) { mutableStateOf(current.delayMs.toString()) }
        var pageText by remember(current.pageLimit) { mutableStateOf(current.pageLimit.toString()) }
        var chunkText by remember(current.chunkSize) { mutableStateOf(current.chunkSize.toString()) }
        val ranges = listOf(1, 7, 14, 30, 90, 180, 365, 730, 1095, 1460, 1825, 2190)
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Zeitraum", style = MaterialTheme.typography.titleMedium)
            ranges.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { days ->
                        FilterChip(
                            selected = current.rangeDays == days,
                            onClick = { update(current.copy(rangeDays = days)) },
                            label = { Text(if (days < 365) "${days}T" else "${days / 365}J") }
                        )
                    }
                }
            }
            OutlinedTextField(delayText, { delayText = it.filter(Char::isDigit); it.toLongOrNull()?.let { v -> update(current.copy(delayMs = v)) } }, label = { Text("Delay pro Mod in ms (1500-15000)") })
            OutlinedTextField(pageText, { pageText = it.filter(Char::isDigit); it.toIntOrNull()?.let { v -> update(current.copy(pageLimit = v)) } }, label = { Text("Listing-Seiten pro Sammellauf (1-500)") })
            OutlinedTextField(chunkText, { chunkText = it.filter(Char::isDigit); it.toIntOrNull()?.let { v -> update(current.copy(chunkSize = v)) } }, label = { Text("Mods pro Exportblock (10-500)") })
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(current.exportOnlyChanged, { update(current.copy(exportOnlyChanged = it)) })
                Spacer(Modifier.width(8.dp))
                Text("Beim Export nur NEW + UPDATED")
            }
            Text("Adult-Mods werden nicht ausgefiltert. Translation-/Localization-Mods, Bilder, Videos, Screenshots und Savegames werden ausgeschlossen.", style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun ExportPane(count: Int, status: String, settings: ScanSettings, importClick: () -> Unit, exportClick: () -> Unit) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Katalog: $count Mods")
            Text("Export: ${settings.chunkSize} Mods je Block • ${settings.rangeDays} Tage • ${if (settings.exportOnlyChanged) "NEW + UPDATED" else "gesamter Zeitraum"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = importClick) { Text("JSON importieren") }
                Button(onClick = exportClick, enabled = count > 0) { Text("Chunks exportieren") }
            }
            Text(status)
        }
    }

    private suspend fun export(tree: Uri, settings: ScanSettings, status: (String) -> Unit) {
        val chunks = repo.exportChunks(settings.chunkSize, onlyInRange = true, onlyChanged = settings.exportOnlyChanged)
        val docTree = DocumentFile.fromTreeUri(this, tree)
        chunks.forEachIndexed { i, text ->
            val file = docTree?.createFile("application/json", "skyrimse_${settings.rangeDays}d_${(i + 1).toString().padStart(4, '0')}.json")
            if (file != null) contentResolver.openOutputStream(file.uri)?.bufferedWriter()?.use { it.write(text) }
        }
        status("${chunks.size} Exportblöcke geschrieben")
    }

    private fun decodeJsString(raw: String): String = runCatching {
        json.decodeFromString<String>(raw)
    }.getOrElse { raw.trim('"').replace("\\\"", "\"").replace("\\\\", "\\") }
}
