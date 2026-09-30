package com.example

import com.example.data.model.FormEntry
import com.example.data.model.FormField
import com.example.data.model.FormSection
import com.example.data.model.Patient
import com.example.data.service.AiSummaryService
import com.example.data.service.GeminiContent
import com.example.data.service.GeminiContentPart
import com.example.data.service.GeminiGenerationConfig
import com.example.data.service.GeminiRequest
import com.example.data.service.SummaryInputBuilder
import com.example.data.service.SummaryPurpose
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SummaryTest {

    private val sections = listOf(FormSection(uid = "s1", templateId = "t1", title = "Assessment", sortOrder = 1))
    private val fields = listOf(
        FormField(uid = "age", sectionId = "s1", type = "number", label = "Age", sortOrder = 1),
        FormField(uid = "hx", sectionId = "s1", type = "dropdown", label = "Difficult airway history?", sortOrder = 2),
        FormField(uid = "sev", sectionId = "s1", type = "radio_group", label = "Severity", sortOrder = 3, conditionalLogicJson = "hx==Yes"),
        FormField(uid = "notes", sectionId = "s1", type = "textarea", label = "Notes", sortOrder = 4),
        FormField(uid = "lines", sectionId = "s1", type = "checkbox_group", label = "Lines", sortOrder = 5, isProcedure = true, askIfPerformedByMe = true),
        FormField(uid = "ett", sectionId = "s1", type = "checkbox", label = "Intubation", sortOrder = 6, isProcedure = true)
    )
    private val patients = listOf(Patient(uid = "p1", name = "Jane Doe", medicalId = "MRN-33019", phone = "+1 555 0192", age = "54"))

    private fun entry(uid: String, answers: String, patientId: String? = "p1", createdAt: Long = 1_000L, attributions: String? = null) =
        FormEntry(uid = uid, templateId = "t1", patientId = patientId, templateName = "Airway", patientName = "Jane Doe",
            title = "Jane Doe night shift", answersJson = answers, attributionsJson = attributions, createdAt = createdAt)

    private val answers = """{"age":"54","hx":"No","sev":"Mild",
        "lines":"{\"procedure\":\"Lines\",\"completed\":true,\"choices\":\"CVC,Art line,PICC\",\"choices_perf\":{\"CVC\":true,\"Art line\":false}}",
        "ett":"{\"procedure\":\"Intubation\",\"completed\":true,\"performedByMe\":true}"}"""
    private val attributions = """{"ett":{"fieldId":"ett","providerType":"","providerName":"","providerId":"","supervisorWitness":"Dr Khan","isWitnessVerified":true}}"""

    private fun build(purpose: SummaryPurpose = SummaryPurpose.CASE) =
        SummaryInputBuilder.build(purpose, listOf(entry("e1", answers, attributions = attributions)), sections, fields, patients)

    @Test
    fun `nothing identifying is sent to the cloud`() {
        val sent = AiSummaryService.userPrompt(build())
        listOf("Jane", "Doe", "MRN-33019", "555", "night shift").forEach { assertFalse("leaked: $it", sent.contains(it)) }
        assertTrue(sent.contains("Patient 1"))
    }

    @Test
    fun `roles and supervisors are kept exactly`() {
        val procs = build().logs.single().procedures.associateBy { it.name }
        assertEquals(true, procs.getValue("Lines: CVC").performedByMe)
        assertEquals(false, procs.getValue("Lines: Art line").performedByMe)
        assertEquals(null, procs.getValue("Lines: PICC").performedByMe) // never answered
        assertEquals("Dr Khan", procs.getValue("Intubation").supervisor)
        assertTrue(procs.getValue("Intubation").verified)
    }

    @Test
    fun `hidden questions are left out and blank visible ones are marked not recorded`() {
        val qa = build().logs.single().sections.flatMap { it.answers }.associate { it.question to it.answer }
        assertFalse("Severity is hidden when history is No", qa.containsKey("Severity"))
        assertEquals("Not recorded", qa["Notes"])
        assertEquals("54", qa["Age"])
    }

    @Test
    fun `offline logbook lists every procedure under the right heading`() = runBlocking {
        val text = AiSummaryService.LocalOfflineProvider().generate(build(SummaryPurpose.LOGBOOK))
        val performed = text.substringAfter("Performed by me:").substringBefore("Assisted:")
        val assisted = text.substringAfter("Assisted:").substringBefore("Role not recorded:")
        val unknown = text.substringAfter("Role not recorded:").substringBefore("Totals:")
        assertTrue(performed.contains("Lines: CVC") && performed.contains("Intubation") && performed.contains("Dr Khan (verified)"))
        assertTrue(assisted.contains("Lines: Art line"))
        assertTrue(unknown.contains("Lines: PICC"))
        assertTrue(text.contains("Totals: performed by me 2, assisted 1, role not recorded 1"))
        assertFalse(text.contains("Jane"))
    }

    @Test
    fun `default purpose follows the selection`() {
        val one = entry("a", "{}")
        assertEquals(SummaryPurpose.CASE, SummaryInputBuilder.defaultPurpose(listOf(one)))
        assertEquals(SummaryPurpose.TIMELINE, SummaryInputBuilder.defaultPurpose(listOf(one, entry("b", "{}"))))
        assertEquals(SummaryPurpose.LOGBOOK, SummaryInputBuilder.defaultPurpose(listOf(one, entry("c", "{}", patientId = null))))
    }

    @Test
    fun `gemini request carries the rules and a low temperature`() {
        val request = GeminiRequest(
            contents = listOf(GeminiContent(listOf(GeminiContentPart("x")))),
            systemInstruction = GeminiContent(listOf(GeminiContentPart(AiSummaryService.SYSTEM_INSTRUCTION))),
            generationConfig = GeminiGenerationConfig(temperature = 0.2, maxOutputTokens = 1024)
        )
        val json = Moshi.Builder().build().adapter(GeminiRequest::class.java).toJson(request)
        assertTrue(json.contains("\"systemInstruction\""))
        assertTrue(json.contains("Use ONLY the data provided"))
        assertTrue(json.contains("\"temperature\":0.2"))
    }
}
