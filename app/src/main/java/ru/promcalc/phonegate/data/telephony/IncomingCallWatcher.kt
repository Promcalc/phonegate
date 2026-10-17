@file:Suppress("DEPRECATION") // PhoneStateListener нужен для Android 11 и ниже

package ru.promcalc.phonegate.data.telephony

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogRepository
import ru.promcalc.phonegate.data.sim.SubscriptionRepository

/**
 * Слежение за состоянием входящего звонка.
 *
 * Слушатель ставится отдельно на каждую активную подписку (`createForSubscriptionId`), чтобы
 * знать, на какую SIM звонят: от этого зависит, какое mp3 проигрывать. Если список подписок
 * недоступен (нет разрешения), слушаем состояние телефона в целом и считаем, что звонили
 * на первую SIM — с предупреждением в лог.
 *
 * На Android 12+ используется `TelephonyCallback`, на более старых — `PhoneStateListener`.
 * Регистрация должна идти из потока с очередью сообщений, поэтому вызывать её нужно из
 * главного потока (служба делает это в `onCreate`).
 */
class IncomingCallWatcher(
    context: Context,
    private val subscriptions: SubscriptionRepository,
    private val log: LogRepository,
) {

    /** События телефонии, приведённые к нашим понятиям. */
    interface Listener {
        fun onCallStateChanged(simSlot: Int, state: Int, incomingNumber: String?)
    }

    private val appContext = context.applicationContext
    private val registrations = mutableListOf<Registration>()

    fun start(listener: Listener) {
        stop()

        val active = subscriptions.active()
        if (active.isEmpty()) {
            val manager = appContext.getSystemService(TelephonyManager::class.java)
            if (manager == null) {
                log.error(LogCategory.CALL_IN, "Служба телефонии недоступна: входящие звонки не отслеживаются")
                return
            }
            log.warn(
                LogCategory.CALL_IN,
                "Список SIM недоступен: следим за звонками без привязки к SIM (считаем первой)",
            )
            register(manager, simSlot = 0, listener = listener)
            return
        }

        active.forEach { info ->
            val manager = try {
                appContext.getSystemService(TelephonyManager::class.java)
                    ?.createForSubscriptionId(info.subscriptionId)
            } catch (e: Exception) {
                log.warn(LogCategory.CALL_IN, "Не удалось подписаться на SIM ${info.simSlotIndex + 1}", e.toString())
                null
            } ?: return@forEach

            register(manager, simSlot = info.simSlotIndex, listener = listener)
        }

        log.info(LogCategory.CALL_IN, "Слежение за входящими звонками включено (SIM: ${active.size})")
    }

    fun stop() {
        registrations.forEach { registration ->
            try {
                registration.unregister()
            } catch (e: Exception) {
                log.debug(LogCategory.CALL_IN, "Не удалось снять слушателя звонков", e.toString())
            }
        }
        registrations.clear()
    }

    private fun register(manager: TelephonyManager, simSlot: Int, listener: Listener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    listener.onCallStateChanged(simSlot, state, incomingNumber = null)
                }
            }
            manager.registerTelephonyCallback(ContextCompat.getMainExecutor(appContext), callback)
            registrations += Registration.Api31(manager, callback)
        } else {
            @Suppress("DEPRECATION")
            val phoneStateListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    listener.onCallStateChanged(simSlot, state, incomingNumber = phoneNumber)
                }
            }
            @Suppress("DEPRECATION")
            manager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            registrations += Registration.Legacy(manager, phoneStateListener)
        }
    }

    private sealed interface Registration {
        fun unregister()

        class Api31(
            private val manager: TelephonyManager,
            private val callback: TelephonyCallback,
        ) : Registration {
            override fun unregister() = manager.unregisterTelephonyCallback(callback)
        }

        class Legacy(
            private val manager: TelephonyManager,
            private val listener: PhoneStateListener,
        ) : Registration {
            @Suppress("DEPRECATION")
            override fun unregister() = manager.listen(listener, PhoneStateListener.LISTEN_NONE)
        }
    }
}
