package com.sync9797.taskbarbridge

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** Binds [ShellService] through Shizuku and runs shell commands on a background thread. */
object ShizukuShell {
    const val PERMISSION_REQUEST = 7797

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var service: IShellService? = null
    private val waiting = mutableListOf<(IShellService?) -> Unit>()

    private val args by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            service = s
            flush(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun isRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun hasPermission(): Boolean = isRunning() && !Shizuku.isPreV11() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestPermission() = Shizuku.requestPermission(PERMISSION_REQUEST)

    private fun flush(s: IShellService?) {
        val callbacks = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
        callbacks.forEach { it(s) }
    }

    private fun withService(cb: (IShellService?) -> Unit) {
        service?.let { return cb(it) }
        if (!hasPermission()) return cb(null)
        synchronized(waiting) { waiting.add(cb) }
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) {
            DebugLog.add("Shizuku bind failed: ${e.message}")
            flush(null)
        }
    }

    /** Runs [command] as shell; [onResult] is delivered on the main thread. */
    fun exec(command: String, onResult: (String) -> Unit) {
        withService { s ->
            if (s == null) {
                main.post { onResult("Shizuku is not running or permission was not granted.") }
                return@withService
            }
            worker.execute {
                val out = try { s.exec(command) } catch (e: Throwable) { "error: ${e.message}" }
                main.post { onResult(out) }
            }
        }
    }
}
