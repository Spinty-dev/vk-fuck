package app.vkturn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vkturn.proxy.RouteState
import app.vkturn.proxy.RouteStatus
import app.vkturn.ui.components.BottomNav
import app.vkturn.ui.components.LogsSheet
import app.vkturn.ui.components.MiniStatusIndicator
import app.vkturn.ui.components.ProfilePickerSheet
import app.vkturn.ui.components.SideNav
import app.vkturn.ui.nav.TopDest
import app.vkturn.ui.screens.DashboardCompact
import app.vkturn.ui.screens.DashboardExpanded
import app.vkturn.ui.screens.DashboardState
import app.vkturn.ui.screens.LogsScreen
import app.vkturn.ui.screens.ProfilesScreen
import app.vkturn.ui.screens.SettingsScreen
import app.vkturn.ui.screens.WingsvImportDialog
import app.vkturn.ui.theme.AmneziaColors
import app.vkturn.ui.theme.AmneziaTheme
import app.vkturn.vm.AppViewModel
import app.vkturn.vm.Profile

/**
 * Single entry-point for both platforms. Decides desktop vs mobile shell
 * by measuring [BoxWithConstraints.maxWidth] and routes through one shared
 * [TopDest] state for both.
 */
@Composable
fun App(
    viewModel: AppViewModel,
    onSaveConfig: (String, String) -> Unit,
) {
    AmneziaTheme {
        val config by viewModel.config.collectAsState()
        val statuses by viewModel.statuses.collectAsState()
        val profiles by viewModel.profiles.collectAsState()
        val activeProfile by viewModel.activeProfile.collectAsState()

        var dest by remember { mutableStateOf(TopDest.Dashboard) }
        var logsOpen by remember { mutableStateOf(false) }
        var pickerOpen by remember { mutableStateOf(false) }
        var importOpen by remember { mutableStateOf(false) }

        val activeStatus = activeProfile?.routeId?.let { statuses[it] }
        val activeState = activeStatus?.state ?: RouteState.IDLE
        val activeRoute = activeProfile?.let { p ->
            config.server(p.serverId)
                ?.identities?.firstOrNull { it.id == p.identityId }
                ?.routes?.firstOrNull { it.id == p.routeId }
        }

        val dashboardState = DashboardState(
            activeProfile = activeProfile,
            routeState = activeState,
            status = activeStatus,
            route = activeRoute,
        )

        val connect: () -> Unit = { viewModel.connectActive() }
        val disconnect: () -> Unit = { viewModel.disconnectAll() }
        val openImport: () -> Unit = { importOpen = true }
        val openLogs: () -> Unit = { logsOpen = true }
        val openPicker: () -> Unit = { pickerOpen = true }
        val gotoProfiles: () -> Unit = { dest = TopDest.Profiles }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val size = windowSizeFor(maxWidth)
            Surface(color = AmneziaColors.Background, modifier = Modifier.fillMaxSize()) {
                when (size) {
                    WindowSize.Compact -> CompactShell(
                        dest = dest,
                        onDest = { dest = it },
                        dashboardState = dashboardState,
                        viewModel = viewModel,
                        onSaveConfig = onSaveConfig,
                        activeStatusLabel = compactStatusLabel(activeProfile, activeState),
                        onConnect = connect,
                        onDisconnect = disconnect,
                        onOpenImport = openImport,
                        onOpenLogs = openLogs,
                        onOpenPicker = openPicker,
                        onEditActiveProfile = {
                            dest = TopDest.Profiles
                        },
                    )
                    WindowSize.Expanded -> ExpandedShell(
                        dest = dest,
                        onDest = { dest = it },
                        dashboardState = dashboardState,
                        viewModel = viewModel,
                        onSaveConfig = onSaveConfig,
                        activeStatusLabel = compactStatusLabel(activeProfile, activeState),
                        onConnect = connect,
                        onDisconnect = disconnect,
                        onOpenImport = openImport,
                        onOpenLogs = openLogs,
                        onOpenPicker = openPicker,
                        onEditActiveProfile = gotoProfiles,
                    )
                }
            }
        }

        if (importOpen) {
            WingsvImportDialog(
                viewModel = viewModel,
                onDismiss = { importOpen = false },
                onImported = { _, _, _ ->
                    importOpen = false
                    dest = TopDest.Profiles
                },
            )
        }

        if (logsOpen) {
            LogsSheet(
                logs = viewModel.logs,
                onClear = viewModel::clearLogs,
                onDismiss = { logsOpen = false },
            )
        }

        if (pickerOpen) {
            ProfilePickerSheet(
                profiles = profiles,
                activeProfileId = activeProfile?.routeId,
                statuses = statuses,
                onPick = { picked ->
                    viewModel.activateProfile(picked)
                    pickerOpen = false
                },
                onOpenImport = {
                    pickerOpen = false
                    importOpen = true
                },
                onDismiss = { pickerOpen = false },
            )
        }
    }
}

// ---- mobile ------------------------------------------------------------

