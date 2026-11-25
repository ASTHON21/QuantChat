package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AppReleaseInfo
import com.example.data.InferenceSettings
import com.example.ui.theme.QuantEmerald
import com.example.ui.theme.QuantEmeraldContainer
import com.example.ui.theme.QuantOnEmerald
import com.example.ui.theme.QuantOnEmeraldContainer
import com.example.ui.theme.QuantOnSurface
import com.example.ui.theme.QuantOnSurfaceVariant
import com.example.ui.theme.QuantOutline
import com.example.ui.theme.QuantOutlineVariant
import com.example.ui.theme.QuantRose
import com.example.ui.theme.QuantRoseContainer
import com.example.ui.theme.QuantSurfaceContainer
import com.example.ui.theme.QuantSurfaceHigh
import com.example.ui.theme.QuantSurfaceLow
import com.example.ui.theme.QuantSurfaceLowest
import com.example.ui.theme.QuantTextStyles
import com.quantchat.engine.core.AcceleratorBackendType
import com.quantchat.engine.core.AcceleratorPreference
import com.quantchat.engine.core.CpuScheduleMode
import com.quantchat.hardware.CpuCoreTier
import com.quantchat.hardware.ThermalSeverity
import com.quantchat.resource.GpuOffloadPlanner
import com.quantchat.resource.OrchestratorSnapshot
import java.util.Locale
import kotlin.math.roundToInt

