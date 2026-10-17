package ru.promcalc.phonegate.data.telephony

/**
 * Управление текущим вызовом.
 *
 * Отдельный интерфейс нужен, чтобы сценарий входящего звонка не зависел от того, каким
 * способом мы снимаем трубку: сегодня это `TelecomManager`, при неудаче возможно подключение
 * роли «Телефон по умолчанию» с `InCallService` (см. план, раздел рисков).
 */
interface CallController {

    /** Снять трубку на входящем звонке. */
    fun acceptRingingCall(): Boolean

    /** Сбросить входящий звонок, не отвечая. */
    fun rejectRingingCall(): Boolean

    /** Положить трубку в активном разговоре. */
    fun hangUp(): Boolean

    /** Включить или выключить громкую связь. */
    fun setSpeakerphoneOn(on: Boolean)
}
