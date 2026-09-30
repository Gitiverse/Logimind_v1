package com.example.data.service

import com.example.data.model.ConditionalLogic
import com.example.data.model.FormEntry
import com.example.data.model.FormField
import com.example.data.model.FormSection
import com.example.data.model.Patient
import com.example.data.model.ProcedureAnswer
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// What the summary is for. The app picks a default from the selected logs (defaultPurpose).
enum class SummaryPurpose(val label: String) {
    CASE("Case summary"),
    TIMELINE("Patient timeline"),
    LOGBOOK("Logbook")
}

const val NOT_RECORDED = "Not recorded"

data class SummaryAnswer(val question: String, val answer: String)
data class SummarySection(val title: String, val answers: List<SummaryAnswer>)
data class SummaryProcedure(
    val name: String,
    val performedByMe: Boolean?, // null = role not recorded
    val supervisor: String,
    val verified: Boolean
)

data class SummaryLog(
    val number: Int,
    val date: Long,
    val patientAlias: String?, // "Patient 1"; null for walk-in / theatre logs
    val templateName: String,
    val sections: List<SummarySection>,
    val procedures: List<SummaryProcedure>
)

// A patient as the AI sees them: alias and age only. realName/mrn stay on the phone.
data class SummaryPatient(val alias: String, val age: String?, val realName: String, val mrn: String)

// De-identified, structured input shared by the offline and the Gemini summaries.
data class SummaryInput(
    val purpose: SummaryPurpose,
    val patients: List<SummaryPatient>,
    val logs: List<SummaryLog>
) {
    private val dateFormat get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun formatDate(ms: Long): String = dateFormat.format(Date(ms))

    // Exactly what is sent to a cloud engine: no names, MRNs, phone numbers or entry titles.
    fun toCloudJson(): JSONObject = JSONObject().apply {
        put("purpose", purpose.label)
        put("patients", JSONArray().apply {
            patients.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.alias)
                    put("age", p.age ?: NOT_RECORDED)
                })
            }
        })
        put("logs", JSONArray().apply {
            logs.forEach { log ->
                put(JSONObject().apply {
                    put("log", log.number)
                    put("date", formatDate(log.date))
                    put("patient", log.patientAlias ?: "No patient profile")
                    put("form", log.templateName)
                    put("sections", JSONArray().apply {
                        log.sections.forEach { sec ->
                            put(JSONObject().apply {
                                put("title", sec.title)
                                put("answers", JSONArray().apply {
                                    sec.answers.forEach { a ->
                                        put(JSONObject().apply {
                                            put("question", a.question)
                                            put("answer", a.answer)
                                        })
                                    }
                                })
                            })
                        }
                    })
                    put("procedures", JSONArray().apply {
                        log.procedures.forEach { p ->
                            put(JSONObject().apply {
                                put("procedure", p.name)
                                put("role", roleText(p.performedByMe))
                                put("supervisor", p.supervisor.ifBlank { NOT_RECORDED })
                                put("supervisor_verified", p.verified)
                            })
                        }
                    })
                })
            }
        })
    }

    // Shown under the summary on the phone only: maps aliases back to real patients.
    fun patientKey(): String = patients.joinToString("\n") { "${it.alias} = ${it.realName} (${it.mrn})" }

    companion object {
        fun roleText(performedByMe: Boolean?): String = when (performedByMe) {
            true -> "performed by me"
            false -> "assisted / performed by another clinician"
            null -> "role not recorded"
        }
    }
}

object SummaryInputBuilder {

    // One log -> case summary; several logs of one patient -> timeline; otherwise logbook.
    fun defaultPurpose(entries: List<FormEntry>): SummaryPurpose {
        if (entries.size <= 1) return SummaryPurpose.CASE
        val patientIds = entries.map { it.patientId }.distinct()
        return if (patientIds.size == 1 && patientIds.first() != null) SummaryPurpose.TIMELINE else SummaryPurpose.LOGBOOK
    }

