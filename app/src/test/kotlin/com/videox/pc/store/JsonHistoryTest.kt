package com.videox.pc.store

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonHistoryTest {

    private fun tempFile(): java.io.File =
        Files.createTempFile("videox-history-test", ".json").toFile().also { it.delete() }

    private fun item(id: String, title: String = "Title $id") = HistoryItem(
        id = id, url = "https://example.com/$id", title = title,
        fileName = "$id.mp4", fileSize = 100L, status = "Completed",
        kind = "video", timestamp = id.hashCode().toLong()
    )

    @Test
    fun newestFirst() {
        val store = JsonHistory(tempFile())
        store.add(item("a"))
        store.add(item("b"))
        assertEquals(listOf("b", "a"), store.all().map { it.id })
    }

    @Test
    fun encodeDecodeRoundTripWithSpecialChars() {
        val store = JsonHistory(tempFile())
        val tricky = item("x", "A \"quoted\" title\nwith newline \\ backslash")
        val decoded = store.decode(store.encode(listOf(tricky)))
        assertEquals(1, decoded.size)
        assertEquals(tricky.title, decoded[0].title)
        assertEquals(tricky.url, decoded[0].url)
    }

    @Test
    fun persistsAcrossInstances() {
        val file = tempFile()
        JsonHistory(file).add(item("a"))
        JsonHistory(file).add(item("b"))
        assertEquals(listOf("b", "a"), JsonHistory(file).all().map { it.id })
        file.delete()
    }

    @Test
    fun corruptFileYieldsEmptyHistory() {
        val file = tempFile()
        file.writeText("{broken")
        assertTrue(JsonHistory(file).all().isEmpty())
        file.delete()
    }

    @Test
    fun cappedAt200() {
        val store = JsonHistory(tempFile())
        repeat(250) { store.add(item("id$it")) }
        val all = store.all()
        assertEquals(200, all.size)
        assertEquals("id249", all.first().id)
    }

    @Test
    fun updateRemoveClear() {
        val store = JsonHistory(tempFile())
        store.add(item("a"))
        store.add(item("b"))
        store.update(item("a").copy(status = "Failed"))
        assertEquals("Failed", store.all().first { it.id == "a" }.status)
        store.remove("b")
        assertEquals(listOf("a"), store.all().map { it.id })
        store.clear()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun emptyAndEmptyArrayDecodeToEmpty() {
        val store = JsonHistory(tempFile())
        assertTrue(store.decode("").isEmpty())
        assertTrue(store.decode("[]").isEmpty())
        assertTrue(store.decode("  ").isEmpty())
    }
}
