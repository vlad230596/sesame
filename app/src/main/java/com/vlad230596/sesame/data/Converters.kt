package com.vlad230596.sesame.data

import androidx.room.TypeConverter

/**
 * Перечисления хранятся строками: датасет читается с компьютера,
 * и порядковый номер enum'а там бесполезен.
 */
class Converters {

    @TypeConverter
    fun directionToString(value: Direction?): String? = value?.name

    @TypeConverter
    fun stringToDirection(value: String?): Direction? =
        value?.let { runCatching { Direction.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun travelModeToString(value: TravelMode?): String? = value?.name

    @TypeConverter
    fun stringToTravelMode(value: String?): TravelMode? =
        value?.let { runCatching { TravelMode.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun labelSourceToString(value: LabelSource?): String? = value?.name

    @TypeConverter
    fun stringToLabelSource(value: String?): LabelSource? =
        value?.let { runCatching { LabelSource.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun callOutcomeToString(value: CallOutcome?): String? = value?.name

    @TypeConverter
    fun stringToCallOutcome(value: String?): CallOutcome? =
        value?.let { runCatching { CallOutcome.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun sessionLabelToString(value: SessionLabel?): String? = value?.name

    @TypeConverter
    fun stringToSessionLabel(value: String?): SessionLabel? =
        value?.let { runCatching { SessionLabel.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun logEventTypeToString(value: LogEventType?): String? = value?.name

    @TypeConverter
    fun stringToLogEventType(value: String?): LogEventType? =
        value?.let { runCatching { LogEventType.valueOf(it) }.getOrNull() }
}
