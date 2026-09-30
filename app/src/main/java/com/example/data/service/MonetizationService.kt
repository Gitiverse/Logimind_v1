package com.example.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SubscriptionTier {
    FREE,
    PREMIUM
}

data class PlanFeature(
    val name: String,
    val description: String,
    val isPremiumOnly: Boolean
)

object SubscriptionManager {
    private val _currentTier = MutableStateFlow(SubscriptionTier.PREMIUM)
    val currentTier: StateFlow<SubscriptionTier> = _currentTier.asStateFlow()

    val features = listOf(
        PlanFeature("Scalable Blueprints", "Create and duplicate clinical EMR templates", false),
        PlanFeature("Dynamic Form Engine", "Expandable sections and conditional question logic", false),
        PlanFeature("Custom Intake Questions", "Choose patient registration questionnaire options", false),
        PlanFeature("Advanced AI Patient Summary", "Summarize symptoms, surgery history, timelines, and medications", true),
        PlanFeature("Secure Cloud Sync & Backup", "Sync clinical logs between phone and web terminals", true),
        PlanFeature("PDF Report Export", "Generate ready-to-print clinical reports of checkups", true),
        PlanFeature("Team Sharing & EMR", "Share logs securely with on-duty surgical and ICU staff", true)
    )

    fun toggleSubscription() {
        _currentTier.value = if (_currentTier.value == SubscriptionTier.FREE) {
            SubscriptionTier.PREMIUM
        } else {
            SubscriptionTier.FREE
        }
    }

    fun isFeatureAvailable(featureName: String): Boolean {
        val f = features.find { it.name == featureName } ?: return true
        if (f.isPremiumOnly && _currentTier.value == SubscriptionTier.FREE) {
            return false
        }
        return true
    }

    // Free tier constraints checking
    fun canCreateTemplate(existingTemplatesCount: Int): Boolean {
        if (_currentTier.value == SubscriptionTier.FREE && existingTemplatesCount >= 3) {
            return false // Free users limited to 3 blueprints
        }
        return true
    }

    fun canUseAiSummaries(existingSummariesCount: Int): Boolean {
        if (_currentTier.value == SubscriptionTier.FREE && existingSummariesCount >= 2) {
            return false // Free users limited to 2 AI preview summarization attempts
        }
        return true
    }
}
