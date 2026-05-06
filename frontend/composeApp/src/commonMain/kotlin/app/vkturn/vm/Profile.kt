package app.vkturn.vm

import app.vkturn.model.AppConfig
import app.vkturn.model.Route

/** Three families of routes — UI uses this for badges and icons. */
enum class RouteKind(val badge: String) {
    VkTurnProxy("vk-turn"),
    SingBox("sing-box"),
    Direct("direct"),
    ;

    companion object {
        fun of(route: Route): RouteKind = when (route) {
            is Route.VkTurnProxy -> VkTurnProxy
            is Route.SingBox -> SingBox
            is Route.Direct -> Direct
        }
    }
}

/**
 * Flat projection of `(server, identity, route)` — the unit the user
 * actually cares about ("куда подключиться"). Generated from [AppConfig],
 * never persisted.
 */
data class Profile(
    val serverId: String,
    val identityId: String,
    val routeId: String,
    val serverName: String,
    val serverHost: String,
    val identityName: String,
    val routeLabel: String,
    val routeKind: RouteKind,
    val summary: String,
)

fun AppConfig.profileList(): List<Profile> {
    val out = mutableListOf<Profile>()
    for (server in servers) {
        for (identity in server.identities) {
            for (route in identity.routes) {
                out += Profile(
                    serverId = server.id,
                    identityId = identity.id,
                    routeId = route.id,
                    serverName = server.name.ifBlank { "(без имени)" },
                    serverHost = server.host,
                    identityName = identity.name.ifBlank { "identity" },
                    routeLabel = route.label,
                    routeKind = RouteKind.of(route),
                    summary = routeSummary(route, server.host, server.proxyPort),
                )
            }
        }
    }
    return out
}

/** Active profile = `(activeServer.id, activeIdentity.id, activeIdentity.activeRoute.id)`. */
fun AppConfig.activeProfile(): Profile? {
    val server = activeServer ?: return null
    val identity = activeIdentity ?: return null
    val route = identity.routes.firstOrNull { it.id == identity.activeRouteId }
        ?: identity.routes.firstOrNull()
        ?: return null
    return Profile(
        serverId = server.id,
        identityId = identity.id,
        routeId = route.id,
        serverName = server.name.ifBlank { "(без имени)" },
        serverHost = server.host,
        identityName = identity.name.ifBlank { "identity" },
        routeLabel = route.label,
        routeKind = RouteKind.of(route),
        summary = routeSummary(route, server.host, server.proxyPort),
    )
}

private fun routeSummary(route: Route, serverHost: String, serverPort: Int): String = when (route) {
    is Route.VkTurnProxy -> {
        val link = route.primaryLinks.firstOrNull()?.takeIf { it.isNotBlank() }
        link ?: "${route.listenHost}:${route.listenPort}"
    }
    is Route.SingBox -> {
        val host = parseVlessHost(route.vlessLink)
        host ?: "socks ${route.socksListenHost}:${route.socksListenPort}"
    }
    is Route.Direct -> route.endpoint.ifBlank { "$serverHost:$serverPort" }
}

private fun parseVlessHost(link: String): String? {
    val body = link.trim().removePrefix("vless://").substringBefore('#').substringAfter('@', "")
    if (body.isEmpty()) return null
    val hostPort = body.substringBefore('?').substringBefore('/')
    val host = hostPort.substringBeforeLast(':', hostPort)
    return host.ifBlank { null }
}
