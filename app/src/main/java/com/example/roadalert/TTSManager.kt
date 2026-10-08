package com.example.roadalert

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isReady = false
    private var lastAnnouncedId: String? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.FRANCE
            
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            tts?.setAudioAttributes(audioAttributes)
            isReady = true
        }
    }

    fun speak(eventId: String, message: String) {
        if (!isReady) return
        
        if (eventId != lastAnnouncedId) {
            lastAnnouncedId = eventId
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, eventId)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}