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
 * Unity's entry point for importing a PDF through the system document picker.
 *
 * On Meta Quest the picker is a 2D panel. It is hosted by [FilePickerOverlayActivity], which runs in
 * its own task, so Horizon OS shows it as an overlay over the immersive Unity activity instead of
 * taking the Unity task out of VR. The picked document is copied into the destination directory
 * before Unity hears about it, so Unity only deals with a plain file path.
 */
object FilePickerBridge {
    private const val TAG = "EmuFilePicker"
    private const val COPY_BUFFER_SIZE = 64 * 1024

    private val copyExecutor = Executors.newSingleThreadExecutor()
    private var pendingRequest: Request? = null

    private class Request(val destinationDir: File, val callback: FilePickerCallback)

    /** The task of the activity that opened the picker, which gets focus back when it closes. */
    private var launcherTaskId = -1

    @JvmStatic
    fun launch(activity: Activity, destinationDir: String, callback: FilePickerCallback) {
        synchronized(this) {
            if (pendingRequest != null) {
                callback.onFailed("The file picker is already open")
                return
            }
            pendingRequest = Request(File(destinationDir), callback)
        }
        launcherTaskId = activity.taskId

        try {
            val intent = Intent(activity, FilePickerOverlayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to open the file picker", e)
            takeRequest()?.callback?.onFailed("Failed to open the file picker: ${e.message}")
        }
    }

    /**
     * Copies the picked document off the UI thread. The stream must already be open: the read grant
     * for the document belongs to [FilePickerOverlayActivity] and ends when it finishes, but a stream
     * opened while it was held stays readable.
     */
    internal fun onDocumentPicked(document: InputStream, displayName: String) {
        val request = takeRequest()
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

    internal fun onPickerCancelled() {
        takeRequest()?.callback?.onCancelled()
    }

    internal fun onPickerFailed(message: String) {
        takeRequest()?.callback?.onFailed(message)
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

    private fun takeRequest(): Request? = synchronized(this) {
        pendingRequest.also { pendingRequest = null }
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
        return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
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
