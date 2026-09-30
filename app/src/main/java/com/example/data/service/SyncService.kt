package com.example.data.service

import com.example.data.model.FormEntry
import com.example.data.model.FormTemplate
import com.example.data.model.Patient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
    data class Success(val itemsSyncedCount: Int, val timestamp: Long) : SyncState()
    data class Error(val errorMsg: String) : SyncState()
}

interface CloudSyncRepository {
    suspend fun uploadTemplateToCloud(template: FormTemplate): Boolean
    suspend fun uploadPatientToCloud(patient: Patient): Boolean
    suspend fun uploadEntryToCloud(entry: FormEntry): Boolean
    suspend fun downloadLatestClinicalUpdates(): List<FormTemplate>
}

class SyncPlaceholderService : CloudSyncRepository {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Stub backend sync method that mimics standard full sync pipelines
    suspend fun requestFullSync(
        localTemplates: List<FormTemplate>,
        localPatients: List<Patient>,
        localEntries: List<FormEntry>
    ): SyncState {
        _syncState.value = SyncState.Syncing
        try {
            // Emulate cloud ping network delays
            delay(1500)
            
            val totalItems = localTemplates.size + localPatients.size + localEntries.size
            _syncState.value = SyncState.Success(totalItems, System.currentTimeMillis())
        } catch (e: Exception) {
            _syncState.value = SyncState.Error(e.message ?: "Sync connection interrupted")
        }
        return _syncState.value
    }

    override suspend fun uploadTemplateToCloud(template: FormTemplate): Boolean {
        // Will wire up collections referencing Firestore: db.collection("templates").document(template.uid).set(template)
        return true
    }

    override suspend fun uploadPatientToCloud(patient: Patient): Boolean {
        // Firestore linking: db.collection("patients").document(patient.uid).set(patient)
        return true
    }

    override suspend fun uploadEntryToCloud(entry: FormEntry): Boolean {
        // Firestore linking: db.collection("entries").document(entry.uid).set(entry)
        return true
    }

    override suspend fun downloadLatestClinicalUpdates(): List<FormTemplate> {
        return emptyList()
    }
}
