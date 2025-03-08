package com.emu.android

import java.util.Date

data class PdfMetaData(
    val title: String,
    val author: String,
    val subject: String,
    val keywords: String,
    val creationDate: Date,
    val modificationDate: Date
)
