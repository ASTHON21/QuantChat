package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ActiveDownloadState
import com.example.data.DownloadPhase
import com.example.data.FileInspectionUiState
import com.example.data.HfAuthState
import com.example.data.HubModelItem
import com.example.data.HubModelTab
import com.example.data.HubSearchUiState
import com.example.data.LocalRuntimeState
import com.example.data.LocalWeightFile
import com.example.data.ModelFileArtifact
import com.example.ui.theme.QuantEmerald
import com.example.ui.theme.QuantEmeraldContainer
import com.example.ui.theme.QuantError
import com.example.ui.theme.QuantOnEmerald
import com.example.ui.theme.QuantOnEmeraldContainer
import com.example.ui.theme.QuantOnSurface
import com.example.ui.theme.QuantOnSurfaceVariant
import com.example.ui.theme.QuantOutline
import com.example.ui.theme.QuantOutlineVariant
import com.example.ui.theme.QuantRose
import com.example.ui.theme.QuantSurface
import com.example.ui.theme.QuantSurfaceContainer
import com.example.ui.theme.QuantSurfaceHigh
import com.example.ui.theme.QuantSurfaceLow
import com.example.ui.theme.QuantSurfaceLowest
import com.example.ui.theme.QuantTextStyles
import com.quantchat.engine.core.NpuCompatibilityReport
import com.quantchat.engine.core.NpuProbeResult
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    curatedModels: List<HubModelItem>,
    selectedTab: HubModelTab,
    searchQuery: String,
    hubSearchState: HubSearchUiState,
    fileInspectionState: FileInspectionUiState,
    activeDownload: ActiveDownloadState,
    localWeightFiles: List<LocalWeightFile>,
    runtimeState: LocalRuntimeState,
    hfAuthState: HfAuthState,
    isOnline: Boolean,
    isImportingLocalFile: Boolean,
    npuCapabilities: NpuProbeResult? = null,
    npuCompatibilityReport: NpuCompatibilityReport? = null,
    onSelectTab: (HubModelTab) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchHubClick: () -> Unit,
    onInspectModelFiles: (HubModelItem, Boolean) -> Unit,
    onCloseFileInspection: () -> Unit,
    onSelectArtifactToDownload: (HubModelItem, ModelFileArtifact) -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onSwitchDownloadToMirror: () -> Unit,
    onLoadLocalModel: (LocalWeightFile) -> Unit,
    onUnloadLocalModel: () -> Unit,
    onOpenChatWithModel: () -> Unit,
    onImportLocalGgufUri: (Uri) -> Unit,
    onResumeLocalPartial: (LocalWeightFile) -> Unit,
    onDeleteLocalFile: (LocalWeightFile) -> Unit,
    onSaveHfToken: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val keyboard = LocalSoftwareKeyboardController.current
    var showTokenDialog by remember { mutableStateOf(false) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportLocalGgufUri(uri)
        }
    }

    val filteredCurated = remember(curatedModels, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) curatedModels
        else curatedModels.filter {
            it.displayName.lowercase().contains(q) ||
                it.repoId.lowercase().contains(q) ||
                it.paramsLabel.lowercase().contains(q)
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
            // Clean Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(QuantSurface.copy(alpha = 0.92f))
                    .statusBarsPadding()
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
                    Column {
                        Text(
                            text = "OFFLINE GGUF RUNTIME",
                            style = QuantTextStyles.LabelSm.copy(letterSpacing = 1.2.sp),
                            color = QuantEmerald
                        )
                        Text(
                            text = "Models",
                            style = QuantTextStyles.HeadlineMd,
                            color = QuantOnSurface
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(QuantSurfaceContainer)
                        .border(0.8.dp, QuantSurfaceHigh, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) QuantEmerald else QuantError)
                    )
                    Text(
                        text = if (isOnline) "HUB ONLINE" else "OFFLINE",
                        style = QuantTextStyles.LabelSm,
                        color = if (isOnline) QuantEmerald else QuantError
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Optional HF Token Strip
                item(key = "hf_auth_strip") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(QuantSurfaceLowest)
                            .border(1.dp, QuantOutlineVariant.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (hfAuthState.hasToken) Icons.Filled.Key else Icons.Filled.Public,
                                contentDescription = null,
                                tint = if (hfAuthState.hasToken) QuantEmerald else QuantOutline,
                                modifier = Modifier.size(16.dp)
                            )
                            Column {
                                Text(
                                    text = if (hfAuthState.hasToken) "HF Token Active (${hfAuthState.maskedPreview})"
                                    else "Public Hub Access (Token Optional)",
                                    style = QuantTextStyles.LabelMd,
                                    color = QuantOnSurface
                                )
                                Text(
                                    text = "Open GGUF models download directly; add token for gated repos",
                                    style = QuantTextStyles.LabelSm,
                                    color = QuantOutline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (hfAuthState.hasToken) "Edit Token" else "Add Token",
                            style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.Medium),
                            color = QuantEmerald,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(QuantEmerald.copy(alpha = 0.12f))
                                .testTag("hf_token_config_button")
                                .clickable { showTokenDialog = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                // Active OkHttp Streaming Download Banner
                if (activeDownload.phase != DownloadPhase.IDLE) {
                    item(key = "active_download_banner") {
                        ActiveDownloadBanner(
                            state = activeDownload,
                            onPause = onPauseDownload,
                            onResume = onResumeDownload,
                            onCancel = onCancelDownload,
                            onAddToken = { showTokenDialog = true },
                            onUseMirror = onSwitchDownloadToMirror
                        )
                    }
                }

                // Search Bar + Tabs
                item(key = "search_and_tabs") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .background(QuantSurfaceLow, RoundedCornerShape(4.dp))
                                    .border(1.dp, QuantOutlineVariant.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = "Filter or search",
                                    tint = QuantOutline,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search Qwen3, Gemma, DeepSeek...",
                                            style = QuantTextStyles.BodySm,
                                            color = QuantOutline,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = onSearchQueryChange,
                                        singleLine = true,
                                        textStyle = QuantTextStyles.BodySm.copy(color = QuantOnSurface),
                                        cursorBrush = SolidColor(QuantEmerald),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(
                                            onSearch = {
                                                keyboard?.hide()
                                                onSearchHubClick()
                                            }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("models_search_input")
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Clear search",
                                        tint = QuantOutline,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable { onSearchQueryChange("") }
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    keyboard?.hide()
                                    onSearchHubClick()
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("models_search_hub_button"),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = QuantEmeraldContainer,
                                    contentColor = QuantOnEmeraldContainer
                                )
                            ) {
                                Text(
                                    text = "Search Hub",
                                    style = QuantTextStyles.LabelMd.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HubModelTab.entries.forEach { tab ->
                                val selected = tab == selectedTab
                                val label = when (tab) {
                                    HubModelTab.CURATED -> "Curated (${curatedModels.size})"
                                    HubModelTab.HUB_RESULTS -> {
                                        val count = (hubSearchState as? HubSearchUiState.Success)?.items?.size
                                        if (count != null) "Hub ($count)" else "Hub Results"
                                    }
                                    HubModelTab.DOWNLOADED -> "Downloaded (${localWeightFiles.size})"
                                }
                                Text(
                                    text = label,
                                    style = QuantTextStyles.LabelSm.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = if (selected) QuantOnEmerald else QuantOutline,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (selected) QuantEmerald else QuantSurfaceContainer)
                                        .testTag("models_tab_${tab.name}")
                                        .clickable { onSelectTab(tab) }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                        }
                        HorizontalDivider(color = QuantSurfaceHigh.copy(alpha = 0.5f), thickness = 0.8.dp)
                    }
                }

                when (selectedTab) {
                    HubModelTab.CURATED -> {
                        if (filteredCurated.isEmpty()) {
                            item(key = "curated_empty") {
                                EmptyHubHint(
                                    title = "No local match for \"$searchQuery\"",
                                    subtitle = "Tap \"Search Hub\" above to query Hugging Face Hub online for latest GGUF models."
                                )
                            }
                        } else {
                            items(filteredCurated, key = { it.id }) { model ->
                                HubModelCard(
                                    model = model,
                                    onOpenRepo = { uriHandler.openUri(model.repoUrl) },
                                    onInspectClick = { onInspectModelFiles(model, false) }
                                )
                            }
                        }
                    }

                    HubModelTab.HUB_RESULTS -> {
                        when (val state = hubSearchState) {
                            is HubSearchUiState.Idle -> {
                                item(key = "hub_idle") {
                                    EmptyHubHint(
                                        title = "Search Official Hugging Face Models",
                                        subtitle = "Enter a model name (e.g. Qwen3.5, Gemma 3, Phi-4, Llama 3.2) and tap Search Hub to fetch downloadable GGUF repositories."
                                    )
                                }
                            }
                            is HubSearchUiState.Loading -> {
                                item(key = "hub_loading") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            color = QuantEmerald,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Querying huggingface.co/api/models...",
                                            style = QuantTextStyles.LabelMd,
                                            color = QuantOnSurfaceVariant
                                        )
                                    }
                                }
                            }
                            is HubSearchUiState.Error -> {
                                item(key = "hub_error") {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(QuantSurfaceLowest)
                                            .border(1.dp, QuantError.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Hub Search Unavailable",
                                            style = QuantTextStyles.HeadlineMd,
                                            color = QuantError
                                        )
                                        Text(
                                            text = state.message,
                                            style = QuantTextStyles.BodySm,
                                            color = QuantOnSurfaceVariant
                                        )
                                        Text(
                                            text = "Retry Search",
                                            style = QuantTextStyles.LabelMd,
                                            color = QuantEmerald,
                                            modifier = Modifier
                                                .clickable(onClick = onSearchHubClick)
                                                .padding(vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            is HubSearchUiState.Success -> {
                                if (state.items.isEmpty()) {
                                    item(key = "hub_empty_results") {
                                        EmptyHubHint(
                                            title = "No GGUF models found for \"${state.query}\"",
                                            subtitle = "Try a shorter keyword such as Qwen3, Gemma, Phi-4, or DeepSeek."
                                        )
                                    }
                                } else {
                                    items(state.items, key = { it.id }) { item ->
                                        HubModelCard(
                                            model = item,
                                            onOpenRepo = { uriHandler.openUri(item.repoUrl) },
                                            onInspectClick = { onInspectModelFiles(item, false) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HubModelTab.DOWNLOADED -> {
                        item(key = "import_local_gguf") {
                            OutlinedButton(
                                onClick = {
                                    openDocumentLauncher.launch(arrayOf("*/*"))
                                },
                                enabled = !isImportingLocalFile,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("import_local_gguf_button"),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                if (isImportingLocalFile) {
                                    CircularProgressIndicator(
                                        color = QuantEmerald,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Importing .gguf into app storage...",
                                        style = QuantTextStyles.LabelMd,
                                        color = QuantEmerald
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.FolderOpen,
                                        contentDescription = null,
                                        tint = QuantEmerald,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Import Local .gguf File from Device",
                                        style = QuantTextStyles.LabelMd.copy(fontWeight = FontWeight.Medium),
                                        color = QuantEmerald
                                    )
                                }
                            }
                        }

                        if (localWeightFiles.isEmpty()) {
                            item(key = "downloaded_empty") {
                                EmptyHubHint(
                                    title = "No Local GGUF Weights Yet",
                                    subtitle = "Download a model from Curated (4) / Hub Results, or import a .gguf file already on your device."
                                )
                            }
                        } else {
                            items(localWeightFiles, key = { it.diskFileName }) { file ->
                                val isLoaded = (runtimeState as? LocalRuntimeState.Ready)?.file?.diskFileName == file.diskFileName
                                val isLoading = (runtimeState as? LocalRuntimeState.Loading)?.file?.diskFileName == file.diskFileName
                                LocalWeightCard(
                                    file = file,
                                    isLoaded = isLoaded,
                                    isLoading = isLoading,
                                    npuCapabilities = npuCapabilities,
                                    npuCompatibilityReport = if (isLoaded) npuCompatibilityReport else null,
                                    onLoad = { onLoadLocalModel(file) },
                                    onUnload = onUnloadLocalModel,
                                    onOpenChat = onOpenChatWithModel,
                                    onResume = { onResumeLocalPartial(file) },
                                    onDelete = { onDeleteLocalFile(file) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // GGUF File Picker Modal Bottom Sheet
    if (fileInspectionState !is FileInspectionUiState.Closed) {
        ModalBottomSheet(
            onDismissRequest = onCloseFileInspection,
            containerColor = QuantSurfaceLow,
            contentColor = QuantOnSurface
        ) {
            when (fileInspectionState) {
                is FileInspectionUiState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = QuantEmerald, strokeWidth = 2.dp)
                        Text(
                            text = "Resolving GGUF artifacts in ${fileInspectionState.model.repoId}...",
                            style = QuantTextStyles.LabelMd,
                            color = QuantOnSurfaceVariant
                        )
                    }
                }
                is FileInspectionUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = if (fileInspectionState.authRequired) "Gated Repository Authorization"
                            else "Unable to Resolve Files",
                            style = QuantTextStyles.HeadlineMd,
                            color = QuantRose
                        )
                        Text(
                            text = fileInspectionState.message,
                            style = QuantTextStyles.BodySm,
                            color = QuantOnSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (fileInspectionState.authRequired) {
                                Button(
                                    onClick = {
                                        onCloseFileInspection()
                                        showTokenDialog = true
                                    },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = QuantEmeraldContainer,
                                        contentColor = QuantOnEmeraldContainer
                                    )
                                ) {
                                    Text("Enter HF Token", style = QuantTextStyles.LabelMd)
                                }
                            }
                            val fallbackRepo = fileInspectionState.model.fallbackOpenRepoId
                            if (!fallbackRepo.isNullOrBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        onInspectModelFiles(fileInspectionState.model, true)
                                    },
                                    modifier = Modifier.weight(1f).height(46.dp)
                                ) {
                                    Text("Use Open GGUF Mirror", style = QuantTextStyles.LabelSm, color = QuantEmerald)
                                }
                            }
                        }
                    }
                }
                is FileInspectionUiState.Ready -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "SELECT QUANTIZED GGUF FILE",
                            style = QuantTextStyles.LabelSm,
                            color = QuantEmerald
                        )
                        Text(
                            text = fileInspectionState.model.repoId,
                            style = QuantTextStyles.HeadlineMd,
                            color = QuantOnSurface
                        )
                        HorizontalDivider(color = QuantSurfaceHigh, thickness = 0.8.dp)

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(fileInspectionState.files, key = { it.rfilename }) { artifact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(QuantSurfaceLowest)
                                        .border(1.dp, QuantSurfaceHigh, RoundedCornerShape(6.dp))
                                        .clickable {
                                            onSelectArtifactToDownload(fileInspectionState.model, artifact)
                                        }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = artifact.quantTag,
                                                style = QuantTextStyles.LabelSm,
                                                color = QuantEmerald,
                                                modifier = Modifier
                                                    .background(QuantEmerald.copy(alpha = 0.14f), RoundedCornerShape(3.dp))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                            Text(
                                                text = formatBytes(artifact.sizeBytes),
                                                style = QuantTextStyles.DataTabular,
                                                color = QuantOnSurface
                                            )
                                        }
                                        Text(
                                            text = artifact.rfilename,
                                            style = QuantTextStyles.BodySm,
                                            color = QuantOnSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Filled.Download,
                                        contentDescription = "Download ${artifact.rfilename}",
                                        tint = QuantEmerald,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                FileInspectionUiState.Closed -> Unit
            }
        }
    }

    if (showTokenDialog) {
        HfTokenDialog(
            currentToken = hfAuthState.token,
            onDismiss = { showTokenDialog = false },
            onSave = { newToken ->
                showTokenDialog = false
                onSaveHfToken(newToken)
            }
        )
    }
}

@Composable
private fun ActiveDownloadBanner(
    state: ActiveDownloadState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onAddToken: () -> Unit,
    onUseMirror: () -> Unit
) {
    val isError = state.phase == DownloadPhase.ERROR_AUTH_REQUIRED || state.phase == DownloadPhase.ERROR_NETWORK
    val borderColor = if (isError) QuantError.copy(alpha = 0.55f) else QuantEmerald.copy(alpha = 0.4f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(QuantSurfaceLowest)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = state.fileName,
                    style = QuantTextStyles.LabelMd.copy(fontWeight = FontWeight.SemiBold),
                    color = QuantOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${state.repoId} · OkHttp Streaming",
                    style = QuantTextStyles.LabelSm,
                    color = QuantOutline
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state.phase) {
                    DownloadPhase.DOWNLOADING -> {
                        IconButton(onClick = onPause, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Pause, contentDescription = "Pause download", tint = QuantEmerald)
                        }
                    }
                    DownloadPhase.PAUSED, DownloadPhase.ERROR_NETWORK -> {
                        IconButton(onClick = onResume, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Resume download", tint = QuantEmerald)
                        }
                    }
                    else -> Unit
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss download", tint = QuantOutline)
                }
            }
        }

        if (!isError) {
            LinearProgressIndicator(
                progress = { if (state.phase == DownloadPhase.COMPLETED) 1f else state.progressFraction },
                color = QuantEmerald,
                trackColor = QuantSurfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(99.dp))
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)} (${state.progressPercent}%)",
                    style = QuantTextStyles.LabelSm,
                    color = QuantOutline
                )
                val statusRight = when (state.phase) {
                    DownloadPhase.COMPLETED -> "SAVED TO APP STORAGE"
                    DownloadPhase.PAUSED -> "PAUSED (RANGE READY)"
                    else -> "${formatSpeed(state.speedBytesPerSec)} · ETA ${state.etaSeconds}s"
                }
                Text(
                    text = statusRight,
                    style = QuantTextStyles.DataTabular.copy(fontSize = 10.sp),
                    color = QuantEmerald
                )
            }
        } else {
            Text(
                text = state.errorMessage ?: "Download interrupted.",
                style = QuantTextStyles.BodySm,
                color = QuantError
            )
            if (state.phase == DownloadPhase.ERROR_AUTH_REQUIRED) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Add HF Token",
                        style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                        color = QuantEmerald,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(QuantEmerald.copy(alpha = 0.14f))
                            .clickable(onClick = onAddToken)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                    if (!state.fallbackOpenRepoId.isNullOrBlank()) {
                        Text(
                            text = "Use Open Mirror",
                            style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                            color = QuantOnSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(QuantSurfaceContainer)
                                .clickable(onClick = onUseMirror)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HubModelCard(
    model: HubModelItem,
    onOpenRepo: () -> Unit,
    onInspectClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(QuantSurfaceLowest)
            .border(1.dp, QuantOutlineVariant.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = model.displayName,
                        style = QuantTextStyles.HeadlineMd,
                        color = QuantOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = model.paramsLabel,
                        style = QuantTextStyles.DataTabular.copy(fontWeight = FontWeight.SemiBold),
                        color = QuantEmerald,
                        modifier = Modifier
                            .background(QuantEmerald.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (model.isGated) "GATED · HF TOKEN" else "OPEN GGUF",
                        style = QuantTextStyles.LabelSm.copy(fontSize = 9.sp),
                        color = if (model.isGated) QuantRose else QuantEmerald,
                        modifier = Modifier
                            .background(
                                (if (model.isGated) QuantRose else QuantEmerald).copy(alpha = 0.12f),
                                RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .clickable(onClick = onOpenRepo)
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Code,
                            contentDescription = "Open Hugging Face repository",
                            tint = QuantOutline,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = model.repoId,
                            style = QuantTextStyles.LabelSm.copy(fontSize = 10.sp),
                            color = QuantOutline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(QuantSurfaceContainer)
                    .border(1.dp, QuantEmerald.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                    .testTag("model_download_${model.id}")
                    .clickable(onClick = onInspectClick)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = if (model.isGated) Icons.Filled.Lock else Icons.Filled.Download,
                    contentDescription = "Select GGUF File",
                    tint = QuantEmerald,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "GGUF",
                    style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                    color = QuantEmerald
                )
            }
        }

        Text(
            text = model.summary,
            style = QuantTextStyles.BodySm,
            color = QuantOnSurfaceVariant
        )
    }
}

@Composable
private fun LocalWeightCard(
    file: LocalWeightFile,
    isLoaded: Boolean,
    isLoading: Boolean,
    npuCapabilities: NpuProbeResult?,
    npuCompatibilityReport: NpuCompatibilityReport?,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onOpenChat: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(QuantSurfaceLowest)
            .border(
                1.dp,
                if (isLoaded) QuantEmerald.copy(alpha = 0.6f) else QuantSurfaceHigh,
                RoundedCornerShape(6.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isLoaded) Icons.Filled.CheckCircle else Icons.Filled.Storage,
                    contentDescription = null,
                    tint = when {
                        file.isPartial -> QuantOutline
                        isLoaded -> QuantEmerald
                        else -> QuantOnSurfaceVariant
                    },
                    modifier = Modifier.size(19.dp)
                )
                Column {
                    Text(
                        text = file.displayFileName,
                        style = QuantTextStyles.LabelMd.copy(fontWeight = FontWeight.Medium),
                        color = QuantOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${file.repoId} · ${file.quantTag} · ${formatBytes(file.sizeBytes)}" +
                            when {
                                file.isPartial -> " (Partial)"
                                isLoaded -> " (Loaded in RAM)"
                                else -> " (Ready)"
                            },
                        style = QuantTextStyles.LabelSm,
                        color = when {
                            file.isPartial -> QuantRose
                            isLoaded -> QuantEmerald
                            else -> QuantOutline
                        }
                    )
                    if (!file.isPartial) {
                        val metaParts = buildList {
                            file.headerMetadata?.architecture?.let { add("arch: $it") }
                            file.headerMetadata?.declaredContextLength?.let { add("maxCtx: $it") }
                            file.admission?.summary?.let { add(it) }
                        }
                        if (metaParts.isNotEmpty()) {
                            Text(
                                text = metaParts.joinToString(" · "),
                                style = QuantTextStyles.LabelSm,
                                color = QuantOnSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        val compatText = when {
                            npuCompatibilityReport != null ->
                                "NPU Gate: ${npuCompatibilityReport.verdictCode.label} → ${npuCompatibilityReport.routedFallbackTarget}"
                            file.diskFileName.endsWith(".gguf", ignoreCase = true) && npuCapabilities?.isHardwareLikelyPresent == true ->
                                "NPU Gate (${npuCapabilities.vendorFamily.label}): GGUF runs on Vulkan GPU / CPU"
                            else -> null
                        }
                        if (compatText != null) {
                            Text(
                                text = compatText,
                                style = QuantTextStyles.LabelSm,
                                color = QuantOutline,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .testTag("local_weight_npu_gate_${file.diskFileName}")
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("delete_weight_${file.diskFileName}")
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "Delete file",
                    tint = QuantOutline
                )
            }
        }

        if (file.isPartial) {
            if (file.downloadUrl != null) {
                Button(
                    onClick = onResume,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QuantSurfaceContainer,
                        contentColor = QuantEmerald
                    )
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Resume Download", style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    isLoading -> {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(QuantSurfaceContainer),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color = QuantEmerald,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Loading GGUF...", style = QuantTextStyles.LabelSm, color = QuantEmerald)
                        }
                    }
                    isLoaded -> {
                        Button(
                            onClick = onOpenChat,
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .testTag("open_chat_loaded_${file.diskFileName}"),
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = QuantEmeraldContainer,
                                contentColor = QuantOnEmeraldContainer
                            )
                        ) {
                            Text("Chat Now →", style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold))
                        }
                        OutlinedButton(
                            onClick = onUnload,
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("Unload", style = QuantTextStyles.LabelSm, color = QuantRose)
                        }
                    }
                    else -> {
                        Button(
                            onClick = onLoad,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .testTag("load_weight_${file.diskFileName}"),
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = QuantSurfaceContainer,
                                contentColor = QuantEmerald
                            )
                        ) {
                            Text("Load into Offline Runtime", style = QuantTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHubHint(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(QuantSurfaceLowest)
            .border(1.dp, QuantSurfaceHigh, RoundedCornerShape(6.dp))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = title, style = QuantTextStyles.HeadlineMd, color = QuantOnSurfaceVariant)
        Text(text = subtitle, style = QuantTextStyles.BodySm, color = QuantOutline)
    }
}

@Composable
private fun HfTokenDialog(
    currentToken: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var input by remember(currentToken) { mutableStateOf(currentToken) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = QuantSurfaceLow,
        titleContentColor = QuantOnSurface,
        textContentColor = QuantOnSurfaceVariant,
        title = { Text("Hugging Face Access Token", style = QuantTextStyles.HeadlineMd) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Optional for public GGUF models. Required for official gated repositories such as google/gemma-3-4b-it-qat-q4_0-gguf after accepting the license on huggingface.co.",
                    style = QuantTextStyles.BodySm,
                    color = QuantOnSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Bearer Token (hf_...)", style = QuantTextStyles.LabelSm) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = QuantEmerald,
                        unfocusedBorderColor = QuantOutlineVariant,
                        focusedTextColor = QuantOnSurface,
                        unfocusedTextColor = QuantOnSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hf_token_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(input) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = QuantEmeraldContainer,
                    contentColor = QuantOnEmeraldContainer
                )
            ) {
                Text("Save Locally", style = QuantTextStyles.LabelMd.copy(fontWeight = FontWeight.SemiBold))
            }
        },
        dismissButton = {
            Row {
                if (currentToken.isNotBlank()) {
                    TextButton(onClick = { onSave("") }) {
                        Text("Clear", style = QuantTextStyles.LabelMd, color = QuantRose)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", style = QuantTextStyles.LabelMd, color = QuantOutline)
                }
            }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(Locale.US, "%.2f GB", gb)
    } else {
        val mb = bytes / (1024.0 * 1024.0)
        String.format(Locale.US, "%.1f MB", mb)
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "connecting..."
    val mbps = bytesPerSec / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB/s", mbps)
}
