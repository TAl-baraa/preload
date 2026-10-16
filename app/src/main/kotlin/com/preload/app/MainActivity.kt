package com.preload.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var url by mutableStateOf("")
    private val analyzer by lazy { VideoAnalyzer(applicationContext) }
    private val downloader by lazy { VideoDownloader(applicationContext) }
    private val appPreferences by lazy { AppPreferences(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        lifecycleScope.launch { analyzer.initialize() }
        lifecycleScope.launch { downloader.initialize() }
        setContent {
            val themePreference by appPreferences.theme.collectAsState(ThemePreference.System)
            val useDarkTheme = when (themePreference) {
                ThemePreference.System -> isSystemInDarkTheme()
                ThemePreference.Light -> false
                ThemePreference.Dark -> true
            }

            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        lightScrim = android.graphics.Color.TRANSPARENT,
                        darkScrim = android.graphics.Color.TRANSPARENT,
                        detectDarkMode = { useDarkTheme },
                    ),
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim = 0xFFF0F4F8.toInt(),
                        darkScrim = 0xFF171E28.toInt(),
                        detectDarkMode = { useDarkTheme },
                    ),
                )
            }

            PreloadTheme(darkTheme = useDarkTheme) {
                PreloadAppShell(
                    themePreference = themePreference,
                    onThemePreferenceChanged = { preference ->
                        lifecycleScope.launch { appPreferences.setTheme(preference) }
                    },
                ) {
                    PreloadScreen(
                        url = url,
                        onUrlChange = { url = it },
                        onAnalyze = analyzer::analyze,
                        onDownload = downloader::download,
                        onCancelDownload = downloader::cancel,
                        onOpenDownload = ::openDownloadedFile,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return

        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return
        url = URL_PATTERN.find(sharedText)?.value ?: sharedText
    }

    private fun openDownloadedFile(file: DownloadedFile): Boolean = try {
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(file.contentUri, file.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private companion object {
        val URL_PATTERN = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    }
}

@Composable
private fun PreloadScreen(
    url: String,
    onUrlChange: (String) -> Unit,
    onAnalyze: suspend (String) -> AnalyzedVideo,
    onDownload: suspend (String, String, SelectableFormat, (DownloadProgress) -> Unit) -> DownloadedFile,
    onCancelDownload: () -> Boolean,
    onOpenDownload: (DownloadedFile) -> Boolean,
) {
    var isLoading by remember { mutableStateOf(false) }
    var analyzedVideo by remember { mutableStateOf<AnalyzedVideo?>(null) }
    var analysisError by remember { mutableStateOf<AnalysisError?>(null) }
    var selectedFormatId by remember { mutableStateOf<String?>(null) }
    var downloadState by remember { mutableStateOf<DownloadUiState>(DownloadUiState.Idle) }
    var downloadGeneration by remember { mutableStateOf(0L) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val analysisFailedMessage = stringResource(R.string.analysis_failed)
    val invalidLinkMessage = stringResource(R.string.analysis_invalid_link)
    val networkErrorMessage = stringResource(R.string.analysis_network_error)
    val unavailableMediaMessage = stringResource(R.string.analysis_media_unavailable)
    val downloadFailedMessage = stringResource(R.string.download_failed)
    val downloadCompleteMessage = stringResource(R.string.download_complete)
    val videoOnlyMessage = stringResource(R.string.video_only_unavailable)
    val cancelFailedMessage = stringResource(R.string.cancel_failed)
    val downloadCanceledMessage = stringResource(R.string.download_canceled)
    val downloadPublishFailedMessage = stringResource(R.string.download_publish_failed)
    val storagePermissionDeniedMessage = stringResource(R.string.storage_permission_denied)
    val openFailedMessage = stringResource(R.string.open_failed)
    var pendingLegacyDownload by remember { mutableStateOf<(() -> Unit)?>(null) }

    val startDownload: (SelectableFormat) -> Unit = { selectedFormat ->
        coroutineScope.launch {
            val generation = ++downloadGeneration
            downloadState = DownloadUiState.Downloading(DownloadProgress())
            try {
                val video = analyzedVideo ?: return@launch
                val result = onDownload(
                    video.webpageUrl ?: url.trim(),
                    video.title,
                    selectedFormat,
                ) { progress ->
                    coroutineScope.launch {
                        if (downloadGeneration == generation && downloadState is DownloadUiState.Downloading) {
                            downloadState = DownloadUiState.Downloading(progress)
                        }
                    }
                }
                if (downloadGeneration == generation) downloadGeneration++
                downloadState = DownloadUiState.Complete(result)
                snackbarHostState.showSnackbar(downloadCompleteMessage)
            } catch (_: DownloadCanceledException) {
                if (downloadGeneration == generation) downloadGeneration++
                downloadState = DownloadUiState.Idle
                snackbarHostState.showSnackbar(downloadCanceledMessage)
            } catch (_: DownloadPublishException) {
                downloadState = DownloadUiState.Failed(downloadPublishFailedMessage)
                snackbarHostState.showSnackbar(downloadPublishFailedMessage)
            } catch (error: Throwable) {
                Log.e(DOWNLOAD_TAG, "Download failed", error)
                downloadState = DownloadUiState.Failed(null)
                snackbarHostState.showSnackbar(downloadFailedMessage)
            }
        }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingLegacyDownload
        pendingLegacyDownload = null
        if (granted) {
            pending?.invoke()
        } else {
            coroutineScope.launch { snackbarHostState.showSnackbar(storagePermissionDeniedMessage) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.privacy_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = onUrlChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.url_placeholder)) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            textStyle = LocalTextStyle.current.copy(
                                textAlign = TextAlign.Start,
                                textDirection = TextDirection.Ltr,
                            ),
                            trailingIcon = {
                                if (url.isNotEmpty()) {
                                    IconButton(onClick = { onUrlChange("") }) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = stringResource(R.string.clear_url),
                                        )
                                    }
                                }
                            },
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (url.isNotEmpty()) {
                                TextButton(onClick = { onUrlChange("") }) {
                                    Text(stringResource(R.string.clear))
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.getText()?.text?.let { pastedText ->
                                        onUrlChange(pastedText)
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.paste))
                            }
                        }

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isLoading = true
                                    analysisError = null
                                    analyzedVideo = null
                                    selectedFormatId = null
                                    downloadState = DownloadUiState.Idle
                                    try {
                                        val trimmedUrl = url.trim()
                                        if (!trimmedUrl.isLikelyWebUrl()) {
                                            analysisError = AnalysisError.InvalidLink
                                        } else {
                                            analyzedVideo = onAnalyze(trimmedUrl)
                                        }
                                    } catch (error: Throwable) {
                                        Log.e(ANALYSIS_TAG, "Video analysis failed for ${url.trim()}", error)
                                        analysisError = error.toAnalysisError()
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            enabled = url.isNotEmpty() && !isLoading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.analyze))
                        }
                    }
                }

                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = stringResource(R.string.analyzing),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                analysisError?.let { error ->
                    AnalysisErrorCard(
                        message = when (error) {
                            AnalysisError.InvalidLink -> invalidLinkMessage
                            AnalysisError.Network -> networkErrorMessage
                            AnalysisError.UnavailableMedia -> unavailableMediaMessage
                            AnalysisError.Generic -> analysisFailedMessage
                        },
                    )
                }

                when {
                    analyzedVideo != null -> {
                        val video = analyzedVideo!!
                        AnalyzedResultCard(
                            video = video,
                            selectedFormatId = selectedFormatId,
                            downloadState = downloadState,
                            onFormatSelected = {
                                selectedFormatId = it
                                downloadState = DownloadUiState.Idle
                            },
                            onDownload = { selectedFormat ->
                                when {
                                    selectedFormat.hasVideo && !selectedFormat.hasAudio -> {
                                        coroutineScope.launch { snackbarHostState.showSnackbar(videoOnlyMessage) }
                                    }
                                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                        ) != PackageManager.PERMISSION_GRANTED -> {
                                        pendingLegacyDownload = { startDownload(selectedFormat) }
                                        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    }
                                    else -> startDownload(selectedFormat)
                                }
                            },
                            onCancel = {
                                if (!onCancelDownload()) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(cancelFailedMessage)
                                    }
                                }
                            },
                            onOpen = { file ->
                                if (!onOpenDownload(file)) {
                                    coroutineScope.launch { snackbarHostState.showSnackbar(openFailedMessage) }
                                }
                            },
                        )
                    }
                    !isLoading && analysisError == null -> InitialStateCard()
                }
            }

        }
    }
}

