package com.vlad230596.sesame.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * Иконки макета.
 *
 * Рисуются здесь, а не берутся из `material-icons`: стрелы шлагбаума в Material
 * нет вовсе, а остальные иконки макета — одного штрихового семейства (обводка
 * 2, скруглённые концы), и подмешивать к ним заливные Material-иконки значило бы
 * получить два разных набора на одном экране.
 *
 * Все контуры — обводка без заливки, поэтому иконка красится `Icon(tint = …)`
 * как любая другая.
 */
object SesameIcons {

    /**
     * Стрела шлагбаума: стойка с шарниром и полосатая штанга. Главный знак
     * приложения — им помечены обе кнопки открытия и обе кнопки виджета.
     */
    val BoomGate: ImageVector by lazy {
        stroked(size = 36f, strokeWidth = 2.2f) {
            // Стойка.
            moveTo(8f, 31f)
            verticalLineTo(13f)
            // Шарнир.
            moveTo(4.5f, 9.5f)
            arcToRelative(3.5f, 3.5f, 0f, true, true, 7f, 0f)
            arcToRelative(3.5f, 3.5f, 0f, true, true, -7f, 0f)
            // Штанга.
            moveTo(14.7f, 15.5f)
            lineTo(29.8f, 15.5f)
            arcToRelative(2.2f, 2.2f, 0f, false, true, 2.2f, 2.2f)
            lineTo(32f, 20.8f)
            arcToRelative(2.2f, 2.2f, 0f, false, true, -2.2f, 2.2f)
            lineTo(14.7f, 23f)
            arcToRelative(2.2f, 2.2f, 0f, false, true, -2.2f, -2.2f)
            lineTo(12.5f, 17.7f)
            arcToRelative(2.2f, 2.2f, 0f, false, true, 2.2f, -2.2f)
            close()
            // Косые полосы на штанге.
            moveTo(19f, 15.5f)
            lineToRelative(-4f, 7.5f)
            moveTo(25f, 15.5f)
            lineToRelative(-4f, 7.5f)
            moveTo(31f, 15.5f)
            lineToRelative(-4f, 7.5f)
        }
    }

