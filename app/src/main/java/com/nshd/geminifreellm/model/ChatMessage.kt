package com.nshd.geminifreellm.model

data class ChatMessage(val id: Long, val text: String, val role: Role, val timestamp: Long = System.currentTimeMillis()) { enum class Role { USER, ASSISTANT, ERROR } }
