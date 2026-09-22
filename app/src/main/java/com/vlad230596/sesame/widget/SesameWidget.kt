package com.vlad230596.sesame.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import com.vlad230596.sesame.R
import com.vlad230596.sesame.ui.MainActivity

/**
 * Виджет на домашний экран (§4.2): три кнопки — шлагбаум A, шлагбаум B,
 * начать интенсивную запись.
 *
 * В каркасе подписи берутся из строковых ресурсов и все кнопки просто открывают
 * приложение. В v0 подписи шлагбаумов придут из настроек, нажатие запустит ту же
 * логику подтверждения, что и в приложении (прогресс отмены показывается в виджете),
 * а кнопка записи откроет экран выбора метки сессии (§4.3).
 */
class SesameWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetContent()
            }
        }
    }

    @Composable
    private fun WidgetContent() {
        val context = LocalContext.current
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                text = context.getString(R.string.widget_barrier_a),
                onClick = actionStartActivity<MainActivity>(),
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(6.dp))
            Button(
                text = context.getString(R.string.widget_barrier_b),
                onClick = actionStartActivity<MainActivity>(),
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(6.dp))
            Button(
                text = context.getString(R.string.widget_record),
                onClick = actionStartActivity<MainActivity>(),
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    }
}
