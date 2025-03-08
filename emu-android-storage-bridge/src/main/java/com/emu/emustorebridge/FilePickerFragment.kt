package com.emu.emustorebridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import com.emu.emustorebridge.databinding.FragmentFilePickerBinding

class FilePickerFragment : Fragment() {

    private val TAG = "FilePickerActivity"
    private var _binding: FragmentFilePickerBinding? = null
    private val binding get() = _binding!!
    private val FILE_PICKER_REQUEST_CODE = 1
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private var fileUri: Uri? = null // todo ensure this doesn't linger across requests


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.i(TAG, "FilePicker created")
        _binding = FragmentFilePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPickFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            startActivityForResult(intent, FILE_PICKER_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val uri: Uri? = data?.data
            uri?.let {
                binding.tvSelectedFile.text = "Selected File: $it"
            }
        }
    }

    fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        filePickerLauncher.launch(intent)
    }

    fun openFilePickerFromUnity() {
        openFilePicker()
    }

    fun getFileContent(): ByteArray? {
        fileUri?.let { uri ->
            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                return inputStream.readBytes()
            }
        }
        return null
    }

    // Function to get the file name from the URI
    private fun getFileName(uri: Uri): String {
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex != -1) {
                return cursor.getString(nameIndex)
            }
        }
        return "Unknown file"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
