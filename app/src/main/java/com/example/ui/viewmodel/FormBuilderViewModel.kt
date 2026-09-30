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
    }

    init {
        // Pre-populate database with default EMR checklist templates only on first run
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("logimind_prefs", Context.MODE_PRIVATE)
            val alreadySeeded = prefs.getBoolean("db_seeded_v1", false)
            if (!alreadySeeded) {
                prepopulateDatabase()
                prefs.edit().putBoolean("db_seeded_v1", true).apply()
            }
            // Load saved Gemini API key for AiSummaryService
            val savedKey = prefs.getString("gemini_api_key", "") ?: ""
            com.example.data.service.AiSummaryService.customApiKey = savedKey
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
        askIfPerformedByMe: Boolean = false
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
                repository.insertOption(FormFieldOption(uid = UUID.randomUUID().toString(), fieldId = newUid, label = opt.label, sortOrder = opt.sortOrder))
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
    }

    fun clearSolveAnswers() {
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

    fun selectEntryForEdit(entry: FormEntry) {
        viewModelScope.launch {
            val template = repository.getTemplateByUid(entry.templateId) ?: return@launch
            _activeTemplate.value = template
            loadActiveTemplateStructures(template.uid)
            
            _editingEntryUid.value = entry.uid
            _editingEntryTitle.value = entry.title
            _initialTitle.value = entry.title
            
            // Parse answers JSON
            val answers = mutableMapOf<String, String>()
            if (!entry.answersJson.isNullOrBlank()) {
                try {
                    val obj = org.json.JSONObject(entry.answersJson)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        answers[key] = obj.getString(key)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            _solveAnswers.value = answers
            _initialAnswers.value = answers
            
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
            _initialAttributions.value = attributions
            
            // Find patient
            val patient = if (!entry.patientId.isNullOrBlank()) {
                repository.getPatientByUid(entry.patientId)
            } else {
                null
            }
            _wizardSelectedPatient.value = patient
            _initialPatient.value = patient
        }
    }

    fun saveEntry(template: FormTemplate, patient: Patient?, title: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val serialMap = _solveAnswers.value
            val attribs = _solveAttributions.value

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

            val entryUid = _editingEntryUid.value ?: UUID.randomUUID().toString()
            val existingEntry = if (_editingEntryUid.value != null) repository.getEntryByUid(entryUid) else null

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
            if (_editingEntryUid.value != null) {
                repository.deleteAnswersForEntry(entryUid)
            }

            // Dynamic indexes table mapping
            for ((fId, vText) in serialMap) {
                val attAtId = attribs[fId]
                var performedByMeValue: Boolean? = null
                if (vText.startsWith("{")) {
                    try {
                        val obj = org.json.JSONObject(vText)
                        if (obj.has("performedByMe")) {
                            performedByMeValue = obj.getBoolean("performedByMe")
                        }
                    } catch (e: Exception) {}
                }

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
                        performedByMe = performedByMeValue
                    )
                )
            }

            clearSolveAnswers()
            onSuccess()
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
        viewModelScope.launch {
            val newTemplateId = UUID.randomUUID().toString()
            val newTemplate = FormTemplate(
                uid = newTemplateId,
                name = "${original.name} (Copy)",
                isActive = original.isActive
            )
            repository.insertTemplate(newTemplate)

            // Copy sections, fields, and options
            val sections = repository.getSectionsForTemplateDirect(original.uid)
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
                    repository.insertField(
                        f.copy(
                            uid = newFieldId,
                            sectionId = newSecId
                        )
                    )

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

    fun deletePatient(patient: Patient) {
        viewModelScope.launch {
            repository.deletePatient(patient.uid)
        }
    }

    fun deleteTemplate(template: FormTemplate) {
        viewModelScope.launch {
            repository.deleteTemplate(template.uid)
        }
    }

    fun deleteFormEntry(uid: String) {
        viewModelScope.launch {
            repository.deleteEntry(uid)
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

    fun restoreBackup(jsonString: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val obj = org.json.JSONObject(jsonString)
                val schemaVersion = obj.optInt("schemaVersion", 1)
                
                // Read templates
                val templatesArr = obj.optJSONArray("templates")
                if (templatesArr != null) {
                    for (i in 0 until templatesArr.length()) {
                        val tJson = templatesArr.getJSONObject(i)
                        val t = FormTemplate(
                            uid = tJson.getString("uid"),
                            name = tJson.getString("name"),
                            isActive = tJson.optBoolean("isActive", true),
                            createdAt = tJson.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = tJson.optLong("updatedAt", System.currentTimeMillis())
                        )
                        repository.insertTemplate(t)
                    }
                }

                // Read patients
                val patientsArr = obj.optJSONArray("patients")
                if (patientsArr != null) {
                    for (i in 0 until patientsArr.length()) {
                        val pJson = patientsArr.getJSONObject(i)
                        val p = Patient(
                            uid = pJson.getString("uid"),
                            name = pJson.getString("name"),
                            medicalId = pJson.getString("medicalId"),
                            phone = pJson.optString("phone", null).takeIf { it != "null" && !it.isNullOrBlank() },
                            age = pJson.optString("age", null).takeIf { it != "null" && !it.isNullOrBlank() },
                            insurance = pJson.optString("insurance", null).takeIf { it != "null" && !it.isNullOrBlank() },
                            notes = pJson.optString("notes", null).takeIf { it != "null" && !it.isNullOrBlank() },
                            customAnswersJson = pJson.optString("customAnswersJson", null).takeIf { it != "null" && !it.isNullOrBlank() },
                            createdAt = pJson.optLong("createdAt", System.currentTimeMillis())
                        )
                        repository.insertPatient(p)
                    }
                }

                // Read logs
                val logsArr = obj.optJSONArray("logs")
                if (logsArr != null) {
                    for (i in 0 until logsArr.length()) {
                        val eJson = logsArr.getJSONObject(i)
                        val e = FormEntry(
                            uid = eJson.getString("uid"),
                            templateId = eJson.getString("templateId"),
                            patientId = eJson.optString("patientId", null).takeIf { it != "null" && !it.isNullOrBlank() },
                            templateName = eJson.optString("templateName", null).takeIf { it != "null" && !it.isNullOrBlank() },
                            patientName = eJson.optString("patientName", null).takeIf { it != "null" && !it.isNullOrBlank() },
                            title = eJson.getString("title"),
                            answersJson = eJson.getString("answersJson"),
                            attributionsJson = eJson.optString("attributionsJson", null).takeIf { it != "null" && !it.isNullOrBlank() },
                            createdAt = eJson.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = eJson.optLong("updatedAt", System.currentTimeMillis())
                        )
                        repository.insertEntry(e)
                    }
                }

                onSuccess()
            } catch (e: Exception) {
                onError("Failed to restore: ${e.localizedMessage}")
            }
        }
    }

    fun getGeminiApiKey(): String {
        val prefs = context.getSharedPreferences("logimind_prefs", Context.MODE_PRIVATE)
        return prefs.getString("gemini_api_key", "") ?: ""
    }

    fun saveGeminiApiKey(apiKey: String) {
        val prefs = context.getSharedPreferences("logimind_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("gemini_api_key", apiKey.trim()).apply()
        com.example.data.service.AiSummaryService.customApiKey = apiKey.trim()
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
