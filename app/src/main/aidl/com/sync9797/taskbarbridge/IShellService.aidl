package com.sync9797.taskbarbridge;

import com.sync9797.taskbarbridge.ITaskWatcher;

interface IShellService {
    // Reserved transaction code Shizuku uses to tear down user services.
    void destroy() = 16777114;

    // Runs a shell command as the Shizuku (shell) user and returns stdout + stderr.
    String exec(String command) = 1;

    // Watches the window manager's task stack and reports the top task to [watcher].
    // Returns a short description of the mode it ended up in ("listener" or "poke-only"),
    // or a string starting with "error" if it couldn't start at all.
    String startTaskWatch(ITaskWatcher watcher) = 2;

    void stopTaskWatch() = 3;

    // Asks the watcher to re-check the task stack now (used when the listener isn't available).
    void pokeTaskWatch() = 4;
}
