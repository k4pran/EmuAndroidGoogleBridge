package com.emu.android

interface FileReaderCallback {
    fun onContentRead(content: ByteArray)
}