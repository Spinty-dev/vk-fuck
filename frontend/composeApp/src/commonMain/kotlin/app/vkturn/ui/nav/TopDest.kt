package app.vkturn.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Single top-level destination enum used by both the desktop sidebar and
 * the mobile bottom bar. The app-wide state keeps exactly one of these
 * active — no parallel mobile/desktop navigation trees.
 */
enum class TopDest(
    val labelMobile: String,
    val labelDesktop: String,
    val iconMobile: ImageVector,
    val iconDesktop: ImageVector,
) {
    Dashboard(
        labelMobile = "Подключение",
        labelDesktop = "Панель",
        iconMobile = Icons.Filled.Power,
        iconDesktop = Icons.Filled.Power,
    ),
    Profiles(
        labelMobile = "Профили",
        labelDesktop = "Профили",
        iconMobile = Icons.Filled.Dns,
        iconDesktop = Icons.Filled.Dns,
    ),
    Logs(
        labelMobile = "Журнал",
        labelDesktop = "Журнал",
        iconMobile = Icons.AutoMirrored.Filled.Article,
        iconDesktop = Icons.AutoMirrored.Filled.Article,
    ),
    Settings(
        labelMobile = "Ещё",
        labelDesktop = "Настройки",
        iconMobile = Icons.Filled.MoreHoriz,
        iconDesktop = Icons.Filled.Settings,
    ),
    ;

    companion object {
        /** Mobile bottom bar shows only 3 items — Logs is reachable via the sheet. */
        val mobileTabs: List<TopDest> = listOf(Dashboard, Profiles, Settings)

        /** Desktop sidebar lists every destination. */
        val desktopItems: List<TopDest> = listOf(Dashboard, Profiles, Logs, Settings)
    }
}
