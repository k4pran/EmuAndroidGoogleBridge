package com.emu.android

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader


class FilePickerActivity : Activity() {

    companion object {
        private const val TAG = "FilePickerActivity"
        private const val FILE_PICKER_REQUEST_CODE = 42
        private var filePickerCallback: FilePickerCallback? = null
        private var supportedMimes: Array<String> = arrayOf("application/pdf")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "{$this} launched")
        PDFBoxResourceLoader.init(applicationContext)

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, supportedMimes)
        }
        Log.i(TAG, "Launching native file picker")
        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE)
    }

    fun openFilePicker(parentActivity: Activity, unityCallback: FilePickerCallback?) {
        val parentIntent = Intent(parentActivity, FilePickerActivity::class.java)
        Log.i(TAG, "Launching FilePickerActivity")
        filePickerCallback = unityCallback;
        parentActivity.startActivityForResult(parentIntent, FILE_PICKER_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            FILE_PICKER_REQUEST_CODE -> {
                val fileUri: Uri? = data?.takeIf { resultCode == RESULT_OK }?.data

                Log.i(TAG, "File picker result: $fileUri")

                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                if (fileUri != null) {
                    contentResolver.releasePersistableUriPermission(fileUri, flags)
                }
                Log.i(TAG, "PERMISSIONS - ${contentResolver.persistedUriPermissions}")
                fileUri?.let {
                    contentResolver.takePersistableUriPermission(it, flags)

                    val inputStream = contentResolver.openInputStream(fileUri)
                    inputStream?.use {pdfInputStream ->
                        val metaData = PdfMetaExtractor.extractMetadata(fileUri.toString(), pdfInputStream)
                        filePickerCallback?.onFilePicked(metaData)
                    }?: Log.e(TAG, "Failed to open PDF input stream")
                }
                Log.i(TAG, "PARENT INFO")
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                val taskInfo = am.getRunningTasks(10)

                Log.i(TAG, "Parent Package: ${parent?.packageName}")
                Log.i(TAG, "Parent Class: ${parent?.javaClass?.name}")
                Log.i(TAG, "Parent toString: ${parent?.toString()}")

                val callingActivity = callingActivity
                Log.i(TAG, "Calling Activity: ${callingActivity?.className}")

                val callingIntent = intent
                Log.i(TAG, "Intent: $callingIntent")
                Log.i(TAG, "Intent component: ${callingIntent?.component}")
                Log.i(TAG, "Intent flags: ${callingIntent?.flags}")

                for (task in taskInfo) {
                    Log.i(TAG, "Task: ${task.baseActivity?.className}")
                }
//
//                val runningApp = am.runningAppProcesses.firstOrNull { it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
//                Log.i(TAG, "Foreground App: ${runningApp?.processName}")
//
//                val unityIntent = packageManager.getLaunchIntentForPackage(packageName)
//                if (unityIntent != null) {
//                    unityIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
//                    startActivity(unityIntent)
//                }


                // Send the user to the home screen
                val homeIntent = Intent(Intent.ACTION_MAIN)
                homeIntent.addCategory(Intent.CATEGORY_HOME)
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(homeIntent)


                // Wait 3 seconds, then bring Unity back
                Log.i(TAG, "Bringing unity back with package name $packageName")
                Handler(Looper.getMainLooper()).postDelayed({
                    val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                    if (am != null) {
                        val tasks = am.appTasks
                        for (task in tasks) {
                            val baseActivity = task.taskInfo.baseActivity
                            if (baseActivity != null && baseActivity.packageName == packageName) {
                                Log.i(TAG, "Moving Unity task to front...")
                                task.moveToFront()
                                return@postDelayed // Exit after moving to front
                            }
                        }
                        Log.w(TAG, "No matching task found to move to front.")
                    }
                }, 2000) // Adjust delay if needed

                finish()
            }
            else -> Log.e(TAG, "Invalid code $requestCode")
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "FilePickerActivity onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "FilePickerActivity onPause")
    }
}
