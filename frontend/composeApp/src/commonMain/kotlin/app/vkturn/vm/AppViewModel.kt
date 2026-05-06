package app.vkturn.vm

import app.vkturn.daemon.DaemonClient
import app.vkturn.daemon.VkturndElevator
import app.vkturn.model.AppConfig
import app.vkturn.model.Identity
import app.vkturn.model.Route
import app.vkturn.model.Server
import app.vkturn.persistence.SettingsStore
import app.vkturn.proxy.BinaryResolver
import app.vkturn.proxy.LogBus
import app.vkturn.proxy.ProcessHost
import app.vkturn.proxy.RouteStatus
import app.vkturn.proxy.RouteSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single plain-Kotlin viewmodel for the whole app. Holds the active
 * [AppConfig] tree, a route supervisor, and the shared log bus.
 */
class AppViewModel(
    private val store: SettingsStore,
    resolver: BinaryResolver,
    hostFactory: () -> ProcessHost,
    daemonClient: DaemonClient,
    vkturndElevator: VkturndElevator? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    val logs: LogBus = LogBus()
    private val supervisor = RouteSupervisor(logs, resolver, hostFactory, daemonClient, vkturndElevator)
    val statuses: StateFlow<Map<String, RouteStatus>> = supervisor.statuses

    /** Flat list of every `(server, identity, route)` combination — the core UI unit. */
    val profiles: StateFlow<List<Profile>> = _config
        .map { it.profileList() }
        .stateIn(scope, SharingStarted.Eagerly, _config.value.profileList())

    /** Derived from [config] — the profile currently targeted by the Connect button. */
    val activeProfile: StateFlow<Profile?> = _config
        .map { it.activeProfile() }
        .stateIn(scope, SharingStarted.Eagerly, _config.value.activeProfile())

    // ---------- tree mutations ----------------------------------------

    fun updateConfig(block: (AppConfig) -> AppConfig) {
        val next = block(_config.value)
        _config.value = next
        scope.launch { store.save(next) }
    }

    fun addServer(template: Server = Server(name = "new server")) = updateConfig {
        val next = it.copy(servers = it.servers + template)
        next.copy(activeServerId = next.activeServerId ?: template.id)
    }

    fun removeServer(serverId: String) = updateConfig {
        val filtered = it.servers.filterNot { s -> s.id == serverId }
        it.copy(
            servers = filtered,
            activeServerId = if (it.activeServerId == serverId) filtered.firstOrNull()?.id else it.activeServerId,
        )
    }

    fun mutateServer(serverId: String, block: (Server) -> Server) = updateConfig { cfg ->
        cfg.copy(servers = cfg.servers.map { if (it.id == serverId) block(it) else it })
    }

    fun addIdentity(serverId: String, template: Identity = Identity()) {
        mutateServer(serverId) { s -> s.copy(identities = s.identities + template) }
    }

    fun removeIdentity(serverId: String, identityId: String) {
        mutateServer(serverId) { s ->
            s.copy(identities = s.identities.filterNot { it.id == identityId })
        }
    }

    fun mutateIdentity(serverId: String, identityId: String, block: (Identity) -> Identity) {
        mutateServer(serverId) { s ->
            s.copy(identities = s.identities.map { if (it.id == identityId) block(it) else it })
        }
    }

    fun addRoute(serverId: String, identityId: String, template: Route) {
        mutateIdentity(serverId, identityId) { it.copy(routes = it.routes + template) }
    }

    fun removeRoute(serverId: String, identityId: String, routeId: String) {
        mutateIdentity(serverId, identityId) { id ->
            val next = id.routes.filterNot { it.id == routeId }
            id.copy(
                routes = next,
                activeRouteId = if (id.activeRouteId == routeId) next.firstOrNull()?.id else id.activeRouteId,
            )
        }
    }

    fun mutateRoute(serverId: String, identityId: String, routeId: String, block: (Route) -> Route) {
        mutateIdentity(serverId, identityId) { id ->
            id.copy(routes = id.routes.map { if (it.id == routeId) block(it) else it })
        }
    }

    fun selectServer(serverId: String) = updateConfig { it.copy(activeServerId = serverId) }
    fun selectIdentity(identityId: String) = updateConfig { it.copy(activeIdentityId = identityId) }
    fun selectRoute(serverId: String, identityId: String, routeId: String) {
        mutateIdentity(serverId, identityId) { it.copy(activeRouteId = routeId) }
    }

    /**
     * One-shot activation: picks the server/identity/route in a single
     * config mutation so the UI only sees one recomposition instead of three.
     */
    fun activateProfile(profile: Profile) = updateConfig { cfg ->
        val updatedServers = cfg.servers.map { server ->
            if (server.id != profile.serverId) server
            else server.copy(
                identities = server.identities.map { id ->
                    if (id.id != profile.identityId) id
                    else id.copy(activeRouteId = profile.routeId)
                },
            )
        }
        cfg.copy(
            servers = updatedServers,
            activeServerId = profile.serverId,
            activeIdentityId = profile.identityId,
        )
    }

    // ---------- lifecycle ---------------------------------------------

    fun connect(serverId: String, identityId: String, routeId: String) {
        val cfg = _config.value
        val server = cfg.server(serverId) ?: return
        val identity = server.identities.firstOrNull { it.id == identityId } ?: return
        val route = identity.routes.firstOrNull { it.id == routeId } ?: return
        supervisor.start(cfg, server, identity, route)
    }

    /** Connect the currently-active route of the currently-active identity. */
    fun connectActive() {
        val cfg = _config.value
        val server = cfg.activeServer ?: return
        val identity = cfg.activeIdentity ?: return
        val route = identity.activeRoute
        supervisor.start(cfg, server, identity, route)
    }

    fun disconnect(routeId: String) { supervisor.stop(routeId) }
    fun disconnectAll() { supervisor.stopAll() }
    fun clearLogs() { logs.clear() }

    fun dispose() {
        supervisor.dispose()
        scope.cancel()
    }
}
