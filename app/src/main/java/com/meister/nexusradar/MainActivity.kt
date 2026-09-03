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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
        var screen by rememberSaveable { mutableIntStateOf(0) }
        var scanStatus by remember { mutableStateOf("Bereit") }
        var exportStatus by remember { mutableStateOf("Noch kein Export ausgeführt") }
        var address by remember {
            mutableStateOf("https://www.nexusmods.com/skyrimspecialedition/mods/")
        }
        var scanState by remember { mutableStateOf(scanStore.load()) }
        var settings by remember { mutableStateOf(settingsStore.load()) }
        var exportUri by remember { mutableStateOf(exportStore.load()) }
        var catalogFilters by remember { mutableStateOf(CatalogFilterState()) }
        val mods by repo.observeMods().collectAsState(initial = emptyList())
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()

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

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(Modifier.widthIn(max = 360.dp)) {
                    AppDrawer(
                        currentScreen = screen,
                        selectScreen = { selected ->
                            screen = selected
                            scope.launch { drawerState.close() }
                        },
                        mods = mods,
                        filters = catalogFilters,
                        updateFilters = { catalogFilters = it },
                        resetFilters = { catalogFilters = CatalogFilterState() }
                    )
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Text("☰", style = MaterialTheme.typography.headlineSmall)
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    listOf("Scanner", "Katalog", "Setup", "Export")[screen],
                                    maxLines = 1
                                )
                                Text("Nexus Skyrim Radar • v0.10", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        actions = {
                            if (screen == 1 && catalogFilters.activeCount() > 0) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        catalogFilters.activeCount().toString(),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (screen) {
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
                        1 -> CatalogPane(
                            mods = mods,
                            filters = catalogFilters,
                            updateFilters = { catalogFilters = it },
                            openFilters = { scope.launch { drawerState.open() } }
                        )
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
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun AppDrawer(
        currentScreen: Int,
        selectScreen: (Int) -> Unit,
        mods: List<ModEntity>,
        filters: CatalogFilterState,
        updateFilters: (CatalogFilterState) -> Unit,
        resetFilters: () -> Unit
    ) {
        val screens = listOf("Scanner", "Katalog", "Setup", "Export")
        val categoryCounts = remember(mods) {
            mods.groupingBy {
                it.category?.takeIf(String::isNotBlank) ?: UNKNOWN_CATEGORY
            }.eachCount().toList().sortedBy { it.first.lowercase() }
        }

        LazyColumn(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            item {
                Text(
                    "Nexus Skyrim Radar",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Text(
                    "v0.10 • lokaler Mod-Katalog",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 12.dp)
                )
            }
            items(screens.indices.toList()) { index ->
                NavigationDrawerItem(
                    label = { Text(screens[index]) },
                    selected = currentScreen == index,
                    onClick = { selectScreen(index) },
                    badge = {
                        if (index == 1) Text(mods.size.toString())
                    }
                )
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Katalogfilter", fontWeight = FontWeight.Bold)
                        Text(
                            "${filters.activeCount()} aktiv",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = resetFilters) { Text("Zurücksetzen") }
                }
            }
            item {
                DrawerSectionTitle("Inhalt")
                DrawerCheckbox(
                    label = "NSFW anzeigen",
                    detail = "${mods.count { it.adult }} Adult-Mods im Katalog",
                    checked = filters.showAdult,
                    onChecked = { updateFilters(filters.copy(showAdult = it)) }
                )
                DrawerCheckbox(
                    label = "Nur gewählter Zeitraum",
                    checked = filters.onlyInRange,
                    onChecked = { updateFilters(filters.copy(onlyInRange = it)) }
                )
                DrawerCheckbox(
                    label = "Nur SKSE / DLL",
                    checked = filters.onlySkseOrDll,
                    onChecked = { updateFilters(filters.copy(onlySkseOrDll = it)) }
                )
                DrawerCheckbox(
                    label = "Nur mit Requirements",
                    checked = filters.onlyWithRequirements,
                    onChecked = { updateFilters(filters.copy(onlyWithRequirements = it)) }
                )
                DrawerCheckbox(
                    label = "Nach Kategorie gruppieren",
                    checked = filters.groupByCategory,
                    onChecked = { updateFilters(filters.copy(groupByCategory = it)) }
                )
            }
            item {
                DrawerSectionTitle("Status")
                listOf(
                    "NEW" to "Neu",
                    "UPDATED" to "Aktualisiert",
                    "UNCHANGED" to "Unverändert",
                    "DISCOVERED" to "Nur entdeckt"
                ).forEach { (state, label) ->
                    DrawerCheckbox(
                        label = label,
                        checked = state in filters.selectedStates,
                        onChecked = { checked ->
                            updateFilters(
                                filters.copy(
                                    selectedStates = if (checked) {
                                        filters.selectedStates + state
                                    } else {
                                        filters.selectedStates - state
                                    }
                                )
                            )
                        }
                    )
                }
            }
            item {
                DrawerSectionTitle("Sortierung")
                CatalogSort.entries.forEach { sort ->
                    DrawerRadio(
                        label = sort.label,
                        selected = filters.sort == sort,
                        onClick = { updateFilters(filters.copy(sort = sort)) }
                    )
                }
            }
            item {
                DrawerSectionTitle("Dateigröße")
                SizeFilter.entries.forEach { size ->
                    DrawerRadio(
                        label = size.label,
                        selected = filters.sizeFilter == size,
                        onClick = { updateFilters(filters.copy(sizeFilter = size)) }
                    )
                }
            }
            item {
                DrawerSectionTitle("Nexus-Kategorien")
                DrawerCheckbox(
                    label = "Alle Kategorien",
                    detail = "${mods.size} Mods",
                    checked = filters.selectedCategories.isEmpty(),
                    onChecked = { checked ->
                        if (checked) updateFilters(filters.copy(selectedCategories = emptySet()))
                    }
                )
            }
            items(categoryCounts, key = { "drawer-category:${it.first}" }) { (category, count) ->
                DrawerCheckbox(
                    label = category,
                    detail = "$count Mods",
                    checked = category in filters.selectedCategories,
                    onChecked = { checked ->
                        updateFilters(
                            filters.copy(
                                selectedCategories = if (checked) {
                                    filters.selectedCategories + category
                                } else {
                                    filters.selectedCategories - category
                                }
                            )
                        )
                    }
                )
            }
        }
    }

    @Composable
    private fun DrawerSectionTitle(title: String) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 5.dp)
        )
    }

    @Composable
    private fun DrawerCheckbox(
        label: String,
        detail: String? = null,
        checked: Boolean,
        onChecked: (Boolean) -> Unit
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onChecked)
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    private fun DrawerRadio(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Text(label, style = MaterialTheme.typography.bodyMedium)
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
        val persisted = scanStore.load()
        if (persisted.startedAt == null) {
            scanStore.save(persisted.copy(startedAt = Instant.now().toString()))
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
            startedWith = initial.queue.size + initial.processedIds.size,
            startedAt = initial.startedAt ?: Instant.now().toString()
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
            var record = parseRecordWithRetry(web, expectedId)
                ?: error("Nexus-Metadaten wurden nicht rechtzeitig geladen")
            if (settings.scanFileSizes && !record.url.isNullOrBlank()) {
                setStatus("Lese Dateigröße: ${record.name}")
                web.loadUrl(record.url + "?tab=files")
                delay(settings.delayMs.coerceAtLeast(1500L))
                val metrics = parseFileMetricsWithRetry(web)
                if (metrics != null) {
                    record = record.copy(
                        file_size_bytes = metrics.file_size_bytes,
                        main_files_count = metrics.main_files_count
                    )
                }
            }
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
        runCatching { evaluateJavascript(web, NexusPageParser.expandMetadataSections) }
        delay(350)
        repeat(10) { attempt ->
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
                    candidate.updated_at != null &&
                    attempt >= 2
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
            !record.updated_at.isNullOrBlank(),
            !record.author.isNullOrBlank(),
            record.endorsements != null,
            record.total_downloads != null
        ).count { it }

    private suspend fun parseFileMetricsWithRetry(web: WebView): NexusFileMetrics? {
        var best: NexusFileMetrics? = null
        repeat(8) {
            val candidate = runCatching {
                val raw = evaluateJavascript(web, NexusPageParser.parseFilesTab)
                json.decodeFromString<NexusFileMetrics>(decodeJsString(raw))
            }.getOrNull()
            if (candidate != null) {
                best = candidate
                if (candidate.file_size_bytes != null) return candidate
            }
            delay(500)
        }
        return best
    }

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

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun CatalogPane(
        mods: List<ModEntity>,
        filters: CatalogFilterState,
        updateFilters: (CatalogFilterState) -> Unit,
        openFilters: () -> Unit
    ) {
        var query by rememberSaveable { mutableStateOf("") }
        val collapsedCategories = remember { mutableStateMapOf<String, Boolean>() }
        val filtered = remember(mods, query, filters) {
            applyCatalogFilters(mods, query, filters)
        }
        val byCategory = remember(filtered) {
            filtered.groupBy {
                it.category?.takeIf(String::isNotBlank) ?: UNKNOWN_CATEGORY
            }
        }
        val categoryNames = remember(byCategory) {
            byCategory.keys.sortedWith(
                compareBy<String> { if (it == UNKNOWN_CATEGORY) 1 else 0 }
                    .thenBy { it.lowercase() }
            )
        }
        val newCount = mods.count { it.collectionState == "NEW" && it.inSelectedRange }
        val updatedCount = mods.count { it.collectionState == "UPDATED" && it.inSelectedRange }
        val adultCount = mods.count { it.adult }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Mod-Katalog",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${filtered.size} von ${mods.size} Mods",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = openFilters) {
                    Text(
                        if (filters.activeCount() == 0) "Filter"
                        else "Filter · ${filters.activeCount()}"
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatBadge("Gesamt", mods.size)
                StatBadge("Neu", newCount)
                StatBadge("Updates", updatedCount)
                StatBadge("NSFW", adultCount)
                StatBadge("Kategorien", mods.mapNotNull { it.category }.distinct().size)
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Mods, Autoren oder Kategorien suchen") },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        TextButton(onClick = { query = "" }) { Text("×") }
                    }
                }
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        filters.sort.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = {
                        updateFilters(CatalogFilterState())
                    }) { Text("Filter löschen") }
                }
            }

            if (filtered.isEmpty()) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            if (mods.isEmpty()) "Noch keine Mods gescannt"
                            else "Keine Mods passen zu diesen Filtern",
                            fontWeight = FontWeight.SemiBold
                        )
                        if (mods.isNotEmpty()) {
                            Text(
                                "Öffne das Menü und lockere Kategorie, Status, Größe oder NSFW-Filter.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    if (filters.groupByCategory) {
                        categoryNames.forEach { category ->
                            val categoryMods = byCategory[category].orEmpty()
                            val collapsed = collapsedCategories[category] == true
                            item(key = "category:$category") {
                                CategoryHeader(
                                    category = category,
                                    count = categoryMods.size,
                                    collapsed = collapsed,
                                    toggle = {
                                        collapsedCategories[category] = !collapsed
                                    }
                                )
                            }
                            if (!collapsed) {
                                items(categoryMods, key = { "mod:${it.modId}" }) { mod ->
                                    ModCard(mod)
                                }
                            }
                        }
                    } else {
                        items(filtered, key = { "mod:${it.modId}" }) { mod ->
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
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }

    @Composable
    private fun CategoryHeader(
        category: String,
        count: Int,
        collapsed: Boolean,
        toggle: () -> Unit
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(onClick = toggle),
            color = Color.Transparent
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    (if (collapsed) "›  " else "⌄  ") + category,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
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
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ModCard(mod: ModEntity) {
        var expanded by rememberSaveable(mod.modId) { mutableStateOf(false) }
        ElevatedCard(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
        ) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        mod.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (expanded) 5 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 10.dp)
                    )
                    Text(
                        mod.collectionState,
                        style = MaterialTheme.typography.labelMedium,
                        color = when (mod.collectionState) {
                            "NEW" -> MaterialTheme.colorScheme.primary
                            "UPDATED" -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Text(
                    "#${mod.modId} • v${mod.version ?: "?"}" +
                        (mod.author?.let { " • $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MetaBadge("Update ${displayDate(mod.updatedAt)}")
                    MetaBadge(formatBytes(mod.fileSizeBytes))
                    if (mod.adult) MetaBadge("NSFW", alert = true)
                    if (mod.hasSkseHint || mod.hasDllHint) MetaBadge("SKSE / DLL")
                }

                if (expanded) {
                    HorizontalDivider(Modifier.padding(vertical = 3.dp))
                    Text(
                        mod.category?.takeIf(String::isNotBlank) ?: UNKNOWN_CATEGORY,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    mod.summary?.takeIf(String::isNotBlank)?.let { summary ->
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        mod.endorsements?.let { MetaBadge("${formatCount(it)} Endorsements") }
                        mod.totalDownloads?.let { MetaBadge("${formatCount(it)} Downloads") }
                        mod.uniqueDownloads?.let { MetaBadge("${formatCount(it)} Unique DLs") }
                        if (mod.mainFilesCount > 0) MetaBadge("${mod.mainFilesCount} Hauptdateien")
                        if (mod.requirementsCount > 0) MetaBadge("${mod.requirementsCount} Requirements")
                        if (mod.requiredByCount > 0) MetaBadge("Von ${formatCount(mod.requiredByCount.toLong())} Mods genutzt")
                    }
                    Text(
                        "Veröffentlicht: ${displayDate(mod.publishedAt)} • Tippen zum Schließen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (mod.diagnostics.isNotBlank()) {
                        Text(
                            "Einige Metadaten fehlen – diesen Mod bitte neu scannen.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Text(
                        "Tippen für Details",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    private fun MetaBadge(label: String, alert: Boolean = false) {
        Surface(
            shape = RoundedCornerShape(7.dp),
            color = if (alert) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall
            )
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
            SectionCard("Zusätzliche Metadaten") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Dateigröße erfassen", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Öffnet pro Mod zusätzlich den Files-Tab. Das dauert länger, ermöglicht aber Größenfilter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = current.scanFileSizes,
                        onCheckedChange = { update(current.copy(scanFileSizes = it)) }
                    )
                }
            }
            SectionCard("Scan-Regeln") {
                Text("✓ Adult-Mods bleiben erhalten")
                Text("✓ Nexus-Kategorien werden übernommen")
                Text("✓ Autor, Statistik und Abhängigkeiten werden erfasst")
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
            onlyChanged = onlyChanged,
            rangeDays = settings.rangeDays,
            scanStartedAt = scanStore.load().startedAt
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

    private fun formatBytes(value: Long?): String {
        if (value == null) return "Größe unbekannt"
        val kib = 1024.0
        val mib = kib * 1024.0
        val gib = mib * 1024.0
        return when {
            value >= gib -> String.format(java.util.Locale.GERMAN, "%.1f GB", value / gib)
            value >= mib -> String.format(java.util.Locale.GERMAN, "%.1f MB", value / mib)
            value >= kib -> String.format(java.util.Locale.GERMAN, "%.0f KB", value / kib)
            else -> "$value B"
        }
    }

    private fun formatCount(value: Long): String = when {
        value >= 1_000_000_000 -> String.format(java.util.Locale.GERMAN, "%.1f Mrd.", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format(java.util.Locale.GERMAN, "%.1f Mio.", value / 1_000_000.0)
        value >= 1_000 -> String.format(java.util.Locale.GERMAN, "%.1f Tsd.", value / 1_000.0)
        else -> value.toString()
    }

    private fun decodeJsString(raw: String): String = runCatching {
        json.decodeFromString<String>(raw)
    }.getOrElse {
        raw.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
