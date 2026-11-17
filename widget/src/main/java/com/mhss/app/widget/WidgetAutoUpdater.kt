package com.mhss.app.widget

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.mhss.app.domain.repository.NoteRepository
import com.mhss.app.domain.repository.TaskRepository
import com.mhss.app.widget.calendar.CalendarWidget
import com.mhss.app.widget.notes.NotesWidget
import com.mhss.app.widget.tasks.TasksWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/**
 * Mantiene los widgets al día. Antes solo se actualizaban al crear o editar una tarea o un evento:
 * borrar o completar tareas, cualquier cambio en notas, importar, sincronizar o los cambios hechos
 * por el asistente dejaban el widget mostrando datos viejos.
 *
 * Mientras la app está viva observa las tablas de tareas y notas (y el calendario del teléfono)
 * y, ante cualquier cambio, vuelve a dibujar el widget correspondiente.
 */
@Single
class WidgetAutoUpdater(
    private val context: Context,
    private val taskRepository: TaskRepository,
    private val noteRepository: NoteRepository,
) {
    @Volatile
    private var iniciado = false

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (iniciado) return
        iniciado = true

        // Huella liviana: id + fecha de modificación (+ completada). Cambia con altas, bajas y ediciones.
        val tareas = taskRepository.getAllTasks().map { lista ->
            lista.map { Triple(it.id, it.updatedDate, it.isCompleted) }
        }
        val notas = noteRepository.getAllNotes().map { lista ->
            lista.map { "${it.id}|${it.updatedDate}|${it.folderId}|${it.pinned}" }
        }

        observar(scope, "tareas", tareas) { TasksWidget().updateAll(context) }
        observar(scope, "notas", notas) { NotesWidget().updateAll(context) }
        observar(scope, "calendario", cambiosDelCalendario()) { CalendarWidget().updateAll(context) }
    }

    @OptIn(FlowPreview::class)
    private fun <T> observar(scope: CoroutineScope, nombre: String, cambios: Flow<T>, actualizar: suspend () -> Unit) {
        scope.launch {
            try {
                cambios
                    .distinctUntilChanged()
                    .drop(1) // el primer valor es el estado actual: el widget ya lo muestra
                    .debounce(DEMORA_MS)
                    .conflate()
                    .collect {
                        try {
                            actualizar()
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

    /** Emite un contador cada vez que cambia algo en el calendario del teléfono (de cualquier app). */
    private fun cambiosDelCalendario(): Flow<Long> = callbackFlow {
        var n = 0L
        trySend(n)
        val observador = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(++n)
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

    private companion object {
        const val TAG = "WidgetAutoUpdater"
        const val DEMORA_MS = 500L
    }
}
