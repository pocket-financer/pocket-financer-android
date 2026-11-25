package com.pocketfinancer

import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage

/**
 * Builds the exact runtime identity used by every app-owned SLM flow.
 *
 * Keeping this mapping in one place prevents two callers from treating the
 * same GGUF artifact as differently configured native models.
 */
fun SlmTier.toModelSpec(
    storage: SlmModelStorage,
    device: DeviceCapabilities.DeviceInfo
): SlmModelSpec {
    val artifact = storage.modelFile(modelFile)
    return SlmModelSpec(
        modelId = id,
        modelPath = artifact.absolutePath,
        artifactRevision = "$modelFile:${artifact.length()}:${artifact.lastModified()}",
        contextSize = 3072,
        gpuLayers = 0,
        numThreads = 0,
        hasFp16 = device.cpu?.hasFp16 ?: false
    )
}
