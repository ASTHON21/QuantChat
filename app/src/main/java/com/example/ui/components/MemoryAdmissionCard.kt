package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.LocalWeightFile
import com.example.ui.theme.QuantEmerald
import com.example.ui.theme.QuantEmeraldContainer
import com.example.ui.theme.QuantOnEmeraldContainer
import com.example.ui.theme.QuantOnSurface
import com.example.ui.theme.QuantOnSurfaceVariant
import com.example.ui.theme.QuantOutline
import com.example.ui.theme.QuantRose
import com.example.ui.theme.QuantRoseContainer
import com.example.ui.theme.QuantSurfaceContainer
import com.example.ui.theme.QuantSurfaceHigh
import com.example.ui.theme.QuantSurfaceLow
import com.example.ui.theme.QuantSurfaceLowest
import com.example.ui.theme.QuantTextStyles
import com.quantchat.hardware.LiveDeviceMetrics
import com.quantchat.hardware.ProcessMemoryTelemetry
import com.quantchat.resource.AdmissionAssessment
import com.quantchat.resource.AdmissionDowngradeSuggestion
import com.quantchat.resource.AdmissionVerdict
import com.quantchat.resource.MemoryBudgetBreakdown

private val QuantAmber = Color(0xFFF59E0B)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryAdmissionBreakdownStrip(
    assessment: AdmissionAssessment,
    modifier: Modifier = Modifier
) {
    val breakdown = assessment.breakdown ?: return
    val statusColor = when (assessment.verdict) {
        AdmissionVerdict.ALLOWED -> QuantEmerald
        AdmissionVerdict.WARNING -> QuantAmber
        AdmissionVerdict.BLOCKED -> QuantRose
    }
    val statusLabel = when (assessment.verdict) {
        AdmissionVerdict.ALLOWED -> "ADMISSION SAFE"
        AdmissionVerdict.WARNING -> "TIGHT HEADROOM"
        AdmissionVerdict.BLOCKED -> "ADMISSION BLOCKED"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (assessment.verdict == AdmissionVerdict.BLOCKED) {
                    QuantRoseContainer.copy(alpha = 0.35f)
                } else {
                    QuantSurfaceContainer
                }
            )
            .border(
                0.8.dp,
                statusColor.copy(alpha = 0.4f),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 9.dp, vertical = 7.dp)
            .testTag("memory_admission_strip"),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusLabel,
                style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                color = statusColor
            )
            Text(
                text = if (breakdown.systemAvailableMb > 0L) {
                    "M_total ${breakdown.totalRequiredMb}MB / ${breakdown.systemAvailableMb}MB free"
                } else {
                    "M_total ~${breakdown.totalRequiredMb}MB (ctx ${breakdown.activeContextSize})"
                },
                style = QuantTextStyles.DataTabular,
                color = QuantOnSurfaceVariant
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MemoryTermPill("Weights", breakdown.weightsMb)
            MemoryTermPill("KV", breakdown.kvCacheMb)
            MemoryTermPill("Compute", breakdown.computeBufferMb)
            MemoryTermPill("Runtime", breakdown.runtimeMb)
            MemoryTermPill("Reserve", breakdown.reserveMb)
        }

        val telemetry = assessment.processTelemetry
        if (telemetry != null) {
            val rssText = buildString {
                if (telemetry.processTotalPssMb > 0L) append("PSS ${telemetry.processTotalPssMb}MB")
                if (telemetry.processVmRssMb > 0L) {
                    if (isNotEmpty()) append(" · ")
                    append("RSS ${telemetry.processVmRssMb}MB")
                }
                if (telemetry.systemThresholdMb > 0L) {
                    if (isNotEmpty()) append(" · ")
                    append("LMK ${telemetry.systemThresholdMb}MB")
                }
            }
            if (rssText.isNotBlank()) {
                Text(
                    text = rssText,
                    style = QuantTextStyles.LabelSm,
                    color = QuantOutline
                )
            }
        }

        val downgrade = assessment.downgradeSuggestion
        if (assessment.verdict == AdmissionVerdict.BLOCKED && downgrade != null) {
            Text(
                text = "Safe downgrade available: ${downgrade.rationale}",
                style = QuantTextStyles.LabelSm,
                color = QuantRose
            )
        }
    }
}

