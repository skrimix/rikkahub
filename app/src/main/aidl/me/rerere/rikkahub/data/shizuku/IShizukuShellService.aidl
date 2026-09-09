package me.rerere.rikkahub.data.shizuku;

import me.rerere.rikkahub.data.shizuku.IShizukuShellCallback;

interface IShizukuShellService {
    oneway void execute(String requestId, String command, String cwd, long timeoutMillis,
        IShizukuShellCallback callback) = 1;
    oneway void cancel(String requestId) = 2;
    oneway void destroy() = 16777114;
}
