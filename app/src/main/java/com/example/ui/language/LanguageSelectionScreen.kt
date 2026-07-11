package com.example.ui.language

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun LanguageSelectionScreen(onLanguageSelected: (String) -> Unit) {
    val context = LocalContext.current
    var selectedLang by remember { mutableStateOf("en") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val strings = com.example.ui.i18n.LocalStrings.current
        val freshLightBlue = com.example.ui.theme.Sage // brand sage (first-run screen stays on-brand)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Translate,
                contentDescription = "Language Icon",
                tint = freshLightBlue,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Language",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = freshLightBlue
            )
        }
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedCard(
            onClick = { selectedLang = "en" },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = if (selectedLang == "en") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
        ) {
            Text(
                "English",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selectedLang == "en") FontWeight.Bold else FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedCard(
            onClick = { selectedLang = "fa" },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = if (selectedLang == "fa") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
        ) {
            Text(
                "فارسی (Persian)",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selectedLang == "fa") FontWeight.Bold else FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onLanguageSelected(selectedLang) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        ) {
            val strings = com.example.ui.i18n.LocalStrings.current
            Text(strings.continueBtn)
        }
    }
}
