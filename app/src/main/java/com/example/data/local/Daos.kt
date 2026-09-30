package com.example.data.local

import androidx.room.*
import com.example.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicalFormDao {

    // --- Templates ---
    @Query("SELECT * FROM form_templates ORDER BY createdAt DESC")
    fun getAllFormTemplatesFlow(): Flow<List<FormTemplate>>

    @Query("SELECT * FROM form_templates WHERE uid = :uid LIMIT 1")
    suspend fun getFormTemplateByUid(uid: String): FormTemplate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFormTemplate(template: FormTemplate)

    @Update
    suspend fun updateFormTemplate(template: FormTemplate)

    @Query("DELETE FROM form_templates WHERE uid = :uid")
    suspend fun deleteFormTemplateByUid(uid: String)

    // --- Sections ---
    @Query("SELECT * FROM form_sections WHERE templateId = :templateId ORDER BY sortOrder ASC")
    fun getSectionsForTemplateFlow(templateId: String): Flow<List<FormSection>>

    @Query("SELECT * FROM form_sections WHERE templateId = :templateId ORDER BY sortOrder ASC")
    suspend fun getSectionsForTemplate(templateId: String): List<FormSection>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFormSection(section: FormSection)

    @Query("DELETE FROM form_sections WHERE templateId = :templateId")
    suspend fun deleteSectionsForTemplate(templateId: String)

    @Query("DELETE FROM form_sections WHERE uid = :uid")
    suspend fun deleteFormSectionByUid(uid: String)

    // --- Fields ---
    @Query("SELECT * FROM form_fields")
    fun getAllFormFieldsFlow(): Flow<List<FormField>>

    @Query("SELECT * FROM form_fields WHERE sectionId = :sectionId ORDER BY sortOrder ASC")
    fun getFieldsForSectionFlow(sectionId: String): Flow<List<FormField>>

    @Query("SELECT * FROM form_fields WHERE sectionId = :sectionId ORDER BY sortOrder ASC")
    suspend fun getFieldsForSection(sectionId: String): List<FormField>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFormField(field: FormField)

    @Update
    suspend fun updateFormField(field: FormField)

    @Query("DELETE FROM form_fields WHERE uid = :uid")
    suspend fun deleteFormFieldByUid(uid: String)

    // --- Field Options ---
    @Query("SELECT * FROM form_field_options WHERE fieldId = :fieldId ORDER BY sortOrder ASC")
    fun getOptionsForFieldFlow(fieldId: String): Flow<List<FormFieldOption>>

    @Query("SELECT * FROM form_field_options WHERE fieldId = :fieldId ORDER BY sortOrder ASC")
    suspend fun getOptionsForField(fieldId: String): List<FormFieldOption>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFormFieldOption(option: FormFieldOption)

    @Query("DELETE FROM form_field_options WHERE fieldId = :fieldId")
    suspend fun deleteOptionsForField(fieldId: String)

    @Query("DELETE FROM form_field_options WHERE uid = :uid")
    suspend fun deleteFormFieldOptionByUid(uid: String)

    // --- Patients ---
    @Query("SELECT * FROM patients ORDER BY name ASC")
    fun getAllPatientsFlow(): Flow<List<Patient>>

    @Query("SELECT * FROM patients WHERE uid = :uid LIMIT 1")
    suspend fun getPatientByUid(uid: String): Patient?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatient(patient: Patient)

    @Query("DELETE FROM patients WHERE uid = :uid")
    suspend fun deletePatientByUid(uid: String)

    // --- Form Entries ---
    @Query("SELECT * FROM form_entries ORDER BY createdAt DESC")
    fun getAllFormEntriesFlow(): Flow<List<FormEntry>>

    @Query("SELECT * FROM form_entries WHERE uid = :uid LIMIT 1")
    suspend fun getFormEntryByUid(uid: String): FormEntry?

    @Query("SELECT * FROM form_entries WHERE patientId = :patientId ORDER BY createdAt DESC")
    fun getFormEntriesForPatientFlow(patientId: String): Flow<List<FormEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFormEntry(entry: FormEntry)

    @Query("DELETE FROM form_entries WHERE uid = :uid")
    suspend fun deleteFormEntryByUid(uid: String)

    // --- Entry Answers ---
    @Query("SELECT * FROM entry_answers WHERE entryId = :entryId")
    fun getAnswersForEntryFlow(entryId: String): Flow<List<EntryAnswer>>

    @Query("SELECT * FROM entry_answers WHERE entryId = :entryId")
    suspend fun getAnswersForEntry(entryId: String): List<EntryAnswer>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntryAnswer(answer: EntryAnswer)

    @Query("DELETE FROM entry_answers WHERE entryId = :entryId")
    suspend fun deleteAnswersForEntry(entryId: String)
}
