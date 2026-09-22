package org.bandcharts.app.chartserve

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bandcharts.app.data.ChartServeClient

enum class PairPhase { IDLE, PAIRING }

/**
 * Exchanging a pairing code for this device's own token.
 *
 * This is the one thing about ChartServe that needs a network call before
 * there is anything to show for it - see [ChartServeClient] for why. Once
 * paired, the server address and the token just sit in
 * [org.bandcharts.app.data.AppSettings] like any other setting, which is why
 * there is no controller state here for "paired" - the screen reads that
 * straight out of settings.
 */
class ChartServeController(private val scope: CoroutineScope) {
    var phase by mutableStateOf(PairPhase.IDLE)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun pair(
        serverUrl: String,
        code: String,
        deviceName: String,
        onPaired: (serverUrl: String, token: String, deviceId: String) -> Unit,
    ) {
        if (phase == PairPhase.PAIRING) return
        val trimmedUrl = serverUrl.trim()
        if (trimmedUrl.isEmpty()) {
            error = "Enter the server's address first."
            return
        }
        scope.launch {
            phase = PairPhase.PAIRING
            error = null
            when (val result = ChartServeClient.pair(trimmedUrl, code, deviceName)) {
                is ChartServeClient.PairResult.Failed -> error = result.reason
                is ChartServeClient.PairResult.Ok ->
                    onPaired(trimmedUrl, result.response.token, result.response.deviceId)
            }
            phase = PairPhase.IDLE
        }
    }

    fun dismissError() {
        error = null
    }
}
