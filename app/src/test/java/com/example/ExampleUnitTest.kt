package com.example

import org.junit.Assert.*
import org.junit.Test
import com.example.data.service.GeminiRequest
import com.example.data.service.GeminiContent
import com.example.data.service.GeminiContentPart
import com.example.data.service.GeminiResponse
import com.example.data.service.GeminiCandidate
import com.squareup.moshi.Moshi

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testMoshiSerialization() {
        val moshi = Moshi.Builder()
            .build()

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiContentPart(text = "Hello")
                    )
                )
            )
        )

        val adapter = moshi.adapter(GeminiRequest::class.java)
        val json = adapter.toJson(request)
        assertNotNull(json)
        assertTrue(json.contains("Hello"))
    }

    @Test
    fun testMoshiDeserialization() {
        val moshi = Moshi.Builder()
            .build()

        val json = """
            {
                "candidates": [
                    {
                        "content": {
                            "parts": [
                                {
                                    "text": "Success"
                                }
                            ]
                        }
                    }
                ]
            }
        """.trimIndent()

        val adapter = moshi.adapter(GeminiResponse::class.java)
        val response = adapter.fromJson(json)
        assertNotNull(response)
        assertEquals("Success", response?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text)
    }
}
