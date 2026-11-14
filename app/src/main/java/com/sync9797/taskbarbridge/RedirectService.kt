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
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Keeps One UI Home as the system default (so Samsung's taskbar stays alive) and
 * bounces the user to their chosen launcher whenever the One UI home screen appears.
 *
 * Detection uses only the event's window class (no view-tree walk), which is what
 * makes the redirect fast. Observed on One UI 9 / Fold 5:
 *   home screen -> com.sec.android.app.launcher.Launcher
 *   Recents     -> com.android.quickstep.RecentsActivity
 */
class RedirectService : AccessibilityService() {

    companion object {
        const val SAMSUNG_HOME = "com.sec.android.app.launcher"

        private const val HOME_CLASS = "com.sec.android.app.launcher.Launcher"
        private val HOME_CLASS_FALLBACKS = listOf(".LauncherActivity", ".activities.LauncherActivity")
        private const val RECENTS_HINT = "Recents"

        // Settle detection: poll the active window until One UI Home owns it.
        private const val SETTLE_POLL_MS = 40L
        private const val SETTLE_STABLE_POLLS = 2
        private const val SETTLE_TIMEOUT_MS = 4_000L

        /** One UI fires a second home event right after we redirect; ignore it. */
        private const val COOLDOWN_MS = 900L

        // Loop guard: more than this many redirects inside the window pauses redirecting.
        private const val LOOP_MAX = 6
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
    private var lastRedirectAt = 0L

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
        if (pkg != SAMSUNG_HOME) return
        val cls = event.className?.toString().orEmpty()
        val displayId = event.displayId
        val diag = Prefs.diagnostics(this)

        if (cls.contains(RECENTS_HINT)) {
            // Recents: leave it alone and cancel any queued redirect.
            stopPoll()
            if (diag) log("skip: Recents ($cls)")
            return
        }
        if (!isHomeClass(cls)) {
            if (diag) log("skip: other One UI window ($cls)")
            return
        }
        if (!Prefs.enabled(this)) return
        val target = Prefs.target(this) ?: return

        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirectAt < COOLDOWN_MS) {
            if (diag) log("skip: cooldown (${now - lastRedirectAt} ms since redirect)")
            return
        }

        // Don't redirect yet: during the swipe-home gesture One UI already shows its home window,
        // and launching an activity mid-gesture cancels the gesture and bounces back to the app.
        // Wait until One UI Home actually owns the active window (gesture finished).
        if (polling) return
        startSettlePoll(target, displayId, event.eventTime)
    }

    private var polling = false
    private var pollStartedAt = 0L
    private var stableCount = 0

    private fun stopPoll() {
        polling = false
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    private fun startSettlePoll(target: ComponentName, displayId: Int, eventTime: Long) {
        polling = true
        pollStartedAt = SystemClock.elapsedRealtime()
        stableCount = 0
        val diag = Prefs.diagnostics(this)
        val tick = object : Runnable {
            override fun run() {
                if (!polling) return
                val elapsed = SystemClock.elapsedRealtime() - pollStartedAt
                val active = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
                // During the swipe the previous app's window is still on screen (being animated).
                // The gesture is over once no other app window is visible.
                val others = otherAppWindows()
                if (diag) log("settle t=${elapsed}ms active=$active others=$others")

                if (active == SAMSUNG_HOME && others.isEmpty()) stableCount++ else stableCount = 0
                when {
                    stableCount >= SETTLE_STABLE_POLLS -> {
                        stopPoll()
                        val extra = Prefs.delayMs(this@RedirectService)
                        if (extra <= 0L) {
                            maybeRedirect(target, displayId, eventTime)
                        } else {
                            val r = Runnable { maybeRedirect(target, displayId, eventTime) }
                            pending = r
                            handler.postDelayed(r, extra)
                        }
                    }
                    elapsed > SETTLE_TIMEOUT_MS -> {
                        stopPoll()
                        if (active == SAMSUNG_HOME) {
                            // Home is up but something (e.g. a pop-up window) stays visible: redirect anyway.
                            if (diag) log("settle timeout with home active (others=$others): redirecting")
                            maybeRedirect(target, displayId, eventTime)
                        } else if (diag) {
                            // Gesture was cancelled or the user stayed in the app: do nothing.
                            log("skip: home never settled (active=$active)")
                        }
                    }
                    else -> handler.postDelayed(this, SETTLE_POLL_MS)
                }
            }
        }
        pending = tick
        handler.post(tick)
    }

    /** Packages of visible app windows other than One UI Home (unknown windows are ignored). */
    private fun otherAppWindows(): List<String> = try {
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { w -> w.root?.packageName?.toString() }
            .filter { it != SAMSUNG_HOME }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }

    private fun isHomeClass(cls: String) =
        cls == HOME_CLASS || HOME_CLASS_FALLBACKS.any { cls.endsWith(it) }

    private fun maybeRedirect(target: ComponentName, displayId: Int, eventTime: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now < pausedUntil) return

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) return

        // Loop guard.
        while (launchTimes.isNotEmpty() && now - launchTimes.first() > LOOP_WINDOW_MS) launchTimes.removeFirst()
        if (launchTimes.size >= LOOP_MAX) {
            pausedUntil = now + LOOP_PAUSE_MS
            launchTimes.clear()
            log("loop guard: pausing redirects for ${LOOP_PAUSE_MS / 1000}s")
            return
        }
        launchTimes.addLast(now)
        lastRedirectAt = now
        launch(target, displayId, SystemClock.uptimeMillis() - eventTime)

        if (Prefs.diagnostics(this)) {
            // Logged after launching so it never slows the redirect down.
            handler.post { rootInActiveWindow?.let { log("ids: " + collectIds(it, 25).joinToString(", ")) } }
        }
    }

    private fun launch(target: ComponentName, displayId: Int, latencyMs: Long) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opts = ActivityOptions.makeCustomAnimation(this, 0, 0)
        if (displayId >= 0) opts.launchDisplayId = displayId
        try {
            startActivity(intent, opts.toBundle())
            log("redirected to ${target.packageName} (display $displayId, ${latencyMs} ms after event)")
        } catch (e: Exception) {
            log("launch failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun collectIds(root: AccessibilityNodeInfo, max: Int): List<String> {
        val ids = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 200 && ids.size < max) {
            val node = queue.removeFirst()
            seen++
            node.viewIdResourceName?.substringAfter(":id/")?.let(ids::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return ids.toList()
    }

    private fun log(msg: String) = DebugLog.add(msg)
}
