package ru.promcalc.phonegate.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

/** Вспомогательные функции для SAF-URI (выбор mp3-сообщения). */
object Files {

    private const val TAG = "PhoneGate.Files"

    /**
     * Отображаемое имя файла по URI.
     *
     * Сначала пытаемся получить реальное имя у провайдера, при неудаче берём последний
     * сегмент пути — чтобы в настройках всегда было видно, что выбрано.
     */
    fun displayNameOf(context: Context, uri: Uri): String {
        val fallback = uri.lastPathSegment.orEmpty()
        return try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index).orEmpty().ifBlank { fallback } else fallback
                    } else {
                        fallback
                    }
                } ?: fallback
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось определить имя файла для $uri", e)
            fallback
        }
    }

    /** Доступен ли URI для чтения (файл не удалён и разрешение не отозвано). */
    fun isReadable(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "URI недоступен для чтения: $uri", e)
        false
    }
}
