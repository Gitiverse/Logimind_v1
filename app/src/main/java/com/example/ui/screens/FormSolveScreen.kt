@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.example.ui.screens

import android.widget.Space
import androidx.compose.animation.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.ui.viewmodel.FormBuilderViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FormSolveScreen(
    viewModel: FormBuilderViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val template by viewModel.activeTemplate.collectAsState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val sections by viewModel.activeSections.collectAsState()
    val fields by viewModel.activeFields.collectAsState()
    val optionsMap by viewModel.activeFieldOptions.collectAsState()
    val solveAnswers by viewModel.solveAnswers.collectAsState()
    val solveAttributions by viewModel.solveAttributions.collectAsState()
    val patients by viewModel.patients.collectAsState()

    val editingEntryUid by viewModel.editingEntryUid.collectAsState()
    val editingEntryTitle by viewModel.editingEntryTitle.collectAsState()

    val wizardPatient by viewModel.wizardSelectedPatient.collectAsState()
    var selectedPatient by remember(wizardPatient) { mutableStateOf(wizardPatient) }
    var entryTitle by remember(editingEntryTitle) { mutableStateOf(editingEntryTitle) }
    var showSuccessToast by remember { mutableStateOf(false) }
    var savedAsUpdate by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val isSaving by viewModel.isSavingEntry.collectAsState()
    val isLoadingEntry by viewModel.isLoadingEntry.collectAsState()
    var validationErrorText by remember { mutableStateOf<String?>(null) }

    val initialAnswers by viewModel.initialAnswers.collectAsState()
    val initialAttributions by viewModel.initialAttributions.collectAsState()
    val initialPatient by viewModel.initialPatient.collectAsState()
    val initialTitle by viewModel.initialTitle.collectAsState()

    val hasBeenEdited = remember(
        solveAnswers, initialAnswers,
        solveAttributions, initialAttributions,
        selectedPatient, initialPatient,
        entryTitle, initialTitle
    ) {
        val answersChanged = solveAnswers != initialAnswers
        val attributionsChanged = solveAttributions != initialAttributions
        val patientChanged = (selectedPatient?.uid ?: "") != (initialPatient?.uid ?: "")
        val titleChanged = entryTitle != initialTitle
        
        answersChanged || attributionsChanged || patientChanged || titleChanged
    }

    // Drafts: unsaved new entries are kept so they survive the app closing.
    LaunchedEffect(entryTitle, selectedPatient) {
        viewModel.onDraftMetaChanged(entryTitle, selectedPatient?.uid)
    }
    var draftOfferedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(template?.uid, isLoadingEntry) {
        val t = template ?: return@LaunchedEffect
        if (!isLoadingEntry && editingEntryUid == null && solveAnswers.isEmpty()) {
            draftOfferedAt = viewModel.draftSavedAt(t.uid)
        }
    }

    // --- Safe Unsaved Exit Warning state ---
    var showSaveConfirmationDialog by remember { mutableStateOf(false) }

    if (template == null || isLoadingEntry) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isLoadingEntry) CircularProgressIndicator() else Text("No Blueprint Available")
        }
        return
    }

    val handleBackClick = {
        if (hasBeenEdited) {
            showSaveConfirmationDialog = true
        } else {
            onNavigateBack()
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        handleBackClick()
    }

    // --- Dynamic Conditional Engine Algorithm ---
    // Evaluates what fields are currently visible based on answers
    val visibleFieldsMap = remember(solveAnswers, fields) {
        val visibleIds = ConditionalLogic.visibleFieldUids(fields.values.flatten(), solveAnswers)
        fields.mapValues { (_, fList) -> fList.filter { it.uid in visibleIds } }
    }

    // --- Dynamic Progress Metrics ---
    val progressMetrics = remember(solveAnswers, visibleFieldsMap) {
        val allVisibleFields = visibleFieldsMap.values.flatten()
        val totalCount = allVisibleFields.size
        val answeredCount = allVisibleFields.count { isAnswered(solveAnswers[it.uid]) }

        val percent = if (totalCount > 0) (answeredCount.toFloat() / totalCount.toFloat()) else 0f
        Triple(answeredCount, totalCount, percent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (editingEntryUid != null) "Edit Record Intake" else "Solve Record Intake",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Filling: ${template!!.name}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = handleBackClick) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Clear Answers", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                modifier = Modifier.border(width = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            )
        }
    ) { paddingValues ->
        if (showSuccessToast) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Card(
                    modifier = Modifier.padding(32.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (savedAsUpdate) "Updated Successfully!" else "Submitted Successfully!",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (savedAsUpdate) "Record updated in local EMR history database." else "Record filed into local EMR history database.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            showSuccessToast = false
                            onNavigateBack()
                        }) {
                            Text("Done")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {
                        focusManager.clearFocus()
                    },
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                // --- PROGRESS STATS BAR ---
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), shape = RoundedCornerShape(12.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Intake Form Process Progress",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${progressMetrics.first}/${progressMetrics.second} completed",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progressMetrics.third },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            )
                        }
                    }
                }

                // --- RECORD SETUP CARD ---
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)), shape = RoundedCornerShape(12.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(11.dp)
                        ) {
                            Text("1. Record Setup & Patient Link", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            
                            Text("Bind Patient Profile:", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)

                            var patientListExpanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { patientListExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {
                                    Text(selectedPatient?.name ?: "Tap to Link Clinical Patient profile...")
                                }
                                DropdownMenu(
                                    expanded = patientListExpanded,
                                    onDismissRequest = { patientListExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Save as general/walk-in patient") },
                                        onClick = {
                                            selectedPatient = null
                                            patientListExpanded = false
                                        }
                                    )
                                    patients.forEach { p ->
                                        DropdownMenuItem(
                                            text = { Text("${p.name} (${p.medicalId})") },
                                            onClick = {
                                                selectedPatient = p
                                                patientListExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            
                            Text("Custom Log Name / Title:", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                            
                            OutlinedTextField(
                                value = entryTitle,
                                onValueChange = { entryTitle = it },
                                placeholder = { Text("e.g. Session 1, Initial Assessment, etc. (Optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true,
                                trailingIcon = {
                                    if (entryTitle.isNotEmpty()) {
                                        IconButton(onClick = { entryTitle = "" }) {
                                            Icon(Icons.Rounded.Clear, contentDescription = "Clear text")
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )
                        }
                    }
                }

                // --- METRIC SECTIONS ---
                sections.forEach { section ->
                    val sectionFields = visibleFieldsMap[section.uid] ?: emptyList()
                    if (sectionFields.isNotEmpty()) {
                        item {
                            Text(
                                text = section.title.uppercase(),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                letterSpacing = 0.5.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                            )
                        }

                        items(sectionFields, key = { it.uid }) { field ->
                            SolveFieldCard(
                                field = field,
                                options = optionsMap[field.uid] ?: emptyList(),
                                currentAnswer = solveAnswers[field.uid] ?: "",
                                onUpdateAnswer = { valStr -> viewModel.updateAns(field.uid, valStr) },
                                attribution = solveAttributions[field.uid],
                                onAttributionChange = { viewModel.updateProcedureAttribution(field.uid, it) }
                            )
                        }
                    }
                }

                // --- SAVE SUBMIT TRIGGER ---
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (validationErrorText != null) {
                        Text(
                            text = validationErrorText!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )
                    }

                    Button(
                        onClick = {
                            // Run required field validation engine check
                            val allVisibleFields = visibleFieldsMap.values.flatten()
                            val missingRequired = allVisibleFields.find { 
                                it.isRequired && !isAnswered(solveAnswers[it.uid])
                            }

                            if (missingRequired != null) {
                                validationErrorText = "Required answer missing: '${missingRequired.label}'"
                            } else {
                                validationErrorText = null
                                savedAsUpdate = editingEntryUid != null
                                viewModel.saveEntry(
                                    template = template!!,
                                    patient = selectedPatient,
                                    title = entryTitle,
                                    onSuccess = {
                                        showSuccessToast = true
                                    }
                                )
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (editingEntryUid != null) "Update & Save Checklist" else "Commit & Submit Checklist",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showSaveConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showSaveConfirmationDialog = false },
            title = { Text("Save changes before leaving?", fontWeight = FontWeight.Bold) },
            text = { Text("You have unsaved entries in this active clinical session. Discarding will clear filled values.") },
            confirmButton = {
                Button(
                    onClick = {
                        showSaveConfirmationDialog = false
                        // Same required-field check as the main save button; never leave without saving.
                        val missingRequired = visibleFieldsMap.values.flatten().find {
                            it.isRequired && !isAnswered(solveAnswers[it.uid])
                        }
                        if (missingRequired != null) {
                            validationErrorText = "Required answer missing: '${missingRequired.label}'"
                        } else {
                            viewModel.saveEntry(
                                template = template!!,
                                patient = selectedPatient,
                                title = entryTitle.ifBlank { "${template!!.name} Record" },
                                onSuccess = {
                                    onNavigateBack()
                                }
                            )
                        }
                    },
                    enabled = !isSaving
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            showSaveConfirmationDialog = false
                            template?.let { viewModel.discardDraft(it.uid) }
                            viewModel.clearSolveAnswers()
                            onNavigateBack()
                        }
                    ) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { showSaveConfirmationDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    draftOfferedAt?.let { savedAt ->
        AlertDialog(
            onDismissRequest = { draftOfferedAt = null },
            title = { Text("Resume unsaved entry?") },
            text = {
                Text(
                    "You started this form on " +
                        java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(savedAt)) +
                        " without saving it. Continue where you left off?"
                )
            },
            confirmButton = {
                Button(onClick = {
                    template?.let { viewModel.restoreDraft(it.uid) }
                    draftOfferedAt = null
                }) {
                    Text("Resume")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    template?.let { viewModel.discardDraft(it.uid) }
                    draftOfferedAt = null
                }) {
                    Text("Start fresh")
                }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all answers?") },
            text = { Text("All answers filled in this form will be removed.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAnswersOnly()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// An unticked checkbox ("false" / {"completed":false}) is not an answer for required checks.
private fun isAnswered(value: String?): Boolean {
    if (value.isNullOrBlank() || value == "false") return false
    if (value.startsWith("{")) {
        return try {
            val obj = org.json.JSONObject(value)
            if (obj.has("choices")) obj.optString("choices").isNotBlank() else obj.optBoolean("completed", false)
        } catch (e: Exception) {
            true
        }
    }
    return true
}

@Composable
fun SolveFieldCard(
    field: FormField,
    options: List<FormFieldOption>,
    currentAnswer: String,
    onUpdateAnswer: (String) -> Unit,
    attribution: ProcedureAttributionData? = null,
    onAttributionChange: (ProcedureAttributionData) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val standardOptions = remember(options) { options.filter { !it.isAdvanced } }
    val advancedOptions = remember(options) { options.filter { it.isAdvanced } }
    var isMinimized by remember(field.uid) { mutableStateOf(field.startMinimized) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)), shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isMinimized = !isMinimized }
                    .padding(vertical = 4.dp)
            ) {
                Icon(
                    imageVector = if (isMinimized) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                    contentDescription = if (isMinimized) "Expand Question" else "Minimize Question",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = field.label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (field.isRequired) {
                    Text(
                        text = "* REQUIRED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            if (!field.hint.isNullOrBlank() && !isMinimized) {
                Text(
                    text = field.hint,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 32.dp)
                )
            }

            if (isMinimized) {
                val displayText = remember(currentAnswer, field.type) {
                    if (currentAnswer.isBlank()) {
                        "Tap to expand and configure"
                    } else if (currentAnswer.startsWith("{")) {
                        try {
                            val obj = org.json.JSONObject(currentAnswer)
                            if (obj.optBoolean("completed", false)) {
                                "Completed (Performed: ${if (obj.optBoolean("performedByMe", false)) "Yes" else "No"})"
                            } else {
                                "Not Completed"
                            }
                        } catch (e: Exception) {
                            currentAnswer
                        }
                    } else {
                        currentAnswer
                    }
                }
                
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isMinimized = false }
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = displayText,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                when (field.type) {
                "checkbox" -> {
                    val isChecked = remember(currentAnswer) {
                        if (field.isProcedure) {
                            try {
                                if (currentAnswer.startsWith("{")) {
                                    org.json.JSONObject(currentAnswer).optBoolean("completed", false)
                                } else {
                                    currentAnswer.toBoolean()
                                }
                            } catch (e: Exception) {
                                false
                            }
                        } else {
                            currentAnswer.toBoolean()
                        }
                    }
                    // null = not answered yet; stays out of "Procedures I Performed" until answered.
                    val performedByMe: Boolean? = remember(currentAnswer) {
                        if (field.isProcedure) {
                            try {
                                if (currentAnswer.startsWith("{")) {
                                    val obj = org.json.JSONObject(currentAnswer)
                                    if (obj.has("performedByMe")) obj.optBoolean("performedByMe", false) else null
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newChecked = !isChecked
                                    if (field.isProcedure) {
                                        val obj = org.json.JSONObject()
                                        obj.put("procedure", field.label)
                                        obj.put("completed", newChecked)
                                        // Not asked: keep the previous default (false). Asked: only once answered.
                                        if (!field.askIfPerformedByMe) obj.put("performedByMe", performedByMe ?: false)
                                        else if (performedByMe != null) obj.put("performedByMe", performedByMe)
                                        onUpdateAnswer(obj.toString())
                                    } else {
                                        onUpdateAnswer(newChecked.toString())
                                    }
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { newChecked ->
                                    if (field.isProcedure) {
                                        val obj = org.json.JSONObject()
                                        obj.put("procedure", field.label)
                                        obj.put("completed", newChecked)
                                        // Not asked: keep the previous default (false). Asked: only once answered.
                                        if (!field.askIfPerformedByMe) obj.put("performedByMe", performedByMe ?: false)
                                        else if (performedByMe != null) obj.put("performedByMe", performedByMe)
                                        onUpdateAnswer(obj.toString())
                                    } else {
                                        onUpdateAnswer(newChecked.toString())
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(field.label, fontSize = 13.sp)
                        }

                        if (field.isProcedure && field.askIfPerformedByMe && isChecked) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 36.dp, top = 4.dp, bottom = 4.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Did I perform this?",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable {
                                            val obj = org.json.JSONObject()
                                            obj.put("procedure", field.label)
                                            obj.put("completed", true)
                                            obj.put("performedByMe", true)
                                            onUpdateAnswer(obj.toString())
                                        }
                                    ) {
                                        RadioButton(
                                            selected = performedByMe == true,
                                            onClick = {
                                                val obj = org.json.JSONObject()
                                                obj.put("procedure", field.label)
                                                obj.put("completed", true)
                                                obj.put("performedByMe", true)
                                                onUpdateAnswer(obj.toString())
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Yes", fontSize = 12.sp)
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable {
                                            val obj = org.json.JSONObject()
                                            obj.put("procedure", field.label)
                                            obj.put("completed", true)
                                            obj.put("performedByMe", false)
                                            onUpdateAnswer(obj.toString())
                                        }
                                    ) {
                                        RadioButton(
                                            selected = performedByMe == false,
                                            onClick = {
                                                val obj = org.json.JSONObject()
                                                obj.put("procedure", field.label)
                                                obj.put("completed", true)
                                                obj.put("performedByMe", false)
                                                onUpdateAnswer(obj.toString())
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("No", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                 "text" -> {
                    OutlinedTextField(
                        value = currentAnswer,
                        onValueChange = onUpdateAnswer,
                        placeholder = { Text("Response text...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter) {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    true
                                } else {
                                    false
                                }
                            },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                "textarea" -> {
                    OutlinedTextField(
                        value = currentAnswer,
                        onValueChange = onUpdateAnswer,
                        placeholder = { Text("Spacious clinical notes...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        maxLines = 5,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                "number" -> {
                    OutlinedTextField(
                        value = currentAnswer,
                        onValueChange = { typed ->
                            // Deleting is always allowed so older free-text answers (e.g. "12 kg") can be corrected.
                            if (typed.length < currentAnswer.length || Regex("^-?\\d*[.,]?\\d*$").matches(typed)) onUpdateAnswer(typed)
                        },
                        placeholder = { Text("Numeric measurement...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter) {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    true
                                } else {
                                    false
                                }
                            },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                "dropdown" -> {
                    var expanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(currentAnswer.ifBlank { "Select Option Group..." })
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            if (currentAnswer.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text("Clear selection", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        onUpdateAnswer("")
                                        expanded = false
                                    }
                                )
                            }
                            options.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt.label) },
                                    onClick = {
                                        onUpdateAnswer(opt.label)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                "radio_group" -> {
                    var showAdvanced by remember { mutableStateOf(false) }
                    val isHorizontalLayout = field.defaultValue == "horizontal"

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isHorizontalLayout) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                standardOptions.forEach { opt ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clickable { onUpdateAnswer(opt.label) }
                                            .padding(vertical = 4.dp, horizontal = 4.dp)
                                    ) {
                                        RadioButton(
                                            selected = currentAnswer == opt.label,
                                            onClick = { onUpdateAnswer(opt.label) }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(opt.label, fontSize = 13.sp)
                                    }
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                standardOptions.forEach { opt ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onUpdateAnswer(opt.label) }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        RadioButton(
                                            selected = currentAnswer == opt.label,
                                            onClick = { onUpdateAnswer(opt.label) }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(opt.label, fontSize = 13.sp)
                                    }
                                }
                            }
                        }

                        if (advancedOptions.isNotEmpty()) {
                            AnimatedVisibility(
                                visible = showAdvanced,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                if (isHorizontalLayout) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        advancedOptions.forEach { opt ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .clickable { onUpdateAnswer(opt.label) }
                                                    .padding(vertical = 4.dp, horizontal = 4.dp)
                                            ) {
                                                RadioButton(
                                                    selected = currentAnswer == opt.label,
                                                    onClick = { onUpdateAnswer(opt.label) }
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = opt.label,
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        advancedOptions.forEach { opt ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { onUpdateAnswer(opt.label) }
                                                    .padding(vertical = 4.dp)
                                            ) {
                                                RadioButton(
                                                    selected = currentAnswer == opt.label,
                                                    onClick = { onUpdateAnswer(opt.label) }
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = opt.label,
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            TextButton(
                                onClick = { showAdvanced = !showAdvanced },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Icon(
                                    imageVector = if (showAdvanced) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (showAdvanced) "Show Less" else "Show Advanced Choices (${advancedOptions.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                "checkbox_group" -> {
                    // Checkbox is multiple choice. We encode as a JSON including selected choices and their individual performed statuses
                    val selectedSet = remember(currentAnswer) {
                        if (field.isProcedure) {
                            try {
                                if (currentAnswer.startsWith("{")) {
                                    val obj = org.json.JSONObject(currentAnswer)
                                    obj.optString("choices", "").split(",").filter { it.isNotBlank() }.toSet()
                                } else {
                                    currentAnswer.split(",").filter { it.isNotBlank() }.toSet()
                                }
                            } catch (e: Exception) {
                                emptySet()
                            }
                        } else {
                            currentAnswer.split(",").filter { it.isNotBlank() }.toSet()
                        }
                    }
                    val choicePerformedMap = remember(currentAnswer) {
                        val map = mutableMapOf<String, Boolean>() // missing key = not answered yet
                        if (field.isProcedure) {
                            try {
                                if (currentAnswer.startsWith("{")) {
                                    val obj = org.json.JSONObject(currentAnswer)
                                    val choicesPerf = obj.optJSONObject("choices_perf")
                                    if (choicesPerf != null) {
                                        val keys = choicesPerf.keys()
                                        while (keys.hasNext()) {
                                            val key = keys.next()
                                            map[key] = choicesPerf.optBoolean(key, false)
                                        }
                                    } else {
                                        val pByMe = obj.optBoolean("performedByMe", false)
                                        obj.optString("choices", "").split(",").forEach {
                                            if (it.isNotBlank()) map[it] = pByMe
                                        }
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                        map
                    }

                    val updateChoicePerformance: (String, Boolean) -> Unit = { choice, pByMe ->
                        if (field.isProcedure) {
                            try {
                                val obj = if (currentAnswer.startsWith("{")) {
                                    org.json.JSONObject(currentAnswer)
                                } else {
                                    org.json.JSONObject().apply {
                                        put("procedure", field.label)
                                        put("completed", selectedSet.isNotEmpty())
                                        put("choices", selectedSet.joinToString(","))
                                    }
                                }
                                val choicesPerf = obj.optJSONObject("choices_perf") ?: org.json.JSONObject()
                                choicesPerf.put(choice, pByMe)
                                obj.put("choices_perf", choicesPerf)
                                onUpdateAnswer(obj.toString())
                            } catch (e: Exception) {}
                        }
                    }

                    val updateGroupAnswer: (Set<String>) -> Unit = { newSet ->
                        if (field.isProcedure) {
                            try {
                                val obj = if (currentAnswer.startsWith("{")) {
                                    org.json.JSONObject(currentAnswer)
                                } else {
                                    org.json.JSONObject().apply {
                                        put("procedure", field.label)
                                    }
                                }
                                obj.put("completed", newSet.isNotEmpty())
                                obj.put("choices", newSet.joinToString(","))
                                
                                val choicesPerf = obj.optJSONObject("choices_perf") ?: org.json.JSONObject()
                                val newPerfObj = org.json.JSONObject()
                                for (choice in newSet) {
                                    if (choicesPerf.has(choice)) {
                                        newPerfObj.put(choice, choicesPerf.getBoolean(choice))
                                    } else if (!field.askIfPerformedByMe) {
                                        // Not asked: keep the previous default so existing logbooks count the same way.
                                        newPerfObj.put(choice, true)
                                    }
                                    // Asked: left unset until the user answers "Did I perform this?".
                                }
                                obj.put("choices_perf", newPerfObj)
                                onUpdateAnswer(obj.toString())
                            } catch (e: Exception) {}
                        } else {
                            onUpdateAnswer(newSet.joinToString(","))
                        }
                    }
                    var showAdvanced by remember { mutableStateOf(false) }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val isHorizontalLayout = field.defaultValue == "horizontal"

                    if (isHorizontalLayout) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            standardOptions.forEach { opt ->
                                CheckboxOptionItem(
                                    label = opt.label,
                                    isSelected = selectedSet.contains(opt.label),
                                    onCheckedChange = { isChecked ->
                                        val newSet = if (isChecked) selectedSet + opt.label else selectedSet - opt.label
                                        updateGroupAnswer(newSet)
                                    },
                                    isProcedure = field.isProcedure,
                                    askIfPerformedByMe = field.askIfPerformedByMe,
                                    choicePerfVal = choicePerformedMap[opt.label],
                                    onPerformanceChange = { perf -> updateChoicePerformance(opt.label, perf) },
                                    isHorizontal = true
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            standardOptions.forEach { opt ->
                                CheckboxOptionItem(
                                    label = opt.label,
                                    isSelected = selectedSet.contains(opt.label),
                                    onCheckedChange = { isChecked ->
                                        val newSet = if (isChecked) selectedSet + opt.label else selectedSet - opt.label
                                        updateGroupAnswer(newSet)
                                    },
                                    isProcedure = field.isProcedure,
                                    askIfPerformedByMe = field.askIfPerformedByMe,
                                    choicePerfVal = choicePerformedMap[opt.label],
                                    onPerformanceChange = { perf -> updateChoicePerformance(opt.label, perf) },
                                    isHorizontal = false
                                )
                            }
                        }
                    }


                        if (advancedOptions.isNotEmpty()) {
                            AnimatedVisibility(
                                visible = showAdvanced,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    advancedOptions.forEach { opt ->
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        val newSet = if (selectedSet.contains(opt.label)) {
                                                            selectedSet - opt.label
                                                        } else {
                                                            selectedSet + opt.label
                                                        }
                                                        updateGroupAnswer(newSet)
                                                    }
                                                    .padding(vertical = 4.dp)
                                            ) {
                                                Checkbox(
                                                    checked = selectedSet.contains(opt.label),
                                                    onCheckedChange = {
                                                        val newSet = if (it) {
                                                            selectedSet + opt.label
                                                        } else {
                                                            selectedSet - opt.label
                                                        }
                                                        updateGroupAnswer(newSet)
                                                    }
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = opt.label,
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }

                                            if (field.isProcedure && field.askIfPerformedByMe && selectedSet.contains(opt.label)) {
                                                val choicePerfVal = choicePerformedMap[opt.label]
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(start = 36.dp, bottom = 6.dp)
                                                        .background(
                                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                                            shape = RoundedCornerShape(6.dp)
                                                        )
                                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    Text(
                                                        "Did I perform this?",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.clickable {
                                                            updateChoicePerformance(opt.label, true)
                                                        }
                                                    ) {
                                                        RadioButton(
                                                            selected = choicePerfVal == true,
                                                            onClick = { updateChoicePerformance(opt.label, true) }
                                                        )
                                                        Spacer(modifier = Modifier.width(2.dp))
                                                        Text("Yes", fontSize = 11.sp)
                                                    }
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.clickable {
                                                            updateChoicePerformance(opt.label, false)
                                                        }
                                                    ) {
                                                        RadioButton(
                                                            selected = choicePerfVal == false,
                                                            onClick = { updateChoicePerformance(opt.label, false) }
                                                        )
                                                        Spacer(modifier = Modifier.width(2.dp))
                                                        Text("No", fontSize = 11.sp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            TextButton(
                                onClick = { showAdvanced = !showAdvanced },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Icon(
                                    imageVector = if (showAdvanced) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (showAdvanced) "Show Less" else "Show Advanced Choices (${advancedOptions.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                "date" -> {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val calendar = remember { java.util.Calendar.getInstance() }
                    
                    val parts = currentAnswer.split("-")
                    val year = if (parts.size == 3) parts[0].toIntOrNull() ?: calendar.get(java.util.Calendar.YEAR) else calendar.get(java.util.Calendar.YEAR)
                    val month = if (parts.size == 3) (parts[1].toIntOrNull() ?: (calendar.get(java.util.Calendar.MONTH) + 1)) - 1 else calendar.get(java.util.Calendar.MONTH)
                    val day = if (parts.size == 3) parts[2].toIntOrNull() ?: calendar.get(java.util.Calendar.DAY_OF_MONTH) else calendar.get(java.util.Calendar.DAY_OF_MONTH)

                    val datePickerDialog = remember(currentAnswer) {
                        android.app.DatePickerDialog(
                            context,
                            { _, selectedYear, selectedMonth, selectedDayOfMonth ->
                                val formattedMonth = String.format("%02d", selectedMonth + 1)
                                val formattedDay = String.format("%02d", selectedDayOfMonth)
                                onUpdateAnswer("$selectedYear-$formattedMonth-$formattedDay")
                            },
                            year,
                            month,
                            day
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { datePickerDialog.show() }
                    ) {
                        OutlinedTextField(
                            value = currentAnswer,
                            onValueChange = {},
                            readOnly = true,
                            enabled = false,
                            placeholder = { Text("Select date (YYYY-MM-DD)...") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.DateRange,
                                    contentDescription = "Select Date"
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
                else -> {
                    OutlinedTextField(
                        value = currentAnswer,
                        onValueChange = onUpdateAnswer,
                        placeholder = { Text("Response info...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter) {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    true
                                } else {
                                    false
                                }
                            },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                        )
                    )
                }
            }
        }

            // Supervisor / witness for a completed procedure (stored in the entry's attributions).
            if (field.isProcedure && !isMinimized && ProcedureAnswer.completedItems(currentAnswer).isNotEmpty()) {
                val current = attribution ?: ProcedureAttributionData(fieldId = field.uid, providerType = "")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = current.supervisorWitness,
                        onValueChange = { onAttributionChange(current.copy(supervisorWitness = it)) },
                        label = { Text("Supervisor / witness (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable(enabled = current.supervisorWitness.isNotBlank()) {
                                onAttributionChange(current.copy(isWitnessVerified = !current.isWitnessVerified))
                            }
                    ) {
                        Checkbox(
                            checked = current.isWitnessVerified,
                            onCheckedChange = { onAttributionChange(current.copy(isWitnessVerified = it)) },
                            enabled = current.supervisorWitness.isNotBlank()
                        )
                        Text("Verified by supervisor", fontSize = 12.sp)
                    }
                }
            }
    }
}
}

@Composable
fun CheckboxOptionItem(
    label: String,
    isSelected: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isProcedure: Boolean,
    askIfPerformedByMe: Boolean,
    choicePerfVal: Boolean?,
    onPerformanceChange: (Boolean) -> Unit,
    isHorizontal: Boolean
) {
    Column(
        modifier = if (isHorizontal) Modifier.widthIn(max = 240.dp) else Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onCheckedChange(!isSelected) }
                .padding(vertical = 4.dp)
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = onCheckedChange
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, fontSize = 13.sp)
        }

        if (isProcedure && askIfPerformedByMe && isSelected) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, bottom = 6.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Did I perform this?",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onPerformanceChange(true) }
                    ) {
                        RadioButton(
                            selected = choicePerfVal == true,
                            onClick = { onPerformanceChange(true) }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Yes", fontSize = 11.sp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onPerformanceChange(false) }
                    ) {
                        RadioButton(
                            selected = choicePerfVal == false,
                            onClick = { onPerformanceChange(false) }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("No", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
