package com.pocketfinancer.inference

import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LlamaEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var llamaEngine: LlamaEngine

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockContext = mockk()
        every { mockContext.filesDir } returns tempFolder.newFolder("filesDir")

        llamaEngine = LlamaEngine(mockContext)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `computeSha256 hashes empty, ASCII, and UTF-8 inputs deterministically`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            llamaEngine.computeSha256("")
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            llamaEngine.computeSha256("hello")
        )
        val unicode = "pocket-financer-₹-💰"
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(unicode.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, llamaEngine.computeSha256(unicode))
    }

    @Test
    fun `SlmPerformanceData computes tokensPerSecond safely across positive, zero, and negative times`() {
        assertEquals(15.0, SlmPerformanceData(100, 200, 1000, 15).tokensPerSecond, 0.001)
        assertEquals(0.0, SlmPerformanceData(100, 200, 0, 15).tokensPerSecond, 0.001)
        assertEquals(0.0, SlmPerformanceData(100, 200, -500, 15).tokensPerSecond, 0.001)
    }

    @Test
    fun `model storage directory and session file naming follow contract`() {
        assertEquals("qwen3-1.7b-Q8_0.gguf", LlamaEngine.MODEL_FILENAME)

        val storageDir = llamaEngine.getModelStorageDir()
        assertTrue(storageDir.exists())
        assertEquals("models", storageDir.name)

        val sessionFile = llamaEngine.getSessionFile("abcd1234")
        val expectedName = "session_qwen3-1_7b-Q8_0_gguf_abcd1234.bin"
        assertEquals(expectedName, sessionFile.name)
        assertEquals(File(storageDir, expectedName).absolutePath, sessionFile.absolutePath)
    }

    @Test
    fun `deleteStaleSessions removes stale sessions while preserving active and non-session files`() {
        val storageDir = llamaEngine.getModelStorageDir()
        val prefix = "session_qwen3-1_7b-Q8_0_gguf_"

        // Empty directory is a safe no-op.
        llamaEngine.deleteStaleSessions("activehash")

        val stale = File(storageDir, "${prefix}stale.bin").apply { createNewFile() }
        val active = File(storageDir, "${prefix}activehash.bin").apply { createNewFile() }
        val other = File(storageDir, "other_file.txt").apply { createNewFile() }

        llamaEngine.deleteStaleSessions("activehash")

        assertFalse(stale.exists())
        assertTrue(active.exists())
        assertTrue(other.exists())

        // Passing null clears all session files but leaves non-session files intact.
        llamaEngine.deleteStaleSessions(null)

        assertFalse(active.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `SlmModelStorage sessionNamespace changes with artifact revision and context size`() {
        val storage = DefaultSlmModelStorage(mockContext)
        val base = SlmModelSpec(
            modelId = "model",
            modelPath = File(storage.modelDirectory, "model.gguf").absolutePath,
            artifactRevision = "r1",
            contextSize = 3072
        )
        val newRevision = SlmModelSpec(
            modelId = "model",
            modelPath = File(storage.modelDirectory, "model.gguf").absolutePath,
            artifactRevision = "r2",
            contextSize = 3072
        )
        val newContext = SlmModelSpec(
            modelId = "model",
            modelPath = File(storage.modelDirectory, "model.gguf").absolutePath,
            artifactRevision = "r1",
            contextSize = 4096
        )

        assertNotEquals(storage.sessionNamespace(base), storage.sessionNamespace(newRevision))
        assertNotEquals(storage.sessionNamespace(base), storage.sessionNamespace(newContext))
    }
}
