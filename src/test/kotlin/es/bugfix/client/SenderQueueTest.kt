package es.bugfix.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SenderQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun onlyHttpsOrLocalServersAreAllowed() {
        assertTrue(Sender.allowed("https://api.bugfix.es/v1"))
        assertTrue(Sender.allowed("http://localhost:8080/v1"))
        assertTrue(Sender.allowed("http://10.0.2.2:80/v1"))
        assertFalse(Sender.allowed("http://api.bugfix.es/v1"))
        assertFalse(Sender.allowed("ftp://api.bugfix.es"))
        assertFalse(Sender.allowed("https://"))
        assertFalse(Sender.allowed("not a url"))
    }

    @Test
    fun sendsTheMobileKeyAndAppId() = TestServer().use { server ->
        assertEquals(Sender.Outcome.SENT, Sender("${server.url}/v1", "bfm_k", "com.example.app").send("bug", """{"a":1}"""))
        val req = server.requests.single()
        assertEquals("/v1/bug", req.path)
        assertEquals("Bearer bfm_k", req.headers["authorization"])
        assertEquals("com.example.app", req.headers["x-bugfixes-app"])
        assertEquals("""{"a":1}""", req.body)
    }

    @Test
    fun neverFollowsARedirectWithTheKey() = TestServer().use { other ->
        TestServer(302, location = "${other.url}/steal").use { server ->
            assertEquals(Sender.Outcome.DROP, Sender(server.url, "bfm_k", "a.b").send("bug", "{}"))
            assertTrue(other.requests.isEmpty())
        }
    }

    @Test
    fun retriesServerFailuresAndDropsRejectedReports() = TestServer(503).use { server ->
        val sender = Sender(server.url, "bfm_k", "a.b")
        assertEquals(Sender.Outcome.RETRY, sender.send("bug", "{}"))
        server.status = 429
        assertEquals(Sender.Outcome.RETRY, sender.send("bug", "{}"))
        server.status = 400
        assertEquals(Sender.Outcome.DROP, sender.send("bug", "{}"))
        server.status = 401
        assertEquals(Sender.Outcome.DROP, sender.send("bug", "{}"))
        // nothing listening: try again later
        assertEquals(Sender.Outcome.RETRY, Sender("http://127.0.0.1:1", "bfm_k", "a.b", timeoutMs = 500).send("bug", "{}"))
    }

    @Test
    fun queueKeepsOrderAndDropsTheOldestPastItsCap() {
        val queue = Queue(tmp.newFolder("q"), cap = 3)
        for (i in 1..5) queue.add(if (i % 2 == 0) "log" else "bug", "r$i")
        val items = queue.items()
        assertEquals(listOf("r3", "r4", "r5"), items.map { it.readText() })
        assertEquals(listOf("bug", "log", "bug"), items.map { it.extension })
    }
}
