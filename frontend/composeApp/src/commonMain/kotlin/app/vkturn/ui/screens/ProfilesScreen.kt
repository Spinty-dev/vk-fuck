package app.vkturn.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.vkturn.model.AppConfig
import app.vkturn.model.Identity
import app.vkturn.model.Route
import app.vkturn.model.Server
import app.vkturn.proxy.RouteState
import app.vkturn.proxy.RouteStatus
import app.vkturn.ui.CompactBreakpoint
import app.vkturn.ui.theme.AmneziaColors
import app.vkturn.vm.AppViewModel
import app.vkturn.vm.Profile
import app.vkturn.vm.RouteKind
import app.vkturn.vm.profileList

private data class Selection(val serverId: String, val identityId: String, val routeId: String)

private enum class EditorTab { Server, Identity, RouteTab }

/**
 * The "Profiles" screen — replaces the old tree-first ServersScreen.
 *
 * On compact, we drill into a pushed editor. On expanded, master-detail:
 * a sorted list of profiles on the left, tabbed editor on the right.
 */
@Composable
fun ProfilesScreen(
    config: AppConfig,
    statuses: Map<String, RouteStatus>,
    viewModel: AppViewModel,
    onSaveConfig: (String, String) -> Unit,
    onOpenImport: () -> Unit,
) {
    var selection by remember { mutableStateOf<Selection?>(null) }

    val profiles = remember(config) { config.profileList() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < CompactBreakpoint

        if (isCompact) {
            if (selection == null) {
                ProfilesList(
                    profiles = profiles,
                    config = config,
                    statuses = statuses,
                    onOpenImport = onOpenImport,
                    onCreateServer = { viewModel.addServer() },
                    onAddIdentity = { serverId ->
                        val template = Identity(name = "identity")
                        viewModel.addIdentity(serverId, template)
                    },
                    onAddRoute = { serverId, identityId, template ->
                        viewModel.addRoute(serverId, identityId, template)
                    },
                    onRemoveServer = viewModel::removeServer,
                    onRemoveIdentity = viewModel::removeIdentity,
                    onRemoveRoute = viewModel::removeRoute,
                    onStart = viewModel::connect,
                    onStop = { _, _, routeId -> viewModel.disconnect(routeId) },
                    onActivate = { viewModel.activateProfile(it) },
                    onOpenDetail = { p ->
                        selection = Selection(p.serverId, p.identityId, p.routeId)
                    },
                )
            } else {
                val sel = selection!!
                val server = config.server(sel.serverId)
                val identity = server?.identities?.firstOrNull { it.id == sel.identityId }
                val route = identity?.routes?.firstOrNull { it.id == sel.routeId }
                if (server == null || identity == null || route == null) {
                    selection = null
                } else {
                    ProfileEditor(
                        server = server,
                        identity = identity,
                        route = route,
                        status = statuses[route.id],
                        viewModel = viewModel,
                        onSaveConfig = onSaveConfig,
                        onBack = { selection = null },
                    )
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.width(360.dp).fillMaxHeight()) {
                    ProfilesList(
                        profiles = profiles,
                        config = config,
                        statuses = statuses,
                        activeSelection = selection,
                        onOpenImport = onOpenImport,
                        onCreateServer = { viewModel.addServer() },
                        onAddIdentity = { serverId ->
                            viewModel.addIdentity(serverId, Identity(name = "identity"))
                        },
                        onAddRoute = { serverId, identityId, template ->
                            viewModel.addRoute(serverId, identityId, template)
                        },
                        onRemoveServer = viewModel::removeServer,
                        onRemoveIdentity = viewModel::removeIdentity,
                        onRemoveRoute = viewModel::removeRoute,
                        onStart = viewModel::connect,
                        onStop = { _, _, routeId -> viewModel.disconnect(routeId) },
                        onActivate = { viewModel.activateProfile(it) },
                        onOpenDetail = { p ->
                            selection = Selection(p.serverId, p.identityId, p.routeId)
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(AmneziaColors.Background),
                ) {
                    val sel = selection
                    if (sel == null) {
                        EmptyEditorHint()
                    } else {
                        val server = config.server(sel.serverId)
                        val identity = server?.identities?.firstOrNull { it.id == sel.identityId }
                        val route = identity?.routes?.firstOrNull { it.id == sel.routeId }
                        if (server == null || identity == null || route == null) {
                            EmptyEditorHint()
                        } else {
                            ProfileEditor(
                                server = server,
                                identity = identity,
                                route = route,
                                status = statuses[route.id],
                                viewModel = viewModel,
                                onSaveConfig = onSaveConfig,
                                onBack = null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilesList(
    profiles: List<Profile>,
    config: AppConfig,
    statuses: Map<String, RouteStatus>,
    activeSelection: Selection? = null,
    onOpenImport: () -> Unit,
    onCreateServer: () -> Unit,
    onAddIdentity: (serverId: String) -> Unit,
    onAddRoute: (serverId: String, identityId: String, template: Route) -> Unit,
    onRemoveServer: (String) -> Unit,
    onRemoveIdentity: (serverId: String, identityId: String) -> Unit,
    onRemoveRoute: (serverId: String, identityId: String, routeId: String) -> Unit,
    onStart: (serverId: String, identityId: String, routeId: String) -> Unit,
    onStop: (serverId: String, identityId: String, routeId: String) -> Unit,
    onActivate: (Profile) -> Unit,
    onOpenDetail: (Profile) -> Unit,
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmneziaColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Профили",
                style = MaterialTheme.typography.headlineMedium,
                color = AmneziaColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onOpenImport,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AmneziaColors.Primary),
                border = BorderStroke(1.dp, AmneziaColors.Primary),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("WingsV")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onCreateServer,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AmneziaColors.Primary),
                border = BorderStroke(1.dp, AmneziaColors.Primary),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Сервер")
            }
        }

        if (profiles.isEmpty()) {
            Text(
                "Пока пусто. Импортируй WingsV-ссылку или добавь сервер вручную.",
                style = MaterialTheme.typography.bodyMedium,
                color = AmneziaColors.TextTertiary,
            )
        }

        config.servers.forEach { server ->
            val expandedNow = expanded.getOrElse(server.id) { true }
            ServerGroupHeader(
                server = server,
                expanded = expandedNow,
                onToggle = { expanded[server.id] = !expandedNow },
                onAddIdentity = { onAddIdentity(server.id) },
                onRemove = { onRemoveServer(server.id) },
            )
            if (expandedNow) {
                server.identities.forEach { identity ->
                    IdentitySubGroup(
                        server = server,
                        identity = identity,
                        statuses = statuses,
                        activeSelection = activeSelection,
                        onAddRoute = { template -> onAddRoute(server.id, identity.id, template) },
                        onRemoveIdentity = { onRemoveIdentity(server.id, identity.id) },
                        onRemoveRoute = { routeId -> onRemoveRoute(server.id, identity.id, routeId) },
                        onStart = { routeId -> onStart(server.id, identity.id, routeId) },
                        onStop = { routeId -> onStop(server.id, identity.id, routeId) },
                        onActivate = { p -> onActivate(p) },
                        onOpenDetail = { p -> onOpenDetail(p) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerGroupHeader(
    server: Server,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAddIdentity: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "▾ ${server.name.ifBlank { "(без имени)" }}" else "▸ ${server.name.ifBlank { "(без имени)" }}",
            style = MaterialTheme.typography.titleMedium,
            color = AmneziaColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${server.host.ifBlank { "?" }}:${server.proxyPort}",
            style = MaterialTheme.typography.labelMedium,
            color = AmneziaColors.TextTertiary,
        )
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onAddIdentity) {
            Icon(Icons.Filled.Add, contentDescription = "Новый identity", tint = AmneziaColors.Primary)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Удалить сервер", tint = AmneziaColors.TextTertiary)
        }
    }
}

@Composable
private fun IdentitySubGroup(
    server: Server,
    identity: Identity,
    statuses: Map<String, RouteStatus>,
    activeSelection: Selection?,
    onAddRoute: (Route) -> Unit,
    onRemoveIdentity: () -> Unit,
    onRemoveRoute: (String) -> Unit,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
    onActivate: (Profile) -> Unit,
    onOpenDetail: (Profile) -> Unit,
) {
    var addOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "— ${identity.name}",
                style = MaterialTheme.typography.labelLarge,
                color = AmneziaColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { addOpen = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Новый маршрут", tint = AmneziaColors.Primary)
                }
                DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("vk-turn-proxy") },
                        onClick = { onAddRoute(Route.defaultVkTurn()); addOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("sing-box (VLESS)") },
                        onClick = { onAddRoute(Route.defaultSingBox()); addOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("direct") },
                        onClick = { onAddRoute(Route.defaultDirect()); addOpen = false },
                    )
                }
            }
            IconButton(onClick = onRemoveIdentity) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить identity", tint = AmneziaColors.TextTertiary)
            }
        }

        if (identity.routes.isEmpty()) {
            Text(
                "Нет маршрутов",
                style = MaterialTheme.typography.bodySmall,
                color = AmneziaColors.TextTertiary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        identity.routes.forEach { r ->
            val status = statuses[r.id]
            val profile = Profile(
                serverId = server.id,
                identityId = identity.id,
                routeId = r.id,
                serverName = server.name.ifBlank { "(без имени)" },
                serverHost = server.host,
                identityName = identity.name.ifBlank { "identity" },
                routeLabel = r.label,
                routeKind = RouteKind.of(r),
                summary = routeSummaryShort(r),
            )
            RouteRow(
                profile = profile,
                status = status,
                selected = activeSelection?.routeId == r.id,
                onOpenDetail = { onOpenDetail(profile) },
                onStart = { onStart(r.id) },
                onStop = { onStop(r.id) },
                onActivate = { onActivate(profile) },
                onRemove = { onRemoveRoute(r.id) },
            )
        }
    }
}

@Composable
private fun RouteRow(
    profile: Profile,
    status: RouteStatus?,
    selected: Boolean,
    onOpenDetail: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onActivate: () -> Unit,
    onRemove: () -> Unit,
) {
    val state = status?.state ?: RouteState.IDLE
    val dotColor = when (state) {
        RouteState.CONNECTED -> AmneziaColors.Connected
        RouteState.CONNECTING, RouteState.STARTING, RouteState.CAPTCHA, RouteState.LOCKOUT ->
            AmneziaColors.Connecting
        RouteState.ERROR -> AmneziaColors.Error
        RouteState.STOPPING, RouteState.IDLE -> AmneziaColors.TextTertiary
    }
    val bg = if (selected) AmneziaColors.SurfaceElevated else Color.Transparent
    val border = if (selected) BorderStroke(1.dp, AmneziaColors.Primary) else BorderStroke(1.dp, AmneziaColors.OutlineSoft)
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpenDetail),
        color = bg,
        shape = RoundedCornerShape(10.dp),
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.routeLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = AmneziaColors.TextPrimary,
                )
                Text(
                    "${profile.routeKind.badge} · ${profile.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AmneziaColors.TextSecondary,
                )
            }
            val isBusy = state == RouteState.CONNECTED ||
                state == RouteState.CONNECTING ||
                state == RouteState.STARTING ||
                state == RouteState.CAPTCHA ||
                state == RouteState.LOCKOUT
            IconButton(onClick = { if (isBusy) onStop() else onStart() }) {
                if (isBusy) {
                    Icon(Icons.Filled.Stop, contentDescription = "Остановить", tint = AmneziaColors.Error)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Запустить", tint = AmneziaColors.Connected)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null, tint = AmneziaColors.TextSecondary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Сделать активным") },
                        onClick = { onActivate(); menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Редактировать") },
                        onClick = { onOpenDetail(); menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        onClick = { onRemove(); menuOpen = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileEditor(
    server: Server,
    identity: Identity,
    route: Route,
    status: RouteStatus?,
    viewModel: AppViewModel,
    onSaveConfig: (String, String) -> Unit,
    onBack: (() -> Unit)?,
) {
    var tab by remember { mutableStateOf(EditorTab.RouteTab) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = AmneziaColors.TextPrimary)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${server.name.ifBlank { "(без имени)" }} · ${identity.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = AmneziaColors.TextPrimary,
                )
                Text(
                    text = route.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = AmneziaColors.TextSecondary,
                )
            }
        }

        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = AmneziaColors.Surface,
            contentColor = AmneziaColors.TextPrimary,
        ) {
            EditorTab.entries.forEach { t ->
                Tab(
                    selected = t == tab,
                    onClick = { tab = t },
                    text = {
                        Text(
                            when (t) {
                                EditorTab.Server -> "Сервер"
                                EditorTab.Identity -> "Identity"
                                EditorTab.RouteTab -> "Маршрут"
                            },
                        )
                    },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (tab) {
                EditorTab.Server -> ServerEditor(
                    server = server,
                    onChange = { updated -> viewModel.mutateServer(server.id) { updated } },
                )
                EditorTab.Identity -> IdentityEditor(
                    server = server,
                    identity = identity,
                    onChange = { updated -> viewModel.mutateIdentity(server.id, identity.id) { updated } },
                    onSaveConfig = onSaveConfig,
                )
                EditorTab.RouteTab -> RouteEditor(
                    server = server,
                    identity = identity,
                    route = route,
                    status = status,
                    onChange = { updated ->
                        viewModel.mutateRoute(server.id, identity.id, route.id) { updated }
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyEditorHint() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Выбери профиль слева",
            style = MaterialTheme.typography.headlineSmall,
            color = AmneziaColors.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Правая панель — редактор сервера, identity и маршрута в виде трёх вкладок.",
            style = MaterialTheme.typography.bodyMedium,
            color = AmneziaColors.TextSecondary,
        )
    }
}

private fun routeSummaryShort(route: Route): String = when (route) {
    is Route.VkTurnProxy -> route.primaryLinks.firstOrNull()?.take(60)
        ?: "${route.listenHost}:${route.listenPort}"
    is Route.SingBox -> "socks ${route.socksListenHost}:${route.socksListenPort}"
    is Route.Direct -> route.endpoint.ifBlank { "direct" }
}
