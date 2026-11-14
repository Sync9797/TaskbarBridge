package com.sync9797.taskbarbridge

import android.content.ComponentName
import android.content.Context

/** Small wrapper around the app's SharedPreferences. */
object Prefs {
    private const val FILE = "settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGET = "target"
    private const val KEY_DELAY = "delay_ms"
    private const val KEY_DIAGNOSTICS = "diagnostics"

    const val DEFAULT_DELAY_MS = 60L

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = sp(ctx).getBoolean(KEY_ENABLED, true)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    fun target(ctx: Context): ComponentName? =
        sp(ctx).getString(KEY_TARGET, null)?.let(ComponentName::unflattenFromString)

    fun setTarget(ctx: Context, c: ComponentName) =
        sp(ctx).edit().putString(KEY_TARGET, c.flattenToString()).apply()

    /** How long to wait after One UI Home appears before redirecting (lets us tell Home from Recents). */
    fun delayMs(ctx: Context) = sp(ctx).getLong(KEY_DELAY, DEFAULT_DELAY_MS)
    fun setDelayMs(ctx: Context, v: Long) = sp(ctx).edit().putLong(KEY_DELAY, v).apply()

    fun diagnostics(ctx: Context) = sp(ctx).getBoolean(KEY_DIAGNOSTICS, false)
    fun setDiagnostics(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_DIAGNOSTICS, v).apply()
}
