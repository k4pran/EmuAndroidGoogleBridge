package com.emu.android

/**
 * Receives the outcome of [FilePickerBridge.launch]. Exactly one method is called per launch.
 * Calls arrive on an Android thread, not on Unity's main thread.
 */
interface FilePickerCallback {
    /** The picked document was copied to [path]. */
    fun onFilePicked(path: String)

    /** The user closed the picker without choosing a document. */
    fun onCancelled()

    /** The picker could not be shown, or the picked document could not be copied. */
    fun onFailed(message: String)
}
