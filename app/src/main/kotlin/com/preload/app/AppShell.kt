package com.preload.app

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.core.os.LocaleListCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.util.Locale

enum class OpenDownloadResult {
    Opened,
    Missing,
    NoHandler,
}

private enum class AppDestination {
    Home,
    Downloads,
    Settings,
}

private enum class DownloadsTab {
    Active,
    Completed,
    Failed,
}

private data class DestinationItem(
    val destination: AppDestination,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun PreloadAppShell(
    themePreference: ThemePreference,
    downloadJobs: List<DownloadJob>,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onRemoveDownload: (String) -> Unit,
    onOpenDownload: (DownloadJob) -> OpenDownloadResult,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    home: @Composable () -> Unit,
) {
    var selectedDestinationName by rememberSaveable { mutableStateOf(AppDestination.Home.name) }
    val selectedDestination = AppDestination.entries
        .firstOrNull { it.name == selectedDestinationName }
        ?: AppDestination.Home
    val destinations = listOf(
        DestinationItem(AppDestination.Home, stringResource(R.string.nav_home), Icons.Default.Home),
        DestinationItem(
            AppDestination.Downloads,
            stringResource(R.string.nav_downloads),
            Icons.Default.KeyboardArrowDown,
        ),
        DestinationItem(
            AppDestination.Settings,
            stringResource(R.string.nav_settings),
            Icons.Default.Settings,
        ),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { item ->
                    NavigationBarItem(
                        selected = selectedDestination == item.destination,
                        onClick = { selectedDestinationName = item.destination.name },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            PersistentDestination(selectedDestination == AppDestination.Home) { home() }
            PersistentDestination(selectedDestination == AppDestination.Downloads) {
                DownloadsScreen(
                    jobs = downloadJobs,
                    onCancelDownload = onCancelDownload,
                    onRetryDownload = onRetryDownload,
                    onRemoveDownload = onRemoveDownload,
                    onOpenDownload = onOpenDownload,
                )
            }
            PersistentDestination(selectedDestination == AppDestination.Settings) {
                SettingsScreen(
                    themePreference = themePreference,
                    onThemePreferenceChanged = onThemePreferenceChanged,
                )
            }
        }
    }
}

@Composable
private fun PersistentDestination(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .alpha(if (visible) 1f else 0f)
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
    ) {
        content()
    }
}

@Composable
private fun DownloadsScreen(
    jobs: List<DownloadJob>,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onRemoveDownload: (String) -> Unit,
    onOpenDownload: (DownloadJob) -> OpenDownloadResult,
) {
    val active = jobs.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
    val queued = jobs.filter { it.status == DownloadStatus.QUEUED }.sortedBy { it.createdAt }
    val completed = jobs.filter { it.status == DownloadStatus.COMPLETED }
        .sortedByDescending { it.completedAt ?: it.createdAt }
    val terminal = jobs.filter {
        it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED
    }.sortedByDescending { it.completedAt ?: it.createdAt }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val missingMessage = stringResource(R.string.download_file_missing)
    val noHandlerMessage = stringResource(R.string.download_no_open_app)
    var completedPendingRemoval by remember { mutableStateOf<DownloadJob?>(null) }
    var selectedTabName by rememberSaveable { mutableStateOf(DownloadsTab.Active.name) }
    val selectedTab = DownloadsTab.entries.firstOrNull { it.name == selectedTabName }
        ?: DownloadsTab.Active
    val tabs = listOf(
        DownloadsTab.Active to stringResource(R.string.downloads_tab_active),
        DownloadsTab.Completed to stringResource(R.string.downloads_tab_completed),
        DownloadsTab.Failed to stringResource(R.string.downloads_tab_failed),
    )

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            Text(
                stringResource(R.string.downloads_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
            SecondaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                tabs.forEach { (tab, label) ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTabName = tab.name },
                        text = { Text(label, maxLines = 1) },
                    )
                }
            }
            when (selectedTab) {
                DownloadsTab.Active -> {
                    if (active == null && queued.isEmpty()) {
                        DownloadTabEmptyState(R.string.downloads_empty_active)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            active?.let { job ->
                                item("active-heading") {
                                    DownloadSectionHeading(R.string.downloads_section_active)
                                }
                                item(job.id) {
                                    ActiveDownloadCard(job) { onCancelDownload(job.id) }
                                }
                            }
                            if (queued.isNotEmpty()) {
                                item("queue-heading") {
                                    DownloadSectionHeading(R.string.downloads_section_queue)
                                }
                                items(queued, key = { it.id }) { job ->
                                    QueuedDownloadCard(job, queued.indexOf(job) + 1) {
                                        onCancelDownload(job.id)
                                    }
                                }
                            }
                        }
                    }
                }
                DownloadsTab.Completed -> {
                    if (completed.isEmpty()) {
                        DownloadTabEmptyState(R.string.downloads_empty_completed)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                        items(completed, key = { it.id }) { job ->
                            CompletedDownloadCard(
                                job = job,
                                onOpen = {
                                    when (onOpenDownload(job)) {
                                        OpenDownloadResult.Opened -> Unit
                                        OpenDownloadResult.Missing -> scope.launch {
                                            snackbarHostState.showSnackbar(missingMessage)
                                        }
                                        OpenDownloadResult.NoHandler -> scope.launch {
                                            snackbarHostState.showSnackbar(noHandlerMessage)
                                        }
                                    }
                                },
                                onRemove = { completedPendingRemoval = job },
                            )
                        }
                    }
                    }
                }
                DownloadsTab.Failed -> {
                    if (terminal.isEmpty()) {
                        DownloadTabEmptyState(R.string.downloads_empty_failed)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                        items(terminal, key = { it.id }) { job ->
                            TerminalDownloadCard(
                                job,
                                onRetry = { onRetryDownload(job.id) },
                                onRemove = { onRemoveDownload(job.id) },
                            )
                        }
                    }
                    }
                }
            }
        }
    }

    completedPendingRemoval?.let { job ->
        AlertDialog(
            onDismissRequest = { completedPendingRemoval = null },
            title = { Text(stringResource(R.string.remove_history_title)) },
            text = { Text(stringResource(R.string.remove_history_message)) },
            confirmButton = {
                TextButton(onClick = {
                    completedPendingRemoval = null
                    onRemoveDownload(job.id)
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { completedPendingRemoval = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun DownloadTabEmptyState(messageRes: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(messageRes), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun DownloadSectionHeading(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun ActiveDownloadCard(job: DownloadJob, onCancel: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DownloadIdentity(job, prominent = true)
            job.progressPercent?.let { percent ->
                LinearProgressIndicator(
                    progress = { (percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            val transferDetails = listOfNotNull(
                job.bytesPerSecond?.let {
                    stringResource(R.string.download_speed_value, formatDownloadBytes(it))
                },
                job.etaSeconds?.let {
                    stringResource(R.string.download_eta_value, formatDownloadEta(it))
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    job.progressPercent?.let {
                        stringResource(R.string.download_percent_value, it.toInt().coerceIn(0, 100))
                    }.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (transferDetails.isNotEmpty()) {
                    SupportingText(transferDetails.joinToString(" • "))
                }
            }
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun QueuedDownloadCard(job: DownloadJob, position: Int, onCancel: () -> Unit) {
    DownloadSurfaceCard {
        DownloadIdentity(job)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.download_queue_position, position),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun CompletedDownloadCard(job: DownloadJob, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DownloadArtwork(job, compact = true)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = job.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            val metadata = listOfNotNull(
                job.extension?.uppercase(),
                job.totalBytes?.let(::formatDownloadBytes),
            )
            if (metadata.isNotEmpty()) SupportingText(metadata.joinToString(" • "))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.more_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_from_history)) },
                        onClick = {
                            menuExpanded = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalDownloadCard(job: DownloadJob, onRetry: () -> Unit, onRemove: () -> Unit) {
    DownloadSurfaceCard {
        Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
        SupportingText(job.formatLabel)
        Text(
            text = if (job.status == DownloadStatus.FAILED) {
                stringResource(R.string.download_status_failed)
            } else {
                stringResource(R.string.download_status_cancelled)
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (job.status == DownloadStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (job.status == DownloadStatus.FAILED) {
            job.errorMessage?.takeIf { it.isNotBlank() }?.let { SupportingText(it) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun DownloadSurfaceCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            content = content,
        )
    }
}

@Composable
private fun DownloadIdentity(job: DownloadJob, prominent: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        DownloadArtwork(job)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                job.title,
                style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                maxLines = 2,
            )
            SupportingText(job.formatLabel)
        }
    }
}

@Composable
private fun DownloadArtwork(job: DownloadJob, compact: Boolean = false) {
    Box(
        modifier = Modifier
            .size(
                width = if (compact) 72.dp else 96.dp,
                height = if (compact) 52.dp else 58.dp,
            )
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (!job.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = job.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = if (job.mediaKind == DownloadMediaKind.AUDIO) {
                    Icons.Default.KeyboardArrowDown
                } else {
                    Icons.Default.PlayArrow
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SupportingText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
    )
}

private fun formatDownloadBytes(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1.0) String.format(Locale.getDefault(), "%.1f MB", megabytes)
    else String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
}

private fun formatDownloadEta(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun SettingsScreen(
    themePreference: ThemePreference,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
) {
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    val configuredLanguage = remember(AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
        val language = AppCompatDelegate.getApplicationLocales().get(0)?.language
        when (language) {
            "en" -> LanguagePreference.English
            "ar" -> LanguagePreference.Arabic
            else -> LanguagePreference.System
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        PreferenceSection(
            title = stringResource(R.string.appearance_section),
            label = stringResource(R.string.theme_setting),
            value = themePreference.label(),
            onClick = { showThemeDialog = true },
        )
        PreferenceSection(
            title = stringResource(R.string.language_section),
            label = stringResource(R.string.language_setting),
            value = configuredLanguage.label(),
            onClick = { showLanguageDialog = true },
        )
    }

    if (showThemeDialog) {
        PreferenceDialog(
            title = stringResource(R.string.theme_setting),
            options = ThemePreference.entries.map { it to it.label() },
            selected = themePreference,
            onSelected = {
                onThemePreferenceChanged(it)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showLanguageDialog) {
        PreferenceDialog(
            title = stringResource(R.string.language_setting),
            options = LanguagePreference.entries.map { it to it.label() },
            selected = configuredLanguage,
            onSelected = {
                showLanguageDialog = false
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(it.languageTag),
                )
            },
            onDismiss = { showLanguageDialog = false },
        )
    }
}

@Composable
private fun ThemePreference.label(): String = when (this) {
    ThemePreference.System -> stringResource(R.string.preference_system)
    ThemePreference.Light -> stringResource(R.string.theme_light)
    ThemePreference.Dark -> stringResource(R.string.theme_dark)
}

@Composable
private fun LanguagePreference.label(): String = when (this) {
    LanguagePreference.System -> stringResource(R.string.preference_system)
    LanguagePreference.English -> stringResource(R.string.language_english)
    LanguagePreference.Arabic -> stringResource(R.string.language_arabic)
}

@Composable
private fun <T> PreferenceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (option, label) ->
                    PreferenceRadioRow(
                        label = label,
                        selected = option == selected,
                        onClick = { onSelected(option) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun PreferenceSection(
    title: String,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(text = label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreferenceRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
