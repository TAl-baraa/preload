package com.preload.app

import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
                DownloadsScreen()
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
private fun DownloadsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(stringResource(R.string.downloads_title), style = MaterialTheme.typography.headlineMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
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
}

@Composable
private fun SettingsScreen(
    themePreference: ThemePreference,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
) {
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
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        PreferenceSection(
            title = stringResource(R.string.appearance_section),
            label = stringResource(R.string.theme_setting),
        ) {
            PreferenceRadioRow(
                label = stringResource(R.string.preference_system),
                selected = themePreference == ThemePreference.System,
                onClick = { onThemePreferenceChanged(ThemePreference.System) },
            )
            PreferenceRadioRow(
                label = stringResource(R.string.theme_light),
                selected = themePreference == ThemePreference.Light,
                onClick = { onThemePreferenceChanged(ThemePreference.Light) },
            )
            PreferenceRadioRow(
                label = stringResource(R.string.theme_dark),
                selected = themePreference == ThemePreference.Dark,
                onClick = { onThemePreferenceChanged(ThemePreference.Dark) },
            )
        }
        PreferenceSection(
            title = stringResource(R.string.language_section),
            label = stringResource(R.string.language_setting),
        ) {
            LanguagePreference.entries.forEach { preference ->
                val label = when (preference) {
                    LanguagePreference.System -> stringResource(R.string.preference_system)
                    LanguagePreference.English -> stringResource(R.string.language_english)
                    LanguagePreference.Arabic -> stringResource(R.string.language_arabic)
                }
                PreferenceRadioRow(
                    label = label,
                    selected = configuredLanguage == preference,
                    onClick = {
                        AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags(preference.languageTag),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PreferenceSection(
    title: String,
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                content()
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
