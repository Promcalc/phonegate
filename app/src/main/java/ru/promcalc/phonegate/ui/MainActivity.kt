package ru.promcalc.phonegate.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ru.promcalc.phonegate.ui.theme.PhoneGateTheme

/**
 * Единственная Activity приложения: окно с вкладками «Настройки» и «Логи».
 *
 * Закрытие окна не останавливает фоновую службу: она живёт в отдельном процессе-компоненте
 * (см. [ru.promcalc.phonegate.service.GatewayService]) и запускается/останавливается
 * только пользователем через настройки.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhoneGateTheme {
                MainScreen()
            }
        }
    }
}
