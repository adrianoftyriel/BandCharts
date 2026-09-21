package org.bandcharts.app.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bandcharts.app.data.DocumentSources
import org.bandcharts.app.data.LibraryRepository
import org.bandcharts.app.data.SettingsRepository
import org.bandcharts.app.data.SetlistBook
import org.bandcharts.app.data.SetlistRepository
import org.bandcharts.library.LibraryBackupCodec
import org.bandcharts.library.LibraryBackupManifest

/**
 * Everything off this device, in one file, and everything back.
 *
 * A backup is a zip rather than a plain JSON file like a set list export,
 * because it has to carry more than metadata: a managed chart's bytes live
 * only in this app's storage, and a backup that could not restore those would
 * hand the user a library full of songs with nothing behind them. The
 * manifest at the root is exactly [LibraryBackupCodec]'s JSON, so the schema
 * is shared with the pure-Kotlin layer and only the zip container is
 * Android-specific.
 */
class BackupController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val setlists: SetlistRepository,
    private val settings: SettingsRepository,
    private val appVersion: String,
) {
    var working by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    /** Writes a backup to a shareable file and opens the share sheet. */
    fun backup() {
        scope.launch {
            working = true
            val uri = withContext(Dispatchers.IO) { runCatching { writeBackupFile() }.getOrNull() }
            working = false

            if (uri == null) {
                message = "Could not create the backup file."
                return@launch
            }

            val share = Intent(Intent.ACTION_SEND).apply {
                type = LibraryBackupManifest.MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "BandCharts backup")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(share, "Save BandCharts backup")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Reads a backup file and replaces the library and set lists on this
     * device with what is in it.
     *
     * This is a restore, not a merge: anything added since the backup was made
     * is gone afterwards. That is what "restore" means, and pretending
     * otherwise by quietly merging would make it impossible to undo a mistake
     * by going back to an older backup.
     */
    fun restore(uri: Uri) {
        scope.launch {
            working = true
            val extracted = withContext(Dispatchers.IO) { runCatching { extractBackupFile(uri) }.getOrNull() }
            working = false

            val manifest = extracted?.manifest
            if (manifest == null) {
                message = "That does not look like a BandCharts backup."
                return@launch
            }
            if (!LibraryBackupCodec.canRead(manifest)) {
                message = "That backup was made by a newer version of BandCharts. " +
                    "Update the app to restore it without losing anything."
                return@launch
            }

            val managedDirectory = File(context.filesDir, MANAGED_DIRECTORY)
            var missingManaged = 0
            val songs = manifest.library.songs.mapNotNull { song ->
                if (song.sourceId != DocumentSources.MANAGED_SOURCE_ID) return@mapNotNull song
                val name = Uri.parse(song.uri).lastPathSegment
                if (name == null || name !in extracted.restoredManagedFiles) {
                    missingManaged++
                    return@mapNotNull null
                }
                song.copy(uri = Uri.fromFile(File(managedDirectory, name)).toString())
            }

            library.restore(manifest.library.copy(songs = songs))
            setlists.restore(SetlistBook(manifest.setlists))

            message = buildString {
                append("Restored ${songs.size} chart(s) and ${manifest.setlists.size} set list(s)")
                manifest.exportedBy?.let { append(" from $it") }
                append(".")
                if (missingManaged > 0) {
                    append(
                        " $missingManaged chart(s) stored on the device were missing from the " +
                            "file and were skipped.",
                    )
                }
                val externalSources = manifest.library.sources.count {
                    it.id != DocumentSources.MANAGED_SOURCE_ID
                }
                if (externalSources > 0) {
                    append(
                        " Folders from Drive, OneDrive and similar may need Android's " +
                            "permission re-granted before a rescan finds their charts again.",
                    )
                }
            }
        }
    }

    fun clearMessage() {
        message = null
    }

    private fun writeBackupFile(): Uri {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val deviceName = settings.settings.value.deviceName.ifEmpty { null }
        val manifest = LibraryBackupCodec.bundle(
            library = library.index.value,
            setlists = setlists.book.value.setlists,
            exportedBy = deviceName,
            producer = "BandCharts $appVersion",
            now = now,
        )
        val file = File(directory, LibraryBackupCodec.fileName(deviceName, now))

        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
            zip.putNextEntry(ZipEntry(LibraryBackupManifest.MANIFEST_ENTRY))
            zip.write(LibraryBackupCodec.encode(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val managedFiles = File(context.filesDir, MANAGED_DIRECTORY).listFiles().orEmpty()
            for (managedFile in managedFiles) {
                if (!managedFile.isFile) continue
                zip.putNextEntry(ZipEntry("${LibraryBackupManifest.MANAGED_ENTRY_PREFIX}${managedFile.name}"))
                managedFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private data class ExtractedBackup(
        val manifest: LibraryBackupManifest?,
        val restoredManagedFiles: Set<String>,
    )

    /**
     * Unpacks the zip in one pass: the manifest is read into memory, and each
     * managed chart's bytes are written straight into place. Names inside
     * `managed/` are checked for path traversal before use, because this file
     * may have been forwarded through an email client or a chat app by the
     * time it gets here.
     */
    private fun extractBackupFile(uri: Uri): ExtractedBackup {
        val managedDirectory = File(context.filesDir, MANAGED_DIRECTORY).apply { mkdirs() }
        var manifest: LibraryBackupManifest? = null
        val restored = mutableSetOf<String>()

        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        when {
                            entry.name == LibraryBackupManifest.MANIFEST_ENTRY -> {
                                manifest = LibraryBackupCodec.decode(zip.readBytes().toString(Charsets.UTF_8))
                            }
                            entry.name.startsWith(LibraryBackupManifest.MANAGED_ENTRY_PREFIX) -> {
                                val name = entry.name.removePrefix(LibraryBackupManifest.MANAGED_ENTRY_PREFIX)
                                if (name.isNotBlank() && !name.contains('/') && name != ".." ) {
                                    File(managedDirectory, name).outputStream().use { out -> zip.copyTo(out) }
                                    restored += name
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        return ExtractedBackup(manifest, restored)
    }

    companion object {
        private const val MANAGED_DIRECTORY = "managed"
    }
}
