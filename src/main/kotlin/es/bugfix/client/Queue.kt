package es.bugfix.client

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reports waiting to be sent, one file each, so a crash written just before the process dies
 * is still sent on the next launch. Oldest first; past [cap] the oldest are dropped.
 */
internal class Queue(private val dir: File, private val cap: Int = 50) {
    private val seq = AtomicInteger()

    /** [endpoint] is "bug" or "log"; it's the file's extension. */
    @Synchronized
    fun add(endpoint: String, body: String) {
        dir.mkdirs()
        items().dropLast(cap - 1).forEach { it.delete() }
        val name = "%013d-%06d.%s".format(System.currentTimeMillis(), seq.incrementAndGet() % 1_000_000, endpoint)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(body)
        tmp.renameTo(File(dir, name))
    }

    @Synchronized
    fun items(): List<File> =
        dir.listFiles { f -> f.extension == "bug" || f.extension == "log" }.orEmpty().sortedBy { it.name }
}
