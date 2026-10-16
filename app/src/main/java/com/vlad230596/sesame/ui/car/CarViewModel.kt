package com.vlad230596.sesame.ui.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vlad230596.sesame.data.BarrierRepository
import com.vlad230596.sesame.data.CollectorJournal
import com.vlad230596.sesame.data.LogEventType
import com.vlad230596.sesame.data.ParkingRepository
import com.vlad230596.sesame.data.entity.Barrier
import com.vlad230596.sesame.data.prefs.SettingsRepository
import com.vlad230596.sesame.parking.ParkedCar
import com.vlad230596.sesame.parking.ParkingAnchor
import com.vlad230596.sesame.parking.ParkingSource
import com.vlad230596.sesame.parking.SpotClassifier
import com.vlad230596.sesame.parking.SpotResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Состояние экрана «Где машина» (§4.1).
 *
 * @param userAnchors метки, которые человек поставил сам; пусто — работают
 *        [effectiveAnchors] из шлагбаумов.
 * @param home точка дома — куда смотреть карте, если парковок ещё не было.
 */
data class CarUiState(
    val car: ParkedCar? = null,
    val barriers: List<Barrier> = emptyList(),
    val userAnchors: List<ParkingAnchor> = emptyList(),
    val home: Pair<Double, Double>? = null,
    val now: Long = System.currentTimeMillis(),
    val loaded: Boolean = false,
) {
    /** Метки, от которых строится ответ: свои или, пока их нет, шлагбаумы. */
    val effectiveAnchors: List<ParkingAnchor>
        get() = userAnchors.ifEmpty { SpotClassifier.anchorsFromBarriers(barriers) }

    val usesDefaultAnchors: Boolean get() = userAnchors.isEmpty()

    val spot: SpotResult?
        get() = car?.let {
            SpotClassifier.classify(it.lat, it.lon, it.accuracyMeters.toDouble(), effectiveAnchors)
        }

    /** «у Ш2» — для короткой строки на главном экране. */
    val spotPhrase: String? get() = spot?.let(::spotPhrase)
}

/**
 * «Где машина»: последняя парковка, ответ словами, ручная правка пина и меток.
 *
 * Карта сюда не попадает — ViewModel отдаёт данные, а MapView живёт в
 * композиции: у неё свой жизненный цикл и свой контекст.
 */
@HiltViewModel
class CarViewModel @Inject constructor(
    private val parking: ParkingRepository,
    private val journal: CollectorJournal,
    barrierRepository: BarrierRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val clock = MutableStateFlow(System.currentTimeMillis())

    val state: StateFlow<CarUiState> = combine(
        parking.parked,
        parking.anchors,
        barrierRepository.observeAll(),
        settingsRepository.settings,
        clock,
    ) { parked, anchors, barriers, settings, now ->
        // Парковки, записанные до ParkingRepository, жили только в настройках:
        // одна точная фиксация без трека. Показываем её, пока не будет новой.
        val legacy = if (parked == null && settings.carParkedLat != null && settings.carParkedLon != null) {
            ParkedCar(
                at = settings.carParkedAt ?: 0L,
                lat = settings.carParkedLat,
                lon = settings.carParkedLon,
                accuracyMeters = settings.carParkedAccuracyMeters ?: 0f,
                source = ParkingSource.FIX,
            )
        } else {
            null
        }
        CarUiState(
            car = parked ?: legacy,
            barriers = barriers,
            userAnchors = anchors,
            home = if (settings.homeLat != null && settings.homeLon != null) {
                settings.homeLat to settings.homeLon
            } else {
                null
            },
            now = now,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CarUiState())

    init {
        viewModelScope.launch {
            // «N минут назад» — минутной точности хватает.
            while (true) {
                delay(30_000)
                clock.value = System.currentTimeMillis()
            }
        }
    }

    /**
     * «Машина на самом деле здесь». Это истина для настройки оценки (§4.1),
     * поэтому в журнал уходит и прежняя точка, и её источник, и возраст парковки.
     * Трек и сырая фиксация остаются — по ним потом сравнивают оценку с правкой.
     */
    fun correctPosition(lat: Double, lon: Double) {
        val car = state.value.car ?: return
        val now = System.currentTimeMillis()
        val moved = SpotClassifier.distanceMeters(car.lat, car.lon, lat, lon)
        journal.log(
            LogEventType.CAR_POSITION_CORRECTED,
            mapOf(
                "fromLat" to car.lat,
                "fromLon" to car.lon,
                "toLat" to lat,
                "toLon" to lon,
                "fromSource" to car.source.name,
                "ageMillis" to (now - car.at).coerceAtLeast(0),
                "movedMeters" to Math.round(moved * 10) / 10.0,
            ),
        )
        viewModelScope.launch {
            parking.saveParked(
                car.copy(lat = lat, lon = lon, source = ParkingSource.MANUAL, accuracyMeters = MANUAL_ACCURACY_METERS),
            )
        }
    }

    // --- Метки мест -----------------------------------------------------------------
    //
    // Пока своих меток нет, на экране работают шлагбаумы. Первая же правка
    // переносит их в список как обычные метки: иначе новая «гипермаркет» молча
    // вытеснила бы «Ш1» и «Ш2», и ответ «между Ш1 и Ш2» пропал бы.

    fun addAnchor(name: String, lat: Double, lon: Double) = editAnchors { list ->
        list + ParkingAnchor(UUID.randomUUID().toString(), name.trim(), lat, lon)
    }

    fun renameAnchor(id: String, name: String) = editAnchors { list ->
        list.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    fun deleteAnchor(id: String) = editAnchors { list -> list.filterNot { it.id == id } }

    private fun editAnchors(transform: (List<ParkingAnchor>) -> List<ParkingAnchor>) {
        val current = state.value
        // Правка идёт по тем id, что видны на карте (у шлагбаумов — «barrier-5»);
        // в сохранённый список метки уходят уже со своими UUID.
        val edited = transform(current.effectiveAnchors).map { anchor ->
            if (current.usesDefaultAnchors && anchor.id.startsWith(SpotClassifier.BARRIER_ANCHOR_PREFIX)) {
                anchor.copy(id = UUID.randomUUID().toString())
            } else {
                anchor
            }
        }
        viewModelScope.launch { parking.saveAnchors(edited) }
    }

    private companion object {
        /** Ручная точка: человек видит машину, погрешность — размер самой машины. */
        const val MANUAL_ACCURACY_METERS = 3f
    }
}

/** Ответ словами без заглавной: «у Ш2», «между Ш1 и центром». */
fun spotPhrase(spot: SpotResult): String = when (spot) {
    is SpotResult.Near -> "у ${spot.anchor.name}"
    is SpotResult.Between -> "между ${spot.a.name} и ${spot.b.name}"
    is SpotResult.Outside -> "вне отмеченных мест — ${formatMeters(spot.distanceMeters)} от «${spot.nearest.name}»"
    SpotResult.None -> "меток мест нет"
}

/** «85 м», «300 м», «1,2 км»: после сотни метров точнее десятка не нужно. */
fun formatMeters(meters: Double): String = when {
    meters < 100 -> "${Math.round(meters)} м"
    meters < 1000 -> "${Math.round(meters / 10) * 10} м"
    else -> "%.1f км".format(java.util.Locale.forLanguageTag("ru"), meters / 1000)
}

fun sourceTitle(source: ParkingSource): String = when (source) {
    ParkingSource.TAIL -> "по треку"
    ParkingSource.FIX -> "одна фиксация"
    ParkingSource.MANUAL -> "вручную"
}
