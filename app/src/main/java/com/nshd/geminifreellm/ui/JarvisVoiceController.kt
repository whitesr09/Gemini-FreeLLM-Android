package com.nshd.geminifreellm.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Owns the short-lived speech recognizer and TTS engine used by the foreground Jarvis screen.
 * Call start/stop from the UI thread and release when the screen leaves composition.
 */
internal class JarvisVoiceController(
    context: Context,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onMessage: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var released = false

    init {
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        onListeningChanged(true)
                        onMessage("Listening… speak now")
                    }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        onListeningChanged(false)
                        onMessage("Processing your request…")
                    }
                    override fun onError(error: Int) {
                        onListeningChanged(false)
                        onMessage(when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that. Tap the microphone and try again."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything. Tap the microphone to retry."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice control."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognition is busy. Please try again."
                            else -> "Voice recognition stopped. Tap the microphone to try again."
                        })
                    }
                    override fun onResults(results: Bundle?) {
                        onListeningChanged(false)
                        val transcript = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (transcript.isBlank()) {
                            onMessage("I didn't catch that. Tap the microphone and try again.")
                        } else {
                            onMessage("Heard: $transcript")
                            onTranscript(transcript)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
        }
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    fun startListening() {
        if (released) return
        val speech = recognizer
        if (speech == null) {
            onMessage("Speech recognition isn't available on this device.")
            return
        }
        runCatching {
            tts?.stop()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            speech.cancel()
            speech.startListening(intent)
            onMessage("Starting microphone…")
        }.onFailure {
            onListeningChanged(false)
            onMessage("Couldn't start voice recognition. Check microphone permission and try again.")
        }
    }

    fun stopListening() {
        runCatching { recognizer?.stopListening() }
        onListeningChanged(false)
    }

    fun speak(text: String) {
        if (!released && ttsReady && text.isNotBlank()) {
            tts?.speak(text.take(500), TextToSpeech.QUEUE_FLUSH, null, "jarvis-response")
        }
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { recognizer?.cancel(); recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        onListeningChanged(false)
    }
}
