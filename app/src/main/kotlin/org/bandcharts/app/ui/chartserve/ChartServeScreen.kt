package org.bandcharts.app.ui.chartserve

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import org.bandcharts.app.chartserve.ChartServeController
import org.bandcharts.app.chartserve.IssuePhase
import org.bandcharts.app.chartserve.PairPhase
import org.bandcharts.app.data.AppSettings
import org.bandcharts.app.ui.common.Header
import org.bandcharts.app.ui.common.SectionLabel
import org.bandcharts.chartserve.PairingCode

/**
 * Pairing this device with the band's ChartServe - and, once it is paired
 * with publish rights, pairing other phones from it.
 *
 * There is no field for an admin credential here, on purpose - see
 * `docs/API.md` in ChartServe. Codes are made by whoever runs the server, in
 * its admin portal, and never by typing anything powerful into a phone. What
 * is typed in here is the short code, which is good for ten minutes and works
 * once.
 *
 * A phone that can publish can make a code too, for the new dep at rehearsal
 * who would otherwise have to find whoever runs the server. That code only
 * ever pairs a phone that reads: the server keeps publishing codes for an
 * admin.
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
                    if (settings.chartServeCanPublish) {
                        "Reads charts and set lists from that server, and can publish to it too."
                    } else {
                        "Reads charts and set lists from that server. Ask whoever runs it for a " +
                            "publishing code if this phone should be able to add charts as well."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    "To stop, unpair here. Unpairing only forgets the token on this phone - to " +
                        "remove it from the server as well, revoke it under Phones in the " +
                        "server's admin portal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )

                if (settings.chartServeCanPublish) {
                    PairAnotherPhone(controller, settings)
                }

                OutlinedButton(
                    onClick = {
                        controller.clearIssued()
                        onChange {
                            it.copy(
                                chartServeUrl = "",
                                chartServeToken = "",
                                chartServeDeviceId = "",
                                chartServeCanPublish = false,
                            )
                        }
                    },
                ) { Text("Unpair this device") }
            } else {
                SectionLabel("Server")
                Text(
                    "The band's own ChartServe holds the shared chart library between gigs. Ask " +
                        "whoever runs it, or anyone whose phone can publish to it, for its " +
                        "address and a pairing code.",
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
                        ) { pairedUrl, token, deviceId, canPublish ->
                            onChange {
                                it.copy(
                                    chartServeUrl = pairedUrl,
                                    chartServeToken = token,
                                    chartServeDeviceId = deviceId,
                                    chartServeCanPublish = canPublish,
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

/** Making a code on this phone for somebody else's. Only shown when this phone can publish. */
@Composable
private fun PairAnotherPhone(controller: ChartServeController, settings: AppSettings) {
    val issued = controller.issued

    // A code is gone from the server once it expires; it should be gone from
    // this screen too, rather than being read out to somebody and refused.
    LaunchedEffect(issued) {
        if (issued != null) {
            delay((issued.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
            controller.clearIssued(issued)
        }
    }

    SectionLabel("Pair another phone")
    Text(
        "Make a code for a bandmate's phone. It will be able to read the library, not " +
            "publish to it - only whoever runs the server can make a publishing code.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (issued != null) {
        Text(
            "On their phone, in BandCharts, go to Settings → ChartServe and enter:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "Server address",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            issued.serverUrl ?: settings.chartServeUrl,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "Code",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            issued.code,
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Works once, until " +
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(issued.expiresAt)) + ".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }

    Button(
        onClick = { controller.createPairingCode(settings.chartServeUrl, settings.chartServeToken) },
        enabled = controller.issuePhase == IssuePhase.IDLE,
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Text(
            when {
                controller.issuePhase == IssuePhase.ISSUING -> "Making a code…"
                issued != null -> "Make another code"
                else -> "Make a pairing code"
            },
        )
    }

    controller.issueError?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    Spacer(Modifier.height(24.dp))
}
