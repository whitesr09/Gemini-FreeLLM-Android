package com.nshd.geminifreellm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nshd.geminifreellm.ui.FreeLlmApp

class MainActivity : ComponentActivity() {
    private val chat: ChatViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FreeLlmApp(chat) }
    }
    override fun onStop() { chat.flushDraft(); super.onStop() }
}
