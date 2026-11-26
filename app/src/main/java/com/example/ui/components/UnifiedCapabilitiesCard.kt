package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.QuantEmerald
import com.example.ui.theme.QuantOnSurface
import com.example.ui.theme.QuantOnSurfaceVariant
import com.example.ui.theme.QuantOutline
import com.example.ui.theme.QuantRose
import com.example.ui.theme.QuantRoseContainer
import com.example.ui.theme.QuantSurfaceContainer
import com.example.ui.theme.QuantSurfaceHigh
import com.example.ui.theme.QuantSurfaceLowest
import com.example.ui.theme.QuantTextStyles
import com.quantchat.engine.core.AcceleratorBackendType
import com.quantchat.engine.core.AcceleratorPreference
import com.quantchat.engine.core.BackendRuntimeStatus
import com.quantchat.engine.core.DeviceCapabilities
import com.quantchat.engine.core.DeviceCapabilitiesPolicy
import com.quantchat.engine.core.MmapLifecycleSnapshot
import java.util.Locale

@Composable
fun UnifiedCapabilitiesCard(
    deviceCapabilities: DeviceCapabilities?,
    backendStatus: BackendRuntimeStatus,
    acceleratorPreference: AcceleratorPreference,
    onRefreshCapabilities: () -> Unit,
    mmapLifecycle: MmapLifecycleSnapshot? = null,
    modifier: Modifier = Modifier
) {
    val thermalLabel = deviceCapabilities?.let { thermalStatusLabel(it.thermalStatus) } ?: "Unknown"
    val isThermallyThrottled = deviceCapabilities?.isThermallyThrottled == true
    val gateResult = deviceCapabilities?.let {
        DeviceCapabilitiesPolicy.evaluateGpuEligibility(it, acceleratorPreference)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(QuantSurfaceContainer)
            .border(0.8.dp, QuantSurfaceHigh, RoundedCornerShape(4.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp)
            .testTag("unified_device_capabilities_card"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "UNIFIED DEVICE CAPABILITIES",
                    style = QuantTextStyles.LabelSm,
                    color = QuantEmerald
                )
                Text(
                    text = "HardwareManager -> Engine Scheduler",
                    style = QuantTextStyles.LabelSm,
                    color = QuantOutline
                )
            }
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(QuantSurfaceLowest)
                    .border(0.8.dp, QuantEmerald.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .clickable(onClick = onRefreshCapabilities)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("refresh_device_capabilities_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Refresh",
                    style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                    color = QuantEmerald
                )
            }
        }

        if (deviceCapabilities != null) {
            CapRow(
                label = "cpuCores",
                value = "${deviceCapabilities.cpuCores} cores"
            )
            CapRow(
                label = "cpuFeatures",
                value = if (deviceCapabilities.cpuFeatures.isNotEmpty()) {
                    deviceCapabilities.cpuFeatures.sorted().joinToString(" · ")
                } else {
                    "Standard SIMD"
                }
            )
            CapRow(
                label = "gpuBackends",
                value = if (deviceCapabilities.gpuBackends.isNotEmpty()) {
                    deviceCapabilities.gpuBackends.sorted().joinToString(" · ")
                } else {
                    "None (CPU Fallback)"
                },
                valueColor = if (deviceCapabilities.hasVulkanGpu) QuantEmerald else QuantOnSurfaceVariant
            )
            CapRow(
                label = "npuAvailable",
                value = if (deviceCapabilities.npuAvailable) "true (Vendor Runtime Ready)" else "false (GGUF -> GPU/CPU)",
                valueColor = if (deviceCapabilities.npuAvailable) QuantEmerald else QuantOnSurfaceVariant
            )
            CapRow(
                label = "availableMemoryBytes",
                value = formatMemorySnapshot(deviceCapabilities.availableMemoryBytes)
            )
            CapRow(
                label = "thermalStatus",
                value = "$thermalLabel (${deviceCapabilities.thermalStatus})",
                valueColor = if (isThermallyThrottled) QuantRose else QuantOnSurfaceVariant
            )
            if (mmapLifecycle != null) {
                val mmapText = if (mmapLifecycle.totalActiveMappings > 0) {
                    "${mmapLifecycle.totalActiveMappings} active lease(s) · FileLock"
                } else {
                    "0 active leases (unmapped)"
                }
                CapRow(
                    label = "mmapLifecycle",
                    value = mmapText,
                    valueColor = if (mmapLifecycle.totalActiveMappings > 0) QuantEmerald else QuantOnSurfaceVariant
                )
            }

            // Live Scheduling Linkage Banner
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isThermallyThrottled) QuantRoseContainer.copy(alpha = 0.45f) else QuantSurfaceLowest)
                    .border(
                        0.8.dp,
                        if (isThermallyThrottled) QuantRose.copy(alpha = 0.45f) else QuantSurfaceHigh,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isThermallyThrottled) {
                            "SCHEDULER · THERMAL GUARD ACTIVE"
                        } else if (gateResult?.gpuEligible == true) {
                            "SCHEDULER · GPU ROUTING ELIGIBLE"
                        } else {
                            "SCHEDULER · CPU ROUTING"
                        },
                        style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                        color = when {
                            isThermallyThrottled -> QuantRose
                            gateResult?.gpuEligible == true -> QuantEmerald
                            else -> QuantOnSurface
                        }
                    )
                    Text(
                        text = backendStatus.statusSummary,
                        style = QuantTextStyles.DataTabular,
                        color = if (backendStatus.activeBackend == AcceleratorBackendType.VULKAN_GPU) {
                            QuantEmerald
                        } else {
                            QuantOnSurfaceVariant
                        }
                    )
                }
                val linkDetail = when {
                    deviceCapabilities.isSevereThermal ->
                        "Severe thermal load detected: Vulkan offload blocked & inference threads capped."
                    isThermallyThrottled ->
                        "Moderate thermal status: automatic thread downshift & BACKGROUND priority applied."
                    gateResult != null && !gateResult.gpuEligible && !gateResult.reason.isNullOrBlank() ->
                        gateResult.reason.orEmpty()
                    else ->
                        "Live capabilities synced across hardware probe, admission gate, and engine router."
                }
                Text(
                    text = linkDetail,
                    style = QuantTextStyles.LabelSm,
                    color = if (isThermallyThrottled) QuantRose else QuantOutline
                )
            }
        } else {
            CapRow(label = "Status", value = "Collecting unified DeviceCapabilities snapshot...")
        }
    }
}

@Composable
private fun CapRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = QuantOnSurfaceVariant
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = QuantTextStyles.LabelSm,
            color = QuantOutline
        )
        Text(
            text = value,
            style = QuantTextStyles.DataTabular,
            color = valueColor
        )
    }
}

private fun formatMemorySnapshot(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val mb = bytes / (1024L * 1024L)
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return String.format(Locale.US, "%d MB (%.2f GB)", mb, gb)
}

private fun thermalStatusLabel(statusCode: Int): String = when (statusCode) {
    DeviceCapabilities.THERMAL_STATUS_NOMINAL -> "Nominal"
    DeviceCapabilities.THERMAL_STATUS_LIGHT -> "Light"
    DeviceCapabilities.THERMAL_STATUS_MODERATE -> "Moderate"
    DeviceCapabilities.THERMAL_STATUS_SEVERE -> "Severe"
    DeviceCapabilities.THERMAL_STATUS_CRITICAL -> "Critical"
    else -> "Unknown"
}
