package com.xiaojingyu.assistant

import android.content.Context

/** 用户配置：全部落在 SharedPreferences 里，改完即生效（服务下次唤醒时读取）。 */
data class Cfg(
    val apiBase: String = DEFAULT_BASE,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val wakeWords: String = DEFAULT_WAKE_WORDS,
    val persona: String = DEFAULT_PERSONA,
    val speak: Boolean = true,
    val autoStart: Boolean = true,
) {
    val wakeList: List<String>
        get() = wakeWords.split(',', '，', '\n')
            .map { it.trim().replace(" ", "") }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("小鲸鱼") }

    companion object {
        const val DEFAULT_BASE = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_WAKE_WORDS = "小鲸鱼,你好小鲸鱼,你好助手"
        const val DEFAULT_PERSONA =
            "你是一个随叫随到的语音助手，运行在用户的手机上。" +
                "说话要短、直接、口语化：先给结论，通常不超过三句。不要说 Markdown、列表和表情符号，因为你的回答会被朗读出来。" +
                "信息不足时用一句话反问，不要猜。"

        private const val PREF = "cfg"

        fun load(ctx: Context): Cfg {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return Cfg(
                apiBase = sp.getString("apiBase", DEFAULT_BASE) ?: DEFAULT_BASE,
                apiKey = sp.getString("apiKey", "") ?: "",
                model = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
                wakeWords = sp.getString("wakeWords", DEFAULT_WAKE_WORDS) ?: DEFAULT_WAKE_WORDS,
                persona = sp.getString("persona", DEFAULT_PERSONA) ?: DEFAULT_PERSONA,
                speak = sp.getBoolean("speak", true),
                autoStart = sp.getBoolean("autoStart", true),
            )
        }

        fun save(ctx: Context, c: Cfg) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("apiBase", c.apiBase)
                .putString("apiKey", c.apiKey)
                .putString("model", c.model)
                .putString("wakeWords", c.wakeWords)
                .putString("persona", c.persona)
                .putBoolean("speak", c.speak)
                .putBoolean("autoStart", c.autoStart)
                .apply()
        }
    }
}
