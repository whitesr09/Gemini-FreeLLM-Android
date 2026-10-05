package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ChatResult { data class Success(val text:String):ChatResult; data class Failure(val message:String):ChatResult }

class FreeLlmApiClient {
 private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
 suspend fun send(baseUrl:String,apiKey:String,messages:List<ChatMessage>):ChatResult=withContext(Dispatchers.IO){
  val cleanBase=baseUrl.trim().trimEnd('/'); if(cleanBase.isBlank())return@withContext ChatResult.Failure("Base URL is empty."); if(apiKey.isBlank())return@withContext ChatResult.Failure("Unified API key is missing.")
  try{
   val jsonMessages=JSONArray(); messages.filter{it.role!=ChatMessage.Role.ERROR}.forEach{m->jsonMessages.put(JSONObject().put("role",if(m.role==ChatMessage.Role.USER)"user" else "assistant").put("content",m.text))}
   val payload=JSONObject().put("model","auto").put("messages",jsonMessages).put("stream",false)
   val request=Request.Builder().url("$cleanBase/chat/completions").header("Authorization","Bearer $apiKey").header("Accept","application/json").header("Content-Type","application/json").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
   client.newCall(request).execute().use{response->
    val body=response.body?.string().orEmpty(); if(!response.isSuccessful)return@withContext ChatResult.Failure(when(response.code){401,403->"The API key was rejected. Check your Unified API key.";404->"API endpoint not found. Check the Base URL.";429->"The provider is rate-limited. Try again in a moment.";in 500..599->"Server error (${response.code}). Try again.";else->"Request failed (${response.code})."})
    runCatching{val choices=JSONObject(body).optJSONArray("choices")?:error("No choices");val text=choices.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim().orEmpty();if(text.isBlank())error("Empty response");text}.fold({ChatResult.Success(it)},{ChatResult.Failure("The server returned an unreadable response.")})
   }
  }catch(_:IOException){ChatResult.Failure("Couldn't reach FreeLLMAPI. Make sure the server is running.")}catch(e:Exception){ChatResult.Failure(e.message?.takeIf{it.isNotBlank()}?:"Something went wrong.")}
 }
}
