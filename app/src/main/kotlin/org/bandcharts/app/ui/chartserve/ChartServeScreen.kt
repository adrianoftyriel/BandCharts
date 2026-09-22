package org.bandcharts.app.ui.chartserve

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bandcharts.app.chartserve.ChartServeController
import org.bandcharts.app.chartserve.PairPhase
import org.bandcharts.app.data.AppSettings
import org.bandcharts.app.ui.common.Header
import org.bandcharts.app.ui.common.SectionLabel
import org.bandcharts.chartserve.PairingCode

/**
 * Pairing this device with the band's ChartServe.
 *
 * There is no field for the admin token here, on purpose - see
 * `docs/API.md` in ChartServe. That token belongs to whoever runs the server,
 * lives on the machine that mints pairing codes, and is never typed into a
 * phone. What is typed in here is the short code that token was used to
 * generate, which is good for ten minutes and works once.
 */
@Composable
fun ChartServeScreen(
    controller: ChartServeController,
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val paired = settings.chartServeToken.isNotBlank()
    var serverUrl by remember(settings.chartServeUrl) { mutableStateOf(settings.chartServeUrl) }
    var code by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Header(title = "ChartServe", onBack = onBack)

        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (paired) {
                SectionLabel("This device")
                Text(
                    "Paired with ${settings.chartServeUrl}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Reads charts and set lists from that server. To stop, unpair here and, if " +
                        "you want it gone from the server too, revoke it from there as well - " +
                        "unpairing here only forgets the token on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                OutlinedButton(
                    onClick = {
                        onChange {
                            it.copy(chartServeUrl = "", chartServeToken = "", chartServeDeviceId = "")
                        }
                    },
                ) { Text("Unpair this device") }
            } else {
                SectionLabel("Server")
                Text(
                    "The band's own ChartServe holds the shared chart library between gigs. Ask " +
                        "whoever runs it for its address and a pairing code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server address") },
                    placeholder = { Text("https://charts.example.org") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )

                SectionLabel("Pairing code")
                Text(
                    "Valid for ten minutes and works once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code") },
                    placeholder = { Text("K7QM-3PDX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                Button(
                    onClick = {
                        controller.pair(
                            serverUrl = serverUrl,
                            code = PairingCode.normalise(code),
                            deviceName = settings.deviceName,
                        ) { pairedUrl, token, deviceId ->
                            onChange {
                                it.copy(
                                    chartServeUrl = pairedUrl,
                                    chartServeToken = token,
                                    chartServeDeviceId = deviceId,
                                )
                            }
                        }
                    },
                    enabled = controller.phase == PairPhase.IDLE &&
                        serverUrl.isNotBlank() &&
                        PairingCode.looksValid(code),
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text(if (controller.phase == PairPhase.PAIRING) "Pairing…" else "Pair") }

                if (controller.phase == PairPhase.PAIRING) {
                    CircularProgressIndicator(Modifier.padding(top = 16.dp))
                }

                controller.error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}
