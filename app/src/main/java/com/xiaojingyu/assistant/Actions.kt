package com.xiaojingyu.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * 手机动作层：把模型输出的一行 `@act {...}` 变成真的操作。
 * 只需要无障碍的动作走 PhoneA11yService；打开应用/拨号/闹钟走系统 Intent。
 */
object Actions {

    data class Result(val ok: Boolean, val message: String, val payload: String? = null)

    val PROTOCOL = """
【你可以操作手机】
需要动手时，先单独输出一行指令，再（可省略）补一句给用户听的话。
指令格式固定：@act {"do":"...","参数":"..."}

可用动作：
- {"do":"open","app":"微信"}          打开应用；也可以 {"do":"open","url":"https://..."}
- {"do":"call","name":"张三"}         打电话；不知道名字时用 {"do":"call","number":"138..."}
- {"do":"alarm","time":"07:30"}       设闹钟，时间必须转成 24 小时制 HH:mm
- {"do":"timer","minutes":"10"}       倒计时
- {"do":"screen"}                     读取当前屏幕上的文字
- {"do":"click","text":"发送"}        点屏幕上写着这段文字的地方
- {"do":"type","text":"你好"}         往当前输入框打字
- {"do":"scroll","dir":"down"}        滚动屏幕（up/down/left/right）
- {"do":"back"} / {"do":"home"}       返回 / 回桌面
- {"do":"search","q":"明天天气"}      用浏览器搜索

规则：
1. 一次最多一条指令，参数只用用户真的说过的内容，不要编造。
2. 不要把指令念出来，也不要解释指令格式。
3. 要先看屏幕才能回答（例如"屏幕上写的什么"），先发 screen。
4. 打电话、发消息、花钱、删除这类不可撤销的事，先问一句确认再执行。
""".trim()

    private val appAliases = mapOf(
        "微信" to "com.tencent.mm",
        "支付宝" to "com.eg.android.AlipayGphone",
        "电话" to "com.android.dialer",
        "拨号" to "com.android.dialer",
        "短信" to "com.android.mms",
        "相机" to "com.android.camera",
        "相册" to "com.coloros.gallery3d",
        "设置" to "com.android.settings",
        "时钟" to "com.coloros.alarmclock",
        "闹钟" to "com.coloros.alarmclock",
        "日历" to "com.coloros.calendar",
        "计算器" to "com.coloros.calculator",
    )

    /** 把模型输出拆成「给人看的文本」和「要执行的指令列表」。 */
    fun split(text: String): Pair<String, List<Map<String, String>>> {
        val re = Regex("@act\\s*(\\{[^}]*\\})")
        val acts = ArrayList<Map<String, String>>()
        for (m in re.findAll(text)) {
            val obj = Json.obj(m.groupValues[1])
            if (obj.isNotEmpty()) acts.add(obj)
        }
        val clean = text.replace(re, "").replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n").trim()
        return clean to acts
    }

