package com.example

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.EntryAnswer
import com.example.data.model.FormEntry
import com.example.data.model.FormField
import com.example.data.model.FormFieldOption
import com.example.data.model.FormSection
import com.example.data.model.FormTemplate
import com.example.data.model.Patient
import com.example.data.repository.FormRepository
import com.example.data.service.ExportService
import com.example.ui.viewmodel.FormBuilderViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataSafetyTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: FormRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Skip first-run sample data so counts below are exact.
        context.getSharedPreferences("logimind_prefs", Context.MODE_PRIVATE).edit().putBoolean("db_seeded_v1", true).commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = FormRepository(db)
        runBlocking { seed() }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        repo.insertTemplate(FormTemplate(uid = "t1", name = "Airway"))
        repo.insertSection(FormSection(uid = "s1", templateId = "t1", title = "A", sortOrder = 1))
        repo.insertField(FormField(uid = "f1", sectionId = "s1", type = "dropdown", label = "History?", sortOrder = 1))
        repo.insertOption(FormFieldOption(uid = "o1", fieldId = "f1", label = "Yes", sortOrder = 1))
        repo.insertPatient(Patient(uid = "p1", name = "Jane", medicalId = "MRN-1"))
        repo.insertEntry(FormEntry(uid = "e1", templateId = "t1", patientId = "p1", title = "Case 1", answersJson = """{"f1":"Yes"}"""))
        repo.insertAnswer(EntryAnswer(uid = "a1", entryId = "e1", fieldId = "f1", value = "Yes"))
    }

    @Test
    fun `renaming or moving a section keeps its questions`() = runBlocking {
        repo.insertSection(FormSection(uid = "s1", templateId = "t1", title = "Renamed", sortOrder = 5))
        assertEquals(1, repo.getFieldsForSectionDirect("s1").size)
        assertEquals(1, repo.getOptionsForFieldDirect("f1").size)
    }

    @Test
    fun `re-saving a template or entry keeps its children`() = runBlocking {
        repo.insertTemplate(FormTemplate(uid = "t1", name = "Airway v2"))
        repo.insertEntry(FormEntry(uid = "e1", templateId = "t1", title = "Case 1 edited", answersJson = "{}"))
        assertEquals(1, repo.getSectionsForTemplateDirect("t1").size)
        assertNotNull(repo.getEntryByUid("e1"))
        assertEquals(1, repo.getAnswersForEntryDirect("e1").size)
    }

    @Test
    fun `restoring an old v1 backup deletes nothing and recovers unknown templates`() {
        val v1 = """
            {"schemaVersion":1,
             "templates":[{"uid":"t1","name":"Airway"},{"uid":"t2","name":"Old ICU"}],
             "patients":[{"uid":"p2","name":"Omar","medicalId":"MRN-2"}],
             "logs":[{"uid":"e2","templateId":"t2","title":"ICU case","answersJson":"{\"field-bp\":\"120/80\"}"},
                     {"uid":"bad","title":"missing templateId","answersJson":"{}"}]}
        """.trimIndent()
        val report = restore(v1)

        runBlocking {
            // Existing data untouched
            assertEquals(1, repo.getFieldsForSectionDirect("s1").size)
            assertNotNull(repo.getEntryByUid("e1"))
            assertEquals(1, repo.getAnswersForEntryDirect("e1").size)
            // New data added; the unknown template gets a readable recovered question
            assertNotNull(repo.getEntryByUid("e2"))
            assertEquals(2, repo.allPatients.first().size)
            val recovered = repo.getSectionsForTemplateDirect("t2").single()
            assertEquals("field-bp", repo.getFieldsForSectionDirect(recovered.uid).single().uid)
        }
        assert(report.contains("skipped (invalid rows): 1")) { report }
    }

    @Test
    fun `full v2 backup restores every table on an empty phone`() {
        val backup = runBlocking {
            ExportService.generateJsonBackup(
                context, repo.allTemplates.first(), repo.allPatients.first(), repo.allEntries.first(),
                repo.getAllSections(), repo.getAllFields(), repo.getAllOptions(), repo.getAllAnswers()
            ).readText()
        }
        db.close()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = FormRepository(db)

        restore(backup)

        runBlocking {
            assertEquals(1, repo.allTemplates.first().size)
            assertEquals(1, repo.getAllSections().size)
            assertEquals(1, repo.getAllFields().size)
            assertEquals(1, repo.getAllOptions().size)
            assertEquals(1, repo.allPatients.first().size)
            assertEquals(1, repo.allEntries.first().size)
            assertEquals(1, repo.getAllAnswers().size)
        }
    }

    @Test
    fun `undo after deleting an entry restores it with its answers`() {
        val vm = FormBuilderViewModel(repo, context)
        var undo: (() -> Unit)? = null
        vm.deleteFormEntry("e1") { undo = it }
        waitUntil { undo != null }
        assertEquals(null, runBlocking { repo.getEntryByUid("e1") })

        undo!!.invoke()
        waitUntil { runBlocking { repo.getEntryByUid("e1") } != null }
        assertEquals(1, runBlocking { repo.getAnswersForEntryDirect("e1") }.size)
    }

    @Test
    fun `editing a patient updates the name shown on their entries`() {
        val vm = FormBuilderViewModel(repo, context)
        vm.updatePatient(Patient(uid = "p1", name = "Jane Smith", medicalId = "MRN-1"))
        waitUntil { runBlocking { repo.getEntryByUid("e1") }?.patientName == "Jane Smith" }
    }

    @Test
    fun `csv has one labelled column per question in template order`() {
        repo.let { r -> runBlocking {
            r.insertField(FormField(uid = "f0", sectionId = "s1", type = "text", label = "First Q", sortOrder = 0))
            r.insertEntry(FormEntry(uid = "e1", templateId = "t1", patientId = "p1", title = "Case 1", answersJson = """{"f1":"Yes","f0":"=cmd"}"""))
        } }
        val csv = ExportService.generateCsv(
            context,
            runBlocking { repo.allEntries.first() },
            fieldLabelMap = mapOf("f0" to "First Q", "f1" to "History?"),
            fieldOrder = mapOf("f0" to 0, "f1" to 1)
        ).readLines()
        assert(csv[0].startsWith("RecordID,Title,PatientName,TemplateName,LoggedDate,First Q,History?,")) { csv[0] }
        // Formula-looking answers are neutralised for spreadsheets.
        assert(csv[1].contains(",'=cmd,Yes,")) { csv[1] }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        check(condition()) { "condition not met in time" }
    }

    // Runs the ViewModel restore and waits for its main-thread callback.
    private fun restore(json: String): String {
        val vm = FormBuilderViewModel(repo, context)
        var result: String? = null
        vm.restoreBackup(json, onSuccess = { result = it }, onError = { result = "ERROR $it" })
        val deadline = System.currentTimeMillis() + 10_000
        while (result == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        val report = checkNotNull(result) { "restore did not finish" }
        check(!report.startsWith("ERROR")) { report }
        return report
    }
}
