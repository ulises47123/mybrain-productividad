package com.mhss.app.mybrain.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.mhss.app.domain.repository.NoteRepository
import com.mhss.app.database.dao.TaskDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext

/**
 * Acceso de SOLO LECTURA a las tareas para otras apps propias (por ejemplo, Soledad).
 * Protegido por el permiso com.mhss.app.mybrain.permission.LEER_TAREAS (protectionLevel="signature"):
 * solo lo pueden usar apps firmadas con la misma clave. No permite insertar, cambiar ni borrar.
 *
 * content://<applicationId>.tareas/pendientes  → tareas sin completar
 * content://<applicationId>.tareas/todas       → todas
 * content://<applicationId>.tareas/notas       → notas (título, texto recortado, carpeta y fecha), para que Soledad
 *                                                pueda contestar «¿tenés el cuaderno de inglés?»
 */
class ProveedorTareas : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (uri.lastPathSegment == "notas") return notas()
        val soloPendientes = uri.lastPathSegment != "todas"
        val dao = GlobalContext.get().get<TaskDao>()
        // Las pendientes se filtran en SQL (índice is_completed) en vez de traer todas a memoria.
        val tareas = runBlocking { if (soloPendientes) dao.getPendingFullTasks() else dao.getAllFullTasks() }
            .sortedWith(compareBy({ it.dueDate ?: Long.MAX_VALUE }, { -it.priority }))
        val c = MatrixCursor(COLUMNAS)
        for (t in tareas) {
            c.addRow(
                arrayOf<Any?>(
                    t.id,
                    t.title,
                    t.description,
                    t.dueDate,
                    t.priority,
                    if (t.isCompleted) 1 else 0,
                    if (t.recurring) 1 else 0,
                    t.subTasks.count { !it.isCompleted },
                )
            )
        }
        return c
    }

    private fun notas(): Cursor {
        // El repositorio que usa la app (base de datos o carpeta de archivos Markdown, según Ajustes): las mismas notas que se ven en pantalla.
        val repo = GlobalContext.get().get<NoteRepository>()
        val (lista, carpetas) = runBlocking {
            // Con notas en archivos Markdown, getAllFullNotes() viene vacío y el listado trae solo los títulos: el texto se lee nota por nota.
            val notas = repo.getAllFullNotes().ifEmpty {
                repo.getAllNotes().first().sortedByDescending { it.updatedDate }.take(MAX_NOTAS).map { n ->
                    if (n.content.isNotEmpty()) n else try { repo.getNote(n.id) ?: n } catch (e: Exception) { n }
                }
            }
            notas to repo.getAllNoteFolders().first().associate { it.id to it.name }
        }
        val c = MatrixCursor(COLUMNAS_NOTAS)
        for (n in lista.sortedByDescending { it.updatedDate }.take(MAX_NOTAS)) {
            c.addRow(arrayOf<Any?>(n.title, n.content.take(MAX_TEXTO_NOTA), n.folderId?.let { carpetas[it] }, n.updatedDate))
        }
        return c
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.mybrain.tarea"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("solo lectura")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("solo lectura")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("solo lectura")

    companion object {
        val COLUMNAS_NOTAS = arrayOf("titulo", "contenido", "carpeta", "modificada")
        private const val MAX_NOTAS = 400
        private const val MAX_TEXTO_NOTA = 6_000
        val COLUMNAS = arrayOf("id", "titulo", "descripcion", "vence", "prioridad", "completada", "repetitiva", "subtareas_pendientes")
    }
}
