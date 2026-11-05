package com.pim.app.notes

import android.util.AtomicFile
import com.pim.app.TestPimApp
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteMaterialIoAtomicWriterTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun overwritePublishesLatestExactBytesToFreshReaders() {
        val file = File(temp.newFolder("material"), "checkpoint.json")
        val first = """{"revision":1,"body":"${"甲".repeat(16 * 1024)}"}"""
        val latest = """{"revision":2,"body":"${"乙".repeat(24 * 1024)}"}"""

        writeMaterialFile(file) { it.write(first.toByteArray(Charsets.UTF_8)) }
        writeVerifiedMaterialJson(file, latest)

        assertArrayEquals(latest.toByteArray(Charsets.UTF_8), AtomicFile(file).readFully())
        val pending = file.parentFile!!.listFiles().orEmpty().filter { it.name.endsWith(".pending") }
        assertTrue("Pending writer files must not survive a confirmed write", pending.isEmpty())
    }

    @Test fun failedWriteKeepsPreviousConfirmedBytesReadable() {
        val file = File(temp.newFolder("material"), "checkpoint.json")
        val previous = """{"revision":7,"body":"previous checkpoint"}"""
        writeMaterialFile(file) { it.write(previous.toByteArray(Charsets.UTF_8)) }

        try {
            writeMaterialFile(file) {
                it.write("partial replacement".toByteArray(Charsets.UTF_8))
                error("simulated writer failure")
            }
            fail("Writer failure must escape")
        } catch (_: IllegalStateException) {
        }

        assertArrayEquals(previous.toByteArray(Charsets.UTF_8), AtomicFile(file).readFully())
        assertFalse(File(file.path + ".pending").exists())
    }
}
