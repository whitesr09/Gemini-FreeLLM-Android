package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

internal fun agentToJson(agent: AgentConfig) = JSONObject().put("enabled", agent.enabled)
    .put("persona", agent.persona).put("instructions", agent.instructions).put("memory", agent.memory)
    .put("skills", JSONArray(agent.skills.map { JSONObject().put("id", it.id).put("name", it.name).put("content", it.content).put("enabled", it.enabled) }))
internal fun agentFromJson(json: JSONObject) = AgentConfig(json.optBoolean("enabled", true), json.optString("persona"),
    json.optString("instructions"), json.optString("memory"), json.optJSONArray("skills")?.let { a ->
        (0 until a.length()).map { i -> a.getJSONObject(i).let { AgentSkill(it.optString("id", newId()), it.getString("name"), it.getString("content"), it.optBoolean("enabled", true)) } }
    }.orEmpty())

/** Text-only import; bounded before allocation, rejects binary and oversized documents. */
fun readPortableText(input: InputStream, limit: Int = 16_000): String {
    val bytes = input.readBytesBounded(limit * 4)
    val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    require(text.isNotBlank() && text.length <= limit && text.none { it == '\u0000' }) { "Choose a UTF-8 text file up to $limit characters." }
    require(!text.trimStart().startsWith("<!DOCTYPE html", true) && !text.trimStart().startsWith("<html", true)) { "Use a raw text link instead of a web page." }
    return text
}
private fun InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "This file is too large." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
