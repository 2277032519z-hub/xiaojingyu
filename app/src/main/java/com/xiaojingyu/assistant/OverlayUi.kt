package com.xiaojingyu.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 屏幕底部的悬浮提示条：不抢焦点，唤醒后显示「在听 / 思考中 / 回答」。 */
class OverlayUi(private val ctx: Context) {

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var body: TextView? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(ctx)

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun show() {
        if (root != null || !canShow()) return
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F21A1E26"))
                setStroke(dp(1), Color.parseColor("#554D7CFE"))
            }
        }
        title = TextView(ctx).apply {
            setTextColor(Color.parseColor("#7AA2FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "小鲸鱼"
        }
        body = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, dp(6), 0, 0)
            maxLines = 8
        }
        card.addView(title)
        card.addView(body)

        val screenW = ctx.resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            screenW - dp(24),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            x = dp(12)
            y = dp(28)
        }

        try {
            w.addView(card, params)
            wm = w
            root = card
        } catch (e: Throwable) {
            root = null
        }
    }

    fun set(title: String, body: String) {
        this.title?.text = title
        this.body?.text = body
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    fun hide() {
        val r = root ?: return
        try {
            wm?.removeView(r)
        } catch (_: Throwable) {
        }
        root = null
        wm = null
        title = null
        body = null
    }
}
