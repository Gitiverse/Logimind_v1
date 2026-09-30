package com.example.data.service

import com.example.data.model.FormEntry
import com.example.data.model.ProcedureAnswer
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// Structured medical dataset to improve AI precision and consistency
data class NormalizedMedicalLog(
    val uid: String,
    val title: String,
    val dateStr: String,
    val createdAt: Long = 0L,
    val patientName: String,
    val templateName: String,
    val chiefComplaintsOrSymptoms: List<String>,
    val proceduresObserved: List<String>,
    val medicationsListed: List<String>,
    val outcomesAndStability: String,
    val onDutyPerformer: String,
    val clinicalNotes: String,
    val proceduresPerformedByMe: List<String> = emptyList(),
    val proceduresPerformedByOthers: List<String> = emptyList()
)

// AI Provider interface matching requirements
interface AiSummaryProvider {
    val id: String
    val displayName: String
    val isOfflineSupported: Boolean
    val isPremiumOnly: Boolean
    
    suspend fun generateCaseSummary(
        logs: List<NormalizedMedicalLog>,
        style: String // "short", "bullets", "timeline", "clinical"
    ): String
}

object AiSummaryService {

    @Volatile
    var customApiKey: String = ""

    // Written only when data is missing; the summary never invents clinical content.
    private const val NOT_RECORDED = "Not recorded"

