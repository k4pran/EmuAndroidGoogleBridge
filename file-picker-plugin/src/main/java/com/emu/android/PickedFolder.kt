package com.emu.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** Finds the PDFs in a folder picked with ACTION_OPEN_DOCUMENT_TREE. */
internal object PickedFolder {
    /** Deep enough for Author/Series/Book layouts with room to spare, shallow enough to stop a cycle. */
    private const val MAX_DEPTH = 8

    private val CHILD_COLUMNS = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE
    )

    /**
     * A PDF in the picked folder. [folders] are the names of the subfolders it is in, outermost first,
     * and empty for a PDF in the picked folder itself.
     */
    class Pdf(val uri: Uri, val displayName: String, val folders: List<String>, val size: Long?)

    fun findPdfs(resolver: ContentResolver, tree: Uri): List<Pdf> {
        val pdfs = mutableListOf<Pdf>()
        val visited = HashSet<String>()

        fun walk(documentId: String, folders: List<String>) {
            if (folders.size > MAX_DEPTH || !visited.add(documentId)) {
                return
            }

            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val subfolders = mutableListOf<Pair<String, String>>()
            resolver.query(children, CHILD_COLUMNS, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: ""
                    val mimeType = cursor.getString(2)
                    if (mimeType == Document.MIME_TYPE_DIR) {
                        subfolders += id to name
                    } else if (mimeType == FilePickerBridge.PDF_MIME_TYPE || name.endsWith(".pdf", ignoreCase = true)) {
                        val size = if (cursor.isNull(3)) null else cursor.getLong(3)
                        pdfs += Pdf(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, folders, size)
                    }
                }
            }

            // After the cursor is closed, so only one is open at a time however deep the folder goes.
            for ((id, name) in subfolders) {
                walk(id, folders + name)
            }
        }

        walk(DocumentsContract.getTreeDocumentId(tree), emptyList())
        return pdfs
    }
}
