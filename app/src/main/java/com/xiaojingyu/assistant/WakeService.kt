package com.xiaojingyu.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * 常驻服务：一直在听唤醒词；一旦唤醒就录一整句 → 交给模型 → 念出来。
 *
 * 想省电/换唤醒词，改 Config 里的 wakeWords（走语法受限的离线识别，命中率比整句识别高）。
 */
class WakeService : Service() {

    private enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    companion object {
        const val ACTION_START = "com.xiaojingyu.assistant.START"
        const val ACTION_STOP = "com.xiaojingyu.assistant.STOP"
        const val ACTION_TRIGGER = "com.xiaojingyu.assistant.TRIGGER"

        private const val NOTIF_ID = 41
        private const val CHANNEL_ID = "wake"
        private const val RATE = 16000
        private const val VOICE_LEVEL = 700      // 判定「有人在说话」的能量阈值
        private const val END_SILENCE = 900L     // 静音多久算说完
        private const val MAX_UTTER = 12000L     // 一句话最长录多久

        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, WakeService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, WakeService::class.java).setAction(ACTION_STOP)
            try {
                ctx.startService(i)
            } catch (_: Throwable) {
            }
        }

        fun trigger(ctx: Context) {
            val i = Intent(ctx, WakeService::class.java).setAction(ACTION_TRIGGER)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (_: Throwable) {
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val engine by lazy { VoskEngine(this) }
    private val overlay by lazy { OverlayUi(this) }
    private val speaker by lazy { Speaker(this) }
    private val history = ArrayList<Llm.Msg>()

    private var audioThread: Thread? = null
    private var record: AudioRecord? = null
    private var call: okhttp3.Call? = null
    @Volatile private var state = State.IDLE

    private var utterStart = 0L
    private var lastVoice = 0L
    private var speechSeen = false
    private var tone: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER -> {
                startForegroundCompat("正在处理…")
                if (!running) boot()
                main.post { onWake() }
                return START_STICKY
            }
            else -> {
                startForegroundCompat("准备中…")
                boot()
                return START_STICKY
            }
        }
    }

    private fun boot() {
        if (running) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotif("没有麦克风权限，请在「小鲸鱼」里点「授予权限」")
            return
        }
        running = true
        val cfg = Cfg.load(this)
        engine.wakeWords = cfg.wakeList
        engine.init(
            cfg.wakeList,
            onReady = { main.post { updateNotif("在听，说「" + (cfg.wakeList.firstOrNull() ?: "小鲸鱼") + "」") } },
            onError = { msg ->
                running = false
                main.post { updateNotif(msg) }
            }
        )
        startAudio()
    }

    private fun startAudio() {
        if (audioThread != null) return
        audioThread = Thread {
            var waited = 0
            while (running && !engine.ready && waited < 300) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                }
                waited++
            }
            if (!running) return@Thread
            if (!engine.ready) {
                main.post { updateNotif("语音模型没加载成功：" + engine.status) }
                return@Thread
            }

            val minBuf = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf * 2, 8000)
            val r = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize * 2
                )
            } catch (e: Throwable) {
                null
            }
            if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
                main.post { updateNotif("麦克风打不开，可能被其他应用占用") }
                return@Thread
            }
            record = r
            try {
                r.startRecording()
            } catch (e: Throwable) {
                main.post { updateNotif("录音启动失败：" + e.message) }
                return@Thread
            }

            val buf = ByteArray(bufSize)
            while (running) {
                val n = try {
                    r.read(buf, 0, buf.size)
                } catch (e: Throwable) {
                    -1
                }
                if (n <= 0) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                    }
                    continue
                }
                try {
                    onPcm(buf, n)
                } catch (_: Throwable) {
                }
            }

            try {
                r.stop()
            } catch (_: Throwable) {
            }
            try {
                r.release()
            } catch (_: Throwable) {
            }
            record = null
        }.apply {
            isDaemon = true
            name = "wake-audio"
            start()
        }
    }

    /** 音频线程：只在 IDLE 时听唤醒词，在 LISTENING 时录一句话。 */
    private fun onPcm(buf: ByteArray, n: Int) {
        when (state) {
            State.IDLE -> {
                val hit = engine.feedKws(buf, n)
                if (hit != null) main.post { onWake() }
            }
            State.LISTENING -> {
                engine.feedAsr(buf, n)
                val now = SystemClock.elapsedRealtime()
                if (rms(buf, n) > VOICE_LEVEL) {
                    lastVoice = now
                    speechSeen = true
                }
                val done = (speechSeen && now - lastVoice > END_SILENCE) || (now - utterStart > MAX_UTTER)
                if (done) {
                    state = State.THINKING
                    val text = engine.finishAsr()
                    main.post { ask(text) }
                }
            }
            else -> {
                // THINKING / SPEAKING：麦克风继续读，避免缓冲区堵住，但不做识别
            }
        }
    }

    private fun onWake() {
        if (state != State.IDLE) return
        state = State.LISTENING
        utterStart = SystemClock.elapsedRealtime()
        lastVoice = utterStart
        speechSeen = false
        engine.resetKws()
        beep()
        overlay.show()
        overlay.set("在听…", "说完停顿一下就行")
        updateNotif("在听你说…")
    }

    private fun ask(raw: String) {
        val q = raw.trim()
        if (q.length < 2) {
            finishTurn("没听清，再说一次好吗？", true, 2500)
            return
        }
        val cfg = Cfg.load(this)
        state = State.THINKING
        overlay.show()
        overlay.set("你", q)

        val msgs = ArrayList<Llm.Msg>()
        msgs.add(Llm.Msg("system", cfg.persona + "\n\n" + Actions.PROTOCOL))
        msgs.addAll(history)
        msgs.add(Llm.Msg("user", q))
        call = Llm(cfg).stream(
            msgs,
            onDelta = { partial -> main.post { overlay.set("小鲸鱼", partial) } },
            onDone = { full -> main.post { handleAnswer(cfg, q, full) } },
            onError = { err -> main.post { finishTurn(err, true, 4000) } }
        )
    }

    /** 模型回答里可能带 @act 指令：先执行，再决定要不要补一轮。 */
    private fun handleAnswer(cfg: Cfg, q: String, full: String) {
        val (spoken, acts) = Actions.split(full)
        if (spoken.isNotEmpty()) overlay.set("小鲸鱼", spoken)

        if (acts.isEmpty()) {
            val answer = spoken.trim()
            if (answer.isEmpty()) {
                finishTurn("模型没有返回内容", true, 2500)
                return
            }
            remember(q, answer)
            speakAnswer(cfg, answer)
            return
        }

        val results = ArrayList<Actions.Result>()
        for (a in acts.take(2)) results.add(Actions.execute(this, a))
        val lines = results.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.message }
        val shown = if (spoken.isBlank()) lines else spoken + "\n\n" + lines
        overlay.set("小鲸鱼", shown)

        val payload = results.firstOrNull { it.payload != null }?.payload
        if (payload != null) {
            remember(q, spoken)
            followUp(cfg, q, payload)
            return
        }
        val answer = if (spoken.isNotBlank()) spoken else lines
        remember(q, answer)
        speakAnswer(cfg, answer)
    }

    /** 读了屏幕内容之后，让模型用一两句话讲给用户听（只补一轮，避免无限循环）。 */
    private fun followUp(cfg: Cfg, q: String, payload: String) {
        val sys = cfg.persona + "\n\n你刚读到手机屏幕上的内容，如下：\n" + payload.take(3000) +
            "\n\n请直接用一两句话回答用户的问题，不要再输出 @act 指令。"
        val msgs = ArrayList<Llm.Msg>()
        msgs.add(Llm.Msg("system", sys))
        msgs.add(Llm.Msg("user", q))
        call = Llm(cfg).stream(
            msgs,
            onDelta = { partial -> main.post { overlay.set("小鲸鱼", partial) } },
            onDone = { full ->
                main.post {
                    val (clean, _) = Actions.split(full)
                    val answer = clean.trim().ifBlank { "读到了屏幕内容，但没什么可说的" }
                    remember(q, answer)
                    speakAnswer(cfg, answer)
                }
            },
            onError = { err -> main.post { finishTurn(err, true, 4000) } }
        )
    }

    private fun remember(q: String, answer: String) {
        history.add(Llm.Msg("user", q))
        history.add(Llm.Msg("assistant", answer))
        while (history.size > 8) history.removeAt(0)
    }

    private fun speakAnswer(cfg: Cfg, answer: String) {
        overlay.set("小鲸鱼", answer)
        state = State.SPEAKING
        if (cfg.speak) {
            updateNotif("正在说…")
            speaker.speak(answer) { main.post { scheduleIdle(800) } }
        } else {
            scheduleIdle(4500)
        }
    }

    private fun finishTurn(message: String, isError: Boolean, delay: Long) {
        overlay.show()
        overlay.set(if (isError) "出错了" else "小鲸鱼", message)
        state = State.SPEAKING
        updateNotif(message.take(40))
        val cfg = Cfg.load(this)
        if (isError && cfg.speak) {
            speaker.speak(message) { main.post { scheduleIdle(delay) } }
        } else {
            scheduleIdle(delay)
        }
    }

    private fun scheduleIdle(delay: Long) {
        main.postDelayed({
            state = State.IDLE
            overlay.hide()
            engine.resetKws()
            val w = Cfg.load(this).wakeList.firstOrNull() ?: "小鲸鱼"
            updateNotif("在听，说「$w」")
        }, delay)
    }

    private fun rms(buf: ByteArray, n: Int): Int {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
            sum += (s.toDouble() * s.toDouble())
            count++
            i += 2
        }
        if (count == 0) return 0
        return sqrt(sum / count).toInt()
    }

    private fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (_: Throwable) {
        }
    }

    private fun shutdown() {
        running = false
        state = State.IDLE
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        try {
            call?.cancel()
        } catch (_: Throwable) {
        }
        try {
            speaker.stop()
        } catch (_: Throwable) {
        }
        main.postDelayed({
            overlay.hide()
            try {
                speaker.shutdown()
            } catch (_: Throwable) {
            }
            try {
                engine.release()
            } catch (_: Throwable) {
            }
            stopForegroundCompat()
            stopSelf()
            audioThread = null
        }, 200)
    }

    override fun onDestroy() {
        running = false
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        try {
            tone?.release()
        } catch (_: Throwable) {
        }
        tone = null
        super.onDestroy()
    }

    /* ------------------------------------------------------------ 通知 */

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "常驻聆听", NotificationManager.IMPORTANCE_LOW).apply {
                description = "小鲸鱼需要常驻后台才能随时被你叫醒"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("小鲸鱼")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        try {
            val notif = buildNotification(text)
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {
        }
    }

    private fun updateNotif(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Throwable) {
        }
    }

    private fun stopForegroundCompat() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
    }
}
