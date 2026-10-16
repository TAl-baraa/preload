package com.preload.app

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.os.LocaleListCompat

private enum class AppDestination {
    Home,
    Downloads,
    Settings,
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
                DownloadsScreen(downloadJobs, onCancelDownload)
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
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.downloads_title), style = MaterialTheme.typography.headlineMedium)
        if (jobs.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                EmptyDownloadsCard()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(jobs, key = { it.id }) { job ->
                    DownloadJobCard(job, onCancelDownload)
                }
            }
        }
    }
}

@Composable
private fun EmptyDownloadsCard() {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.no_downloads_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.no_downloads_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
}

@Composable
private fun DownloadJobCard(job: DownloadJob, onCancelDownload: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(job.title, style = MaterialTheme.typography.titleMedium)
            Text(
                job.formatLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                job.status.label(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (job.status == DownloadStatus.DOWNLOADING) {
                job.progressPercent?.let { percent ->
                    LinearProgressIndicator(
                        progress = { (percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.downloading_percent, percent.toInt().coerceIn(0, 100)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.DOWNLOADING) {
                OutlinedButton(
                    onClick = { onCancelDownload(job.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun DownloadStatus.label(): String = when (this) {
    DownloadStatus.QUEUED -> stringResource(R.string.download_status_queued)
    DownloadStatus.DOWNLOADING -> stringResource(R.string.download_status_downloading)
    DownloadStatus.COMPLETED -> stringResource(R.string.download_status_completed)
    DownloadStatus.FAILED -> stringResource(R.string.download_status_failed)
    DownloadStatus.CANCELLED -> stringResource(R.string.download_status_cancelled)
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
