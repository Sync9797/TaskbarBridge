# TaskbarBridge

A bridge allowing you to use Samsung's taskbar with a third-party launcher.

## How it works

Samsung's taskbar on foldables and tablets is drawn by **One UI Home**. The moment another launcher becomes the default home app, One UI disables it.

TaskbarBridge flips the problem around:

1. **One UI Home stays the default home app**, so the taskbar, the swipe-up peek and Recents keep working exactly as Samsung built them.
2. An accessibility service watches for the One UI home screen coming to the front and **immediately opens the launcher you picked**.
3. It tries to tell the home screen apart from Recents, so the Recents screen isn't redirected.

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

## Roadmap

- Shizuku-powered foreground detection (task-stack listener) for a faster, flash-free redirect
- Per-display option (main screen only / cover screen only)
- Fallbacks: Samsung's hidden taskbar setting, our own floating taskbar, LSPosed module (root)

## Building

`./gradlew assembleDebug` (JDK 17). CI builds every push to `main`.
