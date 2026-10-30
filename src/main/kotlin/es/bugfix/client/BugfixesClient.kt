package es.bugfix.client

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class Level(internal val wire: String) { DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error") }

/**
 * Sends the app's crashes, ANRs, errors and logs to Bugfixes, which groups them into reports and
 * raises tickets. Build one at app start; with no key it does nothing. It never throws into the app.
 *
 * ```
 * val bugfixes = BugfixesClient.builder()
 *     .key(BuildConfig.BUGFIXES_KEY)
 *     .environment(BuildConfig.BUILD_TYPE)
 *     .build(this)
 * ```
 */
class BugfixesClient private constructor(
    private val enabled: Boolean,
    private val minLevel: Level,
    private val payloads: Payloads?,
    private val queue: Queue?,
    private val sender: Sender?,
    private val executor: Executor,
) {
    /** Reports a caught exception as an error. */
    fun report(t: Throwable) {
        if (!enabled) return
        val body = runCatching { payloads!!.crash(t, "error", Thread.currentThread().name) }.getOrNull() ?: return
        enqueue("bug", body)
    }

    /** Sends a log line at or above the client's level (error by default). */
    @JvmOverloads
    fun log(level: Level, message: String, t: Throwable? = null) {
        if (!enabled || level < minLevel) return
        val body = runCatching { payloads!!.log(level, message, t) }.getOrNull() ?: return
        enqueue("log", body)
    }

    private fun enqueue(endpoint: String, body: String) = runCatching {
        executor.execute {
            runCatching {
                queue!!.add(endpoint, body)
                flush()
            }
        }
    }

    internal fun flush() {
        for (file in queue!!.items()) {
            val body = runCatching { file.readText() }.getOrElse { file.delete(); continue }
            when (sender!!.send(file.extension, body)) {
                Sender.Outcome.SENT, Sender.Outcome.DROP -> file.delete()
                // ponytail: no network callback; what's left goes on the next report or launch
                Sender.Outcome.RETRY -> return
            }
        }
    }

    private fun installCrashHandler() {
        if (!installed.compareAndSet(false, true)) return // a second client would report each crash twice
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // the process is dying: write it now, send it on the next launch
            runCatching { queue!!.add("bug", payloads!!.crash(e, "crash", thread.name)) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** ANRs and native crashes from previous runs (API 30+), each reported once. */
    internal fun reportExits(context: Context, marker: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.reason == ApplicationExitInfo.REASON_ANR || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
        val last = runCatching { marker.readText().trim().toLong() }.getOrNull()
        marker.parentFile?.mkdirs()
        marker.writeText(maxOf(last ?: 0, exits.maxOfOrNull { it.timestamp } ?: 0).toString())
        // first launch with the SDK only records where we are, so exits from before it aren't sent
        if (last == null) return
        for (exit in exits.filter { it.timestamp > last }.sortedBy { it.timestamp }) {
            val anr = exit.reason == ApplicationExitInfo.REASON_ANR
            // ponytail: a native crash's trace is a binary tombstone (protobuf); only its description is sent
            val trace = if (anr) runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull() else null
            queue!!.add("bug", payloads!!.exit(if (anr) "ANR" else "native crash", exit.description ?: "", trace))
        }
    }

    class Builder internal constructor() {
        private var key = ""
        private var server = DEFAULT_SERVER
        private var environment = ""
        private var release: String? = null
        private var level = Level.ERROR
        internal var executor: Executor? = null

        /** The project's public mobile key (`bfm_…`). Empty: reporting is off. */
        fun key(key: String) = apply { this.key = key.trim() }

        fun environment(environment: String) = apply { this.environment = environment }

        /** Defaults to `versionName (versionCode)`. */
        fun release(release: String) = apply { this.release = release }

        /** The lowest [log] level sent; [report] and crashes are always sent. */
        fun level(level: Level) = apply { this.level = level }

        /** Only change this for local development (`http://10.0.2.2:…` from an emulator). */
        fun server(server: String) = apply { this.server = server.trimEnd('/') }

        fun build(context: Context): BugfixesClient {
            val exec = executor ?: Executors.newSingleThreadExecutor { Thread(it, "bugfixes").apply { isDaemon = true } }
            val disabled = BugfixesClient(false, level, null, null, null, exec)
            if (key.isEmpty()) return disabled
            if (!key.startsWith(KEY_PREFIX)) return disabled.also { Log.w(TAG, "not reporting: the key must be a mobile key ($KEY_PREFIX…), never an agent secret") }
            if (!Sender.allowed(server)) return disabled.also { Log.w(TAG, "not reporting: $server must be https") }
            return runCatching {
                val app = context.applicationContext ?: context
                val device = device(app)
                val dir = File(app.noBackupFilesDir, "bugfixes")
                val client = BugfixesClient(
                    true, level,
                    Payloads(device, release ?: "${device.appVersion} (${device.build})", environment),
                    Queue(File(dir, "queue")),
                    Sender(server, key, device.app),
                    exec,
                )
                client.installCrashHandler()
                exec.execute {
                    runCatching { client.reportExits(app, File(dir, "last_exit")) }
                    runCatching { client.flush() }
                }
                client
            }.getOrElse { disabled }
        }

        @Suppress("DEPRECATION")
        private fun device(context: Context): Device {
            val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            val code = info?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong() }
            return Device(
                app = context.packageName,
                osVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                model = "${Build.MANUFACTURER} ${Build.MODEL}",
                appVersion = info?.versionName ?: "",
                build = code?.toString() ?: "",
            )
        }
    }

    companion object {
        const val DEFAULT_SERVER = "https://api.bugfix.es/v1"
        private const val KEY_PREFIX = "bfm_"
        private const val TAG = "bugfixes"
        private val installed = AtomicBoolean(false)

        @JvmStatic
        fun builder() = Builder()

        internal fun resetForTests() = installed.set(false)
    }
}
