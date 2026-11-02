package com.pocketfinancer.pipeline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the full extraction prompt using the system prompt, few-shot examples,
 * and the ### EXAMPLES / ### YOUR TASK delimiter structure validated in the
 * SLM evaluation pipeline.
 *
 * This class is a pure string-builder — it does not depend on the JNI bridge
 * so it can be tested without a loaded model. The actual chat-template
 * rendering (Qwen3 Jinja format) is applied inside the process-wide SLM
 * runtime's serialized native operation.
 *
 * Format:
 *   SYSTEM_PROMPT
 *   \n\n### EXAMPLES (already labeled — for reference only, do NOT answer these)\n\n
 *   Sender: <sender>\nSMS: <sms>\nOutput: <answer>\n
 *   ...
 *   \n### YOUR TASK (answer this one SMS only — output JSON or null, nothing else)\n\n
 *   Sender: <sender>\nSMS: <sms>\nOutput:
 */
@Singleton
class PromptBuilder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val systemPrompt: String by lazy {
        context.assets.open("system_prompt.txt").bufferedReader().use { it.readText() }
    }

    private val fewShotExamples: JSONArray by lazy {
        val text = context.assets.open("few_shot_examples.json").bufferedReader().use { it.readText() }
        JSONArray(text)
    }

    /**
     * Get the static prompt prefix (system prompt and few-shot examples).
     */
    fun getStaticPrefix(): String {
        val sb = StringBuilder()
        sb.append(systemPrompt)
        sb.append("\n\n### EXAMPLES (already labeled — for reference only, do NOT answer these)\n\n")
        for (i in 0 until fewShotExamples.length()) {
            val ex = fewShotExamples.getJSONObject(i)
            sb.append("Sender: ${ex.getString("sender")}\n")
            sb.append("SMS: ${ex.getString("sms")}\n")
            sb.append("Output: ${ex.getString("answer")}\n\n")
        }
        sb.append("### YOUR TASK (answer this one SMS only — output JSON or null, nothing else)\n\n")
        return sb.toString()
    }

    /**
     * Build the full extraction prompt for a given SMS message.
     *
     * @param sender The SMS sender address (e.g. "AX-HDFCBK")
     * @param smsBody The raw SMS body text
     * @return Complete prompt string ready for chat template rendering
     */
    fun buildExtractionPrompt(sender: String, smsBody: String): String {
        val sb = StringBuilder()
        sb.append(getStaticPrefix())
        sb.append("Sender: $sender\n")
        sb.append("SMS: $smsBody\n")
        sb.append("Output: ")
        return sb.toString()
    }

}
