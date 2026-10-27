package com.emu.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * The Google Drive app, if it is installed. Drive hands its files to other apps in two ways: its own
 * picker, for ACTION_GET_CONTENT, and a documents provider that the system picker lists as a root.
 * Horizon OS has neither, so on Quest [pickIntent] is null and Unity imports through the browser.
 */
internal object GoogleDriveApp {
    private const val PACKAGE = "com.google.android.apps.docs"
    private const val DOCUMENTS_AUTHORITY = "com.google.android.apps.docs.storage"

    fun isInstalled(context: Context, mimeType: String): Boolean = pickIntent(context, mimeType) != null

    /** The intent that lets the user pick a [mimeType] document from Drive, or null without Drive. */
    fun pickIntent(context: Context, mimeType: String): Intent? {
        val drivePicker = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
            .setPackage(PACKAGE)
        if (drivePicker.resolveActivity(context.packageManager) != null) {
            return drivePicker
        }

        if (hasDocumentsProvider(context.packageManager)) {
            // Drive has no picker of its own here, but the system picker lists it as a root.
            return Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeType)
        }
        return null
    }

    private fun hasDocumentsProvider(packageManager: PackageManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveContentProvider(DOCUMENTS_AUTHORITY, PackageManager.ComponentInfoFlags.of(0)) != null
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveContentProvider(DOCUMENTS_AUTHORITY, 0) != null
        }
}
