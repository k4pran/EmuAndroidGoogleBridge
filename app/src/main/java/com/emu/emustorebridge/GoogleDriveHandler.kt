package com.emu.emustorebridge

import android.util.Log

class GoogleDriveHandler {

    object EmuBridge {
        // This is the function we'll call from Unity's C# code
        @JvmStatic
        fun helloWorld() {
            Log.d("EmuBridge", "Hello from Kotlin!")
        }
    }
}