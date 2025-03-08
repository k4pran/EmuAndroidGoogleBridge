package com.emu.android

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.InputStream

class PdfMetaExtractor {

    companion object {
        @JvmStatic
        fun extractMetadata(uri: String, pdfStream: InputStream): Map<String, String> {
            Log.i("PdfMetaExtractor", "Loading pdf with pdfbox from $uri")
            PDDocument.load(pdfStream).use { document ->
                val info = document.documentInformation

                Log.i("PdfMetaExtractor", "loaded pdf document with pdfbox - $info")

                return mapOf(
                    "title" to (info.title ?: ""),
                    "author" to (info.author ?: ""),
                    "subject" to (info.subject ?: ""),
                    "keywords" to (info.keywords ?: ""),
                    "creationDate" to (info.creationDate?.time.toString()), // Convert Date to String
                    "modificationDate" to (info.modificationDate?.time.toString()),
                    "uri" to (uri)
                )
            }
        }
    }
}