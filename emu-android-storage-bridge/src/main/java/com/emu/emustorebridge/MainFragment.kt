package com.emu.emustorebridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.emu.emustorebridge.databinding.FragmentMainBinding

class MainFragment : Fragment() {

    private var TAG = "MainFragment"
    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val FILE_PICKER_REQUEST_CODE = 42
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Navigate to FilePickerFragment
//        binding.btnFilePicker.setOnClickListener {
//            findNavController().navigate(R.id.action_mainFragment_to_filePickerFragment)
//        }

        binding.btnFilePicker.setOnClickListener {
            // Start FilePickerActivity
            Log.i(TAG, "starting FilePickerActivity")
            val intent = Intent(requireContext(), FilePickerActivity::class.java)
            startActivityForResult(intent, FILE_PICKER_REQUEST_CODE)
        }

        // Navigate to GoogleDriveFragment
        binding.btnGoogleDrive.setOnClickListener {
            findNavController().navigate(R.id.action_mainFragment_to_googleDriveFragment)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val fileUri = data?.data
            Log.i("MainFragment", "File picked: ${fileUri?.toString()}")
        } else {
            Log.i("MainFragment", "File picker canceled")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
