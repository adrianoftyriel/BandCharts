package org.bandcharts.app.chartserve

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bandcharts.app.data.ChartServeClient
import org.bandcharts.chartserve.PairCode
import org.bandcharts.chartserve.ServerAddress

enum class PairPhase { IDLE, PAIRING }

enum class IssuePhase { IDLE, ISSUING }

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
        onPaired: (serverUrl: String, token: String, deviceId: String, canPublish: Boolean) -> Unit,
    ) {
        if (phase == PairPhase.PAIRING) return
        val trimmedUrl = ServerAddress.normalise(serverUrl)
        if (trimmedUrl.isEmpty()) {
            error = "Enter the server's address first."
            return
        }
        scope.launch {
            phase = PairPhase.PAIRING
            error = null
            when (val result = ChartServeClient.pair(trimmedUrl, code, deviceName)) {
                is ChartServeClient.PairResult.Failed -> error = result.reason
                is ChartServeClient.PairResult.Ok -> onPaired(
                    trimmedUrl,
                    result.response.token,
                    result.response.deviceId,
                    result.response.canPublish,
                )
            }
            phase = PairPhase.IDLE
        }
    }

    fun dismissError() {
        error = null
    }

    // ---- pairing another phone -----------------------------------------------

    /**
     * A code this phone minted for somebody else's, while it is still worth
     * showing. Not saved anywhere: it lasts ten minutes and works once, and a
     * code that outlived leaving this screen would only be a code on a screen
     * nobody is looking at.
     */
    var issued by mutableStateOf<PairCode?>(null)
        private set
    var issuePhase by mutableStateOf(IssuePhase.IDLE)
        private set
    var issueError by mutableStateOf<String?>(null)
        private set

    fun createPairingCode(serverUrl: String, token: String) {
        if (issuePhase == IssuePhase.ISSUING) return
        scope.launch {
            issuePhase = IssuePhase.ISSUING
            issueError = null
            when (val result = ChartServeClient.createPairingCode(serverUrl, token)) {
                is ChartServeClient.PairCodeResult.Failed -> issueError = result.reason
                is ChartServeClient.PairCodeResult.Ok -> issued = result.code
            }
            issuePhase = IssuePhase.IDLE
        }
    }

    /** Forgets a code once it has expired, or when this phone unpairs. */
    fun clearIssued(code: PairCode? = null) {
        if (code == null || issued == code) issued = null
        issueError = null
    }
}
