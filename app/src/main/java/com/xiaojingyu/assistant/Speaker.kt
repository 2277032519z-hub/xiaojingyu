package com.xiaojingyu.assistant

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** 用系统 TTS 把回答念出来（中文音色由系统语音包提供）。 */
class Speaker(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var total = 0
    private var finished = 0
    private var onEnd: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                val engine = tts
                if (engine != null) {
                    try {
                        engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                        engine.setSpeechRate(1.02f)
                    } catch (_: Throwable) {
                    }
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            countDone()
                        }
                        override fun onError(utteranceId: String?) {
                            countDone()
                        }
                    })
                }
            }
        }
    }

    private fun countDone() {
        val end: (() -> Unit)?
        synchronized(this) {
            finished++
            end = if (finished >= total) onEnd else null
            if (end != null) onEnd = null
        }
        end?.invoke()
    }

    /** 念一段文字；念完（或失败）回调 onEnd。 */
    fun speak(text: String, onEnd: () -> Unit) {
        val engine = tts
        if (engine == null || !ready || text.isBlank()) {
            onEnd()
            return
        }
        val chunks = split(text.trim())
        if (chunks.isEmpty()) {
            onEnd()
            return
        }
        synchronized(this) {
            this.onEnd = onEnd
            total = chunks.size
            finished = 0
        }
        engine.stop()
        chunks.forEachIndexed { i, s ->
            val params = Bundle()
            engine.speak(s, TextToSpeech.QUEUE_ADD, params, "u$i")
        }
    }

    private fun split(text: String): List<String> {
        if (text.length <= 120) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch in "。！？!?；;\n" && sb.length >= 40) {
                out.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    fun stop() {
        synchronized(this) {
            onEnd = null
            total = 0
            finished = 0
        }
        try {
            tts?.stop()
        } catch (_: Throwable) {
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        ready = false
    }
}
