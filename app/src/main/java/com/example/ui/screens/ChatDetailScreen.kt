package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ChatMessage
import com.example.data.ChatRole
import com.example.data.ChatThread
import com.example.data.InferenceSettings
import com.example.data.LocalRuntimeState
import com.example.data.LocalWeightFile
import com.example.ui.theme.QuantEmerald
import com.example.ui.theme.QuantEmeraldContainer
import com.example.ui.theme.QuantError
import com.example.ui.theme.QuantOnEmeraldContainer
import com.example.ui.theme.QuantOnSurface
import com.example.ui.theme.QuantOnSurfaceVariant
import com.example.ui.theme.QuantOutline
import com.example.ui.theme.QuantRose
import com.example.ui.theme.QuantRoseContainer
import com.example.ui.theme.QuantSurface
import com.example.ui.theme.QuantSurfaceContainer
import com.example.ui.theme.QuantSurfaceHigh
import com.example.ui.theme.QuantSurfaceHighest
import com.example.ui.theme.QuantSurfaceLow
import com.example.ui.theme.QuantSurfaceLowest
import com.example.ui.theme.QuantTextStyles
import com.quantchat.engine.core.AcceleratorBackendType
import com.quantchat.engine.core.AcceleratorPreference
import com.quantchat.engine.core.CpuScheduleMode
import com.quantchat.resource.OrchestratorSnapshot
import java.util.Locale

private val STARTER_PROMPTS = listOf(
    "Explain Q4_K_M vs Q8_0 GGUF quantization trade-offs",
    "Write a concise Kotlin coroutine retry helper",
    "Summarize the Kelly Criterion for position sizing"
)

