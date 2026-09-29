package com.vlad230596.sesame.events

import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Журнал событий окружения (§4.4): Bluetooth, экран, зарядка, Wi-Fi.
 *
 * Держит динамическую регистрацию [SystemEventReceiver] (почему динамическую —
 * см. его описание) и подписку на сеть. Живёт ровно столько, сколько постоянный
 * foreground service (§9): [start] зовётся при запуске сбора, [stop] — в
 * `onDestroy` сервиса, иначе приёмник утечёт вместе с контекстом.
 *
 * **Wi-Fi слушается не броадкастом, а `ConnectivityManager`.** Старый
 * `WifiManager.NETWORK_STATE_CHANGED_ACTION` объявлен устаревшим, приходит с
 * пустым SSID без разрешения на локацию и дублируется по несколько раз на одно
 * подключение. `NetworkCallback` даёт то же самое точнее: одно событие на сеть,
 * а SSID берётся из `NetworkCapabilities.transportInfo`.
 */
@Singleton
class SystemEventWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val journal: CollectorJournal,
) {

    private val receiver = SystemEventReceiver()
    private val externalReceiver = SystemEventReceiver()

    /** Сеть → последний известный SSID; `null` — подключены, но SSID не отдали. */
    private val wifiNetworks = ConcurrentHashMap<Network, String>()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var registered = false

    @Synchronized
    fun start() {
        if (registered) return
        registered = true

        // Два приёмника, а не один: флаг экспорта задаётся на регистрацию, а
        // Bluetooth и USER_PRESENT без экспорта не приходят вовсе — почему, см.
        // SystemEventReceiver.externalSystemIntentFilter().
        register(receiver, SystemEventReceiver.systemIntentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        register(
            externalReceiver,
            SystemEventReceiver.externalSystemIntentFilter(),
            ContextCompat.RECEIVER_EXPORTED,
        )

        startWifi()
    }

    private fun register(target: SystemEventReceiver, filter: IntentFilter, flags: Int) {
        runCatching {
            ContextCompat.registerReceiver(context, target, filter, flags)
        }.onFailure {
            journal.log(
                LogEventType.COLLECTION_ERROR,
                mapOf("reason" to "system_receiver_register_failed", "message" to it.message),
            )
        }
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        registered = false
        listOf(receiver, externalReceiver).forEach { target ->
            runCatching { context.unregisterReceiver(target) }
                .onFailure { Log.w(TAG, "Приёмник уже снят", it) }
        }
        val manager = context.getSystemService<ConnectivityManager>()
        networkCallback?.let { callback ->
            runCatching { manager?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        wifiNetworks.clear()
    }

    private fun startWifi() {
        val manager = context.getSystemService<ConnectivityManager>() ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        // Без FLAG_INCLUDE_LOCATION_INFO Android 12+ вычищает SSID из
        // transportInfo даже при выданном разрешении на локацию: за неделю сбора
        // все события Wi-Fi пришли с ssid = null (разбор 29.09).
        val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val ssid = ssidOf(capabilities)
                val known = wifiNetworks.containsKey(network)
                val previous = wifiNetworks[network]
                when {
                    !known -> {
                        wifiNetworks[network] = ssid.orEmpty()
                        log(LogEventType.WIFI_CONNECTED, ssid, resolved = false)
                    }
                    // SSID часто приходит только со второй порцией capabilities:
                    // первая может быть без него. Дописываем событие один раз,
                    // а не повторяем его на каждое обновление.
                    ssid != null && previous.isNullOrEmpty() -> {
                        wifiNetworks[network] = ssid
                        log(LogEventType.WIFI_CONNECTED, ssid, resolved = true)
                    }
                }
            }

            override fun onLost(network: Network) {
                val ssid = wifiNetworks.remove(network)?.takeIf { it.isNotEmpty() }
                log(LogEventType.WIFI_DISCONNECTED, ssid, resolved = false)
            }
        }

        val ok = runCatching { manager.registerNetworkCallback(request, callback) }.isSuccess
        if (ok) {
            networkCallback = callback
        } else {
            journal.log(
                LogEventType.COLLECTION_ERROR,
                mapOf("reason" to "wifi_callback_register_failed"),
            )
        }
    }

    private fun log(type: LogEventType, ssid: String?, resolved: Boolean) {
        journal.log(
            type = type,
            payload = buildMap {
                put("ssid", ssid)
                put("eventTimeSource", "receive")
                if (resolved) put("ssidResolvedLater", true)
            },
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
        )
    }

    /**
     * SSID без разрешения на локацию система отдаёт заглушкой `<unknown ssid>` —
     * такое значение в датасете хуже отсутствующего, поэтому оно отбрасывается.
     */
    private fun ssidOf(capabilities: NetworkCapabilities): String? {
        val info = capabilities.transportInfo as? WifiInfo ?: return null
        val raw = runCatching { info.ssid }.getOrNull()?.trim('"').orEmpty()
        return raw.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
    }

    private companion object {
        const val TAG = "SystemEventWatcher"
    }
}
