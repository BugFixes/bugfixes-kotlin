package es.bugfix.client

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** A local server that records requests and answers with [status]. */
class TestServer(var status: Int = 201, private val location: String? = null) : AutoCloseable {
    data class Request(val path: String, val headers: Map<String, String>, val body: String)

    val requests: MutableList<Request> = java.util.Collections.synchronizedList(mutableListOf())
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += Request(
                    request.path.orEmpty(),
                    request.headers.associate { (k, v) -> k.lowercase() to v },
                    request.body.readUtf8(),
                )
                return MockResponse().setResponseCode(status).apply { location?.let { addHeader("Location", it) } }
            }
        }
        start()
    }

    val url = "http://127.0.0.1:${server.port}"

    override fun close() = server.shutdown()
}
