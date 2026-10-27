package com.emu.android

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Unity's entry point for importing a PDF through the system document picker, or through the Google
 * Drive app when it is installed.
 *
 * On Meta Quest the picker is a 2D panel. It is hosted by [FilePickerOverlayActivity], which runs in
 * its own task, so Horizon OS shows it as an overlay over the immersive Unity activity instead of
 * taking the Unity task out of VR. The picked document is copied into the destination directory
 * before Unity hears about it, so Unity only deals with a plain file path.
 */
object FilePickerBridge {
    private const val TAG = "EmuFilePicker"
    private const val COPY_BUFFER_SIZE = 64 * 1024
    internal const val PDF_MIME_TYPE = "application/pdf"
    internal const val EXTRA_REQUEST_ID = "com.emu.android.REQUEST_ID"
    internal const val EXTRA_SOURCE = "com.emu.android.SOURCE"

    /** Where the overlay asks the user to pick from. */
    internal enum class Source { DEVICE, GOOGLE_DRIVE }

    private val copyExecutor = Executors.newSingleThreadExecutor()
    private var pendingRequest: Request? = null
    private var lastRequestId = 0

    /**
     * One launch. Its id travels with the overlay that serves it, so an overlay that is still being
     * torn down cannot answer, or cancel, a launch that came after it.
     */
    private class Request(val id: Int, val destinationDir: File, val callback: FilePickerCallback)

    /** The task of the activity that opened the picker, which gets focus back when it closes. */
    private var launcherTaskId = -1

    @JvmStatic
    fun launch(activity: Activity, destinationDir: String, callback: FilePickerCallback) {
        launch(activity, destinationDir, callback, Source.DEVICE)
    }

    /**
     * Whether [launchGoogleDrive] can pick through the Google Drive app. Without the app, Unity
     * imports from Drive through the browser instead.
     */
    @JvmStatic
    fun hasGoogleDriveApp(context: Context): Boolean = GoogleDriveApp.isInstalled(context, PDF_MIME_TYPE)

    /** Like [launch], but the user picks from the Google Drive app. */
    @JvmStatic
    fun launchGoogleDrive(activity: Activity, destinationDir: String, callback: FilePickerCallback) {
        launch(activity, destinationDir, callback, Source.GOOGLE_DRIVE)
    }

    private fun launch(activity: Activity, destinationDir: String, callback: FilePickerCallback, source: Source) {
        val request = synchronized(this) {
            if (pendingRequest != null) {
                callback.onFailed("The file picker is already open")
                return
            }
            Request(++lastRequestId, File(destinationDir), callback).also { pendingRequest = it }
        }
        launcherTaskId = activity.taskId

        try {
            // MULTIPLE_TASK: a fresh task every time. With NEW_TASK alone, a launch made while the
            // previous overlay's task is still being removed would join that task and go with it.
            val intent = Intent(activity, FilePickerOverlayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(EXTRA_REQUEST_ID, request.id)
                .putExtra(EXTRA_SOURCE, source.name)
            activity.startActivity(intent)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to open the file picker", e)
            takeRequest(request.id)?.callback?.onFailed("Failed to open the file picker: ${e.message}")
        }
    }

    /**
     * Copies the picked document off the UI thread. The stream must already be open: the read grant
     * for the document belongs to [FilePickerOverlayActivity] and ends when it finishes, but a stream
     * opened while it was held stays readable.
     */
    internal fun onDocumentPicked(requestId: Int, document: InputStream, displayName: String) {
        val request = takeRequest(requestId)
        if (request == null) {
            document.close()
            return
        }

        copyExecutor.execute {
            try {
                val file = document.use { copyToDirectory(it, displayName, request.destinationDir) }
                Log.i(TAG, "Imported $displayName to $file")
                request.callback.onFilePicked(file.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import $displayName", e)
                request.callback.onFailed("Failed to import $displayName: ${e.message}")
            }
        }
    }

    internal fun onPickerCancelled(requestId: Int) {
        takeRequest(requestId)?.callback?.onCancelled()
    }

    internal fun onPickerFailed(requestId: Int, message: String) {
        takeRequest(requestId)?.callback?.onFailed(message)
    }

    internal fun returnToLauncherTask(context: Context) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val task = activityManager.appTasks.firstOrNull { it.taskInfo.taskId == launcherTaskId }
        if (task == null) {
            Log.w(TAG, "Task $launcherTaskId is gone; leaving focus to the system")
            return
        }
        task.moveToFront()
    }

    /** Claims the pending request if it is the one [requestId] names. Each request is answered once. */
    private fun takeRequest(requestId: Int): Request? = synchronized(this) {
        pendingRequest?.takeIf { it.id == requestId }?.also { pendingRequest = null }
    }

    private fun copyToDirectory(document: InputStream, displayName: String, directory: File): File {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create $directory")
        }

        val target = uniqueFile(directory, pdfFileName(displayName))
        val partial = File(directory, "${target.name}.part")
        try {
            partial.outputStream().use { document.copyTo(it, COPY_BUFFER_SIZE) }
            if (!partial.renameTo(target)) {
                throw IOException("Could not move $partial to $target")
            }
        } finally {
            partial.delete()
        }
        return target
    }

    private fun pdfFileName(displayName: String): String {
        val name = displayName.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.')
        val base = name.ifEmpty { "Imported book" }
        // Always a lower-case extension, which is what Unity looks for when it reloads the library.
        val stem = if (base.endsWith(".pdf", ignoreCase = true)) base.dropLast(".pdf".length) else base
        return "$stem.pdf"
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        val stem = fileName.substring(0, fileName.length - ".pdf".length)
        var candidate = File(directory, fileName)
        var copy = 2
        while (candidate.exists()) {
            candidate = File(directory, "$stem ($copy).pdf")
            copy++
        }
        return candidate
    }
}
