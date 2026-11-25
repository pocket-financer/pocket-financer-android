package com.pocketfinancer.hardware

import java.io.File

/**
 * Defines available SLM tiers and auto-selects the best one for the
 * device's hardware capabilities.
 *
 * Selection is based on device capacity and measured direct-generation quality.
 * Within each family, higher quantization fidelity requires more RAM.
 *
 * HuggingFace GGUF repos:
 *   Qwen3:  unsloth/Qwen3-1.7B-GGUF
 *   Gemma4: unsloth/gemma-4-E2B-it-GGUF
 */
data class SlmTier(
    val id: String,
    val name: String,
    val modelFile: String,
    val description: String,
    val sizeMb: Int,           // approximate GGUF size in MB
    val minRamGb: Float,       // minimum RAM to run
    val downloadUrl: String,
    val family: String = ""   // "qwen3" or "gemma4"
) {
    val sizeGb: Float get() = sizeMb / 1024f

    companion object {
        // ═══════════════════════════════════════════════════════════════
        // Qwen3-1.7B
        // ═══════════════════════════════════════════════════════════════

        /** Qwen3-1.7B Q8_0: ~1950 MB, best quality. Needs 4GB+ RAM, GPU recommended. */
        val QWEN3_1_7B_Q8_0 = SlmTier(
            id = "qwen3-1.7b-q8_0",
            name = "Qwen3-1.7B Q8_0",
            modelFile = "Qwen3-1.7B-Q8_0.gguf",
            description = "~1.9 GB · 8-bit quant · Best extraction quality",
            sizeMb = 1950,
            minRamGb = 4.0f,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q8_0.gguf",
            family = "qwen3"
        )

        /** Qwen3-1.7B Q4_K_M: ~1100 MB, good quality. 3.5GB+ RAM. */
        val QWEN3_1_7B_Q4_K_M = SlmTier(
            id = "qwen3-1.7b-q4_k_m",
            name = "Qwen3-1.7B Q4_K_M",
            modelFile = "Qwen3-1.7B-Q4_K_M.gguf",
            description = "~1.1 GB · 4-bit quant · Balanced size/quality",
            sizeMb = 1136,
            minRamGb = 3.5f,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            family = "qwen3"
        )

        // ═══════════════════════════════════════════════════════════════
        // Gemma 4 E2B
        // ═══════════════════════════════════════════════════════════════

        /** Gemma 4 E2B Q8_0: ~5170 MB. 8GB+ RAM, GPU recommended. */
        val GEMMA4_E2B_Q8_0 = SlmTier(
            id = "gemma4-e2b-q8_0",
            name = "Gemma 4 E2B Q8_0",
            modelFile = "gemma-4-E2B-it-Q8_0.gguf",
            description = "~5.0 GB · 8-bit quant · Highest overall quality",
            sizeMb = 5170,
            minRamGb = 8.0f,
            downloadUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q8_0.gguf",
            family = "gemma4"
        )

        /** Gemma 4 E2B Q4_K_M: ~3180 MB. 6GB+ RAM. */
        val GEMMA4_E2B_Q4_K_M = SlmTier(
            id = "gemma4-e2b-q4_k_m",
            name = "Gemma 4 E2B Q4_K_M",
            modelFile = "gemma-4-E2B-it-Q4_K_M.gguf",
            description = "~3.1 GB · 4-bit quant · Alternative model family",
            sizeMb = 3180,
            minRamGb = 6.0f,
            downloadUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
            family = "gemma4"
        )

        // ═══════════════════════════════════════════════════════════════
        // Fallback
        // ═══════════════════════════════════════════════════════════════

        /** Qwen3-0.6B Q8_0: ~700 MB. 2.5GB+ RAM. Lightweight fallback. */
        val QWEN3_0_6B_Q8_0 = SlmTier(
            id = "qwen3-0.6b-q8_0",
            name = "Qwen3-0.6B Q8_0",
            modelFile = "Qwen3-0.6B-Q8_0.gguf",
            description = "~0.7 GB · 8-bit quant · Lightweight fallback",
            sizeMb = 700,
            minRamGb = 2.5f,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
            family = "qwen3"
        )

        /** Default model used during initial onboarding for fast setup. */
        val DEFAULT_ONBOARDING_SLM = QWEN3_0_6B_Q8_0

        /** All tiers in priority order (best extraction first). */
        val ALL_TIERS = listOf(
            GEMMA4_E2B_Q8_0,
            GEMMA4_E2B_Q4_K_M,
            QWEN3_1_7B_Q8_0,
            QWEN3_1_7B_Q4_K_M,
            QWEN3_0_6B_Q8_0
        )
    }
}

