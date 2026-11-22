package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.AppReleaseInfo
import com.example.ui.theme.QuantEmerald
import com.example.ui.theme.QuantEmeraldContainer
import com.example.ui.theme.QuantOnEmerald
import com.example.ui.theme.QuantOnEmeraldContainer
import com.example.ui.theme.QuantOnSurface
import com.example.ui.theme.QuantOnSurfaceVariant
import com.example.ui.theme.QuantOutline
import com.example.ui.theme.QuantSurfaceContainer
import com.example.ui.theme.QuantSurfaceHigh
import com.example.ui.theme.QuantSurfaceLow
import com.example.ui.theme.QuantSurfaceLowest
import com.example.ui.theme.QuantTextStyles

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppVersionSheet(
    onDismiss: () -> Unit
) {
    val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

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
                        text = "QUANTCHAT RELEASE BUILD",
                        style = QuantTextStyles.LabelSm,
                        color = QuantEmerald
                    )
                    Text(
                        text = "Version ${AppReleaseInfo.versionName} (Build ${AppReleaseInfo.versionCode})",
                        style = QuantTextStyles.HeadlineMd,
                        color = QuantOnSurface
                    )
                }
                Text(
                    text = AppReleaseInfo.buildType.uppercase(),
                    style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                    color = QuantOnEmerald,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(QuantEmerald)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(QuantSurfaceLowest)
                    .border(1.dp, QuantSurfaceHigh, RoundedCornerShape(6.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BuildMetaRow(label = "Commit", value = AppReleaseInfo.gitSha)
                BuildMetaRow(label = "Engine", value = "llama.cpp (llamacpp-kotlin 0.4.0)")
                BuildMetaRow(label = "Device ABI", value = primaryAbi)
                BuildMetaRow(label = "Android API", value = "SDK ${Build.VERSION.SDK_INT}")
            }

            Text(
                text = "ITERATION CHANGELOG",
                style = QuantTextStyles.LabelSm,
                color = QuantOutline
            )

            AppReleaseInfo.changelog.forEach { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(QuantSurfaceContainer)
                        .border(1.dp, QuantSurfaceHigh, RoundedCornerShape(6.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${entry.version} · Build ${entry.buildNumber}",
                            style = QuantTextStyles.LabelLg.copy(fontWeight = FontWeight.SemiBold),
                            color = QuantOnSurface
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = entry.date,
                                style = QuantTextStyles.LabelSm,
                                color = QuantOutline
                            )
                            Text(
                                text = entry.badge,
                                style = QuantTextStyles.LabelSm,
                                color = if (entry.badge == "CURRENT") QuantEmerald else QuantOnSurfaceVariant,
                                modifier = Modifier
                                    .background(QuantSurfaceLowest, RoundedCornerShape(3.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = QuantSurfaceHigh, thickness = 0.8.dp)
                    entry.highlights.forEach { bullet ->
                        Text(
                            text = "• $bullet",
                            style = QuantTextStyles.BodySm,
                            color = QuantOnSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("close_version_sheet_button"),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = QuantEmeraldContainer,
                    contentColor = QuantOnEmeraldContainer
                )
            ) {
                Text(
                    text = "Close",
                    style = QuantTextStyles.LabelLg.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

@Composable
private fun BuildMetaRow(label: String, value: String) {
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
            color = QuantOnSurface
        )
    }
}
