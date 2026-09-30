package com.example.data.model

// Stored rule format (kept for compatibility with existing data): "<parentFieldUid>==<expectedValue>".
object ConditionalLogic {

    fun parse(rule: String?): Pair<String, String>? {
        if (rule.isNullOrBlank() || !rule.contains("==")) return null
        return rule.substringBefore("==") to rule.substringAfter("==")
    }

    // A field is visible when it has no rule, or its parent is visible and answered with the
    // expected value. A rule pointing at a deleted field no longer hides the question.
    fun visibleFieldUids(fields: Collection<FormField>, answers: Map<String, String>): Set<String> {
        val byUid = fields.associateBy { it.uid }
        val memo = mutableMapOf<String, Boolean>()

        fun isVisible(uid: String, depth: Int): Boolean {
            memo[uid]?.let { return it }
            val field = byUid[uid] ?: return true
            val rule = parse(field.conditionalLogicJson)
            val visible = when {
                rule == null -> true
                depth > byUid.size -> true
                byUid[rule.first] == null -> true
                else -> isVisible(rule.first, depth + 1) && answers[rule.first] == rule.second
            }
            memo[uid] = visible
            return visible
        }

        return fields.filter { isVisible(it.uid, 0) }.map { it.uid }.toSet()
    }
}
