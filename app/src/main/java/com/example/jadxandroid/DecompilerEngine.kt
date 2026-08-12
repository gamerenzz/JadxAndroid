package com.example.jadxandroid

import java.io.File
import java.io.OutputStream

interface DecompilerEngine {
    fun getName(): String

    suspend fun decompilePreview(file: File, filterMode: FilterMode): String

    suspend fun decompileAll(
        file: File,
        outputStream: OutputStream,
        filterMode: FilterMode,
        onProgress: suspend (current: Int, total: Int, className: String) -> Unit
    ): Boolean
}
