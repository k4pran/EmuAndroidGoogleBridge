package com.emu.android

interface FilePickerCallback {
    fun onFilePicked(metadata: Map<String, String>);
}