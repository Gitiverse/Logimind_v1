package com.example.data.service

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

interface AiSummaryProvider {
    val id: String
    val displayName: String
    val sendsDataOffDevice: Boolean

    suspend fun generate(input: SummaryInput): String
}

object AiSummaryService {

    @Volatile
    var customApiKey: String = ""

    // "gemini-2.5-flash-lite" (fast, default) or "gemini-2.5-flash" (better quality).
    @Volatile
    var geminiModel: String = MODEL_FAST

    const val MODEL_FAST = "gemini-2.5-flash-lite"
    const val MODEL_BETTER = "gemini-2.5-flash"

    // Added by the app under every summary (never left to the model).
    const val FOOTER = "AI draft from logged data — check before clinical use."

    val providers: List<AiSummaryProvider> = listOf(LocalOfflineProvider(), GeminiProvider())

    // ---------------- Prompt (shared rules + one instruction per purpose) ----------------

    const val SYSTEM_INSTRUCTION = """You summarise clinical logbook entries for the clinician who recorded them.
Rules:
- Use ONLY the data provided in the JSON. Never add diagnoses, medications, vital signs, test results, recommendations or plans that are not in the data.
- If something relevant is missing, write "Not recorded". Do not guess or speculate.
- Keep each procedure's role exactly as given: "performed by me", "assisted / performed by another clinician" or "role not recorded". Keep supervisor names and whether they verified exactly as given.
- Keep numbers, units and dates exactly as recorded.
- Refer to patients only by their id (e.g. "Patient 1"). Never invent names or identifiers.
- Plain, concise clinical English. Plain text only: headings on their own line, "- " bullets, no markdown symbols such as ** or #.
- Follow the requested format and length exactly."""

    fun purposeInstruction(purpose: SummaryPurpose): String = when (purpose) {
        SummaryPurpose.CASE -> """Write a CASE SUMMARY of the log(s) using exactly these headings:
Summary: one sentence (patient id, age if recorded, form, date).
Findings: bullets of the key recorded answers.
Procedures: bullets "procedure — role — supervisor (verified or not)"; "None recorded" if empty.
Outcome: from the recorded answers, otherwise "Not recorded".
Not recorded: bullets of important questions that were left blank.
Maximum 150 words."""
        SummaryPurpose.TIMELINE -> """Write a PATIENT TIMELINE using exactly these headings:
Timeline: one bullet per log in date order: "date — form: key findings; procedures (role)".
Changes over time: 1 to 3 bullets on what changed between logs, only if the data shows it; otherwise "No change recorded".
Maximum 200 words."""
        SummaryPurpose.LOGBOOK -> """Write a PROCEDURE LOGBOOK summary using exactly these headings:
Performed by me: bullets "procedure — date — patient id — supervisor (verified or not)".
Assisted: same bullet format.
Role not recorded: same bullet format.
Totals: one line with counts for each heading.
Write "None" under an empty heading. Do not add clinical commentary.
Maximum 200 words."""
    }

    fun userPrompt(input: SummaryInput): String =
        purposeInstruction(input.purpose) + "\n\nData (JSON):\n" + input.toCloudJson().toString(2)

    // ---------------- Offline provider: same headings, filled directly from the data ----------------

    class LocalOfflineProvider : AiSummaryProvider {
        override val id = "local_offline"
        override val displayName = "LogiMind Local (offline)"
        override val sendsDataOffDevice = false

        override suspend fun generate(input: SummaryInput): String {
            if (input.logs.isEmpty()) return "No logs selected."
            return when (input.purpose) {
                SummaryPurpose.CASE -> caseSummary(input)
                SummaryPurpose.TIMELINE -> timeline(input)
                SummaryPurpose.LOGBOOK -> logbook(input)
            }
        }

        private fun procedureLine(p: SummaryProcedure) =
            "${p.name} — ${SummaryInput.roleText(p.performedByMe)} — " +
                (if (p.supervisor.isBlank()) "supervisor not recorded" else "supervisor ${p.supervisor} (${if (p.verified) "verified" else "not verified"})")

        private fun caseSummary(input: SummaryInput): String = buildString {
            for (log in input.logs) {
                val age = input.patients.find { it.alias == log.patientAlias }?.age
                appendLine("Summary:")
                appendLine("${log.patientAlias ?: "No patient profile"}${if (age != null) ", age $age" else ""} — ${log.templateName} — ${input.formatDate(log.date)}")
                val answered = log.sections.flatMap { it.answers }.filter { it.answer != NOT_RECORDED }
                val missing = log.sections.flatMap { it.answers }.filter { it.answer == NOT_RECORDED }
                appendLine("Findings:")
                if (answered.isEmpty()) appendLine("- $NOT_RECORDED") else answered.forEach { appendLine("- ${it.question}: ${it.answer}") }
                appendLine("Procedures:")
                if (log.procedures.isEmpty()) appendLine("- None recorded") else log.procedures.forEach { appendLine("- ${procedureLine(it)}") }
                appendLine("Not recorded:")
                if (missing.isEmpty()) appendLine("- Nothing left blank") else missing.forEach { appendLine("- ${it.question}") }
                if (input.logs.size > 1) appendLine()
            }
        }.trim()

