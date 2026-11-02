package com.pocketfinancer.inference

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Filesystem/asset access that does not touch native model state. */
interface SlmModelStorage {
    val modelDirectory: File

    fun modelFile(name: String): File

    fun readTextAsset(filename: String): String

    fun readAssetBytes(filename: String): ByteArray

    fun sessionNamespace(spec: SlmModelSpec): String

    fun sessionFile(spec: SlmModelSpec, prefixHash: String): File =
        sessionFile(sessionNamespace(spec), prefixHash)

    fun sessionFile(modelName: String, prefixHash: String): File

    fun deleteStaleSessions(spec: SlmModelSpec, activeHash: String?) =
        deleteStaleSessions(sessionNamespace(spec), activeHash)

    fun deleteStaleSessions(modelName: String, activeHash: String?)

    fun sha256(input: String): String
}

@Singleton
class DefaultSlmModelStorage @Inject constructor(
    @ApplicationContext private val context: Context
) : SlmModelStorage {
    override val modelDirectory: File
        get() = File(context.filesDir, "models").also { directory ->
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }

    override fun modelFile(name: String): File = File(modelDirectory, name)

    override fun readTextAsset(filename: String): String =
        context.assets.open(filename).bufferedReader().use { it.readText() }

    override fun readAssetBytes(filename: String): ByteArray =
        context.assets.open(filename).use { it.readBytes() }

    override fun sessionNamespace(spec: SlmModelSpec): String {
        val exactIdentity = buildString {
            append(spec.modelId)
            append('|')
            append(spec.modelPath)
            append('|')
            append(spec.artifactRevision)
            append('|')
            append(spec.contextSize)
            append('|')
            append(spec.gpuLayers)
            append('|')
            append(spec.numThreads)
            append('|')
            append(spec.hasFp16)
        }
        return "${File(spec.modelPath).name}_${sha256(exactIdentity).take(16)}"
    }

    override fun sessionFile(modelName: String, prefixHash: String): File {
        val safeModelName = modelName.replace(NON_FILENAME_CHARACTER, "_")
        return File(modelDirectory, "session_${safeModelName}_$prefixHash.bin")
    }

    override fun deleteStaleSessions(modelName: String, activeHash: String?) {
        val safeModelName = modelName.replace(NON_FILENAME_CHARACTER, "_")
        val prefix = "session_${safeModelName}_"
        val activeName = activeHash?.let { "$prefix$it.bin" }
        modelDirectory.listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix) &&
                file.name.endsWith(".bin") &&
                file.name != activeName
            ) {
                runCatching { file.delete() }
            }
        }
    }

    override fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val NON_FILENAME_CHARACTER = Regex("[^a-zA-Z0-9_-]")
    }
}