@Composable
private fun InitialStateCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = stringResource(R.string.initial_state_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun AnalysisErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.analysis_error_title), style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.analysis_retry_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AnalyzedResultCard(
    video: AnalyzedVideo,
    selectedFormatId: String?,
    downloadState: DownloadUiState,
    onFormatSelected: (String) -> Unit,
    onDownload: (SelectableFormat) -> Unit,
    onCancel: () -> Unit,
    onOpen: (DownloadedFile) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = video.title.ifBlank { stringResource(R.string.untitled_video) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            video.uploader?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val details = listOfNotNull(
                video.durationSeconds?.let(::formatDuration),
                video.site?.takeIf { it.isNotBlank() },
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FormatSelectionSection(
            formats = video.formats,
            mediaTitle = video.title.ifBlank { stringResource(R.string.untitled_video) },
            thumbnailUrl = video.thumbnailUrl,
            durationSeconds = video.durationSeconds,
            selectedFormatId = selectedFormatId,
            downloadState = downloadState,
            onFormatSelected = onFormatSelected,
            onDownload = onDownload,
            onCancel = onCancel,
            onOpen = onOpen,
        )
    }
}

@Composable
private fun FormatSelectionSection(
    formats: List<SelectableFormat>,
    mediaTitle: String,
    thumbnailUrl: String?,
    durationSeconds: Int?,
    selectedFormatId: String?,
    downloadState: DownloadUiState,
    onFormatSelected: (String) -> Unit,
    onDownload: (SelectableFormat) -> Unit,
    onCancel: () -> Unit,
    onOpen: (DownloadedFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDownloading = downloadState is DownloadUiState.Downloading

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.quality),
            style = MaterialTheme.typography.titleMedium,
        )

        if (formats.none { it.hasVideo }) {
            Text(
                text = stringResource(R.string.no_video_formats),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        formats.chunked(2).forEach { rowFormats ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowFormats.forEach { format ->
                    FormatCard(
                        format = format,
                        mediaTitle = mediaTitle,
                        thumbnailUrl = thumbnailUrl,
                        durationSeconds = durationSeconds,
                        selected = selectedFormatId == format.formatId,
                        enabled = !isDownloading,
                        onSelected = { onFormatSelected(format.formatId) },
                        onDownload = {
                            onFormatSelected(format.formatId)
                            onDownload(format)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowFormats.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }

        when (downloadState) {
            is DownloadUiState.Downloading -> DownloadProgressCard(downloadState.progress, onCancel)
            is DownloadUiState.Complete -> DownloadSuccessCard(downloadState.file, onOpen)
            is DownloadUiState.Failed -> DownloadErrorCard()
            DownloadUiState.Idle -> Unit
        }

    }
}

@Composable
private fun DownloadProgressCard(progress: DownloadProgress, onCancel: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = progress.percent?.let {
                    stringResource(R.string.downloading_percent, it.toInt().coerceIn(0, 100))
                } ?: stringResource(R.string.downloading),
                style = MaterialTheme.typography.titleSmall,
            )
            if (progress.downloadedBytes != null && progress.totalBytes != null) {
                Text(
                    text = stringResource(
                        R.string.download_size_progress,
                        formatProgressBytes(progress.downloadedBytes, progress.sizeUsesBinaryUnits),
                        formatProgressBytes(progress.totalBytes, progress.sizeUsesBinaryUnits),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val speed = progress.bytesPerSecond?.let {
                "${formatProgressBytes(it, progress.speedUsesBinaryUnits)}/s"
            }
            val eta = progress.etaSeconds?.let {
                stringResource(R.string.download_eta_remaining, formatEta(it))
            }
            listOfNotNull(speed, eta).takeIf { it.isNotEmpty() }?.let { details ->
                Text(text = details.joinToString(" • "), style = MaterialTheme.typography.bodyMedium)
            }
            progress.percent?.let { percent ->
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun DownloadSuccessCard(file: DownloadedFile, onOpen: (DownloadedFile) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.download_complete), style = MaterialTheme.typography.titleSmall)
            Text(
                text = file.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(formatFileSize(file.sizeBytes), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.saved_to_downloads), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { onOpen(file) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.open))
            }
        }
    }
}

@Composable
private fun DownloadErrorCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.download_failed),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun FormatCard(
    format: SelectableFormat,
    mediaTitle: String,
    thumbnailUrl: String?,
    durationSeconds: Int?,
    selected: Boolean,
    enabled: Boolean,
    onSelected: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.selectable(
                selected = selected,
                enabled = enabled,
                onClick = onSelected,
                role = Role.RadioButton,
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                thumbnailUrl?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f),
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Text(
                        text = format.extension?.uppercase() ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                durationSeconds?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f),
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    ) {
                        Text(
                            text = formatDuration(it),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = mediaTitle,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RadioButton(selected = selected, onClick = null, enabled = enabled)
                }
                Text(
                    text = formatCardMetadata(format),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (format.hasVideo && !format.hasAudio) {
                    Text(
                        text = stringResource(R.string.requires_merging),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Button(
                    onClick = onDownload,
                    enabled = enabled && format.hasAudio,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = if (!format.hasVideo) stringResource(R.string.download_audio)
                        else stringResource(R.string.download),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun formatCardMetadata(format: SelectableFormat): String = listOfNotNull(
    if (format.hasVideo) format.height?.let { "${it}p" } else format.bitrateKbps?.let { "$it kbps" },
    format.extension?.uppercase(),
    format.fileSizeBytes?.let(::formatFileSize),
).joinToString(" • ")

private fun formatFileSize(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1.0) "%.1f MB".format(megabytes)
    else "%.1f KB".format(bytes / 1024.0)
}

private fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private fun Throwable.safeMessage(): String? = message
    ?.lineSequence()
    ?.firstOrNull { it.isNotBlank() }
    ?.trim()
    ?.take(180)

private fun String.isLikelyWebUrl(): Boolean =
    startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

private fun Throwable.toAnalysisError(): AnalysisError {
    val detail = generateSequence(this) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
        .lowercase()
    return when {
        detail.contains("unable to resolve host") || detail.contains("network") ||
            detail.contains("timed out") || detail.contains("timeout") -> AnalysisError.Network
        detail.contains("unsupported url") || detail.contains("invalid url") ||
            detail.contains("not a valid url") -> AnalysisError.InvalidLink
        detail.contains("unavailable") || detail.contains("unsupported") ||
            detail.contains("private video") || detail.contains("removed") -> AnalysisError.UnavailableMedia
        else -> AnalysisError.Generic
    }
}

private enum class AnalysisError {
    InvalidLink,
    Network,
    UnavailableMedia,
    Generic,
}

private const val ANALYSIS_TAG = "PreloadAnalysis"
private const val DOWNLOAD_TAG = "PreloadDownload"

private sealed interface DownloadUiState {
    data object Idle : DownloadUiState
    data class Downloading(val progress: DownloadProgress) : DownloadUiState
    data class Complete(val file: DownloadedFile) : DownloadUiState
    data class Failed(val detail: String?) : DownloadUiState
}
