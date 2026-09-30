package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "form_templates")
data class FormTemplate(
    @PrimaryKey val uid: String,
    val name: String,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "form_sections",
    foreignKeys = [
        ForeignKey(
            entity = FormTemplate::class,
            parentColumns = ["uid"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["templateId"])]
)
data class FormSection(
    @PrimaryKey val uid: String,
    val templateId: String,
    val title: String,
    val sortOrder: Int,
    val isExpandable: Boolean = false
)

@Entity(
    tableName = "form_fields",
    foreignKeys = [
        ForeignKey(
            entity = FormSection::class,
            parentColumns = ["uid"],
            childColumns = ["sectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sectionId"])]
)
data class FormField(
    @PrimaryKey val uid: String,
    val sectionId: String,
    val type: String,
    val label: String,
    val hint: String? = null,
    val isRequired: Boolean = false,
    val sortOrder: Int,
    val conditionalLogicJson: String? = null,
    val defaultValue: String? = null,
    val isProcedure: Boolean = false,
    val askIfPerformedByMe: Boolean = false,
    val startMinimized: Boolean = false
)

@Entity(
    tableName = "form_field_options",
    foreignKeys = [
        ForeignKey(
            entity = FormField::class,
            parentColumns = ["uid"],
            childColumns = ["fieldId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["fieldId"])]
)
data class FormFieldOption(
    @PrimaryKey val uid: String,
    val fieldId: String,
    val label: String,
    val sortOrder: Int,
    val isAdvanced: Boolean = false
)

@Entity(tableName = "patients")
data class Patient(
    @PrimaryKey val uid: String,
    val name: String,
    val medicalId: String,
    val phone: String? = null,
    val age: String? = null,
    val insurance: String? = null,
    val notes: String? = null,
    val customAnswersJson: String? = null, // Holds flexible additional fields in JSON format
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "form_entries",
    foreignKeys = [
        ForeignKey(
            entity = FormTemplate::class,
            parentColumns = ["uid"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["templateId"])]
)
data class FormEntry(
    @PrimaryKey val uid: String,
    val templateId: String,
    val patientId: String? = null,
    val templateName: String? = null,
    val patientName: String? = null,
    val title: String,
    val answersJson: String, // Hybrid architecture: Raw JSON string for lightning-fast list & grid views
    val attributionsJson: String? = null, // Stores procedure attributions lightning fast
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "entry_answers",
    foreignKeys = [
        ForeignKey(
            entity = FormEntry::class,
            parentColumns = ["uid"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["entryId"])]
)
data class EntryAnswer(
    @PrimaryKey val uid: String,
    val entryId: String,
    val fieldId: String,
    var value: String,
    val providerType: String? = null,
    val providerName: String? = null,
    val providerId: String? = null,
    val supervisorWitness: String? = null,
    val isWitnessVerified: Boolean = false,
    val performedByMe: Boolean? = null
)

// --- Procedure Attribution Helper Data Model ---
data class ProcedureAttributionData(
    val fieldId: String,
    val providerType: String = "Student", // "Student", "Resident", "Attending"
    val providerName: String = "",
    val providerId: String = "",
    val supervisorWitness: String = "",
    val isWitnessVerified: Boolean = false
)

// --- Dynamic Demographic Fields Configuration Map ---
data class PatientFieldConfig(
    val id: String,
    val label: String,
    val isActive: Boolean,
    val isRequired: Boolean
)
