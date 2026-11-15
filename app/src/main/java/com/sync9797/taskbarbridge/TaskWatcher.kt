package com.sync9797.taskbarbridge

import android.content.ComponentName
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Runs inside the Shizuku (shell) process. Registers a task-stack listener with the window
 * manager and reports the top task, plus which other apps are still visible, to the app.
 *
 * Everything goes through reflection so it survives Samsung/AOSP signature changes:
 * - The listener is a raw [Binder] behind a dynamic proxy of `android.app.ITaskStackListener`.
 *   We don't decode transaction codes (they shift between Android versions); any call just
 *   means "the task stack changed", and we read the real state with `getTasks`.
 * - After a change we keep polling briefly, because visibility (the swipe-home animation
 *   finishing) doesn't always produce a listener call of its own.
 */
class TaskWatcher(private val cb: ITaskWatcher) {

    companion object {
        private const val LISTENER = "android.app.ITaskStackListener"
        private const val BURST_POLL_MS = 32L
        private const val BURST_MAX_MS = 4_000L
        private const val BURST_STABLE_POLLS = 4
        private const val MAX_TASKS = 8
        // WindowConfiguration.WINDOWING_MODE_PINNED / WINDOWING_MODE_FREEFORM
        private val FLOATING_MODES = setOf(2, 5)
    }

    private val thread = HandlerThread("TaskWatcher").apply { start() }
    private val handler = Handler(thread.looper)

    private val atm: Any by lazy {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "activity_task") as IBinder
        Class.forName("android.app.IActivityTaskManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }
    private val getTasks: Method by lazy { atm.javaClass.methods.first { it.name == "getTasks" } }

    private var listenerProxy: Any? = null
    private var lastReported = ""
    private var burstStartedAt = 0L
    private var stablePolls = 0
    private var bursting = false

    private val listenerBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                poke()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }.apply { attachInterface(null, LISTENER) }

    private val burst = object : Runnable {
        override fun run() {
            val changed = check()
            stablePolls = if (changed) 0 else stablePolls + 1
            val elapsed = SystemClock.uptimeMillis() - burstStartedAt
            if (stablePolls >= BURST_STABLE_POLLS || elapsed > BURST_MAX_MS) {
                bursting = false
                return
            }
            handler.postDelayed(this, BURST_POLL_MS)
        }
    }

    /** Returns "listener", "poke-only" or "error: …". */
    fun start(): String {
        val sig = try {
            getTasks.parameterTypes.joinToString(",") { it.simpleName }
        } catch (e: Throwable) {
            return "error: no IActivityTaskManager (${e.javaClass.simpleName}: ${e.message})"
        }
        log("getTasks($sig)")
        val mode = try {
            val iface = Class.forName(LISTENER)
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { self, m, args ->
                when (m.name) {
                    "asBinder" -> listenerBinder
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.getOrNull(0)
                    "toString" -> "TaskBridgeListener"
                    else -> null
                }
            }
            atm.javaClass.getMethod("registerTaskStackListener", iface).invoke(atm, proxy)
            listenerProxy = proxy
            "listener"
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            log("listener failed: ${cause.javaClass.simpleName}: ${cause.message}")
            "poke-only"
        }
        poke()
        return mode
    }

    fun stop() {
        listenerProxy?.let { p ->
            try {
                val iface = Class.forName(LISTENER)
                atm.javaClass.getMethod("unregisterTaskStackListener", iface).invoke(atm, p)
            } catch (_: Throwable) {
            }
        }
        listenerProxy = null
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    fun poke() {
        handler.post {
            burstStartedAt = SystemClock.uptimeMillis()
            stablePolls = 0
            if (!bursting) {
                bursting = true
                handler.post(burst)
            }
        }
    }

    /** Reads the task stack; reports and returns true if what we'd report changed. */
    private fun check(): Boolean {
        val tasks = try {
            queryTasks()
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            log("getTasks failed: ${cause.javaClass.simpleName}: ${cause.message}")
            return false
        }
        val top = tasks.firstOrNull() ?: return false
        val topPkg = top.component?.packageName
        val others = tasks.drop(1)
            // Pop-up (freeform) and picture-in-picture windows can stay up on top of home; ignore them.
            .filter { it.displayId == top.displayId && it.visible && it.windowingMode !in FLOATING_MODES }
            .filter { it.component?.packageName != topPkg }
            .mapNotNull { it.component?.packageName }
            .distinct()
            .joinToString(",")
        val comp = top.component?.flattenToShortString().orEmpty()
        val key = "${top.taskId}|${top.displayId}|$comp|$others"
        if (key == lastReported) return false
        lastReported = key
        try {
            cb.onTopTask(top.taskId, top.displayId, comp, others)
        } catch (_: Throwable) {
            // App died; Shizuku will tear us down.
        }
        return true
    }

    private class Task(
        val taskId: Int,
        val displayId: Int,
        val component: ComponentName?,
        val visible: Boolean,
        val windowingMode: Int,
    )

    private fun queryTasks(): List<Task> {
        var ints = 0
        val args = getTasks.parameterTypes.map { t ->
            when (t) {
                Int::class.javaPrimitiveType -> if (ints++ == 0) MAX_TASKS else -1 // maxNum, then displayId = INVALID (all)
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        @Suppress("UNCHECKED_CAST")
        val list = getTasks.invoke(atm, *args) as List<Any>
        return list.map { info ->
            Task(
                taskId = intField(info, "taskId") ?: -1,
                displayId = intField(info, "displayId") ?: 0,
                component = (field(info, "topActivity") as? ComponentName)
                    ?: (field(info, "baseActivity") as? ComponentName),
                // isVisible = actually on screen (still true while the swipe-home animation plays).
                visible = (field(info, "isVisible") as? Boolean)
                    ?: (field(info, "isVisibleRequested") as? Boolean)
                    ?: false,
                windowingMode = windowingMode(info),
            )
        }
    }

    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }.get(o)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun intField(o: Any, name: String) = field(o, name) as? Int

    private fun windowingMode(info: Any): Int = try {
        val config = field(info, "configuration") ?: return 0
        val wc = field(config, "windowConfiguration") ?: return 0
        wc.javaClass.getMethod("getWindowingMode").invoke(wc) as Int
    } catch (_: Throwable) {
        0
    }

    private fun log(msg: String) {
        try {
            cb.onLog("[shell] $msg")
        } catch (_: Throwable) {
        }
    }
}
