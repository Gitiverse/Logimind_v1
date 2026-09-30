package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.ui.viewmodel.FormBuilderViewModel
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateDesignerScreen(
    viewModel: FormBuilderViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val template by viewModel.activeTemplate.collectAsState()
    val sections by viewModel.activeSections.collectAsState()
    val fields by viewModel.activeFields.collectAsState()
    val options by viewModel.activeFieldOptions.collectAsState()

    var showAddSectionDialog by remember { mutableStateOf(false) }
    var selectedSectionForFieldAdd by remember { mutableStateOf<FormSection?>(null) }
    var selectedFieldForOptionAdd by remember { mutableStateOf<FormField?>(null) }
    var showRenameTemplateDialog by remember { mutableStateOf(false) }

    // --- Save Confirmation State Triggers ---
    var hasMadeChanges by remember { mutableStateOf(false) }
    var showSaveConfirmationDialog by remember { mutableStateOf(false) }

    var sectionToDelete by remember { mutableStateOf<FormSection?>(null) }
    var fieldToDelete by remember { mutableStateOf<FormField?>(null) }
    var optionToDelete by remember { mutableStateOf<FormFieldOption?>(null) }
    val fieldLabels = remember(fields) { fields.values.flatten().associate { it.uid to it.label } }
    // Drag callbacks outlive recompositions; read the latest sections, not the list from first composition.
    val currentSections by rememberUpdatedState(sections)

    var activeDraggedSectionId by remember { mutableStateOf<String?>(null) }
    var activeSectionDragOffset by remember { mutableStateOf(Offset.Zero) }
    val sectionCenters = remember { mutableStateMapOf<String, Offset>() }

    if (template == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No Blueprint Selected")
        }
        return
    }

    // Intercept back actions
    val handleBackClick = {
        if (hasMadeChanges) {
            showSaveConfirmationDialog = true
        } else {
            onNavigateBack()
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        handleBackClick()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.clickable { showRenameTemplateDialog = true }
                    ) {
                        Text(
                            text = "Card Designer Workspace",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Drafting: ${template!!.name}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Rename",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = handleBackClick) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { showAddSectionDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Group", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                },
                modifier = Modifier.border(width = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            if (sections.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        EmptyStateCard(
                            text = "This layout blueprint has no functional groups yet.\nTap 'Add Group' above to append assessment chapters.",
                            icon = Icons.Rounded.Info
                        )
                    }
                }
            } else {
                items(sections, key = { it.uid }) { section ->
                    val id = section.uid
                    val isDragging = activeDraggedSectionId == id

                    val dragModifier = Modifier
                        .onGloballyPositioned { layoutCoordinates ->
                            val size = layoutCoordinates.size
                            val localCenter = Offset(size.width / 2f, size.height / 2f)
                            sectionCenters[id] = layoutCoordinates.localToRoot(localCenter)
                        }
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    activeDraggedSectionId = id
                                    activeSectionDragOffset = Offset.Zero
                                },
                                onDragEnd = {
                                    activeDraggedSectionId = null
                                    activeSectionDragOffset = Offset.Zero
                                },
                                onDragCancel = {
                                    activeDraggedSectionId = null
                                    activeSectionDragOffset = Offset.Zero
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    activeSectionDragOffset += dragAmount

                                    val currentCenter = sectionCenters[id]
                                    if (currentCenter != null) {
                                        val fingerPos = currentCenter + activeSectionDragOffset
                                        var nearestId: String? = null
                                        var minDistance = Float.MAX_VALUE
                                        for ((otherId, otherCenter) in sectionCenters) {
                                            if (otherId != id) {
                                                val dist = (fingerPos - otherCenter).getDistance()
                                                if (dist < minDistance) {
                                                    minDistance = dist
                                                    nearestId = otherId
                                                }
                                            }
                                        }
                                        if (nearestId != null && minDistance < 320f) {
                                            val fromSec = currentSections.find { it.uid == id }
                                            val toSec = currentSections.find { it.uid == nearestId }
                                            if (fromSec != null && toSec != null) {
                                                viewModel.swapSectionOrders(fromSec, toSec)
                                                hasMadeChanges = true

                                                val oldCenter = currentCenter
                                                val newCenter = sectionCenters[nearestId]
                                                if (newCenter != null) {
                                                    activeSectionDragOffset += (oldCenter - newCenter)
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        .graphicsLayer {
                            if (isDragging) {
                                translationX = activeSectionDragOffset.x
                                translationY = activeSectionDragOffset.y
                                scaleX = 1.04f
                                scaleY = 1.04f
                                shadowElevation = 18f
                            }
                        }

                    SectionCard(
                        section = section,
                        fields = fields[section.uid] ?: emptyList(),
                        optionsMap = options,
                        onAddOption = { selectedFieldForOptionAdd = it; hasMadeChanges = true },
                        onDeleteOption = { optionToDelete = it },
                        onAddField = { selectedSectionForFieldAdd = section; hasMadeChanges = true },
                        onDeleteSection = { sectionToDelete = section },
                        onUpdateSectionTitle = { viewModel.updateSectionTitle(section, it); hasMadeChanges = true },
                        onDeleteField = { fieldToDelete = it },
                        onDuplicateField = { viewModel.duplicateField(it); hasMadeChanges = true },
                        onToggleRequired = { field, req -> viewModel.updateFieldRequired(field, req); hasMadeChanges = true },
                        onToggleStartMinimized = { field, min -> viewModel.updateFieldStartMinimized(field, min); hasMadeChanges = true },
                        onLayoutChange = { field, isHor -> viewModel.updateFieldLayout(field, isHor); hasMadeChanges = true },
                        onUpdateLabel = { field, label -> viewModel.updateFieldLabel(field, label); hasMadeChanges = true },
                        onUpdateHint = { field, hint -> viewModel.updateFieldHint(field, hint); hasMadeChanges = true },
                        onProcedureChange = { field, isProc -> viewModel.updateFieldIsProcedure(field, isProc); hasMadeChanges = true },
                        onAskIfPerformedChange = { field, ask -> viewModel.updateFieldAskIfPerformedByMe(field, ask); hasMadeChanges = true },
                        onMoveSectionUp = { viewModel.moveSectionUp(section); hasMadeChanges = true },
                        onMoveSectionDown = { viewModel.moveSectionDown(section); hasMadeChanges = true },
                        onMoveFieldUp = { viewModel.moveFieldUp(it); hasMadeChanges = true },
                        onMoveFieldDown = { viewModel.moveFieldDown(it); hasMadeChanges = true },
                        fieldLabels = fieldLabels,
                        modifier = dragModifier
                    )
                }
            }
        }
    }

    // --- DIALOG: ADD SECTION ---
    if (showAddSectionDialog) {
        var sectionTitle by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddSectionDialog = false },
            title = { Text("Append Custom Group") },
            text = {
                OutlinedTextField(
                    value = sectionTitle,
                    onValueChange = { sectionTitle = it },
                    label = { Text("Group Header Title") },
                    placeholder = { Text("e.g. Pre-Anesthetic Vitals") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (sectionTitle.isNotBlank()) {
                            viewModel.addSectionToActive(sectionTitle)
                            hasMadeChanges = true
                            showAddSectionDialog = false
                        }
                    }
                ) {
                    Text("Add Group")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSectionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- DIALOG: ADD FIELD ---
    if (selectedSectionForFieldAdd != null) {
        val section = selectedSectionForFieldAdd!!
        var fieldLabel by remember { mutableStateOf("") }
        var selectedType by remember { mutableStateOf("text") }
        
        // Conditional logical dependencies
        var hasConditionalDependency by remember { mutableStateOf(false) }
        var dependOnFieldId by remember { mutableStateOf("") }
        var dependOnValue by remember { mutableStateOf("") }

        // Procedure attribute flags
        var isProcedure by remember { mutableStateOf(false) }
        var askIfPerformedByMe by remember { mutableStateOf(false) }
        var ruleError by remember { mutableStateOf<String?>(null) }
        var fieldHint by remember { mutableStateOf("") }

        val allAvailableFields = remember(fields) {
            fields.values.flatten().filter { it.type == "dropdown" || it.type == "radio_group" }
        }

        AlertDialog(
            onDismissRequest = { selectedSectionForFieldAdd = null },
            title = { Text("Create Visual Field") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = fieldLabel,
                        onValueChange = { fieldLabel = it },
                        label = { Text("Question Label Text") },
                        placeholder = { Text("e.g. Any prior anesthesia issues?") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = fieldHint,
                        onValueChange = { fieldHint = it },
                        label = { Text("Hint for whoever fills the form (optional)") },
                        placeholder = { Text("e.g. Include ASA grade") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Field UI Element Type:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedType == "text",
                            onClick = { selectedType = "text" },
                            label = { Text("Text Input", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedType == "number",
                            onClick = { selectedType = "number" },
                            label = { Text("Numbers", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedType == "dropdown",
                            onClick = { selectedType = "dropdown" },
                            label = { Text("Dropdown", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedType == "checkbox",
                            onClick = { selectedType = "checkbox" },
                            label = { Text("Single Checkbox", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedType == "radio_group",
                            onClick = { selectedType = "radio_group" },
                            label = { Text("Radio Row", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedType == "textarea",
                            onClick = { selectedType = "textarea" },
                            label = { Text("Large Note", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedType == "checkbox_group",
                            onClick = { selectedType = "checkbox_group" },
                            label = { Text("Multiple Checkboxes", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedType == "date",
                            onClick = { selectedType = "date" },
                            label = { Text("Calendar / Date", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (selectedType == "checkbox" || selectedType == "checkbox_group") {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isProcedure,
                                    onCheckedChange = { isProcedure = it }
                                )
                                Text("This checkbox is a procedure/test/surgery", fontSize = 13.sp)
                            }
                            if (isProcedure) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = askIfPerformedByMe,
                                        onCheckedChange = { askIfPerformedByMe = it }
                                    )
                                    Text("Ask if I performed it", fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    // Conditional setup
                    if (allAvailableFields.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = hasConditionalDependency,
                                onCheckedChange = { hasConditionalDependency = it }
                            )
                            Text("Render conditionally on flag rules", fontSize = 13.sp)
                        }

                        if (hasConditionalDependency) {
                            Text("Display ONLY IF this option value:", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                            
                            // Select dependency field
                            var dropdownExpanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { dropdownExpanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val match = allAvailableFields.find { it.uid == dependOnFieldId }
                                    Text(match?.label ?: "Select Trigger Question...")
                                }
                                DropdownMenu(
                                    expanded = dropdownExpanded,
                                    onDismissRequest = { dropdownExpanded = false }
                                ) {
                                    allAvailableFields.forEach { f ->
                                        DropdownMenuItem(
                                            text = { Text(f.label) },
                                            onClick = {
                                                dependOnFieldId = f.uid
                                                dropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = dependOnValue,
                                onValueChange = { dependOnValue = it; ruleError = null },
                                label = { Text("Matches value (e.g. Yes)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            val parentChoices = options[dependOnFieldId].orEmpty().map { it.label }
                            if (parentChoices.isNotEmpty()) {
                                Text("Choices: ${parentChoices.joinToString(" / ")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                            ruleError?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // The rule must name a trigger question and one of its choices (matched exactly on save).
                        val parentChoices = options[dependOnFieldId].orEmpty().map { it.label }
                        val matchedValue = parentChoices.find { it.equals(dependOnValue.trim(), ignoreCase = true) }
                        ruleError = when {
                            !hasConditionalDependency -> null
                            dependOnFieldId.isBlank() -> "Select the trigger question."
                            matchedValue == null -> "The value must be one of the trigger question's choices."
                            else -> null
                        }
                        if (fieldLabel.isNotBlank() && ruleError == null) {
                            viewModel.addFieldToActive(
                                sectionId = section.uid,
                                type = selectedType,
                                label = fieldLabel,
                                conditionalFieldUid = if (hasConditionalDependency) dependOnFieldId else null,
                                conditionalValue = if (hasConditionalDependency) matchedValue else null,
                                isProcedure = isProcedure && (selectedType == "checkbox" || selectedType == "checkbox_group"),
                                askIfPerformedByMe = askIfPerformedByMe && isProcedure && (selectedType == "checkbox" || selectedType == "checkbox_group"),
                                hint = fieldHint
                            )
                            hasMadeChanges = true
                            selectedSectionForFieldAdd = null
                        }
                    }
                ) {
                    Text("Add Field")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedSectionForFieldAdd = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- DIALOG: ADD OPTION ---
    if (selectedFieldForOptionAdd != null) {
        val field = selectedFieldForOptionAdd!!
        var optionLabel by remember { mutableStateOf("") }
        var isAdvanced by remember { mutableStateOf(false) }
        // Checkbox-group answers are stored comma-separated, so a comma would split one choice in two.
        val commaNotAllowed = field.type == "checkbox_group" && optionLabel.contains(",")
        AlertDialog(
            onDismissRequest = { selectedFieldForOptionAdd = null },
            title = { Text("Create Checklist Value", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = optionLabel,
                        onValueChange = { optionLabel = it },
                        label = { Text("Value Label") },
                        placeholder = { Text("e.g. Low Risk") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        isError = commaNotAllowed,
                        supportingText = if (commaNotAllowed) {
                            { Text("Commas are not allowed in multiple-checkbox choices.") }
                        } else null
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isAdvanced = !isAdvanced }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = isAdvanced,
                            onCheckedChange = { isAdvanced = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Advanced option",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Initially hidden; expands on demand in Solve mode",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (optionLabel.isNotBlank() && !commaNotAllowed) {
                            viewModel.addOptionToField(field.uid, optionLabel.trim(), isAdvanced)
                            hasMadeChanges = true
                            selectedFieldForOptionAdd = null
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Add Option", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedFieldForOptionAdd = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- POPUP DIALOG: SAVE CONFIRMATION BEFORE LEAVING ---
    if (showSaveConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showSaveConfirmationDialog = false },
            title = { Text("Leave the designer?", fontWeight = FontWeight.Bold) },
            text = { Text("Your changes to this blueprint are saved automatically as you make them.") },
            confirmButton = {
                Button(
                    onClick = {
                        showSaveConfirmationDialog = false
                        hasMadeChanges = false
                        onNavigateBack()
                    }
                ) {
                    Text("Done")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showSaveConfirmationDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    sectionToDelete?.let { sec ->
        val count = fields[sec.uid]?.size ?: 0
        ConfirmDeleteDialog(
            title = "Delete group?",
            message = "Delete '${sec.title}'${if (count > 0) " and its $count question(s)" else ""}? Answers already saved in log entries are kept.",
            onConfirm = { viewModel.deleteSection(sec); hasMadeChanges = true; sectionToDelete = null },
            onDismiss = { sectionToDelete = null }
        )
    }
    fieldToDelete?.let { f ->
        val dependents = fields.values.flatten().count { ConditionalLogic.parse(it.conditionalLogicJson)?.first == f.uid }
        ConfirmDeleteDialog(
            title = "Delete question?",
            message = "Delete '${f.label}'? Answers already saved in log entries are kept." +
                if (dependents > 0) " $dependents question(s) shown only when this one is answered will then always be shown." else "",
            onConfirm = { viewModel.deleteField(f); hasMadeChanges = true; fieldToDelete = null },
            onDismiss = { fieldToDelete = null }
        )
    }
    optionToDelete?.let { o ->
        ConfirmDeleteDialog(
            title = "Delete choice?",
            message = "Delete the choice '${o.label}'?",
            onConfirm = { viewModel.deleteOption(o); hasMadeChanges = true; optionToDelete = null },
            onDismiss = { optionToDelete = null }
        )
    }

    // --- DIALOG: RENAME TEMPLATE DIRECT OFFICE WORKSPACE ---
    if (showRenameTemplateDialog) {
        var currentNaming by remember { mutableStateOf(template!!.name) }
        AlertDialog(
            onDismissRequest = { showRenameTemplateDialog = false },
            title = { Text("Rename Blueprint Template", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = currentNaming,
                    onValueChange = { currentNaming = it },
                    label = { Text("New Template Name") },
                    placeholder = { Text("e.g. Critical Care Checklist") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (currentNaming.isNotBlank()) {
                            viewModel.updateTemplateName(template!!, currentNaming)
                            hasMadeChanges = true
                            showRenameTemplateDialog = false
                        }
                    }
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameTemplateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SectionCard(
    section: FormSection,
    fields: List<FormField>,
    optionsMap: Map<String, List<FormFieldOption>>,
    onAddField: () -> Unit,
    onAddOption: (FormField) -> Unit,
    onDeleteOption: (FormFieldOption) -> Unit,
    onDeleteSection: () -> Unit,
    onUpdateSectionTitle: (String) -> Unit,
    onDeleteField: (FormField) -> Unit,
    onDuplicateField: (FormField) -> Unit,
    onToggleRequired: (FormField, Boolean) -> Unit,
    onToggleStartMinimized: (FormField, Boolean) -> Unit,
    onUpdateLabel: (FormField, String) -> Unit,
    onUpdateHint: (FormField, String) -> Unit = { _, _ -> },
    onMoveSectionUp: () -> Unit,
    onMoveSectionDown: () -> Unit,
    onMoveFieldUp: (FormField) -> Unit,
    onMoveFieldDown: (FormField) -> Unit,
    onLayoutChange: (FormField, Boolean) -> Unit,
    onProcedureChange: (FormField, Boolean) -> Unit,
    onAskIfPerformedChange: (FormField, Boolean) -> Unit,
    fieldLabels: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
    var isEditingTitle by remember { mutableStateOf(false) }
    var editedTitleText by remember(section.title) { mutableStateOf(section.title) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)), shape = RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Section Header Toolbar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.List,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                
                if (isEditingTitle) {
                    OutlinedTextField(
                        value = editedTitleText,
                        onValueChange = { editedTitleText = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                        ),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = {
                                        if (editedTitleText.isNotBlank()) {
                                            onUpdateSectionTitle(editedTitleText)
                                            isEditingTitle = false
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Rounded.Check, contentDescription = "Save Title", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(
                                    onClick = {
                                        editedTitleText = section.title
                                        isEditingTitle = false
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Cancel Title", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { isEditingTitle = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = section.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit Title",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onMoveSectionUp,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move Up", modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = onMoveSectionDown,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move Down", modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = onDeleteSection,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete Group", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }

            HorizontalDivider()

            if (fields.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape = RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Group is empty. Tap 'Add Question' below.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (field in fields) {
                        FieldDesignerRow(
                            field = field,
                            opts = optionsMap[field.uid] ?: emptyList(),
                            onAddOption = { onAddOption(field) },
                            onDeleteOption = onDeleteOption,
                            onDelete = { onDeleteField(field) },
                            onDuplicate = { onDuplicateField(field) },
                            onRequiredChange = { onToggleRequired(field, it) },
                            onStartMinimizedChange = { onToggleStartMinimized(field, it) },
                            onLabelChange = { onUpdateLabel(field, it) },
                            onHintChange = { onUpdateHint(field, it) },
                            onMoveUp = { onMoveFieldUp(field) },
                            onMoveDown = { onMoveFieldDown(field) },
                            onLayoutChange = { isHor -> onLayoutChange(field, isHor) },
                            onProcedureChange = { onProcedureChange(field, it) },
                            onAskIfPerformedChange = { onAskIfPerformedChange(field, it) },
                            fieldLabels = fieldLabels
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onAddField,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Question", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun FieldDesignerRow(
    field: FormField,
    opts: List<FormFieldOption>,
    onAddOption: () -> Unit,
    onDeleteOption: (FormFieldOption) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onRequiredChange: (Boolean) -> Unit,
    onStartMinimizedChange: (Boolean) -> Unit,
    onLabelChange: (String) -> Unit,
    onHintChange: (String) -> Unit = {},
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onLayoutChange: (Boolean) -> Unit,
    onProcedureChange: (Boolean) -> Unit,
    onAskIfPerformedChange: (Boolean) -> Unit,
    fieldLabels: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val isConditional = field.conditionalLogicJson != null
    val ruleText = ConditionalLogic.parse(field.conditionalLogicJson)?.let { (parent, value) ->
        "IF ${fieldLabels[parent] ?: "deleted question"} = $value"
    } ?: "IF ${field.conditionalLogicJson}"

    // Edited locally and saved when the field loses focus (or the row leaves the screen),
    // instead of a database write and full reload on every keystroke.
    var labelText by remember(field.uid, field.label) { mutableStateOf(field.label) }
    val latestLabel by rememberUpdatedState(labelText)
    val commitLabel = {
        if (latestLabel.isNotBlank() && latestLabel != field.label) onLabelChange(latestLabel)
    }
    var hintText by remember(field.uid, field.hint) { mutableStateOf(field.hint ?: "") }
    val latestHint by rememberUpdatedState(hintText)
    val commitHint = {
        if (latestHint.trim() != (field.hint ?: "")) onHintChange(latestHint)
    }
    DisposableEffect(field.uid) { onDispose { commitLabel(); commitHint() } }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val borderStroke = if (isConditional) {
        BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    }
    val cardBackground = if (isConditional) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(borderStroke, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBackground
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Row 1: Type label and control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (isConditional) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = field.type.uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isConditional) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Toolbar swap icons
                IconButton(onClick = onMoveUp, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move Up", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move Down", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDuplicate, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Duplicate", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }

            // Show/hide rule on its own line so the full rule stays readable
            if (isConditional) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = ruleText.replaceFirst("IF ", "Shown if "),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Row 2: In-line editable text
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = labelText,
                onValueChange = { labelText = it },
                placeholder = { Text("Type question label here...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) commitLabel() },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focusManager.clearFocus() }),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent
                ),
                shape = RoundedCornerShape(8.dp)
            )

            OutlinedTextField(
                value = hintText,
                onValueChange = { hintText = it },
                placeholder = { Text("Hint (optional)", fontSize = 12.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) commitHint() },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent
                ),
                shape = RoundedCornerShape(8.dp)
            )

            // Question settings as compact toggle chips (same settings as before, shorter labels)
            Spacer(modifier = Modifier.height(6.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingChip("Required", field.isRequired) { onRequiredChange(it) }
                SettingChip("Start collapsed", field.startMinimized) { onStartMinimizedChange(it) }
                if (field.type == "checkbox_group" || field.type == "radio_group") {
                    SettingChip("Horizontal choices", field.defaultValue == "horizontal") { onLayoutChange(it) }
                }
                if (field.type == "checkbox" || field.type == "checkbox_group") {
                    SettingChip("Procedure", field.isProcedure) { onProcedureChange(it) }
                    if (field.isProcedure) {
                        SettingChip("Ask \"Did I perform this?\"", field.askIfPerformedByMe) { onAskIfPerformedChange(it) }
                    }
                }
            }

            // Options container (if list options)
            if (field.type == "dropdown" || field.type == "radio_group" || field.type == "checkbox_group") {
                Spacer(modifier = Modifier.height(6.dp))
                Text("Choices / Options:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    opts.forEach { opt ->
                        val isAdv = opt.isAdvanced
                        val borderColor = if (isAdv) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        val bgColor = if (isAdv) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
                        Surface(
                            color = bgColor,
                            border = BorderStroke(1.dp, borderColor),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                            ) {
                                if (isAdv) {
                                    Icon(
                                        imageVector = Icons.Rounded.Settings,
                                        contentDescription = "Advanced",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = opt.label,
                                    fontSize = 13.sp,
                                    fontWeight = if (isAdv) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isAdv) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                IconButton(onClick = { onDeleteOption(opt) }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Delete choice ${opt.label}",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                    IconButton(
                        onClick = onAddOption,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add Option", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SettingChip(label: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null
    )
}