private val SYSTEM_PROMPT_PRESETS = listOf(
    "General" to InferenceSettings.DEFAULT_SYSTEM_PROMPT,
    "Coding" to "You are an expert software engineer. Provide clean, accurate code and concise technical explanations without filler.",
    "Analyst" to "You are a quantitative and technical analyst. Use structured reasoning, clear numbers, and concise bullet points."
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceSettingsSheet(
    currentSettings: InferenceSettings,
    orchestratorSnapshot: OrchestratorSnapshot? = null,
    isModelReady: Boolean = false,
    onRefreshDiagnostics: () -> Unit = {},
    onRunBenchmark: () -> Unit = {},
    onCalibrateThreads: () -> Unit = {},
    onCancelCalibration: () -> Unit = {},
    onRunVulkanSelfTest: () -> Unit = {},
    onRunNpuProbe: () -> Unit = {},
    onDismiss: () -> Unit,
    onApply: (InferenceSettings) -> Unit,
    onOpenVersionInfo: () -> Unit = {}
) {
    var systemPrompt by remember(currentSettings) { mutableStateOf(currentSettings.systemPrompt) }
    var scheduleMode by remember(currentSettings) { mutableStateOf(currentSettings.scheduleMode) }
    var autoThermalDownshift by remember(currentSettings) { mutableStateOf(currentSettings.autoThermalDownshift) }
    var manualThreadsOverride by remember(currentSettings) { mutableStateOf(currentSettings.manualThreadsOverride) }
    var acceleratorPreference by remember(currentSettings) { mutableStateOf(currentSettings.acceleratorPreference) }
    var autoGpuLayerOffload by remember(currentSettings) { mutableStateOf(currentSettings.autoGpuLayerOffload) }
    var manualGpuLayers by remember(currentSettings) { mutableIntStateOf(currentSettings.manualGpuLayers) }
    var contextSize by remember(currentSettings) { mutableIntStateOf(currentSettings.contextSize) }
    var maxTokens by remember(currentSettings) { mutableIntStateOf(currentSettings.maxTokens) }
    var temperature by remember(currentSettings) { mutableFloatStateOf(currentSettings.temperature) }

    LaunchedEffect(Unit) {
        onRefreshDiagnostics()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = QuantSurfaceLow,
        contentColor = QuantOnSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "LOCAL RUNTIME // LLAMA.CPP",
                        style = QuantTextStyles.LabelSm,
                        color = QuantEmerald
                    )
                    Text(
                        text = "On-Device Inference Settings",
                        style = QuantTextStyles.HeadlineMd,
                        color = QuantOnSurface
                    )
                }
                Text(
                    text = "OFFLINE",
                    style = QuantTextStyles.LabelMd,
                    color = QuantEmerald,
                    modifier = Modifier
                        .background(QuantSurfaceContainer, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (orchestratorSnapshot != null) {
                HardwareCapabilitiesCard(
                    snapshot = orchestratorSnapshot,
                    acceleratorPreference = acceleratorPreference,
                    savedCalibratedThreads = currentSettings.calibratedOptimalThreads,
                    isModelReady = isModelReady,
                    onRefreshCapabilities = onRefreshDiagnostics,
                    onRunBenchmark = onRunBenchmark,
                    onCalibrateThreads = onCalibrateThreads,
                    onCancelCalibration = onCancelCalibration,
                    onRunVulkanSelfTest = onRunVulkanSelfTest,
                    onRunNpuProbe = onRunNpuProbe
                )
            }

            // 3-Mode Accelerator Backend Selector
            AcceleratorPreferenceSelector(
                selectedPreference = acceleratorPreference,
                onSelectPreference = { acceleratorPreference = it }
            )

            // GPU Layer Offload (Auto RAM/VRAM + Manual Override)
            val totalLayersForSlider = (orchestratorSnapshot?.gpuOffloadPlan?.totalLayers ?: 32).coerceIn(1, 128)
            val liveOffloadPreview = remember(
                orchestratorSnapshot,
                acceleratorPreference,
                autoGpuLayerOffload,
                manualGpuLayers,
                contextSize
            ) {
                val basePlan = orchestratorSnapshot?.gpuOffloadPlan
                val metrics = orchestratorSnapshot?.liveMetrics ?: orchestratorSnapshot?.hardwareProfile?.liveMetrics
                val probe = orchestratorSnapshot?.vulkanCapabilities
                if (metrics != null) {
                    val estSizeBytes = (orchestratorSnapshot?.lastAdmission?.estimatedMemoryMb ?: 2048L) * 1024L * 1024L
                    GpuOffloadPlanner.planOffload(
                        fileSizeBytes = estSizeBytes,
                        header = null,
                        contextSize = contextSize,
                        availableRamMb = metrics.availableRamMb,
                        lowMemory = metrics.lowMemory,
                        vulkanProbe = probe,
                        preference = acceleratorPreference,
                        autoOffload = autoGpuLayerOffload,
                        manualLayersOverride = manualGpuLayers
                    ).copy(totalLayers = basePlan?.totalLayers ?: totalLayersForSlider)
                } else {
                    basePlan
                }
            }
            GpuLayerOffloadCard(
                preference = acceleratorPreference,
                autoGpuLayerOffload = autoGpuLayerOffload,
                manualGpuLayers = manualGpuLayers.coerceIn(0, totalLayersForSlider),
                totalLayers = totalLayersForSlider,
                recommendedLayers = liveOffloadPreview?.recommendedLayers ?: 0,
                effectiveLayers = if (acceleratorPreference == AcceleratorPreference.CPU_ONLY) {
                    0
                } else if (autoGpuLayerOffload) {
                    liveOffloadPreview?.recommendedLayers ?: 0
                } else {
                    manualGpuLayers.coerceIn(0, totalLayersForSlider)
                },
                estimatedGpuMb = liveOffloadPreview?.estimatedGpuMemoryMb ?: 0L,
                estimatedCpuMb = liveOffloadPreview?.estimatedCpuMemoryMb ?: 0L,
                policySummary = liveOffloadPreview?.policySummary ?: "Auto RAM/VRAM calculation active",
                onToggleAuto = { auto ->
                    autoGpuLayerOffload = auto
                    if (!auto && manualGpuLayers == 0 && (liveOffloadPreview?.recommendedLayers ?: 0) > 0) {
                        manualGpuLayers = liveOffloadPreview?.recommendedLayers ?: 0
                    }
                },
                onManualLayersChange = { manualGpuLayers = it }
            )

            // 4-Mode CPU Schedule Profile Selector
            CpuScheduleModeGrid(
                selectedMode = scheduleMode,
                modeThreadPreview = orchestratorSnapshot?.modeThreadPreview.orEmpty(),
                isThermallyThrottled = orchestratorSnapshot?.schedulingDecision?.isThermallyThrottled == true,
                onSelectMode = { mode ->
                    scheduleMode = mode
                    manualThreadsOverride = null
                }
            )

            // Auto Thermal Safe Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(QuantSurfaceLowest)
                    .border(1.dp, QuantSurfaceHigh, RoundedCornerShape(6.dp))
                    .clickable { autoThermalDownshift = !autoThermalDownshift }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = "Auto Thermal Safe Protection",
                        style = QuantTextStyles.LabelMd,
                        color = QuantOnSurface
                    )
                    Text(
                        text = "Automatically downshift threads at Moderate+ device temperature and restore when cooled.",
                        style = QuantTextStyles.BodySm,
                        color = QuantOutline
                    )
                }
                Switch(
                    checked = autoThermalDownshift,
                    onCheckedChange = { autoThermalDownshift = it },
                    modifier = Modifier.testTag("auto_thermal_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = QuantOnEmerald,
                        checkedTrackColor = QuantEmerald,
                        uncheckedThumbColor = QuantOutline,
                        uncheckedTrackColor = QuantSurfaceHigh
                    )
                )
            }

            // Optional Manual Thread Override
            ManualThreadOverrideRow(
                selectedOverride = manualThreadsOverride,
                recommendedForMode = orchestratorSnapshot?.modeThreadPreview?.get(scheduleMode)
                    ?: orchestratorSnapshot?.recommendedThreads
                    ?: currentSettings.threads,
                onSelectOverride = { manualThreadsOverride = it }
            )

            // System Prompt Presets + Editor
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SYSTEM PROMPT",
                        style = QuantTextStyles.LabelSm,
                        color = QuantOutline
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SYSTEM_PROMPT_PRESETS.forEach { (label, prompt) ->
                            val isMatch = systemPrompt.trim() == prompt
                            Text(
                                text = label,
                                style = QuantTextStyles.LabelSm,
                                color = if (isMatch) QuantOnEmerald else QuantOnSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isMatch) QuantEmerald else QuantSurfaceContainer)
                                    .clickable { systemPrompt = prompt }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    minLines = 2,
                    maxLines = 4,
                    textStyle = QuantTextStyles.BodySm,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = QuantEmerald,
                        unfocusedBorderColor = QuantOutlineVariant.copy(alpha = 0.5f),
                        focusedTextColor = QuantOnSurface,
                        unfocusedTextColor = QuantOnSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inference_system_prompt_input")
                )
            }

            // Context Window
            ChipSelectorRow(
                label = "CONTEXT WINDOW (N_CTX)",
                options = listOf(1024, 2048, 4096),
                selected = contextSize,
                formatter = { "$it" },
                onSelect = { contextSize = it }
            )

            // Max Output Tokens
            ChipSelectorRow(
                label = "MAX OUTPUT TOKENS",
                options = listOf(256, 512, 1024),
                selected = maxTokens,
                formatter = { "$it" },
                onSelect = { maxTokens = it }
            )

            // Temperature
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(QuantSurfaceLowest, RoundedCornerShape(6.dp))
                    .border(1.dp, QuantSurfaceHigh, RoundedCornerShape(6.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "SAMPLING TEMPERATURE",
                        style = QuantTextStyles.LabelSm,
                        color = QuantOutline
                    )
                    Text(
                        text = String.format(Locale.US, "%.2f", temperature),
                        style = QuantTextStyles.DataTabular,
                        color = QuantEmerald
                    )
                }
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0.0f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = QuantEmerald,
                        activeTrackColor = QuantEmerald,
                        inactiveTrackColor = QuantSurfaceHigh
                    )
                )
            }

            Text(
                text = "CPU profile and thread adjustments apply immediately on the next prompt. Changing Context Window, Accelerator Backend, or GPU Layer Offload remaps the active GGUF model.",
                style = QuantTextStyles.BodySm,
                color = QuantOutline
            )

            Spacer(modifier = Modifier.height(2.dp))

            val previewThreads = manualThreadsOverride
                ?: orchestratorSnapshot?.modeThreadPreview?.get(scheduleMode)
                ?: currentSettings.threads

            Button(
                onClick = {
                    onApply(
                        currentSettings.copy(
                            systemPrompt = systemPrompt.trim().ifEmpty {
                                InferenceSettings.DEFAULT_SYSTEM_PROMPT
                            },
                            threads = previewThreads,
                            contextSize = contextSize,
                            maxTokens = maxTokens,
                            temperature = temperature,
                            scheduleMode = scheduleMode,
                            autoThermalDownshift = autoThermalDownshift,
                            manualThreadsOverride = manualThreadsOverride,
                            acceleratorPreference = acceleratorPreference,
                            autoGpuLayerOffload = autoGpuLayerOffload,
                            manualGpuLayers = manualGpuLayers
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("apply_inference_settings_button"),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = QuantEmeraldContainer,
                    contentColor = QuantOnEmeraldContainer
                )
            ) {
                Text(
                    text = "Apply Runtime Settings",
                    style = QuantTextStyles.LabelLg.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(QuantSurfaceLowest)
                    .border(1.dp, QuantSurfaceHigh, RoundedCornerShape(6.dp))
                    .clickable {
                        onDismiss()
                        onOpenVersionInfo()
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "QuantChat ${AppReleaseInfo.fullVersionLabel} · ${AppReleaseInfo.gitSha}",
                    style = QuantTextStyles.LabelSm,
                    color = QuantOutline
                )
                Text(
                    text = "Changelog →",
                    style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                    color = QuantEmerald
                )
            }
        }
    }
}

