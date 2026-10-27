package com.xiaojingyu.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 开机/更新后不直接拉起常驻服务：Android 14 起禁止从 BOOT_COMPLETED 启动 microphone 类型的前台服务。
 * 这里改成发一条通知，点一下即进 App 自动开始聆听。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Cfg.load(context).autoStart) return

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = "boot"
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channel) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channel, "开机提醒", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val pi = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).putExtra("autostart", true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("小鲸鱼")
                .setContentText("点一下，继续随时喊我")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(7, notif)
        } catch (_: Throwable) {
        }
    }
}
