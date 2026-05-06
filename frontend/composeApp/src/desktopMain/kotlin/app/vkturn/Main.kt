package app.vkturn

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.vkturn.daemon.VkturndElevator
import app.vkturn.daemon.createDaemonClient
import app.vkturn.daemon.defaultDaemonAddress
import app.vkturn.daemon.tryLaunchPrivilegedVkturnd
import app.vkturn.persistence.createSettingsStore
import app.vkturn.proxy.createBinaryResolver
import app.vkturn.proxy.createProcessHost
import app.vkturn.ui.App
import app.vkturn.vm.AppViewModel
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

private val daemonAddress by lazy { defaultDaemonAddress() }
private val daemonClient by lazy { createDaemonClient(daemonAddress) }

private val viewModel: AppViewModel by lazy {
    val resolver = createBinaryResolver()
    AppViewModel(
        store = createSettingsStore(),
        resolver = resolver,
        hostFactory = ::createProcessHost,
        daemonClient = daemonClient,
        vkturndElevator = { logs ->
            tryLaunchPrivilegedVkturnd(daemonClient, daemonAddress, resolver, logs)
        },
    )
}

fun main() = application {
    DisposableEffect(Unit) {
        onDispose { viewModel.dispose() }
    }

    val windowState = rememberWindowState(
        position = WindowPosition.Aligned(Alignment.Center),
        size = DpSize(1200.dp, 800.dp),
    )

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "vkturn",
        resizable = true,
    ) {
        App(
            viewModel = viewModel,
            onSaveConfig = ::saveConfigToDisk,
        )
    }
}

private fun saveConfigToDisk(suggestedName: String, content: String) {
    SwingUtilities.invokeLater {
        val chooser = JFileChooser().apply {
            dialogTitle = "Сохранить конфигурацию"
            selectedFile = File(suggestedName)
            fileFilter = FileNameExtensionFilter("WireGuard config (*.conf)", "conf")
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val target = chooser.selectedFile.let {
                if (it.name.endsWith(".conf")) it else File(it.absolutePath + ".conf")
            }
            target.writeText(content)
            runCatching {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(target.parentFile)
            }
        }
    }
}
