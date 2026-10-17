package ru.promcalc.phonegate.ui.settings

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.records.RecordingInfo
import ru.promcalc.phonegate.data.records.RecordingStore
import ru.promcalc.phonegate.data.settings.Settings
import ru.promcalc.phonegate.logRepository
import ru.promcalc.phonegate.service.GatewayService
import ru.promcalc.phonegate.service.GatewayServiceControl
import ru.promcalc.phonegate.settingsRepository
import ru.promcalc.phonegate.syncCoordinator
import ru.promcalc.phonegate.syncStateStore
import ru.promcalc.phonegate.taskStore

/**
 * Состояние экрана настроек: черновик, признак изменений, ошибки проверки и сообщения.
 *
 * Сохранение выполняет репозиторий; при изменении точки начала сбора данных
 * обнуляются курсоры обмена (docs/decisions.md, D12).
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = application.settingsRepository
    private val stateStore = application.syncStateStore
    private val logRepository = application.logRepository

    private var saved: Settings = repository.current()

    private val _draft = MutableStateFlow(saved)
    val draft: StateFlow<Settings> = _draft.asStateFlow()

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private val _errors = MutableStateFlow<List<String>>(emptyList())
    val errors: StateFlow<List<String>> = _errors.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _lastSuccessSyncAtMs = MutableStateFlow(stateStore.load().lastSuccessSyncAtMs)
    val lastSuccessSyncAtMs: StateFlow<Long> = _lastSuccessSyncAtMs.asStateFlow()

    private val _pendingTasks = MutableStateFlow(application.taskStore.pending().size)
    val pendingTasks: StateFlow<Int> = _pendingTasks.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val recordingStore = RecordingStore(application, application.logRepository)

    private val _recordings = MutableStateFlow(recordingStore.listInfo())
    val recordings: StateFlow<List<RecordingInfo>> = _recordings.asStateFlow()

    /** Ссылка на запись для передачи плееру другого приложения. */
    fun recordingUri(info: RecordingInfo): Uri = FileProvider.getUriForFile(
        getApplication(),
        "${getApplication<Application>().packageName}.files",
        info.file,
    )

    init {
        // Подхватываем изменения, сделанные вне экрана (например, запуск/остановка службы).
        viewModelScope.launch {
            repository.settings.collect { fresh ->
                if (!_dirty.value) {
                    saved = fresh
                    _draft.value = fresh
                }
            }
        }
    }

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_draft.value)
        _draft.value = next
        _dirty.value = next != saved
        if (_errors.value.isNotEmpty()) _errors.value = emptyList()
    }

    fun setHistoryStartTo(millis: Long) = update { it.copy(historyStartAtMs = millis) }

    fun setHistoryStartToNow() = setHistoryStartTo(System.currentTimeMillis())

    fun setMessageForSlot(simSlot: Int, uri: String, displayName: String) = update { current ->
        if (simSlot == 0) {
            current.copy(sim1MessageUri = uri, sim1MessageName = displayName)
        } else {
            current.copy(sim2MessageUri = uri, sim2MessageName = displayName)
        }
    }

    fun clearMessageForSlot(simSlot: Int) = update { current ->
        if (simSlot == 0) {
            current.copy(sim1MessageUri = "", sim1MessageName = "")
        } else {
            current.copy(sim2MessageUri = "", sim2MessageName = "")
        }
    }

    /** Показать сообщение под формой (используется экраном для подсказок). */
    fun showMessage(text: String) {
        _message.value = text
    }

    fun refreshStatus() {
        _lastSuccessSyncAtMs.value = stateStore.load().lastSuccessSyncAtMs
        _pendingTasks.value = getApplication<Application>().taskStore.pending().size
        _recordings.value = recordingStore.listInfo()
    }

    /**
     * Кнопка «Запустить/Остановить фоновый процесс»: сначала сохраняем настройки (цикл должен
     * работать с ними), затем запускаем или останавливаем службу.
     */
    fun setServiceEnabled(enabled: Boolean) {
        val candidate = _draft.value.copy(serviceEnabled = enabled)
        val problems = validate(candidate)
        if (problems.isNotEmpty()) {
            _errors.value = problems
            return
        }

        _draft.value = candidate
        repository.save(candidate)
        saved = candidate
        _dirty.value = false
        GatewayServiceControl.setEnabled(getApplication(), enabled)
        _message.value = if (enabled) "Фоновый процесс запущен" else "Фоновый процесс остановлен"
        refreshStatus()
    }

    /** Ручной обмен: собрать данные, отправить их и забрать команды. */
    fun sendNow() {
        if (_sending.value) return
        _sending.value = true
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                getApplication<Application>().syncCoordinator.syncNow()
            }
            _message.value = outcome.describe()
            _sending.value = false
            // Если сервер вернул команду с уже наступившим временем, просим работающий
            // процесс проверить очередь немедленно.
            if (outcome.exchangeOk && _draft.value.serviceEnabled) {
                GatewayService.requestWakeUp()
            }
            refreshStatus()
        }
    }

    fun messageShown() {
        _message.value = null
    }

    fun save() {
        val candidate = _draft.value
        val problems = validate(candidate)
        if (problems.isNotEmpty()) {
            _errors.value = problems
            return
        }

        val historyChanged = candidate.historyStartAtMs != saved.historyStartAtMs
        repository.save(candidate)
        if (historyChanged) {
            stateStore.resetCursors()
        }
        logRepository.info(
            category = LogCategory.SERVICE,
            message = if (historyChanged) {
                "Настройки сохранены, курсор сбора данных сброшен"
            } else {
                "Настройки сохранены"
            },
        )
        saved = candidate
        _dirty.value = false
        _message.value = if (historyChanged) {
            "Сохранено. Курсор сбора данных сброшен, данные уйдут начиная с указанного момента"
        } else {
            "Настройки сохранены"
        }
        // Требование ТЗ: после сохранения настроек фоновый процесс перезапускается.
        GatewayServiceControl.restart(getApplication())
        refreshStatus()
    }

    private fun validate(settings: Settings): List<String> {
        val problems = mutableListOf<String>()

        if (settings.intervalSeconds !in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS) {
            problems += "Интервал запросов: от $MIN_INTERVAL_SECONDS до $MAX_INTERVAL_SECONDS секунд"
        }
        validateUrl(settings.exchangeUrl, "URL обмена")?.let { problems += it }
        validateUrl(settings.outgoingReportUrl, "URL отчёта об исходящих")?.let { problems += it }

        if (settings.sim1Phone.isNotBlank()) {
            validatePhone(settings.sim1Phone)?.let { problems += "SIM1: $it" }
        }
        if (settings.sim2Phone.isNotBlank()) {
            validatePhone(settings.sim2Phone)?.let { problems += "SIM2: $it" }
        }

        if (settings.answerAfterSeconds !in 0..MAX_ANSWER_SECONDS) {
            problems += "Ожидание до снятия трубки: от 0 до $MAX_ANSWER_SECONDS секунд"
        }
        if (settings.maxRecordSeconds !in 0..MAX_RECORD_SECONDS) {
            problems += "Длительность записи: от 0 до $MAX_RECORD_SECONDS секунд"
        }
        if (settings.outgoingHangupAfterSeconds !in 1..MAX_HANGUP_SECONDS) {
            problems += "Сброс исходящего звонка: от 1 до $MAX_HANGUP_SECONDS секунд"
        }
        if (settings.keepRecordings !in 1..MAX_KEEP_RECORDINGS) {
            problems += "Хранить записей разговора: от 1 до $MAX_KEEP_RECORDINGS"
        }
        if (settings.historyStartAtMs <= 0L) {
            problems += "Укажите начало сбора данных"
        } else if (settings.historyStartAtMs > System.currentTimeMillis() + CLOCK_SKEW_MS) {
            problems += "Начало сбора данных не может быть в будущем"
        }

        return problems
    }

    private fun validateUrl(url: String, title: String): String? {
        if (url.isBlank()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "$title: адрес должен начинаться с http:// или https://"
        }
        if (url.length < MIN_URL_LENGTH) {
            return "$title: слишком короткий адрес"
        }
        return null
    }

    private fun validatePhone(phone: String): String? {
        val allowed = phone.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }
        if (!allowed) return "недопустимые символы в номере"
        if (phone.count { it.isDigit() } < MIN_PHONE_DIGITS) return "слишком короткий номер"
        return null
    }

    companion object {
        const val MIN_INTERVAL_SECONDS = 5
        const val MAX_INTERVAL_SECONDS = 86_400
        const val MAX_ANSWER_SECONDS = 600
        const val MAX_RECORD_SECONDS = 3_600
        const val MAX_HANGUP_SECONDS = 600
        const val MAX_KEEP_RECORDINGS = 1_000

        private const val MIN_URL_LENGTH = 12
        private const val MIN_PHONE_DIGITS = 5
        private const val CLOCK_SKEW_MS = 60_000L
    }
}
