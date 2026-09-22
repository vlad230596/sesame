package com.vlad230596.sesame.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Общие размеры. Держатся в одном месте, чтобы экраны, написанные по отдельности,
 * не разъезжались по ритму.
 *
 * Кнопки открытия намеренно очень крупные: ТЗ §4.1 требует попадания одним тапом
 * за рулём, и это важнее плотности информации на экране.
 */
object Dimens {
    val SpaceXs = 4.dp
    val SpaceS = 8.dp
    val SpaceM = 16.dp
    val SpaceL = 24.dp
    val SpaceXl = 32.dp

    /** Высота кнопки открытия шлагбаума. */
    val BarrierButtonHeight = 120.dp

    /** Высота кнопки «Интенсивная запись» — заметно меньше, чтобы не путать с открытием. */
    val RecordButtonHeight = 72.dp

    /** Минимальная цель для любого второстепенного тапа. */
    val MinTouchTarget = 48.dp

    val CardCorner = 20.dp
    val ButtonCorner = 24.dp

    val ScreenPadding = 16.dp
}
