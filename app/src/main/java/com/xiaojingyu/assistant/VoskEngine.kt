package com.xiaojingyu.assistant

import android.content.Context
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException

/**
 * 离线语音引擎（Vosk）：
 *  - 一个「语法受限」的识别器专门听唤醒词（准确率高、省电）
 *  - 一个完整识别器负责把用户说完的一整句转成文字
 *  - 全部离线，不联网、不上传录音
 */
class VoskEngine(private val ctx: Context) {

    private var model: Model? = null
    private var kws: Recognizer? = null
    private var asr: Recognizer? = null
    private val asrText = StringBuilder()

    @Volatile var ready = false
        private set
    @Volatile var status = "语音模型未加载"
        private set

    var wakeWords: List<String> = listOf("小鲸鱼")

    fun init(wake: List<String>, onReady: () -> Unit = {}, onError: (String) -> Unit = {}) {
        wakeWords = wake
        if (ready) {
            onReady()
            return
        }
        status = "正在加载语音模型…"
        StorageService.unpack(
            ctx,
            ASSET_DIR,
            MODEL_DIR,
            { m: Model ->
                try {
                    model = m
                    kws = buildKws(m, wake)
                    asr = Recognizer(m, SAMPLE_RATE)
                    ready = true
                    status = "模型就绪（离线）"
                    onReady()
                } catch (e: Throwable) {
                    status = "识别器初始化失败：" + e.message
                    onError(status)
                }
            },
            { e: IOException ->
                status = "模型加载失败：" + e.message
                onError(status)
            }
        )
    }

    private fun buildKws(m: Model, wake: List<String>): Recognizer {
        val words = LinkedHashSet<String>()
        wake.forEach { words.add(it) }
        words.add("小鲸鱼")
        words.add("你好")
        val grammar = "[" + words.take(8).joinToString(",") { Json.quote(it) } + ",\"[unk]\"]"
        return try {
            Recognizer(m, SAMPLE_RATE, grammar)
        } catch (e: Exception) {
            Recognizer(m, SAMPLE_RATE)
        }
    }

    /** 喂一批 16k/16bit/单声道 PCM，返回命中的唤醒词（没命中返回 null）。 */
    fun feedKws(pcm: ByteArray, len: Int): String? {
        val r = kws ?: return null
        if (len <= 0) return null
        return try {
            if (r.acceptWaveForm(pcm, len)) {
                val t = Json.field(r.result, "text")
                if (t.looksLikeWake(wakeWords)) t else null
            } else {
                val p = Json.field(r.partialResult, "partial")
                if (p.looksLikeWake(wakeWords)) p else null
            }
        } catch (e: Throwable) {
            null
        }
    }

    fun resetKws() {
        try {
            kws?.reset()
        } catch (_: Throwable) {
        }
    }

    fun feedAsr(pcm: ByteArray, len: Int) {
        val r = asr ?: return
        if (len <= 0) return
        try {
            if (r.acceptWaveForm(pcm, len)) {
                val t = Json.field(r.result, "text")
                if (t.isNotBlank()) asrText.append(t)
            }
        } catch (_: Throwable) {
        }
    }

    fun finishAsr(): String {
        val r = asr
        if (r != null) {
            try {
                asrText.append(Json.field(r.finalResult, "text"))
            } catch (_: Throwable) {
            }
        }
        val out = asrText.toString().trim()
        asrText.setLength(0)
        resetKws()
        return out
    }

    fun release() {
        ready = false
        try {
            kws?.close()
        } catch (_: Throwable) {
        }
        try {
            asr?.close()
        } catch (_: Throwable) {
        }
        try {
            model?.close()
        } catch (_: Throwable) {
        }
        kws = null
        asr = null
        model = null
    }

    companion object {
        const val SAMPLE_RATE = 16000.0f
        const val ASSET_DIR = "model-cn"
        const val MODEL_DIR = "vosk-cn"
    }
}