    fun build(
        purpose: SummaryPurpose,
        entries: List<FormEntry>,
        sections: List<FormSection>,
        fields: List<FormField>,
        patients: List<Patient>
    ): SummaryInput {
        val sortedEntries = entries.sortedBy { it.createdAt }
        val patientById = patients.associateBy { it.uid }
        val aliasById = LinkedHashMap<String, String>()
        sortedEntries.mapNotNull { it.patientId }.filter { it in patientById }.distinct().forEachIndexed { i, id ->
            aliasById[id] = "Patient ${i + 1}"
        }
        val sectionById = sections.associateBy { it.uid }
        val fieldById = fields.associateBy { it.uid }

        val logs = sortedEntries.mapIndexed { index, entry ->
            val answers = parseMap(entry.answersJson)
            val attributions = parseAttributions(entry.attributionsJson)
            val templateSections = sections.filter { it.templateId == entry.templateId }.sortedBy { it.sortOrder }
            val templateFields = fields.filter { f -> templateSections.any { it.uid == f.sectionId } }
            val visible = ConditionalLogic.visibleFieldUids(templateFields, answers)

            val summarySections = templateSections.mapNotNull { sec ->
                val items = templateFields.filter { it.sectionId == sec.uid && it.uid in visible }
                    .sortedBy { it.sortOrder }
                    .filterNot { it.isProcedure }
                    .mapNotNull { f ->
                        val raw = answers[f.uid]
                        when {
                            raw.isNullOrBlank() -> SummaryAnswer(f.label, NOT_RECORDED)
                            raw == "true" -> SummaryAnswer(f.label, "Yes")
                            raw == "false" -> SummaryAnswer(f.label, "No")
                            ProcedureAnswer.isProcedure(raw) -> null
                            else -> SummaryAnswer(f.label, raw)
                        }
                    }
                if (items.isEmpty()) null else SummarySection(sec.title, items)
            }.toMutableList()

            // Answers to questions no longer in the template (deleted later) are still reported.
            val orphanAnswers = answers.filterKeys { it !in fieldById && !ProcedureAnswer.isProcedure(answers[it] ?: "") }
                .filterValues { it.isNotBlank() }
                .map { (_, v) -> SummaryAnswer("Deleted question", v) }
            if (orphanAnswers.isNotEmpty()) summarySections += SummarySection("Other answers", orphanAnswers)

            val procedures = answers.flatMap { (key, value) ->
                val currentLabel = fieldById[key]?.label
                val stored = try { JSONObject(value).optString("procedure") } catch (e: Exception) { "" }
                val attribution = attributions[key]
                ProcedureAnswer.completedItemsDetailed(value).map { item ->
                    SummaryProcedure(
                        name = if (currentLabel != null) currentLabel + item.name.removePrefix(stored) else item.name,
                        performedByMe = item.performedByMe,
                        supervisor = attribution?.first ?: "",
                        verified = attribution?.second ?: false
                    )
                }
            }

            SummaryLog(
                number = index + 1,
                date = entry.createdAt,
                patientAlias = entry.patientId?.let { aliasById[it] },
                templateName = entry.templateName ?: "Form",
                sections = summarySections,
                procedures = procedures
            )
        }

        val summaryPatients = aliasById.map { (id, alias) ->
            val p = patientById.getValue(id)
            SummaryPatient(alias = alias, age = p.age?.takeIf { it.isNotBlank() }, realName = p.name, mrn = p.medicalId)
        }
        return SummaryInput(purpose, summaryPatients, logs)
    }

    private fun parseMap(json: String): Map<String, String> = try {
        val o = JSONObject(json)
        o.keys().asSequence().associateWith { o.optString(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    // fieldUid -> (supervisor, verified)
    private fun parseAttributions(json: String?): Map<String, Pair<String, Boolean>> = try {
        if (json.isNullOrBlank()) emptyMap() else {
            val o = JSONObject(json)
            o.keys().asSequence().mapNotNull { k ->
                o.optJSONObject(k)?.let { a -> k to (a.optString("supervisorWitness") to a.optBoolean("isWitnessVerified")) }
            }.toMap()
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
