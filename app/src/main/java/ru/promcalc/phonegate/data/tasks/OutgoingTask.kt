package ru.promcalc.phonegate.data.tasks

import kotlinx.serialization.Serializable

/** Тип действия, полученного от сервера. */
@Serializable
enum class TaskKind { SEND_SMS, MAKE_CALL }

/** Состояние задачи. */
@Serializable
enum class TaskState { PENDING, DONE, FAILED }

/**
 * Задача из ответа сервера: отправить SMS или совершить звонок в указанное время.
 *
 * Задачи переживают перезагрузку (хранятся в `files/tasks.json`), потому что сервер может
 * назначить время в будущем.
 */
@Serializable
data class OutgoingTask(
    val id: Long,
    val kind: TaskKind,
    /** Индекс слота SIM (0 — первая, 1 — вторая). */
    val simSlot: Int,
    val phone: String,
    val text: String = "",
    /** Когда исполнять (мс). Время в прошлом — исполнять немедленно. */
    val atMs: Long,
    val createdAtMs: Long,
    val state: TaskState = TaskState.PENDING,
    val attempts: Int = 0,
    val finishedAtMs: Long? = null,
    val lastError: String? = null,
) {
    /** Признак «эту задачу уже пробовали исполнить»: нужен, чтобы повторы не дублировались. */
    fun sameRequestAs(other: OutgoingTask): Boolean =
        kind == other.kind &&
            simSlot == other.simSlot &&
            phone == other.phone &&
            text == other.text &&
            atMs == other.atMs
}