    // 1. Data Normalization pipeline - Converts dynamic schema logs into structured clinical fields.
    // Answers are keyed by field UID, so classification uses the question label from fieldLabelMap.
    fun normalizeLogs(entries: List<FormEntry>, fieldLabelMap: Map<String, String> = emptyMap()): List<NormalizedMedicalLog> {
        val format = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
        return entries.map { entry ->
            val symptomsList = mutableListOf<String>()
            val proceduresList = mutableListOf<String>()
            val medsList = mutableListOf<String>()
            val proceduresMeList = mutableListOf<String>()
            val proceduresOthersList = mutableListOf<String>()
            val outcomes = mutableListOf<String>()
            val notesList = mutableListOf<String>()
            val otherAnswers = mutableListOf<String>()
            var performer = NOT_RECORDED
 
            try {
                if (!entry.answersJson.isBlank()) {
                    val answers = JSONObject(entry.answersJson)
                    val keys = answers.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = answers.optString(key)
                        if (value.isBlank()) continue
                        val label = fieldLabelMap[key] ?: key.replace("field-", "").replace("-", " ")
                        val match = label.lowercase()

                        if (ProcedureAnswer.isProcedure(value)) {
                            for (item in ProcedureAnswer.completedItems(value)) {
                                if (item.performedByMe) proceduresMeList.add(item.name) else proceduresOthersList.add(item.name)
                            }
                        } else {
                            val display = when (value) {
                                "true" -> "Yes"
                                "false" -> "No"
                                else -> value
                            }
                            val line = "$label: $display"
                            // Infer structure from the question label; anything else is still reported.
                            if (match.contains("symptom") || match.contains("history") || match.contains("complaint") || match.contains("severity")) {
                                symptomsList.add(line)
                            } else if (match.contains("bp") || match.contains("vital") || match.contains("stable") || match.contains("outcome")) {
                                outcomes.add(line)
                            } else if (match.contains("technique") || match.contains("procedure") || match.contains("bypass") || match.contains("airway")) {
                                proceduresList.add(line)
                            } else if (match.contains("med") || match.contains("drug") || match.contains("dose")) {
                                medsList.add(line)
                            } else if (match.contains("note") || match.contains("comment") || match.contains("description")) {
                                notesList.add(line)
                            } else {
                                otherAnswers.add(line)
                            }
                        }
                    }
                }
                
                // Add structured sublists to the main procedures list
                proceduresList.addAll(proceduresMeList)
                proceduresList.addAll(proceduresOthersList)

                // Extract performer from the first recorded attribution if present
                if (!entry.attributionsJson.isNullOrBlank()) {
                    val atts = JSONObject(entry.attributionsJson)
                    val keys = atts.keys()
                    if (keys.hasNext()) {
                        val firstAttr = atts.optJSONObject(keys.next())
                        if (firstAttr != null) {
                            val name = firstAttr.optString("providerName", "")
                            val type = firstAttr.optString("providerType", "")
                            if (name.isNotBlank()) {
                                performer = "$name ($type)"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                notesList.add("Error processing: ${e.localizedMessage}")
            }
 
            NormalizedMedicalLog(
                uid = entry.uid,
                title = entry.title,
                dateStr = format.format(Date(entry.createdAt)),
                createdAt = entry.createdAt,
                patientName = entry.patientName ?: "Theatre Profile",
                templateName = entry.templateName ?: "Dynamic Checklist",
                chiefComplaintsOrSymptoms = (symptomsList + otherAnswers).distinct(),
                proceduresObserved = proceduresList.distinct(),
                medicationsListed = medsList.distinct(),
                outcomesAndStability = outcomes.joinToString("; ").ifBlank { NOT_RECORDED },
                onDutyPerformer = performer,
                clinicalNotes = notesList.joinToString("; ").ifBlank { NOT_RECORDED },
                proceduresPerformedByMe = proceduresMeList.distinct(),
                proceduresPerformedByOthers = proceduresOthersList.distinct()
            )
        }
    }

    // 2. Concrete AI Providers List
    val providers: List<AiSummaryProvider> = listOf(
        LocalOfflineProvider(),
        GeminiProProvider(),
        OpenAiGpt4Provider(),
        ClaudeSonnetProvider()
    )

    // --- Provider Implementation 1: Local Offline (Rule-Based High Fidelity) ---
    class LocalOfflineProvider : AiSummaryProvider {
        override val id = "local_offline"
        override val displayName = "LogiMind Local AI (Offline)"
        override val isOfflineSupported = true
        override val isPremiumOnly = false

        override suspend fun generateCaseSummary(
            logs: List<NormalizedMedicalLog>,
            style: String
        ): String {
            if (logs.isEmpty()) return "No clinical entries provided to formulate a summary."

            val sb = java.lang.StringBuilder()
            val patientName = logs.firstOrNull()?.patientName ?: "Theatre"

            when (style) {
                "short" -> {
                    sb.append("CLINICAL CASE SUMMARY FOR PATIENT: $patientName\n")
                    sb.append("A synthesis of ${logs.size} clinical session logs shows a medical trajectory focused on ${logs.map { it.templateName }.distinct().joinToString(", ")}. ")
                    
                    val pbMe = logs.flatMap { it.proceduresPerformedByMe }.distinct()
                    val pbOthers = logs.flatMap { it.proceduresPerformedByOthers }.distinct()
                    if (pbMe.isNotEmpty()) {
                        sb.append("Doctor personally performed procedure(s): ${pbMe.joinToString(", ")}. ")
                    }
                    if (pbOthers.isNotEmpty()) {
                        sb.append("Other procedures performed by other clinicians include: ${pbOthers.joinToString(", ")}. ")
                    }

                    val symptoms = logs.flatMap { it.chiefComplaintsOrSymptoms }.distinct()
                    if (symptoms.isNotEmpty()) {
                        sb.append("Clinical assessment findings noted: ${symptoms.joinToString(", ")}. ")
                    }
                    
                    sb.append("Current status is documented as: ${logs.map { it.outcomesAndStability }.joinToString("; ")}. ")
                    sb.append("Recorded by: ${logs.map { it.onDutyPerformer }.distinct().joinToString(", ")}.")
                }
                
                "bullets" -> {
                    sb.append("CASE ANALYSIS OUTLINE: $patientName\n")
                    sb.append("========================================\n")
                    sb.append("• Trajectory: Summarizing ${logs.size} clinical logs under EMR templates.\n")
                    
                    val symptoms = logs.flatMap { it.chiefComplaintsOrSymptoms }.distinct()
                    if (symptoms.isNotEmpty()) {
                        sb.append("• Recorded Findings: ${symptoms.joinToString("; ")}\n")
                    }
                    
                    val pbMe = logs.flatMap { it.proceduresPerformedByMe }.distinct()
                    val pbOthers = logs.flatMap { it.proceduresPerformedByOthers }.distinct()
                    if (pbMe.isNotEmpty()) {
                        sb.append("• Procedures Personally Performed: ${pbMe.joinToString(", ")}\n")
                    }
                    if (pbOthers.isNotEmpty()) {
                        sb.append("• Procedures Performed by Others: ${pbOthers.joinToString(", ")}\n")
                    }
                    
                    val meds = logs.flatMap { it.medicationsListed }.distinct()
                    if (meds.isNotEmpty()) {
                        sb.append("• Pharmacotherapy: ${meds.joinToString(", ")}\n")
                    } else {
                        sb.append("• Pharmacotherapy: $NOT_RECORDED\n")
                    }
                    
                    sb.append("• Overall Patient Outcomes: ${logs.map { it.outcomesAndStability }.distinct().joinToString("; ")}\n")
                    sb.append("• Staff Hand-off Attribution: ${logs.map { it.onDutyPerformer }.distinct().joinToString(", ")}\n")
                }
                
                "timeline" -> {
                    sb.append("CLINICAL MEDICAL RECORD TIMELINE: $patientName\n")
                    sb.append("========================================\n")
                    for (log in logs.sortedBy { it.createdAt }) {
                        sb.append("${log.dateStr} [${log.templateName}]:\n")
                        if (log.proceduresPerformedByMe.isNotEmpty()) {
                            sb.append("  - Personally Performed: ${log.proceduresPerformedByMe.joinToString(", ")}\n")
                        }
                        if (log.proceduresPerformedByOthers.isNotEmpty()) {
                            sb.append("  - Performed by Others: ${log.proceduresPerformedByOthers.joinToString(", ")}\n")
                        }
                        sb.append("  - Status/Outcome: ${log.outcomesAndStability}\n")
                        sb.append("  - Provider: ${log.onDutyPerformer}\n\n")
                    }
                }
                
                else -> { // clinical
                    sb.append("COMPREHENSIVE CLINICAL CASE OVERVIEW\n")
                    sb.append("========================================\n")
                    sb.append("Patient: $patientName\n")
                    sb.append("Assessment Logs Compiled: ${logs.size} session record(s)\n")
                    sb.append("Focus Areas: ${logs.map { it.templateName }.distinct().joinToString(" & ")}\n\n")
                    
                    sb.append("1. ASSESSMENT & PRESENTATION FINDINGS:\n")
                    val complaints = logs.flatMap { it.chiefComplaintsOrSymptoms }
                    if (complaints.isNotEmpty()) {
                        complaints.forEach { sb.append("   - $it\n") }
                    } else {
                        sb.append("   - $NOT_RECORDED\n")
                    }
                    
                    sb.append("\n2. EXECUTED THERAPEUTIC PROCEDURES:\n")
                    val pbMe = logs.flatMap { it.proceduresPerformedByMe }.distinct()
                    val pbOthers = logs.flatMap { it.proceduresPerformedByOthers }.distinct()
                    if (pbMe.isNotEmpty()) {
                        sb.append("   * Personally Performed by Doctor:\n")
                        pbMe.forEach { sb.append("     - $it\n") }
                    }
                    if (pbOthers.isNotEmpty()) {
                        sb.append("   * Performed by Other Clinicians:\n")
                        pbOthers.forEach { sb.append("     - $it\n") }
                    }
                    if (pbMe.isEmpty() && pbOthers.isEmpty()) {
                        sb.append("   - $NOT_RECORDED\n")
                    }
                    
                    sb.append("\n3. VITAL OUTCOMES & STABILITY MARGINS:\n")
                    logs.forEach { log ->
                        sb.append("   - [${log.dateStr}]: ${log.outcomesAndStability}\n")
                    }

                    sb.append("\n4. MEDICATIONS & PHARMACY ORDERS:\n")
                    val meds = logs.flatMap { it.medicationsListed }
                    if (meds.isNotEmpty()) {
                        meds.forEach { sb.append("   - $it\n") }
                    } else {
                        sb.append("   - $NOT_RECORDED\n")
                    }

                    sb.append("\n5. COMPLICATIONS & CLINICAL NOTES:\n")
                    val notes = logs.map { "[${it.dateStr}]: ${it.clinicalNotes}" }
                    notes.forEach { sb.append("   - $it\n") }
                }
            }
            return sb.toString()
        }
    }

    // --- Provider 2: Gemini PRO (Prompt Builder Ready for Server Side) ---
    class GeminiProProvider : AiSummaryProvider {
        override val id = "gemini_pro"
        override val displayName = "Google Gemini Pro AI (Cloud)"
        override val isOfflineSupported = false
        override val isPremiumOnly = true

        override suspend fun generateCaseSummary(
            logs: List<NormalizedMedicalLog>,
            style: String
        ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (AiSummaryService.customApiKey.isBlank()) {
                return@withContext "⚠️ No Gemini API key saved.\n\nOpen Settings (gear icon, top right) and paste your Gemini API key, or choose \"LogiMind Local\" to summarise without internet."
            }

            // Prompt construction for server side execution
            val pBuilder = StringBuilder()
            pBuilder.append("System Prompt: You are an EMR Clinical AI Assistant. Summarize these structured medical logs under style '$style' for patient '${logs.firstOrNull()?.patientName}'. Minimize walls of text, render as clinically precise summaries under 1000 words.\n")
            pBuilder.append("CRITICAL: When summarizing, you must distinguish between procedures personally performed by the doctor ('Procedures Personally Performed by Doctor') and procedures performed by other clinicians ('Procedures Performed by Others'). Direct examples to use: 'Doctor personally performed [Procedures]' and '[Procedures] was performed by another clinician'.\n\n")
            logs.forEach { log ->
                pBuilder.append("Log Date: ${log.dateStr} | Title: ${log.title}\n")
                pBuilder.append("Findings: ${log.chiefComplaintsOrSymptoms.joinToString(", ")}\n")
                pBuilder.append("Procedures Personally Performed by Doctor: ${log.proceduresPerformedByMe.joinToString(", ")}\n")
                pBuilder.append("Procedures Performed by Others: ${log.proceduresPerformedByOthers.joinToString(", ")}\n")
                pBuilder.append("Meds: ${log.medicationsListed.joinToString(", ")}\n")
                pBuilder.append("Outcomes: ${log.outcomesAndStability}\n")
                pBuilder.append("Notes: ${log.clinicalNotes}\n")
                pBuilder.append("------------------------------------\n")
            }

            try {
                val request = GeminiRequest(
                    contents = listOf(
                        GeminiContent(
                            parts = listOf(
                                GeminiContentPart(text = pBuilder.toString())
                            )
                        )
                    )
                )
                val response = GeminiClient.service.generateContent(AiSummaryService.customApiKey, request)
                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!responseText.isNullOrBlank()) {
                    return@withContext responseText
                } else {
                    return@withContext "⚠️ Gemini returned an empty summary. Please try again."
                }
            } catch (e: retrofit2.HttpException) {
                val errDetails = try {
                    e.response()?.errorBody()?.string() ?: ""
                } catch (ex: Throwable) {
                    ""
                }
                val reason = when (e.code()) {
                    400, 401, 403 -> "The API key was not accepted. Check it in Settings."
                    429 -> "Gemini's usage limit was reached. Wait a little and try again, or check your quota in Google AI Studio."
                    in 500..599 -> "Gemini is having problems right now. Try again later."
                    else -> "Gemini could not create the summary."
                }
                val details = if (errDetails.isNotBlank()) "\n\nTechnical details (error ${e.code()}): ${errDetails.take(300)}" else ""
                return@withContext "❌ $reason$details"
            } catch (e: Throwable) {
                return@withContext "❌ Could not reach Gemini. Check the internet connection and try again.\n\nTechnical details: ${e.localizedMessage ?: e.toString()}"
            }
        }
    }

    // --- Provider 3: OpenAI GPT-4 (SaaS monetization ready) ---
    class OpenAiGpt4Provider : AiSummaryProvider {
        override val id = "openai_gpt4"
        override val displayName = "OpenAI GPT-4 Turbo"
        override val isOfflineSupported = false
        override val isPremiumOnly = true

        override suspend fun generateCaseSummary(
            logs: List<NormalizedMedicalLog>,
            style: String
        ): String {
            return "⚠️ NOT AVAILABLE YET\n\nThe GPT-4 engine is not connected in this version. Use LogiMind Local or Gemini."
        }
    }

    // --- Provider 4: Anthropic Claude 3 (SaaS monetization ready) ---
    class ClaudeSonnetProvider : AiSummaryProvider {
        override val id = "claude_sonnet"
        override val displayName = "Anthropic Claude 3.5 Sonnet"
        override val isOfflineSupported = false
        override val isPremiumOnly = true

        override suspend fun generateCaseSummary(
            logs: List<NormalizedMedicalLog>,
            style: String
        ): String {
            return "⚠️ NOT AVAILABLE YET\n\nThe Claude engine is not connected in this version. Use LogiMind Local or Gemini."
        }
    }
}

// --- Moshi Data Classes for Gemini REST API ---
@JsonClass(generateAdapter = true)
data class GeminiContentPart(
    @Json(name = "text") val text: String
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    @Json(name = "parts") val parts: List<GeminiContentPart>
)

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    @Json(name = "contents") val contents: List<GeminiContent>
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    @Json(name = "content") val content: GeminiContent?
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    @Json(name = "candidates") val candidates: List<GeminiCandidate>?
)

// --- Retrofit & OkHttp client for Gemini API ---
interface GeminiApiService {
    @POST("v1beta/models/gemini-2.5-flash-lite:generateContent")
    suspend fun generateContent(
        // Header, not a URL query parameter, so the key does not end up in URL logs.
        @Header("x-goog-api-key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

object GeminiClient {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val moshi = com.squareup.moshi.Moshi.Builder()
        .build()

    val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }
}
