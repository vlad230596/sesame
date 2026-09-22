package com.vlad230596.sesame.events

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Bluetooth, экран и зарядка (§4.4) — в журнал.
 *
 * **Почему приёмник регистрируется в рантайме, а не в манифесте.** С Android 8
 * статически объявленный приёмник не получает почти никаких неявных
 * броадкастов, а `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` вообще никогда не
 * доставлялись объявленным в манифесте — это документированное ограничение,
 * а не особенность версии. `ACL_CONNECTED`/`ACL_DISCONNECTED` и
 * `ACTION_USER_PRESENT` формально исключений тоже не имеют. Поэтому регистрация
 * одна на всех — динамическая, из [SystemEventWatcher], который живёт ровно
 * столько же, сколько постоянный foreground service (§9). Приложению это ничего
 * не стоит: сервис и так постоянный, а статическая регистрация всё равно не
 * работала бы.
 *
 * Инъекция — через [EntryPointAccessors] (см. `service/BootReceiver`).
 */
class SystemEventReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SystemEventEntryPoint {
        fun journal(): CollectorJournal
    }

    override fun onReceive(context: Context, intent: Intent) {
        val type = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> LogEventType.BLUETOOTH_CONNECTED
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> LogEventType.BLUETOOTH_DISCONNECTED
            Intent.ACTION_SCREEN_ON -> LogEventType.SCREEN_ON
            Intent.ACTION_SCREEN_OFF -> LogEventType.SCREEN_OFF
            Intent.ACTION_USER_PRESENT -> LogEventType.USER_PRESENT
            Intent.ACTION_POWER_CONNECTED -> LogEventType.POWER_CONNECTED
            Intent.ACTION_POWER_DISCONNECTED -> LogEventType.POWER_DISCONNECTED
            else -> return
        }
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        val journal = runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, SystemEventEntryPoint::class.java)
                .journal()
        }.getOrNull() ?: return

        val payload = buildMap<String, Any?> {
            // §6: у системного броадкаста собственной метки времени нет, поэтому
            // eventTime = receivedTime, и это указано явно. Иначе нулевая
            // задержка в датасете читалась бы как «доставка мгновенная», хотя
            // измерять здесь попросту нечего.
            put("eventTimeSource", "receive")
            if (type == LogEventType.BLUETOOTH_CONNECTED ||
                type == LogEventType.BLUETOOTH_DISCONNECTED
            ) {
                putAll(bluetoothPayload(context, intent))
            }
        }
        journal.log(type = type, payload = payload, elapsedRealtimeNanos = elapsedNanos)
    }

    /**
     * Имя и адрес устройства (§4.4): подключение к магнитоле — сильнейший
     * признак «я в машине», и без имени событие почти бесполезно.
     *
     * Имя доступно только при выданном `BLUETOOTH_CONNECT` (Android 12+).
     * Без него пишется адрес и отметка о нехватке разрешения — не падаем (§8).
     */
    private fun bluetoothPayload(context: Context, intent: Intent): Map<String, Any?> {
        val device = IntentCompat.bluetoothDevice(intent)
        val canReadName = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val name = if (canReadName) runCatching { device?.name }.getOrNull() else null
        return buildMap {
            put("address", device?.address)
            put("name", name)
            put("bondState", device?.bondState)
            if (!canReadName) put("missingPermission", Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    /** `getParcelableExtra(String, Class)` — единственный не-deprecated путь на API 33+. */
    private object IntentCompat {
        fun bluetoothDevice(intent: Intent): BluetoothDevice? = runCatching {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        }.getOrNull()
    }

    companion object {

        /** Всё, что этот приёмник умеет принимать; фильтр собирается здесь же. */
        fun intentFilter(): IntentFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
    }
}
