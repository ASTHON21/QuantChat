package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ChatThread
import com.example.data.LocalRuntimeState
import com.example.data.LocalWeightFile
import com.example.ui.theme.QuantEmerald
import com.example.ui.theme.QuantEmeraldContainer
import com.example.ui.theme.QuantError
import com.example.ui.theme.QuantOnEmeraldContainer
import com.example.ui.theme.QuantOnSurface
import com.example.ui.theme.QuantOnSurfaceVariant
import com.example.ui.theme.QuantOutline
import com.example.ui.theme.QuantSurface
import com.example.ui.theme.QuantSurfaceContainer
import com.example.ui.theme.QuantSurfaceHigh
import com.example.ui.theme.QuantSurfaceLow
import com.example.ui.theme.QuantSurfaceLowest
import com.example.ui.theme.QuantTextStyles

@Composable
fun ChatsScreen(
    threads: List<ChatThread>,
    searchQuery: String,
    runtimeState: LocalRuntimeState,
    readyLocalFiles: List<LocalWeightFile>,
    onSearchQueryChange: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onCreateNewChat: () -> Unit,
    onDeleteThread: (String) -> Unit,
    onOpenInferenceSettings: () -> Unit,
    onGoToModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredThreads = remember(threads, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) threads
        else threads.filter {
            it.title.lowercase().contains(q) ||
                it.preview.lowercase().contains(q) ||
                (it.modelFileName?.lowercase()?.contains(q) == true)
        }
    }

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
            // Header Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(QuantSurface.copy(alpha = 0.92f))
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_quantchat_logo_1795221575026),
                            contentDescription = "QuantChat Logo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Text(
                            text = "Chats",
                            style = QuantTextStyles.HeadlineLg.copy(fontWeight = FontWeight.Medium),
                            color = QuantOnSurface
                        )
                        Text(
                            text = "${threads.size}",
                            style = QuantTextStyles.LabelSm.copy(fontSize = 11.sp),
                            color = QuantOutline,
                            modifier = Modifier
                                .background(QuantSurfaceContainer, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(
                            onClick = onOpenInferenceSettings,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("chats_tune_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = "Inference Settings",
                                tint = QuantOutline,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = onCreateNewChat,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("chats_new_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.EditNote,
                                contentDescription = "New Chat",
                                tint = QuantEmerald,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(color = QuantSurfaceHigh.copy(alpha = 0.45f), thickness = 0.8.dp)
            }

            // Local llama.cpp Runtime Status Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(QuantSurfaceLowest)
                    .clickable(onClick = onGoToModels)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    when (runtimeState) {
                        is LocalRuntimeState.Loading -> {
                            CircularProgressIndicator(
                                color = QuantEmerald,
                                strokeWidth = 1.8.dp,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "LOADING · ${runtimeState.file.displayFileName}...",
                                style = QuantTextStyles.LabelSm,
                                color = QuantEmerald,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        is LocalRuntimeState.Ready -> {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(QuantEmerald)
                            )
                            Text(
                                text = "LOCAL READY · ${runtimeState.file.displayFileName}",
                                style = QuantTextStyles.LabelSm,
                                color = QuantEmerald,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        is LocalRuntimeState.Error -> {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(QuantError)
                            )
                            Text(
                                text = "RUNTIME ERROR · Tap to inspect Models",
                                style = QuantTextStyles.LabelSm,
                                color = QuantError,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        is LocalRuntimeState.Unloaded -> {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(QuantOutline)
                            )
                            Text(
                                text = if (readyLocalFiles.isEmpty()) {
                                    "NO GGUF DOWNLOADED · Tap to get 3.8B–4B model"
                                } else {
                                    "${readyLocalFiles.size} LOCAL GGUF READY · Tap to load"
                                },
                                style = QuantTextStyles.LabelSm,
                                color = QuantOutline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Text(
                    text = "MODELS →",
                    style = QuantTextStyles.LabelSm,
                    color = QuantOnSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            HorizontalDivider(color = QuantSurfaceHigh.copy(alpha = 0.35f), thickness = 0.8.dp)

            // Search Input Bar (shown when threads exist)
            if (threads.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(QuantSurfaceLow.copy(alpha = 0.78f), RoundedCornerShape(4.dp))
                            .border(1.dp, QuantSurfaceHigh.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = "Search chats",
                            tint = QuantOutline,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search local conversations...",
                                    style = QuantTextStyles.BodySm,
                                    color = QuantOutline
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                singleLine = true,
                                textStyle = QuantTextStyles.BodySm.copy(color = QuantOnSurface),
                                cursorBrush = SolidColor(QuantEmerald),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("chats_search_input")
                            )
                        }
                    }
                }
            }

            // Thread List or Clean Empty State
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (threads.isEmpty()) {
                    item(key = "empty_workspace") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(QuantSurfaceLowest)
                                .border(1.dp, QuantSurfaceHigh, RoundedCornerShape(8.dp))
                                .padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "100% OFFLINE GGUF CHAT",
                                style = QuantTextStyles.LabelSm,
                                color = QuantEmerald
                            )
                            Text(
                                text = "No Conversations Yet",
                                style = QuantTextStyles.HeadlineMd,
                                color = QuantOnSurface
                            )
                            Text(
                                text = "Chats run completely offline via llama.cpp against .gguf weights stored on your device. No cloud API keys or data leave your phone.",
                                style = QuantTextStyles.BodySm,
                                color = QuantOnSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = onCreateNewChat,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("empty_start_chat_button"),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = QuantEmeraldContainer,
                                    contentColor = QuantOnEmeraldContainer
                                )
                            ) {
                                Text(
                                    text = "Start New Chat",
                                    style = QuantTextStyles.LabelLg.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                            if (readyLocalFiles.isEmpty()) {
                                OutlinedButton(
                                    onClick = onGoToModels,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("empty_go_models_button"),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "Download / Import GGUF Model",
                                        style = QuantTextStyles.LabelMd,
                                        color = QuantEmerald
                                    )
                                }
                            }
                        }
                    }
                } else if (filteredThreads.isEmpty()) {
                    item(key = "empty_search") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp, horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "No matching conversations",
                                style = QuantTextStyles.HeadlineMd,
                                color = QuantOnSurfaceVariant
                            )
                            Text(
                                text = "Clear the search bar or tap the compose button to start a new chat.",
                                style = QuantTextStyles.BodySm,
                                color = QuantOutline
                            )
                        }
                    }
                } else {
                    items(filteredThreads, key = { it.id }) { thread ->
                        ChatThreadRow(
                            thread = thread,
                            onClick = { onOpenThread(thread.id) },
                            onDelete = { onDeleteThread(thread.id) }
                        )
                        HorizontalDivider(
                            color = QuantSurfaceHigh.copy(alpha = 0.35f),
                            thickness = 0.8.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatThreadRow(
    thread: ChatThread,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_thread_${thread.id}")
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = thread.title,
                    style = QuantTextStyles.BodyLg.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = QuantOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                )
                Text(
                    text = thread.timestampLabel,
                    style = QuantTextStyles.LabelSm.copy(fontSize = 11.sp),
                    color = QuantOutline
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = thread.preview,
                style = QuantTextStyles.BodySm.copy(fontSize = 13.sp),
                color = QuantOutline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!thread.modelFileName.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = thread.modelFileName,
                    style = QuantTextStyles.LabelSm.copy(fontSize = 10.sp),
                    color = QuantEmerald.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .size(48.dp)
                .testTag("delete_thread_${thread.id}")
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = "Delete conversation",
                tint = QuantOutline,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
