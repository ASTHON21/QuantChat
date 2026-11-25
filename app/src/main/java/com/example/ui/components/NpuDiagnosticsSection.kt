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
import com.example.ui.theme.QuantOnSurfaceVariant
import com.example.ui.theme.QuantOutline
import com.example.ui.theme.QuantRose
import com.example.ui.theme.QuantSurfaceContainer
import com.example.ui.theme.QuantSurfaceHigh
import com.example.ui.theme.QuantSurfaceLowest
import com.example.ui.theme.QuantTextStyles
import com.quantchat.engine.core.NpuCompatibilityReport
import com.quantchat.engine.core.NpuProbeResult
import com.quantchat.engine.core.NpuProbeStatus
import com.quantchat.engine.core.NpuVendorFamily

@Composable
fun NpuDiagnosticsSection(
    npuCapabilities: NpuProbeResult?,
    compatibilityReport: NpuCompatibilityReport?,
    onRunNpuProbe: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
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
                text = "NPU · HEXAGON & VENDOR ACCEL",
                style = QuantTextStyles.LabelSm,
                color = if (npuCapabilities?.isVendorRuntimeReady == true) QuantEmerald else QuantOnSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(QuantSurfaceLowest)
                    .border(0.8.dp, QuantEmerald.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .clickable(onClick = onRunNpuProbe)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("run_npu_probe_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Run NPU Probe",
                    style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                    color = QuantEmerald
                )
            }
        }

        if (npuCapabilities != null) {
            NpuDiagRow(
                label = "SoC / Vendor",
                value = buildString {
                    append(npuCapabilities.vendorFamily.label)
                    if (!npuCapabilities.hexagonGenerationHint.isNullOrBlank()) {
                        append(" (${npuCapabilities.hexagonGenerationHint})")
                    } else if (npuCapabilities.chipsetLabel.isNotBlank()) {
                        append(" · ${npuCapabilities.chipsetLabel}")
                    }
                }
            )
            NpuDiagRow(
                label = "Vendor Runtime",
                value = when {
                    npuCapabilities.vendorFamily == NpuVendorFamily.QUALCOMM_HEXAGON ->
                        "${npuCapabilities.status.label} (QNN=${if (npuCapabilities.qnnRuntimePresent) "Y" else "N"} · CDSP=${if (npuCapabilities.cdspRpcAccessible) "Y" else "N"})"
                    else -> npuCapabilities.status.label
                }
            )
            val nnapiText = when {
                !npuCapabilities.supportsNnapi -> "Unavailable"
                npuCapabilities.nnapiDeprecatedOnOs -> "Legacy (Deprecated Android 15+)"
                else -> "Available (${npuCapabilities.supportedPrecisionHints.joinToString("/")})"
            }
            NpuDiagRow(label = "Android NNAPI", value = nnapiText)

            if (npuCapabilities.supportedPrecisionHints.isNotEmpty()) {
                NpuDiagRow(
                    label = "NPU Precision",
                    value = npuCapabilities.supportedPrecisionHints.joinToString(" · ")
                )
            }

            val noteColor = when (npuCapabilities.status) {
                NpuProbeStatus.RUNTIME_READY -> QuantEmerald
                NpuProbeStatus.PROBE_FAILED -> QuantRose
                else -> QuantOutline
            }
            if (!npuCapabilities.reason.isNullOrBlank()) {
                Text(
                    text = npuCapabilities.reason.orEmpty(),
                    style = QuantTextStyles.LabelSm,
                    color = noteColor
                )
            }
        } else {
            NpuDiagRow(label = "NPU Status", value = "Probing SoC & NPU runtime...")
        }

        if (compatibilityReport != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(QuantSurfaceLowest)
                    .border(0.8.dp, QuantSurfaceHigh, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "NPU GATE · ${compatibilityReport.verdictCode.label.uppercase()}",
                        style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                        color = if (compatibilityReport.compatibleWithNpu) QuantEmerald else QuantOnSurfaceVariant
                    )
                    Text(
                        text = "→ ${compatibilityReport.routedFallbackTarget}",
                        style = QuantTextStyles.DataTabular,
                        color = QuantEmerald
                    )
                }
                val gateReason = compatibilityReport.blockingReason ?: compatibilityReport.operatorRequirementsSummary
                Text(
                    text = gateReason,
                    style = QuantTextStyles.LabelSm,
                    color = QuantOutline
                )
            }
        }
    }
}

@Composable
private fun NpuDiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = QuantTextStyles.LabelSm, color = QuantOutline)
        Text(text = value, style = QuantTextStyles.LabelSm, color = QuantOnSurfaceVariant)
    }
}
