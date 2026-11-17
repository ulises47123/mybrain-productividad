package com.mhss.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.mhss.app.widget.calendar.CalendarWidget
import com.mhss.app.widget.tasks.TasksWidget
import org.koin.core.annotation.Single

@Single
class WidgetUpdaterImpl(
    private val context: Context
): WidgetUpdater {
    override suspend fun updateAll(type: WidgetUpdater.WidgetType) {
        when (type) {
            WidgetUpdater.WidgetType.Calendar -> {
                // Marca la hora para que WidgetAutoUpdater no repita este redibujo cuando le llegue
                // el aviso del proveedor de calendario por este mismo cambio.
                WidgetAutoUpdater.registrarActualizacionCalendario()
                CalendarWidget().updateAll(context)
            }
            WidgetUpdater.WidgetType.Tasks -> {
                TasksWidget().updateAll(context)
            }
        }
    }

}