    fun execute(ctx: Context, a: Map<String, String>): Result {
        val what = (a["do"] ?: a["action"] ?: "").lowercase()
        return try {
            when (what) {
                "open" -> open(ctx, a["app"] ?: a["url"] ?: a["name"] ?: "")
                "call" -> call(ctx, a["name"] ?: a["number"] ?: a["to"] ?: "")
                "alarm" -> alarm(ctx, a["time"] ?: a["at"] ?: "")
                "timer" -> timer(ctx, a["minutes"] ?: "", a["seconds"] ?: "")
                "screen", "read" -> screen()
                "click" -> click(a["text"] ?: "")
                "type", "input" -> type(a["text"] ?: "")
                "scroll" -> scroll(a["dir"] ?: "down")
                "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "已返回")
                "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "已回到桌面")
                "search" -> search(ctx, a["q"] ?: a["text"] ?: "")
                else -> Result(false, "我还不认识「" + (a["do"] ?: "") + "」这个操作")
            }
        } catch (e: Throwable) {
            Result(false, "操作没成功：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /* ------------------------------------------------------------ 应用 */

    private fun open(ctx: Context, arg: String): Result {
        val target = arg.trim()
        if (target.isEmpty()) return Result(false, "要打开什么？")
        if (target.startsWith("http://") || target.startsWith("https://")) {
            launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(target)))
            return Result(true, "已经打开网页")
        }
        val pkg = findPackage(ctx, target)
            ?: return Result(false, "没找到叫「$target」的应用")
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return Result(true, "找到了 $target，但它打不开")
        launch(ctx, intent)
        val label = runCatching {
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(target)
        return Result(true, "已经打开$label")
    }

    private fun findPackage(ctx: Context, name: String): String? {
        appAliases[name]?.let { alias ->
            if (isInstalled(ctx, alias)) return alias
        }
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = try {
            pm.queryIntentActivities(intent, 0)
        } catch (_: Throwable) {
            emptyList()
        }
        var fuzzy: String? = null
        for (ri in list) {
            val pkg = ri.activityInfo.packageName
            val label = try {
                ri.loadLabel(pm).toString()
            } catch (_: Throwable) {
                pkg
            }
            if (label == name) return pkg
            if (fuzzy == null && name.isNotEmpty() && (label.contains(name) || pkg.contains(name))) fuzzy = pkg
        }
        return fuzzy
    }

    private fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    /* ------------------------------------------------------------ 电话 */

    private fun call(ctx: Context, arg: String): Result {
        val who = arg.trim()
        if (who.isEmpty()) return Result(false, "要打给谁？")
        val number = if (who.matches(Regex("[+0-9*#, ]{3,}"))) who.replace(" ", "") else findContact(ctx, who)
        if (number.isNullOrBlank()) return Result(false, "通讯录里没找到「$who」，你可以说号码我拨")

        val canCall = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
        launch(ctx, intent)
        return Result(true, if (canCall) "正在拨给$who" else "已经打开拨号界面，点一下绿色按钮就能打给$who")
    }

    private fun findContact(ctx: Context, name: String): String? {
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val sel = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?"
            ctx.contentResolver.query(uri, proj, sel, arrayOf("%$name%"), null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /* ------------------------------------------------------------ 闹钟 */

    private fun alarm(ctx: Context, arg: String): Result {
        val nums = Regex("\\d+").findAll(arg).map { it.value.toInt() }.toList()
        if (nums.isEmpty()) return Result(false, "几点？我帮你设闹钟")
        val hour = nums[0].coerceIn(0, 23)
        val minute = if (nums.size > 1) nums[1].coerceIn(0, 59) else 0
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "小鲸鱼设的闹钟")
        launch(ctx, intent)
        return Result(true, "闹钟设好了：" + two(hour) + ":" + two(minute))
    }

    private fun timer(ctx: Context, minutes: String, seconds: String): Result {
        val sec = seconds.trim().toIntOrNull()
            ?: (minutes.trim().toIntOrNull() ?: 10) * 60
        if (sec <= 0) return Result(false, "多久？")
        launch(
            ctx,
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, sec)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "小鲸鱼计时")
        )
        return Result(true, "开始计时 " + (if (sec >= 60) (sec / 60).toString() + " 分钟" else sec.toString() + " 秒"))
    }

    /* ------------------------------------------------------------ 无障碍 */

    private fun screen(): Result {
        val svc = PhoneA11yService.instance
            ?: return Result(false, "无障碍服务没开，我读不了屏幕。请到设置里打开「小鲸鱼」的无障碍服务")
        val text = try {
            svc.dumpScreen()
        } catch (e: Throwable) {
            ""
        }
        if (text.isBlank()) return Result(false, "屏幕上没读到文字")
        return Result(true, "读到屏幕内容", text)
    }

    private fun click(arg: String): Result {
        val svc = PhoneA11yService.instance
            ?: return Result(false, "无障碍服务没开，我点不了屏幕")
        val target = arg.trim()
        if (target.isEmpty()) return Result(false, "要点哪里？")
        return if (svc.clickText(target)) Result(true, "点了「$target」")
        else Result(false, "屏幕上没找到「$target」")
    }

    private fun type(arg: String): Result {
        val svc = PhoneA11yService.instance
            ?: return Result(false, "无障碍服务没开，我打不了字")
        if (arg.isEmpty()) return Result(false, "要打什么？")
        return if (svc.setTextOnFocus(arg)) Result(true, "已经输入")
        else Result(false, "当前没有可以打字的输入框")
    }

    private fun scroll(dir: String): Result {
        val svc = PhoneA11yService.instance
            ?: return Result(false, "无障碍服务没开，我滚不了屏幕")
        return if (svc.scroll(dir)) Result(true, "已滚动") else Result(false, "这一页好像不能滚动")
    }

    private fun global(action: Int, okMsg: String): Result {
        val svc = PhoneA11yService.instance
            ?: return Result(false, "无障碍服务没开，我按不了键")
        return if (svc.globalAction(action)) Result(true, okMsg) else Result(false, "这个操作没成功")
    }

    private fun search(ctx: Context, q: String): Result {
        if (q.isBlank()) return Result(false, "搜什么？")
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        launch(ctx, intent)
        return Result(true, "正在搜「$q」")
    }

    /* ------------------------------------------------------------ 公共 */

    private fun launch(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    private fun two(v: Int): String = if (v < 10) "0$v" else v.toString()
}
