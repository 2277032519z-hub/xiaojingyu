package com.xiaojingyu.assistant

import java.util.Locale

/** 最小 JSON 工具：不引第三方库，够用即可。 */
object Json {
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }


    /** 把一段扁平 JSON 对象解析成 key→value（只处理字符串/数字/布尔，够 @act 用）。 */
    fun obj(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            if (s[i] != '"') {
                i++
                continue
            }
            val key = readString(s, i) ?: break
            i = key.second
            while (i < s.length && s[i] != ':' && s[i] != '}') i++
            if (i >= s.length || s[i] == '}') break
            i++
            while (i < s.length && s[i] == ' ') i++
            if (i < s.length && s[i] == '"') {
                val v = readString(s, i) ?: break
                out[key.first] = v.first
                i = v.second
            } else {
                val start = i
                while (i < s.length && s[i] != ',' && s[i] != '}') i++
                out[key.first] = s.substring(start, i).trim().trim('"')
            }
        }
        return out
    }

    private fun readString(s: String, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(s[i + 1])
                i += 2
                continue
            }
            if (c == '"') return sb.toString() to (i + 1)
            sb.append(c)
            i++
        }
        return null
    }

    /** 从形如 {"text":"你好"} 里取字段；取不到返回 ""。 */
    fun field(json: String, name: String): String {
        val key = "\"$name\""
        var i = json.indexOf(key)
        if (i < 0) return ""
        i = json.indexOf(':', i + key.length)
        if (i < 0) return ""
        i = json.indexOf('"', i + 1)
        if (i < 0) return ""
        val sb = StringBuilder()
        var j = i + 1
        while (j < json.length) {
            val c = json[j]
            if (c == '\\' && j + 1 < json.length) {
                when (val e = json[j + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (j + 5 < json.length) {
                            val hex = json.substring(j + 2, j + 6)
                            hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            j += 4
                        }
                    }
                    else -> sb.append(e)
                }
                j += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                j++
            }
        }
        return sb.toString()
    }
}

fun String.squeeze(): String = replace(Regex("\\s+"), "")

fun String.looksLikeWake(words: List<String>): Boolean {
    val t = squeeze()
    if (t.isEmpty()) return false
    if (words.any { t.contains(it) }) return true
    // 识别模型偶尔把「小鲸鱼」吞成「水鲸鱼 / 小京鱼」之类，放宽一点：
    return t.length <= 12 && t.contains("鲸鱼")
}
