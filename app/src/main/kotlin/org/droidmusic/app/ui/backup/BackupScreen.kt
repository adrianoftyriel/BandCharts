package org.droidmusic.app.ui.backup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.SectionLabel
import org.droidmusic.library.LibraryBackupManifest

/**
 * One file, everything in it: every chart the library knows about (managed
 * copies included), every set list, archived or not.
 *
 * Restore asks first. It replaces what's on the device rather than merging,
 * so picking the wrong file by mistake would otherwise be a quiet way to lose
 * an evening's work building tonight's set list.
 */
@Composable
fun BackupScreen(
    controller: BackupController,
    onBack: () -> Unit,
) {
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }

    val pickBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) pendingRestore = uri
    }

    Column(Modifier.fillMaxSize()) {
        Header(title = "Backup & restore", onBack = onBack)

        Column(Modifier.padding(16.dp)) {
            SectionLabel("Back up")
            Text(
                "Saves every chart the library knows about - including copies stored on this " +
                    "device - and every set list, to one file you can save wherever you like: " +
                    "a Drive folder, an email to yourself, a USB stick.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = controller::backup,
                enabled = !controller.working,
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Create a backup") }

            HorizontalDivider(Modifier.padding(vertical = 24.dp))

            SectionLabel("Restore")
            Text(
                "Replaces the library and every set list on this device with what's in the " +
                    "backup file. Anything added since that backup was made is gone afterwards.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    pickBackup.launch(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(
                                Intent.EXTRA_MIME_TYPES,
                                arrayOf(LibraryBackupManifest.MIME_TYPE, "application/octet-stream"),
                            )
                        },
                    )
                },
                enabled = !controller.working,
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Choose a backup file") }

            if (controller.working) {
                CircularProgressIndicator(Modifier.padding(top = 16.dp))
            }

            controller.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }

    val toRestore = pendingRestore
    if (toRestore != null) {
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "This replaces the current library and every set list on this device. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.restore(toRestore)
                        pendingRestore = null
                    },
                ) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }
}
