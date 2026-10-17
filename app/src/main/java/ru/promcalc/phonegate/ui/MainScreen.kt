package ru.promcalc.phonegate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ru.promcalc.phonegate.ui.access.AccessChecks
import ru.promcalc.phonegate.ui.access.AccessScreen
import ru.promcalc.phonegate.ui.logs.LogsScreen
import ru.promcalc.phonegate.ui.settings.SettingsScreen

enum class MainTab(val title: String) {
    SETTINGS("Настройки"),
    LOGS("Логи"),
    ACCESS("Доступ"),
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var selectedIndex by rememberSaveable {
        // Первый запуск начинается с экрана доступа: без разрешений сценарии не работают.
        mutableIntStateOf(
            if (AccessChecks.hasMissingCritical(context)) MainTab.ACCESS.ordinal else MainTab.SETTINGS.ordinal,
        )
    }
    val tabs = MainTab.entries

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    MainTab.SETTINGS -> Icons.Filled.Settings
                                    MainTab.LOGS -> Icons.AutoMirrored.Filled.List
                                    MainTab.ACCESS -> Icons.Filled.Check
                                },
                                contentDescription = tab.title,
                            )
                        },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (tabs[selectedIndex]) {
                MainTab.SETTINGS -> SettingsScreen()
                MainTab.LOGS -> LogsScreen()
                MainTab.ACCESS -> AccessScreen()
            }
        }
    }
}
