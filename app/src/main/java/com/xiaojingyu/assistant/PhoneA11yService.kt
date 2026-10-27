package com.xiaojingyu.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务：让助手能「看屏幕 + 点屏幕 + 打字 + 返回」。
 * 只在使用时读取当前窗口，不常驻分析、不记录。
 */
class PhoneA11yService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PhoneA11yService? = null
            private set

        fun alive(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    /** 按文字点：先精确匹配，再包含匹配，点不到就往上找可点击的父节点。 */
    fun clickText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val needle = target.trim()
        if (needle.isEmpty()) return false
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes)

        val exact = nodes.firstOrNull {
            it.text?.toString()?.trim() == needle || it.contentDescription?.toString()?.trim() == needle
        }
        val hit = exact ?: nodes.firstOrNull {
            val t = it.text?.toString() ?: ""
            val d = it.contentDescription?.toString() ?: ""
            t.contains(needle) || d.contains(needle)
        } ?: return false

        var node: AccessibilityNodeInfo? = hit
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
        }
        return hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** 往当前焦点输入框写字；没有焦点就找第一个可编辑的。 */
    fun setTextOnFocus(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes)
        val target = nodes.firstOrNull { it.isFocused && it.isEditable }
            ?: nodes.firstOrNull { it.isEditable }
            ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun dumpScreen(limit: Int = 160): String {
        val root = rootInActiveWindow ?: return ""
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes)
        val seen = HashSet<String>()
        val sb = StringBuilder()
        var count = 0
        for (n in nodes) {
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val line = if (t.isNotEmpty()) t else d
            if (line.isEmpty()) continue
            if (!seen.add(line)) continue
            sb.append(line).append('\n')
            if (++count >= limit) break
        }
        return sb.toString().trim()
    }

    /** dir: up / down / left / right（也认中文） */
    fun scroll(dir: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val backward = dir.contains("上") || dir.contains("左") || dir == "up" || dir == "left"
        val action = if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes)
        val scroller = nodes.firstOrNull { it.isScrollable } ?: root
        return scroller.performAction(action)
    }

    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        out.add(node)
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out)
        }
    }
}
