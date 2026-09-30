package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.MedicalFormDao
import com.example.data.model.*
import kotlinx.coroutines.flow.Flow

class FormRepository(private val database: AppDatabase) {

    private val dao: MedicalFormDao = database.medicalFormDao()

    // Runs multi-step writes atomically so a crash mid-save never leaves partial data.
    suspend fun <R> inTransaction(block: suspend () -> R): R = database.withTransaction { block() }

    // --- Templates ---
    val allTemplates: Flow<List<FormTemplate>> = dao.getAllFormTemplatesFlow()

    suspend fun getTemplateByUid(uid: String): FormTemplate? = dao.getFormTemplateByUid(uid)

    suspend fun insertTemplate(template: FormTemplate) = dao.insertFormTemplate(template)

    suspend fun updateTemplate(template: FormTemplate) = dao.updateFormTemplate(template)

    suspend fun deleteTemplate(uid: String) = dao.deleteFormTemplateByUid(uid)

    // --- Sections ---
    fun getSectionsForTemplate(templateId: String): Flow<List<FormSection>> = dao.getSectionsForTemplateFlow(templateId)

    suspend fun getSectionsForTemplateDirect(templateId: String): List<FormSection> = dao.getSectionsForTemplate(templateId)

    suspend fun insertSection(section: FormSection) = dao.insertFormSection(section)

    suspend fun deleteSection(uid: String) = dao.deleteFormSectionByUid(uid)

    suspend fun deleteSectionsForTemplate(templateId: String) = dao.deleteSectionsForTemplate(templateId)

    // --- Fields ---
    val allFields: Flow<List<FormField>> = dao.getAllFormFieldsFlow()

    fun getFieldsForSection(sectionId: String): Flow<List<FormField>> = dao.getFieldsForSectionFlow(sectionId)

    suspend fun getFieldsForSectionDirect(sectionId: String): List<FormField> = dao.getFieldsForSection(sectionId)

    suspend fun insertField(field: FormField) = dao.insertFormField(field)

    suspend fun updateField(field: FormField) = dao.updateFormField(field)

    suspend fun deleteField(uid: String) = dao.deleteFormFieldByUid(uid)

    // --- Field Options ---
    fun getOptionsForField(fieldId: String): Flow<List<FormFieldOption>> = dao.getOptionsForFieldFlow(fieldId)

    suspend fun getOptionsForFieldDirect(fieldId: String): List<FormFieldOption> = dao.getOptionsForField(fieldId)

    suspend fun insertOption(option: FormFieldOption) = dao.insertFormFieldOption(option)
    
    suspend fun deleteOptionsForField(fieldId: String) = dao.deleteOptionsForField(fieldId)

    suspend fun deleteOption(uid: String) = dao.deleteFormFieldOptionByUid(uid)

    // --- Patients ---
    val allPatients: Flow<List<Patient>> = dao.getAllPatientsFlow()

    suspend fun getPatientByUid(uid: String): Patient? = dao.getPatientByUid(uid)

    suspend fun insertPatient(patient: Patient) = dao.insertPatient(patient)

    suspend fun deletePatient(uid: String) = dao.deletePatientByUid(uid)

    // --- Entries ---
    val allEntries: Flow<List<FormEntry>> = dao.getAllFormEntriesFlow()

    suspend fun getEntryByUid(uid: String): FormEntry? = dao.getFormEntryByUid(uid)

    fun getEntriesForPatient(patientId: String): Flow<List<FormEntry>> = dao.getFormEntriesForPatientFlow(patientId)

    suspend fun insertEntry(entry: FormEntry) = dao.insertFormEntry(entry)

    suspend fun deleteEntry(uid: String) = dao.deleteFormEntryByUid(uid)

    val allSections: Flow<List<FormSection>> = dao.getAllSectionsFlow()

    suspend fun updatePatientNameInEntries(patientId: String, name: String) = dao.updatePatientNameInEntries(patientId, name)

    // --- Full backup reads ---
    suspend fun getAllSections(): List<FormSection> = dao.getAllSections()

    suspend fun getAllFields(): List<FormField> = dao.getAllFields()

    suspend fun getAllOptions(): List<FormFieldOption> = dao.getAllOptions()

    suspend fun getAllAnswers(): List<EntryAnswer> = dao.getAllAnswers()

    suspend fun countEntriesForTemplate(templateId: String): Int = dao.countEntriesForTemplate(templateId)

    // --- Answers ---
    fun getAnswersForEntry(entryId: String): Flow<List<EntryAnswer>> = dao.getAnswersForEntryFlow(entryId)

    suspend fun getAnswersForEntryDirect(entryId: String): List<EntryAnswer> = dao.getAnswersForEntry(entryId)

    suspend fun insertAnswer(answer: EntryAnswer) = dao.insertEntryAnswer(answer)

    suspend fun deleteAnswersForEntry(entryId: String) = dao.deleteAnswersForEntry(entryId)
}
