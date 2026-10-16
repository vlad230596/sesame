package com.vlad230596.sesame.data

import android.content.Context
import com.vlad230596.sesame.parking.ParkedCar
import com.vlad230596.sesame.parking.ParkingAnchor
import com.vlad230596.sesame.parking.ParkingSource
import com.vlad230596.sesame.parking.TrackPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Последняя парковка и метки мест (§4.1). Два маленьких JSON-файла в
 * `filesDir`, а не DataStore: трек — список из сотни точек, ему не место в
 * настройках. Координаты дома из приложения не уходят — эти файлы не
 * попадают ни в выгрузку, ни в репозиторий.
 */
@Singleton
class ParkingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val parkedFile get() = File(context.filesDir, "parked_car.json")
    private val anchorsFile get() = File(context.filesDir, "parking_anchors.json")

    private val parkedState = MutableStateFlow<ParkedCar?>(null)
    private val anchorsState = MutableStateFlow<List<ParkingAnchor>>(emptyList())
    @Volatile private var loaded = false

    /** Последняя парковка; `null` — ещё не было. */
    val parked: Flow<ParkedCar?> = parkedState.asStateFlow().onStart { load() }

    /** Метки мест; пусто — экран берёт шлагбаумы. */
    val anchors: Flow<List<ParkingAnchor>> = anchorsState.asStateFlow().onStart { load() }

    suspend fun current(): ParkedCar? {
        load()
        return parkedState.value
    }

    suspend fun currentAnchors(): List<ParkingAnchor> {
        load()
        return anchorsState.value
    }

    suspend fun saveParked(car: ParkedCar) = mutex.withLock {
        loadLocked()
        parkedState.value = car
        write(parkedFile, parkedToJson(car).toString())
    }

    suspend fun saveAnchors(anchors: List<ParkingAnchor>) = mutex.withLock {
        loadLocked()
        anchorsState.value = anchors
        write(anchorsFile, JSONArray(anchors.map(::anchorToJson)).toString())
    }

    private suspend fun load() {
        if (loaded) return
        mutex.withLock { loadLocked() }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        withContext(Dispatchers.IO) { synchronized(this@ParkingRepository) { if (!loaded) readAll() } }
    }

    private fun readAll() {
        parkedState.value = runCatching { parkedFile.takeIf { it.exists() }?.readText()?.let { parkedFromJson(JSONObject(it)) } }
            .getOrNull()
        anchorsState.value = runCatching {
            anchorsFile.takeIf { it.exists() }?.readText()?.let { text ->
                val array = JSONArray(text)
                (0 until array.length()).map { anchorFromJson(array.getJSONObject(it)) }
            }
        }.getOrNull().orEmpty()
        loaded = true
    }

    private suspend fun write(file: File, text: String) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        fun parkedToJson(c: ParkedCar) = JSONObject().apply {
            put("at", c.at)
            put("lat", c.lat)
            put("lon", c.lon)
            put("accuracyMeters", c.accuracyMeters.toDouble())
            put("source", c.source.name)
            c.bearingDeg?.let { put("bearingDeg", it.toDouble()) }
            c.rawLat?.let { put("rawLat", it) }
            c.rawLon?.let { put("rawLon", it) }
            c.rawAccuracyMeters?.let { put("rawAccuracyMeters", it.toDouble()) }
            put(
                "track",
                JSONArray(
                    c.track.map {
                        JSONArray().put(it.t).put(it.lat).put(it.lon).put(it.accuracyMeters.toDouble())
                    },
                ),
            )
        }

        fun parkedFromJson(o: JSONObject): ParkedCar {
            val track = o.optJSONArray("track")
            return ParkedCar(
                at = o.getLong("at"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                accuracyMeters = o.getDouble("accuracyMeters").toFloat(),
                source = runCatching { ParkingSource.valueOf(o.getString("source")) }.getOrDefault(ParkingSource.FIX),
                bearingDeg = o.optDoubleOrNull("bearingDeg")?.toFloat(),
                track = if (track == null) {
                    emptyList()
                } else {
                    (0 until track.length()).map {
                        val p = track.getJSONArray(it)
                        TrackPoint(p.getLong(0), p.getDouble(1), p.getDouble(2), p.getDouble(3).toFloat())
                    }
                },
                rawLat = o.optDoubleOrNull("rawLat"),
                rawLon = o.optDoubleOrNull("rawLon"),
                rawAccuracyMeters = o.optDoubleOrNull("rawAccuracyMeters")?.toFloat(),
            )
        }

        fun anchorToJson(a: ParkingAnchor) = JSONObject().apply {
            put("id", a.id)
            put("name", a.name)
            put("lat", a.lat)
            put("lon", a.lon)
        }

        fun anchorFromJson(o: JSONObject) =
            ParkingAnchor(o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))

        fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    }
}
