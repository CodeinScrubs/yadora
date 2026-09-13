package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/**
 * Minimal theme customization: appearance mode (System / Light / Dark) + an accent color.
 * Saves to the shared prefs and calls [onThemeChange] so the whole app recolors instantly.
 * Accent "" means the default Quiet-Mastery indigo; any other value is a parseable #RRGGBB hex.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit, onThemeChange: (String, String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE) }
    val strings = com.example.ui.i18n.LocalStrings.current
    val isFa = strings.languageCode == "fa"

    var mode by remember { mutableStateOf(sp.getString("theme_mode", "system") ?: "system") }
    var accent by remember { mutableStateOf(sp.getString("accent_color", "") ?: "") }

    val modes = listOf(
        "system" to (if (isFa) "سیستم" else if (strings.languageCode == "de") "System" else "System"),
        "light" to (if (isFa) "روشن" else if (strings.languageCode == "de") "Hell" else "Light"),
        "dark" to (if (isFa) "تیره" else if (strings.languageCode == "de") "Dunkel" else "Dark")
    )
    // storage value -> swatch color. "" is the default sage brand. A small, curated set that all
    // harmonize with the warm-paper surface (no neon); none collide with the reserved rating hues.
    // A swatch carries its meaning ONLY in its color, so each needs a spoken name — otherwise a
    // screen-reader user hears four identical unlabeled buttons and cannot pick a color at all.
    fun accentName(fa: String, de: String, en: String) =
        if (isFa) fa else if (strings.languageCode == "de") de else en
    val accents = listOf(
        Triple("", com.example.ui.theme.Sage, accentName("مریم‌گلی", "Salbei", "Sage")),
        Triple("#33689B", Color(0xFF33689B), accentName("آبی فولادی", "Stahlblau", "Steel blue")),
        Triple("#6B5B95", Color(0xFF6B5B95), accentName("آلویی", "Pflaume", "Muted plum")),
        Triple("#A8554E", Color(0xFFA8554E), accentName("خاک‌رس", "Ton", "Clay")),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isFa) "تم و رنگ‌ها" else if (strings.languageCode == "de") "Design & Farben" else "Theme & Colors") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (isFa) "بازگشت" else if (strings.languageCode == "de") "Zurück" else "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
        ) {
            Text(
                text = if (isFa) "حالت" else if (strings.languageCode == "de") "Darstellung" else "Appearance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { (value, label) ->
                    FilterChip(
                        selected = mode == value,
                        onClick = {
                            mode = value
                            sp.edit { putString("theme_mode", value) }
                            onThemeChange(value, accent)
                        },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = if (isFa) "رنگ اصلی" else if (strings.languageCode == "de") "Akzentfarbe" else "Accent color",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            // selectableGroup + Role.RadioButton so assistive tech announces this as one exclusive
            // choice ("Sage, selected, 1 of 4") instead of four unrelated taps. Selection is still
            // shown visually by the heavier ring, but color alone is no longer the only channel.
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.selectableGroup()
            ) {
                accents.forEach { (hex, color, name) ->
                    val selected = accent == hex
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                            .selectable(
                                selected = selected,
                                role = androidx.compose.ui.semantics.Role.RadioButton,
                                onClick = {
                                    accent = hex
                                    sp.edit { putString("accent_color", hex) }
                                    onThemeChange(mode, hex)
                                },
                            )
                            .semantics { contentDescription = name }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = if (isFa) "تغییرات بلافاصله اعمال می‌شوند." else if (strings.languageCode == "de") "Änderungen gelten sofort." else "Changes apply instantly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
