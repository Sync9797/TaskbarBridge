package com.sync9797.taskbarbridge

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Keeps One UI Home as the system default (so Samsung's taskbar stays alive) and
 * bounces the user to their chosen launcher whenever the One UI home screen appears.
 */
class RedirectService : AccessibilityService() {

    companion object {
        const val SAMSUNG_HOME = "com.sec.android.app.launcher"

        /** Fragments of view IDs / text that indicate Recents (overview), not the home screen. */
        private val RECENTS_HINTS = listOf("recents", "clear_all", "clearall", "overview", "task_view", "taskview")
        private val RECENTS_TEXT = listOf("close all", "clear all")

        // Loop guard: more than this many redirects inside the window pauses redirecting.
        private const val LOOP_MAX = 5
        private const val LOOP_WINDOW_MS = 4_000L
        private const val LOOP_PAUSE_MS = 15_000L

        @Volatile
        var isRunning = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private val launchTimes = ArrayDeque<Long>()
    private var pausedUntil = 0L

    override fun onServiceConnected() {
        isRunning = true
        DebugLog.add("Redirect service connected")
    }

    override fun onDestroy() {
        isRunning = false
        DebugLog.add("Redirect service destroyed")
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()
        val displayId = event.displayId

        if (Prefs.diagnostics(this)) DebugLog.add("event pkg=$pkg cls=$cls display=$displayId")
        if (pkg != SAMSUNG_HOME || !Prefs.enabled(this)) return
        val target = Prefs.target(this) ?: return

        // Debounce: One UI fires several state changes during the home animation.
        pending?.let(handler::removeCallbacks)
        val task = Runnable { maybeRedirect(target, displayId) }
        pending = task
        handler.postDelayed(task, Prefs.delayMs(this))
    }

    private fun maybeRedirect(target: ComponentName, displayId: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now < pausedUntil) return

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) return

        val root = rootInActiveWindow
        val activePkg = root?.packageName?.toString()
        if (activePkg != null && activePkg != SAMSUNG_HOME) {
            log("skip: active window is $activePkg")
            return
        }
        if (root != null) {
            if (Prefs.diagnostics(this)) log("ids: " + collectIds(root, 40).joinToString(", "))
            if (looksLikeRecents(root)) {
                log("skip: Recents detected")
                return
            }
        }

        // Loop guard.
        while (launchTimes.isNotEmpty() && now - launchTimes.first() > LOOP_WINDOW_MS) launchTimes.removeFirst()
        if (launchTimes.size >= LOOP_MAX) {
            pausedUntil = now + LOOP_PAUSE_MS
            launchTimes.clear()
            log("loop guard: pausing redirects for ${LOOP_PAUSE_MS / 1000}s")
            return
        }
        launchTimes.addLast(now)
        launch(target, displayId)
    }

    private fun launch(target: ComponentName, displayId: Int) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opts = ActivityOptions.makeCustomAnimation(this, 0, 0)
        if (displayId >= 0) opts.launchDisplayId = displayId
        try {
            startActivity(intent, opts.toBundle())
            log("redirected to ${target.packageName} (display $displayId)")
        } catch (e: Exception) {
            log("launch failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun looksLikeRecents(root: AccessibilityNodeInfo): Boolean {
        var found = false
        walk(root, 400) { node ->
            val id = node.viewIdResourceName?.lowercase()
            val text = node.text?.toString()?.lowercase()
            if ((id != null && RECENTS_HINTS.any { id.contains(it) }) ||
                (text != null && RECENTS_TEXT.any { text == it })
            ) {
                found = true
            }
            !found
        }
        return found
    }

    private fun collectIds(root: AccessibilityNodeInfo, max: Int): List<String> {
        val ids = LinkedHashSet<String>()
        walk(root, 400) { node ->
            node.viewIdResourceName?.substringAfter(":id/")?.let(ids::add)
            ids.size < max
        }
        return ids.toList()
    }

    /** Breadth-first walk; [visit] returns false to stop early. */
    private inline fun walk(root: AccessibilityNodeInfo, limit: Int, visit: (AccessibilityNodeInfo) -> Boolean) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < limit) {
            val node = queue.removeFirst()
            seen++
            if (!visit(node)) return
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
    }

    private fun log(msg: String) = DebugLog.add(msg)
}
