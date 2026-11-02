package ru.promcalc.phonegate.data.settings

/**
 * Настройки приложения. Все значения переживают перезагрузку телефона.
 *
 * Значения по умолчанию совпадают с тем, что описано в docs/plan.md (раздел 5).
 */
data class Settings(
    /** Фоновый процесс должен работать (единственный источник правды для службы). */
    val serviceEnabled: Boolean = false,
    /** Поднимать фоновый процесс при включении телефона. */
    val startOnBoot: Boolean = false,
    /** Интервал между циклами обмена, секунды. */
    val intervalSeconds: Int = 60,
    /** URL обмена: отправляем принятые SMS и звонки, получаем команды. */
    val exchangeUrl: String = "",
    /** URL отчёта об исходящих действиях; пусто — отчёт не отправляется. */
    val outgoingReportUrl: String = "",
    /** Имя заголовка авторизации; пусто — авторизация не добавляется. */
    val authHeaderName: String = "Authorization",
    /** Значение заголовка авторизации, например `Bearer abc123`. */
    val authHeaderValue: String = "",
    /**
     * Не отправлять записи, у которых номер отправителя SMS или звонящего не
     * приводится к E.164 (например, буквенные имена отправителей и служебные
     * номера). Такие записи всё равно считаются прочитанными: курсор сдвигается.
     */
    val skipInvalidNumbers: Boolean = true,
    /** Номер телефона первой SIM. */
    val sim1Phone: String = "",
    /** Номер телефона второй SIM. */
    val sim2Phone: String = "",
    /** Секунд ожидания до снятия трубки на входящем звонке. */
    val answerAfterSeconds: Int = 10,
    /** Максимальная длительность записи ответа абонента, секунды; 0 — не записывать. */
    val maxRecordSeconds: Int = 60,
    /** Секунд до сброса исходящего звонка. */
    val outgoingHangupAfterSeconds: Int = 30,
    /** mp3-сообщение для входящих на первую SIM (SAF-URI). */
    val sim1MessageUri: String = "",
    /** Отображаемое имя файла сообщения для первой SIM. */
    val sim1MessageName: String = "",
    /** mp3-сообщение для входящих на вторую SIM (SAF-URI). */
    val sim2MessageUri: String = "",
    /** Отображаемое имя файла сообщения для второй SIM. */
    val sim2MessageName: String = "",
    /** Момент, с которого собираются SMS и звонки (мс). */
    val historyStartAtMs: Long = 0L,
    /** Сколько последних записей разговоров хранить. */
    val keepRecordings: Int = 20,
) {

    /** Номер телефона SIM по индексу слота (0 — первая, 1 — вторая). */
    fun phoneForSlot(simSlot: Int): String = if (simSlot == 0) sim1Phone else sim2Phone

    /** URI сообщения для входящих по индексу слота. */
    fun messageUriForSlot(simSlot: Int): String = if (simSlot == 0) sim1MessageUri else sim2MessageUri

    /** Отображаемое имя файла сообщения по индексу слота. */
    fun messageNameForSlot(simSlot: Int): String = if (simSlot == 0) sim1MessageName else sim2MessageName

    /** Задан ли номер для второй SIM (иначе секция второй SIM в отчёте не отправляется). */
    val hasSecondSim: Boolean get() = sim2Phone.isNotBlank()
}
