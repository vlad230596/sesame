package com.vlad230596.sesame.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * `AppWidgetProvider` для [SesameWidget]. Описание виджета — в
 * `res/xml/sesame_widget_info.xml`.
 */
class SesameWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = SesameWidget()
}
