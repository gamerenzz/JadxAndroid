package com.example.jadxandroid

import java.io.File
import java.io.OutputStream

interface DecompilerEngine {
    // 获取当前反编译器的名称
    fun getName(): String

    // 针对单个类（或 JAR/ZIP 中前 5 个类）的反编译预览
    suspend fun decompilePreview(file: File, filterSdk: Boolean): String

    // 将全部类分批、流式写入到输出流中，并支持进度和防抖回调
    suspend fun decompileAll(
        file: File,
        outputStream: OutputStream,
        filterSdk: Boolean,
        onProgress: suspend (current: Int, total: Int, className: String) -> Unit
    ): Boolean
}
