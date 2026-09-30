package com.example

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Installed apps hold users' clinical data in database version 3. If this test fails, the Room
// schema changed: that needs a real Migration and a new frozen schema, never a silent change.
class SchemaFreezeTest {

    @Test
    fun `database schema is still version 3 with the shipped identity hash`() {
        val schema = File("schemas/com.example.data.local.AppDatabase/3.json")
        assertTrue("Exported schema missing: ${schema.absolutePath}", schema.exists())
        val text = schema.readText()
        assertEquals("9fd2b3085e40b8491799279cd33b12c8", Regex("\"identityHash\": \"([0-9a-f]+)\"").find(text)?.groupValues?.get(1))
        assertEquals("3", Regex("\"version\": (\\d+)").find(text)?.groupValues?.get(1))
    }
}
