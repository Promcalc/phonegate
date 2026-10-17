package ru.promcalc.phonegate.data.telephony

import android.content.Context
import android.net.Uri
import android.telephony.TelephonyManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository
import ru.promcalc.phonegate.data.media.AudioMessagePlayer
import ru.promcalc.phonegate.data.media.CallRecorder
import ru.promcalc.phonegate.data.media.MessageCache
import ru.promcalc.phonegate.data.records.RecordingStore
import ru.promcalc.phonegate.data.settings.Settings
import ru.promcalc.phonegate.data.settings.SettingsRepository
import ru.promcalc.phonegate.logRepository
import ru.promcalc.phonegate.service.Notifications
import ru.promcalc.phonegate.settingsRepository

/**
 * Сценарий входящего звонка из ТЗ.
 *
 * Порядок шагов: ждём `answerAfterSeconds` → если звонок сброшен, выходим → если mp3 не задан
 * или недоступен, сбрасываем звонок → снимаем трубку, включаем громкую связь, проигрываем
 * сообщение → записываем ответ не дольше `maxRecordSeconds` (0 — не записывать) → кладём
 * трубку и чистим старые записи. Каждый шаг попадает в лог.
 *
 * Сценарий можно прервать в любой момент: если абонент положил трубку, шаги останавливаются;
 * если трубку снял сам пользователь, автоматический сценарий отменяется целиком.
 */
class IncomingCallHandler(context: Context) : IncomingCallWatcher.Listener {

    private val appContext = context.applicationContext
    private val log: LogRepository = appContext.logRepository
    private val settingsRepository: SettingsRepository = appContext.settingsRepository

    private val callController: CallController = TelecomCallController(appContext, log)
    private val messageCache = MessageCache(appContext, log)
    private val player = AudioMessagePlayer(appContext, log)
    private val recorder = CallRecorder(appContext, log)
    private val recordingStore = RecordingStore(appContext, log)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callEndedSignal = Channel<Unit>(Channel.CONFLATED)
    private val callFinished = AtomicBoolean(false)

    @Volatile
    private var answeredByUs = false

    @Volatile
    private var handlingJob: Job? = null

