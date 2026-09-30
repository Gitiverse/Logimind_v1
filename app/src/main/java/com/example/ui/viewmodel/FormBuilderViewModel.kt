package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.*
import com.example.data.repository.FormRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class FormBuilderViewModel(private val repository: FormRepository, private val context: Context) : ViewModel() {

    // --- Template List ---
    val templates: StateFlow<List<FormTemplate>> = repository.allTemplates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Form Entries ---
    val entries: StateFlow<List<FormEntry>> = repository.allEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- All Fields (for id-to-label mappings) ---
    val allFields: StateFlow<List<FormField>> = repository.allFields
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Patients ---
    val patients: StateFlow<List<Patient>> = repository.allPatients
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Template order of every question (section order, then question order), used to list
    // answers in the entry viewer, PDF and CSV the way the form shows them.
    val fieldOrder: StateFlow<Map<String, Int>> = combine(repository.allSections, repository.allFields) { sections, fields ->
        val sectionOrder = sections.associate { it.uid to it.sortOrder }
        fields.sortedWith(compareBy({ sectionOrder[it.sectionId] ?: Int.MAX_VALUE }, { it.sortOrder }))
            .mapIndexed { index, f -> f.uid to index }
            .toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // --- Active Design Template State ---
    private val _activeTemplate = MutableStateFlow<FormTemplate?>(null)
    val activeTemplate: StateFlow<FormTemplate?> = _activeTemplate.asStateFlow()

    private val _activeSections = MutableStateFlow<List<FormSection>>(emptyList())
    val activeSections: StateFlow<List<FormSection>> = _activeSections.asStateFlow()

    private val _activeFields = MutableStateFlow<Map<String, List<FormField>>>(emptyMap()) // sectionId -> List<FormField>
    val activeFields: StateFlow<Map<String, List<FormField>>> = _activeFields.asStateFlow()

    private val _activeFieldOptions = MutableStateFlow<Map<String, List<FormFieldOption>>>(emptyMap()) // fieldId -> List<FormFieldOption>
    val activeFieldOptions: StateFlow<Map<String, List<FormFieldOption>>> = _activeFieldOptions.asStateFlow()

    // --- Active Solve/Entry Answers State ---
    private val _solveAnswers = MutableStateFlow<Map<String, String>>(emptyMap()) // fieldUid -> valueString
    val solveAnswers: StateFlow<Map<String, String>> = _solveAnswers.asStateFlow()

    private val _initialAnswers = MutableStateFlow<Map<String, String>>(emptyMap())
    val initialAnswers: StateFlow<Map<String, String>> = _initialAnswers.asStateFlow()

    private val _editingEntryUid = MutableStateFlow<String?>(null)
    val editingEntryUid: StateFlow<String?> = _editingEntryUid.asStateFlow()

    private val _editingEntryTitle = MutableStateFlow("")
    val editingEntryTitle: StateFlow<String> = _editingEntryTitle.asStateFlow()

    private val _initialTitle = MutableStateFlow("")
    val initialTitle: StateFlow<String> = _initialTitle.asStateFlow()

    private val _wizardSelectedPatient = MutableStateFlow<Patient?>(null)
    val wizardSelectedPatient: StateFlow<Patient?> = _wizardSelectedPatient.asStateFlow()

    private val _initialPatient = MutableStateFlow<Patient?>(null)
    val initialPatient: StateFlow<Patient?> = _initialPatient.asStateFlow()

    fun setWizardSelectedPatient(patient: Patient?) {
        _wizardSelectedPatient.value = patient
        _initialPatient.value = patient
    }

    private val _solveAttributions = MutableStateFlow<Map<String, ProcedureAttributionData>>(emptyMap()) // fieldUid -> ProcedureAttributionData
    val solveAttributions: StateFlow<Map<String, ProcedureAttributionData>> = _solveAttributions.asStateFlow()

    private val _initialAttributions = MutableStateFlow<Map<String, ProcedureAttributionData>>(emptyMap())
    val initialAttributions: StateFlow<Map<String, ProcedureAttributionData>> = _initialAttributions.asStateFlow()

    fun updateProcedureAttribution(fieldUid: String, data: ProcedureAttributionData) {
        val updated = _solveAttributions.value.toMutableMap()
        updated[fieldUid] = data
        _solveAttributions.value = updated
        scheduleDraftSave()
    }

    // --- Drafts: a new (not yet saved) entry is kept in prefs so it survives the app being closed ---
    private var draftJob: kotlinx.coroutines.Job? = null
    private var draftTitle = ""
    private var draftPatientId: String? = null

    fun onDraftMetaChanged(title: String, patientId: String?) {
        if (title == draftTitle && patientId == draftPatientId) return
        draftTitle = title
        draftPatientId = patientId
        scheduleDraftSave()
    }

    private fun scheduleDraftSave() {
        if (_editingEntryUid.value != null) return // edits of saved entries are not drafts
        val templateId = _activeTemplate.value?.uid ?: return
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            writeDraft(templateId)
        }
    }

    private fun writeDraft(templateId: String) {
        val answers = _solveAnswers.value
        // An empty form never overwrites or removes a saved draft (the user may be about to
        // resume it); drafts are removed only by discardDraft (Start fresh / Discard / Save).
        if (answers.values.all { it.isBlank() }) return
        val json = org.json.JSONObject().apply {
            put("savedAt", System.currentTimeMillis())
            put("title", draftTitle)
            put("patientId", draftPatientId ?: org.json.JSONObject.NULL)
            put("answers", org.json.JSONObject(answers as Map<*, *>))
        }
        prefs().edit().putString(DRAFT_PREFIX + templateId, json.toString()).apply()
    }

    // Time the draft for this template was saved, or null when there is none.
    fun draftSavedAt(templateId: String): Long? = try {
        prefs().getString(DRAFT_PREFIX + templateId, null)?.let { org.json.JSONObject(it).optLong("savedAt") }
    } catch (e: Exception) {
        null
    }

    fun restoreDraft(templateId: String) {
        val raw = prefs().getString(DRAFT_PREFIX + templateId, null) ?: return
        viewModelScope.launch {
            try {
                val obj = org.json.JSONObject(raw)
                val answersObj = obj.optJSONObject("answers") ?: org.json.JSONObject()
                val answers = answersObj.keys().asSequence().associateWith { answersObj.optString(it) }
                val patient = obj.optString("patientId").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { repository.getPatientByUid(it) }
                draftTitle = obj.optString("title")
                draftPatientId = patient?.uid
                _solveAnswers.value = answers
                _editingEntryTitle.value = draftTitle
                _wizardSelectedPatient.value = patient
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun discardDraft(templateId: String) {
        draftJob?.cancel()
        prefs().edit().remove(DRAFT_PREFIX + templateId).apply()
    }

    // --- Customizable Patient Registration Intake Fields Configuration ---
    // Pre-loaded with standard clinical demographic options
    private val _customIntakeFields = MutableStateFlow<List<PatientFieldConfig>>(
        listOf(
            PatientFieldConfig("age", "Patient Age", isActive = true, isRequired = true),
            PatientFieldConfig("phone", "Primary Phone Number", isActive = true, isRequired = false),
            PatientFieldConfig("insurance", "Insurance Group / Provider", isActive = true, isRequired = false),
            PatientFieldConfig("notes", "Critical Clinical Background Issues", isActive = true, isRequired = false),
            PatientFieldConfig("allergies", "Drug Allergy Detail History", isActive = true, isRequired = true),
            PatientFieldConfig("bloodGroup", "Patient ABO Blood Type", isActive = true, isRequired = false)
        )
    )
    val customIntakeFields: StateFlow<List<PatientFieldConfig>> = _customIntakeFields.asStateFlow()

    fun updateIntakeFields(list: List<PatientFieldConfig>) {
        _customIntakeFields.value = list
        val arr = org.json.JSONArray()
        for (c in list) {
            arr.put(org.json.JSONObject().apply {
                put("id", c.id)
                put("label", c.label)
                put("isActive", c.isActive)
                put("isRequired", c.isRequired)
            })
        }
        prefs().edit().putString(PREF_INTAKE_FIELDS, arr.toString()).apply()
    }

    private fun loadIntakeFields() {
        val saved = prefs().getString(PREF_INTAKE_FIELDS, null) ?: return
        try {
            val arr = org.json.JSONArray(saved)
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PatientFieldConfig(
                    id = o.getString("id"),
                    label = o.optString("label", o.getString("id")),
                    isActive = o.optBoolean("isActive", true),
                    isRequired = o.optBoolean("isRequired", false)
                )
            }
            if (list.isNotEmpty()) _customIntakeFields.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Appearance: System (default) / Light / Dark ---
    private val _themeMode = MutableStateFlow(
        runCatching { com.example.ui.theme.ThemeMode.valueOf(prefs().getString(PREF_THEME_MODE, null) ?: "SYSTEM") }
            .getOrDefault(com.example.ui.theme.ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<com.example.ui.theme.ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: com.example.ui.theme.ThemeMode) {
        _themeMode.value = mode
        prefs().edit().putString(PREF_THEME_MODE, mode.name).apply()
    }

    // --- Pinned templates (shown first when starting a new entry) ---
    private val _pinnedTemplates = MutableStateFlow(prefs().getStringSet(PREF_PINNED_TEMPLATES, emptySet())?.toSet() ?: emptySet())
    val pinnedTemplates: StateFlow<Set<String>> = _pinnedTemplates.asStateFlow()

    fun togglePinnedTemplate(templateId: String) {
        val updated = _pinnedTemplates.value.let { if (templateId in it) it - templateId else it + templateId }
        _pinnedTemplates.value = updated
        prefs().edit().putStringSet(PREF_PINNED_TEMPLATES, updated).apply()
    }

    // Archived = isActive false: hidden from "New Entry" but its log entries are kept.
    fun setTemplateArchived(template: FormTemplate, archived: Boolean) {
        viewModelScope.launch {
            repository.updateTemplate(template.copy(isActive = !archived, updatedAt = System.currentTimeMillis()))
        }
    }

    // --- App lock with the phone's PIN / pattern / fingerprint ---
    private val _appLockEnabled = MutableStateFlow(prefs().getBoolean(PREF_APP_LOCK, false))
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    fun setAppLockEnabled(enabled: Boolean) {
        _appLockEnabled.value = enabled
        prefs().edit().putBoolean(PREF_APP_LOCK, enabled).apply()
    }

    // --- Backup reminder: shown when there are entries and no exported backup for 7 days ---
    private val _showBackupReminder = MutableStateFlow(false)
    val showBackupReminder: StateFlow<Boolean> = _showBackupReminder.asStateFlow()

    private suspend fun refreshBackupReminder() {
        val now = System.currentTimeMillis()
        val lastExport = prefs().getLong(PREF_LAST_MANUAL_EXPORT, 0L)
        val snoozedUntil = prefs().getLong(PREF_REMINDER_SNOOZE_UNTIL, 0L)
        val hasEntries = repository.allEntries.first().isNotEmpty()
        _showBackupReminder.value = hasEntries && now - lastExport > 7 * DAY_MS && now > snoozedUntil
    }

    fun snoozeBackupReminder() {
        prefs().edit().putLong(PREF_REMINDER_SNOOZE_UNTIL, System.currentTimeMillis() + 3 * DAY_MS).apply()
        _showBackupReminder.value = false
    }

    init {
        loadIntakeFields()
        // Pre-populate database with default EMR checklist templates only on first run
        viewModelScope.launch {
            val prefs = prefs()
            writeSafetyBackupOnce()
            val alreadySeeded = prefs.getBoolean("db_seeded_v1", false)
            if (!alreadySeeded) {
                prepopulateDatabase()
                prefs.edit().putBoolean("db_seeded_v1", true).apply()
            }
            // Load saved Gemini API key for AiSummaryService
            com.example.data.service.AiSummaryService.customApiKey = getGeminiApiKey()
            writeAutoBackupIfDue()
            refreshBackupReminder()
        }
    }

    // --- Database Pre-population ---
    private suspend fun prepopulateDatabase() {
        val templateId = "default-airway-template-1"
        if (repository.getTemplateByUid(templateId) != null) return

        val template = FormTemplate(
            uid = templateId,
            name = "Difficult Airway Intake Form",
            isActive = true
        )
        repository.insertTemplate(template)

        // Section A
        val sec1Id = "section-a-clinical"
        val sec1 = FormSection(uid = sec1Id, templateId = templateId, title = "A. Clinical Assessment", sortOrder = 1)
        repository.insertSection(sec1)

        val f1Id = "field-age"
        val f1 = FormField(uid = f1Id, sectionId = sec1Id, type = "number", label = "Patient Age", isRequired = true, sortOrder = 1)
        repository.insertField(f1)

        val f2Id = "field-airway-history"
        val f2 = FormField(uid = f2Id, sectionId = sec1Id, type = "dropdown", label = "Difficult Airway History Found?", isRequired = true, sortOrder = 2)
        repository.insertField(f2)

        val opt1 = FormFieldOption(uid = "opt-history-yes", fieldId = f2Id, label = "Yes", sortOrder = 1)
        val opt2 = FormFieldOption(uid = "opt-history-no", fieldId = f2Id, label = "No", sortOrder = 2)
        repository.insertOption(opt1)
        repository.insertOption(opt2)

        // Section B
        val sec2Id = "section-b-advanced"
        val sec2 = FormSection(uid = sec2Id, templateId = templateId, title = "B. Advanced Planning", sortOrder = 2)
        repository.insertSection(sec2)

        val f3Id = "field-severity"
        val f3 = FormField(
            uid = f3Id,
            sectionId = sec2Id,
            type = "radio_group",
            label = "Airway Complication Severity",
            isRequired = false,
            sortOrder = 1,
            conditionalLogicJson = "$f2Id==Yes" // Shown only is airway history is YES
        )
        repository.insertField(f3)

        val optSec1 = FormFieldOption(uid = "opt-sev-mild", fieldId = f3Id, label = "Mild (Class II Mallampati)", sortOrder = 1)
        val optSec2 = FormFieldOption(uid = "opt-sev-mod", fieldId = f3Id, label = "Moderate (Class III Mallampati)", sortOrder = 2)
        val optSec3 = FormFieldOption(uid = "opt-sev-crit", fieldId = f3Id, label = "Extreme Threat (Class IV Mallampati)", sortOrder = 3)
        repository.insertOption(optSec1)
        repository.insertOption(optSec2)
        repository.insertOption(optSec3)

        val f4Id = "field-techniques"
        val f4 = FormField(
            uid = f4Id,
            sectionId = sec2Id,
            type = "textarea",
            label = "Emergency Bypass Technique Notes",
            isRequired = false,
            sortOrder = 2,
            conditionalLogicJson = "$f2Id==Yes"
        )
        repository.insertField(f4)

        // Prepopulate a template 2 for selection
        val templateId2 = "default-icu-discharge-2"
        val template2 = FormTemplate(
            uid = templateId2,
            name = "ICU Patient Safety Discharge Check",
            isActive = true
        )
        repository.insertTemplate(template2)

        val secICUID = "section-icu-main"
        val secICU = FormSection(uid = secICUID, templateId = templateId2, title = "Discharge Checklist", sortOrder = 1)
        repository.insertSection(secICU)

        repository.insertField(FormField(uid = "field-bp", sectionId = secICUID, type = "text", label = "Final Blood Pressure Vitals", isRequired = true, sortOrder = 1))
        repository.insertField(FormField(uid = "field-stable", sectionId = secICUID, type = "dropdown", label = "Hemodynamically Stable for 24 hours?", isRequired = true, sortOrder = 2))
        repository.insertOption(FormFieldOption(uid = "opt-stable-yes", fieldId = "field-stable", label = "Yes", sortOrder = 1))
        repository.insertOption(FormFieldOption(uid = "opt-stable-no", fieldId = "field-stable", label = "No", sortOrder = 2))

        // Pre-create patients
        repository.insertPatient(Patient(uid = "patient-jane-doe", name = "Jane Doe", medicalId = "MRN-33019", phone = "+1 (555) 0192-384"))
        repository.insertPatient(Patient(uid = "patient-marcus-aurelius", name = "Marcus Aurelius", medicalId = "MRN-56291", phone = "+1 (555) 4381-002"))
    }

    // --- Designer Actions ---
    fun selectTemplateForEdit(template: FormTemplate) {
        _activeTemplate.value = template
        loadActiveTemplateStructures(template.uid)
    }

    fun loadActiveTemplateStructures(templateId: String) {
        viewModelScope.launch {
            val sList = repository.getSectionsForTemplateDirect(templateId)
            _activeSections.value = sList.sortedBy { it.sortOrder }
            
            val fMap = mutableMapOf<String, List<FormField>>()
            val oMap = mutableMapOf<String, List<FormFieldOption>>()

            for (s in sList) {
                val fields = repository.getFieldsForSectionDirect(s.uid).sortedBy { it.sortOrder }
                fMap[s.uid] = fields
                for (f in fields) {
                    val opts = repository.getOptionsForFieldDirect(f.uid).sortedBy { it.sortOrder }
                    oMap[f.uid] = opts
                }
            }
            _activeFields.value = fMap
            _activeFieldOptions.value = oMap
        }
    }

    fun addTemplate(name: String) {
        viewModelScope.launch {
            val template = FormTemplate(uid = UUID.randomUUID().toString(), name = name)
            repository.insertTemplate(template)
        }
    }

    fun addSectionToActive(title: String) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            val nextOrder = (_activeSections.value.maxOfOrNull { it.sortOrder } ?: 0) + 1
            val section = FormSection(uid = UUID.randomUUID().toString(), templateId = template.uid, title = title, sortOrder = nextOrder)
            repository.insertSection(section)
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun updateSectionTitle(section: FormSection, title: String) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            repository.insertSection(section.copy(title = title))
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun addFieldToActive(
        sectionId: String,
        type: String,
        label: String,
        conditionalFieldUid: String? = null,
        conditionalValue: String? = null,
        isProcedure: Boolean = false,
        askIfPerformedByMe: Boolean = false,
        hint: String? = null
    ) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            val sFields = _activeFields.value[sectionId] ?: emptyList()
            val nextOrder = (sFields.maxOfOrNull { it.sortOrder } ?: 0) + 1
            
            val conditionalLogicStr = if (conditionalFieldUid != null && conditionalValue != null) {
                "$conditionalFieldUid==$conditionalValue"
            } else null

            val field = FormField(
                uid = UUID.randomUUID().toString(),
                sectionId = sectionId,
                type = type,
                label = label,
                hint = hint?.trim()?.ifBlank { null },
                sortOrder = nextOrder,
                conditionalLogicJson = conditionalLogicStr,
                isProcedure = isProcedure,
                askIfPerformedByMe = askIfPerformedByMe
            )
            repository.insertField(field)
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun addOptionToField(fieldId: String, label: String, isAdvanced: Boolean = false) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            val opts = _activeFieldOptions.value[fieldId] ?: emptyList()
            val nextOrder = (opts.maxOfOrNull { it.sortOrder } ?: 0) + 1
            val option = FormFieldOption(
                uid = UUID.randomUUID().toString(),
                fieldId = fieldId,
                label = label,
                sortOrder = nextOrder,
                isAdvanced = isAdvanced
            )
            repository.insertOption(option)
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun updateFieldLabel(field: FormField, newLabel: String) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            repository.updateField(field.copy(label = newLabel))
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun updateFieldHint(field: FormField, hint: String) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            repository.updateField(field.copy(hint = hint.trim().ifBlank { null }))
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun updateFieldRequired(field: FormField, required: Boolean) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            repository.updateField(field.copy(isRequired = required))
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun updateFieldStartMinimized(field: FormField, startMinimized: Boolean) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            repository.updateField(field.copy(startMinimized = startMinimized))
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun updateFieldLayout(field: FormField, isHorizontal: Boolean) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            repository.updateField(field.copy(defaultValue = if (isHorizontal) "horizontal" else "vertical"))
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun updateFieldIsProcedure(field: FormField, isProcedure: Boolean) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            repository.updateField(field.copy(isProcedure = isProcedure, askIfPerformedByMe = if (isProcedure) field.askIfPerformedByMe else false))
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun updateFieldAskIfPerformedByMe(field: FormField, askIfPerformed: Boolean) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            repository.updateField(field.copy(askIfPerformedByMe = askIfPerformed))
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun deleteField(field: FormField) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            repository.deleteField(field.uid)
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun deleteOption(option: FormFieldOption) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            repository.deleteOption(option.uid)
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun deleteSection(section: FormSection) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            repository.deleteSection(section.uid)
            loadActiveTemplateStructures(template.uid)
        }
    }

    fun duplicateField(field: FormField) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            val newUid = UUID.randomUUID().toString()
            val nextOrder = ((_activeFields.value[field.sectionId] ?: emptyList()).maxOfOrNull { it.sortOrder } ?: 0) + 1
            val duplicated = field.copy(uid = newUid, label = "${field.label} (Copy)", sortOrder = nextOrder)
            repository.insertField(duplicated)

            // Duplicate choices
            val originalOpts = repository.getOptionsForFieldDirect(field.uid)
            for (opt in originalOpts) {
                repository.insertOption(FormFieldOption(uid = UUID.randomUUID().toString(), fieldId = newUid, label = opt.label, sortOrder = opt.sortOrder, isAdvanced = opt.isAdvanced))
            }

            loadActiveTemplateStructures(template.uid)
        }
    }

    fun swapSectionOrders(from: FormSection, to: FormSection) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            repository.insertSection(from.copy(sortOrder = to.sortOrder))
            repository.insertSection(to.copy(sortOrder = from.sortOrder))
            loadActiveTemplateStructures(template.uid)
        }
    }

    // --- Index Shift Reordering ---
    fun moveSectionUp(section: FormSection) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            val sectionsSorted = _activeSections.value
            val index = sectionsSorted.indexOfFirst { it.uid == section.uid }
            if (index > 0) {
                val prevSection = sectionsSorted[index - 1]
                repository.insertSection(prevSection.copy(sortOrder = section.sortOrder))
                repository.insertSection(section.copy(sortOrder = prevSection.sortOrder))
                loadActiveTemplateStructures(template.uid)
            }
        }
    }

    fun moveSectionDown(section: FormSection) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            val sectionsSorted = _activeSections.value
            val index = sectionsSorted.indexOfFirst { it.uid == section.uid }
            if (index != -1 && index < sectionsSorted.size - 1) {
                val nextSection = sectionsSorted[index + 1]
                repository.insertSection(nextSection.copy(sortOrder = section.sortOrder))
                repository.insertSection(section.copy(sortOrder = nextSection.sortOrder))
                loadActiveTemplateStructures(template.uid)
            }
        }
    }

    fun moveFieldUp(field: FormField) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            val fieldsSorted = _activeFields.value[field.sectionId] ?: emptyList()
            val index = fieldsSorted.indexOfFirst { it.uid == field.uid }
            if (index > 0) {
                val prevField = fieldsSorted[index - 1]
                repository.updateField(prevField.copy(sortOrder = field.sortOrder))
                repository.updateField(field.copy(sortOrder = prevField.sortOrder))
                loadActiveTemplateStructures(template.uid)
            }
        }
    }

    fun moveFieldDown(field: FormField) {
        val template = _activeTemplate.value ?: return
        viewModelScope.launch {
            val fieldsSorted = _activeFields.value[field.sectionId] ?: emptyList()
            val index = fieldsSorted.indexOfFirst { it.uid == field.uid }
            if (index != -1 && index < fieldsSorted.size - 1) {
                val nextField = fieldsSorted[index + 1]
                repository.updateField(nextField.copy(sortOrder = field.sortOrder))
                repository.updateField(field.copy(sortOrder = nextField.sortOrder))
                loadActiveTemplateStructures(template.uid)
            }
        }
    }

    // --- Solved Intake Operations ---
    fun updateAns(fieldUid: String, valStr: String) {
        val updated = _solveAnswers.value.toMutableMap()
        updated[fieldUid] = valStr
        _solveAnswers.value = updated
        scheduleDraftSave()
    }

    fun clearSolveAnswers() {
        draftJob?.cancel()
        draftTitle = ""
        draftPatientId = null
        _solveAnswers.value = emptyMap()
        _initialAnswers.value = emptyMap()
        _wizardSelectedPatient.value = null
        _initialPatient.value = null
        _solveAttributions.value = emptyMap()
        _initialAttributions.value = emptyMap()
        _editingEntryUid.value = null
        _editingEntryTitle.value = ""
        _initialTitle.value = ""
    }

    // Clears filled answers but keeps the entry being edited, so a later save still updates it.
    fun clearAnswersOnly() {
        _solveAnswers.value = emptyMap()
        _solveAttributions.value = emptyMap()
    }

    private val _isLoadingEntry = MutableStateFlow(false)
    val isLoadingEntry: StateFlow<Boolean> = _isLoadingEntry.asStateFlow()

    // Starts a NEW entry pre-filled with this entry's answers and patient ("Repeat entry").
    fun startNewEntryFrom(entry: FormEntry) = selectEntryForEdit(entry, asNewCopy = true)

    fun selectEntryForEdit(entry: FormEntry, asNewCopy: Boolean = false) {
        // Set synchronously so the solve screen knows it is editing (and waits) before it shows.
        _isLoadingEntry.value = true
        _editingEntryUid.value = if (asNewCopy) null else entry.uid
        viewModelScope.launch {
            try {
                val template = repository.getTemplateByUid(entry.templateId) ?: return@launch
                _activeTemplate.value = template
                loadActiveTemplateStructures(template.uid)
            
                _editingEntryUid.value = if (asNewCopy) null else entry.uid
                _editingEntryTitle.value = if (asNewCopy) "" else entry.title
                _initialTitle.value = if (asNewCopy) "" else entry.title
            
                // Parse answers JSON
                val answers = mutableMapOf<String, String>()
                if (!entry.answersJson.isNullOrBlank()) {
                    try {
                        val obj = org.json.JSONObject(entry.answersJson)
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            answers[key] = obj.optString(key)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                _solveAnswers.value = answers
                _initialAnswers.value = if (asNewCopy) emptyMap() else answers
            
                // Parse attributions JSON
                val attributions = mutableMapOf<String, ProcedureAttributionData>()
                if (!entry.attributionsJson.isNullOrBlank()) {
                    try {
                        val obj = org.json.JSONObject(entry.attributionsJson)
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val child = obj.getJSONObject(key)
                            attributions[key] = ProcedureAttributionData(
                                fieldId = child.optString("fieldId", ""),
                                providerType = child.optString("providerType", ""),
                                providerName = child.optString("providerName", ""),
                                providerId = child.optString("providerId", ""),
                                supervisorWitness = child.optString("supervisorWitness", ""),
                                isWitnessVerified = child.optBoolean("isWitnessVerified", false)
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                _solveAttributions.value = attributions
                _initialAttributions.value = if (asNewCopy) emptyMap() else attributions
            
                // Find patient
                val patient = if (!entry.patientId.isNullOrBlank()) {
                    repository.getPatientByUid(entry.patientId)
                } else {
                    null
                }
                _wizardSelectedPatient.value = patient
                _initialPatient.value = patient
            } finally {
                _isLoadingEntry.value = false
            }
        }
    }

    private val _isSavingEntry = MutableStateFlow(false)
    val isSavingEntry: StateFlow<Boolean> = _isSavingEntry.asStateFlow()

    fun saveEntry(template: FormTemplate, patient: Patient?, title: String, onSuccess: () -> Unit) {
        // A second tap while the first save runs would otherwise create a duplicate entry.
        if (_isSavingEntry.value) return
        _isSavingEntry.value = true
        viewModelScope.launch {
            try {
                // Answers to questions hidden by show/hide rules are not saved. Answers for
                // fields that no longer exist in the template (edited old entries) are kept.
                val templateFields = _activeFields.value.values.flatten()
                val templateFieldIds = templateFields.map { it.uid }.toSet()
                val visibleIds = ConditionalLogic.visibleFieldUids(templateFields, _solveAnswers.value)
                val serialMap = _solveAnswers.value.filterKeys { it in visibleIds || it !in templateFieldIds }
                val attribs = _solveAttributions.value.filter { (k, a) ->
                    k in serialMap && (a.supervisorWitness.isNotBlank() || a.providerName.isNotBlank() || a.isWitnessVerified)
                }

                // Build key maps for answers
                val jsonObject = org.json.JSONObject()
                for ((key, value) in serialMap) {
                    jsonObject.put(key, value)
                }
                val answersJsonStr = jsonObject.toString()

                // Build key maps for attributions
                val attribsJson = org.json.JSONObject()
                for ((key, value) in attribs) {
                    val singleObj = org.json.JSONObject()
                    singleObj.put("fieldId", value.fieldId)
                    singleObj.put("providerType", value.providerType)
                    singleObj.put("providerName", value.providerName)
                    singleObj.put("providerId", value.providerId)
                    singleObj.put("supervisorWitness", value.supervisorWitness)
                    singleObj.put("isWitnessVerified", value.isWitnessVerified)
                    attribsJson.put(key, singleObj)
                }
                val attributionsJsonStr = attribsJson.toString()

                val editingUid = _editingEntryUid.value
                val entryUid = editingUid ?: UUID.randomUUID().toString()

                repository.inTransaction {
                    val existingEntry = if (editingUid != null) repository.getEntryByUid(entryUid) else null

                    val finalEntry = FormEntry(
                        uid = entryUid,
                        templateId = template.uid,
                        patientId = patient?.uid,
                        templateName = template.name,
                        patientName = patient?.name ?: "Theatre Patient Log",
                        title = title.ifBlank { "${template.name} Record" },
                        answersJson = answersJsonStr,
                        attributionsJson = attributionsJsonStr,
                        createdAt = existingEntry?.createdAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.insertEntry(finalEntry)

                    // If we are editing, delete previous answers first
                    if (editingUid != null) {
                        repository.deleteAnswersForEntry(entryUid)
                    }

                    // Dynamic indexes table mapping
                    for ((fId, vText) in serialMap) {
                        val attAtId = attribs[fId]
                        repository.insertAnswer(
                            EntryAnswer(
                                uid = UUID.randomUUID().toString(),
                                entryId = entryUid,
                                fieldId = fId,
                                value = vText,
                                providerType = attAtId?.providerType,
                                providerName = attAtId?.providerName,
                                providerId = attAtId?.providerId,
                                supervisorWitness = attAtId?.supervisorWitness,
                                isWitnessVerified = attAtId?.isWitnessVerified ?: false,
                                performedByMe = ProcedureAnswer.performedByMe(vText)
                            )
                        )
                    }
                }

                if (editingUid == null) discardDraft(template.uid)
                clearSolveAnswers()
                onSuccess()
            } finally {
                _isSavingEntry.value = false
            }
        }
    }

    fun addPatient(name: String, medicalId: String, phone: String?) {
        viewModelScope.launch {
            repository.insertPatient(
                Patient(
                    uid = UUID.randomUUID().toString(),
                    name = name,
                    medicalId = medicalId,
                    phone = phone
                )
            )
        }
    }

    // --- Template Duplication Architecture ---
    fun duplicateTemplate(original: FormTemplate) {
        viewModelScope.launch { repository.inTransaction { duplicateTemplateTx(original) } }
    }

    private suspend fun duplicateTemplateTx(original: FormTemplate) {
        val newTemplateId = UUID.randomUUID().toString()
        val newTemplate = FormTemplate(
            uid = newTemplateId,
            name = "${original.name} (Copy)",
            isActive = original.isActive
        )
        repository.insertTemplate(newTemplate)

        // Copy sections, fields, and options; show/hide rules are re-pointed at the copied fields
        val sections = repository.getSectionsForTemplateDirect(original.uid)
        val fieldIdMap = mutableMapOf<String, String>()
        val copiedFields = mutableListOf<FormField>()
        for (sec in sections) {
            val newSecId = UUID.randomUUID().toString()
            repository.insertSection(
                FormSection(
                    uid = newSecId,
                    templateId = newTemplateId,
                    title = sec.title,
                    sortOrder = sec.sortOrder,
                    isExpandable = sec.isExpandable
                )
            )

            val fields = repository.getFieldsForSectionDirect(sec.uid)
            for (f in fields) {
                val newFieldId = UUID.randomUUID().toString()
                fieldIdMap[f.uid] = newFieldId
                val copy = f.copy(uid = newFieldId, sectionId = newSecId)
                copiedFields.add(copy)
                repository.insertField(copy)

                val options = repository.getOptionsForFieldDirect(f.uid)
                for (opt in options) {
                    repository.insertOption(
                        FormFieldOption(
                            uid = UUID.randomUUID().toString(),
                            fieldId = newFieldId,
                            label = opt.label,
                            sortOrder = opt.sortOrder,
                            isAdvanced = opt.isAdvanced
                        )
                    )
                }
            }
        }

        for (copy in copiedFields) {
            val rule = ConditionalLogic.parse(copy.conditionalLogicJson) ?: continue
            val newParent = fieldIdMap[rule.first] ?: continue
            repository.updateField(copy.copy(conditionalLogicJson = "$newParent==${rule.second}"))
        }
    }

    // --- Single-Transaction Draft Save Engine ---
    fun saveTemplateDraft(
        templateId: String,
        sections: List<FormSection>,
        fields: Map<String, List<FormField>>,
        options: Map<String, List<FormFieldOption>>
    ) {
        viewModelScope.launch {
            repository.inTransaction {
                repository.deleteSectionsForTemplate(templateId)
                for (sec in sections) {
                    repository.insertSection(sec)
                    val fList = fields[sec.uid] ?: emptyList()
                    for (f in fList) {
                        repository.insertField(f)
                        val oList = options[f.uid] ?: emptyList()
                        for (o in oList) {
                            repository.insertOption(o)
                        }
                    }
                }
            }
            loadActiveTemplateStructures(templateId)
        }
    }

    fun updateTemplateName(template: FormTemplate, newName: String) {
        viewModelScope.launch {
            repository.updateTemplate(template.copy(name = newName))
            if (_activeTemplate.value?.uid == template.uid) {
                _activeTemplate.value = template.copy(name = newName)
            }
        }
    }

    fun addPatientDetailed(
        name: String,
        medicalId: String,
        phone: String?,
        age: String?,
        insurance: String?,
        notes: String?,
        customAnswers: String? = null
    ) {
        viewModelScope.launch {
            repository.insertPatient(
                Patient(
                    uid = UUID.randomUUID().toString(),
                    name = name,
                    medicalId = medicalId,
                    phone = phone,
                    age = age,
                    insurance = insurance,
                    notes = notes,
                    customAnswersJson = customAnswers
                )
            )
        }
    }

    // onDeleted receives an undo action that puts the patient back.
    fun deletePatient(patient: Patient, onDeleted: (undo: () -> Unit) -> Unit = {}) {
        viewModelScope.launch {
            repository.deletePatient(patient.uid)
            onDeleted { viewModelScope.launch { repository.insertPatient(patient) } }
        }
    }

    fun updatePatient(patient: Patient) {
        viewModelScope.launch {
            repository.inTransaction {
                repository.insertPatient(patient)
                repository.updatePatientNameInEntries(patient.uid, patient.name)
            }
        }
    }

    fun deleteTemplate(template: FormTemplate) {
        viewModelScope.launch {
            repository.deleteTemplate(template.uid)
        }
    }

    // onDeleted receives an undo action that restores the entry and its answers.
    fun deleteFormEntry(uid: String, onDeleted: (undo: () -> Unit) -> Unit = {}) {
        viewModelScope.launch {
            val entry = repository.getEntryByUid(uid) ?: return@launch
            val answers = repository.getAnswersForEntryDirect(uid)
            repository.deleteEntry(uid)
            onDeleted {
                viewModelScope.launch {
                    // The template may have been deleted meanwhile; then the entry cannot come back.
                    if (repository.getTemplateByUid(entry.templateId) == null) return@launch
                    repository.inTransaction {
                        repository.insertEntry(entry)
                        answers.forEach { repository.insertAnswer(it) }
                    }
                }
            }
        }
    }

    private val _selectedEntriesForExport = MutableStateFlow<Set<String>>(emptySet())
    val selectedEntriesForExport: StateFlow<Set<String>> = _selectedEntriesForExport.asStateFlow()

    fun toggleEntrySelectionForExport(entryId: String) {
        val current = _selectedEntriesForExport.value.toMutableSet()
        if (current.contains(entryId)) {
            current.remove(entryId)
        } else {
            current.add(entryId)
        }
        _selectedEntriesForExport.value = current
    }

    fun clearEntrySelectionForExport() {
        _selectedEntriesForExport.value = emptySet()
    }

    fun setEntrySelectionForExport(entryIds: Set<String>) {
        _selectedEntriesForExport.value = entryIds
    }

    private val _aiSummaryCount = MutableStateFlow(0)
    val aiSummaryCount: StateFlow<Int> = _aiSummaryCount.asStateFlow()

    fun incrementAiSummaryCount() {
        _aiSummaryCount.value += 1
    }

    fun deleteAllTemplates() {
        viewModelScope.launch {
            for (t in templates.value) {
                repository.deleteTemplate(t.uid)
            }
        }
    }

    // Builds a complete backup (schemaVersion 2): v1 content plus template structure and answers.
    fun exportFullBackup(onReady: (java.io.File) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.data.service.ExportService.generateJsonBackup(
                        context = context,
                        templates = repository.allTemplates.first(),
                        patients = repository.allPatients.first(),
                        entries = repository.allEntries.first(),
                        sections = repository.getAllSections(),
                        fields = repository.getAllFields(),
                        options = repository.getAllOptions(),
                        answers = repository.getAllAnswers()
                    )
                }
                prefs().edit().putLong(PREF_LAST_MANUAL_EXPORT, System.currentTimeMillis()).apply()
                _showBackupReminder.value = false
                onReady(file)
            } catch (e: Exception) {
                onError("Backup failed: ${e.localizedMessage}")
            }
        }
    }

    // First launch of v1.1: keep a full copy of the existing data in app storage before this
    // version writes anything, so an upgrade problem can always be undone.
    private suspend fun writeSafetyBackupOnce() {
        if (prefs().getBoolean(PREF_SAFETY_BACKUP_DONE, false)) return
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val json = com.example.data.service.ExportService.generateJsonBackup(
                    context = context,
                    templates = repository.allTemplates.first(),
                    patients = repository.allPatients.first(),
                    entries = repository.allEntries.first(),
                    sections = repository.getAllSections(),
                    fields = repository.getAllFields(),
                    options = repository.getAllOptions(),
                    answers = repository.getAllAnswers()
                )
                val dir = java.io.File(context.filesDir, "backups").apply { mkdirs() }
                json.copyTo(java.io.File(dir, "pre_upgrade_v1_1_${System.currentTimeMillis()}.json"), overwrite = true)
            }
            prefs().edit().putBoolean(PREF_SAFETY_BACKUP_DONE, true).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Daily automatic backup inside the app (keeps the newest AUTO_BACKUPS_KEPT files).
    // It protects against mistakes in the app; it does not survive uninstalling, so users
    // are still reminded to export a copy off the phone.
    private suspend fun writeAutoBackupIfDue() {
        val now = System.currentTimeMillis()
        if (now - prefs().getLong(PREF_LAST_AUTO_BACKUP, 0L) < DAY_MS) return
        if (repository.allEntries.first().isEmpty() && repository.allPatients.first().isEmpty()) return
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val json = com.example.data.service.ExportService.generateJsonBackup(
                    context = context,
                    templates = repository.allTemplates.first(),
                    patients = repository.allPatients.first(),
                    entries = repository.allEntries.first(),
                    sections = repository.getAllSections(),
                    fields = repository.getAllFields(),
                    options = repository.getAllOptions(),
                    answers = repository.getAllAnswers()
                )
                val dir = java.io.File(context.filesDir, "backups").apply { mkdirs() }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date(now))
                json.copyTo(java.io.File(dir, "auto_$stamp.json"), overwrite = true)
                dir.listFiles { f -> f.name.startsWith("auto_") }
                    ?.sortedByDescending { it.name }
                    ?.drop(AUTO_BACKUPS_KEPT)
                    ?.forEach { it.delete() }
            }
            prefs().edit().putLong(PREF_LAST_AUTO_BACKUP, now).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ZIP bundle for the selected entries: PDF (with question labels) + backup JSON of those entries.
    fun exportZipBundle(
        selectedEntries: List<FormEntry>,
        fieldLabelMap: Map<String, String>,
        onReady: (java.io.File) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val entryIds = selectedEntries.map { it.uid }.toSet()
                    val json = com.example.data.service.ExportService.generateJsonBackup(
                        context = context,
                        templates = repository.allTemplates.first(),
                        patients = repository.allPatients.first(),
                        entries = selectedEntries,
                        sections = repository.getAllSections(),
                        fields = repository.getAllFields(),
                        options = repository.getAllOptions(),
                        answers = repository.getAllAnswers().filter { it.entryId in entryIds }
                    )
                    com.example.data.service.ExportService.generateZipArchive(context, json, selectedEntries, fieldLabelMap, fieldOrder.value)
                }
                onReady(file)
            } catch (e: Exception) {
                onError("Export failed: ${e.localizedMessage}")
            }
        }
    }

    // Restores a v1 or v2 backup without deleting anything already on the phone: rows are
    // upserted parent-first inside one transaction, and bad rows are skipped and counted.
    fun restoreBackup(jsonString: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    restoreBackupTx(org.json.JSONObject(jsonString))
                }
                onSuccess(report)
            } catch (e: Exception) {
                onError("Failed to restore: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun restoreBackupTx(obj: org.json.JSONObject): String {
        fun org.json.JSONObject.optNullableString(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

        fun org.json.JSONObject.array(key: String): List<org.json.JSONObject> {
            val arr = optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }

        val counts = linkedMapOf<String, Int>()
        var skipped = 0
        suspend fun restoreEach(name: String, rows: List<org.json.JSONObject>, write: suspend (org.json.JSONObject) -> Unit) {
            var ok = 0
            for (row in rows) {
                try {
                    write(row)
                    ok++
                } catch (e: Exception) {
                    skipped++
                }
            }
            counts[name] = ok
        }

        repository.inTransaction {
            val existingTemplateIds = repository.allTemplates.first().map { it.uid }.toSet()

            restoreEach("templates", obj.array("templates")) { t ->
                repository.insertTemplate(
                    FormTemplate(
                        uid = t.getString("uid"),
                        name = t.getString("name"),
                        isActive = t.optBoolean("isActive", true),
                        createdAt = t.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = t.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }

            // v1 files have no structure; keep whatever structure is already on the phone.
            val sectionRows = obj.array("sections")
            val templatesWithStructure = sectionRows.map { it.optString("templateId") }.toSet()
            restoreEach("sections", sectionRows) { sec ->
                repository.insertSection(
                    FormSection(
                        uid = sec.getString("uid"),
                        templateId = sec.getString("templateId"),
                        title = sec.getString("title"),
                        sortOrder = sec.optInt("sortOrder", 0),
                        isExpandable = sec.optBoolean("isExpandable", false)
                    )
                )
            }
            restoreEach("fields", obj.array("fields")) { f ->
                repository.insertField(
                    FormField(
                        uid = f.getString("uid"),
                        sectionId = f.getString("sectionId"),
                        type = f.getString("type"),
                        label = f.getString("label"),
                        hint = f.optNullableString("hint"),
                        isRequired = f.optBoolean("isRequired", false),
                        sortOrder = f.optInt("sortOrder", 0),
                        conditionalLogicJson = f.optNullableString("conditionalLogicJson"),
                        defaultValue = f.optNullableString("defaultValue"),
                        isProcedure = f.optBoolean("isProcedure", false),
                        askIfPerformedByMe = f.optBoolean("askIfPerformedByMe", false),
                        startMinimized = f.optBoolean("startMinimized", false)
                    )
                )
            }
            restoreEach("options", obj.array("options")) { o ->
                repository.insertOption(
                    FormFieldOption(
                        uid = o.getString("uid"),
                        fieldId = o.getString("fieldId"),
                        label = o.getString("label"),
                        sortOrder = o.optInt("sortOrder", 0),
                        isAdvanced = o.optBoolean("isAdvanced", false)
                    )
                )
            }

            restoreEach("patients", obj.array("patients")) { pJson ->
                repository.insertPatient(
                    Patient(
                        uid = pJson.getString("uid"),
                        name = pJson.getString("name"),
                        medicalId = pJson.getString("medicalId"),
                        phone = pJson.optNullableString("phone"),
                        age = pJson.optNullableString("age"),
                        insurance = pJson.optNullableString("insurance"),
                        notes = pJson.optNullableString("notes"),
                        customAnswersJson = pJson.optNullableString("customAnswersJson"),
                        createdAt = pJson.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }

            val logRows = obj.array("logs")
            restoreEach("logs", logRows) { eJson ->
                repository.insertEntry(
                    FormEntry(
                        uid = eJson.getString("uid"),
                        templateId = eJson.getString("templateId"),
                        patientId = eJson.optNullableString("patientId"),
                        templateName = eJson.optNullableString("templateName"),
                        patientName = eJson.optNullableString("patientName"),
                        title = eJson.getString("title"),
                        answersJson = eJson.getString("answersJson"),
                        attributionsJson = eJson.optNullableString("attributionsJson"),
                        createdAt = eJson.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = eJson.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }

            restoreEach("answers", obj.array("answers")) { a ->
                repository.insertAnswer(
                    EntryAnswer(
                        uid = a.getString("uid"),
                        entryId = a.getString("entryId"),
                        fieldId = a.getString("fieldId"),
                        value = a.optString("value"),
                        providerType = a.optNullableString("providerType"),
                        providerName = a.optNullableString("providerName"),
                        providerId = a.optNullableString("providerId"),
                        supervisorWitness = a.optNullableString("supervisorWitness"),
                        isWitnessVerified = a.optBoolean("isWitnessVerified", false),
                        performedByMe = if (a.has("performedByMe") && !a.isNull("performedByMe")) a.optBoolean("performedByMe") else null
                    )
                )
            }

            // A template from an old (v1) backup that is new to this phone has no questions.
            // Rebuild one read-only section from its entries' answer keys so they stay readable.
            val recovered = obj.array("templates")
                .map { it.optString("uid") }
                .filter { it.isNotBlank() && it !in existingTemplateIds && it !in templatesWithStructure }
            val existingFieldIds = repository.getAllFields().map { it.uid }.toSet()
            for (templateId in recovered) {
                if (repository.getSectionsForTemplateDirect(templateId).isNotEmpty()) continue
                val keys = logRows.filter { it.optString("templateId") == templateId }
                    .flatMap { row ->
                        try {
                            org.json.JSONObject(row.optString("answersJson", "{}")).keys().asSequence().toList()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    .distinct()
                    .filter { it !in existingFieldIds }
                if (keys.isEmpty()) continue
                val sectionId = "recovered-$templateId"
                repository.insertSection(FormSection(uid = sectionId, templateId = templateId, title = "Recovered from backup", sortOrder = 1))
                keys.forEachIndexed { i, key ->
                    repository.insertField(FormField(uid = key, sectionId = sectionId, type = "textarea", label = key, sortOrder = i + 1))
                }
                counts["recovered templates"] = (counts["recovered templates"] ?: 0) + 1
            }
        }

        val summary = counts.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        return if (skipped > 0) "$summary\nskipped (invalid rows): $skipped" else summary
    }

    // Read-only structure for the template preview dialog: section title -> questions (with choices).
    fun loadTemplatePreview(templateId: String, onResult: (List<Pair<String, List<Pair<FormField, List<FormFieldOption>>>>>) -> Unit) {
        viewModelScope.launch {
            val result = repository.getSectionsForTemplateDirect(templateId).sortedBy { it.sortOrder }.map { sec ->
                sec.title to repository.getFieldsForSectionDirect(sec.uid).sortedBy { it.sortOrder }.map { f ->
                    f to repository.getOptionsForFieldDirect(f.uid).sortedBy { it.sortOrder }
                }
            }
            onResult(result)
        }
    }

    fun countEntriesForTemplate(templateId: String, onResult: (Int) -> Unit) {
        viewModelScope.launch { onResult(repository.countEntriesForTemplate(templateId)) }
    }

    // Stored encrypted (PREF_API_KEY_ENC). An older plain-text value under "gemini_api_key" is
    // moved to the encrypted slot on first read; if the Keystore is unavailable it stays readable.
    fun getGeminiApiKey(): String {
        val prefs = prefs()
        prefs.getString(PREF_API_KEY_ENC, null)?.let { enc ->
            return com.example.data.service.SecretStore.decrypt(enc) ?: ""
        }
        val plain = prefs.getString("gemini_api_key", "") ?: ""
        if (plain.isNotBlank()) {
            runCatching { com.example.data.service.SecretStore.encrypt(plain) }.getOrNull()?.let { enc ->
                prefs.edit().putString(PREF_API_KEY_ENC, enc).remove("gemini_api_key").apply()
            }
        }
        return plain
    }

    fun saveGeminiApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        val enc = if (trimmed.isEmpty()) null else runCatching { com.example.data.service.SecretStore.encrypt(trimmed) }.getOrNull()
        prefs().edit().apply {
            if (trimmed.isEmpty()) remove(PREF_API_KEY_ENC).remove("gemini_api_key")
            else if (enc != null) putString(PREF_API_KEY_ENC, enc).remove("gemini_api_key")
            else putString("gemini_api_key", trimmed)
        }.apply()
        com.example.data.service.AiSummaryService.customApiKey = apiKey.trim()
    }

    companion object {
        // Frozen: existing installs keep their settings in this file (see AI_BUILDER_GUIDE.md 1.2).
        private const val PREFS_NAME = "logimind_prefs"
        private const val PREF_INTAKE_FIELDS = "intake_fields_v1"
        private const val PREF_SAFETY_BACKUP_DONE = "safety_backup_done_v2"
        private const val PREF_THEME_MODE = "theme_mode"
        private const val PREF_PINNED_TEMPLATES = "pinned_templates"
        private const val PREF_APP_LOCK = "app_lock_enabled"
        private const val PREF_LAST_MANUAL_EXPORT = "last_manual_export"
        private const val PREF_REMINDER_SNOOZE_UNTIL = "backup_reminder_snooze_until"
        private const val PREF_LAST_AUTO_BACKUP = "last_auto_backup"
        private const val PREF_API_KEY_ENC = "gemini_api_key_enc"
        private const val DRAFT_PREFIX = "draft_"
        private const val AUTO_BACKUPS_KEPT = 7
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

class FormBuilderViewModelFactory(private val repository: FormRepository, private val context: Context) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FormBuilderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FormBuilderViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
