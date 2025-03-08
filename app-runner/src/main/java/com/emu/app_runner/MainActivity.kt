package com.emu.app_runner

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupActionBarWithNavController
import com.emu.emustorebridge.R
import com.emu.android.FilePickerActivity
import com.emu.android.FilePickerCallback
import com.emu.android.FileReaderCallback
import com.emu.android.FileReaderActivity

class MainActivity : AppCompatActivity(), FilePickerCallback, FileReaderCallback {

    companion object {
        private const val TAG = "MainActivity"
        private const val FILE_READER_REQUEST_CODE = 50
        private val filePickerActivity = FilePickerActivity();
        private val fileReaderActivity = FileReaderActivity();

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragmentContainer) as NavHostFragment
        val navController = navHostFragment.navController
        setupActionBarWithNavController(navController)


        filePickerActivity.openFilePicker(this, this)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragmentContainer) as NavHostFragment
        return navHostFragment.navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun onFilePicked(metadata: Map<String, String>) {
//        Log.i(TAG, "onFilePicked - ${uri}")
//        if (uri != null) {
//            fileReaderActivity.readFileContents(this, uri, this)
//        }
    }

    override fun onContentRead(content: ByteArray) {
        TODO("Not yet implemented")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            FILE_READER_REQUEST_CODE -> {
                Log.i(TAG, "FILE READER ACTIVITY RESULT CALLED")
            }
        }
    }
}