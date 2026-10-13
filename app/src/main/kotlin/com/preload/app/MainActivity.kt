package com.preload.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var url by mutableStateOf("")
    private val analyzer by lazy { VideoAnalyzer(applicationContext) }
    private val downloader by lazy { VideoDownloader(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        lifecycleScope.launch { analyzer.initialize() }
        lifecycleScope.launch { downloader.initialize() }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                PreloadScreen(
                    url = url,
                    onUrlChange = { url = it },
                    onAnalyze = analyzer::analyze,
                    onDownload = downloader::download,
                    onCancelDownload = downloader::cancel,
                )
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

    private companion object {
        val URL_PATTERN = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    }
}

@Composable
private fun PreloadScreen(
    url: String,
    onUrlChange: (String) -> Unit,
    onAnalyze: suspend (String) -> AnalyzedVideo,
    onDownload: suspend (String, String, SelectableFormat, (Float) -> Unit) -> DownloadedFile,
    onCancelDownload: () -> Boolean,
) {
    var isLoading by remember { mutableStateOf(false) }
    var analyzedVideo by remember { mutableStateOf<AnalyzedVideo?>(null) }
    var selectedFormatId by remember { mutableStateOf<String?>(null) }
    var downloadState by remember { mutableStateOf<DownloadUiState>(DownloadUiState.Idle) }
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val analysisFailedMessage = stringResource(R.string.analysis_failed)
    val downloadFailedMessage = stringResource(R.string.download_failed)
    val downloadCompleteMessage = stringResource(R.string.download_complete)
    val videoOnlyMessage = stringResource(R.string.video_only_unavailable)
    val audioOnlyMessage = stringResource(R.string.audio_only_unavailable)
    val cancelFailedMessage = stringResource(R.string.cancel_failed)
    val downloadCanceledMessage = stringResource(R.string.download_canceled)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.app_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(28.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.url_placeholder)) },
                    singleLine = true,
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

                OutlinedButton(
                    onClick = {
                        clipboardManager.getText()?.text?.let { pastedText ->
                            onUrlChange(pastedText)
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.paste))
                }

                Button(
                    onClick = {
                        coroutineScope.launch {
                            isLoading = true
                            analyzedVideo = null
                            selectedFormatId = null
                            downloadState = DownloadUiState.Idle
                            try {
                                analyzedVideo = onAnalyze(url.trim())
                            } catch (error: Throwable) {
                                val detail = error.safeMessage()
                                snackbarHostState.showSnackbar(
                                    if (detail == null) analysisFailedMessage
                                    else "$analysisFailedMessage: $detail",
                                )
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = url.isNotEmpty() && !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.analyze))
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                analyzedVideo?.let { video ->
                    MetadataCard(video)
                    FormatSelectionSection(
                        formats = video.formats,
                        selectedFormatId = selectedFormatId,
                        downloadState = downloadState,
                        onFormatSelected = {
                            selectedFormatId = it
                            downloadState = DownloadUiState.Idle
                        },
                        onDownload = { selectedFormat ->
                            coroutineScope.launch {
                                when {
                                    selectedFormat.hasVideo && !selectedFormat.hasAudio -> {
                                        snackbarHostState.showSnackbar(videoOnlyMessage)
                                    }
                                    !selectedFormat.hasVideo && selectedFormat.hasAudio -> {
                                        snackbarHostState.showSnackbar(audioOnlyMessage)
                                    }
                                    else -> {
                                        downloadState = DownloadUiState.Downloading(null)
                                        try {
                                            val result = onDownload(
                                                video.webpageUrl ?: url.trim(),
                                                video.title,
                                                selectedFormat,
                                            ) { progress ->
                                                coroutineScope.launch {
                                                    downloadState = DownloadUiState.Downloading(
                                                        progress.toInt().coerceIn(0, 100),
                                                    )
                                                }
                                            }
                                            downloadState = DownloadUiState.Complete(result)
                                            snackbarHostState.showSnackbar(downloadCompleteMessage)
                                        } catch (_: DownloadCanceledException) {
                                            downloadState = DownloadUiState.Idle
                                            snackbarHostState.showSnackbar(downloadCanceledMessage)
                                        } catch (error: Throwable) {
                                            val detail = error.safeMessage()
                                            downloadState = DownloadUiState.Failed(detail)
                                            snackbarHostState.showSnackbar(
                                                if (detail == null) downloadFailedMessage
                                                else "$downloadFailedMessage: $detail",
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        onCancel = {
                            if (!onCancelDownload()) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(cancelFailedMessage)
                                }
                            }
                        },
                    )
                }

                Text(
                    text = stringResource(R.string.supported_sites_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.privacy_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun MetadataCard(video: AnalyzedVideo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            video.thumbnailUrl?.let { thumbnailUrl ->
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = stringResource(R.string.video_thumbnail),
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    contentScale = ContentScale.Crop,
                )
            }

            MetadataField(
                stringResource(R.string.title_label),
                video.title.ifBlank { stringResource(R.string.untitled_video) },
            )
            video.uploader?.let { MetadataField(stringResource(R.string.uploader_label), it) }
            video.durationSeconds?.let {
                MetadataField(stringResource(R.string.duration_label), formatDuration(it))
            }
            video.site?.let { MetadataField(stringResource(R.string.site_label), it) }
            video.webpageUrl?.let { MetadataField(stringResource(R.string.webpage_label), it) }

        }
    }
}

@Composable
private fun FormatSelectionSection(
    formats: List<SelectableFormat>,
    selectedFormatId: String?,
    downloadState: DownloadUiState,
    onFormatSelected: (String) -> Unit,
    onDownload: (SelectableFormat) -> Unit,
    onCancel: () -> Unit,
) {
    val videoFormats = formats.filter { it.hasVideo }
    val audioFormats = formats.filter { !it.hasVideo && it.hasAudio }
    val selectedFormat = formats.firstOrNull { it.formatId == selectedFormatId }
    val isDownloading = downloadState is DownloadUiState.Downloading

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.video_quality),
            style = MaterialTheme.typography.titleMedium,
        )

        if (videoFormats.isEmpty()) {
            Text(
                text = stringResource(R.string.no_video_formats),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            videoFormats.forEach { format ->
                FormatRow(
                    format = format,
                    title = format.height?.let { "${it}p" }
                        ?: stringResource(R.string.video_format),
                    selected = selectedFormatId == format.formatId,
                    enabled = !isDownloading,
                    onSelected = { onFormatSelected(format.formatId) },
                )
            }
        }

        if (audioFormats.isNotEmpty()) {
            Text(
                text = stringResource(R.string.audio),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            audioFormats.forEach { format ->
                FormatRow(
                    format = format,
                    title = stringResource(R.string.best_audio),
                    selected = selectedFormatId == format.formatId,
                    enabled = !isDownloading,
                    onSelected = { onFormatSelected(format.formatId) },
                )
            }
        }

        when (downloadState) {
            is DownloadUiState.Downloading -> {
                Text(
                    text = downloadState.percent?.let {
                        stringResource(R.string.downloading_percent, it)
                    } ?: stringResource(R.string.downloading),
                    style = MaterialTheme.typography.titleSmall,
                )
                downloadState.percent?.let { percent ->
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
            is DownloadUiState.Complete -> {
                Text(
                    text = stringResource(R.string.download_complete),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = downloadState.file.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatFileSize(downloadState.file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is DownloadUiState.Failed -> {
                Text(
                    text = downloadState.detail?.let {
                        stringResource(R.string.download_failed_with_detail, it)
                    } ?: stringResource(R.string.download_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            DownloadUiState.Idle -> Unit
        }

        Button(
            onClick = { selectedFormat?.let(onDownload) },
            enabled = selectedFormat != null && !isDownloading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.download))
        }
    }
}

@Composable
private fun FormatRow(
    format: SelectableFormat,
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onSelected: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onSelected,
                role = Role.RadioButton,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(
                modifier = Modifier.padding(start = 12.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = formatTechnicalDetails(format),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = when {
                        format.hasVideo && format.hasAudio -> stringResource(R.string.video_and_audio)
                        format.hasVideo -> stringResource(R.string.video_only_merge)
                        else -> stringResource(R.string.audio_only)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                format.fileSizeBytes?.let { size ->
                    Text(
                        text = if (format.fileSizeIsApproximate) {
                            stringResource(R.string.approximate_size, formatFileSize(size))
                        } else {
                            formatFileSize(size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatTechnicalDetails(format: SelectableFormat): String = buildList {
    format.extension?.let { add(it.uppercase()) }
    format.fps?.let { add("$it fps") }
    format.bitrateKbps?.let { add("$it kbps") }
}.ifEmpty { listOf("Format ${format.formatId}") }.joinToString(" • ")

private fun formatFileSize(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1.0) "%.1f MB".format(megabytes)
    else "%.1f KB".format(bytes / 1024.0)
}

@Composable
private fun MetadataField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
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

private sealed interface DownloadUiState {
    data object Idle : DownloadUiState
    data class Downloading(val percent: Int?) : DownloadUiState
    data class Complete(val file: DownloadedFile) : DownloadUiState
    data class Failed(val detail: String?) : DownloadUiState
}
