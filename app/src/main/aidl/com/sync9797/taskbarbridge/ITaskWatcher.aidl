package com.sync9797.taskbarbridge;

// Called from the Shizuku (shell) process into the app.
oneway interface ITaskWatcher {
    // Top task on [displayId] changed or its visibility settled.
    // [topComponent] is the flattened ComponentName of the top activity (may be empty).
    // [othersVisible] is a comma-separated list of packages of other tasks still visible on that display.
    void onTopTask(int taskId, int displayId, String topComponent, String othersVisible);

    void onLog(String msg);
}
