package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.ui.theme.QuantSurfaceContainer
import com.example.ui.theme.QuantSurfaceHigh
import com.example.ui.theme.QuantSurfaceLow
import com.example.ui.theme.QuantSurfaceLowest
import com.example.ui.theme.QuantTextStyles
import java.util.Locale

private val SYSTEM_PROMPT_PRESETS = listOf(
    "General" to InferenceSettings.DEFAULT_SYSTEM_PROMPT,
    "Coding" to "You are an expert software engineer. Provide clean, accurate code and concise technical explanations without filler.",
    "Analyst" to "You are a quantitative and technical analyst. Use structured reasoning, clear numbers, and concise bullet points."
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceSettingsSheet(
    currentSettings: InferenceSettings,
    onDismiss: () -> Unit,
    onApply: (InferenceSettings) -> Unit,
    onOpenVersionInfo: () -> Unit = {}
) {
    var systemPrompt by remember(currentSettings) { mutableStateOf(currentSettings.systemPrompt) }
    var threads by remember(currentSettings) { mutableIntStateOf(currentSettings.threads) }
    var contextSize by remember(currentSettings) { mutableIntStateOf(currentSettings.contextSize) }
    var maxTokens by remember(currentSettings) { mutableIntStateOf(currentSettings.maxTokens) }
    var temperature by remember(currentSettings) { mutableFloatStateOf(currentSettings.temperature) }

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

            // CPU Threads
            ChipSelectorRow(
                label = "CPU THREADS",
                options = listOf(2, 4, 6, 8),
                selected = threads,
                formatter = { "${it}T" },
                onSelect = { threads = it }
            )

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
                text = "Changing CPU threads or context window will automatically reload an active model.",
                style = QuantTextStyles.BodySm,
                color = QuantOutline
            )

            Spacer(modifier = Modifier.height(2.dp))

            Button(
                onClick = {
                    onApply(
                        currentSettings.copy(
                            systemPrompt = systemPrompt.trim().ifEmpty {
                                InferenceSettings.DEFAULT_SYSTEM_PROMPT
                            },
                            threads = threads,
                            contextSize = contextSize,
                            maxTokens = maxTokens,
                            temperature = temperature
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
