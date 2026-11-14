package com.sync9797.taskbarbridge;

interface IShellService {
    // Reserved transaction code Shizuku uses to tear down user services.
    void destroy() = 16777114;

    // Runs a shell command as the Shizuku (shell) user and returns stdout + stderr.
    String exec(String command) = 1;
}
