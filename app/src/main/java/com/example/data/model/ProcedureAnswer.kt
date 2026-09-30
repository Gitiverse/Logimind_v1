package com.example.data.model

import org.json.JSONObject

// Reads the stored procedure answer formats:
//   single checkbox: {"procedure","completed","performedByMe"}
//   checkbox group:  {"procedure","completed","choices":"a,b","choices_perf":{"a":true,"b":false}}
object ProcedureAnswer {

    data class Item(val name: String, val performedByMe: Boolean)

    fun isProcedure(value: String): Boolean = value.startsWith("{") && value.contains("procedure")

    // Completed procedures in an answer; group choices are listed as "<procedure>: <choice>".
    fun completedItems(value: String): List<Item> {
        if (!isProcedure(value)) return emptyList()
        return try {
            val obj = JSONObject(value)
            val procedure = obj.optString("procedure")
            val choicesPerf = obj.optJSONObject("choices_perf")
            val choices = obj.optString("choices", "").split(",").filter { it.isNotBlank() }
            when {
                choicesPerf != null -> {
                    val names = choices.ifEmpty { choicesPerf.keys().asSequence().toList() }
                    names.map { Item("$procedure: $it", choicesPerf.optBoolean(it, false)) }
                }
                obj.optBoolean("completed", false) -> {
                    val name = if (choices.isNotEmpty()) "$procedure: ${choices.joinToString(",")}" else procedure
                    listOf(Item(name, obj.optBoolean("performedByMe", false)))
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Value for entry_answers.performedByMe: true if any completed item was performed by me.
    fun performedByMe(value: String): Boolean? {
        val items = completedItems(value)
        if (items.isEmpty()) return null
        return items.any { it.performedByMe }
    }
}