@Composable
private fun HardwareCapabilitiesCard(
    snapshot: OrchestratorSnapshot,
    acceleratorPreference: AcceleratorPreference,
    savedCalibratedThreads: Int?,
    isModelReady: Boolean,
    onRefreshCapabilities: () -> Unit,
    onRunBenchmark: () -> Unit,
    onCalibrateThreads: () -> Unit,
    onCancelCalibration: () -> Unit,
    onRunVulkanSelfTest: () -> Unit,
    onRunNpuProbe: () -> Unit
) {
    val profile = snapshot.hardwareProfile
    val metrics = snapshot.liveMetrics ?: profile?.liveMetrics
    val bench = snapshot.lastBenchmark
    val calProgress = snapshot.calibrationProgress
    val lastCal = snapshot.lastCalibration
    val decision = snapshot.schedulingDecision

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(QuantSurfaceLowest)
            .border(1.dp, QuantSurfaceHigh, RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "HARDWARE & CPU TOPOLOGY",
                style = QuantTextStyles.LabelSm,
                color = QuantEmerald
            )
            Text(
                text = "${snapshot.versionInfo.backendId.uppercase(Locale.US)} · ${snapshot.versionInfo.engineVersion}",
                style = QuantTextStyles.LabelSm,
                color = QuantOutline
            )
        }

        if (profile != null) {
            val simdText = if (profile.cpuFeatures.isNotEmpty()) {
                profile.cpuFeatures.joinToString(" · ")
            } else {
                "Standard ${profile.primaryAbi}"
            }
            DiagRow(label = "SoC / SIMD", value = "${profile.socName} · $simdText")
            DiagRow(
                label = "Topology",
                value = "${profile.cpuClusters.topologyLabel} (${profile.cpuClusters.onlineCores}/${profile.cpuClusters.totalCores} online)"
            )

            // Per-cluster Prime / Big / LITTLE breakdown
            if (profile.cpuClusters.clusters.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(QuantSurfaceContainer)
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    profile.cpuClusters.clusters.forEach { group ->
                        val tierColor = when (group.tier) {
                            CpuCoreTier.PRIME -> QuantEmerald
                            CpuCoreTier.BIG -> QuantOnSurface
                            CpuCoreTier.LITTLE, CpuCoreTier.UNKNOWN -> QuantOutline
                        }
                        val freqText = if (group.maxFreqMhz > 0) "${group.maxFreqMhz}MHz" else "freq hidden"
                        val capText = group.capacity?.let { "cap $it" } ?: "freq-sorted"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${group.tier.label} · ${group.coreIds.size}C (cpu ${group.coreIds.first()}–${group.coreIds.last()})",
                                style = QuantTextStyles.LabelSm,
                                color = tierColor
                            )
                            Text(
                                text = "$freqText · $capText",
                                style = QuantTextStyles.DataTabular,
                                color = QuantOnSurfaceVariant
                            )
                        }
                    }
                    if (profile.cpuClusters.sysfsRestricted) {
                        Text(
                            text = "Some sysfs capacity nodes hidden by kernel · using frequency/core fallback.",
                            style = QuantTextStyles.LabelSm,
                            color = QuantOutline
                        )
                    }
                }
            }

            Text(
                text = "Standard non-root mode: dynamic n_threads + Process.setThreadPriority (kernel governs physical core placement).",
                style = QuantTextStyles.LabelSm,
                color = QuantOutline
            )
        }

        if (metrics != null) {
            val thermalColor = when (metrics.thermalSeverity) {
                ThermalSeverity.MODERATE, ThermalSeverity.SEVERE, ThermalSeverity.CRITICAL -> QuantRose
                else -> QuantOnSurfaceVariant
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Memory / Thermal", style = QuantTextStyles.LabelSm, color = QuantOutline)
                Text(
                    text = "${metrics.availableRamMb}MB / ${metrics.totalRamMb}MB free · ${metrics.thermalSeverity.label}",
                    style = QuantTextStyles.DataTabular,
                    color = thermalColor
                )
            }
        }

        if (decision != null) {
            DiagRow(
                label = "Active Policy",
                value = "${decision.effectiveMode.label} · ${decision.effectiveThreads}T (${decision.priorityHint.label})"
            )
        } else if (savedCalibratedThreads != null) {
            DiagRow(
                label = "Calibrated Base",
                value = "${savedCalibratedThreads}T saved on this device"
            )
        }

        UnifiedCapabilitiesCard(
            deviceCapabilities = snapshot.deviceCapabilities,
            backendStatus = snapshot.backendRuntimeStatus,
            acceleratorPreference = acceleratorPreference,
            onRefreshCapabilities = onRefreshCapabilities
        )

        // Vulkan GPU & OpenCL Phase 2 Diagnostics Section
        val vk = snapshot.vulkanCapabilities ?: profile?.vulkanCapabilities
        val backendStatus = snapshot.backendRuntimeStatus
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(QuantSurfaceContainer)
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GPU · VULKAN ACCELERATION",
                    style = QuantTextStyles.LabelSm,
                    color = if (vk?.isUsableForInference == true) QuantEmerald else QuantOnSurfaceVariant
                )
                Text(
                    text = "Run GPU Probe",
                    style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                    color = QuantEmerald,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(QuantSurfaceLowest)
                        .border(0.8.dp, QuantEmerald.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        .clickable(onClick = onRunVulkanSelfTest)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("run_vulkan_probe_button")
                )
            }
            if (vk != null) {
                DiagRow(
                    label = "GPU / Vulkan",
                    value = listOfNotNull(
                        vk.deviceName ?: vk.vendorLabel,
                        "VK ${vk.vulkanApiVersionLabel ?: vk.systemVulkanVersion}"
                    ).joinToString(" · ")
                )
                DiagRow(
                    label = "Self-Test",
                    value = if (vk.isUsableForInference) {
                        "Verified (16bit=${if (vk.supports16BitStorage) "Y" else "N"} · FP16=${if (vk.supportsFp16Compute) "Y" else "N"})"
                    } else {
                        vk.status.label
                    }
                )
                if (!vk.reason.isNullOrBlank()) {
                    Text(
                        text = vk.reason.orEmpty(),
                        style = QuantTextStyles.LabelSm,
                        color = if (vk.isUsableForInference) QuantOutline else QuantRose
                    )
                }
            } else {
                DiagRow(
                    label = "GPU Status",
                    value = profile?.gpuStatusSummary ?: "Probing..."
                )
            }
            if (backendStatus.fallbackOccurred && !backendStatus.fallbackReason.isNullOrBlank()) {
                Text(
                    text = "CPU Auto-Fallback Active: ${backendStatus.fallbackReason}",
                    style = QuantTextStyles.LabelSm,
                    color = QuantRose
                )
            }
            Text(
                text = profile?.openClStatusNote ?: "OpenCL deferred to Phase 2 compatibility track",
                style = QuantTextStyles.LabelSm,
                color = QuantOutline
            )
        }

        NpuDiagnosticsSection(
            npuCapabilities = snapshot.npuCapabilities ?: profile?.npuCapabilities,
            compatibilityReport = snapshot.npuCompatibilityReport,
            onRunNpuProbe = onRunNpuProbe
        )

        HorizontalDivider(color = QuantSurfaceHigh, thickness = 0.8.dp)

        // Calibration & Quick Bench Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (calProgress.running) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    CircularProgressIndicator(
                        color = QuantEmerald,
                        strokeWidth = 1.8.dp,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Calibrating ${calProgress.currentThreads ?: "-"}T (${calProgress.stepIndex}/${calProgress.totalSteps})...",
                        style = QuantTextStyles.LabelSm,
                        color = QuantEmerald,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Cancel",
                    style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                    color = QuantRose,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(QuantRoseContainer)
                        .border(0.8.dp, QuantRose.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                        .clickable(onClick = onCancelCalibration)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("cancel_calibration_button")
                )
            } else {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isModelReady && !snapshot.isBenchmarking) QuantEmerald.copy(alpha = 0.14f) else QuantSurfaceLow)
                        .border(
                            0.8.dp,
                            if (isModelReady && !snapshot.isBenchmarking) QuantEmerald.copy(alpha = 0.45f) else QuantSurfaceHigh,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable(enabled = isModelReady && !snapshot.isBenchmarking, onClick = onCalibrateThreads)
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                        .testTag("calibrate_threads_button"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isModelReady) "Calibrate Optimal Threads" else "Load Model to Calibrate",
                        style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isModelReady) QuantEmerald else QuantOutline
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isModelReady) QuantSurfaceContainer else QuantSurfaceLow)
                        .border(
                            0.8.dp,
                            if (isModelReady) QuantEmerald.copy(alpha = 0.35f) else QuantSurfaceHigh,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable(enabled = isModelReady && !snapshot.isBenchmarking, onClick = onRunBenchmark)
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                        .testTag("run_benchmark_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (snapshot.isBenchmarking) {
                        CircularProgressIndicator(
                            color = QuantEmerald,
                            strokeWidth = 1.8.dp,
                            modifier = Modifier.size(11.dp)
                        )
                        Text("Running...", style = QuantTextStyles.LabelSm, color = QuantEmerald)
                    } else {
                        Text(
                            text = "Run Bench",
                            style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isModelReady) QuantEmerald else QuantOutline
                        )
                    }
                }
            }
        }

        val calEntries = if (calProgress.running && calProgress.completedEntries.isNotEmpty()) {
            calProgress.completedEntries
        } else {
            lastCal?.entries.orEmpty()
        }
        if (calEntries.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(QuantSurfaceContainer)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = if (lastCal != null && !calProgress.running) {
                        "THREAD CALIBRATION RESULT · OPTIMAL ${lastCal.optimalThreads}T"
                    } else {
                        "CALIBRATION SWEEP IN PROGRESS"
                    },
                    style = QuantTextStyles.LabelSm,
                    color = QuantEmerald
                )
                calEntries.forEach { entry ->
                    val isWinner = lastCal?.optimalThreads == entry.threads && !calProgress.running
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isWinner) "${entry.threads}T ★ OPTIMAL" else "${entry.threads}T",
                            style = QuantTextStyles.DataTabular.copy(
                                fontWeight = if (isWinner) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (isWinner) QuantEmerald else QuantOnSurfaceVariant
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "PP %.1f · TG %.1f tok/s",
                                entry.prefillTokPerSec,
                                entry.decodeTokPerSec
                            ),
                            style = QuantTextStyles.DataTabular,
                            color = if (isWinner) QuantEmerald else QuantOutline
                        )
                    }
                }
            }
        } else if (bench != null) {
            Text(
                text = String.format(
                    Locale.US,
                    "LAST BENCH · PP %.1f tok/s (%dt) · TG %.1f tok/s (%dt)",
                    bench.prefillTokensPerSec,
                    bench.prefillTokens,
                    bench.decodeTokensPerSec,
                    bench.decodeTokens
                ),
                style = QuantTextStyles.DataTabular,
                color = QuantEmerald
            )
        }
    }
}

