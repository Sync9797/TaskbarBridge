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
import rikka.shizuku.Shizuku

/**
 * Keeps One UI Home as the system default (so Samsung's taskbar stays alive) and
 * bounces the user to their chosen launcher whenever the One UI home screen appears.
 *
 * Two ways to detect the home screen:
 * - Shizuku (preferred): a task-stack listener in the shell process tells us when One UI
 *   Home's task is on top AND the previous app is no longer visible, i.e. the swipe-home
 *   gesture has really finished. No guessing, no polling of accessibility windows.
 * - Accessibility (fallback): window events plus settle polling, below.
 *
 * Accessibility detection uses only the event's window class (no view-tree walk), which is what
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

        // Bounce recovery.
        private const val BOUNCE_POLL_MS = 50L
        private const val BOUNCE_WATCH_MS = 2_000L
        private const val BOUNCE_MAX_RETRIES = 2

        /** One UI fires a second home event right after we redirect; ignore it. */
        private const val COOLDOWN_MS = 900L

        // Loop guard: more than this many redirects inside the window pauses redirecting.
        private const val LOOP_MAX = 6
        private const val LOOP_WINDOW_MS = 4_000L
        private const val LOOP_PAUSE_MS = 15_000L

        @Volatile
        var isRunning = false
            private set

        /** "accessibility", "shizuku-listener" or "shizuku-poke-only"; shown on the main screen. */
        @Volatile
        var detectionMode = "accessibility"
            private set

        @Volatile
        var instance: RedirectService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private val launchTimes = ArrayDeque<Long>()
    private var pausedUntil = 0L
    private var lastRedirectAt = 0L

    override fun onServiceConnected() {
        isRunning = true
        instance = this
        DebugLog.add("Redirect service connected")
        Shizuku.addBinderReceivedListenerSticky(shizukuReceived)
        Shizuku.addBinderDeadListener(shizukuDead)
        ShizukuShell.disconnectListeners.add(shellDisconnected)
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        Shizuku.removeBinderReceivedListener(shizukuReceived)
        Shizuku.removeBinderDeadListener(shizukuDead)
        ShizukuShell.disconnectListeners.remove(shellDisconnected)
        if (taskMode != null) ShizukuShell.stopTaskWatch()
        taskMode = null
        detectionMode = "accessibility"
        DebugLog.add("Redirect service destroyed")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Shizuku task watch

    /** null = accessibility detection; otherwise the mode the shell watcher reported. */
    private var taskMode: String? = null
    private var starting = false
    private var topPkg: String? = null
    /** Last ordinary app on top, i.e. the app the user swiped away from. */
    private var lastAppPkg: String? = null
    private var redirectedFrom: String? = null
    private var bounceUntil = 0L
    private var bounceAttempts = 0

    private val watcherCb = object : ITaskWatcher.Stub() {
        override fun onTopTask(taskId: Int, displayId: Int, topComponent: String, othersVisible: String) {
            handler.post { onTopTask(taskId, displayId, topComponent, othersVisible) }
        }

        override fun onLog(msg: String) = DebugLog.add(msg)
    }

    private val shizukuReceived = Shizuku.OnBinderReceivedListener { handler.post { startTaskWatch() } }
    private val shizukuDead = Shizuku.OnBinderDeadListener { handler.post { taskWatchLost("Shizuku stopped") } }
    private val shellDisconnected: () -> Unit = {
        taskWatchLost("shell process died")
        handler.postDelayed({ startTaskWatch() }, 1_000)
    }

    /** Called by the main screen when the detection switch or Shizuku permission changes. */
    fun reconfigure() {
        if (Prefs.shizukuDetection(this)) startTaskWatch() else {
            if (taskMode != null) ShizukuShell.stopTaskWatch()
            taskWatchLost("turned off")
        }
    }

    private fun startTaskWatch() {
        if (taskMode != null || starting) return
        if (!Prefs.shizukuDetection(this) || !ShizukuShell.hasPermission()) return
        starting = true
        ShizukuShell.startTaskWatch(watcherCb) cb@{ mode ->
            starting = false
            if (mode.startsWith("error")) {
                log("Shizuku task watch unavailable ($mode); using accessibility")
                return@cb
            }
            taskMode = mode
            detectionMode = "shizuku-$mode"
            stopPoll()
            stopWatch()
            log("Detection: Shizuku task watch ($mode)")
        }
    }

    private fun taskWatchLost(why: String) {
        if (taskMode == null) return
        taskMode = null
        detectionMode = "accessibility"
        log("Shizuku task watch stopped ($why); using accessibility")
    }

    private fun onTopTask(taskId: Int, displayId: Int, comp: String, others: String) {
        if (taskMode == null) return
        val cn = ComponentName.unflattenFromString(comp)
        val pkg = cn?.packageName ?: return
        val cls = cn.className
        val diag = Prefs.diagnostics(this)
        if (diag) log("task top=$comp #$taskId d$displayId visible=[$others]")
        topPkg = pkg
        val target = Prefs.target(this)
        val now = SystemClock.elapsedRealtime()

        if (pkg != SAMSUNG_HOME) {
            pending?.let(handler::removeCallbacks)
            pending = null
            if (target != null && pkg == target.packageName) {
                if (diag && now < bounceUntil) log("landed on $pkg")
                return
            }
            if (target != null && now < bounceUntil && pkg == redirectedFrom) {
                if (bounceAttempts >= BOUNCE_MAX_RETRIES) {
                    log("bounced back to $pkg again; giving up")
                    bounceUntil = 0
                } else {
                    bounceAttempts++
                    log("bounced back to $pkg -> retry #$bounceAttempts")
                    lastRedirectAt = now
                    launch(target, displayId, -1)
                }
                return
            }
            bounceUntil = 0
            if (pkg != packageName) lastAppPkg = pkg
            return
        }

        // One UI Home is on top.
        if (cls.contains(RECENTS_HINT)) {
            if (diag) log("skip: Recents")
            return
        }
        if (!isHomeClass(cls)) {
            if (diag) log("skip: other One UI activity ($cls)")
            return
        }
        if (others.isNotEmpty()) {
            // Swipe-home animation still running: the app we came from is still on screen.
            if (diag) log("waiting: $others still visible")
            return
        }
        if (!Prefs.enabled(this) || target == null) return
        if (now - lastRedirectAt < COOLDOWN_MS) {
            if (diag) log("skip: cooldown")
            return
        }
        pending?.let(handler::removeCallbacks)
        val go = Runnable {
            pending = null
            if (topPkg != SAMSUNG_HOME) return@Runnable // user left home during the delay
            redirectedFrom = lastAppPkg
            if (maybeRedirect(target, displayId, -1L, watchBounce = false)) {
                bounceUntil = SystemClock.elapsedRealtime() + BOUNCE_WATCH_MS
                bounceAttempts = 0
            }
        }
        val extra = Prefs.delayMs(this)
        if (extra <= 0L) go.run() else {
            pending = go
            handler.postDelayed(go, extra)
        }
    }

    // ---------------------------------------------------------------- accessibility detection

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != SAMSUNG_HOME) return
        if (taskMode != null) {
            // The shell watcher decides; a window event is just a hint to look again.
            ShizukuShell.pokeTaskWatch()
            return
        }
        val cls = event.className?.toString().orEmpty()
        val displayId = event.displayId
        val diag = Prefs.diagnostics(this)

        if (cls.contains(RECENTS_HINT)) {
            // Recents: leave it alone and cancel any queued redirect.
            stopPoll()
            stopWatch()
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
    /** App(s) that were on screen during the swipe: if one of these comes back, we bounced. */
    private var previousApps: List<String> = emptyList()
    private var watch: Runnable? = null

    private fun stopPoll() {
        polling = false
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    private fun startSettlePoll(target: ComponentName, displayId: Int, eventTime: Long) {
        polling = true
        pollStartedAt = SystemClock.elapsedRealtime()
        stableCount = 0
        previousApps = emptyList()
        stopWatch()
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
                if (others.isNotEmpty()) previousApps = others

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

    private fun stopWatch() {
        watch?.let(handler::removeCallbacks)
        watch = null
    }

    /**
     * One UI is sometimes still finishing the swipe-home transition when home already looks
     * settled. Launching then makes Android cancel the transition and restore the previous app.
     * Watch for that and relaunch the target once the restore is done.
     */
    private fun watchForBounce(target: ComponentName, displayId: Int, previous: List<String>, attempt: Int) {
        stopWatch()
        if (previous.isEmpty()) return
        val diag = Prefs.diagnostics(this)
        val startedAt = SystemClock.elapsedRealtime()
        var onTarget = 0
        val r = object : Runnable {
            override fun run() {
                val t = SystemClock.elapsedRealtime() - startedAt
                val active = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
                when {
                    active == target.packageName -> {
                        onTarget++
                        if (onTarget >= 4) {
                            if (diag) log("landed on ${target.packageName} after ${t}ms")
                            watch = null
                            return
                        }
                    }
                    active != null && active in previous -> {
                        onTarget = 0
                        watch = null
                        if (attempt > BOUNCE_MAX_RETRIES) {
                            log("bounced back to $active again; giving up")
                            return
                        }
                        log("bounced back to $active after ${t}ms -> retry #$attempt")
                        lastRedirectAt = SystemClock.elapsedRealtime()
                        launch(target, displayId, -1)
                        watchForBounce(target, displayId, previous, attempt + 1)
                        return
                    }
                    else -> onTarget = 0
                }
                if (t > BOUNCE_WATCH_MS) {
                    if (diag) log("bounce watch ended (active=$active)")
                    watch = null
                    return
                }
                handler.postDelayed(this, BOUNCE_POLL_MS)
            }
        }
        watch = r
        handler.postDelayed(r, BOUNCE_POLL_MS)
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

    /** Returns true if it launched the target. [eventTime] < 0 means "no event latency to log". */
    private fun maybeRedirect(
        target: ComponentName,
        displayId: Int,
        eventTime: Long,
        watchBounce: Boolean = true,
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < pausedUntil) return false

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) return false

        // Loop guard.
        while (launchTimes.isNotEmpty() && now - launchTimes.first() > LOOP_WINDOW_MS) launchTimes.removeFirst()
        if (launchTimes.size >= LOOP_MAX) {
            pausedUntil = now + LOOP_PAUSE_MS
            launchTimes.clear()
            log("loop guard: pausing redirects for ${LOOP_PAUSE_MS / 1000}s")
            return false
        }
        launchTimes.addLast(now)
        lastRedirectAt = now
        launch(target, displayId, if (eventTime < 0) 0 else SystemClock.uptimeMillis() - eventTime)
        if (watchBounce) watchForBounce(target, displayId, previousApps, attempt = 1)

        if (Prefs.diagnostics(this)) {
            // Logged after launching so it never slows the redirect down.
            handler.post { rootInActiveWindow?.let { log("ids: " + collectIds(it, 25).joinToString(", ")) } }
        }
        return true
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
            log(if (latencyMs >= 0) "redirected to ${target.packageName} (display $displayId, ${latencyMs} ms after event)"
                else "relaunched ${target.packageName}")
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
