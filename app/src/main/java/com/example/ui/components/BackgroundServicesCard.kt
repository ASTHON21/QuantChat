package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import com.example.ui.theme.QuantSurfaceContainer
import com.example.ui.theme.QuantSurfaceLowest
import com.example.ui.theme.QuantTextStyles
import com.quantchat.integration.BackgroundServicesStatus
import com.quantchat.integration.ForegroundLaunchOutcome

@Composable
fun BackgroundServicesCard(
    status: BackgroundServicesStatus,
    onRunMaintenanceNow: () -> Unit,
    onRequestNotificationsPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(QuantSurfaceContainer)
            .padding(horizontal = 9.dp, vertical = 8.dp)
            .testTag("background_services_card"),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BACKGROUND & FOREGROUND SERVICES",
                style = QuantTextStyles.LabelSm,
                color = QuantEmerald
            )
            Text(
                text = "Run Maintenance",
                style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                color = QuantEmerald,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(QuantSurfaceLowest)
                    .border(0.8.dp, QuantEmerald.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .clickable(onClick = onRunMaintenanceNow)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .testTag("run_maintenance_button")
            )
        }

        val dl = status.downloadService
        val dlLabel = when {
            dl.foregroundActive -> "FGS dataSync Active · ${dl.statusText}"
            dl.lastLaunchOutcome == ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION ->
                "Blocked by OS Background FGS Policy"
            else -> dl.statusText
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "ModelDownloadService", style = QuantTextStyles.LabelSm, color = QuantOutline)
            Text(
                text = dlLabel,
                style = QuantTextStyles.DataTabular,
                color = if (dl.foregroundActive) QuantEmerald else QuantOnSurfaceVariant
            )
        }

        val inf = status.inferenceService
        val infLabel = when {
            inf.foregroundActive -> "FGS specialUse Active · ${inf.phaseLabel}"
            inf.lastLaunchOutcome == ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION ->
                "Blocked by OS Background FGS Policy"
            else -> inf.phaseLabel
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "InferenceForegroundService", style = QuantTextStyles.LabelSm, color = QuantOutline)
            Text(
                text = infLabel,
                style = QuantTextStyles.DataTabular,
                color = if (inf.foregroundActive) QuantEmerald else QuantOnSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "ModelMaintenanceWorker", style = QuantTextStyles.LabelSm, color = QuantOutline)
            Text(
                text = status.maintenanceStateLabel,
                style = QuantTextStyles.DataTabular,
                color = if (status.lastMaintenanceReport?.deferredByThermal == true) QuantRose else QuantOnSurface
            )
        }

        val report = status.lastMaintenanceReport
        if (report != null) {
            Text(
                text = report.summary,
                style = QuantTextStyles.LabelSm,
                color = if (report.corruptGgufCount > 0 || report.deferredByThermal) QuantRose else QuantOutline
            )
        } else {
            Text(
                text = status.nextScheduledMaintenanceNote,
                style = QuantTextStyles.LabelSm,
                color = QuantOutline
            )
        }

        if (!status.notificationsPermissionGranted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(QuantRose.copy(alpha = 0.14f))
                    .border(0.8.dp, QuantRose.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "POST_NOTIFICATIONS denied — allow alerts for visible FGS progress",
                    style = QuantTextStyles.LabelSm,
                    color = QuantRose,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Text(
                    text = "Allow",
                    style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                    color = QuantOnSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(QuantRose)
                        .clickable(onClick = onRequestNotificationsPermission)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("enable_notifications_button")
                )
            }
        }
    }
}
