package com.emu.android

import android.app.Activity
import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Unity's entry point for importing PDFs through the system document picker, or through the Google
 * Drive app when it is installed. The user can pick several PDFs, or, with [launchFolder], a folder
 * whose PDFs are all imported.
 *
 * On Meta Quest the picker is a 2D panel. It is hosted by [FilePickerOverlayActivity], which runs in
 * its own task, so Horizon OS shows it as an overlay over the immersive Unity activity instead of
 * taking the Unity task out of VR. Picked documents are copied into the destination directory before
 * Unity hears about them, so Unity only deals with plain file paths.
 */
object FilePickerBridge {
    private const val TAG = "EmuFilePicker"
    private const val COPY_BUFFER_SIZE = 64 * 1024
    internal const val PDF_MIME_TYPE = "application/pdf"
    internal const val EXTRA_REQUEST_ID = "com.emu.android.REQUEST_ID"
    internal const val EXTRA_SOURCE = "com.emu.android.SOURCE"

    /** Where the overlay asks the user to pick from. */
    internal enum class Source { DEVICE, DEVICE_FOLDER, GOOGLE_DRIVE }

    /** A picked document, opened while the overlay still held the grant to read it. */
    internal class PickedDocument(val displayName: String, val stream: InputStream?)

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
     * Like [launch], but the user picks a folder. Every PDF in it, and in its subfolders, is copied
     * into the same subfolders of the destination directory. A PDF already copied there by an earlier
     * import of the folder is not copied again, so picking the folder again brings in only new PDFs.
     */
    @JvmStatic
    fun launchFolder(activity: Activity, destinationDir: String, callback: FilePickerCallback) {
        launch(activity, destinationDir, callback, Source.DEVICE_FOLDER)
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
     * Copies the picked documents off the UI thread. Their streams must already be open: the read
     * grants belong to [FilePickerOverlayActivity] and end when it finishes, but a stream opened while
     * they were held stays readable. A document whose stream is null could not be opened.
     */
    internal fun onDocumentsPicked(requestId: Int, documents: List<PickedDocument>) {
        val request = takeRequest(requestId)
        if (request == null) {
            documents.forEach { it.stream?.close() }
            return
        }

        copyExecutor.execute {
            for (document in documents) {
                importOne(request, document.displayName) {
                    val stream = document.stream ?: throw IOException("it could not be opened")
                    stream.use { copyToDirectory(it, document.displayName, request.destinationDir) }
                }
            }
            request.callback.onFinished()
        }
    }

    /**
     * Copies the PDFs in a picked folder off the UI thread. The caller has taken a persistable read
     * grant on [tree] when [releaseGrant] is true, since walking the folder outlives the overlay; it
     * is released once the copies are done.
     */
    internal fun onFolderPicked(requestId: Int, resolver: ContentResolver, tree: Uri, releaseGrant: Boolean) {
        val request = takeRequest(requestId)
        if (request == null) {
            if (releaseGrant) releaseReadGrant(resolver, tree)
            return
        }

        copyExecutor.execute {
            try {
                val pdfs = PickedFolder.findPdfs(resolver, tree)
                if (pdfs.isEmpty()) {
                    request.callback.onFailed("There are no PDFs in that folder")
                    return@execute
                }

                Log.i(TAG, "Importing ${pdfs.size} PDFs from $tree")
                for (pdf in pdfs) {
                    importOne(request, pdf.displayName) { copyFromFolder(resolver, pdf, request.destinationDir) }
                }
                request.callback.onFinished()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read the folder $tree", e)
                request.callback.onFailed("The folder could not be read: ${e.message}")
            } finally {
                if (releaseGrant) releaseReadGrant(resolver, tree)
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

    private inline fun importOne(request: Request, displayName: String, copy: () -> File) {
        try {
            val file = copy()
            Log.i(TAG, "Imported $displayName to $file")
            request.callback.onFilePicked(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import $displayName", e)
            request.callback.onFileFailed("Failed to import $displayName: ${e.message}")
        }
    }

    private fun copyFromFolder(resolver: ContentResolver, pdf: PickedFolder.Pdf, destinationDir: File): File {
        val directory = pdf.folders.fold(destinationDir) { parent, folder -> File(parent, safeName(folder, "Folder")) }
        val existing = File(directory, pdfFileName(pdf.displayName))
        if (existing.isFile && pdf.size != null && existing.length() == pdf.size) {
            // Copied by an earlier import of this folder.
            return existing
        }

        val document = resolver.openInputStream(pdf.uri) ?: throw IOException("it could not be opened")
        return document.use { copyToDirectory(it, pdf.displayName, directory) }
    }

    private fun releaseReadGrant(resolver: ContentResolver, tree: Uri) {
        try {
            resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to release the read grant on $tree", e)
        }
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
        val base = safeName(displayName, "Imported book")
        // Always a lower-case extension, which is what Unity looks for when it reloads the library.
        val stem = if (base.endsWith(".pdf", ignoreCase = true)) base.dropLast(".pdf".length) else base
        return "$stem.pdf"
    }

    /** [name] made safe as one file or folder name. Leading dots go, which also rules out "..". */
    private fun safeName(name: String, fallback: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').ifEmpty { fallback }

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
