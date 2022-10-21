package com.example.androidmic.ui.home.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.androidmic.R
import com.example.androidmic.ui.Event
import com.example.androidmic.ui.MainViewModel
import com.example.androidmic.ui.home.dialog.DialogIpPort
import com.example.androidmic.ui.home.dialog.DialogMode
import com.example.androidmic.ui.home.dialog.DialogTheme
import com.example.androidmic.utils.Modes.Companion.MODE_BLUETOOTH
import com.example.androidmic.utils.Modes.Companion.MODE_USB
import com.example.androidmic.utils.Modes.Companion.MODE_WIFI
import com.example.androidmic.utils.States
import com.example.androidmic.utils.Themes.Companion.DARK_THEME
import com.example.androidmic.utils.Themes.Companion.LIGHT_THEME
import com.example.androidmic.utils.Themes.Companion.SYSTEM_THEME

@Composable
fun DrawerHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp)
            .padding(start = 30.dp)
    ) {
        Text(
            text = stringResource(id = R.string.drawerHeader),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
fun DrawerBody(mainViewModel: MainViewModel, uiStates: States.UiStates) {

    DialogIpPort(mainViewModel = mainViewModel, uiStates = uiStates)
    DialogMode(mainViewModel = mainViewModel, uiStates = uiStates)
    DialogTheme(mainViewModel = mainViewModel, uiStates = uiStates)

    DrawerBodyList(
        items = listOf(
            MenuItem(
                id = R.string.drawerIpPort,
                title = stringResource(id = R.string.drawerIpPort),
                contentDescription = "set ip and port",
                icon = painterResource(id = R.drawable.ic_baseline_wifi_24)

            ),
            MenuItem(
                id = R.string.drawerMode,
                title = stringResource(id = R.string.drawerMode),
                contentDescription = "set mode",
                icon = rememberVectorPainter(Icons.Default.Settings)
            ),
            MenuItem(
                id = R.string.drawerTheme,
                title = stringResource(id = R.string.drawerTheme),
                contentDescription = "set theme",
                icon = rememberVectorPainter(Icons.Default.Settings)
            )
        ),
        onItemClick = {
            mainViewModel.onEvent(Event.ShowDialog(it.id))
        },
        uiStates = uiStates
    )
}


@Composable
fun DrawerBodyList(
    items: List<MenuItem>,
    onItemClick: (MenuItem) -> Unit,
    modifier: Modifier = Modifier,
    uiStates: States.UiStates
) {
    LazyColumn(modifier) {
        item {
            DrawerHeader()
            Divider(color = MaterialTheme.colorScheme.onBackground)
        }
        items(items) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onItemClick(item)
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = item.icon,
                    contentDescription = item.contentDescription,
                    tint = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    when (item.id) {
                        R.string.drawerIpPort -> {
                            Text(
                                text = uiStates.IP + ":" + uiStates.PORT,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        R.string.drawerMode -> {
                            Text(
                                text = when (uiStates.mode) {
                                    MODE_WIFI -> stringResource(id = R.string.mode_wifi)
                                    MODE_BLUETOOTH -> stringResource(id = R.string.mode_bluetooth)
                                    MODE_USB -> stringResource(id = R.string.mode_usb)
                                    else -> "NONE"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        R.string.drawerTheme -> {
                            Text(
                                text = when (uiStates.theme) {
                                    SYSTEM_THEME -> stringResource(id = R.string.system_theme)
                                    LIGHT_THEME -> stringResource(id = R.string.light_theme)
                                    DARK_THEME -> stringResource(id = R.string.dark_theme)
                                    else -> "NONE"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
            Divider(color = MaterialTheme.colorScheme.onBackground)
        }
    }
}