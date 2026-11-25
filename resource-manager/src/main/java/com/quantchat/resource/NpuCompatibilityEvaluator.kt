package com.quantchat.resource

import com.quantchat.engine.core.ModelArtifactFormat
import com.quantchat.engine.core.NpuCompatibilityReport
import com.quantchat.engine.core.NpuInferenceBackend
import com.quantchat.engine.core.NpuProbeResult
import com.quantchat.model.GgufHeaderMetadata

object NpuCompatibilityEvaluator {

    private val defaultNpuGate = NpuInferenceBackend()

    fun evaluate(
        modelId: String,
        filePath: String,
        header: GgufHeaderMetadata?,
        npuProbe: NpuProbeResult?,
        routedFallbackTarget: String,
        npuBackend: NpuInferenceBackend? = null
    ): NpuCompatibilityReport {
        val backend = npuBackend ?: defaultNpuGate
        val effectiveFormat = if (header != null) {
            ModelArtifactFormat.GGUF
        } else {
            backend.detectArtifactFormat(filePath)
        }

        val baseReport = backend.evaluateArtifactCompatibility(
            modelId = modelId,
            filePath = if (effectiveFormat == ModelArtifactFormat.GGUF && !filePath.endsWith(".gguf", ignoreCase = true)) {
                "$filePath.gguf"
            } else {
                filePath
            },
            probeResult = npuProbe,
            routedFallbackTarget = routedFallbackTarget
        )

        if (effectiveFormat == ModelArtifactFormat.GGUF && header != null) {
            val archInfo = header.architecture?.let { "arch=$it" } ?: "GGUF graph"
            val vendorStack = npuProbe?.vendorFamily?.stackName ?: "Hexagon/NPU"
            return baseReport.copy(
                blockingReason = "GGUF ($archInfo) cannot execute directly on ${npuProbe?.vendorFamily?.label ?: "NPU"}; $vendorStack requires pre-compiled static-shape context binaries. Routed automatically to $routedFallbackTarget."
            )
        }

        return baseReport
    }
}
