package com.emu.emustorebridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

class FilePickerActivity : Activity() {

    companion object {
        private const val FILE_PICKER_REQUEST_CODE = 42
        private const val TAG = "FilePickerActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Automatically open the file picker dialog
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // Allow all file types
        }
        Log.i(TAG, "Launching file picker")
        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE)
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_PICKER_REQUEST_CODE) {
            val fileUri: Uri? = if (resultCode == Activity.RESULT_OK && data != null) {
                data.data
            } else {
                null
            }

            Log.i(TAG, "File picker result: ${fileUri?.toString()}")

            val resultIntent = Intent()
            resultIntent.data = fileUri // Set the URI directly
            setResult(Activity.RESULT_OK, resultIntent)

            finish() // Close this activity
        }
    }

}