@Composable
fun ChatDetailScreen(
    thread: ChatThread,
    messages: List<ChatMessage>,
    streamingMessage: ChatMessage?,
    runtimeState: LocalRuntimeState,
    readyLocalFiles: List<LocalWeightFile>,
    inferenceSettings: InferenceSettings,
    isGenerating: Boolean,
    orchestratorSnapshot: OrchestratorSnapshot? = null,
    initialDraftText: String? = null,
    onDraftConsumed: () -> Unit = {},
    onBack: () -> Unit,
    onLoadLocalModel: (LocalWeightFile) -> Unit,
    onUnloadLocalModel: () -> Unit,
    onClearMessages: () -> Unit,
    onOpenInferenceSettings: () -> Unit,
    onGoToModels: () -> Unit,
    onSelectScheduleMode: (CpuScheduleMode) -> Unit = {},
    onToggleAutoThermal: (Boolean) -> Unit = {},
    onSelectAcceleratorPreference: (AcceleratorPreference) -> Unit = {},
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onCopiedMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var scheduleMenuExpanded by remember { mutableStateOf(false) }
    var acceleratorMenuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(initialDraftText) {
        if (!initialDraftText.isNullOrBlank()) {
            inputText = initialDraftText
            onDraftConsumed()
        }
    }

    val combinedMessages = remember(messages, streamingMessage, thread.id) {
        if (streamingMessage != null &&
            streamingMessage.threadId == thread.id &&
            messages.none { it.id == streamingMessage.id }
        ) {
            messages + streamingMessage
        } else {
            messages
        }
    }

    LaunchedEffect(combinedMessages.size, streamingMessage?.text?.length) {
        if (combinedMessages.isNotEmpty()) {
            listState.scrollToItem(combinedMessages.lastIndex)
        }
    }

    val activeFileName = when (runtimeState) {
        is LocalRuntimeState.Ready -> runtimeState.file.displayFileName
        is LocalRuntimeState.Loading -> "Loading ${runtimeState.file.displayFileName}..."
        else -> "Select Local GGUF"
    }

    val isReady = runtimeState is LocalRuntimeState.Ready

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(QuantSurface),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
        ) {
            // Top App Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(QuantSurface.copy(alpha = 0.94f))
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("chat_detail_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Chats",
                                tint = QuantOnSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        Box(modifier = Modifier.weight(1f, fill = false)) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(QuantSurfaceContainer)
                                    .clickable { modelDropdownExpanded = true }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (runtimeState) {
                                                is LocalRuntimeState.Ready -> QuantEmerald
                                                is LocalRuntimeState.Error -> QuantError
                                                else -> QuantOutline
                                            }
                                        )
                                )
                                Text(
                                    text = activeFileName,
                                    style = QuantTextStyles.LabelMd.copy(fontWeight = FontWeight.Medium),
                                    color = QuantOnSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Icon(
                                    imageVector = Icons.Filled.ExpandMore,
                                    contentDescription = "Select GGUF Model",
                                    tint = QuantOutline,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = modelDropdownExpanded,
                                onDismissRequest = { modelDropdownExpanded = false },
                                modifier = Modifier.background(QuantSurfaceLow)
                            ) {
                                if (readyLocalFiles.isEmpty()) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "No downloaded GGUF files",
                                                style = QuantTextStyles.BodySm,
                                                color = QuantOutline
                                            )
                                        },
                                        onClick = {
                                            modelDropdownExpanded = false
                                            onGoToModels()
                                        }
                                    )
                                } else {
                                    readyLocalFiles.forEach { file ->
                                        val isActive = (runtimeState as? LocalRuntimeState.Ready)?.file?.diskFileName == file.diskFileName
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = file.displayFileName,
                                                            style = QuantTextStyles.LabelMd,
                                                            color = if (isActive) QuantEmerald else QuantOnSurface,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = "${file.quantTag} · ${formatWeightSize(file.sizeBytes)}",
                                                            style = QuantTextStyles.LabelSm,
                                                            color = QuantOutline
                                                        )
                                                    }
                                                    if (isActive) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Check,
                                                            contentDescription = "Active",
                                                            tint = QuantEmerald,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                modelDropdownExpanded = false
                                                if (!isActive) onLoadLocalModel(file)
                                            }
                                        )
                                    }
                                    if (isReady) {
                                        HorizontalDivider(color = QuantSurfaceHigh, thickness = 0.8.dp)
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Unload Active Model",
                                                    style = QuantTextStyles.LabelMd,
                                                    color = QuantRose
                                                )
                                            },
                                            onClick = {
                                                modelDropdownExpanded = false
                                                onUnloadLocalModel()
                                            }
                                        )
                                    }
                                }
                                HorizontalDivider(color = QuantSurfaceHigh, thickness = 0.8.dp)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Browse / Download Models →",
                                            style = QuantTextStyles.LabelMd,
                                            color = QuantEmerald
                                        )
                                    },
                                    onClick = {
                                        modelDropdownExpanded = false
                                        onGoToModels()
                                    }
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (messages.isNotEmpty()) {
                            IconButton(
                                onClick = onClearMessages,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("chat_detail_clear_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteSweep,
                                    contentDescription = "Clear Messages",
                                    tint = QuantOutline,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = onOpenInferenceSettings,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("chat_detail_tune_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = "Inference Settings",
                                tint = QuantOnSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = QuantSurfaceHighest, thickness = 0.8.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(QuantSurfaceLowest)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val budget = orchestratorSnapshot?.lastContextBudget
                    val leftTelemetry = buildString {
                        if (budget != null) {
                            append("CTX ${budget.promptTokens}/${budget.contextLimit}")
                            if (budget.kvCacheHit) append(" · KV")
                        } else {
                            append("CTX ${inferenceSettings.contextSize}")
                        }
                        append(" · T=${String.format(Locale.US, "%.2f", inferenceSettings.temperature)}")
                    }
                    Text(
                        text = leftTelemetry,
                        style = QuantTextStyles.LabelSm.copy(letterSpacing = 0.3.sp),
                        color = QuantOutline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val backendStatus = orchestratorSnapshot?.backendRuntimeStatus
                        val isGpuActive = backendStatus?.activeBackend == AcceleratorBackendType.VULKAN_GPU
                        val isFallback = backendStatus?.fallbackOccurred == true
                        val accelLabel = when {
                            isGpuActive -> {
                                val applied = backendStatus?.appliedGpuLayers ?: 0
                                val total = backendStatus?.totalModelLayers ?: 0
                                if (total > 0 && applied >= total) "GPU · VK FULL"
                                else if (total > 0) "GPU · VK ${applied}/${total}L"
                                else "GPU · VK ${applied}L"
                            }
                            isFallback -> "CPU · VK FALLBACK"
                            else -> "CPU · SAFE"
                        }
                        val accelColor = when {
                            isGpuActive -> QuantEmerald
                            isFallback -> QuantRose
                            else -> QuantOnSurfaceVariant
                        }
                        val accelBg = when {
                            isGpuActive -> QuantEmerald.copy(alpha = 0.14f)
                            isFallback -> QuantRoseContainer
                            else -> QuantSurfaceContainer
                        }
                        val accelBorder = when {
                            isGpuActive -> QuantEmerald.copy(alpha = 0.4f)
                            isFallback -> QuantRose.copy(alpha = 0.45f)
                            else -> QuantSurfaceHigh
                        }

                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(accelBg)
                                    .border(0.8.dp, accelBorder, RoundedCornerShape(4.dp))
                                    .clickable { acceleratorMenuExpanded = true }
                                    .padding(horizontal = 7.dp, vertical = 4.dp)
                                    .testTag("chat_accelerator_pill"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = accelLabel,
                                    style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                                    color = accelColor,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Filled.ExpandMore,
                                    contentDescription = "Select Accelerator Backend",
                                    tint = accelColor,
                                    modifier = Modifier.size(12.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = acceleratorMenuExpanded,
                                onDismissRequest = { acceleratorMenuExpanded = false },
                                modifier = Modifier.background(QuantSurfaceLow)
                            ) {
                                val probe = orchestratorSnapshot?.vulkanCapabilities
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    Text(
                                        text = if (isGpuActive) {
                                            "BACKEND · ${backendStatus?.activeDeviceLabel ?: "VULKAN GPU"}"
                                        } else {
                                            "BACKEND · ${backendStatus?.statusSummary ?: "CPU SAFE"}"
                                        },
                                        style = QuantTextStyles.LabelSm,
                                        color = accelColor
                                    )
                                    val detailText = when {
                                        isFallback && !backendStatus?.fallbackReason.isNullOrBlank() ->
                                            "Fallback: ${backendStatus?.fallbackReason}"
                                        probe != null ->
                                            probe.deviceName?.let { "$it · ${probe.selfTestSummary}" } ?: probe.selfTestSummary
                                        else -> "Vulkan capability probe pending"
                                    }
                                    Text(
                                        text = detailText,
                                        style = QuantTextStyles.BodySm,
                                        color = if (isFallback) QuantRose else QuantOutline
                                    )
                                }
                                HorizontalDivider(color = QuantSurfaceHigh, thickness = 0.8.dp)

                                AcceleratorPreference.entries.forEach { pref ->
                                    val selected = inferenceSettings.acceleratorPreference == pref
                                    DropdownMenuItem(
                                        modifier = Modifier.testTag("accel_menu_${pref.name.lowercase(Locale.US)}"),
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = pref.label,
                                                        style = QuantTextStyles.LabelMd,
                                                        color = if (selected) QuantEmerald else QuantOnSurface
                                                    )
                                                    Text(
                                                        text = pref.description,
                                                        style = QuantTextStyles.LabelSm,
                                                        color = QuantOutline
                                                    )
                                                }
                                                if (selected) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = "Selected Backend",
                                                        tint = QuantEmerald,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            acceleratorMenuExpanded = false
                                            onSelectAcceleratorPreference(pref)
                                        }
                                    )
                                }
                                HorizontalDivider(color = QuantSurfaceHigh, thickness = 0.8.dp)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "GPU Offload & Vulkan Probe →",
                                            style = QuantTextStyles.LabelMd,
                                            color = QuantEmerald
                                        )
                                    },
                                    onClick = {
                                        acceleratorMenuExpanded = false
                                        onOpenInferenceSettings()
                                    }
                                )
                            }
                        }

                        val decision = orchestratorSnapshot?.schedulingDecision
                        val effectiveMode = decision?.effectiveMode ?: inferenceSettings.scheduleMode
                        val effectiveThreads = decision?.effectiveThreads ?: inferenceSettings.threads
                        val isThrottled = decision?.isThermallyThrottled == true
                        val pillLabel = if (isThrottled && decision?.requestedMode != CpuScheduleMode.THERMAL_SAFE) {
                            "THERMAL SAFE · ${effectiveThreads}T (AUTO)"
                        } else {
                            "${effectiveMode.label.uppercase(Locale.US)} · ${effectiveThreads}T"
                        }
                        val pillTextColor = when {
                            isThrottled || effectiveMode == CpuScheduleMode.THERMAL_SAFE -> QuantRose
                            effectiveMode == CpuScheduleMode.ECO -> QuantOnSurfaceVariant
                            else -> QuantEmerald
                        }
                        val pillBg = when {
                            isThrottled || effectiveMode == CpuScheduleMode.THERMAL_SAFE -> QuantRoseContainer
                            effectiveMode == CpuScheduleMode.ECO -> QuantSurfaceContainer
                            else -> QuantEmerald.copy(alpha = 0.12f)
                        }
                        val pillBorder = when {
                            isThrottled || effectiveMode == CpuScheduleMode.THERMAL_SAFE -> QuantRose.copy(alpha = 0.45f)
                            effectiveMode == CpuScheduleMode.ECO -> QuantSurfaceHigh
                            else -> QuantEmerald.copy(alpha = 0.35f)
                        }

                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(pillBg)
                                    .border(0.8.dp, pillBorder, RoundedCornerShape(4.dp))
                                    .clickable { scheduleMenuExpanded = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .testTag("chat_cpu_profile_pill"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = pillLabel,
                                    style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                                    color = pillTextColor,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Filled.ExpandMore,
                                    contentDescription = "Select CPU Schedule Mode",
                                    tint = pillTextColor,
                                    modifier = Modifier.size(12.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = scheduleMenuExpanded,
                                onDismissRequest = { scheduleMenuExpanded = false },
                                modifier = Modifier.background(QuantSurfaceLow)
                            ) {
                                val rationale = decision?.rationale ?: "Dynamic per-request n_threads"
                                val thermalLabel = orchestratorSnapshot?.liveMetrics?.thermalSeverity?.label ?: "Unknown"
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    Text(
                                        text = "CPU SCHEDULER · THERMAL: ${thermalLabel.uppercase(Locale.US)}",
                                        style = QuantTextStyles.LabelSm,
                                        color = if (isThrottled) QuantRose else QuantEmerald
                                    )
                                    Text(
                                        text = rationale,
                                        style = QuantTextStyles.BodySm,
                                        color = QuantOutline
                                    )
                                }
                                HorizontalDivider(color = QuantSurfaceHigh, thickness = 0.8.dp)

                                CpuScheduleMode.entries.forEach { mode ->
                                    val isRequested = inferenceSettings.scheduleMode == mode
                                    val previewThreads = orchestratorSnapshot?.modeThreadPreview?.get(mode)
                                    val modeColor = if (mode == CpuScheduleMode.THERMAL_SAFE) QuantRose else QuantEmerald
                                    DropdownMenuItem(
                                        modifier = Modifier.testTag("schedule_menu_mode_${mode.name.lowercase(Locale.US)}"),
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = if (previewThreads != null) {
                                                            "${mode.label} · ${previewThreads}T"
                                                        } else {
                                                            mode.label
                                                        },
                                                        style = QuantTextStyles.LabelMd,
                                                        color = if (isRequested) modeColor else QuantOnSurface
                                                    )
                                                    Text(
                                                        text = mode.scenarioHint,
                                                        style = QuantTextStyles.LabelSm,
                                                        color = QuantOutline
                                                    )
                                                }
                                                if (isRequested) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = "Selected Mode",
                                                        tint = modeColor,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            scheduleMenuExpanded = false
                                            onSelectScheduleMode(mode)
                                        }
                                    )
                                }

                                HorizontalDivider(color = QuantSurfaceHigh, thickness = 0.8.dp)
                                DropdownMenuItem(
                                    modifier = Modifier.testTag("schedule_menu_auto_thermal_toggle"),
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    text = "Auto Thermal Safe",
                                                    style = QuantTextStyles.LabelMd,
                                                    color = QuantOnSurface
                                                )
                                                Text(
                                                    text = "Auto-downshift at Moderate+ temp",
                                                    style = QuantTextStyles.LabelSm,
                                                    color = QuantOutline
                                                )
                                            }
                                            Text(
                                                text = if (inferenceSettings.autoThermalDownshift) "ON" else "OFF",
                                                style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                                                color = if (inferenceSettings.autoThermalDownshift) QuantEmerald else QuantOutline
                                            )
                                        }
                                    },
                                    onClick = {
                                        onToggleAutoThermal(!inferenceSettings.autoThermalDownshift)
                                    }
                                )
                            }
                        }

                        Text(
                            text = when (runtimeState) {
                                is LocalRuntimeState.Ready -> "OFFLINE"
                                is LocalRuntimeState.Loading -> "LOADING"
                                is LocalRuntimeState.Error -> "ERROR"
                                is LocalRuntimeState.Unloaded -> "IDLE"
                            },
                            style = QuantTextStyles.LabelSm,
                            color = when (runtimeState) {
                                is LocalRuntimeState.Ready -> QuantEmerald
                                is LocalRuntimeState.Error -> QuantError
                                else -> QuantOutline
                            }
                        )
                    }
                }
            }

            // Chat Message Stream
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (runtimeState !is LocalRuntimeState.Ready) {
                    item(key = "runtime_readiness_card") {
                        RuntimeReadinessCard(
                            runtimeState = runtimeState,
                            readyLocalFiles = readyLocalFiles,
                            onLoadModel = onLoadLocalModel,
                            onGoToModels = onGoToModels
                        )
                    }
                }

                if (combinedMessages.isEmpty() && isReady) {
                    item(key = "empty_ready_card") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(QuantSurfaceContainer)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "LOCAL ENGINE READY",
                                style = QuantTextStyles.LabelSm,
                                color = QuantEmerald
                            )
                            Text(
                                text = "Running ${activeFileName} offline via bundled llama.cpp. Ask anything or pick a starter prompt:",
                                style = QuantTextStyles.BodySm,
                                color = QuantOnSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                STARTER_PROMPTS.forEach { sample ->
                                    Text(
                                        text = sample,
                                        style = QuantTextStyles.LabelSm,
                                        color = QuantOnSurface,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(QuantSurfaceLowest)
                                            .border(1.dp, QuantSurfaceHigh, RoundedCornerShape(6.dp))
                                            .clickable { inputText = sample }
                                            .padding(horizontal = 10.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                items(combinedMessages, key = { it.id }) { msg ->
                    when (msg.role) {
                        ChatRole.USER -> UserChatBubble(message = msg)
                        ChatRole.ASSISTANT -> AssistantChatBubble(
                            message = msg,
                            isVulkanActive = orchestratorSnapshot?.backendRuntimeStatus?.activeBackend == AcceleratorBackendType.VULKAN_GPU,
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(msg.text))
                                onCopiedMessage()
                            }
                        )
                    }
                }
            }

            // Sticky Bottom Prompt Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(QuantSurfaceLowest)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(QuantSurfaceContainer)
                            .border(1.dp, QuantSurfaceHigh, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = if (isReady) "Message local model offline..." else "Load a GGUF model to chat offline...",
                                style = QuantTextStyles.BodyMd,
                                color = QuantOutline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            textStyle = QuantTextStyles.BodyMd.copy(color = QuantOnSurface),
                            cursorBrush = SolidColor(QuantEmerald),
                            maxLines = 5,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat_message_input")
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isGenerating -> QuantRose.copy(alpha = 0.18f)
                                    isReady && inputText.isNotBlank() -> QuantEmeraldContainer
                                    else -> QuantSurfaceHigh
                                }
                            )
                            .testTag("chat_send_button")
                            .clickable(
                                enabled = isGenerating || (isReady && inputText.isNotBlank())
                            ) {
                                if (isGenerating) {
                                    onStopGeneration()
                                } else {
                                    val toSend = inputText
                                    inputText = ""
                                    onSendMessage(toSend)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isGenerating) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Stop Generation",
                                tint = QuantRose,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.ArrowUpward,
                                contentDescription = "Send Message",
                                tint = if (isReady && inputText.isNotBlank()) QuantOnEmeraldContainer else QuantOutline,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeReadinessCard(
    runtimeState: LocalRuntimeState,
    readyLocalFiles: List<LocalWeightFile>,
    onLoadModel: (LocalWeightFile) -> Unit,
    onGoToModels: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(QuantSurfaceLowest)
            .border(
                1.dp,
                if (runtimeState is LocalRuntimeState.Error) QuantError.copy(alpha = 0.5f) else QuantSurfaceHigh,
                RoundedCornerShape(8.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (runtimeState) {
            is LocalRuntimeState.Loading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        color = QuantEmerald,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Loading ${runtimeState.file.displayFileName}...",
                        style = QuantTextStyles.HeadlineMd,
                        color = QuantOnSurface
                    )
                }
                Text(
                    text = "Mapping GGUF tensors into native llama.cpp context. Larger 4B weights take a few seconds.",
                    style = QuantTextStyles.BodySm,
                    color = QuantOutline
                )
            }
            is LocalRuntimeState.Error -> {
                Text(
                    text = "Native Model Load Error",
                    style = QuantTextStyles.HeadlineMd,
                    color = QuantError
                )
                Text(
                    text = runtimeState.message,
                    style = QuantTextStyles.BodySm,
                    color = QuantOnSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    runtimeState.file?.let { failedFile ->
                        Button(
                            onClick = { onLoadModel(failedFile) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = QuantEmeraldContainer,
                                contentColor = QuantOnEmeraldContainer
                            )
                        ) {
                            Text("Retry Load", style = QuantTextStyles.LabelMd)
                        }
                    }
                    Button(
                        onClick = onGoToModels,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = QuantSurfaceContainer,
                            contentColor = QuantOnSurface
                        )
                    ) {
                        Text("Open Models", style = QuantTextStyles.LabelMd)
                    }
                }
            }
            is LocalRuntimeState.Unloaded -> {
                val firstReady = readyLocalFiles.firstOrNull()
                Text(
                    text = if (firstReady != null) "Local GGUF Ready to Load" else "No Local Model Downloaded",
                    style = QuantTextStyles.HeadlineMd,
                    color = QuantOnSurface
                )
                Text(
                    text = if (firstReady != null) {
                        "Tap below to load ${firstReady.displayFileName} into offline memory, or switch models in the top bar."
                    } else {
                        "Download Qwen3.5 4B, Qwen3 4B, Gemma 3 4B, Phi-4-mini 3.8B, or import a .gguf file to chat 100% offline."
                    },
                    style = QuantTextStyles.BodySm,
                    color = QuantOnSurfaceVariant
                )
                Button(
                    onClick = {
                        if (firstReady != null) onLoadModel(firstReady) else onGoToModels()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("chat_readiness_cta"),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QuantEmeraldContainer,
                        contentColor = QuantOnEmeraldContainer
                    )
                ) {
                    Text(
                        text = if (firstReady != null) {
                            "Load ${firstReady.displayFileName}"
                        } else {
                            "Download / Import GGUF Model"
                        },
                        style = QuantTextStyles.LabelMd.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            is LocalRuntimeState.Ready -> Unit
        }
    }
}

@Composable
private fun UserChatBubble(message: ChatMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp),
        horizontalAlignment = Alignment.End
    ) {
        Column(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 2.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp
                    )
                )
                .background(QuantSurfaceHigh)
                .padding(horizontal = 13.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.text,
                style = QuantTextStyles.BodyMd,
                color = QuantOnSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.timestampLabel,
                style = QuantTextStyles.LabelSm,
                color = QuantOutline,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun AssistantChatBubble(
    message: ChatMessage,
    isVulkanActive: Boolean = false,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        topStart = 2.dp,
                        topEnd = 12.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp
                    )
                )
                .background(QuantSurfaceContainer)
                .border(
                    width = if (message.isError) 1.dp else 0.dp,
                    color = if (message.isError) QuantError.copy(alpha = 0.6f) else QuantSurfaceContainer,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.modelLabel ?: "Local GGUF",
                    style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.Medium),
                    color = if (message.isError) QuantError else QuantEmerald,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                val statsLabel = when {
                    message.isStreaming -> if ((message.tokenCount ?: 0) > 0) "streaming · ${message.tokenCount} tok" else "streaming..."
                    message.tokensPerSec != null && message.latencyMs != null && message.tokensPerSec > 0f ->
                        String.format(Locale.US, "%.1f tok/s · %dms", message.tokensPerSec, message.latencyMs)
                    message.latencyMs != null -> "${message.latencyMs}ms"
                    else -> message.timestampLabel
                }
                Text(
                    text = statsLabel,
                    style = QuantTextStyles.DataTabular.copy(fontSize = 10.sp),
                    color = QuantOutline
                )
            }

            if (message.isStreaming && message.text.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    CircularProgressIndicator(
                        color = QuantEmerald,
                        strokeWidth = 1.8.dp,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (isVulkanActive) {
                            "Evaluating prompt on Vulkan GPU..."
                        } else {
                            "Evaluating prompt on local CPU..."
                        },
                        style = QuantTextStyles.BodySm,
                        color = QuantOutline
                    )
                }
            } else {
                Text(
                    text = message.text,
                    style = QuantTextStyles.BodyMd,
                    color = if (message.isError) QuantError else QuantOnSurface
                )
            }

            if (!message.isStreaming && message.text.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.timestampLabel,
                        style = QuantTextStyles.LabelSm,
                        color = QuantOutline
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onCopy)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy response",
                            tint = QuantOutline,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Copy",
                            style = QuantTextStyles.LabelSm,
                            color = QuantOutline
                        )
                    }
                }
            }
        }
    }
}

private fun formatWeightSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format(Locale.US, "%.2f GB", gb)
    else String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
