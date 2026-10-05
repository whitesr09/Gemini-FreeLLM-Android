package com.nshd.geminifreellm

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.nshd.geminifreellm.data.ChatResult
import com.nshd.geminifreellm.data.FreeLlmApiClient
import com.nshd.geminifreellm.model.ChatMessage
import com.nshd.geminifreellm.ui.ChatScreen
import com.nshd.geminifreellm.ui.GeminiTheme
import com.nshd.geminifreellm.ui.SettingsDialog
import com.nshd.geminifreellm.ui.ThemeMode
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { GeminiFreeLlmApp(this) } }
}

@Composable private fun GeminiFreeLlmApp(context: Context) {
 val prefs=remember{context.getSharedPreferences("settings",Context.MODE_PRIVATE)}
 val client=remember{FreeLlmApiClient()}; val ids=remember{AtomicLong(System.currentTimeMillis())}; val scope=rememberCoroutineScope()
 var baseUrl by remember{mutableStateOf(prefs.getString("baseUrl","http://127.0.0.1:3001/v1") ?: "")}
 var apiKey by remember{mutableStateOf(prefs.getString("apiKey","") ?: "")}
 var themeMode by remember{mutableStateOf(runCatching{ThemeMode.valueOf(prefs.getString("theme",ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)}.getOrDefault(ThemeMode.SYSTEM))}
 var showSettings by remember{mutableStateOf(apiKey.isBlank())}; var input by remember{mutableStateOf("")}; var busy by remember{mutableStateOf(false)}
 val messages=remember{mutableStateListOf<ChatMessage>()}
 GeminiTheme(themeMode){
  ChatScreen(messages,busy,input,{if(!busy)input=it},{
   val prompt=input.trim(); if(prompt.isBlank()||busy)return@ChatScreen; input=""; messages+=ChatMessage(ids.incrementAndGet(),prompt,ChatMessage.Role.USER); busy=true
   scope.launch{when(val result=client.send(baseUrl,apiKey,messages.toList())){is ChatResult.Success->messages+=ChatMessage(ids.incrementAndGet(),result.text,ChatMessage.Role.ASSISTANT);is ChatResult.Failure->messages+=ChatMessage(ids.incrementAndGet(),result.message,ChatMessage.Role.ERROR)};busy=false}
  },{showSettings=true},{messages.clear()})
  if(showSettings)SettingsDialog(baseUrl,apiKey,themeMode,{url,key,theme->baseUrl=url.trim().removeSuffix("/");apiKey=key.trim();themeMode=theme;prefs.edit().putString("baseUrl",baseUrl).putString("apiKey",apiKey).putString("theme",theme.name).apply();showSettings=false},{if(apiKey.isNotBlank())showSettings=false})
 }
}