@Composable
private fun CpuScheduleModeGrid(
    selectedMode: CpuScheduleMode,
    modeThreadPreview: Map<CpuScheduleMode, Int>,
    isThermallyThrottled: Boolean,
    onSelectMode: (CpuScheduleMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CPU SCHEDULING PROFILE",
                style = QuantTextStyles.LabelSm,
                color = QuantOutline
            )
            if (isThermallyThrottled) {
                Text(
                    text = "THERMAL AUTO-DOWNSHIFT ACTIVE",
                    style = QuantTextStyles.LabelSm,
                    color = QuantRose
                )
            }
        }

        val rows = listOf(
            listOf(CpuScheduleMode.ECO, CpuScheduleMode.BALANCED),
            listOf(CpuScheduleMode.PERFORMANCE, CpuScheduleMode.THERMAL_SAFE)
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEach { mode ->
                        val isSelected = selectedMode == mode
                        val isThermalCard = mode == CpuScheduleMode.THERMAL_SAFE
                        val accentColor = if (isThermalCard) QuantRose else QuantEmerald
                        val cardBg = when {
                            isSelected && isThermalCard -> QuantRoseContainer
                            isSelected -> QuantEmerald.copy(alpha = 0.14f)
                            else -> QuantSurfaceLowest
                        }
                        val cardBorder = when {
                            isSelected -> accentColor
                            else -> QuantSurfaceHigh
                        }
                        val previewT = modeThreadPreview[mode]

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(cardBg)
                                .border(1.dp, cardBorder, RoundedCornerShape(6.dp))
                                .clickable { onSelectMode(mode) }
                                .padding(horizontal = 10.dp, vertical = 9.dp)
                                .testTag("schedule_mode_card_${mode.name.lowercase(Locale.US)}"),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = mode.label,
                                    style = QuantTextStyles.LabelMd.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isSelected) accentColor else QuantOnSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (previewT != null) {
                                    Text(
                                        text = "${previewT}T",
                                        style = QuantTextStyles.DataTabular,
                                        color = if (isSelected) accentColor else QuantOutline
                                    )
                                }
                            }
                            Text(
                                text = mode.scenarioHint,
                                style = QuantTextStyles.LabelSm,
                                color = QuantOutline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualThreadOverrideRow(
    selectedOverride: Int?,
    recommendedForMode: Int,
    onSelectOverride: (Int?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "MANUAL THREAD OVERRIDE (AUTO = ${recommendedForMode}T)",
            style = QuantTextStyles.LabelSm,
            color = QuantOutline
        )
        val options: List<Int?> = listOf(null, 2, 4, 6, 8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { value ->
                val isSelected = selectedOverride == value
                val label = if (value == null) "Auto" else "${value}T"
                val tagSuffix = value?.let { "${it}t" } ?: "auto"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) QuantEmerald else QuantSurfaceContainer)
                        .border(
                            1.dp,
                            if (isSelected) QuantEmerald else QuantOutlineVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { onSelectOverride(value) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("manual_threads_chip_$tagSuffix"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = QuantTextStyles.LabelMd,
                        color = if (isSelected) QuantOnEmerald else QuantOnSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = QuantTextStyles.LabelSm, color = QuantOutline)
        Text(text = value, style = QuantTextStyles.LabelSm, color = QuantOnSurfaceVariant)
    }
}

@Composable
private fun ChipSelectorRow(
    label: String,
    options: List<Int>,
    selected: Int,
    formatter: (Int) -> String,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = QuantTextStyles.LabelSm,
            color = QuantOutline
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { value ->
                val isSelected = selected == value
                Text(
                    text = formatter(value),
                    style = QuantTextStyles.LabelMd,
                    color = if (isSelected) QuantOnEmerald else QuantOnSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) QuantEmerald else QuantSurfaceContainer)
                        .border(
                            1.dp,
                            if (isSelected) QuantEmerald else QuantOutlineVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun AcceleratorPreferenceSelector(
    selectedPreference: AcceleratorPreference,
    onSelectPreference: (AcceleratorPreference) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "ACCELERATOR BACKEND",
            style = QuantTextStyles.LabelSm,
            color = QuantOutline
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AcceleratorPreference.entries.forEach { pref ->
                val isSelected = selectedPreference == pref
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) QuantEmerald.copy(alpha = 0.14f) else QuantSurfaceLowest)
                        .border(
                            1.dp,
                            if (isSelected) QuantEmerald else QuantSurfaceHigh,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { onSelectPreference(pref) }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .testTag("accelerator_pref_${pref.name.lowercase(Locale.US)}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = pref.label,
                            style = QuantTextStyles.LabelMd.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isSelected) QuantEmerald else QuantOnSurface
                        )
                        Text(
                            text = pref.description,
                            style = QuantTextStyles.LabelSm,
                            color = QuantOutline
                        )
                    }
                    Text(
                        text = if (isSelected) "ACTIVE" else "SELECT",
                        style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isSelected) QuantEmerald else QuantOutline
                    )
                }
            }
        }
    }
}

