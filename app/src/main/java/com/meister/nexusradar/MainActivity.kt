package com.meister.nexusradar

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import com.meister.nexusradar.data.AppDatabase
import com.meister.nexusradar.data.ModEntity
import com.meister.nexusradar.domain.*
import com.meister.nexusradar.scan.*
import com.meister.nexusradar.settings.*
import com.meister.nexusradar.transfer.AppBackupPayload
import com.meister.nexusradar.transfer.TransferArchives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val RadarColors = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF241006),
    secondary = Color(0xFFC6A8FF),
    background = Color(0xFF101116),
    surface = Color(0xFF191A20),
    surfaceVariant = Color(0xFF24252D),
    outline = Color(0xFF777985)
)

private data class ShareableDocument(
    val uri: Uri,
    val name: String,
    val mimeType: String = "application/zip"
)

private data class StoredTransfer(
    val document: ShareableDocument,
    val message: String
)

private data class BackupRestoreResult(
    val mods: Int,
    val reports: Int,
    val queued: Int
)

class MainActivity : ComponentActivity() {
    private lateinit var repo: Repository
    private lateinit var scanStore: ScanStateStore
    private lateinit var historyStore: ScanHistoryStore
    private lateinit var settingsStore: ScanSettingsStore
    private lateinit var filterStore: CatalogFilterStore
    private lateinit var exportStore: ExportDestinationStore
    private val requestedScreen = mutableIntStateOf(0)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repository(AppDatabase.get(this).modDao())
        scanStore = ScanStateStore(this)
        historyStore = ScanHistoryStore(this)
        settingsStore = ScanSettingsStore(this)
        filterStore = CatalogFilterStore(this)
        exportStore = ExportDestinationStore(this)
        requestedScreen.intValue = intent.getIntExtra(EXTRA_OPEN_SCREEN, 0).coerceIn(0, 4)
        val interruptedState = scanStore.load()
        val staleScan = (interruptedState.running || interruptedState.collecting) &&
            !ScanForegroundService.isActive
        if (staleScan) {
            scanStore.save(
                interruptedState.copy(
                    running = false,
                    collecting = false,
                    statusMessage = "Hintergrundscan unterbrochen • Fortschritt kann fortgesetzt werden"
                )
            )
        }
        setContent {
            MaterialTheme(colorScheme = RadarColors) {
                RadarApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedScreen.intValue = intent.getIntExtra(EXTRA_OPEN_SCREEN, 0).coerceIn(0, 4)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RadarApp() {
        var screen by rememberSaveable { mutableIntStateOf(requestedScreen.intValue) }
        var scanStatus by remember { mutableStateOf("Bereit") }
        var exportStatus by remember { mutableStateOf("Noch kein Export ausgeführt") }
        var transferBusy by remember { mutableStateOf(false) }
        var lastShareable by remember { mutableStateOf<ShareableDocument?>(null) }
        var pendingBackupRestore by remember { mutableStateOf<Uri?>(null) }
        var address by remember {
            mutableStateOf("https://www.nexusmods.com/games/skyrimspecialedition/mods")
        }
        val scanStateFlow = remember { scanStore.observe() }
        val scanState by scanStateFlow.collectAsState(initial = scanStore.load())
        val scanHistoryFlow = remember { historyStore.observe() }
        val scanHistory by scanHistoryFlow.collectAsState(initial = historyStore.load())
        var settings by remember { mutableStateOf(settingsStore.load()) }
        var exportUri by remember { mutableStateOf(exportStore.load()) }
        var catalogFilters by remember { mutableStateOf(filterStore.load()) }
        val mods by repo.observeMods().collectAsState(initial = emptyList())
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val updateCatalogFilters: (CatalogFilterState) -> Unit = { updated ->
            catalogFilters = updated
            filterStore.save(updated)
        }

        LaunchedEffect(requestedScreen.intValue) {
            screen = requestedScreen.intValue
        }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                scanStatus = "Scan läuft weiter • Fertig-Popup ist ohne Benachrichtigungsrecht deaktiviert"
            }
        }

        val requestScanNotifications = {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && !transferBusy) {
                lifecycleScope.launch {
                    transferBusy = true
                    try {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                                    repo.importJson(it.readText())
                                } ?: error("Datei konnte nicht geöffnet werden")
                            }
                        }.onSuccess {
                            exportStatus = "Importiert: ${it.accepted} • ausgeschlossen: ${it.rejected}"
                        }.onFailure {
                            exportStatus = "Importfehler: ${it.message ?: "unbekannt"}"
                        }
                    } finally {
                        transferBusy = false
                    }
                }
            }
        }

        val backupImporter = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null && !transferBusy) pendingBackupRestore = uri
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

        if (pendingBackupRestore != null) {
            AlertDialog(
                onDismissRequest = { pendingBackupRestore = null },
                title = { Text("Vollbackup wiederherstellen?") },
                text = {
                    Text(
                        "Das Backup wird zuerst vollständig geprüft. Danach ersetzt es Katalog, " +
                            "Abhängigkeiten, Tags, Einstellungen, Filter, Queue und Scanberichte."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val restoreUri = pendingBackupRestore
                        pendingBackupRestore = null
                        if (restoreUri != null) {
                            lifecycleScope.launch {
                                transferBusy = true
                                exportStatus = "Backup wird geprüft …"
                                try {
                                    runCatching { restoreBackup(restoreUri) }
                                        .onSuccess { result ->
                                            settings = settingsStore.load()
                                            catalogFilters = filterStore.load()
                                            exportStatus = "Backup wiederhergestellt: ${result.mods} Mods • " +
                                                "${result.reports} Berichte • ${result.queued} Queue-Mods"
                                        }
                                        .onFailure { error ->
                                            exportStatus = "Backupfehler: ${error.message ?: "Datei ist ungültig"}"
                                        }
                                } finally {
                                    transferBusy = false
                                }
                            }
                        }
                    }) { Text("Prüfen und ersetzen") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingBackupRestore = null }) { Text("Abbrechen") }
                }
            )
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
                        updateFilters = updateCatalogFilters,
                        resetFilters = { updateCatalogFilters(CatalogFilterState()) }
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
                                    listOf("Scanner", "Katalog", "Berichte", "Setup", "Export")[screen],
                                    maxLines = 1
                                )
                                Text("Nexus Skyrim Radar • v0.15", style = MaterialTheme.typography.labelSmall)
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
                                scanStore.save(state)
                            },
                            settings = settings,
                            requestScanNotifications = requestScanNotifications
                        )
                        1 -> CatalogPane(
                            mods = mods,
                            filters = catalogFilters,
                            updateFilters = updateCatalogFilters,
                            openFilters = { scope.launch { drawerState.open() } }
                        )
                        2 -> ReportsPane(
                            history = scanHistory,
                            busy = scanState.running || scanState.collecting,
                            retryFailed = { failedItems ->
                                val queue = QueueOrdering.newestFirst(
                                    failedItems.distinctBy { it.modId }.map { it.toQueueItem() }
                                )
                                if (queue.isNotEmpty() && !scanState.running && !scanState.collecting) {
                                    requestScanNotifications()
                                    scanStore.save(
                                        PersistedScanState(
                                            queue = queue,
                                            delayMs = settings.delayMs,
                                            startedWith = queue.size,
                                            startedAt = Instant.now().toString(),
                                            queuedNewCount = queue.count { it.reason == "NEW" },
                                            queuedUpdateCount = queue.count { it.reason == "UPDATED" },
                                            statusMessage = "Fehlerprüfung vorbereitet • ${queue.size} Mods"
                                        )
                                    )
                                    runCatching { ScanForegroundService.resume(this@MainActivity) }
                                        .onSuccess {
                                            scanStatus = "Fehlgeschlagene Mods werden erneut geprüft"
                                        }
                                        .onFailure { error ->
                                            scanStatus = "Neustart fehlgeschlagen: ${error.message ?: "Android hat den Dienst blockiert"}"
                                        }
                                }
                            }
                        )
                        3 -> SettingsPane(settings) {
                            settings = it.normalized()
                            settingsStore.save(settings)
                        }
                        else -> ExportPane(
                            mods = mods,
                            status = exportStatus,
                            settings = settings,
                            transferBusy = transferBusy,
                            scannerBusy = scanState.running || scanState.collecting,
                            lastShareName = lastShareable?.name,
                            updateSettings = {
                                settings = it.normalized()
                                settingsStore.save(settings)
                            },
                            folderName = exportFolderName,
                            chooseFolder = { exportDirectoryPicker.launch(exportUri) },
                            importClick = {
                                importer.launch(arrayOf("application/json", "text/plain"))
                            },
                            restoreBackupClick = {
                                backupImporter.launch(arrayOf("application/zip", "application/octet-stream"))
                            },
                            shareClick = {
                                lastShareable?.let(::shareDocument)
                            },
                            zipExportClick = {
                                val target = exportUri
                                if (target == null) {
                                    exportStatus = "Bitte zuerst einen Exportordner wählen"
                                } else {
                                    lifecycleScope.launch {
                                        transferBusy = true
                                        exportStatus = "ZIP-Export wird erstellt und geprüft …"
                                        try {
                                            runCatching { exportVerifiedZip(target, settings) }
                                                .onSuccess { result ->
                                                    lastShareable = result.document
                                                    exportStatus = result.message
                                                }
                                                .onFailure { error ->
                                                    exportStatus = "ZIP-Exportfehler: ${error.message ?: "Schreibzugriff fehlgeschlagen"}"
                                                }
                                        } finally {
                                            transferBusy = false
                                        }
                                    }
                                }
                            },
                            jsonExportClick = {
                                val target = exportUri
                                if (target == null) {
                                    exportStatus = "Bitte zuerst einen Exportordner wählen"
                                } else {
                                    lifecycleScope.launch {
                                        transferBusy = true
                                        exportStatus = "Einzelne JSON-Dateien werden geschrieben und geprüft …"
                                        try {
                                            runCatching { exportJsonFiles(target, settings) }
                                                .onSuccess { exportStatus = it }
                                                .onFailure { error ->
                                                    exportStatus = "JSON-Exportfehler: ${error.message ?: "Schreibzugriff fehlgeschlagen"}"
                                                }
                                        } finally {
                                            transferBusy = false
                                        }
                                    }
                                }
                            },
                            createBackupClick = {
                                val target = exportUri
                                if (target == null) {
                                    exportStatus = "Bitte zuerst einen Zielordner wählen"
                                } else {
                                    lifecycleScope.launch {
                                        transferBusy = true
                                        exportStatus = "Vollbackup wird erstellt und geprüft …"
                                        try {
                                            runCatching { createVerifiedBackup(target) }
                                                .onSuccess { result ->
                                                    lastShareable = result.document
                                                    exportStatus = result.message
                                                }
                                                .onFailure { error ->
                                                    exportStatus = "Backupfehler: ${error.message ?: "Backup konnte nicht erstellt werden"}"
                                                }
                                        } finally {
                                            transferBusy = false
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
        val screens = listOf("Scanner", "Katalog", "Berichte", "Setup", "Export")
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
                    "v0.15 • lokaler Mod-Katalog",
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
        settings: ScanSettings,
        requestScanNotifications: () -> Unit
    ) {
        var localWeb by remember { mutableStateOf<WebView?>(null) }
        var confirmReset by remember { mutableStateOf(false) }
        val busy = scanState.running || scanState.collecting
        val hasPausedWork = scanState.collectionPending || scanState.queue.isNotEmpty()
        val denominator = scanState.totalForRun.coerceAtLeast(1)
        val progress = (scanState.processedCount.toFloat() / denominator.toFloat())
            .coerceIn(0f, 1f)

        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text("Queue zurücksetzen?") },
                text = {
                    Text("Queue, Fortschritt und Zähler werden geleert. Der Katalog bleibt als Scan-Gedächtnis erhalten: Beim nächsten Sammeln werden bekannte Mods nur bei einem erkannten Update erneut eingeplant.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmReset = false
                        scanStore.clear()
                        setScanState(PersistedScanState())
                        setStatus("Queue zurückgesetzt – Scan-Gedächtnis bleibt erhalten")
                    }) { Text("Queue leeren") }
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

            Button(
                onClick = {
                    requestScanNotifications()
                    CookieManager.getInstance().flush()
                    runCatching {
                        if (hasPausedWork) {
                            ScanForegroundService.resume(this@MainActivity)
                        } else {
                            if (isModDetailsPage(address)) {
                                error("Bitte die Skyrim-Modliste statt einer einzelnen Modseite öffnen")
                            }
                            ScanForegroundService.startFull(this@MainActivity, address)
                        }
                    }.onSuccess {
                        setStatus(
                            if (hasPausedWork) {
                                "Scan wird fortgesetzt – du kannst wieder andere Apps öffnen"
                            } else {
                                "Komplettscan gestartet – Listensammlung und Modscan laufen im Hintergrund"
                            }
                        )
                    }.onFailure { error ->
                        setStatus("Startfehler: ${error.message ?: "Android hat den Dienst blockiert"}")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) {
                Text(
                    when {
                        busy -> "Komplettscan läuft …"
                        hasPausedWork -> "Scan fortsetzen"
                        else -> "Komplettscan im Hintergrund starten"
                    },
                    maxLines = 1
                )
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
                    enabled = !busy
                ) { Text("Einzelmod", maxLines = 1) }
                OutlinedButton(
                    onClick = {
                        if (busy) {
                            runCatching { ScanForegroundService.pause(this@MainActivity) }
                                .onSuccess { setStatus("Scan wird sicher pausiert …") }
                                .onFailure { setStatus("Pausieren fehlgeschlagen: ${it.message}") }
                        } else {
                            confirmReset = true
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (busy) "Pausieren" else "Queue leeren", maxLines = 1)
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
                    if (scanState.retryAttemptCount > 0 || scanState.excludedCount > 0) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Wiederholungen ${scanState.retryAttemptCount}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Ausgeschlossen ${scanState.excludedCount}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Neu ${scanState.queuedNewCount}", style = MaterialTheme.typography.bodySmall)
                        Text("Updates ${scanState.queuedUpdateCount}", style = MaterialTheme.typography.bodySmall)
                        Text("Übersprungen ${scanState.skippedUnchangedCount}", style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Ein-Klick-Smart-Scan • ${scanState.listingBatches} Listen-Schritte • ${settings.rangeDays} Tage\n" +
                            scanState.statusMessage.ifBlank { status },
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

    private suspend fun scanCurrentPage(
        web: WebView,
        settings: ScanSettings,
        setStatus: (String) -> Unit,
        expectedId: Long?
    ): Boolean = NexusModScanner(repo, json).scan(
        web = web,
        settings = settings,
        setStatus = setStatus,
        expectedId = expectedId
    ).successful

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

    @Composable
    private fun ReportsPane(
        history: List<ScanRunSummary>,
        busy: Boolean,
        retryFailed: (List<FailedScanItem>) -> Unit
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Scanberichte",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Ergebnisse und konkrete Fehler der letzten 20 abgeschlossenen Läufe",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (history.isEmpty()) {
                item {
                    SectionCard("Noch kein Bericht") {
                        Text("Nach dem nächsten Komplettscan erscheint hier eine Zusammenfassung.")
                        Text(
                            "Die Fertigmeldung öffnet anschließend direkt diese Seite.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(history, key = { "report:${it.id}" }) { report ->
                    ScanReportCard(
                        report = report,
                        initiallyExpanded = report.id == history.first().id,
                        busy = busy,
                        retryFailed = retryFailed
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ScanReportCard(
        report: ScanRunSummary,
        initiallyExpanded: Boolean,
        busy: Boolean,
        retryFailed: (List<FailedScanItem>) -> Unit
    ) {
        var expanded by rememberSaveable(report.id) { mutableStateOf(initiallyExpanded) }
        val successful = (report.processedCount - report.failedCount - report.excludedCount)
            .coerceAtLeast(0)

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            displayDateTime(report.completedAt),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${formatDuration(report.durationSeconds)} • ${report.processedCount} abgearbeitet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        if (report.failedCount == 0) "ERFOLGREICH" else "${report.failedCount} FEHLER",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (report.failedCount == 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatBadge("Entdeckt", report.discoveredCount)
                    StatBadge("Neu", report.queuedNewCount)
                    StatBadge("Updates", report.queuedUpdateCount)
                    StatBadge("Ohne Fehler", successful)
                }

                if (expanded) {
                    HorizontalDivider()
                    Text(
                        "Gestartet: ${displayDateTime(report.startedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MetaBadge("Unverändert ${report.skippedUnchangedCount}")
                        MetaBadge("Wiederholungen ${report.retryAttemptCount}")
                        MetaBadge("Ausgeschlossen ${report.excludedCount}")
                    }

                    if (report.failedItems.isEmpty()) {
                        Text(
                            "Keine fehlgeschlagenen Mods in diesem Lauf.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "Fehlerdetails",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                        report.failedItems.take(MAX_VISIBLE_REPORT_ERRORS).forEach { failed ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(11.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        failed.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "#${failed.modId} • ${failed.attempts} Versuche • ${failed.reason}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Text(
                                        failed.lastError.ifBlank { "Unbekannter Scanfehler" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        if (report.failedCount > MAX_VISIBLE_REPORT_ERRORS) {
                            Text(
                                "+ ${report.failedCount - MAX_VISIBLE_REPORT_ERRORS} weitere Fehler",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { retryFailed(report.failedItems) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy
                        ) {
                            Text(
                                if (busy) "Scanner ist beschäftigt"
                                else "${report.failedCount} fehlgeschlagene Mods erneut prüfen"
                            )
                        }
                    }
                    Text(
                        "Tippen zum Einklappen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    label = { Text("Listen-Schritte: 1–500") }
                )
                Text(
                    "Ein Schritt ist eine Nexus-Seite oder ein „Mehr laden“-Block. Mit 5 Schritten werden je nach Ansicht deutlich mehr als 20 Mods gefunden. 3000 ms sind ein sicherer Startwert.",
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
            SectionCard("Hintergrundscan") {
                Text("✓ Sammelt Listen und scannt Mods vollständig im Hintergrund")
                Text("✓ Neueste Nexus-Aktualisierungen werden zuerst verarbeitet")
                Text("✓ Über die Benachrichtigung kann der Scan sicher pausiert werden")
                Text("✓ Nach Abschluss erscheint eine gut sichtbare Fertigmeldung")
                Text(
                    "Beim ersten Start bitte Benachrichtigungen erlauben. Ohne diese Berechtigung läuft der Scan trotzdem, Android zeigt aber kein Fertig-Popup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SectionCard("Scan-Regeln") {
                Text("✓ Bereits bekannte Mods nur bei erkanntem Update erneut scannen")
                Text("✓ Temporäre Fehler werden automatisch zweimal wiederholt")
                Text("✓ Queue und Fortschritt bleiben nach App-Neustart erhalten")
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
        transferBusy: Boolean,
        scannerBusy: Boolean,
        lastShareName: String?,
        updateSettings: (ScanSettings) -> Unit,
        folderName: String,
        chooseFolder: () -> Unit,
        importClick: () -> Unit,
        restoreBackupClick: () -> Unit,
        shareClick: () -> Unit,
        zipExportClick: () -> Unit,
        jsonExportClick: () -> Unit,
        createBackupClick: () -> Unit
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
        val hasFolder = folderName != "Kein Exportordner gewählt"
        val locked = transferBusy || scannerBusy

        Column(
            Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Export & Backup",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (scannerBusy) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Der Scanner läuft. Export und Wiederherstellung werden freigegeben, sobald der Katalog wieder in einem festen Zustand ist.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
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
            SectionCard("4. Geprüfter Export") {
                Text(
                    "Das ZIP enthält die JSON-Chunks und ein Manifest mit Dateigrößen und SHA-256-Prüfsummen. Nach dem Schreiben wird alles erneut aus dem Zielordner gelesen und kontrolliert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = zipExportClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = exportableCount > 0 && hasFolder && !locked
                ) {
                    Text("Geprüftes ZIP mit $exportableCount Mods erstellen")
                }
                OutlinedButton(
                    onClick = jsonExportClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = exportableCount > 0 && hasFolder && !locked
                ) {
                    Text("JSON-Chunks einzeln speichern")
                }
                if (lastShareName != null) {
                    OutlinedButton(
                        onClick = shareClick,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !transferBusy
                    ) {
                        Text("Letzte ZIP-Datei teilen")
                    }
                    Text(
                        lastShareName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            SectionCard("5. Vollständiges App-Datenbackup") {
                Text(
                    "Sichert Katalog, Abhängigkeiten, Tags, Einstellungen, Katalogfilter, Scan-Queue und Berichte in einer einzigen geprüften ZIP-Datei.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = createBackupClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = hasFolder && !locked
                ) { Text("Vollbackup erstellen") }
                OutlinedButton(
                    onClick = restoreBackupClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !locked
                ) { Text("Vollbackup wiederherstellen") }
                Text(
                    "Nexus-Anmeldung und Android-Ordnerrechte werden bewusst nicht gesichert. Nach einer Neuinstallation bitte erneut anmelden und den Zielordner auswählen.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SectionCard("6. Einzelne JSON-Datei importieren") {
                OutlinedButton(
                    onClick = importClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !locked
                ) { Text("JSON-Chunk importieren") }
            }
            if (transferBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
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

    private suspend fun exportVerifiedZip(tree: Uri, settings: ScanSettings): StoredTransfer =
        withContext(Dispatchers.IO) {
            val chunks = exportChunks(settings)
            val directory = writableDirectory(tree)
            val timestamp = transferTimestamp()
            val modeName = settings.exportMode.name.lowercase()
            val requestedName = "skyrimse_${modeName}_${settings.rangeDays}d_${timestamp}.zip"
            val file = directory.createFile("application/zip", requestedName)
                ?: error("ZIP-Datei konnte nicht angelegt werden")
            try {
                val output = contentResolver.openOutputStream(file.uri, "w")
                    ?: error("ZIP-Datei konnte nicht geöffnet werden")
                val written = TransferArchives.writeExport(
                    output = output,
                    chunks = chunks,
                    appVersion = APP_VERSION,
                    exportMode = settings.exportMode.name,
                    rangeDays = settings.rangeDays
                )
                val input = contentResolver.openInputStream(file.uri)
                    ?: error("ZIP-Datei konnte zur Prüfung nicht gelesen werden")
                val verified = TransferArchives.verifyExport(input)
                require(written == verified) { "Manifest stimmt nach dem Schreiben nicht überein" }
                val actualName = file.getName() ?: requestedName
                StoredTransfer(
                    document = ShareableDocument(file.uri, actualName),
                    message = "✓ ZIP geprüft: ${verified.total_mods} Mods • " +
                        "${verified.chunk_count} Chunks • $actualName"
                )
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    private suspend fun exportJsonFiles(tree: Uri, settings: ScanSettings): String =
        withContext(Dispatchers.IO) {
            val chunks = exportChunks(settings)
            val directory = writableDirectory(tree)
            val timestamp = transferTimestamp()
            val modeName = settings.exportMode.name.lowercase()
            val created = mutableListOf<DocumentFile>()
            try {
                chunks.forEachIndexed { index, contents ->
                    val name = "skyrimse_${modeName}_${settings.rangeDays}d_${timestamp}_chunk_" +
                        (index + 1).toString().padStart(4, '0') + ".json"
                    val file = directory.createFile("application/json", name)
                        ?: error("Datei $name konnte nicht angelegt werden")
                    created += file
                    val stream = contentResolver.openOutputStream(file.uri, "w")
                        ?: error("Datei $name konnte nicht geöffnet werden")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(contents) }
                    val readBack = contentResolver.openInputStream(file.uri)?.bufferedReader()?.use {
                        it.readText()
                    } ?: error("Datei $name konnte nicht zurückgelesen werden")
                    require(readBack == contents) { "Rückleseprüfung fehlgeschlagen: $name" }
                    val parsed = json.decodeFromString<ImportChunk>(readBack)
                    require(parsed.chunk == index + 1 && parsed.schema_version == 8) {
                        "JSON-Prüfung fehlgeschlagen: $name"
                    }
                }
                "✓ ${created.size} JSON-Dateien geschrieben und zurückgelesen • " +
                    (directory.getName() ?: "Zielordner")
            } catch (error: Exception) {
                created.forEach { it.delete() }
                throw error
            }
        }

    private suspend fun createVerifiedBackup(tree: Uri): StoredTransfer =
        withContext(Dispatchers.IO) {
            val currentScan = scanStore.load()
            require(!currentScan.running && !currentScan.collecting) {
                "Bitte den laufenden Scan zuerst pausieren"
            }
            val createdAt = Instant.now().toString()
            val backup = AppBackupPayload(
                app_version = APP_VERSION,
                created_at = createdAt,
                settings = settingsStore.load(),
                catalog_filters = filterStore.load(),
                scan_state = currentScan.copy(running = false, collecting = false),
                scan_history = historyStore.load(),
                catalog = repo.catalogSnapshot()
            )
            val directory = writableDirectory(tree)
            val requestedName = "NexusSkyrimRadar_backup_${transferTimestamp()}.zip"
            val file = directory.createFile("application/zip", requestedName)
                ?: error("Backupdatei konnte nicht angelegt werden")
            try {
                val output = contentResolver.openOutputStream(file.uri, "w")
                    ?: error("Backupdatei konnte nicht geöffnet werden")
                val written = TransferArchives.writeBackup(output, backup)
                val input = contentResolver.openInputStream(file.uri)
                    ?: error("Backupdatei konnte zur Prüfung nicht gelesen werden")
                val verified = TransferArchives.readBackup(input)
                require(verified.catalog.mods.size == written.mod_count) {
                    "Backup stimmt nach dem Schreiben nicht überein"
                }
                val actualName = file.getName() ?: requestedName
                StoredTransfer(
                    document = ShareableDocument(file.uri, actualName),
                    message = "✓ Vollbackup geprüft: ${written.mod_count} Mods • " +
                        "${written.report_count} Berichte • $actualName"
                )
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    private suspend fun restoreBackup(uri: Uri): BackupRestoreResult = withContext(Dispatchers.IO) {
        val currentScan = scanStore.load()
        require(!currentScan.running && !currentScan.collecting) {
            "Ein laufender Scan kann nicht überschrieben werden"
        }
        val input = contentResolver.openInputStream(uri)
            ?: error("Backupdatei konnte nicht geöffnet werden")
        val backup = TransferArchives.readBackup(input)
        repo.restoreCatalog(backup.catalog)
        settingsStore.save(backup.settings)
        filterStore.save(backup.catalog_filters)
        val restoredState = backup.scan_state.copy(
            running = false,
            collecting = false,
            statusMessage = "Backup wiederhergestellt • ${backup.scan_state.queue.size} Queue-Mods bereit"
        )
        scanStore.save(restoredState)
        historyStore.replaceAll(backup.scan_history)
        BackupRestoreResult(
            mods = backup.catalog.mods.size,
            reports = backup.scan_history.size,
            queued = restoredState.queue.size
        )
    }

    private suspend fun exportChunks(settings: ScanSettings): List<String> {
        val chunks = repo.exportChunks(
            chunkSize = settings.chunkSize,
            onlyInRange = settings.exportMode != ExportMode.ALL,
            onlyChanged = settings.exportMode == ExportMode.CHANGED,
            rangeDays = settings.rangeDays,
            scanStartedAt = scanStore.load().startedAt
        )
        require(chunks.isNotEmpty()) {
            "Für den gewählten Exportumfang sind keine Mods vorhanden"
        }
        return chunks
    }

    private fun writableDirectory(tree: Uri): DocumentFile {
        val directory = DocumentFile.fromTreeUri(this, tree)
            ?: error("Der Zielordner ist nicht mehr verfügbar")
        require(directory.exists() && directory.isDirectory) {
            "Der Zielordner existiert nicht mehr"
        }
        require(directory.canWrite()) { "Für den Zielordner fehlt die Schreibberechtigung" }
        return directory
    }

    private fun transferTimestamp(): String = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())

    private fun shareDocument(document: ShareableDocument) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = document.mimeType
            putExtra(Intent.EXTRA_STREAM, document.uri)
            clipData = ClipData.newRawUri(document.name, document.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sendIntent, "${document.name} teilen"))
    }

    private fun displayDate(value: String?): String =
        value?.substringBefore('T')?.takeIf(String::isNotBlank) ?: "unbekannt"

    private fun displayDateTime(value: String?): String {
        if (value.isNullOrBlank()) return "unbekannt"
        return runCatching {
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .format(Instant.parse(value).atZone(ZoneId.systemDefault()))
        }.getOrElse {
            value.replace('T', ' ').take(16)
        }
    }

    private fun formatDuration(seconds: Long?): String {
        if (seconds == null) return "Dauer unbekannt"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60
        return when {
            hours > 0 -> "${hours} Std. ${minutes} Min."
            minutes > 0 -> "${minutes} Min. ${remainingSeconds} Sek."
            else -> "${remainingSeconds} Sek."
        }
    }

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

    companion object {
        const val EXTRA_OPEN_SCREEN = "open_screen"
        private const val APP_VERSION = "0.15.0"
        private const val MAX_VISIBLE_REPORT_ERRORS = 10
    }

}