@Composable
private fun CompactShell(
    dest: TopDest,
    onDest: (TopDest) -> Unit,
    dashboardState: DashboardState,
    viewModel: AppViewModel,
    onSaveConfig: (String, String) -> Unit,
    activeStatusLabel: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenPicker: () -> Unit,
    onEditActiveProfile: () -> Unit,
) {
    val config by viewModel.config.collectAsState()
    val statuses by viewModel.statuses.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        MobileAppBar(
            title = when (dest) {
                TopDest.Dashboard -> "vkturn"
                TopDest.Profiles -> "Профили"
                TopDest.Logs -> "Журнал"
                TopDest.Settings -> "Ещё"
            },
            statusState = dashboardState.routeState,
            statusLabel = activeStatusLabel,
            onOpenLogs = onOpenLogs,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (dest) {
                TopDest.Dashboard -> DashboardCompact(
                    state = dashboardState,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onSwitchProfile = onOpenPicker,
                    onEditProfile = onEditActiveProfile,
                    onImportWingsV = onOpenImport,
                    onOpenLogs = onOpenLogs,
                    onRetry = onConnect,
                )
                TopDest.Profiles -> ProfilesScreen(
                    config = config,
                    statuses = statuses,
                    viewModel = viewModel,
                    onSaveConfig = onSaveConfig,
                    onOpenImport = onOpenImport,
                )
                TopDest.Logs -> LogsScreen(
                    logs = viewModel.logs,
                    config = config,
                    onClear = viewModel::clearLogs,
                )
                TopDest.Settings -> SettingsScreen(
                    config = config,
                    viewModel = viewModel,
                    onOpenImport = onOpenImport,
                    onOpenLogs = onOpenLogs,
                )
            }
        }
        BottomNav(current = dest, onSelect = onDest)
    }
}

@Composable
private fun MobileAppBar(
    title: String,
    statusState: RouteState,
    statusLabel: String,
    onOpenLogs: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().statusBarsPadding(),
        color = AmneziaColors.Background,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniStatusIndicator(
                state = statusState,
                label = statusLabel,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AmneziaColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenLogs) {
                Icon(
                    Icons.AutoMirrored.Filled.Article,
                    contentDescription = "Журнал",
                    tint = AmneziaColors.TextSecondary,
                )
            }
        }
    }
}

// ---- desktop / tablet --------------------------------------------------

@Composable
private fun ExpandedShell(
    dest: TopDest,
    onDest: (TopDest) -> Unit,
    dashboardState: DashboardState,
    viewModel: AppViewModel,
    onSaveConfig: (String, String) -> Unit,
    activeStatusLabel: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenPicker: () -> Unit,
    onEditActiveProfile: () -> Unit,
) {
    val config by viewModel.config.collectAsState()
    val statuses by viewModel.statuses.collectAsState()

    Row(modifier = Modifier.fillMaxSize()) {
        SideNav(
            current = dest,
            onSelect = onDest,
            statusState = dashboardState.routeState,
            statusLabel = activeStatusLabel,
        )
        Column(modifier = Modifier.weight(1f).fillMaxHeight().background(AmneziaColors.Background)) {
            when (dest) {
                TopDest.Dashboard -> DashboardExpanded(
                    state = dashboardState,
                    logs = viewModel.logs,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onSwitchProfile = onOpenPicker,
                    onEditProfile = onEditActiveProfile,
                    onImportWingsV = onOpenImport,
                    onOpenLogs = { onDest(TopDest.Logs) },
                    onRetry = onConnect,
                )
                TopDest.Profiles -> Box(modifier = Modifier.fillMaxSize().padding(PaddingValues(horizontal = 0.dp))) {
                    ProfilesScreen(
                        config = config,
                        statuses = statuses,
                        viewModel = viewModel,
                        onSaveConfig = onSaveConfig,
                        onOpenImport = onOpenImport,
                    )
                }
                TopDest.Logs -> LogsScreen(
                    logs = viewModel.logs,
                    config = config,
                    onClear = viewModel::clearLogs,
                )
                TopDest.Settings -> SettingsScreen(
                    config = config,
                    viewModel = viewModel,
                    onOpenImport = onOpenImport,
                    onOpenLogs = onOpenLogs,
                )
            }
        }
    }
}

private fun compactStatusLabel(profile: Profile?, state: RouteState): String = when {
    profile == null -> "Не настроено"
    state == RouteState.CONNECTED -> "Подключено · ${profile.serverName}"
    state == RouteState.CONNECTING -> "Соединение…"
    state == RouteState.STARTING -> "Запуск…"
    state == RouteState.CAPTCHA -> "Капча"
    state == RouteState.LOCKOUT -> "Ожидание"
    state == RouteState.STOPPING -> "Отключение…"
    state == RouteState.ERROR -> "Ошибка"
    else -> "Не подключено"
}

@Suppress("unused")
private fun RouteStatus.untilText(): String = message.ifBlank { "—" }
