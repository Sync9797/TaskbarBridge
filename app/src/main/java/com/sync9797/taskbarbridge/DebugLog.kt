package com.sync9797.taskbarbridge

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer shown on the main screen, so testers can report what One UI emits. */
object DebugLog {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "(empty)" else lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
