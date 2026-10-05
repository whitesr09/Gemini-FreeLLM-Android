package com.nshd.geminifreellm.data

import org.json.JSONObject

data class StructuredOutputRequest(
    val schema: JSONObject,
    val strict: Boolean = true
)

object StructuredOutput {
    fun responseFormat(request: StructuredOutputRequest): JSONObject =
        JSONObject()
            .put("type", "json_schema")
            .put(
                "json_schema",
                JSONObject()
                    .put("name", "freellm_response")
                    .put("strict", request.strict)
                    .put("schema", request.schema)
            )
}