    override fun onCallStateChanged(simSlot: Int, state: Int, incomingNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> startHandling(simSlot, incomingNumber)
            TelephonyManager.CALL_STATE_OFFHOOK -> onOffHook()
            TelephonyManager.CALL_STATE_IDLE -> onIdle()
        }
    }

    /** Остановить сценарий и перестать следить за звонками (служба выключается). */
    fun stop() {
        handlingJob?.cancel()
        scope.cancel()
        callController.setSpeakerphoneOn(false)
    }

    // region события телефонии

    private fun startHandling(simSlot: Int, incomingNumber: String?) {
        if (handlingJob?.isActive == true) {
            log.debug(LogCategory.CALL_IN, "Звонок уже обрабатывается, повторный сигнал пропущен")
            return
        }

        val slot = if (simSlot < 0) 0 else simSlot
        callFinished.set(false)
        answeredByUs = false
        drainEndedSignals()

        handlingJob = scope.launch { handle(slot, incomingNumber) }
    }

    private fun onOffHook() {
        // Трубку мог снять пользователь: тогда играть сообщение и писать разговор нельзя.
        if (handlingJob?.isActive == true && !answeredByUs) {
            log.info(LogCategory.CALL_IN, "Трубку снял пользователь — автоматический сценарий отменён")
            handlingJob?.cancel()
        }
    }

    private fun onIdle() {
        if (handlingJob?.isActive == true) {
            callFinished.set(true)
            callEndedSignal.trySend(Unit)
        }
        answeredByUs = false
    }

    private fun drainEndedSignals() {
        while (callEndedSignal.tryReceive().isSuccess) {
            // Убираем сигналы от прошлых звонков, чтобы они не прервали текущий сценарий.
        }
    }

    // endregion

    private suspend fun handle(simSlot: Int, incomingNumber: String?) {
        val settings = settingsRepository.current()
        val numberNote = incomingNumber?.takeIf { it.isNotBlank() }?.let { ", номер $it" }.orEmpty()
        log.info(
            LogCategory.CALL_IN,
            "Входящий звонок на SIM ${simSlot + 1}$numberNote, отвечу через ${settings.answerAfterSeconds} с",
        )

        if (!waitBeforeAnswer(settings.answerAfterSeconds)) {
            log.info(LogCategory.CALL_IN, "Звонок завершился до ответа — сценарий не выполняется")
            return
        }

        val messageFile = resolveMessage(simSlot, settings)
        if (messageFile == null) {
            log.warn(
                LogCategory.CALL_IN,
                "mp3-сообщение для SIM ${simSlot + 1} не задано или недоступно — сбрасываю звонок",
            )
            if (!callController.rejectRingingCall()) {
                log.warn(LogCategory.CALL_IN, "Сбросить звонок программно не удалось, сбросьте вручную")
            }
            return
        }

        if (!callController.acceptRingingCall()) {
            log.error(
                LogCategory.CALL_IN,
                "Снять трубку не удалось: проверьте разрешение «Ответ на звонки»",
            )
            Notifications.notifyProblem(
                appContext,
                "Не удалось снять трубку на входящем звонке. Проверьте разрешение «Ответ на звонки» и запустите процесс заново.",
            )
            return
        }

        answeredByUs = true
        log.info(LogCategory.CALL_IN, "Трубка снята, включаю громкую связь")
        callController.setSpeakerphoneOn(true)

        playMessage(messageFile)
        if (callFinished.get()) {
            hangUp()
            return
        }

        recordAnswer(simSlot, settings)
        hangUp()
    }

    /**
     * @return `true`, если время ожидания истекло и звонок всё ещё идёт; `false`, если звонок
     *   завершился раньше.
     */
    private suspend fun waitBeforeAnswer(seconds: Int): Boolean {
        val delayMs = seconds.coerceAtLeast(0) * 1_000L
        if (delayMs <= 0L) return !callFinished.get()
        val endedEarly = withTimeoutOrNull(delayMs) { callEndedSignal.receive() } != null
        return !endedEarly && !callFinished.get()
    }

    private fun resolveMessage(simSlot: Int, settings: Settings): File? {
        val uriString = settings.messageUriForSlot(simSlot)
        if (uriString.isBlank()) return null
        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            log.warn(LogCategory.CALL_IN, "Некорректная ссылка на mp3-сообщение", e.toString())
            return null
        }
        return messageCache.prepare(simSlot, uri)
    }

    private fun playMessage(file: File) {
        log.info(LogCategory.CALL_IN, "Проигрываю сообщение ${file.name}")
        val result = player.play(file) { callFinished.get() }
        when {
            callFinished.get() ->
                log.info(LogCategory.CALL_IN, "Абонент завершил разговор во время сообщения")

            result.completed ->
                log.info(LogCategory.CALL_IN, "Сообщение проиграно полностью")

            else -> log.warn(LogCategory.CALL_IN, "Сообщение проиграно не до конца", result.error)
        }
    }

    private fun recordAnswer(simSlot: Int, settings: Settings) {
        if (settings.maxRecordSeconds <= 0) {
            log.info(LogCategory.CALL_IN, "Запись ответа отключена в настройках (0 с)")
            return
        }

        val file = recordingStore.newFile(simSlot, System.currentTimeMillis())
        log.info(LogCategory.CALL_IN, "Записываю ответ абонента, не дольше ${settings.maxRecordSeconds} с")
        val result = recorder.record(file, settings.maxRecordSeconds * 1_000L) { callFinished.get() }
        if (!result.recorded) {
            log.warn(LogCategory.CALL_IN, "Запись ответа не удалась", result.error)
        }
        recordingStore.prune(settings.keepRecordings)
    }

    private fun hangUp() {
        callController.setSpeakerphoneOn(false)
        if (callController.hangUp()) {
            log.info(LogCategory.CALL_IN, "Трубка положена")
        } else {
            log.warn(LogCategory.CALL_IN, "Положить трубку программно не удалось")
        }
    }
}
