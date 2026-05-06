package app.vkturn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.vkturn.proxy.LogBus
import app.vkturn.proxy.RouteState
import app.vkturn.proxy.RouteStatus
import app.vkturn.ui.components.ActiveProfileCard
import app.vkturn.ui.components.ConnectionCard
import app.vkturn.ui.components.ConnectionCardDensity
import app.vkturn.ui.components.LogFeedDensity
import app.vkturn.ui.components.LogFeedPanel
import app.vkturn.ui.components.MetricsCard
import app.vkturn.ui.components.StatusBanner
import app.vkturn.ui.components.StatusBannerKind
import app.vkturn.ui.components.filterLogLines
import app.vkturn.ui.components.formatForCopy
import app.vkturn.ui.theme.AmneziaColors
import app.vkturn.vm.Profile
import app.vkturn.model.Route as ModelRoute

data class DashboardState(
    val activeProfile: Profile?,
    val routeState: RouteState,
    val status: RouteStatus?,
    val route: ModelRoute?,
)

@Composable
fun DashboardCompact(
    state: DashboardState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSwitchProfile: () -> Unit,
    onEditProfile: () -> Unit,
    onImportWingsV: () -> Unit,
    onOpenLogs: () -> Unit,
    onRetry: () -> Unit,
) {
    val uri = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        banner(state, onImportWingsV, onRetry, onOpenLogs, uri)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ConnectionCard(
                state = state.routeState,
                enabled = state.activeProfile != null,
                statusTitle = headerTitle(state),
                statusSubtitle = headerSubtitle(state),
                onClick = {
                    if (state.routeState == RouteState.IDLE || state.routeState == RouteState.ERROR) {
                        onConnect()
                    } else {
                        onDisconnect()
                    }
                },
                density = ConnectionCardDensity.Compact,
            )
        }
        ActiveProfileCard(
            profile = state.activeProfile,
            state = state.routeState,
            onSwitch = onSwitchProfile,
            onEdit = onEditProfile,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Что-то пошло не так? Открой журнал в правом верхнем углу.",
            style = MaterialTheme.typography.bodySmall,
            color = AmneziaColors.TextTertiary,
        )
    }
}

@Composable
fun DashboardExpanded(
    state: DashboardState,
    logs: LogBus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSwitchProfile: () -> Unit,
    onEditProfile: () -> Unit,
    onImportWingsV: () -> Unit,
    onOpenLogs: () -> Unit,
    onRetry: () -> Unit,
) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val lines by logs.lines.collectAsState()
    val filtered = filterLogLines(
        lines = lines,
        routeFilter = state.activeProfile?.routeId,
        showDebug = false,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Панель", style = MaterialTheme.typography.displaySmall, color = AmneziaColors.TextPrimary)
        banner(state, onImportWingsV, onRetry, onOpenLogs, uri)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.width(360.dp)) {
                ConnectionCard(
                    state = state.routeState,
                    enabled = state.activeProfile != null,
                    statusTitle = headerTitle(state),
                    statusSubtitle = headerSubtitle(state),
                    onClick = {
                        if (state.routeState == RouteState.IDLE || state.routeState == RouteState.ERROR) {
                            onConnect()
                        } else {
                            onDisconnect()
                        }
                    },
                    density = ConnectionCardDensity.Expanded,
                )
            }
            MetricsCard(
                status = state.status,
                route = state.route,
                modifier = Modifier.weight(1f),
            )
        }

        ActiveProfileCard(
            profile = state.activeProfile,
            state = state.routeState,
            onSwitch = onSwitchProfile,
            onEdit = onEditProfile,
        )

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Text(
                "Журнал активного маршрута · откройте «Журнал» в сайдбаре для полного обзора",
                style = MaterialTheme.typography.labelMedium,
                color = AmneziaColors.TextTertiary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            LogFeedPanel(
                filteredLines = filtered,
                modifier = Modifier.fillMaxSize(),
                density = LogFeedDensity.Comfortable,
                title = state.activeProfile?.routeLabel?.let { "События · $it" } ?: "События",
                showDebug = false,
                onShowDebugToggle = { /* full toggle is on Logs screen */ },
                autoscroll = true,
                onAutoscrollToggle = {},
                onCopyAll = {
                    clipboard.setText(AnnotatedString(filtered.joinToString("\n") { it.formatForCopy() }))
                },
                onClear = {},
                emptyHint = "Подключись к профилю, чтобы увидеть события.",
            )
        }
    }
}

@Composable
private fun banner(
    state: DashboardState,
    onImportWingsV: () -> Unit,
    onRetry: () -> Unit,
    onOpenLogs: () -> Unit,
    uri: androidx.compose.ui.platform.UriHandler,
) {
    when {
        state.activeProfile == null -> StatusBanner(
            kind = StatusBannerKind.Info,
            title = "Нет активного профиля",
            subtitle = "Вставь WingsV-ссылку или добавь сервер вручную на вкладке «Профили».",
            actionLabel = "Импорт",
            onAction = onImportWingsV,
        )
        state.routeState == RouteState.ERROR -> StatusBanner(
            kind = StatusBannerKind.Error,
            title = "Ошибка подключения",
            subtitle = state.status?.message?.takeIf { it.isNotBlank() }
                ?: "Смотри журнал — там точная причина.",
            actionLabel = "Повторить",
            onAction = onRetry,
        )
        state.routeState == RouteState.CAPTCHA -> StatusBanner(
            kind = StatusBannerKind.Warn,
            title = "Нужна капча",
            subtitle = state.status?.captchaUrl?.takeIf { it.isNotBlank() }
                ?: "Клиент ждёт прохождения капчи.",
            actionLabel = if (!state.status?.captchaUrl.isNullOrBlank()) "Открыть" else null,
            onAction = state.status?.captchaUrl?.takeIf { it.isNotBlank() }?.let { url -> { uri.openUri(url) } },
        )
        state.routeState == RouteState.LOCKOUT -> StatusBanner(
            kind = StatusBannerKind.Warn,
            title = "Пауза на стороне сервера",
            subtitle = "Сервер попросил подождать. Фоновый супервизор повторит автоматически.",
            actionLabel = "В журнал",
            onAction = onOpenLogs,
        )
        else -> Unit
    }
}

private fun headerTitle(state: DashboardState): String {
    val profile = state.activeProfile ?: return "vkturn"
    return "${profile.serverName} · ${profile.identityName}"
}

private fun headerSubtitle(state: DashboardState): String? {
    val profile = state.activeProfile ?: return "Добавь сервер, чтобы начать"
    return "${profile.routeKind.badge} · ${profile.summary}"
}