/**
 * Auto-select the best SLM for the given hardware capabilities.
 *
 * Priority logic:
 * 1. Gemma 4 E2B Q8_0  — if 8GB+ RAM AND high-performance CPU (highest quality)
 * 2. Gemma 4 E2B Q4_K_M — if 6GB+ RAM (balanced alternative)
 * 3. Qwen3-1.7B Q8_0    — if 4GB+ RAM AND high-performance CPU
 * 4. Qwen3-1.7B Q4_K_M  — if 3.5GB+ RAM
 * 5. Qwen3-0.6B Q8_0    — if 2.5GB+ RAM (lightweight fallback)
 * 6. null               — if < 2.5GB RAM (BLOCKED)
 *
 * The "high-performance CPU" flag checks for ARM i8mm and dotprod
 * instructions. These CPU features meaningfully accelerate LLM inference
 * on CPU. GPU acceleration is NOT used: the llama.cpp Vulkan/OpenCL
 * backends are 10-15× slower than CPU on Android across all GPU vendors,
 * so model tier selection is based on CPU capability instead.
 *
 * Gemma models are preferred on high-end/mid-high devices because they perform
 * exceptionally well on our evaluation dataset. Qwen3 models are used for mid-range,
 * and Qwen3-0.6B is the fallback for budget devices.
 */
fun selectSlmForDevice(device: DeviceCapabilities.DeviceInfo): SlmTier? {
    val ram = device.ramGb
    val highPerf = device.isHighPerformanceDevice

    return when {
        // Tier 1: Gemma 4 E2B Q8_0 (8GB+ RAM & high-perf CPU)
        ram >= 8.0f && highPerf -> SlmTier.GEMMA4_E2B_Q8_0

        // Tier 2: Gemma 4 E2B Q4_K_M (6GB+ RAM & high-perf CPU)
        ram >= 6.0f && highPerf -> SlmTier.GEMMA4_E2B_Q4_K_M

        // Tier 3: Qwen3-1.7B Q8_0 (4GB+ RAM & high-perf CPU)
        ram >= 4.0f && highPerf -> SlmTier.QWEN3_1_7B_Q8_0

        // Tier 4: Qwen3-1.7B Q4_K_M (3.5GB+ RAM & high-perf CPU)
        ram >= 3.5f && highPerf -> SlmTier.QWEN3_1_7B_Q4_K_M

        // Tier 5: Qwen3-0.6B Q8_0 (2.5GB+ RAM)
        ram >= 2.5f -> SlmTier.QWEN3_0_6B_Q8_0

        else -> null
    }
}

/**
 * Explain why a specific tier was or wasn't selected.
 */
