package me.rerere.rikkahub.data.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.BuildConfig
import rikka.shizuku.Shizuku
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shares one lazily bound UserService across assistants. Commands are never retried here. */
class ShizukuShellManager(context: Context) : ShizukuShellBackend {
    private val handler = Handler(Looper.getMainLooper())
    private val mutableStatus = MutableStateFlow(ShizukuStatus())
    val status = mutableStatus.asStateFlow()
    private val bindMutex = Mutex()
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuShellService::class.java))
        .daemon(false)
        .tag("rikkahub-phone-shell")
        .processNameSuffix("phone_shell")
        .version(BuildConfig.VERSION_CODE.toInt())
        .debuggable(BuildConfig.DEBUG)

    // Connection and request state is confined to the main thread.
    private var connection: ServiceConnection? = null
    private var service: IShizukuShellService? = null
    private var serviceDeath: IBinder.DeathRecipient? = null
    private var binding: CompletableDeferred<IShizukuShellService>? = null
    private val pending = mutableMapOf<String, CancellableContinuation<ShizukuShellResult>>()

    init {
        Shizuku.addBinderReceivedListenerSticky({ refreshStatus() }, handler)
        Shizuku.addBinderDeadListener({
            disconnect("Shizuku stopped. Start it again before running another command.")
            refreshStatus()
        }, handler)
        Shizuku.addRequestPermissionResultListener({ _, _ -> refreshStatus() }, handler)
        refreshStatus()
    }

    override fun refreshStatus(): ShizukuStatus {
        val updated = try {
            if (!Shizuku.pingBinder()) ShizukuStatus()
            else if (Shizuku.isPreV11() || Shizuku.getVersion() < 12) {
                ShizukuStatus(available = true, message = "Update Shizuku: backend API v12 or newer is required.")
            } else {
                val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                val denied = !granted && Shizuku.shouldShowRequestPermissionRationale()
                val uid = if (granted) Shizuku.getUid() else null
                ShizukuStatus(
                    available = true,
                    supported = true,
                    permissionGranted = granted,
                    permissionDeniedPermanently = denied,
                    uid = uid,
                    privilege = uid?.let(::privilegeName),
                    message = when {
                        granted -> "Ready: ${if (uid == 0) "root" else if (uid == 2000) "ADB shell" else "UID $uid"} access"
                        denied -> "Permission denied. Allow RikkaHub in Shizuku's authorized applications."
                        else -> "Grant Shizuku permission to run device commands."
                    },
                )
            }
        } catch (_: RuntimeException) {
            ShizukuStatus()
        }
        mutableStatus.value = updated
        if (!updated.permissionGranted) handler.post {
            // Recheck current state: a permission response may have arrived since this refresh.
            if (!mutableStatus.value.permissionGranted) disconnect(updated.message)
        }
        return updated
    }

    fun requestPermission() {
        val current = refreshStatus()
        if (!current.supported || current.permissionGranted || current.permissionDeniedPermanently) return
        try {
            Shizuku.requestPermission(7301)
        } catch (_: RuntimeException) {
            mutableStatus.value = ShizukuStatus(message = "Could not request permission. Check that Shizuku is running.")
        }
    }

    override suspend fun execute(request: ShizukuShellRequest): ShizukuShellResult = withContext(Dispatchers.Main.immediate) {
        checkReady()
        val remote = connect()
        checkReady()
        val requestId = UUID.randomUUID().toString()
        // The service enforces the command deadline. This also bounds a lost callback.
        withTimeoutOrNull((request.timeoutSeconds + 10) * 1000) {
            suspendCancellableCoroutine { continuation ->
                pending[requestId] = continuation
                continuation.invokeOnCancellation {
                    handler.post {
                        pending.remove(requestId)
                        runCatching { remote.cancel(requestId) }
                    }
                }
                val callback = object : IShizukuShellCallback.Stub() {
                    override fun onResult(result: Bundle) {
                        val decoded = ShizukuShellResult(
                            stdout = result.getString("stdout").orEmpty(),
                            stderr = result.getString("stderr").orEmpty(),
                            exitCode = result.getInt("exitCode", -1),
                            timedOut = result.getBoolean("timedOut"),
                            truncated = result.getBoolean("truncated"),
                            uid = result.getInt("uid"),
                            error = result.getString("error"),
                            message = result.getString("message"),
                        )
                        handler.post { pending.remove(requestId)?.resume(decoded) }
                    }
                }
                try {
                    remote.execute(requestId, request.command, request.cwd, request.timeoutSeconds * 1000, callback)
                } catch (_: Exception) {
                    disconnect("Device shell connection was lost. The command may have started; it was not retried.")
                }
            }
        } ?: throw ShizukuShellException("RESULT_TIMEOUT", "No result received from device shell. Cancellation was requested; the command was not retried.")
    }

    private fun checkReady() {
        val current = refreshStatus()
        val code = when {
            !current.available -> "UNAVAILABLE"
            !current.supported -> "UNSUPPORTED"
            !current.permissionGranted -> "NO_PERMISSION"
            else -> return
        }
        throw ShizukuShellException(code, current.message)
    }

    private suspend fun connect(): IShizukuShellService = bindMutex.withLock {
        service?.let { return@withLock it }
        val ready = CompletableDeferred<IShizukuShellService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                handler.post {
                    if (connection !== this) return@post
                    try {
                        val death = IBinder.DeathRecipient {
                            handler.post {
                                if (connection === this) disconnect("Device shell service stopped. The command was not retried.")
                            }
                        }
                        binder.linkToDeath(death, 0)
                        serviceDeath = death
                        val remote = IShizukuShellService.Stub.asInterface(binder)
                        service = remote
                        ready.complete(remote)
                    } catch (e: Exception) {
                        ready.completeExceptionally(ShizukuShellException("SERVICE_LOST", "Device shell service stopped during connection."))
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                handler.post {
                    if (connection === this) disconnect("Device shell service stopped. The command was not retried.")
                }
            }
        }
        connection = conn
        binding = ready
        try {
            Shizuku.bindUserService(args, conn)
            withTimeoutOrNull(10_000) { ready.await() }
                ?: throw ShizukuShellException("CONNECTION_TIMEOUT", "Device shell did not connect within 10 seconds. Check Shizuku and try again.")
        } catch (e: Exception) {
            disconnect("Could not connect to device shell.")
            if (e is kotlinx.coroutines.CancellationException || e is ShizukuShellException) throw e
            throw ShizukuShellException("CONNECTION_FAILED", "Could not connect to device shell. Check Shizuku permission and try again.")
        } finally {
            binding = null
        }
    }

    private fun disconnect(message: String) {
        val remote = service
        val conn = connection
        connection = null
        service = null
        serviceDeath?.let { death -> runCatching { remote?.asBinder()?.unlinkToDeath(death, 0) } }
        serviceDeath = null
        binding?.completeExceptionally(ShizukuShellException("SERVICE_LOST", message))
        val requests = pending.toMap()
        pending.clear()
        requests.forEach { (id, continuation) ->
            runCatching { remote?.cancel(id) }
            continuation.resumeWithException(ShizukuShellException("SERVICE_LOST", message))
        }
        if (conn != null) runCatching { Shizuku.unbindUserService(args, conn, true) }
    }
}
