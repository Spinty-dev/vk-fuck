package app.vkturn.model

import kotlinx.serialization.Serializable

@Serializable
enum class WgFlavor(val label: String) {
    WIREGUARD("WireGuard"),
    AMNEZIA("AmneziaWG"),
}
