package com.sync9797.taskbarbridge

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * Rebind experiment helper: the instant SystemUI writes a taskbar flag back to 0, write 1 again.
 *
 * Uses a ContentObserver and a direct Settings.Global write (needs WRITE_SECURE_SETTINGS, which
 * we grant through Shizuku), so the revert lands within a few ms instead of the ~300 ms a
 * `settings put` shell call takes. That matters if One UI Home reads the flag only once, right
 * when SystemUI rebinds its taskbar service.
 */
object FlagGuard {
    private val KEYS = listOf("sem_task_bar_available", "task_bar")

    private val main = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var startedAt = 0L
    private var stopTask: Runnable? = null

    val isRunning get() = observer != null

    fun start(ctx: Context, durationMs: Long) {
        stop(ctx)
        val cr = ctx.applicationContext.contentResolver
        startedAt = SystemClock.elapsedRealtime()
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val key = uri?.lastPathSegment ?: return
                enforce(ctx, key)
            }
        }
        KEYS.forEach { cr.registerContentObserver(Settings.Global.getUriFor(it), false, obs) }
        observer = obs
        KEYS.forEach { enforce(ctx, it) }
        DebugLog.add("flag guard on for ${durationMs / 1000}s")
        stopTask = Runnable { stop(ctx) }.also { main.postDelayed(it, durationMs) }
    }

    fun stop(ctx: Context) {
        stopTask?.let(main::removeCallbacks)
        stopTask = null
        observer?.let {
            ctx.applicationContext.contentResolver.unregisterContentObserver(it)
            DebugLog.add("flag guard off")
        }
        observer = null
    }

    private fun enforce(ctx: Context, key: String) {
        val cr = ctx.applicationContext.contentResolver
        val v = Settings.Global.getString(cr, key)
        if (v == "1") return
        val t = SystemClock.elapsedRealtime() - startedAt
        try {
            Settings.Global.putInt(cr, key, 1)
            DebugLog.add("flag guard t=${t}ms: $key was $v -> 1")
        } catch (e: SecurityException) {
            DebugLog.add("flag guard: can't write $key (${e.message})")
        }
    }
}