    /** Навигация: «Главная». Домик с дверью. */
    val Home: ImageVector by lazy {
        stroked(size = 24f) {
            moveTo(4f, 11f)
            lineToRelative(8f, -6f)
            lineToRelative(8f, 6f)
            verticalLineToRelative(8f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
            horizontalLineToRelative(-4f)
            verticalLineToRelative(-6f)
            horizontalLineToRelative(-6f)
            verticalLineToRelative(6f)
            horizontalLineTo(5f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
            close()
        }
    }

    /** Навигация: «История». Часы. */
    val History: ImageVector by lazy {
        stroked(size = 24f) {
            moveTo(4f, 12f)
            arcToRelative(8f, 8f, 0f, true, true, 16f, 0f)
            arcToRelative(8f, 8f, 0f, true, true, -16f, 0f)
            moveTo(12f, 7.5f)
            verticalLineTo(12f)
            lineToRelative(3f, 2f)
        }
    }

    /** Навигация: «Настройки». Шестерёнка-«солнце». */
    val Settings: ImageVector by lazy {
        stroked(size = 24f) {
            moveTo(9f, 12f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            moveTo(12f, 3.5f)
            verticalLineToRelative(2f)
            moveTo(12f, 18.5f)
            verticalLineToRelative(2f)
            moveTo(20.5f, 12f)
            horizontalLineToRelative(-2f)
            moveTo(5.5f, 12f)
            horizontalLineToRelative(-2f)
            moveTo(17.9f, 6.1f)
            lineToRelative(-1.4f, 1.4f)
            moveTo(7.5f, 16.5f)
            lineToRelative(-1.4f, 1.4f)
            moveTo(17.9f, 17.9f)
            lineToRelative(-1.4f, -1.4f)
            moveTo(7.5f, 7.5f)
            lineTo(6.1f, 6.1f)
        }
    }

    /** Стрелка «дальше» в плашке. */
    val ChevronRight: ImageVector by lazy {
        stroked(size = 16f) {
            moveTo(6f, 3f)
            lineToRelative(5f, 5f)
            lineToRelative(-5f, 5f)
        }
    }

    /** Метка сессии «Еду домой»: домик со стрелкой внутрь. */
    val HouseIn: ImageVector by lazy {
        stroked(size = 24f) {
            moveTo(4f, 11f)
            lineToRelative(8f, -6f)
            lineToRelative(8f, 6f)
            verticalLineToRelative(8f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
            horizontalLineTo(5f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
            close()
            moveTo(12f, 19f)
            verticalLineToRelative(-5f)
        }
    }

    /** Метка сессии «Еду из дома»: домик со стрелкой наружу. */
    val HouseOut: ImageVector by lazy {
        stroked(size = 24f) {
            moveTo(4f, 11f)
            lineToRelative(8f, -6f)
            lineToRelative(8f, 6f)
            verticalLineToRelative(8f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
            horizontalLineTo(5f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
            close()
            moveTo(8.5f, 15.5f)
            horizontalLineToRelative(7f)
            moveTo(13f, 13f)
            lineToRelative(2.5f, 2.5f)
            lineTo(13f, 18f)
        }
    }

    /** Метка сессии «Пешком». */
    val Walk: ImageVector by lazy {
        stroked(size = 24f) {
            moveTo(11f, 4.5f)
            arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
            arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
            moveTo(13f, 21f)
            lineToRelative(-1.5f, -5.5f)
            lineTo(9f, 13f)
            lineToRelative(1f, -4.5f)
            lineToRelative(3f, -1f)
            lineToRelative(3f, 3f)
            lineToRelative(2.5f, 1f)
            moveTo(10f, 10f)
            lineToRelative(-3f, 3f)
            lineToRelative(-1f, 4f)
        }
    }

    /** Метка сессии «Другое». */
    val Question: ImageVector by lazy {
        stroked(size = 24f) {
            moveTo(3.5f, 12f)
            arcToRelative(8.5f, 8.5f, 0f, true, true, 17f, 0f)
            arcToRelative(8.5f, 8.5f, 0f, true, true, -17f, 0f)
            moveTo(9.8f, 9.6f)
            arcToRelative(2.3f, 2.3f, 0f, true, true, 3.1f, 2.2f)
            curveToRelative(-0.6f, 0.2f, -0.9f, 0.8f, -0.9f, 1.4f)
            verticalLineToRelative(0.5f)
            moveTo(12f, 16.6f)
            verticalLineToRelative(0.1f)
        }
    }

    /** Стрелка «назад» в шапке вложенного экрана. */
    val ChevronLeft: ImageVector by lazy {
        stroked(size = 16f) {
            moveTo(10f, 3f)
            lineTo(5f, 8f)
            lineToRelative(5f, 5f)
        }
    }

    /** «Добавить метку задним числом». */
    val Plus: ImageVector by lazy {
        stroked(size = 24f) {
            moveTo(12f, 5f)
            verticalLineToRelative(14f)
            moveTo(5f, 12f)
            horizontalLineToRelative(14f)
        }
    }

    /** «Экспорт в архив»: стрелка вниз на подставку. */
    val Export: ImageVector by lazy {
        stroked(size = 24f) {
            moveTo(12f, 4f)
            verticalLineToRelative(11f)
            moveTo(8f, 11f)
            lineToRelative(4f, 4f)
            lineToRelative(4f, -4f)
            moveTo(5f, 19f)
            horizontalLineToRelative(14f)
        }
    }

    /** Галочка «выдано». */
    val Check: ImageVector by lazy {
        stroked(size = 24f, strokeWidth = 2.4f) {
            moveTo(5f, 12.5f)
            lineToRelative(4.5f, 4.5f)
            lineTo(19f, 7.5f)
        }
    }

    /** «Показать номер». */
    val Eye: ImageVector by lazy {
        stroked(size = 24f) {
            moveTo(2.5f, 12f)
            curveToRelative(0f, 0f, 3.5f, -6.5f, 9.5f, -6.5f)
            reflectiveCurveToRelative(9.5f, 6.5f, 9.5f, 6.5f)
            reflectiveCurveToRelative(-3.5f, 6.5f, -9.5f, 6.5f)
            reflectiveCurveTo(2.5f, 12f, 2.5f, 12f)
            close()
            moveTo(9.2f, 12f)
            arcToRelative(2.8f, 2.8f, 0f, true, true, 5.6f, 0f)
            arcToRelative(2.8f, 2.8f, 0f, true, true, -5.6f, 0f)
        }
    }

    /** Предупреждение: не хватает разрешений. */
    val Alert: ImageVector by lazy {
        stroked(size = 24f) {
            moveTo(12f, 3.8f)
            lineTo(21.2f, 19.5f)
            horizontalLineTo(2.8f)
            close()
            moveTo(12f, 10f)
            verticalLineToRelative(4.2f)
            moveTo(12f, 16.8f)
            verticalLineToRelative(0.1f)
        }
    }

    private fun stroked(
        size: Float,
        strokeWidth: Float = 2f,
        path: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        defaultWidth = size.dp,
        defaultHeight = size.dp,
        viewportWidth = size,
        viewportHeight = size,
    ).apply {
        addPath(
            pathData = androidx.compose.ui.graphics.vector.PathData(path),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}
