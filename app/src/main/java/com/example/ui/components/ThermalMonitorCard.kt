package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.quantchat.engine.core.DeviceCapabilities
import com.quantchat.engine.core.ThermalInferenceAction
import com.quantchat.hardware.ThermalSeverity
import com.quantchat.resource.ThermalRuntimeActionState
import java.util.Locale

private val ThermalAmber = Color(0xFFF59E0B)

@Composable
fun ThermalStatusMatrixCard(
    thermalState: ThermalRuntimeActionState,
    modifier: Modifier = Modifier
) {
    val accent = when (thermalState.severity) {
        ThermalSeverity.CRITICAL, ThermalSeverity.SEVERE -> QuantRose
        ThermalSeverity.MODERATE -> ThermalAmber
        ThermalSeverity.NOMINAL, ThermalSeverity.LIGHT, ThermalSeverity.UNAVAILABLE -> QuantEmerald
    }

    val tierRows = listOf(
        Triple(
            "NONE / LIGHT",
            "Normal inference",
            thermalState.action == ThermalInferenceAction.NORMAL
        ),
        Triple(
            "MODERATE",
            "Reduce threads or GPU load",
            thermalState.action == ThermalInferenceAction.REDUCE_THREADS_AND_GPU
        ),
        Triple(
            "SEVERE",
            "Throttle speed · pause non-essential tasks",
            thermalState.action == ThermalInferenceAction.THROTTLE_SPEED_AND_PAUSE_NON_ESSENTIAL
        ),
        Triple(
            "CRITICAL",
            "Stop inference & safely release resources",
            thermalState.action == ThermalInferenceAction.STOP_AND_RELEASE
        )
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(QuantSurfaceContainer)
            .border(0.8.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp)
            .testTag("thermal_governance_card"),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "THERMAL MONITOR · POWERMANAGER API",
                style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                color = accent
            )
            Text(
                text = "${thermalState.severity.label.uppercase(Locale.US)} (${thermalState.severity.statusCode})",
                style = QuantTextStyles.DataTabular,
                color = accent
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Active Action", style = QuantTextStyles.LabelSm, color = QuantOutline)
            Text(
                text = "${thermalState.action.title} · ${thermalState.threadCap}T · GPU ${(thermalState.gpuLoadScaleFactor * 100).toInt()}%",
                style = QuantTextStyles.DataTabular,
                color = QuantOnSurface
            )
        }

        if (thermalState.tokenPacingDelayMs > 0L || thermalState.nonEssentialTasksPaused || thermalState.inferenceBlocked) {
            val detail = buildList {
                if (thermalState.tokenPacingDelayMs > 0L) add("Pacing ${thermalState.tokenPacingDelayMs}ms/tok")
                if (thermalState.nonEssentialTasksPaused) add("Downloads/Bench paused")
                if (thermalState.inferenceBlocked) add("Inference halted")
                if (thermalState.emergencyReleaseCount > 0) add("Releases: ${thermalState.emergencyReleaseCount}")
            }.joinToString(" · ")
            Text(
                text = detail,
                style = QuantTextStyles.LabelSm,
                color = QuantRose
            )
        }

        thermalState.thermalHeadroomForecast?.let { headroom ->
            Text(
                text = String.format(Locale.US, "PowerManager 10s thermal headroom: %.2f", headroom),
                style = QuantTextStyles.LabelSm,
                color = QuantOutline
            )
        }

        HorizontalDivider(color = QuantSurfaceHigh, thickness = 0.6.dp)

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            tierRows.forEach { (tierLabel, behavior, isCurrent) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isCurrent) accent.copy(alpha = 0.14f) else QuantSurfaceLowest)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isCurrent) "▶ $tierLabel" else tierLabel,
                        style = QuantTextStyles.LabelSm.copy(
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = if (isCurrent) accent else QuantOnSurfaceVariant
                    )
                    Text(
                        text = behavior,
                        style = QuantTextStyles.LabelSm,
                        color = if (isCurrent) QuantOnSurface else QuantOutline
                    )
                }
            }
        }
    }
}

@Composable
fun ChatThermalGuardBanner(
    thermalState: ThermalRuntimeActionState,
    modifier: Modifier = Modifier
) {
    if (!thermalState.autoProtectionEnabled ||
        thermalState.severity.statusCode < DeviceCapabilities.THERMAL_STATUS_MODERATE
    ) {
        return
    }

    val isSevereOrCritical = thermalState.severity.statusCode >= DeviceCapabilities.THERMAL_STATUS_SEVERE
    val bannerColor = if (isSevereOrCritical) QuantRose else ThermalAmber
    val bgColor = if (isSevereOrCritical) QuantRoseContainer.copy(alpha = 0.5f) else ThermalAmber.copy(alpha = 0.12f)
    val headline = when (thermalState.severity) {
        ThermalSeverity.CRITICAL -> "CRITICAL TEMP · INFERENCE STOPPED & MEMORY RELEASED"
        ThermalSeverity.SEVERE -> "SEVERE TEMP · SPEED THROTTLED & NON-ESSENTIAL PAUSED"
        ThermalSeverity.MODERATE -> "MODERATE TEMP · THREADS & GPU LOAD REDUCED"
        else -> "THERMAL GUARD ACTIVE"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .border(0.6.dp, bannerColor.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .testTag("chat_thermal_guard_banner"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = headline,
            style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
            color = bannerColor
        )
        Text(
            text = thermalState.lastThermalEventNote ?: thermalState.severity.recommendedBehaviorSummary,
            style = QuantTextStyles.LabelSm,
            color = QuantOnSurfaceVariant
        )
    }
}
