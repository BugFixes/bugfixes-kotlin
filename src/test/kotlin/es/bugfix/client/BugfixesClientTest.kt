package es.bugfix.client

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BugfixesClientTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir get() = File(context.noBackupFilesDir, "bugfixes")
    private lateinit var server: TestServer
    private val original = Thread.getDefaultUncaughtExceptionHandler()

    @Before
    fun setUp() {
        BugfixesClient.resetForTests()
        dir.deleteRecursively()
        server = TestServer()
    }

    @After
    fun tearDown() {
        server.close()
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    private fun client(key: String = "bfm_test", url: String = "${server.url}/v1", level: Level = Level.ERROR) =
        BugfixesClient.builder().key(key).server(url).environment("debug").level(level)
            .apply { executor = java.util.concurrent.Executor { it.run() } }
            .build(context)

    private fun body(i: Int = 0) = JSONObject(server.requests[i].body)

    @Test
    fun withoutAValidKeyOrServerItInstallsNothingAndSendsNothing() {
        val marker = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(marker)
        for (c in listOf(client(key = ""), client(key = "an-agent-secret"), client(url = "http://api.bugfix.es/v1"))) {
            c.report(RuntimeException("boom"))
            c.log(Level.ERROR, "boom")
        }
        assertSame(marker, Thread.getDefaultUncaughtExceptionHandler())
        assertTrue(server.requests.isEmpty())
        assertFalse(dir.exists())
    }

    @Test
    fun reportSendsAnAndroidCrashPayload() {
        val e = IllegalStateException("bad state").apply {
            stackTrace = arrayOf(
                StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 10),
                StackTraceElement("${context.packageName}.ViewModel", "load", "ViewModel.kt", 42),
            )
        }
        client().report(e)

        val req = server.requests.single()
        assertEquals("/v1/bug", req.path)
        assertEquals("Bearer bfm_test", req.headers["authorization"])
        assertEquals(context.packageName, req.headers["x-bugfixes-app"])
        val body = body()
        assertEquals("android", body.getString("runtime"))
        assertEquals("error", body.getString("level"))
        assertEquals("ViewModel.kt", body.getString("file"))
        assertEquals("42", body.getString("line"))
        assertEquals("java.lang.IllegalStateException", body.getString("errorName"))
        assertEquals("bad state", body.getString("message"))
        assertTrue(body.getString("raw").contains("ViewModel.load"))
        assertEquals("debug", body.getString("environment"))
        assertTrue(body.getString("os_version").contains("API 35"))
        for (field in listOf("timestamp", "release", "os", "device_model", "app_version", "build")) assertTrue(field, body.has(field))
    }

    @Test
    fun logsBelowTheLevelAreNotSent() {
        val c = client()
        c.log(Level.INFO, "fine")
        c.log(Level.WARN, "meh")
        assertTrue(server.requests.isEmpty())
        c.log(Level.ERROR, "payment failed")
        assertEquals("/v1/log", server.requests.single().path)
        assertEquals("payment failed", body().getString("log"))
        assertEquals("error", body().getString("level"))

        client(level = Level.INFO).log(Level.INFO, "now sent")
        assertEquals(2, server.requests.size)
    }

    @Test
    fun crashHandlerWritesTheCrashAndChainsToThePreviousHandler() {
        var previousSaw: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> previousSaw = e }
        server.status = 503 // offline: the crash stays queued
        client()

        val crash = RuntimeException("fatal")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)

        assertSame(crash, previousSaw)
        val queued = File(dir, "queue").listFiles()!!.single()
        assertEquals("crash", JSONObject(queued.readText()).getString("level"))

        // next launch: it's sent and removed
        server.status = 201
        server.requests.clear()
        BugfixesClient.resetForTests()
        client()
        assertEquals("crash", body().getString("level"))
        assertTrue(File(dir, "queue").listFiles()!!.isEmpty())
    }

    @Test
    fun aSecondClientDoesNotInstallASecondHandler() {
        client()
        val first = Thread.getDefaultUncaughtExceptionHandler()
        client()
        assertSame(first, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun failedSendsStayQueuedUntilTheServerIsBack() {
        server.status = 500
        val c = client()
        c.report(RuntimeException("one"))
        c.report(RuntimeException("two"))
        assertEquals(2, File(dir, "queue").listFiles()!!.size)
        server.status = 201
        c.report(RuntimeException("three"))
        assertEquals(listOf("one", "two", "three"), server.requests.takeLast(3).map { JSONObject(it.body).getString("message") })
        assertTrue(File(dir, "queue").listFiles()!!.isEmpty())
    }

    @Test
    fun anrsAndNativeCrashesAreReportedOnceAndNotFromBeforeTheSdk() {
        val am = shadowOf(context.getSystemService(ActivityManager::class.java))
        fun exit(reason: Int, at: Long, description: String, trace: String? = null) = am.addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder().setProcessName(context.packageName).setReason(reason)
                .setTimestamp(at).setDescription(description)
                .apply { trace?.let { setTraceInputStream(it.byteInputStream()) } }.build(),
        )
        exit(ApplicationExitInfo.REASON_ANR, 1_000, "old anr")
        client() // first launch with the SDK: nothing from before it
        assertTrue(server.requests.isEmpty())

        val trace = "\"main\" prio=5\n  at java.lang.Thread.sleep(Thread.java:450)\n  at ${context.packageName}.MainActivity.onClick(MainActivity.kt:31)\n"
        exit(ApplicationExitInfo.REASON_ANR, 2_000, "input dispatching timed out", trace)
        exit(ApplicationExitInfo.REASON_CRASH_NATIVE, 3_000, "SIGSEGV")
        exit(ApplicationExitInfo.REASON_USER_REQUESTED, 4_000, "swiped away")
        client()
        assertEquals(listOf("ANR", "native crash"), server.requests.map { JSONObject(it.body).getString("exit_reason") })
        assertEquals("input dispatching timed out", body(0).getString("message"))
        assertEquals("MainActivity.kt", body(0).getString("file"))
        assertEquals(31, body(0).getInt("line_number"))
        assertTrue(body(0).getString("raw").contains("MainActivity.onClick"))
        assertEquals("crash", body(1).getString("level"))

        client() // already reported
        assertEquals(2, server.requests.size)
    }
}