        private fun timeline(input: SummaryInput): String = buildString {
            appendLine("Timeline:")
            for (log in input.logs) {
                val findings = log.sections.flatMap { it.answers }.filter { it.answer != NOT_RECORDED }
                    .joinToString("; ") { "${it.question}: ${it.answer}" }.ifBlank { NOT_RECORDED }
                val procs = if (log.procedures.isEmpty()) "" else "; procedures: " +
                    log.procedures.joinToString(", ") { "${it.name} (${SummaryInput.roleText(it.performedByMe)})" }
                appendLine("- ${input.formatDate(log.date)} — ${log.templateName}: $findings$procs")
            }
            appendLine("Changes over time:")
            appendLine("- Compare the entries above (the offline summary does not interpret changes).")
        }.trim()

        private fun logbook(input: SummaryInput): String = buildString {
            val rows = input.logs.flatMap { log -> log.procedures.map { log to it } }
            fun section(title: String, filter: (SummaryProcedure) -> Boolean) {
                val items = rows.filter { filter(it.second) }
                appendLine("$title:")
                if (items.isEmpty()) appendLine("- None")
                items.forEach { (log, p) ->
                    val sup = if (p.supervisor.isBlank()) "supervisor not recorded" else "supervisor ${p.supervisor} (${if (p.verified) "verified" else "not verified"})"
                    appendLine("- ${p.name} — ${input.formatDate(log.date)} — ${log.patientAlias ?: "No patient profile"} — $sup")
                }
            }
            section("Performed by me") { it.performedByMe == true }
            section("Assisted") { it.performedByMe == false }
            section("Role not recorded") { it.performedByMe == null }
            append("Totals: performed by me ${rows.count { it.second.performedByMe == true }}, ")
            append("assisted ${rows.count { it.second.performedByMe == false }}, ")
            append("role not recorded ${rows.count { it.second.performedByMe == null }}")
        }.trim()
    }

    // ---------------- Gemini provider ----------------

    class GeminiProvider : AiSummaryProvider {
        override val id = "gemini_pro"
        override val displayName = "Google Gemini (cloud)"
        override val sendsDataOffDevice = true

        override suspend fun generate(input: SummaryInput): String = withContext(Dispatchers.IO) {
            if (customApiKey.isBlank()) {
                return@withContext "⚠️ No Gemini API key saved.\n\nOpen Settings (gear icon, top right) and paste your Gemini API key, or choose \"LogiMind Local\" to summarise without internet."
            }
            if (input.logs.isEmpty()) return@withContext "No logs selected."
            try {
                val request = GeminiRequest(
                    contents = listOf(GeminiContent(parts = listOf(GeminiContentPart(text = userPrompt(input))))),
                    systemInstruction = GeminiContent(parts = listOf(GeminiContentPart(text = SYSTEM_INSTRUCTION))),
                    generationConfig = GeminiGenerationConfig(temperature = 0.2, maxOutputTokens = 1024)
                )
                val response = GeminiClient.service.generateContent(geminiModel, customApiKey, request)
                val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (text.isNullOrBlank()) "⚠️ Gemini returned an empty summary. Please try again." else text.trim()
            } catch (e: retrofit2.HttpException) {
                val errDetails = try { e.response()?.errorBody()?.string() ?: "" } catch (ex: Throwable) { "" }
                val reason = when (e.code()) {
                    400, 401, 403 -> "The API key was not accepted. Check it in Settings."
                    404 -> "The selected Gemini model is not available for this key. Try the other model in Settings."
                    429 -> "Gemini's usage limit was reached. Wait a little and try again, or check your quota in Google AI Studio."
                    in 500..599 -> "Gemini is having problems right now. Try again later."
                    else -> "Gemini could not create the summary."
                }
                val details = if (errDetails.isNotBlank()) "\n\nTechnical details (error ${e.code()}): ${errDetails.take(300)}" else ""
                "❌ $reason$details"
            } catch (e: Throwable) {
                "❌ Could not reach Gemini. Check the internet connection and try again.\n\nTechnical details: ${e.localizedMessage ?: e.toString()}"
            }
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
data class GeminiGenerationConfig(
    @Json(name = "temperature") val temperature: Double? = null,
    @Json(name = "maxOutputTokens") val maxOutputTokens: Int? = null
)

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    @Json(name = "contents") val contents: List<GeminiContent>,
    @Json(name = "systemInstruction") val systemInstruction: GeminiContent? = null,
    @Json(name = "generationConfig") val generationConfig: GeminiGenerationConfig? = null
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
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
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
