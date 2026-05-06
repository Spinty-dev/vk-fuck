package app.vkturn.tunnel

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.GoBackend
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture

/**
 * Mirrors WINGSV's `GoBackendVpnAccess`: wireguard-android keeps the live
 * [VpnService] in a static [CompletableFuture]. `-protect-sock` must call
 * [VpnService.protect] on **that** instance (the one that will own the TUN),
 * not on a separate app-defined [VpnService] like [VkturnVpnService].
 *
 * **Do not call [ensureServiceStarted] from the main thread** while blocking:
 * [CompletableFuture.get] would deadlock because `GoBackend.VpnService.onCreate`
 * runs on the main thread. Use a background thread (see [VkturnVpnService]),
 * or poll with short sleeps only off-main.
 */
object GoBackendVpnAccess {
    private const val TAG = "vkturn/GoBackendVpn"
    private const val SERVICE_WAIT_TIMEOUT_MS = 10_000L
    private const val SERVICE_WAIT_POLL_MS = 200L

    fun ensureServiceStarted(context: Context?): VpnService? {
        if (context == null) return null
        runCatching {
            context.startService(Intent(context, GoBackend.VpnService::class.java))
        }.onFailure { Log.w(TAG, "startService(GoBackend.VpnService) failed", it) }
        return awaitServiceAlive(SERVICE_WAIT_TIMEOUT_MS)
    }

    fun getServiceNow(): VpnService? {
        return try {
            val future = vpnServiceFuture() ?: return null
            val value = future.getNow(null)
            if (value is VpnService) value else null
        } catch (_: Throwable) {
            null
        }
    }

    fun isServiceAlive(): Boolean = getServiceNow() != null

    fun stopService(context: Context?) {
        if (context == null) return
        clearServiceOwner()
        runCatching {
            context.stopService(Intent(context, GoBackend.VpnService::class.java))
        }
    }

    fun promoteServiceForeground(
        service: VpnService?,
        notificationId: Int,
        notification: Notification?,
    ): Boolean {
        if (service == null || notification == null) return false
        return try {
            service.startForeground(notificationId, notification)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "promote GoBackend.VpnService foreground failed", t)
            false
        }
    }

    fun clearServiceOwner() {
        val service = getServiceNow() ?: return
        if (clearServiceOwnerWithSetter(service)) return
        clearServiceOwnerField(service)
    }

    private fun clearServiceOwnerWithSetter(service: VpnService): Boolean {
        return try {
            val m: Method = service.javaClass.getDeclaredMethod("setOwner", GoBackend::class.java)
            m.isAccessible = true
            m.invoke(service, null as GoBackend?)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun clearServiceOwnerField(service: VpnService) {
        try {
            val ownerField: Field = service.javaClass.getDeclaredField("owner")
            ownerField.isAccessible = true
            ownerField.set(service, null)
        } catch (_: Throwable) {
        }
    }

    /** Poll like WINGSV `AwgBackendVpnAccess.awaitService` — safe vs main-thread deadlock. */
    private fun awaitServiceAlive(timeoutMs: Long): VpnService? {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1L)
        while (System.currentTimeMillis() < deadline) {
            getServiceNow()?.let { return it }
            try {
                Thread.sleep(SERVICE_WAIT_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return getServiceNow()
            }
        }
        return getServiceNow()
    }

    private fun vpnServiceFuture(): CompletableFuture<*>? {
        return try {
            val f: Field = GoBackend::class.java.getDeclaredField("vpnService")
            f.isAccessible = true
            when (val v = f.get(null)) {
                is CompletableFuture<*> -> v
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
