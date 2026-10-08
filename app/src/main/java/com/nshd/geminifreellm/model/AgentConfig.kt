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
    fun prompt(): String = if (!enabled) "" else buildString {
        fun section(name: String, text: String) { if (text.isNotBlank()) append("## $name\n$text\n\n") }
        section("Persona", persona)
        section("User instructions", instructions)
        section("User-managed memory", memory)
        skills.filter { it.enabled }.forEach { section("Skill: ${it.name}", it.content) }
    }
    fun validationError(): String? = when {
        persona.length > 4000 || instructions.length > 8000 || memory.length > 8000 -> "Persona, instructions or memory exceed their size limit."
        skills.size > 20 -> "Keep up to 20 skills."
        skills.any { it.name.isBlank() || it.name.length > 80 || it.content.isBlank() || it.content.length > 16_000 } -> "Each skill needs a name and text (up to 16,000 characters)."
        copy(enabled = true).prompt().length > 48_000 -> "Enabled agent context exceeds 48,000 characters. Disable or shorten some skills."
        else -> null
    }
}
