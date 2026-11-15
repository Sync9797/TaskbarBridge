# TaskbarBridge

A bridge allowing you to use Samsung's taskbar with a third-party launcher.

## How it works

Samsung's taskbar on foldables and tablets is drawn by **One UI Home**. The moment another launcher becomes the default home app, One UI disables it.

TaskbarBridge flips the problem around:

1. **One UI Home stays the default home app**, so the taskbar, the swipe-up peek and Recents keep working exactly as Samsung built them.
2. An accessibility service watches for the One UI home screen coming to the front and **immediately opens the launcher you picked**.
3. It tells the home screen (`com.sec.android.app.launcher.Launcher`) apart from Recents (`com.android.quickstep.RecentsActivity`) by window class alone, so redirects are fast and Recents is left alone.

## Setup

1. Install the APK from the latest [Actions build](../../actions) (download the `TaskbarBridge-debug` artifact).
2. Open TaskbarBridge → **Choose my launcher**.
3. **Keep One UI Home as default** → make sure One UI Home is selected.
4. **Turn on the redirect service** (Accessibility → Installed apps → TaskbarBridge).
   - If Android blocks it as a "restricted setting": App info → ⋮ → *Allow restricted settings*, then try again.
   - Or with [Shizuku](https://shizuku.rikka.app/) running: **Connect Shizuku** → **Turn on service automatically**.
5. Set TaskbarBridge's battery usage to **Unrestricted** so One UI doesn't kill the service.

Tip: clear the One UI home screen and use the same wallpaper on both launchers to hide the brief flash during the redirect.

## Diagnostics

Turn on **Log One UI window events**, swipe home a few times and open Recents, then tap **Copy** and paste the log into an issue. That's how we tune Home vs Recents detection for each One UI version.

**Probe hidden taskbar settings** (Shizuku) lists system settings that mention the taskbar, in case Samsung left a switch that turns it on for any launcher.

## Shizuku detection (v0.8.0)

With Shizuku connected, **Detect home with Shizuku** (on by default) replaces accessibility polling with a task-stack listener running in the Shizuku shell process. It redirects only once One UI Home's task is on top *and* the app you swiped away from is no longer visible, which is exactly when the swipe-home gesture has finished. If it bounces back anyway, it relaunches your launcher (up to 2 times). If Shizuku stops, it falls back to accessibility detection on its own. The status line shows which detection is active.

## Native mode (experiment)

With Shizuku, **Try native mode** sets your launcher as the real default home app and forces Samsung's taskbar flags (`global task_bar`, `global sem_task_bar_available`, `system settings_task_bar_enabled`). If the taskbar survives, no redirect is needed. **Restore One UI Home** undoes it.

- **Rebind test** guards the taskbar flags from inside the app (reverts SystemUI's write within milliseconds), switches home, then restarts One UI Home so SystemUI rebinds its taskbar service while the flags read 1.
- **Gesture check** makes your launcher the default without touching any flags, to see whether swipe gestures still work with a third-party default home.

## Roadmap

- Per-display option (main screen only / cover screen only)
- Fallbacks: Samsung's hidden taskbar setting, our own floating taskbar, LSPosed module (root)

## Building

`./gradlew assembleDebug` (JDK 17). CI builds every push to `main`.
