package com.emu.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import java.io.InputStream

/**
 * Invisible host for the system document picker, or for Google Drive's when the launch asked for
 * Drive. [FilePickerBridge] starts it in a new task (see `taskAffinity` in the manifest) so the
 * picker opens as a Horizon OS panel over the immersive app. It finishes, removing its task, as soon
 * as the picker answers, and VR focus returns to Unity.
 */
class FilePickerOverlayActivity : Activity() {

    companion object {
        private const val TAG = "EmuFilePicker"
        private const val REQUEST_OPEN_DOCUMENT = 1
    }

    private val requestId get() = intent.getIntExtra(FilePickerBridge.EXTRA_REQUEST_ID, -1)

    private val source: FilePickerBridge.Source
        get() {
            val name = intent.getStringExtra(FilePickerBridge.EXTRA_SOURCE)
            return FilePickerBridge.Source.values().firstOrNull { it.name == name } ?: FilePickerBridge.Source.DEVICE
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Recreated while the picker is open; its answer still arrives in onActivityResult.
            return
        }

        val intent = pickIntent()
        if (intent == null) {
            Log.e(TAG, "The Google Drive app is not installed")
            FilePickerBridge.onPickerFailed(requestId, "The Google Drive app is not installed")
            close()
            return
        }

        try {
            startActivityForResult(intent, REQUEST_OPEN_DOCUMENT)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No activity handles ${intent.action}", e)
            FilePickerBridge.onPickerFailed(requestId, "No document picker is available on this device")
            close()
        }
    }

    private fun pickIntent(): Intent? = when (source) {
        FilePickerBridge.Source.DEVICE -> Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(FilePickerBridge.PDF_MIME_TYPE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        FilePickerBridge.Source.DEVICE_FOLDER -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        FilePickerBridge.Source.GOOGLE_DRIVE -> GoogleDriveApp.pickIntent(this, FilePickerBridge.PDF_MIME_TYPE)
            ?.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_OPEN_DOCUMENT) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }

        val uris = if (resultCode == RESULT_OK && data != null) pickedUris(data) else emptyList()
        when {
            uris.isEmpty() -> {
                Log.i(TAG, "Document picker cancelled")
                FilePickerBridge.onPickerCancelled(requestId)
            }
            source == FilePickerBridge.Source.DEVICE_FOLDER -> deliverFolder(uris[0])
            else -> deliver(uris)
        }
        close()
    }

    /** A multiple pick lists its documents in the clip data; a single pick, or a folder, is the data. */
    private fun pickedUris(data: Intent): List<Uri> {
        val clip = data.clipData ?: return listOfNotNull(data.data)
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    override fun onDestroy() {
        // Covers the overlay being torn down before the picker answered. A no-op once a result
        // has been delivered, even if a newer launch is already pending.
        if (isFinishing) {
            FilePickerBridge.onPickerCancelled(requestId)
        }
        super.onDestroy()
    }

    private fun deliver(uris: List<Uri>) {
        val documents = uris.map { uri ->
            val stream: InputStream? = try {
                contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open $uri", e)
                null
            }
            FilePickerBridge.PickedDocument(displayName(uri), stream)
        }
        FilePickerBridge.onDocumentsPicked(requestId, documents)
    }

    /**
     * Hands the folder to the bridge, which reads it after this overlay has gone. The read grant from
     * the picker ends with the overlay, so the folder is held with a persistable grant until then.
     */
    private fun deliverFolder(tree: Uri) {
        val heldGrant = try {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not hold the read grant on $tree; reading it while the grant lasts", e)
            false
        }
        FilePickerBridge.onFolderPicked(requestId, applicationContext.contentResolver, tree, heldGrant)
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read the display name of $uri", e)
        }
        return uri.lastPathSegment ?: ""
    }

    private fun close() {
        if (!isTaskRoot) {
            finish()
            return
        }

        // Left to itself, Horizon OS gives focus to the shell when the panel closes, which brings
        // the user's other open windows up over the immersive app. Hand focus back to the app first.
        FilePickerBridge.returnToLauncherTask(this)
        finishAndRemoveTask()
    }
}