@Composable
fun ProcessAndAdmissionTelemetrySection(
    liveMetrics: LiveDeviceMetrics?,
    lastAdmission: AdmissionAssessment?,
    contextSize: Int,
    modifier: Modifier = Modifier
) {
    val proc: ProcessMemoryTelemetry? = liveMetrics?.processMemory ?: lastAdmission?.processTelemetry
    val breakdown: MemoryBudgetBreakdown? = lastAdmission?.breakdown

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(QuantSurfaceContainer)
            .border(0.8.dp, QuantSurfaceHigh, RoundedCornerShape(4.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp)
            .testTag("process_memory_telemetry_section"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "MEMORY ADMISSION & PROCESS STATS",
            style = QuantTextStyles.LabelSm,
            color = QuantEmerald
        )

        if (liveMetrics != null) {
            val avail = liveMetrics.availableRamMb
            val nominal = liveMetrics.totalRamMb
            val threshold = proc?.systemThresholdMb ?: 0L
            AdmissionStatRow(
                label = "ActivityManager.availMem",
                value = "${avail}MB free (nominal ${nominal}MB · LMK ${threshold}MB)",
                valueColor = if (liveMetrics.lowMemory) QuantRose else QuantOnSurfaceVariant
            )
        }

        if (proc != null) {
            AdmissionStatRow(
                label = "Process PSS / VmRSS",
                value = "${proc.processTotalPssMb}MB PSS · ${proc.processVmRssMb}MB RSS · Native ${proc.nativeHeapAllocatedMb}MB"
            )
            if (proc.memoryClassMb > 0) {
                AdmissionStatRow(
                    label = "App Heap Class",
                    value = "${proc.memoryClassMb}MB std / ${proc.largeMemoryClassMb}MB large"
                )
            }
        }

        if (breakdown != null) {
            HorizontalDivider(color = QuantSurfaceHigh, thickness = 0.6.dp)
            val verdictColor = when (lastAdmission.verdict) {
                AdmissionVerdict.ALLOWED -> QuantEmerald
                AdmissionVerdict.WARNING -> QuantAmber
                AdmissionVerdict.BLOCKED -> QuantRose
            }
            AdmissionStatRow(
                label = "Model Budget (ctx=${breakdown.activeContextSize})",
                value = "M_total ${breakdown.totalRequiredMb}MB (headroom ${breakdown.safeHeadroomMb}MB)",
                valueColor = verdictColor
            )
            Text(
                text = "W=${breakdown.weightsMb} + KV=${breakdown.kvCacheMb} + C=${breakdown.computeBufferMb} + Rt=${breakdown.runtimeMb} + Res=${breakdown.reserveMb} MB",
                style = QuantTextStyles.DataTabular,
                color = QuantOutline
            )
        } else {
            Text(
                text = "5-Term Formula active for n_ctx=$contextSize: Weights + KV + Compute + Runtime + Reserve (never nominal RAM alone).",
                style = QuantTextStyles.LabelSm,
                color = QuantOutline
            )
        }
    }
}

