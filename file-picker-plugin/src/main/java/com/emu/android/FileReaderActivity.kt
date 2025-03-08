package com.emu.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

class FileReaderActivity : Activity() {

    companion object {
        const val TAG = "FileReaderActivity"

        const val ACTION_READ_FILE = "READ_FILE"
        const val ACTION_READ_FILE_CHUNKS = "READ_FILE_CHUNKS"

        const val EXTRA_FILE_URI = "FILE_URI"
        const val EXTRA_CHUNK_SIZE = "CHUNK_SIZE"

        var fileReaderCallback: FileReaderCallback? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called. Intent action: ${intent.action}, URI: ${intent.getStringExtra(EXTRA_FILE_URI)}")

        val action = intent.action
        val fileUri = intent.getStringExtra(EXTRA_FILE_URI)

        if (fileUri == null) {
            Log.e(TAG, "File URI is missing.")
            finish()
            return
        }

        when (action) {
            ACTION_READ_FILE -> readEntireFile(fileUri)
            ACTION_READ_FILE_CHUNKS -> {
                val chunkSize = intent.getIntExtra(EXTRA_CHUNK_SIZE, 8192)
                readFileInChunks(fileUri, chunkSize)
            }
            else -> {
                Log.e(TAG, "Unknown action: $action")
                finish()
            }
        }
    }

    fun readFileContents(parentActivity: Activity, fileUri: String, callback: FileReaderCallback) {
        fileReaderCallback = callback
        val intent = Intent(parentActivity, FileReaderActivity::class.java).apply {
            action = ACTION_READ_FILE
            putExtra(EXTRA_FILE_URI, fileUri)
        }
        parentActivity.startActivity(intent)
    }

    fun readFileContentsInChunks(parentActivity: Activity, fileUri: String, chunkSize: Int, callback: FileReaderCallback) {
        fileReaderCallback = callback
        val intent = Intent(parentActivity, FileReaderActivity::class.java).apply {
            action = ACTION_READ_FILE_CHUNKS
            putExtra(EXTRA_FILE_URI, fileUri)
            putExtra(EXTRA_CHUNK_SIZE, chunkSize)
        }
        parentActivity.startActivity(intent)
    }

    private fun readEntireFile(fileUri: String?) {
        if (fileReaderCallback == null) {
            Log.e(TAG, "No callback object is currently set. Skipping reading file $fileUri")
            finish()
            return
        }
        Log.i(TAG, "Reading entire file: $fileUri")
        val inputStream = contentResolver.openInputStream(Uri.parse(fileUri))
        val fileBytes = inputStream?.use { it.readBytes() }
        Log.i(TAG, "File content size: ${fileBytes?.size} bytes")
        Log.i(TAG, "File content size: ${fileBytes?.size} bytes")
        if (fileBytes != null) {
            fileReaderCallback?.onContentRead(fileBytes)
        }
        finish()
    }

    private fun readFileInChunks(fileUri: String?, chunkSize: Int) {
        if (fileReaderCallback == null) {
            Log.e(TAG, "No callback object is set. Skipping reading file $fileUri")
            finish()
            return
        }

        if (fileUri == null) {
            Log.e(TAG, "File URI is null.")
            finish()
            return
        }

        Thread {
            try {
                val inputStream = contentResolver.openInputStream(Uri.parse(fileUri))
                if (inputStream == null) {
                    Log.e(TAG, "Failed to open InputStream for URI: $fileUri")
                    finish()
                    return@Thread
                }

                inputStream.use { stream ->
                    val buffer = ByteArray(chunkSize)
                    while (true) {
                        val bytesRead = stream.read(buffer)
                        if (bytesRead == -1) {
                            runOnUiThread {
                                fileReaderCallback?.onContentRead(ByteArray(0))
                            }
                            break;
                        }

                        val chunk = buffer.copyOf(bytesRead)

                        runOnUiThread {
                            fileReaderCallback?.onContentRead(chunk)
                        }
                    }
                }

                Log.i(TAG, "Finished reading file in chunks.")

            } catch (e: Exception) {
                Log.e(TAG, "Error reading file in chunks: $e")
            }
        }.start()
        runOnUiThread { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called. Clearing fileReaderCallback.")
        fileReaderCallback = null
    }
}
