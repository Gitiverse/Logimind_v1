package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.data.service.SubscriptionManager
import com.example.data.service.SubscriptionTier
import com.example.data.service.SyncPlaceholderService
import com.example.data.service.SyncState
import com.example.ui.theme.BrandTheme
import com.example.ui.theme.ThemeMode
import com.example.ui.viewmodel.FormBuilderViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: FormBuilderViewModel,
    onNavigateToDesigner: (FormTemplate) -> Unit,
    onNavigateToSolve: (FormTemplate) -> Unit,
    modifier: Modifier = Modifier
) {
    val templates by viewModel.templates.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val patients by viewModel.patients.collectAsState()
    val customIntakeFields by viewModel.customIntakeFields.collectAsState()
    val selectedEntriesForExport by viewModel.selectedEntriesForExport.collectAsState()
    val allFields by viewModel.allFields.collectAsState()

    val fieldLabelMap = remember(allFields) {
        allFields.associate { it.uid to it.label }
    }

    var logToDelete by remember { mutableStateOf<FormEntry?>(null) }
    var templateToDelete by remember { mutableStateOf<Pair<FormTemplate, Int>?>(null) }
    var patientToDelete by remember { mutableStateOf<Patient?>(null) }
    var patientToEdit by remember { mutableStateOf<Patient?>(null) }
    var showLogSearch by remember { mutableStateOf(false) }
    val tipContext = androidx.compose.ui.platform.LocalContext.current
    var tileTipDismissed by remember {
        mutableStateOf(tipContext.getSharedPreferences("logimind_prefs", android.content.Context.MODE_PRIVATE).getBoolean("tile_tip_dismissed", false))
    }
    var logSearchQuery by remember { mutableStateOf("") }
    // Searchable text per entry: title, patient, MRN, template and every answer value.
    val entrySearchText = remember(entries, patients, fieldLabelMap) {
        val mrnByPatient = patients.associate { it.uid to it.medicalId }
        entries.associate { e ->
            val answerText = try {
                val o = JSONObject(e.answersJson)
                o.keys().asSequence().joinToString(" ") { k -> o.optString(k) }
            } catch (ex: Exception) {
                ""
            }
            e.uid to listOf(e.title, e.patientName ?: "", mrnByPatient[e.patientId] ?: "", e.templateName ?: "", answerText)
                .joinToString(" ")
                .lowercase()
        }
    }
    var showSelectLogsForAiDialog by remember { mutableStateOf(false) }
    var selectedLogIdsForAiSummary by remember { mutableStateOf(setOf<String>()) }
    var selectedEntriesForAiSummary by remember { mutableStateOf<List<FormEntry>>(emptyList()) }
    var recentLogsStartDate by remember { mutableStateOf<Long?>(null) }
    var recentLogsEndDate by remember { mutableStateOf<Long?>(null) }

    // --- State Management ---
    val subscriptionTier by SubscriptionManager.currentTier.collectAsState()
    val scope = rememberCoroutineScope()
    val syncService = remember { SyncPlaceholderService() }
    val syncState by syncService.syncState.collectAsState()

    // Screen views: 0 = Main Dashboard, 1 = Patient Registry, 2 = Blueprints Manager
    var selectedTabId by remember { mutableStateOf(0) }

    // Dialog state controllers
    var showCreateTemplateDialog by remember { mutableStateOf(false) }
    var showCreatePatientDialog by remember { mutableStateOf(false) }
    var showCustomizeFieldsDialog by remember { mutableStateOf(false) }
    var showSyncHubDialog by remember { mutableStateOf(false) }
    var showNewEntryWizard by remember { mutableStateOf(false) }
    var showPremiumUpsellDialog by remember { mutableStateOf(false) }
    var showGeminiApiSettingsDialog by remember { mutableStateOf(false) }

    // My Procedures Dashboard states
    var showProceduresDashboard by remember { mutableStateOf(false) }
    var selectedDashboardProcedureName by remember { mutableStateOf<String?>(null) }

    // Dynamic states
    var selectedEntryForView by remember { mutableStateOf<FormEntry?>(null) }
    var selectedTemplateForOptions by remember { mutableStateOf<FormTemplate?>(null) }
    var showTemplatePreviewDialog by remember { mutableStateOf<FormTemplate?>(null) }
    var patientFilterQuery by remember { mutableStateOf("") }
    var viewAllEntries by remember { mutableStateOf(false) }

    // Last clicked completed log driving the Clinical AI Patients Synthesizer
    var selectedEntryForAiSummary by remember { mutableStateOf<FormEntry?>(null) }

    var isSelectionMode by remember { mutableStateOf(false) }
    var syncHubTab by remember { mutableStateOf(0) }
    var restoreMessage by remember { mutableStateOf<String?>(null) }
    var showTextRestoreDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    // Short "Deleted — Undo" message after deletes.
    val showUndo: (String, () -> Unit) -> Unit = { message, undo ->
        scope.launch {
            val result = snackbarHostState.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }
    val showBackupReminder by viewModel.showBackupReminder.collectAsState()
    val pinnedTemplates by viewModel.pinnedTemplates.collectAsState()
    val fieldOrder by viewModel.fieldOrder.collectAsState()
    LaunchedEffect(showBackupReminder) {
        if (showBackupReminder) {
            val result = snackbarHostState.showSnackbar(
                "No backup exported in the last 7 days. Save one off this phone.",
                actionLabel = "Back up",
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                syncHubTab = 2
                showSyncHubDialog = true
            } else {
                viewModel.snoozeBackupReminder()
            }
        }
    }

    // AI Panel interactive state controllers
    var selectedAiStyle by remember { mutableStateOf("short") }
    var selectedAiProvider by remember { mutableStateOf("local_offline") }
    var aiGeneratedSummaryText by remember { mutableStateOf("") }
    var aiIsSynthesizing by remember { mutableStateOf(false) }
    var showCloudAiConsentFor by remember { mutableStateOf<String?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current

    var showRenameTemplateDialogFor by remember { mutableStateOf<FormTemplate?>(null) }

    var tileOrder by remember {
        mutableStateOf(
            run {
                val prefs = context.getSharedPreferences("logimind_prefs", android.content.Context.MODE_PRIVATE)
                val savedStr = prefs.getString("dashboard_tile_order", "patients,new_entry,templates,sync,my_procedures")
                val allTiles = listOf("patients", "new_entry", "templates", "sync", "my_procedures")
                val saved = savedStr?.split(",")?.filter { it in allTiles }?.distinct() ?: emptyList()
                saved + allTiles.filter { it !in saved }
            }
        )
    }

    val saveTileOrder = { newOrder: List<String> ->
        tileOrder = newOrder
        val prefs = context.getSharedPreferences("logimind_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("dashboard_tile_order", newOrder.joinToString(",")).apply()
    }

    var activeDraggedId by remember { mutableStateOf<String?>(null) }
    var activeDragOffset by remember { mutableStateOf(Offset.Zero) }

    val tileCenters = remember { mutableStateMapOf<String, Offset>() }

    val isAnyOverlayOpen = showProceduresDashboard ||
            selectedEntryForView != null ||
            selectedTemplateForOptions != null ||
            showTemplatePreviewDialog != null ||
            selectedEntryForAiSummary != null ||
            showCreateTemplateDialog ||
            showCreatePatientDialog ||
            showCustomizeFieldsDialog ||
            showSyncHubDialog ||
            showNewEntryWizard ||
            showPremiumUpsellDialog ||
            showGeminiApiSettingsDialog ||
            selectedTabId != 0

    androidx.activity.compose.BackHandler(enabled = isAnyOverlayOpen) {
        if (selectedDashboardProcedureName != null) {
            selectedDashboardProcedureName = null
        } else if (showProceduresDashboard) {
            showProceduresDashboard = false
        } else if (selectedEntryForView != null) {
            selectedEntryForView = null
        } else if (selectedTemplateForOptions != null) {
            selectedTemplateForOptions = null
        } else if (showTemplatePreviewDialog != null) {
            showTemplatePreviewDialog = null
        } else if (selectedEntryForAiSummary != null) {
            selectedEntryForAiSummary = null
        } else if (showCreateTemplateDialog) {
            showCreateTemplateDialog = false
        } else if (showCreatePatientDialog) {
            showCreatePatientDialog = false
        } else if (showCustomizeFieldsDialog) {
            showCustomizeFieldsDialog = false
        } else if (showSyncHubDialog) {
            showSyncHubDialog = false
        } else if (showNewEntryWizard) {
            showNewEntryWizard = false
        } else if (showPremiumUpsellDialog) {
            showPremiumUpsellDialog = false
        } else if (showGeminiApiSettingsDialog) {
            showGeminiApiSettingsDialog = false
        } else if (selectedTabId != 0) {
            selectedTabId = 0
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val json = stream.bufferedReader().readText()
                    viewModel.restoreBackup(
                        jsonString = json,
                        onSuccess = { report -> restoreMessage = "Backup restored. Nothing already on this phone was deleted.\n\n$report" },
                        onError = { error -> restoreMessage = "Restore failed: $error" }
                    )
                }
            } catch (e: Exception) {
                restoreMessage = "Error reading backup file: ${e.localizedMessage}"
            }
        }
    }

    // The summary runs only on an explicit request (Generate, "AI Summary" on an entry,
    // "Summarize All", or confirming a selection) - never just because a style or engine
    // chip changed, since cloud engines receive patient data.
    var aiRunRequest by remember { mutableIntStateOf(0) }

    // Auto-sync single entry to the lists
    LaunchedEffect(selectedEntryForAiSummary) {
        val entry = selectedEntryForAiSummary
        if (entry != null) {
            selectedEntriesForAiSummary = listOf(entry)
            aiRunRequest++
        } else {
            selectedEntriesForAiSummary = emptyList()
        }
    }

    LaunchedEffect(selectedEntriesForAiSummary) {
        aiGeneratedSummaryText = ""
    }

    LaunchedEffect(aiRunRequest) {
        if (aiRunRequest > 0 && selectedEntriesForAiSummary.isNotEmpty()) {
            aiIsSynthesizing = true
            aiGeneratedSummaryText = "Normalizing dynamic EMR schema fields and activating AI pipeline..."
            
            val currentEntries = selectedEntriesForAiSummary
            val currentStyle = selectedAiStyle
            val currentProvider = selectedAiProvider
            val currentTier = subscriptionTier

            try {
                // Ensure all complex JSON parsing and networking tasks run on the background Dispatcher
                val resultSummary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val normalized = com.example.data.service.AiSummaryService.normalizeLogs(currentEntries, fieldLabelMap)
                    val provider = com.example.data.service.AiSummaryService.providers.find { it.id == currentProvider }
                    if (provider != null) {
                        if (provider.isPremiumOnly && currentTier == SubscriptionTier.FREE) {
                            "⚠️ UPGRADE REQUIRED\n\nThe chosen provider '${provider.displayName}' is limited to Premium clinical staff tiers.\n\nTurn on 'PRO' mode in the top right header badge to run instant summaries with premium engines!"
                        } else {
                            provider.generateCaseSummary(normalized, currentStyle)
                        }
                    } else {
                        "Provider error."
                    }
                }
                
                // Update UI state on Main Thread
                aiGeneratedSummaryText = resultSummary
                if (!resultSummary.startsWith("⚠️") && !resultSummary.startsWith("❌") && !resultSummary.contains("Provider error") && !resultSummary.contains("EXCEPT")) {
                    viewModel.incrementAiSummaryCount()
                }
            } catch (e: Throwable) {
                aiGeneratedSummaryText = "AI Synthesis exception: ${e.localizedMessage ?: "Unknown initialization/network failure"}"
            } finally {
                aiIsSynthesizing = false
            }
        }
    }

    // Back Confirmation State
    var showDraftBackWarning by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Save Confirmation Wrapper helper
    fun alertUnsavedChanges(onConfirm: () -> Unit) {
        // Since home screen uses modular stack transitions, warn user if they have fields half-filled in custom patient or template names forms
        onConfirm()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            val isPremium = subscriptionTier == SubscriptionTier.PREMIUM
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // LOGO + TITLE
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(id = com.example.R.drawable.ic_logo),
                            contentDescription = "LogiMind logo",
                            modifier = Modifier.size(44.dp)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "LogiMind",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.4).sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                text = "Clinical Logbook",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1
                            )
                        }

                        // SETTINGS + PLAN BADGE (same height and shape)
                        Surface(
                            onClick = { showGeminiApiSettingsDialog = true },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            onClick = { showPremiumUpsellDialog = true },
                            shape = RoundedCornerShape(50),
                            color = if (isPremium) BrandTheme.colors.premiumContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.height(38.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val badgeColor = if (isPremium) BrandTheme.colors.onPremiumContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                Icon(
                                    imageVector = if (isPremium) Icons.Rounded.Star else Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = badgeColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isPremium) "PRO" else "FREE",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                    color = badgeColor
                                )
                            }
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = BrandTheme.colors.headerDivider)
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTabId) {
                0 -> { // MAIN DASHBOARD SCREEN UIs
                    LazyColumn(
                        modifier = modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp)
                    ) {
                        // --- 4 LARGE TILES CARD GRID SYSTEM ---
                        item {
                            val tileSpecs = remember(patients, templates) {
                                listOf(
                                    Triple("patients", "Patients", "${patients.size} Patients"),
                                    Triple("new_entry", "New Entry", "Create Log"),
                                    Triple("templates", "Templates", "${templates.size} Templates"),
                                    Triple("sync", "Sync", "Cloud Backup"),
                                    Triple("my_procedures", "My Procedures", "Statistics & Case Counts")
                                )
                            }
                            
                            val sortedSpecs = remember(tileOrder, tileSpecs) {
                                tileOrder.mapNotNull { id -> tileSpecs.find { it.first == id } }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (!tileTipDismissed) Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Drag & drop: Press & hold any tile to reorganize dashboard",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                tileTipDismissed = true
                                                context.getSharedPreferences("logimind_prefs", android.content.Context.MODE_PRIVATE)
                                                    .edit().putBoolean("tile_tip_dismissed", true).apply()
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Hide tip", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }

                            val chunkedSpecs = remember(sortedSpecs) {
                                val list = mutableListOf<List<Triple<String, String, String>>>()
                                var currentPair = mutableListOf<Triple<String, String, String>>()
                                for (spec in sortedSpecs) {
                                    if (spec.first == "my_procedures") {
                                        if (currentPair.isNotEmpty()) {
                                            list.add(currentPair)
                                            currentPair = mutableListOf()
                                        }
                                        list.add(listOf(spec))
                                    } else {
                                        currentPair.add(spec)
                                        if (currentPair.size == 2) {
                                            list.add(currentPair)
                                            currentPair = mutableListOf()
                                        }
                                    }
                                }
                                if (currentPair.isNotEmpty()) {
                                    list.add(currentPair)
                                }
                                list
                            }
                            for (rowSpecs in chunkedSpecs) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    for (spec in rowSpecs) {
                                        val (id, title, description) = spec
                                            val tileIcon = when (id) {
                                                "patients" -> Icons.Rounded.People
                                                "new_entry" -> Icons.Rounded.Add
                                                "templates" -> Icons.Rounded.Description
                                                "sync" -> Icons.Rounded.Sync
                                                else -> Icons.Rounded.CheckCircle
                                            }
                                            val tileBadge = if (id == "my_procedures") "AGGREGATED" else null
                                            val tileColor = when (id) {
                                                "patients" -> BrandTheme.colors.tilePatients
                                                "new_entry" -> BrandTheme.colors.tileNewEntry
                                                "templates" -> BrandTheme.colors.tileTemplates
                                                "sync" -> BrandTheme.colors.tileSync
                                                else -> BrandTheme.colors.tileProcedures
                                            }
                                            val tileClick = {
                                                when (id) {
                                                    "patients" -> { selectedTabId = 1 }
                                                    "new_entry" -> { showNewEntryWizard = true }
                                                    "templates" -> { selectedTabId = 2 }
                                                    "sync" -> { showSyncHubDialog = true }
                                                    "my_procedures" -> { showProceduresDashboard = true }
                                                }
                                            }

                                            val isDragging = activeDraggedId == id
                                            val dragModifier = Modifier
                                                .weight(1f)
                                                .onGloballyPositioned { layoutCoordinates ->
                                                    val size = layoutCoordinates.size
                                                    val localCenter = Offset(size.width / 2f, size.height / 2f)
                                                    tileCenters[id] = layoutCoordinates.localToRoot(localCenter)
                                                }
                                                .pointerInput(id) {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = {
                                                            activeDraggedId = id
                                                            activeDragOffset = Offset.Zero
                                                        },
                                                        onDragEnd = {
                                                            activeDraggedId = null
                                                            activeDragOffset = Offset.Zero
                                                        },
                                                        onDragCancel = {
                                                            activeDraggedId = null
                                                            activeDragOffset = Offset.Zero
                                                        },
                                                        onDrag = { change, dragAmount ->
                                                            change.consume()
                                                            activeDragOffset += dragAmount

                                                            val currentCenter = tileCenters[id]
                                                            if (currentCenter != null) {
                                                                val fingerPos = currentCenter + activeDragOffset
                                                                var nearestId: String? = null
                                                                var minDistance = Float.MAX_VALUE
                                                                for ((otherId, otherCenter) in tileCenters) {
                                                                    if (otherId != id) {
                                                                        val dist = (fingerPos - otherCenter).getDistance()
                                                                        if (dist < minDistance) {
                                                                            minDistance = dist
                                                                            nearestId = otherId
                                                                        }
                                                                    }
                                                                }
                                                                // Swapping threshold
                                                                if (nearestId != null && minDistance < 320f) {
                                                                    val fromIndex = tileOrder.indexOf(id)
                                                                    val toIndex = tileOrder.indexOf(nearestId)
                                                                    if (fromIndex != -1 && toIndex != -1) {
                                                                        val newOrder = tileOrder.toMutableList()
                                                                        newOrder.removeAt(fromIndex)
                                                                        newOrder.add(toIndex, id)
                                                                        saveTileOrder(newOrder)

                                                                        val oldCenter = currentCenter
                                                                        val newCenter = tileCenters[nearestId]
                                                                        if (newCenter != null) {
                                                                            activeDragOffset += (oldCenter - newCenter)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    )
                                                }
                                                .graphicsLayer {
                                                    if (isDragging) {
                                                        translationX = activeDragOffset.x
                                                        translationY = activeDragOffset.y
                                                        scaleX = 1.06f
                                                        scaleY = 1.06f
                                                        shadowElevation = 18f
                                                    }
                                                }

                                            LargeChoiceTile(
                                                title = title,
                                                description = description,
                                                icon = tileIcon,
                                                badge = tileBadge,
                                                modifier = dragModifier,
                                                tintColor = tileColor,
                                                onClick = tileClick
                                            )
                                        }
                                        if (rowSpecs.size == 1 && rowSpecs[0].first != "my_procedures") {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }

                        // --- RECENT COMPLETED LOG CARDS LIST ---
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "RECENT LOGS",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.secondary,
                                            letterSpacing = 1.sp
                                        )
                                        
                                        // Custom interactive Select mode chip
                                        Surface(
                                            color = if (isSelectionMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.clickable {
                                                isSelectionMode = !isSelectionMode
                                                if (!isSelectionMode) {
                                                    viewModel.clearEntrySelectionForExport()
                                                }
                                            }
                                        ) {
                                            Text(
                                                text = if (isSelectionMode) "✓ Select Active" else "Select Mode",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            showLogSearch = !showLogSearch
                                            if (!showLogSearch) logSearchQuery = ""
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (showLogSearch) Icons.Rounded.SearchOff else Icons.Rounded.Search,
                                            contentDescription = if (showLogSearch) "Close search" else "Search logs",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    TextButton(onClick = { viewAllEntries = !viewAllEntries }) {
                                        Text(
                                            text = if (viewAllEntries) "Collapse" else "View All (${entries.size})",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (showLogSearch) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = logSearchQuery,
                                        onValueChange = { logSearchQuery = it },
                                        placeholder = { Text("Search patient, MRN, procedure, answer...") },
                                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                        trailingIcon = {
                                            if (logSearchQuery.isNotEmpty()) {
                                                IconButton(onClick = { logSearchQuery = "" }) {
                                                    Icon(Icons.Rounded.Clear, contentDescription = "Clear search")
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CalendarDatePickerButton(
                                        label = "Start Date",
                                        selectedTimeMs = recentLogsStartDate,
                                        onDateSelected = { recentLogsStartDate = it },
                                        onClear = { recentLogsStartDate = null },
                                        modifier = Modifier.weight(1f)
                                    )

                                    CalendarDatePickerButton(
                                        label = "End Date",
                                        selectedTimeMs = recentLogsEndDate,
                                        onDateSelected = { recentLogsEndDate = it },
                                        onClear = { recentLogsEndDate = null },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        val searchTerms = logSearchQuery.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                        val filteredEntries = entries.filter { entry ->
                            isWithinDayRange(entry.createdAt, recentLogsStartDate, recentLogsEndDate) &&
                                searchTerms.all { term -> entrySearchText[entry.uid]?.contains(term) == true }
                        }

                        // Selection Action Bar Helper
                        if (isSelectionMode) {
                            item {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().animateContentSize()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${selectedEntriesForExport.size} of ${filteredEntries.size} logs selected",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val allSelected = selectedEntriesForExport.size == filteredEntries.size && filteredEntries.isNotEmpty()
                                            TextButton(
                                                onClick = {
                                                    if (allSelected) {
                                                        viewModel.clearEntrySelectionForExport()
                                                    } else {
                                                        viewModel.setEntrySelectionForExport(filteredEntries.map { it.uid }.toSet())
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text(if (allSelected) "Deselect All" else "Select All Shown", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                            if (selectedEntriesForExport.isNotEmpty()) {
                                                Button(
                                                    onClick = {
                                                        syncHubTab = 1 // Switch directly to Clinical Exports Tab!
                                                        showSyncHubDialog = true
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Export", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val displayedEntries = if (viewAllEntries || searchTerms.isNotEmpty()) filteredEntries else filteredEntries.take(3)

                        if (entries.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    elevation = CardDefaults.cardElevation(4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(22.dp)
                                    ) {
                                        Text(
                                            "No entries solved yet",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            "Select a template blueprint to begin logging.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else if (filteredEntries.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            if (searchTerms.isNotEmpty()) "No entries match your search" else "No entries match this filter range",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            items(displayedEntries, key = { it.uid }) { entry ->
                                val isSelected = selectedEntriesForExport.contains(entry.uid)
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { dismissValue ->
                                        if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                                            logToDelete = entry
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                )

                                if (logToDelete != entry && dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                                    LaunchedEffect(Unit) {
                                        dismissState.reset()
                                    }
                                }

                                SwipeToDismissBox(
                                    state = dismissState,
                                    enableDismissFromStartToEnd = false,
                                    backgroundContent = {
                                        val color = when (dismissState.targetValue) {
                                            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                                            else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(vertical = 4.dp)
                                                .background(color, shape = RoundedCornerShape(12.dp)),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Delete,
                                                contentDescription = "Swipe to delete",
                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.padding(end = 16.dp)
                                            )
                                        }
                                    },
                                    content = {
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (isSelectionMode) {
                                                        viewModel.toggleEntrySelectionForExport(entry.uid)
                                                    } else {
                                                        selectedEntryForView = entry
                                                    }
                                                }
                                                .border(
                                                    BorderStroke(
                                                        1.dp,
                                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                                    ), 
                                                    shape = RoundedCornerShape(12.dp)
                                                ),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
                                            ),
                                            shape = RoundedCornerShape(12.dp),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isSelectionMode) {
                                                    Checkbox(
                                                        checked = isSelected,
                                                        onCheckedChange = {
                                                            viewModel.toggleEntrySelectionForExport(entry.uid)
                                                        },
                                                        modifier = Modifier.padding(end = 8.dp)
                                                    )
                                                }
                                                Surface(
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    modifier = Modifier.size(40.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        val initial = entry.patientName?.trim()?.firstOrNull()?.uppercaseChar()
                                                        if (entry.patientId != null && initial != null) {
                                                            Text(
                                                                text = initial.toString(),
                                                                fontSize = 16.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.primary
                                                            )
                                                        } else {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Assignment,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = entry.title,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = "${entry.patientName} • ${entry.templateName}",
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.secondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = java.text.SimpleDateFormat("EEE, MMM d yyyy • HH:mm", java.util.Locale.getDefault())
                                                            .format(java.util.Date(entry.createdAt)),
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.secondary
                                                    )
                                                }
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.ArrowForward,
                                                        contentDescription = "Details",
                                                        tint = MaterialTheme.colorScheme.outline,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        // --- CLINICAL AI PATIENT ASSISTANT PANEL ---
                        item {
                            Spacer(modifier = Modifier.height(6.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        BorderStroke(
                                            1.dp,
                                            if (selectedEntryForAiSummary != null && subscriptionTier == SubscriptionTier.PREMIUM) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                            } else {
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                            }
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(12.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Star,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "AI Summary",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        // Premium / AI Counter Badge
                                        val aiSummaryCount by viewModel.aiSummaryCount.collectAsState()
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "⚡ DIGITAL BRIEFINGS: $aiSummaryCount",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = {
                                                selectedLogIdsForAiSummary = selectedEntriesForAiSummary.map { it.uid }.toSet()
                                                showSelectLogsForAiDialog = true
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Rounded.List, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Select Logs to Summarize", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))

                                    if (selectedEntriesForAiSummary.isNotEmpty()) {
                                        Text(
                                            text = "Summarizing: ${selectedEntriesForAiSummary.size} selected log(s)",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(bottom = 6.dp)
                                        )
                                    }

                                    if (selectedEntriesForAiSummary.isEmpty()) {
                                        Text(
                                            text = "Click the button above to view and select the assessment logs you wish to summarize.\n\nAI Functionality is architected to parse checklist logs, auto-categorize symptoms, outline histories, list medication timelines, and generate secure treatment briefings.",
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            if (isSelectionMode && selectedEntriesForExport.isNotEmpty()) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = "${selectedEntriesForExport.size} Log(s) Selected",
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 12.sp,
                                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                                            )
                                                            Text(
                                                                text = "Combine active selection into a unified briefing.",
                                                                fontSize = 11.sp,
                                                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                                            )
                                                        }
                                                        Button(
                                                            onClick = {
                                                                val chosenList = entries.filter { selectedEntriesForExport.contains(it.uid) }
                                                                if (chosenList.isNotEmpty()) {
                                                                    selectedEntryForAiSummary = null
                                                                    selectedEntriesForAiSummary = chosenList
                                                                    aiRunRequest++
                                                                }
                                                            },
                                                            shape = RoundedCornerShape(8.dp),
                                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                                        ) {
                                                            Text("Summarize All", fontSize = 11.sp)
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(10.dp))
                                            }

                                            // Current entry info
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                if (selectedEntriesForAiSummary.size == 1) {
                                                    val log = selectedEntriesForAiSummary.first()
                                                    Column(modifier = Modifier.padding(10.dp)) {
                                                        Text(
                                                            text = "Synthesizing case context for: ${log.title}",
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text(
                                                            text = "Patient: ${log.patientName ?: "Theatre"} | Blueprint: ${log.templateName}",
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.secondary
                                                        )
                                                    }
                                                } else {
                                                    Column(modifier = Modifier.padding(10.dp)) {
                                                        Text(
                                                            text = "Synthesizing Combined Clinical Briefing",
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text(
                                                            text = "Synthesizing ${selectedEntriesForAiSummary.size} assessment records across patient logs.",
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.secondary
                                                        )
                                                    }
                                                }
                                            }

                                            // Options & Parameters Selector
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    text = "Configure Presentation Style:",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    val styles = listOf(
                                                        "short" to "Executive",
                                                        "bullets" to "Outlines",
                                                        "timeline" to "Timeline",
                                                        "clinical" to "SOAP Check"
                                                    )
                                                    styles.forEach { (id, label) ->
                                                        val isStyleSel = selectedAiStyle == id
                                                        Surface(
                                                            onClick = { selectedAiStyle = id },
                                                            shape = RoundedCornerShape(20),
                                                            color = if (isStyleSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                            border = if (isStyleSel) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Text(
                                                                text = label,
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = if (isStyleSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                                textAlign = TextAlign.Center,
                                                                modifier = Modifier.padding(vertical = 6.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(4.dp))

                                                Text(
                                                    text = "Select AI Summary LLM Engine:",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                                
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    // GPT-4 and Claude are not connected yet, so they are not offered.
                                                    val providers = listOf(
                                                        "local_offline" to "LogiMind Local",
                                                        "gemini_pro" to "Gemini"
                                                    )
                                                    providers.forEach { (id, label) ->
                                                        val isProvSel = selectedAiProvider == id
                                                        val isPremium = id != "local_offline"
                                                        Surface(
                                                            onClick = {
                                                                // Cloud engines receive patient data; ask once before the first use.
                                                                val consented = context.getSharedPreferences("logimind_prefs", android.content.Context.MODE_PRIVATE)
                                                                    .getBoolean("ai_consent_v1", false)
                                                                if (id == "gemini_pro" && !consented) showCloudAiConsentFor = id else selectedAiProvider = id
                                                            },
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = if (isProvSel) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                                                            border = if (isProvSel) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(vertical = 6.dp),
                                                                horizontalArrangement = Arrangement.Center,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Text(
                                                                    text = label.split(" ").first(),
                                                                    fontSize = 11.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = if (isProvSel) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    textAlign = TextAlign.Center
                                                                )
                                                                if (isPremium) {
                                                                    Spacer(modifier = Modifier.width(2.dp))
                                                                    Icon(
                                                                        imageVector = Icons.Rounded.Star,
                                                                        contentDescription = "Premium Mode",
                                                                        tint = if (isProvSel) MaterialTheme.colorScheme.onSecondary else Color(0xFFFFB300),
                                                                        modifier = Modifier.size(14.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(4.dp))

                                            Button(
                                                onClick = { aiRunRequest++ },
                                                enabled = !aiIsSynthesizing,
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(if (aiGeneratedSummaryText.isBlank()) "Generate Summary" else "Regenerate Summary", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }

                                            // Synthetic or live AI Summary Output Panel
                                            Card(
                                                shape = RoundedCornerShape(12.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                                ),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        val providerName = com.example.data.service.AiSummaryService.providers.find { it.id == selectedAiProvider }?.displayName ?: "AI Model"
                                                        Text(
                                                            text = "$providerName Diagnostics Draft",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        if (aiIsSynthesizing) {
                                                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                                        }
                                                    }

                                                    if (aiIsSynthesizing) {
                                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                                    }

                                                    Text(
                                                        text = aiGeneratedSummaryText,
                                                        fontSize = 11.sp,
                                                        lineHeight = 16.sp,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }

                                            // Action controls
                                            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Button(
                                                    onClick = {
                                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(aiGeneratedSummaryText))
                                                    },
                                                    shape = RoundedCornerShape(8.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                                    modifier = Modifier.height(36.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                                ) {
                                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Copy Clinical Text", fontSize = 11.sp)
                                                }

                                                TextButton(onClick = { selectedEntryForAiSummary = null; selectedEntriesForAiSummary = emptyList() }) {
                                                    Text("Clear AI Panel", fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> { // PATIENT REGISTRY MANAGEMENT VIEW
                    LazyColumn(
                        modifier = modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp)
                    ) {
                        // Header with Customize Options & Add Patient Buttons
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { selectedTabId = 0 },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                                    }
                                    Text(
                                        text = "Home",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    // Custom questions setup gear
                                    IconButton(onClick = { showCustomizeFieldsDialog = true }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Settings,
                                            contentDescription = "Customize Questions",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedTextField(
                                        value = patientFilterQuery,
                                        onValueChange = { patientFilterQuery = it },
                                        placeholder = { Text("name or ID...") },
                                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                        )
                                    )
                                    
                                    Button(
                                        onClick = { showCreatePatientDialog = true },
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Register", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        val filteredPatients = patients.filter {
                            it.name.contains(patientFilterQuery, ignoreCase = true) ||
                            it.medicalId.contains(patientFilterQuery, ignoreCase = true)
                        }

                        if (filteredPatients.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    text = "No match found.",
                                    icon = Icons.Rounded.Person
                                )
                            }
                        } else {
                            // Keyed by patient: position-based state showed one patient's logs on another's card.
                            items(filteredPatients, key = { it.uid }) { pat ->
                                var detailsExpanded by remember { mutableStateOf(false) }
                                val patHistories = remember(entries, pat.uid) { entries.filter { it.patientId == pat.uid } }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp)),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth().clickable { detailsExpanded = !detailsExpanded }
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = pat.name,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "ID: ${pat.medicalId} • ${patHistories.size} clinical logs recorded",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                            IconButton(onClick = { detailsExpanded = !detailsExpanded }) {
                                                Icon(
                                                    imageVector = if (detailsExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                                    contentDescription = if (detailsExpanded) "Hide patient details" else "Show patient details"
                                                )
                                            }
                                        }

                                        if (detailsExpanded) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            HorizontalDivider()
                                            Spacer(modifier = Modifier.height(10.dp))

                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                DemographicRow(label = "Primary Contact", value = pat.phone ?: "Not Recorded")
                                                DemographicRow(label = "Age Bracket", value = pat.age ?: "Not Recorded")
                                                DemographicRow(label = "Insurance Coverage", value = pat.insurance ?: "Not Recorded")
                                                // "notes" holds the clinical background question; allergies live in customAnswersJson.
                                                val intakeLabels = customIntakeFields.associate { it.id to it.label }
                                                DemographicRow(label = intakeLabels["notes"] ?: "Clinical Background", value = pat.notes ?: "Not Recorded")

                                                if (!pat.customAnswersJson.isNullOrBlank()) {
                                                    val customPairs = remember(pat.customAnswersJson) {
                                                        val list = mutableListOf<Pair<String, String>>()
                                                        try {
                                                            val customJson = JSONObject(pat.customAnswersJson)
                                                            val kItr = customJson.keys()
                                                            while (kItr.hasNext()) {
                                                                val k = kItr.next()
                                                                list.add(k to customJson.optString(k))
                                                            }
                                                        } catch (e: Exception) {
                                                            e.printStackTrace()
                                                        }
                                                        list
                                                    }
                                                    customPairs.forEach { (key, value) ->
                                                        DemographicRow(label = intakeLabels[key] ?: intakeKeyFallbackLabel(key), value = value)
                                                    }
                                                }
                                            }

                                            if (patHistories.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(12.dp))
                                                Text("FILING STATS HISTORY LOGS:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                                patHistories.forEach { rec ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                selectedEntryForView = rec
                                                                selectedTabId = 0
                                                            }
                                                            .padding(vertical = 4.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(text = "• ${rec.title}", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                                        Text(text = "View details >", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                TextButton(onClick = { patientToEdit = pat }) {
                                                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Edit Profile", fontSize = 11.sp)
                                                }
                                                TextButton(
                                                    onClick = { patientToDelete = pat },
                                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                                ) {
                                                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Delete Profile", fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> { // BLUEPRINTS MANAGER SCREEN VIEW
                    LazyColumn(
                        modifier = modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { selectedTabId = 0 },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                                }
                                Text(
                                    text = "HOME",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                
                                Button(
                                    onClick = { showCreateTemplateDialog = true },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("NEW", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (templates.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    text = "No templates yet",
                                    icon = Icons.Rounded.List
                                )
                            }
                        } else {
                            items(templates, key = { it.uid }) { template ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedTemplateForOptions = template }
                                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp)),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Rounded.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = template.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = when {
                                                    !template.isActive -> "Archived • hidden from New Entry"
                                                    template.uid in pinnedTemplates -> "★ Pinned • Tap to open Options Dashboard"
                                                    else -> "Tap to open Options Dashboard"
                                                },
                                                fontSize = 11.sp,
                                                color = if (template.isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Rounded.MoreVert,
                                            contentDescription = "Options",
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- SAVE CONFIRMATION SYSTEM POPUP DIALOG ---
    if (showDraftBackWarning != null) {
        AlertDialog(
            onDismissRequest = { showDraftBackWarning = null },
            title = { Text("Discard unsaved details?", fontWeight = FontWeight.Bold) },
            text = { Text("You have typed patient details that are not saved yet. Discard them?") },
            confirmButton = {
                Button(onClick = {
                    // Keep editing: close only this warning.
                    showDraftBackWarning = null
                }) {
                    Text("Keep Editing", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val discard = showDraftBackWarning
                    showDraftBackWarning = null
                    discard?.invoke()
                }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    // --- ACTIVE TEMPLATE OPTIONS ACTION SHEET/DIALOG ---
    if (selectedTemplateForOptions != null) {
        val selTemp = selectedTemplateForOptions!!
        AlertDialog(
            onDismissRequest = { selectedTemplateForOptions = null },
            title = {
                Text(
                    text = selTemp.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose an action for this clinical blueprint:", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    
                    // Option 1: Use Template
                    OutlinedButton(
                        onClick = {
                            selectedTemplateForOptions = null
                            viewModel.clearSolveAnswers()
                            viewModel.selectTemplateForEdit(selTemp)
                            onNavigateToSolve(selTemp)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Use Template", fontWeight = FontWeight.Bold)
                    }

                    // Option 2: Edit Template
                    OutlinedButton(
                        onClick = {
                            selectedTemplateForOptions = null
                            viewModel.selectTemplateForEdit(selTemp)
                            onNavigateToDesigner(selTemp)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Edit blueprint Questions")
                    }

                    // Option 3: Preview Template
                    OutlinedButton(
                        onClick = {
                            selectedTemplateForOptions = null
                            showTemplatePreviewDialog = selTemp
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Preview template read-only")
                    }

                    // Option 4: Duplicate
                    OutlinedButton(
                        onClick = {
                            selectedTemplateForOptions = null
                            viewModel.duplicateTemplate(selTemp)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Duplicate Template COPY")
                    }

                    // Option: Rename Template
                    OutlinedButton(
                        onClick = {
                            selectedTemplateForOptions = null
                            showRenameTemplateDialogFor = selTemp
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Rename Blueprint Template")
                    }

                    // Pin to the top of "New Entry"
                    val isPinned = selTemp.uid in pinnedTemplates
                    OutlinedButton(
                        onClick = {
                            viewModel.togglePinnedTemplate(selTemp.uid)
                            selectedTemplateForOptions = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(if (isPinned) Icons.Rounded.StarBorder else Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isPinned) "Unpin from New Entry" else "Pin to top of New Entry")
                    }

                    // Archive keeps the template and its log entries but hides it from "New Entry"
                    OutlinedButton(
                        onClick = {
                            viewModel.setTemplateArchived(selTemp, archived = selTemp.isActive)
                            selectedTemplateForOptions = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(if (selTemp.isActive) Icons.Rounded.Archive else Icons.Rounded.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (selTemp.isActive) "Archive (keep entries, hide from New Entry)" else "Unarchive")
                    }

                    // Option 5: Delete Blueprint
                    Button(
                        onClick = {
                            selectedTemplateForOptions = null
                            viewModel.countEntriesForTemplate(selTemp.uid) { count ->
                                templateToDelete = selTemp to count
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete Blueprint", fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedTemplateForOptions = null }) {
                    Text("Close")
                }
            }
        )
    }

    // --- DIALOG: RENAME TEMPLATE INTUITIVELY ---
    if (showRenameTemplateDialogFor != null) {
        val tempToRename = showRenameTemplateDialogFor!!
        var renName by remember(tempToRename) { mutableStateOf(tempToRename.name) }
        AlertDialog(
            onDismissRequest = { showRenameTemplateDialogFor = null },
            title = { Text("Rename Blueprint Template", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renName,
                    onValueChange = { renName = it },
                    label = { Text("Blueprint Template Name") },
                    placeholder = { Text("e.g. Pediatric Anesthesia Intubation") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renName.isNotBlank()) {
                            viewModel.updateTemplateName(tempToRename, renName)
                            showRenameTemplateDialogFor = null
                        }
                    }
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameTemplateDialogFor = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- DIALOG: PREVIEW TEMPLATE READ-ONLY ---
    if (showTemplatePreviewDialog != null) {
        val temp = showTemplatePreviewDialog!!
        AlertDialog(
            onDismissRequest = { showTemplatePreviewDialog = null },
            title = { Text(text = "LIVE TEMPLATE PREVIEW: ${temp.name}", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("This is a structural layout blueprint display:", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)

                    var preview by remember(temp.uid) { mutableStateOf<List<Pair<String, List<Pair<FormField, List<com.example.data.model.FormFieldOption>>>>>?>(null) }
                    LaunchedEffect(temp.uid) { viewModel.loadTemplatePreview(temp.uid) { preview = it } }

                    when {
                        preview == null -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        preview!!.isEmpty() -> Text("This template has no questions yet.", fontSize = 11.sp)
                        else -> preview!!.forEach { (sectionTitle, sectionFields) ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(sectionTitle, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    if (sectionFields.isEmpty()) {
                                        Text("No questions in this group.", fontSize = 11.sp)
                                    }
                                    sectionFields.forEach { (field, opts) ->
                                        Text("• ${field.label}${if (field.isRequired) " *" else ""}", fontSize = 11.sp)
                                        if (opts.isNotEmpty()) {
                                            Text(
                                                opts.joinToString(" / ") { it.label },
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.padding(start = 10.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showTemplatePreviewDialog = null }) {
                    Text("Close Preview")
                }
            }
        )
    }

    // --- STEP-BY-STEP NEW ENTRY WIZARD ASSISTANT ---
    if (showNewEntryWizard) {
        var wizardStep by remember { mutableIntStateOf(1) }
        var linkedPatient by remember { mutableStateOf<Patient?>(null) }
        var patientSearchQuery by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showNewEntryWizard = false },
            title = {
                Text(
                    text = "New Assessment Record (Step $wizardStep of 2)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (wizardStep) {
                        1 -> { // Step 1: Link Patients Registry profile
                            Text("Select an enrolled patient profile, or select Theatre:", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                            
                            OutlinedTextField(
                                value = patientSearchQuery,
                                onValueChange = { patientSearchQuery = it },
                                placeholder = { Text("Search by name or ID...") },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )

                            // Quick-add a patient without leaving New Entry
                            TextButton(
                                onClick = { showCreatePatientDialog = true },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("New patient", fontSize = 12.sp)
                            }

                            // Quick Theatre selection
                            Surface(
                                onClick = {
                                    linkedPatient = null
                                    viewModel.setWizardSelectedPatient(null)
                                    wizardStep = 2
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (linkedPatient == null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Theatre", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            val filteredPats = patients.filter {
                                it.name.contains(patientSearchQuery, ignoreCase = true) ||
                                        it.medicalId.contains(patientSearchQuery, ignoreCase = true)
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                filteredPats.forEach { p ->
                                    Surface(
                                        onClick = {
                                            linkedPatient = p
                                            viewModel.setWizardSelectedPatient(p)
                                            wizardStep = 2
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (linkedPatient?.uid == p.uid) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                Text("MRN ID: ${p.medicalId}", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        2 -> { // Step 2: Select active checklist blueprint
                            Text("Select a template:", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                            
                            val wizardTemplates = templates.filter { it.isActive }
                                .sortedByDescending { it.uid in pinnedTemplates }
                            if (wizardTemplates.isEmpty()) {
                                Text("No templates are drafted yet. Add templates first!")
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    wizardTemplates.forEach { temp ->
                                        Surface(
                                            onClick = {
                                                viewModel.clearSolveAnswers()
                                                viewModel.setWizardSelectedPatient(linkedPatient)
                                                viewModel.selectTemplateForEdit(temp)
                                                showNewEntryWizard = false
                                                onNavigateToSolve(temp)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color.Transparent,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    if (temp.uid in pinnedTemplates) Icons.Rounded.Star else Icons.Rounded.List,
                                                    contentDescription = if (temp.uid in pinnedTemplates) "Pinned" else null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(temp.name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (wizardStep == 2) {
                        TextButton(onClick = { wizardStep = 1 }) {
                            Text("Back to Patients")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                    TextButton(onClick = { showNewEntryWizard = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    // --- SECURE SYNC HUB & CLINICAL EXPORTS DIALOG ---
    if (showSyncHubDialog) {
        AlertDialog(
            onDismissRequest = { showSyncHubDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Sync & Clinical Export Hub", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    // Custom interactive Tab Selection Row
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val tabs = listOf("Cloud Sync", "Clinical Reports", "Backup & Restore")
                        tabs.forEachIndexed { index, tabTitle ->
                            val isTabSelected = syncHubTab == index
                            Surface(
                                onClick = { syncHubTab = index },
                                shape = RoundedCornerShape(20),
                                color = if (isTabSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = tabTitle,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center,
                                    color = if (isTabSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    when (syncHubTab) {
                        0 -> { // Cloud Sync Tab (Existing functionality)
                            Text(
                                text = "Cloud sync is not available yet. Your data stays on this phone — use Backup & Restore to keep a copy.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Offline Sync Status:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                    Text(
                                        text = when (syncState) {
                                            is SyncState.Idle -> "Not connected (coming soon)"
                                            is SyncState.Syncing -> "Checking..."
                                            is SyncState.Success -> "Nothing was uploaded: cloud sync is not available yet"
                                            is SyncState.Error -> "Sync error: ${(syncState as SyncState.Error).errorMsg}"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            if (subscriptionTier == SubscriptionTier.FREE) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Cloud Synchronizer is limited to Premium tiers.", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            } else {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            syncService.requestFullSync(templates, patients, entries)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Start Cloud Sync")
                                }
                            }
                        }
                        1 -> { // Clinical Reports Exports Tab
                            val selectedEntriesForExport by viewModel.selectedEntriesForExport.collectAsState()
                            val exportList = if (selectedEntriesForExport.isNotEmpty()) {
                                entries.filter { selectedEntriesForExport.contains(it.uid) }
                            } else {
                                entries
                            }

                            Text(
                                text = "Compile clinical-grade structured report files. If you activate 'Select Mode' on the recents list, you can filter exports to chosen patient records.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )

                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "Target size: ${exportList.size} Record(s)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = if (selectedEntriesForExport.isNotEmpty()) "Using Active Selection Filters" else "Using Complete EMR Database",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }

                            if (exportList.isEmpty()) {
                                Text(
                                    text = "No clinical records are available for export.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    // PDF button
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    com.example.data.service.ExportService.generatePdf(context, exportList, fieldLabelMap, fieldOrder)
                                                }
                                                com.example.data.service.ExportService.shareFile(context, file, "application/pdf")
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Share Monochrome EMR PDF", fontSize = 12.sp)
                                    }

                                    // CSV button
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    com.example.data.service.ExportService.generateCsv(context, exportList, fieldLabelMap, fieldOrder)
                                                }
                                                com.example.data.service.ExportService.shareFile(context, file, "text/csv")
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Share Spreadsheets (CSV)", fontSize = 12.sp)
                                    }

                                    // ZIP package button
                                    Button(
                                        onClick = {
                                            viewModel.exportZipBundle(
                                                selectedEntries = exportList,
                                                fieldLabelMap = fieldLabelMap,
                                                onReady = { file -> com.example.data.service.ExportService.shareFile(context, file, "application/zip") },
                                                onError = { restoreMessage = it }
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Share Packaged ZIP Bundle", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        2 -> { // Backup & Restore Tab
                            Text(
                                text = "Export completely lossless EMR JSON backups or browse and restore a past backup script.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        viewModel.exportFullBackup(
                                            onReady = { file -> com.example.data.service.ExportService.shareFile(context, file, "application/json") },
                                            onError = { restoreMessage = it }
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Rounded.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save Lossless JSON Backup", fontSize = 12.sp)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { filePickerLauncher.launch("application/json") },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Browse File", fontSize = 11.sp)
                                    }

                                    Button(
                                        onClick = { showTextRestoreDialog = true },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                    ) {
                                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Paste Script", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showSyncHubDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showTextRestoreDialog) {
        var pastedJsonText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTextRestoreDialog = false },
            title = { Text("Paste JSON Backup Script") },
            text = {
                OutlinedTextField(
                    value = pastedJsonText,
                    onValueChange = { pastedJsonText = it },
                    placeholder = { Text("Paste complete clinical database JSON structure here...") },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pastedJsonText.isNotBlank()) {
                            viewModel.restoreBackup(
                                jsonString = pastedJsonText,
                                onSuccess = { report ->
                                    restoreMessage = "Backup restored. Nothing already on this phone was deleted.\n\n$report"
                                    showTextRestoreDialog = false
                                    showSyncHubDialog = false // Dismiss both to refresh
                                },
                                onError = { error ->
                                    restoreMessage = "Restore failed: $error"
                                }
                            )
                        }
                    }
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextRestoreDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (restoreMessage != null) {
        AlertDialog(
            onDismissRequest = { restoreMessage = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Backup & Export")
                }
            },
            text = { Text(restoreMessage!!) },
            confirmButton = {
                Button(onClick = { restoreMessage = null }) {
                    Text("OK")
                }
            }
        )
    }

    // --- GLOBAL GEMINI API SECURE CONFIGURATION DIALOG ---
    if (showGeminiApiSettingsDialog) {
        var localApiKey by remember { mutableStateOf(viewModel.getGeminiApiKey()) }
        var isKeyVisible by remember { mutableStateOf(false) }
        var testResultMsg by remember { mutableStateOf<String?>(null) }
        var isTestingKey by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        AlertDialog(
            onDismissRequest = { showGeminiApiSettingsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Settings")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Appearance: System follows the phone's dark mode.
                    val themeMode by viewModel.themeMode.collectAsState()
                    Text("Appearance", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                        modes.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }

                    HorizontalDivider()

                    // App lock uses the phone's own screen lock (PIN, pattern, fingerprint or face).
                    val appLockEnabled by viewModel.appLockEnabled.collectAsState()
                    val deviceSecure = remember {
                        (context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("App lock", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(
                                if (deviceSecure) "Ask for the phone's PIN or fingerprint when opening LogiMind."
                                else "Set a screen lock (PIN, pattern or fingerprint) on this phone to use this.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Switch(
                            checked = appLockEnabled && deviceSecure,
                            onCheckedChange = { viewModel.setAppLockEnabled(it) },
                            enabled = deviceSecure
                        )
                    }

                    HorizontalDivider()

                    Text("Gemini API Key", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "To run real-time clinical summarizations secure and free, paste your personal Google Gemini API key.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = localApiKey,
                        onValueChange = { localApiKey = it },
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("AIzaSy...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        visualTransformation = if (isKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                                Icon(
                                    imageVector = if (isKeyVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                    contentDescription = "Toggle Key Visibility"
                                )
                            }
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Clear Key Button
                        TextButton(
                            onClick = { localApiKey = "" },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Clear Key")
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (localApiKey.isNotBlank()) {
                            // Test Key Button
                            Button(
                                onClick = {
                                    isTestingKey = true
                                    testResultMsg = "Sending lightweight test request..."
                                    scope.launch {
                                        try {
                                            val request = com.example.data.service.GeminiRequest(
                                                contents = listOf(
                                                    com.example.data.service.GeminiContent(
                                                        parts = listOf(
                                                            com.example.data.service.GeminiContentPart(text = "Hello! Respond with exactly one word: Success.")
                                                        )
                                                    )
                                                )
                                            )
                                            val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                com.example.data.service.GeminiClient.service.generateContent(localApiKey.trim(), request)
                                            }
                                            val result = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                                            if (!result.isNullOrBlank()) {
                                                testResultMsg = "✅ Connection successful! Your API key is functioning correctly."
                                            } else {
                                                testResultMsg = "⚠️ Connection made, but response was blank. API Key appears valid."
                                            }
                                        } catch (e: retrofit2.HttpException) {
                                            val errDetails = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { e.response()?.errorBody()?.string() ?: "" } catch (ex: Exception) { "" } }
                                            testResultMsg = "❌ Connection failed (${e.code()}):\n\n${e.message()}\n\nDetails: $errDetails"
                                        } catch (e: Throwable) {
                                            testResultMsg = "❌ Connection failed: ${e.localizedMessage ?: "Unknown network helper error"}\n\nValidate that the API key is correct and that you have a working network connection."
                                        } finally {
                                            isTestingKey = false
                                        }
                                    }
                                },
                                enabled = !isTestingKey,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                            ) {
                                if (isTestingKey) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                } else {
                                    Text("Test Key")
                                }
                            }
                        }
                    }

                    if (testResultMsg != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = testResultMsg!!,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Text("Security Info:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                text = "Your key is stored encrypted on this phone only and is sent only to Google's Gemini API when you run a Gemini summary. Selected log contents (including patient names) are sent to Google for that summary.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveGeminiApiKey(localApiKey)
                        showGeminiApiSettingsDialog = false
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save Key")
                }
            },
            dismissButton = {
                TextButton(onClick = { showGeminiApiSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- MANAGE REGISTRATION QUESTIONS CUSTOMIZATION CONFIG ---
    if (showCustomizeFieldsDialog) {
        val localList = remember(customIntakeFields) { customIntakeFields.toMutableStateList() }
        var newQuestionLabel by remember { mutableStateOf("") }
        var isNewQuestionRequired by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showCustomizeFieldsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Customize Intake Questions")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Configure demographic fields or add custom patient backdrop questions:", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    
                    localList.forEachIndexed { index, item ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(text = if (item.isRequired) "Registration Required" else "Optional intake question", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                }
                                
                                Switch(
                                    checked = item.isActive,
                                    onCheckedChange = { active ->
                                        localList[index] = item.copy(isActive = active)
                                    },
                                    modifier = Modifier.scale(0.8f)
                                )
                                
                                Spacer(modifier = Modifier.width(4.dp))
                                
                                Checkbox(
                                    checked = item.isRequired,
                                    onCheckedChange = { req ->
                                        localList[index] = item.copy(isRequired = req)
                                    }
                                )

                                if (item.id.startsWith("custom_intake_") || !listOf("age", "phone", "insurance", "notes").contains(item.id)) {
                                    IconButton(
                                        onClick = { localList.removeAt(index) },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = "Delete custom field",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Add Custom Theme Key / Question:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newQuestionLabel,
                            onValueChange = { newQuestionLabel = it },
                            placeholder = { Text("e.g. Allergies, Medical Hx") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isNewQuestionRequired, onCheckedChange = { isNewQuestionRequired = it })
                            Text("Req", fontSize = 11.sp)
                        }
                        IconButton(
                            onClick = {
                                if (newQuestionLabel.isNotBlank()) {
                                    val cleanedId = "custom_intake_" + System.currentTimeMillis()
                                    localList.add(
                                        com.example.data.model.PatientFieldConfig(
                                            id = cleanedId,
                                            label = newQuestionLabel.trim(),
                                            isActive = true,
                                            isRequired = isNewQuestionRequired
                                        )
                                    )
                                    newQuestionLabel = ""
                                    isNewQuestionRequired = false
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.AddCircle, contentDescription = "Add Question", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateIntakeFields(localList.toList())
                        showCustomizeFieldsDialog = false
                    }
                ) {
                    Text("Save Configuration")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomizeFieldsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (logToDelete != null) {
        AlertDialog(
            onDismissRequest = { logToDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text("Delete Assessment Log?")
                }
            },
            text = {
                Text("Are you sure you want to permanently delete user record/checklist for '${logToDelete?.title}'? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        logToDelete?.let { entry ->
                            viewModel.deleteFormEntry(entry.uid) { undo -> showUndo("Log entry deleted", undo) }
                            if (selectedEntryForView?.uid == entry.uid) {
                                selectedEntryForView = null
                            }
                            if (selectedEntriesForAiSummary.any { it.uid == entry.uid }) {
                                selectedEntriesForAiSummary = selectedEntriesForAiSummary.filter { it.uid != entry.uid }
                            }
                        }
                        logToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { logToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCloudAiConsentFor != null) {
        AlertDialog(
            onDismissRequest = { showCloudAiConsentFor = null },
            title = { Text("Send logs to Google Gemini?") },
            text = {
                Text("The selected log contents, including patient names and answers, will be sent to Google's Gemini API to write the summary. Only continue if your data policy allows this.")
            },
            confirmButton = {
                Button(onClick = {
                    context.getSharedPreferences("logimind_prefs", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("ai_consent_v1", true).apply()
                    selectedAiProvider = showCloudAiConsentFor!!
                    showCloudAiConsentFor = null
                }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloudAiConsentFor = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (templateToDelete != null) {
        val (temp, entryCount) = templateToDelete!!
        AlertDialog(
            onDismissRequest = { templateToDelete = null },
            title = { Text("Delete Blueprint?") },
            text = {
                Text(
                    if (entryCount > 0) {
                        "Deleting '${temp.name}' also permanently deletes the $entryCount log entr${if (entryCount == 1) "y" else "ies"} filled with it. This cannot be undone. Export a backup first if you may need them."
                    } else {
                        "Delete '${temp.name}' and all its questions? This cannot be undone."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteTemplate(temp)
                        templateToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (entryCount > 0) "Delete all" else "Delete")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { templateToDelete = null }) {
                        Text("Cancel")
                    }
                    if (entryCount > 0 && temp.isActive) {
                        TextButton(onClick = {
                            viewModel.setTemplateArchived(temp, archived = true)
                            templateToDelete = null
                        }) {
                            Text("Archive instead")
                        }
                    }
                }
            }
        )
    }

    if (patientToDelete != null) {
        val pat = patientToDelete!!
        val linkedLogs = entries.count { it.patientId == pat.uid }
        AlertDialog(
            onDismissRequest = { patientToDelete = null },
            title = { Text("Delete Patient Profile?") },
            text = {
                Text(
                    "Delete '${pat.name}' (${pat.medicalId})? " +
                        if (linkedLogs > 0) "Their $linkedLogs log entr${if (linkedLogs == 1) "y is" else "ies are"} kept and still show the patient's name." else "This cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deletePatient(pat) { undo -> showUndo("Patient profile deleted", undo) }
                        patientToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { patientToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSelectLogsForAiDialog) {
        var localSelection by remember(selectedEntriesForAiSummary) {
            mutableStateOf(selectedEntriesForAiSummary.map { it.uid }.toSet())
        }
        var aiSortStartDate by remember { mutableStateOf<Long?>(null) }
        var aiSortEndDate by remember { mutableStateOf<Long?>(null) }

        val filteredAiEntries = remember(entries, aiSortStartDate, aiSortEndDate) {
            entries.filter { entry ->
                isWithinDayRange(entry.createdAt, aiSortStartDate, aiSortEndDate)
            }
        }

        AlertDialog(
            onDismissRequest = { showSelectLogsForAiDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Select Logs to Summarize")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Choose which of the patient logs you'd like the AI engine to consolidate. Consolidating multiple logs creates a unified briefing across sessions.", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CalendarDatePickerButton(
                            label = "Start Date",
                            selectedTimeMs = aiSortStartDate,
                            onDateSelected = { aiSortStartDate = it },
                            onClear = { aiSortStartDate = null },
                            modifier = Modifier.weight(1f)
                        )

                        CalendarDatePickerButton(
                            label = "End Date",
                            selectedTimeMs = aiSortEndDate,
                            onDateSelected = { aiSortEndDate = it },
                            onClear = { aiSortEndDate = null },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                localSelection = localSelection + filteredAiEntries.map { it.uid }.toSet()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            Text("Select All (${filteredAiEntries.size})", fontSize = 11.sp)
                        }

                        TextButton(
                            onClick = {
                                localSelection = localSelection - filteredAiEntries.map { it.uid }.toSet()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            Text("Deselect All", fontSize = 11.sp)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    if (filteredAiEntries.isEmpty()) {
                        Text("No logs available matching this filter range.", fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        filteredAiEntries.forEach { entry ->
                            val isChecked = localSelection.contains(entry.uid)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) else Color.Unspecified,
                                onClick = {
                                    localSelection = if (isChecked) {
                                        localSelection - entry.uid
                                    } else {
                                        localSelection + entry.uid
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp)
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            localSelection = if (checked == true) {
                                                localSelection + entry.uid
                                            } else {
                                                localSelection - entry.uid
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${entry.patientName} • Blueprint: ${entry.templateName}", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val selectedList = entries.filter { localSelection.contains(it.uid) }
                        selectedEntriesForAiSummary = selectedList
                        if (selectedList.isNotEmpty()) aiRunRequest++
                        showSelectLogsForAiDialog = false
                    }
                ) {
                    Text("Confirm Selection")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSelectLogsForAiDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- ENROLL PATIENT DIALOG INCORPORATING CUSTOM QUESTIONS ---
    if (showCreatePatientDialog || patientToEdit != null) {
        val editing = patientToEdit
        val closePatientDialog = {
            showCreatePatientDialog = false
            patientToEdit = null
        }
        var pName by remember(editing) { mutableStateOf(editing?.name ?: "") }
        var pMrn by remember(editing) { mutableStateOf(editing?.medicalId ?: "") }
        var pPhone by remember(editing) { mutableStateOf(editing?.phone ?: "") }
        var pAge by remember(editing) { mutableStateOf(editing?.age ?: "") }
        var pInsurance by remember(editing) { mutableStateOf(editing?.insurance ?: "") }
        var pNotes by remember(editing) { mutableStateOf(editing?.notes ?: "") }
        var customAnswersMap by remember(editing) {
            mutableStateOf<Map<String, String>>(
                editing?.customAnswersJson?.let { raw ->
                    try {
                        val o = JSONObject(raw)
                        o.keys().asSequence().associateWith { o.optString(it) }
                    } catch (e: Exception) {
                        emptyMap<String, String>()
                    }
                } ?: emptyMap()
            )
        }
        val duplicateMrn = pMrn.isNotBlank() && patients.any {
            it.uid != editing?.uid && it.medicalId.trim().equals(pMrn.trim(), ignoreCase = true)
        }

        // Dynamic checks for required fields
        var validationErrorText by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = {
                // Check if changes are unsaved, then throw alert warnings
                if (editing == null && (pName.isNotBlank() || pMrn.isNotBlank())) {
                    showDraftBackWarning = { closePatientDialog() }
                } else {
                    closePatientDialog()
                }
            },
            title = { Text(if (editing != null) "Edit Patient Profile" else "Enroll New EMR Profile") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Fill the required patient credentials below:", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    
                    OutlinedTextField(
                        value = pName,
                        onValueChange = { pName = it },
                        label = { Text("Full Name *") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = pMrn,
                        onValueChange = { pMrn = it },
                        label = { Text("Medical ID (MRN) *") },
                        placeholder = { Text("e.g. MRN-A3092") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        isError = duplicateMrn,
                        supportingText = if (duplicateMrn) {
                            { Text("Another patient already has this MRN.") }
                        } else null
                    )

                    // Dynamically active customizable fields
                    customIntakeFields.forEach { config ->
                        if (config.isActive) {
                            val isReqLabel = if (config.isRequired) " *" else ""
                            when (config.id) {
                                "phone" -> {
                                    OutlinedTextField(
                                        value = pPhone,
                                        onValueChange = { pPhone = it },
                                        label = { Text("${config.label}$isReqLabel") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                                "age" -> {
                                    OutlinedTextField(
                                        value = pAge,
                                        onValueChange = { pAge = it },
                                        label = { Text("${config.label}$isReqLabel") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                                "insurance" -> {
                                    OutlinedTextField(
                                        value = pInsurance,
                                        onValueChange = { pInsurance = it },
                                        label = { Text("${config.label}$isReqLabel") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                                "notes" -> {
                                    OutlinedTextField(
                                        value = pNotes,
                                        onValueChange = { pNotes = it },
                                        label = { Text("${config.label}$isReqLabel") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                                else -> {
                                    val currentValue = customAnswersMap[config.id] ?: ""
                                    OutlinedTextField(
                                        value = currentValue,
                                        onValueChange = { newVal ->
                                            customAnswersMap = customAnswersMap.toMutableMap().apply {
                                                put(config.id, newVal)
                                            }
                                        },
                                        label = { Text("${config.label}$isReqLabel") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (validationErrorText != null) {
                        Text(
                            text = validationErrorText!!,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Check validation matching required dynamic configs
                        var missingField: String? = null
                        if (pName.isBlank()) missingField = "Full Name"
                        else if (pMrn.isBlank()) missingField = "Medical ID (MRN)"
                        else {
                            customIntakeFields.forEach { config ->
                                if (config.isActive && config.isRequired) {
                                    when (config.id) {
                                        "phone" -> if (pPhone.isBlank()) missingField = config.label
                                        "age" -> if (pAge.isBlank()) missingField = config.label
                                        "insurance" -> if (pInsurance.isBlank()) missingField = config.label
                                        "notes" -> if (pNotes.isBlank()) missingField = config.label
                                        else -> if ((customAnswersMap[config.id] ?: "").isBlank()) missingField = config.label
                                    }
                                }
                            }
                        }

                        if (missingField != null) {
                            validationErrorText = "Required: '$missingField' cannot be blank."
                        } else {
                            validationErrorText = null
                            
                            val answersJsonStr = if (customAnswersMap.isEmpty()) null else {
                                org.json.JSONObject().apply {
                                    customAnswersMap.forEach { (k, v) ->
                                        if (v.isNotBlank()) {
                                            put(k, v.trim())
                                        }
                                    }
                                }.toString()
                            }

                            if (editing != null) {
                                viewModel.updatePatient(
                                    editing.copy(
                                        name = pName.trim(),
                                        medicalId = pMrn.trim(),
                                        phone = pPhone.ifBlank { null },
                                        age = pAge.ifBlank { null },
                                        insurance = pInsurance.ifBlank { null },
                                        notes = pNotes.ifBlank { null },
                                        customAnswersJson = answersJsonStr
                                    )
                                )
                            } else {
                                viewModel.addPatientDetailed(
                                    name = pName,
                                    medicalId = pMrn,
                                    phone = pPhone.ifBlank { null },
                                    age = pAge.ifBlank { null },
                                    insurance = pInsurance.ifBlank { null },
                                    notes = pNotes.ifBlank { null },
                                    customAnswers = answersJsonStr
                                )
                            }
                            closePatientDialog()
                        }
                    }
                ) {
                    Text(if (editing != null) "Save Changes" else "Enroll Patient")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (editing == null && (pName.isNotBlank() || pMrn.isNotBlank())) {
                            showDraftBackWarning = { closePatientDialog() }
                        } else {
                            closePatientDialog()
                        }
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- DIALOG: CREATE TEMPLATE BLUEPRINT ---
    if (showCreateTemplateDialog) {
        var templateName by remember { mutableStateOf("") }
        var validationErrorText by remember { mutableStateOf<String?>(null) }

        // Free tier limiting templates rule check
        val canCreate = remember(templates, subscriptionTier) {
            SubscriptionManager.canCreateTemplate(templates.size)
        }

        AlertDialog(
            onDismissRequest = { showCreateTemplateDialog = false },
            title = { Text("New Template") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!canCreate) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Free limit reached (Max 3 blueprints). Upgrade to Premium for unlimited templates drafting.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = templateName,
                        onValueChange = { templateName = it },
                        label = { Text("Template Title") },
                        placeholder = { Text("e.g. ICU Critical Check") },
                        enabled = canCreate,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (validationErrorText != null) {
                        Text(text = validationErrorText!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (templateName.isNotBlank()) {
                            viewModel.addTemplate(templateName)
                            showCreateTemplateDialog = false
                        } else {
                            validationErrorText = "Enter template name."
                        }
                    },
                    enabled = canCreate
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateTemplateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- BOTTOM SHEET: POPULATE LOG ANSWERS DETAILS ---
    if (selectedEntryForView != null) {
        val entry = selectedEntryForView!!
        val answersMap = remember(entry) {
            val map = mutableMapOf<String, String>()
            try {
                val json = JSONObject(entry.answersJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = json.optString(key)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            map
        }

        val attributionsMap = remember(entry) {
            val map = mutableMapOf<String, com.example.data.model.ProcedureAttributionData>()
            try {
                if (!entry.attributionsJson.isNullOrBlank()) {
                    val json = JSONObject(entry.attributionsJson)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val sObj = json.optJSONObject(key)
                        if (sObj != null) {
                            map[key] = com.example.data.model.ProcedureAttributionData(
                                fieldId = sObj.optString("fieldId"),
                                providerType = sObj.optString("providerType", "Student"),
                                providerName = sObj.optString("providerName"),
                                providerId = sObj.optString("providerId"),
                                supervisorWitness = sObj.optString("supervisorWitness"),
                                isWitnessVerified = sObj.optBoolean("isWitnessVerified", false)
                            )
                        }
                    }
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
            map
        }

        val procedureItems = remember(entry, answersMap) {
            answersMap.values.flatMap { com.example.data.model.ProcedureAnswer.completedItems(it) }
        }
        val proceduresIPerformed = procedureItems.filter { it.performedByMe }.map { it.name }
        val otherProcedures = procedureItems.filter { !it.performedByMe }.map { it.name }

        val normalAnswers = remember(entry, answersMap) {
            val list = mutableListOf<Triple<String, String, String>>() // fieldId, label, display value
            for ((fieldId, answer) in answersMap) {
                if (com.example.data.model.ProcedureAnswer.isProcedure(answer) || answer.isBlank()) {
                    continue
                }
                val label = fieldLabelMap[fieldId] ?: fieldId.split("_").joinToString(" ") { fieldPart ->
                    fieldPart.replaceFirstChar { charPart ->
                        if (charPart.isLowerCase()) charPart.titlecase(java.util.Locale.getDefault()) else charPart.toString()
                    }
                }
                val display = when (answer) {
                    "true" -> "Yes"
                    "false" -> "No"
                    else -> answer
                }
                list.add(Triple(fieldId, label, display))
            }
            list.sortedBy { fieldOrder[it.first] ?: Int.MAX_VALUE }
        }

        ModalBottomSheet(
            onDismissRequest = { selectedEntryForView = null },
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Filed: ${java.text.SimpleDateFormat("MMM d, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.createdAt))} • Blueprint: ${entry.templateName}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    IconButton(onClick = { selectedEntryForView = null }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider()

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = entry.patientName ?: "General Theatre", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            val linkedPatient = patients.find { it.uid == entry.patientId }
                            Text(
                                text = if (linkedPatient != null) "MRN: ${linkedPatient.medicalId}" else "No patient profile linked",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                Text(
                    text = "RECORDED ASSESSMENT ANSWERS & ATTRIBUTION:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )

                if (answersMap.isEmpty()) {
                    Text("No answers saved inside log checks.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // 1. General Case Details
                        if (normalAnswers.isNotEmpty()) {
                            Text(
                                text = "General Case Details",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                            for ((fieldId, formattedLabel, answer) in normalAnswers) {
                                val attrItem = attributionsMap[fieldId]

                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = formattedLabel,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.secondary,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = answer,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        if (attrItem != null) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.AccountBox,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = listOfNotNull(
                                                            attrItem.providerName.takeIf { it.isNotBlank() }?.let { "By: $it${if (attrItem.providerType.isNotBlank()) " (${attrItem.providerType})" else ""}" },
                                                            attrItem.supervisorWitness.takeIf { it.isNotBlank() }?.let { "Witness: $it${if (attrItem.isWitnessVerified) " ✓" else ""}" }
                                                        ).joinToString(" | ").ifBlank { "No attribution" },
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Surgeries / Procedures Performed Section
                        if (proceduresIPerformed.isNotEmpty()) {
                            Text(
                                text = "Procedures I Performed",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                            )
                            proceduresIPerformed.forEach { proc ->
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = "• $proc", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        // Supervision recorded on procedure questions
                        val supervisionLines = attributionsMap.filter { (key, a) ->
                            a.supervisorWitness.isNotBlank() && com.example.data.model.ProcedureAnswer.isProcedure(answersMap[key] ?: "")
                        }.map { (key, a) ->
                            "${fieldLabelMap[key] ?: "Procedure"}: ${a.supervisorWitness}${if (a.isWitnessVerified) " ✓ verified" else " (not verified)"}"
                        }
                        if (supervisionLines.isNotEmpty()) {
                            Text(
                                text = "Supervisor / Witness",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                            )
                            supervisionLines.forEach { line ->
                                Text(text = "• $line", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                            }
                        }

                        // 3. Other Procedures Section
                        if (otherProcedures.isNotEmpty()) {
                            Text(
                                text = "Other Procedures",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                            )
                            otherProcedures.forEach { proc ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = "• $proc", fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                
                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val matchedTemplate = templates.find { it.uid == entry.templateId }
                    if (matchedTemplate != null) {
                        Button(
                            onClick = {
                                viewModel.selectEntryForEdit(entry)
                                selectedEntryForView = null
                                onNavigateToSolve(matchedTemplate)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Edit Log Entry", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (matchedTemplate != null) {
                        OutlinedButton(
                            onClick = {
                                viewModel.startNewEntryFrom(entry)
                                selectedEntryForView = null
                                onNavigateToSolve(matchedTemplate)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Repeat as New Entry", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            selectedEntryForAiSummary = entry
                            selectedEntryForView = null // Close sheet
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("AI Summary", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        com.example.data.service.ExportService.generatePdf(context, listOf(entry), fieldLabelMap, fieldOrder)
                                    }
                                    com.example.data.service.ExportService.shareFile(context, file, "application/pdf")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share PDF", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        com.example.data.service.ExportService.generateCsv(context, listOf(entry), fieldLabelMap, fieldOrder)
                                    }
                                    com.example.data.service.ExportService.shareFile(context, file, "text/csv")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            logToDelete = entry
                            selectedEntryForView = null // Close details sheet
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete Log", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showPremiumUpsellDialog) {
        AlertDialog(
            onDismissRequest = { showPremiumUpsellDialog = false },
            title = { Text("⚡ LogiMind Plan") },
            text = {
                Text(
                    if (subscriptionTier == SubscriptionTier.PREMIUM) {
                        "All features are unlocked on this device."
                    } else {
                        "Premium features are not available in this version."
                    }
                )
            },
            confirmButton = {
                Button(onClick = { showPremiumUpsellDialog = false }) {
                    Text("Dismiss")
                }
            }
        )
    }

    if (showProceduresDashboard) {
        val myProceduresStatistics = remember(entries, fieldLabelMap) {
            val list = mutableListOf<MyProcedureCase>()
            for (entry in entries) {
                if (entry.answersJson.isBlank()) continue
                try {
                    val obj = JSONObject(entry.answersJson)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val valStr = obj.optString(key)
                        if (!com.example.data.model.ProcedureAnswer.isProcedure(valStr)) continue
                        // Group by the question's current label so renaming it does not split counts.
                        val storedLabel = JSONObject(valStr).optString("procedure")
                        val currentLabel = fieldLabelMap[key] ?: storedLabel
                        for (item in com.example.data.model.ProcedureAnswer.completedItems(valStr)) {
                            if (!item.performedByMe) continue
                            list.add(
                                MyProcedureCase(
                                    procedureName = currentLabel + item.name.removePrefix(storedLabel),
                                    entry = entry,
                                    date = entry.createdAt,
                                    patientName = entry.patientName ?: "Theatre",
                                    templateName = entry.templateName ?: "Log Checklist"
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            list
        }

        val reportRows = remember(entries, fieldLabelMap) {
            entries.flatMap { entry ->
                try {
                    val obj = JSONObject(entry.answersJson)
                    obj.keys().asSequence().flatMap { key ->
                        val valStr = obj.optString(key)
                        if (!com.example.data.model.ProcedureAnswer.isProcedure(valStr)) return@flatMap emptySequence()
                        val storedLabel = JSONObject(valStr).optString("procedure")
                        val currentLabel = fieldLabelMap[key] ?: storedLabel
                        com.example.data.model.ProcedureAnswer.completedItems(valStr).asSequence().map { item ->
                            com.example.data.service.ExportService.ProcedureCaseRow(
                                procedure = currentLabel + item.name.removePrefix(storedLabel),
                                date = entry.createdAt,
                                patientName = entry.patientName ?: "Theatre",
                                templateName = entry.templateName ?: "Log Checklist",
                                performedByMe = item.performedByMe
                            )
                        }
                    }.toList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

        var searchProcedureQuery by remember { mutableStateOf("") }
        var searchPatientQuery by remember { mutableStateOf("") }
        var selectedFilterTemplateId by remember { mutableStateOf("") }
        var selectedDateRangeDayLimit by remember { mutableStateOf<Int?>(null) } // null = All, 7 = Past 7, 30 = Past 30

        val dayLimit = selectedDateRangeDayLimit
        val filteredProcedures = remember(myProceduresStatistics, searchProcedureQuery, searchPatientQuery, selectedFilterTemplateId, dayLimit) {
            val now = System.currentTimeMillis()
            myProceduresStatistics.filter { proc ->
                val matchProc = proc.procedureName.contains(searchProcedureQuery, ignoreCase = true)
                val matchPat = proc.patientName.contains(searchPatientQuery, ignoreCase = true)
                val matchTemp = if (selectedFilterTemplateId.isBlank()) true else proc.entry.templateId == selectedFilterTemplateId
                val matchDate = if (dayLimit == null) true else {
                    val limitMs = dayLimit.toLong() * 24L * 60L * 60L * 1000L
                    (now - proc.date) <= limitMs
                }
                matchProc && matchPat && matchTemp && matchDate
            }
        }

        val filteredAggregated = remember(filteredProcedures) {
            filteredProcedures.groupBy { it.procedureName }
                .map { (name, list) ->
                    name to list
                }
                .sortedByDescending { it.second.size }
        }

        androidx.compose.ui.window.Dialog(
            onDismissRequest = { 
                showProceduresDashboard = false
                selectedDashboardProcedureName = null
            },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = true
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Header block
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = {
                                    if (selectedDashboardProcedureName != null) {
                                        selectedDashboardProcedureName = null
                                    } else {
                                        showProceduresDashboard = false
                                    }
                                }
                            ) {
                                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                            }
                            Text(
                                text = if (selectedDashboardProcedureName != null) selectedDashboardProcedureName!! else "My Procedures Dashboard",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            IconButton(onClick = { 
                                showProceduresDashboard = false 
                                selectedDashboardProcedureName = null
                            }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Close Dashboard")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (selectedDashboardProcedureName == null) {
                            // --- MAIN DASHBOARD VIEW ---
                            
                            // SEARCH AND FILTERS CARD
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Filter & Search Cases", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    
                                    // Search by Procedure Name
                                    OutlinedTextField(
                                        value = searchProcedureQuery,
                                        onValueChange = { searchProcedureQuery = it },
                                        placeholder = { Text("Search by surgery/test, e.g. Appendectomy") },
                                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )

                                    // Search by Patient
                                    OutlinedTextField(
                                        value = searchPatientQuery,
                                        onValueChange = { searchPatientQuery = it },
                                        placeholder = { Text("Search by Patient Name") },
                                        leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )

                                    // Quick Filters row (Date limit)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(null to "All Time", 7 to "Past 7d", 30 to "Past 30d").forEach { (days, label) ->
                                            FilterChip(
                                                selected = selectedDateRangeDayLimit == days,
                                                onClick = { selectedDateRangeDayLimit = days },
                                                label = { Text(label, fontSize = 11.sp) }
                                            )
                                        }
                                    }

                                    // Filter by Template dropdown
                                    var templateDropdownExpanded by remember { mutableStateOf(false) }
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = { templateDropdownExpanded = true },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            val tName = templates.find { it.uid == selectedFilterTemplateId }?.name ?: "All Blueprints / Templates"
                                            Text(tName, fontSize = 11.sp)
                                        }
                                        DropdownMenu(
                                            expanded = templateDropdownExpanded,
                                            onDismissRequest = { templateDropdownExpanded = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("All Blueprints / Templates") },
                                                onClick = {
                                                    selectedFilterTemplateId = ""
                                                    templateDropdownExpanded = false
                                                }
                                            )
                                            templates.forEach { t ->
                                                DropdownMenuItem(
                                                    text = { Text(t.name) },
                                                    onClick = {
                                                        selectedFilterTemplateId = t.uid
                                                        templateDropdownExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            com.example.data.service.ExportService.generateProcedureReport(context, reportRows, "LogiMind Procedure Logbook")
                                        }
                                        com.example.data.service.ExportService.shareFile(context, file, "application/pdf")
                                    }
                                },
                                enabled = reportRows.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export Procedure Report (PDF)", fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Aggregated Case Counts (${filteredAggregated.size} unique procedures)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            if (filteredAggregated.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("No matching procedures found.", color = MaterialTheme.colorScheme.secondary)
                                }
                            } else {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(filteredAggregated) { pair ->
                                        val name = pair.first
                                        val cases = pair.second
                                        Card(
                                            onClick = { selectedDashboardProcedureName = name },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                    Text("${cases.size} recorded case(s)", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                                }
                                                Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = "Drill Down")
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // --- DRILL DOWN PROCEDURE CASES DETAIL VIEW ---
                            val procName = selectedDashboardProcedureName!!
                            val procedureCases = remember(filteredProcedures, procName) {
                                filteredProcedures.filter { it.procedureName == procName }
                            }

                            // Show only recent cases first if requested, and then show "View All Cases" option!
                            var showAllByMeCases by remember { mutableStateOf(false) }
                            val displayedCases = if (showAllByMeCases) procedureCases else procedureCases.take(5)

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "Aggregated Cases for: $procName (${procedureCases.size} total case logs)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )

                                if (displayedCases.isEmpty()) {
                                    Text("No cases found matching filters.", color = MaterialTheme.colorScheme.secondary)
                                } else {
                                    displayedCases.forEach { case ->
                                        val sdf = remember { java.text.SimpleDateFormat("MMM d, yyyy HH:mm", java.util.Locale.getDefault()) }
                                        val dateStr = sdf.format(java.util.Date(case.date))
                                        
                                        Card(
                                            onClick = {
                                                selectedEntryForView = case.entry
                                            },
                                            modifier = Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)), shape = RoundedCornerShape(10.dp)),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(case.patientName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                    Text(dateStr, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text("Filed under: Blueprint ${case.templateName}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "Tap to open original clinical entry log Check • MRN",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                        }
                                    }

                                    if (!showAllByMeCases && procedureCases.size > 5) {
                                        TextButton(
                                            onClick = { showAllByMeCases = true },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("View All Cases (${procedureCases.size})", fontWeight = FontWeight.Bold)
                                        }
                                    } else if (showAllByMeCases && procedureCases.size > 5) {
                                        TextButton(
                                            onClick = { showAllByMeCases = false },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Collapse to Recent Cases")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

@Composable
fun LargeChoiceTile(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badge: String? = null,
    tintColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .heightIn(min = 145.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp
        ),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.Start
        ) {

            // TOP ICON
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(tintColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = tintColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            // TEXT SECTION
            Column {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = description,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (badge != null) {
                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = tintColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = badge,
                            modifier = Modifier.padding(
                                horizontal = 8.dp,
                                vertical = 2.dp
                            ),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = tintColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DemographicRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

// Picker dates are UTC midnight of the chosen day; entries are compared by their local calendar day.
fun isWithinDayRange(createdAt: Long, startPickerMs: Long?, endPickerMs: Long?): Boolean {
    fun dayKey(cal: java.util.Calendar) =
        cal.get(java.util.Calendar.YEAR) * 10000 + (cal.get(java.util.Calendar.MONTH) + 1) * 100 + cal.get(java.util.Calendar.DAY_OF_MONTH)
    fun pickerDay(ms: Long) = dayKey(java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms })
    val entryDay = dayKey(java.util.Calendar.getInstance().apply { timeInMillis = createdAt })
    if (startPickerMs != null && entryDay < pickerDay(startPickerMs)) return false
    if (endPickerMs != null && entryDay > pickerDay(endPickerMs)) return false
    return true
}

// Label for a saved intake answer whose question config is gone (configs were not saved before v1.1).
fun intakeKeyFallbackLabel(key: String): String = when (key) {
    "allergies" -> "Drug Allergy Detail History"
    "bloodGroup" -> "Patient ABO Blood Type"
    else -> if (key.startsWith("custom_intake_")) "Custom Question" else key
}

// Helpers for modifier scale
private fun Modifier.scale(scale: Float): Modifier = this

data class MyProcedureCase(
    val procedureName: String,
    val entry: FormEntry,
    val date: Long,
    val patientName: String,
    val templateName: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarDatePickerButton(
    label: String,
    selectedTimeMs: Long?,
    onDateSelected: (Long?) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    val formattedDate = remember(selectedTimeMs) {
        if (selectedTimeMs != null) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.format(java.util.Date(selectedTimeMs))
        } else {
            "Select Date"
        }
    }

    Box(modifier = modifier) {
        Surface(
            onClick = { showDialog = true },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DateRange,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = formattedDate,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedTimeMs != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    if (selectedTimeMs != null) {
                        IconButton(
                            onClick = {
                                onClear()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = "Clear date",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        if (showDialog) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = selectedTimeMs ?: System.currentTimeMillis()
            )
            DatePickerDialog(
                onDismissRequest = { showDialog = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDateSelected(datePickerState.selectedDateMillis)
                            showDialog = false
                        }
                    ) {
                        Text("Select", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.secondary)
                    }
                }
            ) {
                DatePicker(
                    state = datePickerState,
                    colors = DatePickerDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                        headlineContentColor = MaterialTheme.colorScheme.onSurface,
                        weekdayContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        subheadContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        navigationContentColor = MaterialTheme.colorScheme.primary,
                        yearContentColor = MaterialTheme.colorScheme.onSurface,
                        selectedYearContentColor = MaterialTheme.colorScheme.onPrimary,
                        selectedYearContainerColor = MaterialTheme.colorScheme.primary,
                        dayContentColor = MaterialTheme.colorScheme.onSurface,
                        selectedDayContainerColor = MaterialTheme.colorScheme.primary,
                        selectedDayContentColor = MaterialTheme.colorScheme.onPrimary,
                        todayContentColor = MaterialTheme.colorScheme.primary,
                        todayDateBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

