package com.nshd.geminifreellm.model

/** Explicit, editable memory and portable prompt skills, encrypted alongside settings. */
data class AgentSkill(val id: String = newId(), val name: String = "", val content: String = "", val enabled: Boolean = true)
data class AgentConfig(
    val enabled: Boolean = true,
    val persona: String = "",
    val instructions: String = "",
    val memory: String = "",
    val skills: List<AgentSkill> = emptyList()
) {
    private fun section(name: String, text: String, out: StringBuilder) {
        if (text.isNotBlank()) out.append("## ").append(name).append('\n').append(text).append("\n\n")
    }

    /** Always-on context: persona, instructions and user-managed memory. Sent with every request. */
    fun basePrompt(): String = if (!enabled) "" else buildString {
        section("Persona", persona, this)
        section("User instructions", instructions, this)
        section("User-managed memory", memory, this)
    }

    /** The prompt section for one explicitly invoked skill (via "/name" in chat). */
    fun skillSection(skill: AgentSkill): String =
        if (!enabled || !skill.enabled || skill.content.isBlank()) "" else "## Skill: ${skill.name}\n${skill.content}\n\n"

    /** Full prompt for a request, with an optional invoked skill appended. */
    fun promptFor(invoked: AgentSkill? = null): String = basePrompt() + if (invoked != null) skillSection(invoked) else ""

    /** Skills available for "/" invocation in chat, in saved order. */
    fun invokableSkills(): List<AgentSkill> = if (!enabled) emptyList() else skills.filter { it.enabled && it.name.isNotBlank() }

    fun validationError(): String? = when {
        skills.size > 20 -> "Keep up to 20 skills."
        skills.any { it.name.isBlank() || it.content.isBlank() } -> "Each skill needs a name and instructions."
        else -> null
    }

    companion object {
        /**
         * Splits a "/SkillName rest of message" draft into the invoked skill and the
         * remaining text. Returns (null, original) when there is no valid invocation.
         */
        fun parseInvocation(draft: String, agent: AgentConfig): Pair<AgentSkill?, String> {
            val trimmed = draft.trimStart()
            if (!trimmed.startsWith("/")) return null to draft
            val after = trimmed.drop(1)
            if (after.isEmpty()) return null to draft
            // Longest name first so "My Skill" matches before "My".
            val skill = agent.invokableSkills()
                .sortedByDescending { it.name.length }
                .firstOrNull { candidate ->
                    after.startsWith(candidate.name, ignoreCase = true) &&
                        (after.length == candidate.name.length || after[candidate.name.length].isWhitespace())
                } ?: return null to draft
            val rest = after.drop(skill.name.length).trimStart()
            return skill to rest
        }

        /**
         * Finds an active "/" trigger in the draft for autocomplete: a "/" that starts
         * a word (at text start or after whitespace) with no whitespace after it.
         * Returns the query text after "/", or null when there is no trigger.
         */
        fun slashTrigger(draft: String): String? {
            val slash = draft.lastIndexOf('/')
            if (slash < 0) return null
            if (slash > 0 && !draft[slash - 1].isWhitespace()) return null
            val query = draft.substring(slash + 1)
            if (query.any { it.isWhitespace() }) return null
            return query
        }

        /** Replaces the active "/" trigger with "/SkillName " (trailing space). */
        fun applySkill(draft: String, skillName: String): String {
            val slash = draft.lastIndexOf('/')
            if (slash < 0) return draft
            return draft.substring(0, slash) + "/" + skillName + " "
        }
    }
}
