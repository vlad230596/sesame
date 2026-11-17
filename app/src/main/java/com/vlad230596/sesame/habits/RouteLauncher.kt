package com.vlad230596.sesame.habits

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.util.Locale

/**
 * Маршрут на машине до точки — в Яндекс Картах, только по нажатию кнопки.
 *
 * Пустая начальная точка в `rtext` — «от текущего положения» (docs/RESEARCH-habits.md,
 * по документации Яндекса; на телефоне проверить). Порядок попыток:
 * приложение Карт → веб-ссылка (откроет Карты или браузер) → `geo:` любому
 * навигатору.
 */
object RouteLauncher {
    private const val TAG = "RouteLauncher"

    fun yandexMapsUri(lat: Double, lon: Double): Uri =
        Uri.parse("yandexmaps://maps.yandex.ru/?rtext=~${coord(lat)},${coord(lon)}&rtt=auto")

    fun webUri(lat: Double, lon: Double): Uri =
        Uri.parse("https://yandex.ru/maps/?rtext=~${coord(lat)},${coord(lon)}&rtt=auto")

    fun geoUri(lat: Double, lon: Double, label: String): Uri =
        Uri.parse("geo:${coord(lat)},${coord(lon)}?q=${coord(lat)},${coord(lon)}(${Uri.encode(label)})")

    fun open(context: Context, lat: Double, lon: Double, label: String): Boolean {
        val candidates = listOf(yandexMapsUri(lat, lon), webUri(lat, lon), geoUri(lat, lon, label))
        for (uri in candidates) {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "Нет обработчика для ${uri.scheme}, пробуем следующий")
            }
        }
        return false
    }

    private fun coord(value: Double) = String.format(Locale.US, "%.6f", value)
}
