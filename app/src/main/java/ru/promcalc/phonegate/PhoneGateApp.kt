package ru.promcalc.phonegate

import android.app.Application
import android.content.Context
import ru.promcalc.phonegate.data.settings.SettingsRepository
import ru.promcalc.phonegate.data.state.SyncStateStore

/**
 * Точка входа приложения и ручное внедрение зависимостей.
 *
 * Репозитории создаются лениво и живут столько же, сколько процесс: настройки и служебное
 * состояние читаются из SharedPreferences, поэтому отдельного контейнера DI не требуется.
 */
class PhoneGateApp : Application() {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val syncStateStore: SyncStateStore by lazy { SyncStateStore(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Прогреваем настройки: на первом запуске фиксируется точка начала сбора данных.
        settingsRepository.current()
    }

    companion object {
        private lateinit var instance: PhoneGateApp

        fun get(): PhoneGateApp = instance
    }
}

val Context.phoneGateApp: PhoneGateApp get() = PhoneGateApp.get()

val Context.settingsRepository: SettingsRepository get() = phoneGateApp.settingsRepository

val Context.syncStateStore: SyncStateStore get() = phoneGateApp.syncStateStore
