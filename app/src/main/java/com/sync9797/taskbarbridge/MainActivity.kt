package com.sync9797.taskbarbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView

    private val serviceComponent by lazy { ComponentName(this, RedirectService::class.java) }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == ShizukuShell.PERMISSION_REQUEST) {
            toast(if (result == PackageManager.PERMISSION_GRANTED) "Shizuku connected" else "Shizuku permission denied")
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        logView = findViewById(R.id.log)

        Shizuku.addRequestPermissionResultListener(permissionListener)

        findViewById<SwitchCompat>(R.id.enabled).apply {
            isChecked = Prefs.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setEnabled(this@MainActivity, v); refresh() }
        }
        findViewById<SwitchCompat>(R.id.diagnostics).apply {
            isChecked = Prefs.diagnostics(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setDiagnostics(this@MainActivity, v) }
        }

        val delayLabel = findViewById<TextView>(R.id.delay_label)
        findViewById<SeekBar>(R.id.delay).apply {
            progress = Prefs.delayMs(this@MainActivity).toInt()
            delayLabel.text = "Extra delay after the swipe finishes: $progress ms"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    delayLabel.text = "Extra delay after the swipe finishes: $p ms"
                    if (fromUser) Prefs.setDelayMs(this@MainActivity, p.toLong())
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }

        findViewById<Button>(R.id.pick).setOnClickListener { pickLauncher() }
        findViewById<Button>(R.id.home_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        findViewById<Button>(R.id.a11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.shizuku).setOnClickListener { connectShizuku() }
        findViewById<Button>(R.id.auto_enable).setOnClickListener { autoEnable() }
        findViewById<Button>(R.id.probe).setOnClickListener { probe() }
        findViewById<Button>(R.id.native_try).setOnClickListener { tryNativeMode() }
        findViewById<Button>(R.id.native_restore).setOnClickListener { restoreSamsungHome() }
        findViewById<Button>(R.id.native_restart).setOnClickListener { forceAndRestartHome() }

        findViewById<Button>(R.id.refresh_log).setOnClickListener { logView.text = DebugLog.dump() }
        findViewById<Button>(R.id.clear_log).setOnClickListener { DebugLog.clear(); logView.text = DebugLog.dump() }
        findViewById<Button>(R.id.copy_log).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("TaskbarBridge log", status.text.toString() + "\n\n" + DebugLog.dump()))
            toast("Log copied")
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun refresh() {
        val target = Prefs.target(this)
        val targetLabel = target?.let { labelFor(it.packageName) } ?: "not chosen"
        val defaultHome = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        val homeOk = defaultHome == RedirectService.SAMSUNG_HOME
        val shizuku = when {
            ShizukuShell.hasPermission() -> "connected"
            ShizukuShell.isRunning() -> "running, not authorized"
            else -> "not running"
        }
        status.text = buildString {
            appendLine("${mark(target != null)} Launcher: $targetLabel")
            appendLine("${mark(homeOk)} Default home: ${defaultHome ?: "none (ask every time)"}" +
                if (homeOk) "" else "  ← must be One UI Home")
            appendLine("${mark(isServiceEnabled())} Redirect service: " +
                if (isServiceEnabled()) "on" else "off")
            appendLine("${mark(Prefs.enabled(this@MainActivity))} Redirect: " +
                if (Prefs.enabled(this@MainActivity)) "enabled" else "paused")
            append("• Shizuku: $shizuku")
        }
        logView.text = DebugLog.dump()
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "✗"

    private fun isServiceEnabled(): Boolean {
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { ComponentName.unflattenFromString(it) == serviceComponent }
    }

    private fun labelFor(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun pickLauncher() {
        val homes = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo }
            .filter { it.packageName != RedirectService.SAMSUNG_HOME && it.packageName != packageName }
            // Skip system fallback/setup home screens.
            .filter { it.packageName != "com.android.settings" && !it.packageName.contains("setupwizard") }

        if (homes.isEmpty()) {
            toast("No third-party launchers found. Install one first (Nova, Niagara, Lawnchair…).")
            return
        }
        val labels = homes.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Send home to…")
            .setItems(labels) { _, i ->
                Prefs.setTarget(this, ComponentName(homes[i].packageName, homes[i].name))
                refresh()
            }
            .show()
    }

    private fun connectShizuku() {
        when {
            !ShizukuShell.isRunning() -> toast("Start Shizuku first, then try again.")
            ShizukuShell.hasPermission() -> toast("Already connected")
            else -> ShizukuShell.requestPermission()
        }
    }

    private fun autoEnable() {
        if (!ShizukuShell.hasPermission()) {
            toast("Connect Shizuku first")
            return
        }
        val me = serviceComponent.flattenToString()
        // Append our service to whatever is already enabled, without clobbering other services.
        val cmd = """
            pm grant $packageName android.permission.WRITE_SECURE_SETTINGS
            cur=${'$'}(settings get secure enabled_accessibility_services)
            case "${'$'}cur" in
              *"$me"*) ;;
              null|"") settings put secure enabled_accessibility_services "$me" ;;
              *) settings put secure enabled_accessibility_services "${'$'}cur:$me" ;;
            esac
            settings put secure accessibility_enabled 1
            settings get secure enabled_accessibility_services
        """.trimIndent()
        ShizukuShell.exec(cmd) { out ->
            DebugLog.add("auto-enable:\n$out")
            refresh()
            toast(if (isServiceEnabled()) "Redirect service on" else "Couldn't enable — see log")
        }
    }

    private fun probe() {
        if (!ShizukuShell.hasPermission()) {
            toast("Connect Shizuku first")
            return
        }
        val cmd = """
            echo "One UI: ${'$'}(getprop ro.build.version.oneui)  SDK: ${'$'}(getprop ro.build.version.sdk)  Model: ${'$'}(getprop ro.product.model)"
            dumpsys package ${RedirectService.SAMSUNG_HOME} | grep -m1 versionName
            for ns in system secure global; do
              settings list ${'$'}ns | grep -i -E 'task_?bar|taskbar|task_bar|navigation_bar|recent' | sed "s/^/${'$'}ns: /"
            done
        """.trimIndent()
        ShizukuShell.exec(cmd) { out ->
            DebugLog.add("probe:\n$out")
            logView.text = DebugLog.dump()
        }
    }

    private fun samsungHomeComponent(): ComponentName? {
        Prefs.samsungHome(this)?.let { return it }
        val info = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo }.firstOrNull { it.packageName == RedirectService.SAMSUNG_HOME } ?: return null
        return ComponentName(info.packageName, info.name).also { Prefs.setSamsungHome(this, it) }
    }

    private fun tryNativeMode() {
        if (!ShizukuShell.hasPermission()) return toast("Connect Shizuku first")
        val target = Prefs.target(this) ?: return toast("Choose your launcher first")
        if (samsungHomeComponent() == null) return toast("Couldn't find One UI Home")
        // Pause the redirect so it doesn't fight the experiment.
        Prefs.setEnabled(this, false)
        findViewById<SwitchCompat>(R.id.enabled).isChecked = false
        val cmd = """
            show() { dumpsys settings 2>/dev/null | grep -E "name:(sem_task_bar_available|task_bar|settings_task_bar_enabled) " | sed -E 's/^ *//'; }
            cmd package set-home-activity --user 0 ${target.flattenToString()}
            cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
            sleep 2
            echo "--- who last wrote the taskbar flags (after switching home):"
            show
            settings put global task_bar 1
            settings put system settings_task_bar_enabled 1
            settings put global sem_task_bar_available 1
            # Nudge One UI Home to re-read the flag.
            settings put global task_bar 0; sleep 0.3; settings put global task_bar 1
            echo "--- forcing sem_task_bar_available=1 for 30s (open an app now)"
            i=0
            while [ ${'$'}i -lt 30 ]; do
              v=${'$'}(settings get global sem_task_bar_available)
              if [ "${'$'}v" != "1" ]; then
                echo "t=${'$'}{i}s reset to ${'$'}v -> forcing 1"
                settings put global sem_task_bar_available 1
              fi
              sleep 1
              i=${'$'}((i+1))
            done
            echo "--- final:"
            show
        """.trimIndent()
        toast("Native test running 30s: open an app and pull up the taskbar")
        ShizukuShell.exec(cmd) { out ->
            DebugLog.add("native mode:\n$out")
            refresh()
            toast("Native test finished. Copy the log.")
        }
    }

    /**
     * SystemUI writes sem_task_bar_available=0 when the default home changes, and One UI Home
     * probably only reads it when its taskbar starts up. Force the flag, then restart One UI Home
     * (SystemUI rebinds its Recents/taskbar service right away) so it re-reads the flag.
     */
    private fun forceAndRestartHome() {
        if (!ShizukuShell.hasPermission()) return toast("Connect Shizuku first")
        val cmd = """
            show() { dumpsys settings 2>/dev/null | grep -E "name:sem_task_bar_available " | sed -E 's/^ *//'; }
            echo "default home: ${'$'}(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
            settings put global sem_task_bar_available 1
            settings put global task_bar 1
            am force-stop ${RedirectService.SAMSUNG_HOME}
            echo "--- One UI Home restarted; watching the flag for 20s (open an app now)"
            i=0
            while [ ${'$'}i -lt 20 ]; do
              v=${'$'}(settings get global sem_task_bar_available)
              if [ "${'$'}v" != "1" ]; then
                echo "t=${'$'}{i}s reset to ${'$'}v -> forcing 1"
                settings put global sem_task_bar_available 1
              fi
              sleep 1
              i=${'$'}((i+1))
            done
            echo "One UI Home running: ${'$'}(pidof ${RedirectService.SAMSUNG_HOME} >/dev/null && echo yes || echo no)"
            show
        """.trimIndent()
        toast("Restarting One UI Home: open an app and pull up the taskbar")
        ShizukuShell.exec(cmd) { out ->
            DebugLog.add("force + restart:\n$out")
            refresh()
            toast("Test finished. Copy the log.")
        }
    }

    private fun restoreSamsungHome() {
        if (!ShizukuShell.hasPermission()) return toast("Connect Shizuku first")
        val home = samsungHomeComponent() ?: return toast("Couldn't find One UI Home")
        val cmd = """
            cmd package set-home-activity --user 0 ${home.flattenToString()}
            cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
        """.trimIndent()
        ShizukuShell.exec(cmd) { out ->
            DebugLog.add("restore:\n$out")
            Prefs.setEnabled(this, true)
            findViewById<SwitchCompat>(R.id.enabled).isChecked = true
            refresh()
            toast("One UI Home restored, redirect back on")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
