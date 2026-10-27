package com.emu.android

/**
 * Receives the outcome of one [FilePickerBridge] launch. Calls arrive on an Android thread, not on
 * Unity's main thread.
 *
 * A pick reports each document with [onFilePicked] or [onFileFailed], then ends with [onFinished].
 * A launch that picks nothing ends with [onCancelled] or [onFailed] instead. Exactly one of
 * [onFinished], [onCancelled] and [onFailed] is called per launch.
 */
interface FilePickerCallback {
    /** One picked document was copied to [path]. */
    fun onFilePicked(path: String)

    /** One picked document could not be copied. */
    fun onFileFailed(message: String)

    /** Every picked document has been reported. */
    fun onFinished()

    /** The user closed the picker without choosing anything. */
    fun onCancelled()

    /** The picker could not be shown, or the picked folder has no PDFs. */
    fun onFailed(message: String)
}