@Composable
private fun GpuLayerOffloadCard(
    preference: AcceleratorPreference,
    autoGpuLayerOffload: Boolean,
    manualGpuLayers: Int,
    totalLayers: Int,
    recommendedLayers: Int,
    effectiveLayers: Int,
    estimatedGpuMb: Long,
    estimatedCpuMb: Long,
    policySummary: String,
    onToggleAuto: (Boolean) -> Unit,
    onManualLayersChange: (Int) -> Unit
) {
    val isCpuForced = preference == AcceleratorPreference.CPU_ONLY
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(QuantSurfaceLowest)
            .border(1.dp, QuantSurfaceHigh, RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "GPU LAYER OFFLOAD (N_GPU_LAYERS)",
                    style = QuantTextStyles.LabelSm,
                    color = if (isCpuForced) QuantOutline else QuantEmerald
                )
                Text(
                    text = if (isCpuForced) "0 / ${totalLayers}L (CPU Only)" else "$effectiveLayers / ${totalLayers}L offloaded",
                    style = QuantTextStyles.DataTabular,
                    color = QuantOnSurface
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Auto",
                    style = QuantTextStyles.LabelSm,
                    color = QuantOnSurfaceVariant
                )
                Switch(
                    checked = autoGpuLayerOffload,
                    enabled = !isCpuForced,
                    onCheckedChange = onToggleAuto,
                    modifier = Modifier.testTag("auto_gpu_offload_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = QuantOnEmerald,
                        checkedTrackColor = QuantEmerald,
                        uncheckedThumbColor = QuantOutline,
                        uncheckedTrackColor = QuantSurfaceHigh
                    )
                )
            }
        }

        val sliderValue = if (isCpuForced) 0 else if (autoGpuLayerOffload) recommendedLayers else manualGpuLayers
        Slider(
            value = sliderValue.toFloat(),
            enabled = !isCpuForced && !autoGpuLayerOffload,
            onValueChange = { value ->
                onManualLayersChange(value.roundToInt().coerceIn(0, totalLayers))
            },
            valueRange = 0f..totalLayers.toFloat().coerceAtLeast(1f),
            steps = (totalLayers - 1).coerceAtLeast(0),
            modifier = Modifier.testTag("manual_gpu_layers_slider"),
            colors = SliderDefaults.colors(
                thumbColor = QuantEmerald,
                activeTrackColor = QuantEmerald,
                inactiveTrackColor = QuantSurfaceHigh,
                disabledThumbColor = QuantOutline,
                disabledActiveTrackColor = QuantOutline.copy(alpha = 0.4f)
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Est. GPU: ~${estimatedGpuMb}MB",
                style = QuantTextStyles.DataTabular,
                color = if (effectiveLayers > 0 && !isCpuForced) QuantEmerald else QuantOutline
            )
            Text(
                text = "Est. CPU/KV: ~${estimatedCpuMb}MB",
                style = QuantTextStyles.DataTabular,
                color = QuantOnSurfaceVariant
            )
        }
        Text(
            text = policySummary,
            style = QuantTextStyles.LabelSm,
            color = QuantOutline
        )
    }
}

