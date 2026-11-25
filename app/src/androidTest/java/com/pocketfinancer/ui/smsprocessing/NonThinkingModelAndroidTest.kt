package com.pocketfinancer.ui.smsprocessing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketfinancer.inference.DefaultDirectCandidateSelector
import com.pocketfinancer.inference.DefaultSlmModelStorage
import com.pocketfinancer.inference.DirectCandidateSelectorRequest
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmRuntimeCoordinator
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.inference.SlmTokenCallback
import com.pocketfinancer.inference.withLease
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NonThinkingModelAndroidTest {
    @Test
    fun installedThinkingCapableModelProducesDirectJsonWithoutThoughtStream() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = DefaultSlmModelStorage(context)
        val modelFile = storage.modelFile("Qwen3-0.6B-Q8_0.gguf")
        assumeTrue(modelFile.isFile && modelFile.length() > 0L)
        val runtime = SlmRuntimeCoordinator(context, storage)
        val model = SlmModelSpec(
            modelId = "qwen3-0.6b",
            modelPath = modelFile.absolutePath,
            contextSize = 3072
        )
        val streamed = StringBuilder()
        val result = runtime.withLease(SlmRuntimeOwner.SETTINGS_TEST, model) { lease ->
            DefaultDirectCandidateSelector().select(
                lease,
                DirectCandidateSelectorRequest(
                    prompt = "Classify the synthetic text. Return exactly one JSON object: " +
                        "{\"decision\":\"none\"}. No explanation.",
                    candidatePayloadJson = "{\"message\":\"Hello from a synthetic test.\"}",
                    grammar = null,
                    jsonCallback = SlmTokenCallback { streamed.append(it) }
                )
            )
        }

        assertTrue(result.rawOutput?.trimStart()?.startsWith("{") == true)
        assertFalse(streamed.toString().contains("<think", ignoreCase = true))
        assertFalse(streamed.toString().contains("reasoning", ignoreCase = true))
    }
}
