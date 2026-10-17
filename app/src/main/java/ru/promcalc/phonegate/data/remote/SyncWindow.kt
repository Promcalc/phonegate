package ru.promcalc.phonegate.data.remote

/**
 * Границы окна сбора данных.
 *
 * Окно начинается от самого позднего из двух моментов: «точка начала сбора» из настроек и
 * время последней успешной отправки. Небольшой нахлёст нужен потому, что запись может попасть
 * в системный провайдер с задержкой — например, звонок записался в журнал уже после того, как
 * мы прочитали журнал в прошлом цикле. Повторно прочитанные строки отсекаются по `_id`,
 * поэтому нахлёст не приводит к дублям.
 *
 * Нахлёст не применяется, пока успешной отправки не было: иначе первый отчёт захватил бы
 * данные раньше выбранной пользователем точки начала.
 */
object SyncWindow {

    const val OVERLAP_MS = 5L * 60 * 1000

    fun start(historyStartAtMs: Long, lastSuccessSyncAtMs: Long): Long {
        val base = maxOf(historyStartAtMs, lastSuccessSyncAtMs)
        val overlap = if (lastSuccessSyncAtMs > 0L) OVERLAP_MS else 0L
        return (base - overlap).coerceAtLeast(0L)
    }
}