@Composable
fun MemoryAdmissionBlockedDialog(
    file: LocalWeightFile,
    assessment: AdmissionAssessment,
    onDismiss: () -> Unit,
    onApplyDowngrade: (AdmissionDowngradeSuggestion) -> Unit
) {
    val breakdown = assessment.breakdown
    val telemetry = assessment.processTelemetry
    val suggestion = assessment.downgradeSuggestion

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = QuantSurfaceLow,
        titleContentColor = QuantOnSurface,
        textContentColor = QuantOnSurfaceVariant,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "MEMORY ADMISSION GUARD",
                    style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                    color = QuantRose
                )
                Text(
                    text = "Load Blocked: ${file.displayFileName}",
                    style = QuantTextStyles.HeadlineMd,
                    color = QuantOnSurface
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.testTag("memory_admission_blocked_dialog"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = assessment.blockReason
                        ?: "Estimated runtime footprint exceeds live Android allocatable RAM headroom.",
                    style = QuantTextStyles.BodySm,
                    color = QuantRose
                )

                if (breakdown != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(QuantSurfaceLowest)
                            .border(0.8.dp, QuantSurfaceHigh, RoundedCornerShape(4.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "M_total = Weights + KV + Compute + Runtime + Reserve",
                            style = QuantTextStyles.LabelSm,
                            color = QuantEmerald
                        )
                        AdmissionStatRow("M_weights", "${breakdown.weightsMb} MB")
                        AdmissionStatRow("M_KV (n_ctx=${breakdown.activeContextSize})", "${breakdown.kvCacheMb} MB")
                        AdmissionStatRow("M_compute", "${breakdown.computeBufferMb} MB")
                        AdmissionStatRow("M_runtime", "${breakdown.runtimeMb} MB")
                        AdmissionStatRow("M_reserve (LMK + safety)", "${breakdown.reserveMb} MB")
                        HorizontalDivider(color = QuantSurfaceHigh, thickness = 0.6.dp)
                        AdmissionStatRow(
                            label = "M_total Required",
                            value = "${breakdown.totalRequiredMb} MB",
                            valueColor = QuantRose
                        )
                        AdmissionStatRow(
                            label = "Live ActivityManager availMem",
                            value = "${breakdown.systemAvailableMb} MB (nominal ${breakdown.nominalTotalRamMb} MB)",
                            valueColor = QuantOnSurface
                        )
                    }
                }

                if (telemetry != null) {
                    Text(
                        text = "Live Process: ${telemetry.processTotalPssMb}MB PSS · ${telemetry.processVmRssMb}MB RSS · LMK threshold ${telemetry.systemThresholdMb}MB",
                        style = QuantTextStyles.LabelSm,
                        color = QuantOutline
                    )
                }

                if (suggestion != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(QuantEmerald.copy(alpha = 0.12f))
                            .border(0.8.dp, QuantEmerald.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "RECOMMENDED SAFE DOWNGRADE",
                            style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                            color = QuantEmerald
                        )
                        Text(
                            text = "${suggestion.rationale} · +${suggestion.projectedHeadroomMb}MB safe margin",
                            style = QuantTextStyles.LabelSm,
                            color = QuantOnSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (suggestion != null) {
                Button(
                    onClick = { onApplyDowngrade(suggestion) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("apply_admission_downgrade_button"),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QuantEmeraldContainer,
                        contentColor = QuantOnEmeraldContainer
                    )
                ) {
                    Text(
                        text = "Downgrade & Load",
                        style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            } else {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("Close")
                }
            }
        },
        dismissButton = {
            if (suggestion != null) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("dismiss_admission_blocked_button"),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("Cancel", style = QuantTextStyles.LabelSm, color = QuantOnSurfaceVariant)
                }
            }
        }
    )
}

@Composable
private fun MemoryTermPill(label: String, valueMb: Long) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(QuantSurfaceLowest)
            .border(0.6.dp, QuantSurfaceHigh, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = QuantTextStyles.LabelSm,
            color = QuantOutline
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "${valueMb}M",
            style = QuantTextStyles.DataTabular,
            color = QuantOnSurfaceVariant
        )
    }
}

@Composable
private fun AdmissionStatRow(
    label: String,
    value: String,
    valueColor: Color = QuantOnSurfaceVariant
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = QuantTextStyles.LabelSm, color = QuantOutline)
        Text(text = value, style = QuantTextStyles.DataTabular, color = valueColor)
    }
}
