package com.sync9797.taskbarbridge

import kotlin.system.exitProcess

/**
 * Runs inside a separate process that Shizuku starts with shell (ADB) privileges.
 * Must keep a public no-arg constructor.
 */
class ShellService : IShellService.Stub() {

    private var watcher: TaskWatcher? = null

    override fun destroy() {
        watcher?.stop()
        exitProcess(0)
    }

    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code == 0) output else "$output\n[exit $code]"
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    @Synchronized
    override fun startTaskWatch(cb: ITaskWatcher): String {
        watcher?.stop()
        val w = TaskWatcher(cb)
        val mode = try {
            w.start()
        } catch (e: Throwable) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
        watcher = if (mode.startsWith("error")) {
            w.stop(); null
        } else {
            w
        }
        return mode
    }

    @Synchronized
    override fun stopTaskWatch() {
        watcher?.stop()
        watcher = null
    }

    override fun pokeTaskWatch() {
        watcher?.poke()
    }
}