fun explainTierSelection(
    tier: SlmTier,
    device: DeviceCapabilities.DeviceInfo,
    isSelected: Boolean
): String {
    val ram = device.ramGb
    val highPerf = device.isHighPerformanceDevice
    val ramStr = "%.1f".format(ram)

    if (isSelected) {
        return when {
            tier == SlmTier.GEMMA4_E2B_Q8_0 ->
                "Selected — highest overall model quality (8-bit quant, high-perf CPU)"
            tier == SlmTier.GEMMA4_E2B_Q4_K_M ->
                "Selected — balanced size/quality (4-bit quant, high-perf CPU)"
            tier == SlmTier.QWEN3_1_7B_Q8_0 ->
                "Selected — balanced quality (8-bit quant, high-perf CPU)"
            tier == SlmTier.QWEN3_1_7B_Q4_K_M ->
                "Selected — balanced size/quality (4-bit quant)"
            tier == SlmTier.QWEN3_0_6B_Q8_0 ->
                "Selected — lightweight fallback for low-RAM devices"
            else -> "Selected — auto-picked based on hardware"
        }
    }

    val blockers = mutableListOf<String>()
    if (ram < tier.minRamGb) {
        blockers.add("needs ≥${"%.1f".format(tier.minRamGb)}GB RAM (you have ${ramStr}GB)")
    }

    when (tier) {
        SlmTier.GEMMA4_E2B_Q8_0,
        SlmTier.GEMMA4_E2B_Q4_K_M,
        SlmTier.QWEN3_1_7B_Q8_0,
        SlmTier.QWEN3_1_7B_Q4_K_M -> {
            if (!highPerf) blockers.add("needs i8mm+dotprod CPU instructions (not detected)")
        }
        else -> {}
    }

    if (blockers.isNotEmpty()) {
        return "Not available: ${blockers.joinToString("; ")}"
    }

    // No blockers but still not selected → a higher-priority tier was picked
    return "Available but a higher-priority tier was selected"
}

/**
 * Checks if a higher quality SLM tier is recommended for the device compared to currentSlm.
 */
fun isUpgradeAvailable(
    currentSlm: SlmTier?,
    device: DeviceCapabilities.DeviceInfo
): Boolean {
    val recommended = selectSlmForDevice(device) ?: return false
    if (currentSlm == null) return true

    val currentRank = SlmTier.ALL_TIERS.indexOf(currentSlm)
    val recommendedRank = SlmTier.ALL_TIERS.indexOf(recommended)

    // Lower index in ALL_TIERS means higher quality/priority tier
    return recommendedRank != -1 && (currentRank == -1 || recommendedRank < currentRank)
}

/**
 * Returns whether [file] is a published model artifact that can be offered to
 * the native loader.
 *
 * [SlmTier.sizeMb] is intentionally approximate and must not be used as an
 * integrity boundary. Downloads use a separate `.part` name until transport
 * verification succeeds, so the final named artifact is published only after
 * completion. The native GGUF load remains the authoritative format check.
 */
fun isPublishedModelArtifact(file: File): Boolean =
    file.isFile && file.length() > 0L

/**
 * Resolves the active model tier to load, checking saved preference first,
 * then downloaded files on disk, and falling back to hardware recommendation.
 */
fun resolveActiveSlmTier(
    context: android.content.Context,
    modelStorageDir: java.io.File,
    device: DeviceCapabilities.DeviceInfo
): SlmTier? {
    val prefs = context.getSharedPreferences(".app_settings", android.content.Context.MODE_PRIVATE)
    val savedId = prefs.getString("selected_slm_id", null)

    // 1. Try saved SLM tier if valid on disk
    if (savedId != null) {
        val savedTier = SlmTier.ALL_TIERS.find { it.id == savedId }
        if (savedTier != null) {
            val file = java.io.File(modelStorageDir, savedTier.modelFile)
            if (isPublishedModelArtifact(file)) {
                return savedTier
            }
        }
    }

    // 2. Try default onboarding SLM tier if valid on disk
    val defaultTier = SlmTier.DEFAULT_ONBOARDING_SLM
    val defaultFile = java.io.File(modelStorageDir, defaultTier.modelFile)
    if (isPublishedModelArtifact(defaultFile)) {
        return defaultTier
    }

    // 3. Try any valid downloaded model file on disk in tier priority order
    for (tier in SlmTier.ALL_TIERS) {
        val file = java.io.File(modelStorageDir, tier.modelFile)
        if (isPublishedModelArtifact(file)) {
            return tier
        }
    }

    // 4. Fall back to hardware recommendation selector
    return selectSlmForDevice(device)
}
