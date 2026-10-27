package com.xiaojingyu.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val askPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateStatus()
            askOverlayIfNeeded()
        }
    private val askOverlay =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateStatus()
            askBatteryIfNeeded()
        }

    private lateinit var statusView: TextView
    private lateinit var baseEdit: EditText
    private lateinit var keyEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var wakeEdit: EditText
    private lateinit var personaEdit: EditText
    private lateinit var speakSwitch: SwitchCompat
    private lateinit var autoSwitch: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        fillFromConfig()
        if (intent?.getBooleanExtra("autostart", false) == true) {
            startAssistant()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, top: Int = 16): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#9AA3B2"))
        textSize = 13f
        setPadding(0, dp(top), 0, dp(6))
    }

    private fun field(hint: String, multiline: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#5A6270"))
        textSize = 15f
        setBackgroundColor(Color.parseColor("#1A1E26"))
        setPadding(dp(14), dp(10), dp(14), dp(10))
        inputType = if (multiline) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        if (multiline) {
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun button(text: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (primary) Color.WHITE else Color.parseColor("#D6DCE8"))
            setBackgroundColor(if (primary) Color.parseColor("#4D7CFE") else Color.parseColor("#232833"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: android.view.View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "小鲸鱼"
            setTextColor(Color.WHITE)
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "喊一声就能用的语音助手 · 离线听唤醒词，回答走你自己的模型 API"
            setTextColor(Color.parseColor("#9AA3B2"))
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        })

        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#D6DCE8"))
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.parseColor("#141821"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }
        root.addView(statusView)

        root.addView(row(
            button("① 授予权限") { askPermissions() },
            button("② 开始聆听", primary = true) { startAssistant() }
        ))
        root.addView(row(
            button("先试一句") { WakeService.trigger(this@MainActivity) },
            button("停止") { WakeService.stop(this@MainActivity); statusView.postDelayed({ updateStatus() }, 600) }
        ))
        root.addView(row(
            button("开启无障碍（让它能动手）", primary = true) { openA11ySettings() }
        ))

        root.addView(label("模型接口（OpenAI 兼容）"))
        baseEdit = field("https://api.deepseek.com/v1")
        root.addView(baseEdit)
        root.addView(label("API Key", 10))
        keyEdit = field("粘贴你的 key；留空表示不修改")
        keyEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(keyEdit)
        root.addView(label("模型名", 10))
        modelEdit = field("deepseek-chat")
        root.addView(modelEdit)

        root.addView(label("唤醒词（逗号分隔，会做语法受限识别）"))
        wakeEdit = field("小鲸鱼,你好小鲸鱼,你好助手")
        root.addView(wakeEdit)

        root.addView(label("人设 / 说话风格"))
        personaEdit = field("", multiline = true)
        root.addView(personaEdit)

        speakSwitch = SwitchCompat(this).apply {
            text = "朗读回答"
            setTextColor(Color.parseColor("#D6DCE8"))
            isChecked = true
            setPadding(0, dp(16), 0, 0)
        }
        autoSwitch = SwitchCompat(this).apply {
            text = "开机后提醒我启动"
            setTextColor(Color.parseColor("#D6DCE8"))
            isChecked = true
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(speakSwitch)
        root.addView(autoSwitch)

        root.addView(row(button("保存设置", primary = true) { save(showToast = true) }))

        root.addView(TextView(this).apply {
            text = "提示：国产系统（ColorOS / HyperOS 等）需要在「电池 → 应用 → 允许后台运行」" +
                "和「自启动」里放行，否则常驻聆听会被系统杀掉。悬浮窗权限用于显示对话气泡。"
            setTextColor(Color.parseColor("#6B7385"))
            textSize = 12f
            setPadding(0, dp(22), 0, 0)
        })

        val scroll = ScrollView(this).apply {
            addView(root)
            setBackgroundColor(Color.parseColor("#0F1115"))
        }
        setContentView(scroll)
    }

    private fun fillFromConfig() {
        val c = Cfg.load(this)
        baseEdit.setText(c.apiBase)
        modelEdit.setText(c.model)
        wakeEdit.setText(c.wakeWords)
        personaEdit.setText(c.persona)
        speakSwitch.isChecked = c.speak
        autoSwitch.isChecked = c.autoStart
        keyEdit.hint = if (c.apiKey.isBlank()) "粘贴你的 key" else "已保存 key（尾号 " + c.apiKey.takeLast(4) + "），留空不修改"
    }

    private fun save(showToast: Boolean) {
        val old = Cfg.load(this)
        val typedKey = keyEdit.text.toString().trim()
        val c = old.copy(
            apiBase = baseEdit.text.toString().trim().ifBlank { Cfg.DEFAULT_BASE },
            apiKey = if (typedKey.isEmpty()) old.apiKey else typedKey,
            model = modelEdit.text.toString().trim().ifBlank { Cfg.DEFAULT_MODEL },
            wakeWords = wakeEdit.text.toString().trim().ifBlank { Cfg.DEFAULT_WAKE_WORDS },
            persona = personaEdit.text.toString().ifBlank { Cfg.DEFAULT_PERSONA },
            speak = speakSwitch.isChecked,
            autoStart = autoSwitch.isChecked
        )
        Cfg.save(this, c)
        if (showToast) Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    private fun startAssistant() {
        save(showToast = false)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "先给麦克风权限", Toast.LENGTH_SHORT).show()
            askPermissions()
            return
        }
        try {
            WakeService.start(this)
            Toast.makeText(this, "小鲸鱼开始听了", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "启动失败：" + e.message, Toast.LENGTH_LONG).show()
        }
        statusView.postDelayed({ updateStatus() }, 800)
    }

    private fun askPermissions() {
        val list = ArrayList<String>()
        list.add(Manifest.permission.RECORD_AUDIO)
        list.add(Manifest.permission.CALL_PHONE)
        list.add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        askPerms.launch(list.toTypedArray())
    }

    /** 权限是一串系统弹窗，必须一个一个来，连着开容易互相顶掉。 */
    private fun askOverlayIfNeeded() {
        if (Settings.canDrawOverlays(this)) {
            askBatteryIfNeeded()
            return
        }
        try {
            askOverlay.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (_: Throwable) {
            askBatteryIfNeeded()
        }
    }

    private fun askBatteryIfNeeded() {
        if (isIgnoringBattery()) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Throwable) {
        }
    }

    private fun isIgnoringBattery(): Boolean = try {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    } catch (_: Throwable) {
        false
    }

    /** 无障碍是否已在系统设置里被打开（以系统记录为准，比服务实例更可靠）。 */
    private fun isA11yOn(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.contains(packageName + "/") && enabled.contains("PhoneA11yService")
        } catch (_: Throwable) {
            false
        }
    }

    private fun openA11ySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "找到「小鲸鱼 屏幕助手」并打开，然后回来", Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "打不开无障碍设置：" + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatus() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val battery = isIgnoringBattery()
        val a11y = isA11yOn()
        val running = WakeService.running

        val mark = { ok: Boolean -> if (ok) "✓" else "✗" }
        statusView.text = buildString {
            append(if (running) "● 正在聆听中\n" else "○ 未启动\n")
            append("麦克风 ").append(mark(mic)).append("    通知 ").append(mark(notif))
            append("    悬浮窗 ").append(mark(overlay)).append("\n")
            append("电池 ").append(mark(battery)).append("    无障碍 ").append(mark(a11y))
            if (!(mic && notif && overlay && battery && a11y)) {
                append("\n\n有项目没打勾：先点「① 授予权限」，把系统弹的窗口都允许。")
            } else if (!running) {
                append("\n\n权限齐了，点「② 开始聆听」。")
            } else {
                append("\n\n喊一声唤醒词试试（默认「小鲸鱼」）。")
            }
        }
    }
}
