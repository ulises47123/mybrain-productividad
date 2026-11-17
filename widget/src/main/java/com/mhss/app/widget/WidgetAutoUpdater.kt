package com.mhss.app.widget

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.mhss.app.domain.use_case.GetWidgetNotesUseCase
import com.mhss.app.domain.use_case.GetWidgetTasksUseCase
import com.mhss.app.preferences.PrefsConstants
import com.mhss.app.preferences.domain.model.SortOrder
import com.mhss.app.preferences.domain.model.SortType
import com.mhss.app.preferences.domain.model.booleanPreferencesKey
import com.mhss.app.preferences.domain.model.intPreferencesKey
import com.mhss.app.preferences.domain.model.toInt
import com.mhss.app.preferences.domain.model.toSortOrder
import com.mhss.app.preferences.domain.use_case.GetPreferenceUseCase
import com.mhss.app.widget.calendar.CalendarWidget
import com.mhss.app.widget.notes.NotesWidget
import com.mhss.app.widget.tasks.TasksWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/**
 * Única vía de actualización de los widgets de Tareas y Notas (y la de cambios externos del Calendario).
 *
 * - Solo trabaja si hay widgets de ese tipo puestos en el inicio (los receivers avisan con
 *   [avisarCambioDeWidgets] cuando se agrega o quita uno).
 * - Observa exactamente lo que muestra cada widget: la misma consulta limitada
 *   (WIDGET_ITEM_LIMIT) con el mismo orden y filtros que leen TasksWidget y NotesWidget. Así
 *   crear, editar, completar, borrar, importar o sincronizar redibuja el widget una sola vez,
 *   y los cambios que no se ven en el widget no lo redibujan.
 * - Calendario: observa el proveedor del teléfono con una demora larga y no repite el
 *   redibujo que ya hicieron los casos de uso de la app (ver [registrarActualizacionCalendario]).
 */
@Single
class WidgetAutoUpdater(
    private val context: Context,
    private val getPreference: GetPreferenceUseCase,
    private val getWidgetTasks: GetWidgetTasksUseCase,
    private val getWidgetNotes: GetWidgetNotesUseCase,
) {
    @Volatile
    private var iniciado = false

    fun start(scope: CoroutineScope) {
        if (iniciado) return
        iniciado = true

        observarSiHayWidgets(scope, "tareas", TasksWidget::class.java, DEMORA_MS, ::tareasDelWidget) {
            TasksWidget().updateAll(context)
        }
        observarSiHayWidgets(scope, "notas", NotesWidget::class.java, DEMORA_MS, ::notasDelWidget) {
            NotesWidget().updateAll(context)
        }
        observarSiHayWidgets(scope, "calendario", CalendarWidget::class.java, DEMORA_CALENDARIO_MS, ::cambiosDelCalendario) { ultimoCambio ->
            // Si el último aviso llegó pegado a una actualización hecha por la app, ya está dibujado.
            if (ultimoCambio > ultimaActualizacionCalendarioMs + MARGEN_CALENDARIO_MS) {
                CalendarWidget().updateAll(context)
            }
        }
    }

    /** Lo mismo que muestra TasksWidget: mismas preferencias, misma consulta limitada. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun tareasDelWidget(): Flow<Any> = combine(
        getPreference(intPreferencesKey(PrefsConstants.TASKS_ORDER_KEY), SortOrder.DueDate(SortType.ASC).toInt()),
        getPreference(booleanPreferencesKey(PrefsConstants.SHOW_COMPLETED_TASKS_KEY), false),
    ) { orden, mostrarCompletadas -> orden to mostrarCompletadas }
        .distinctUntilChanged()
        .flatMapLatest { (orden, mostrarCompletadas) -> getWidgetTasks(orden.toSortOrder(), mostrarCompletadas) }

    /** Lo mismo que muestra NotesWidget: mismas preferencias, misma consulta limitada. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun notasDelWidget(): Flow<Any> = combine(
        getPreference(intPreferencesKey(PrefsConstants.NOTES_ORDER_KEY), SortOrder.DateModified(SortType.DESC).toInt()),
        getPreference(booleanPreferencesKey(PrefsConstants.SHOW_ALL_NOTES_KEY), false),
    ) { orden, mostrarTodas -> orden to mostrarTodas }
        .distinctUntilChanged()
        .flatMapLatest { (orden, mostrarTodas) -> getWidgetNotes(orden.toSortOrder(), mostrarTodas) }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun <T : Any> observarSiHayWidgets(
        scope: CoroutineScope,
        nombre: String,
        widget: Class<out GlanceAppWidget>,
        demoraMs: Long,
        cambios: () -> Flow<T>,
        actualizar: suspend (T) -> Unit,
    ) {
        scope.launch {
            try {
                cambiosDeWidgets
                    .map { hayWidgetsPuestos(widget) }
                    .distinctUntilChanged()
                    .flatMapLatest { hay ->
                        if (!hay) emptyFlow()
                        // el primer valor es el estado actual: el widget ya lo muestra
                        else cambios().distinctUntilChanged().drop(1)
                    }
                    .debounce(demoraMs)
                    .conflate()
                    .collect { valor ->
                        try {
                            actualizar(valor)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo actualizar el widget de $nombre", e)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Dejó de observar $nombre", e)
            }
        }
    }

    private suspend fun hayWidgetsPuestos(widget: Class<out GlanceAppWidget>): Boolean = try {
        GlanceAppWidgetManager(context).getGlanceIds(widget).isNotEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo consultar los widgets puestos", e)
        true // ante la duda, observar
    }

    /** Emite el momento (elapsedRealtime) de cada cambio en el calendario del teléfono. */
    private fun cambiosDelCalendario(): Flow<Long> = callbackFlow {
        trySend(SystemClock.elapsedRealtime())
        val observador = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(SystemClock.elapsedRealtime())
            }
        }
        val registrado = try {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observador)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso para observar el calendario", e)
            false
        }
        awaitClose { if (registrado) context.contentResolver.unregisterContentObserver(observador) }
    }

    companion object {
        private const val TAG = "WidgetAutoUpdater"
        private const val DEMORA_MS = 500L
        private const val DEMORA_CALENDARIO_MS = 3_000L
        private const val MARGEN_CALENDARIO_MS = 1_500L

        private val cambiosDeWidgets = MutableStateFlow(0L)

        @Volatile
        private var ultimaActualizacionCalendarioMs = Long.MIN_VALUE / 2

        /** Los receivers lo llaman cuando se pone o se quita un widget, para volver a revisar. */
        fun avisarCambioDeWidgets() = cambiosDeWidgets.update { it + 1 }

        /** La app ya redibujó el widget del calendario (agregar/editar/borrar evento). */
        internal fun registrarActualizacionCalendario() {
            ultimaActualizacionCalendarioMs = SystemClock.elapsedRealtime()
        }
    }
}
