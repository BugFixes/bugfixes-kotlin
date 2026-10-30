package es.bugfix.client

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The device and build every report carries. */
internal data class Device(
    val app: String,
    val osVersion: String,
    val model: String,
    val appVersion: String,
    val build: String,
)

/** Builds `/v1/bug` and `/v1/log` bodies (orchestrator's `readCrashPayload` / `readLogPayload`). */
internal class Payloads(
    private val device: Device,
    private val release: String,
    private val environment: String,
) {
    fun crash(t: Throwable, level: String, thread: String? = null): String {
        val frame = appFrame(t)
        return base(level)
            .put("file", frame?.fileName ?: frame?.className ?: t.javaClass.name)
            .put("line", (frame?.lineNumber ?: 0).toString())
            .put("line_number", frame?.lineNumber ?: 0)
            .put("raw", t.stackTraceToString().take(MAX_TRACE))
            .put("bug", t.toString().take(MAX_MESSAGE))
            .put("message", (t.message ?: t.toString()).take(MAX_MESSAGE))
            .put("errorName", t.javaClass.name)
            .apply { thread?.let { put("thread", it) } }
            .toString()
    }

    /** An ANR or native crash the system recorded after the process died. */
    fun exit(reason: String, description: String, trace: String?): String {
        val frame = trace?.let { appFrame(it) }
        return base("crash")
            .put("file", frame?.first ?: reason)
            .put("line", (frame?.second ?: 0).toString())
            .put("line_number", frame?.second ?: 0)
            .put("raw", (trace ?: description).take(MAX_TRACE))
            .put("bug", "$reason: $description".take(MAX_MESSAGE))
            .put("message", description.take(MAX_MESSAGE))
            .put("errorName", reason)
            .put("exit_reason", reason)
            .toString()
    }

    fun log(level: Level, message: String, t: Throwable?): String =
        base(level.wire)
            .put("file", t?.let { appFrame(it) }?.fileName ?: device.app)
            .put("log", message.take(MAX_MESSAGE))
            .apply { t?.let { put("stack", it.stackTraceToString().take(MAX_TRACE)) } }
            .toString()

    private fun base(level: String) = JSONObject()
        .put("runtime", "android")
        .put("level", level)
        .put("timestamp", iso.get()!!.format(Date()))
        .put("release", release)
        .put("environment", environment)
        .put("os", "Android")
        .put("os_version", device.osVersion)
        .put("device_model", device.model)
        .put("app_version", device.appVersion)
        .put("build", device.build)

    /** The first frame in the app's own package, through the cause chain; else the first frame. */
    private fun appFrame(t: Throwable): StackTraceElement? {
        val frames = generateSequence(t) { it.cause }.take(10).flatMap { it.stackTrace.asSequence() }
        return frames.firstOrNull { it.className.startsWith(device.app) } ?: t.stackTrace.firstOrNull()
    }

    /** `at com.example.Foo.bar(Foo.kt:42)` in an ANR trace: file and line of the first app frame. */
    private fun appFrame(trace: String): Pair<String, Int>? =
        frameLine.findAll(trace).firstOrNull { it.groupValues[1].startsWith(device.app) }
            ?.let { it.groupValues[2] to it.groupValues[3].toInt() }

    companion object {
        // the server takes up to 1 MiB; JSON escaping can roughly double a trace
        const val MAX_TRACE = 256 * 1024
        const val MAX_MESSAGE = 4 * 1024
        private val frameLine = Regex("""at ([\w$.]+)\(([^:()]+):(\d+)\)""")
        private val iso = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
    }
}
