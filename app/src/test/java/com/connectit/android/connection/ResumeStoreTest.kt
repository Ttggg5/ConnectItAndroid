package com.connectit.android.connection

import com.connectit.android.net.FileControlMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResumeStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun saveFindRemove_persistAcrossInstances() {
        val file = File(temp.root, "resume.json")
        val store = ResumeStore(file)
        val entry = ResumeEntry("peer", "key", "content://downloads/1", "Download/ConnectIt/a.bin", System.currentTimeMillis())
        store.save(entry)

        assertEquals(entry, ResumeStore(file).find("peer", "key"))
        assertNull(store.find("other-peer", "key"))
        assertNull(store.find(null, "key"))

        store.remove("peer", "key")
        assertNull(ResumeStore(file).find("peer", "key"))
    }

    @Test
    fun expiredEntries_areNotOffered() {
        val store = ResumeStore(File(temp.root, "resume.json"))
        store.save(ResumeEntry("peer", "old", "path:/x", "x", System.currentTimeMillis() - ResumeStore.MAX_AGE_MS - 1))

        assertNull(store.find("peer", "old"))
    }

    @Test
    fun resumeKeyHash_isStableAndShort() {
        val a = ResumeKeys.hash("file|content://x|10|5")
        assertEquals(a, ResumeKeys.hash("file|content://x|10|5"))
        assertNotEquals(a, ResumeKeys.hash("file|content://x|10|6"))
        assertEquals(32, a.length)
    }

    @Test
    fun fileControlMessage_carriesResumeKeyAndOffset() {
        val offer = FileControlMessage(type = "file-offer", transferId = "t", resumeKey = "k")
        val accept = FileControlMessage(type = "file-accept", transferId = "t", offset = 1234L)

        assertEquals(offer, FileControlMessage.parse(offer.toJson()))
        assertEquals(accept, FileControlMessage.parse(accept.toJson()))
        assertNull(FileControlMessage.parse("""{"Type":"file-accept","TransferId":"t"}""".toByteArray())!!.offset)
    }
}
