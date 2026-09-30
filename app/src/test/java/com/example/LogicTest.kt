package com.example

import com.example.data.model.ConditionalLogic
import com.example.data.model.FormField
import com.example.data.model.ProcedureAnswer
import com.example.ui.screens.isWithinDayRange
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric provides the real org.json used by ProcedureAnswer.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LogicTest {

    private fun field(uid: String, rule: String? = null) =
        FormField(uid = uid, sectionId = "s", type = "radio_group", label = uid, sortOrder = 0, conditionalLogicJson = rule)

    @Test
    fun `chained rule hides child when parent is hidden`() {
        val fields = listOf(field("a"), field("b", "a==Yes"), field("c", "b==Yes"))
        // b answered earlier, then a changed to No: b hides, so c must hide too.
        val visible = ConditionalLogic.visibleFieldUids(fields, mapOf("a" to "No", "b" to "Yes"))
        assertEquals(setOf("a"), visible)
    }

    @Test
    fun `rule pointing at a deleted question no longer hides the field`() {
        val visible = ConditionalLogic.visibleFieldUids(listOf(field("x", "gone==Yes")), emptyMap())
        assertEquals(setOf("x"), visible)
    }

    @Test
    fun `rule value may contain double equals`() {
        assertEquals("a" to "x==y", ConditionalLogic.parse("a==x==y"))
    }

    @Test
    fun `group procedure reads per-choice performed flags`() {
        val value = """{"procedure":"Lines","completed":true,"choices":"CVC,Art line","choices_perf":{"CVC":true}}"""
        val items = ProcedureAnswer.completedItems(value)
        assertEquals(listOf(ProcedureAnswer.Item("Lines: CVC", true), ProcedureAnswer.Item("Lines: Art line", false)), items)
        assertEquals(true, ProcedureAnswer.performedByMe(value))
    }

    @Test
    fun `unanswered or incomplete single procedure is not performed by me`() {
        assertFalse(ProcedureAnswer.completedItems("""{"procedure":"ETT","completed":true}""").single().performedByMe)
        assertNull(ProcedureAnswer.performedByMe("""{"procedure":"ETT","completed":false,"performedByMe":true}"""))
    }

    @Test
    fun `date filter uses the local calendar day`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Karachi")) // UTC+5
            val entry = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 30, 2, 0, 0) }.timeInMillis
            val pickedSep30 = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear(); set(2026, Calendar.SEPTEMBER, 30)
            }.timeInMillis
            // 02:00 local on Sep 30 is still Sep 29 in UTC; it must count as Sep 30.
            assertTrue(isWithinDayRange(entry, pickedSep30, pickedSep30))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
