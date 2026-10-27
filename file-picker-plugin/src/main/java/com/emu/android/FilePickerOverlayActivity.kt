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
 * Invisible host for the system document picker. [FilePickerBridge] starts it in a new task (see
 * `taskAffinity` in the manifest) so the picker opens as a Horizon OS panel over the immersive app.
 * It finishes, removing its task, as soon as the picker answers, and VR focus returns to Unity.
 */
class FilePickerOverlayActivity : Activity() {

    companion object {
        private const val TAG = "EmuFilePicker"
        private const val REQUEST_OPEN_DOCUMENT = 1
        private const val PDF_MIME_TYPE = "application/pdf"
    }

    private val requestId get() = intent.getIntExtra(FilePickerBridge.EXTRA_REQUEST_ID, -1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Recreated while the picker is open; its answer still arrives in onActivityResult.
            return
        }

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(PDF_MIME_TYPE)
        try {
            startActivityForResult(intent, REQUEST_OPEN_DOCUMENT)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No activity handles ACTION_OPEN_DOCUMENT", e)
            FilePickerBridge.onPickerFailed(requestId, "No document picker is available on this device")
            close()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_OPEN_DOCUMENT) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }

        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            Log.i(TAG, "Document picker cancelled")
            FilePickerBridge.onPickerCancelled(requestId)
        } else {
            deliver(uri)
        }
        close()
    }

    override fun onDestroy() {
        // Covers the overlay being torn down before the picker answered. A no-op once a result
        // has been delivered, even if a newer launch is already pending.
        if (isFinishing) {
            FilePickerBridge.onPickerCancelled(requestId)
        }
        super.onDestroy()
    }

    private fun deliver(uri: Uri) {
        val document: InputStream? = try {
            contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open $uri", e)
            null
        }

        if (document == null) {
            FilePickerBridge.onPickerFailed(requestId, "The picked document could not be opened")
            return
        }
        FilePickerBridge.onDocumentPicked(requestId, document, displayName(uri))
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
