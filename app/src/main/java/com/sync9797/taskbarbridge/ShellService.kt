package com.sync9797.taskbarbridge

import kotlin.system.exitProcess

/**
 * Runs inside a separate process that Shizuku starts with shell (ADB) privileges.
 * Must keep a public no-arg constructor.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
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
}
