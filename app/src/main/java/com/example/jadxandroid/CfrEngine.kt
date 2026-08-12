package com.example.jadxandroid

import android.content.Context
import android.util.Log
import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.OutputSinkFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.ZipFile

class CfrEngine(
    private val context: Context,
    private val currentFileName: String
) : DecompilerEngine {

    private val TAG = "CfrEngine"
    private var libsDir: File? = null

    init {
        try {
            libsDir = context.getExternalFilesDir("libs")
            if (libsDir != null && !libsDir!!.exists()) {
                libsDir!!.mkdirs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getName(): String = "CFR"

    private fun isApkOrDex(file: File): Boolean {
        val ext = file.name.substringAfterLast(".").lowercase()
        return ext == "apk" || ext == "dex"
    }

    private fun isThirdPartyLibrary(className: String): Boolean {
        return className.startsWith("android.") ||
               className.startsWith("androidx.") ||
               className.startsWith("com.google.") ||
               className.startsWith("kotlin.") ||
               className.startsWith("kotlinx.") ||
               className.startsWith("org.libsdl.") ||
               className.startsWith("org.apache.") ||
               className.startsWith("com.squareup.")
    }

    private fun shouldKeepClass(className: String, filterMode: FilterMode): Boolean {
        // CFR 操作单个 .class 时，过滤掉匿名内部类文件能防止重复导出
        if (className.contains("$")) return false

        return when (filterMode) {
            FilterMode.ALL -> true
            FilterMode.FILTER_THIRDPARTY, FilterMode.APP_ONLY -> !isThirdPartyLibrary(className)
        }
    }

    private fun openZipFile(file: File): ZipFile {
        return try {
            ZipFile(file, Charset.forName("GBK"))
        } catch (e: Exception) {
            ZipFile(file, Charset.forName("UTF-8"))
        }
    }

    private fun extractMissingClasses(code: String): List<String> {
        val list = ArrayList<String>()
        val marker = "Could not load the following classes:"
        val index = code.indexOf(marker)
        if (index != -1) {
            val start = index + marker.length
            val end = code.indexOf("*/", start)
            if (end != -1) {
                val linesSection = code.substring(start, end)
                linesSection.lines().forEach { line ->
                    val trimmed = line.trim().trim('*').trim()
                    if (trimmed.isNotEmpty() && 
                        !trimmed.startsWith("java.") && 
                        !trimmed.startsWith("javax.") && 
                        !trimmed.contains("Could not load") &&
                        !trimmed.contains("Decompiled with")
                    ) {
                        list.add(trimmed)
                    }
                }
            }
        }
        return list
    }

    private fun getDiagnosisBanner(missingClasses: List<String>): String {
        val path = libsDir?.absolutePath ?: "内部存储/Android/data/com.example.jadxandroid/files/libs"
        val sb = StringBuilder()
        sb.append("// 💡 [智能依赖诊断助手]\n")
        sb.append("// --------------------------------------------------------------------------\n")
        sb.append("// 探测报告：微臣发现此代码中包含未解析的外部类。\n")
        if (missingClasses.isNotEmpty()) {
            sb.append("// 🔍 检查到缺失的包/类：\n")
            for (missingCls in missingClasses) {
                sb.append("//    📌 $missingCls\n")
            }
        }
        sb.append("// 📁 依赖存放目录：$path\n")
        sb.append("// --------------------------------------------------------------------------\n")
        return sb.toString()
    }

    override suspend fun decompilePreview(file: File, filterMode: FilterMode): String = withContext(Dispatchers.IO) {
        if (isApkOrDex(file)) {
            return@withContext "CFR 引擎仅支持标准 Java .class 或 .jar/.zip 文件。\n安卓 .apk/.dex 请切换至 JADX 引擎解析。"
        }

        val sb = StringBuilder()
        try {
            val ext = file.name.substringAfterLast(".").lowercase()
            if (ext == "class") {
                val code = decompileSingleClass(file, null)
                val missingClasses = extractMissingClasses(code)
                if (missingClasses.isNotEmpty()) {
                    sb.append(getDiagnosisBanner(missingClasses))
                    sb.append("\n")
                }
                sb.append("// 成功解析 1 个类\n\n")
                sb.append(code)
            } else {
                openZipFile(file).use { zip ->
                    val entries = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .filter { shouldKeepClass(it.name.replace('/', '.').substringBeforeLast(".class"), filterMode) }
                        .toList()

                    if (entries.isEmpty()) {
                        return@withContext "未在文件中找到可解析的类（可能均被过滤器过滤）。"
                    }

                    sb.append("// 成功解析，共找到 ${entries.size} 个类（当前模式: ${filterMode.displayName}）\n\n")
                    
                    val displayLimit = minOf(entries.size, 5)
                    for (i in 0 until displayLimit) {
                        val entry = entries[i]
                        val tempClassFile = File(context.cacheDir, "TempDecompile.class")
                        if (tempClassFile.exists()) tempClassFile.delete()

                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(tempClassFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        val code = decompileSingleClass(tempClassFile, file)
                        tempClassFile.delete()

                        sb.append("// 类名: ${entry.name.replace('/', '.').substringBeforeLast(".class")}\n")
                        sb.append(code)
                        sb.append("\n\n// ==========================================\n\n")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sb.append("CFR 反编译预览出错:\n${e.localizedMessage}")
        }
        sb.toString()
    }

    override suspend fun decompileAll(
        file: File,
        outputStream: OutputStream,
        filterMode: FilterMode,
        onProgress: suspend (current: Int, total: Int, className: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                val ext = file.name.substringAfterLast(".").lowercase()
                if (ext == "class") {
                    val className = file.name.substringBeforeLast(".class")
                    writer.write("// 类名: $className\n")
                    val code = decompileSingleClass(file, null)
                    writer.write(code)
                    writer.flush()
                } else {
                    openZipFile(file).use { zip ->
                        val entries = zip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.endsWith(".class") }
                            .filter { shouldKeepClass(it.name.replace('/', '.').substringBeforeLast(".class"), filterMode) }
                            .toList()

                        writer.write("// ==========================================\n")
                        writer.write("//  JADX 手机版 (CFR 引擎) 自动生成\n")
                        writer.write("//  源文件: $currentFileName\n")
                        writer.write("//  模式: ${filterMode.displayName}\n")
                        writer.write("//  实际导出类数: ${entries.size}\n")
                        writer.write("// ==========================================\n\n")

                        val total = entries.size
                        var lastUpdateTime = 0L

                        entries.forEachIndexed { index, entry ->
                            yield()
                            val className = entry.name.replace('/', '.').substringBeforeLast(".class")
                            writer.write("// [类 ${index + 1}/$total] 类名: $className\n")

                            try {
                                val tempClassFile = File(context.cacheDir, "TempDecompile.class")
                                if (tempClassFile.exists()) tempClassFile.delete()

                                zip.getInputStream(entry).use { input ->
                                    FileOutputStream(tempClassFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }

                                val code = decompileSingleClass(tempClassFile, file)
                                tempClassFile.delete()

                                writer.write(code)
                            } catch (e: Throwable) {
                                writer.write("// !!! 警告：该类反编译失败 !!!\n")
                            }

                            writer.write("\n\n// ------------------------------------------\n\n")

                            val currentCount = index + 1
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime > 500 || currentCount == total) {
                                lastUpdateTime = currentTime
                                onProgress(currentCount, total, className)
                            }
                        }
                        writer.flush()
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun decompileSingleClass(classFile: File, classpathFile: File?): String {
        val sb = StringBuilder()
        val sinkFactory = object : OutputSinkFactory {
            override fun getSupportedSinks(
                sinkType: OutputSinkFactory.SinkType?, 
                available: MutableCollection<OutputSinkFactory.SinkClass>?
            ): List<OutputSinkFactory.SinkClass>? {
                return listOf(OutputSinkFactory.SinkClass.DECOMPILED)
            }

            override fun <T> getSink(
                sinkType: OutputSinkFactory.SinkType?, 
                sinkClass: OutputSinkFactory.SinkClass?
            ): OutputSinkFactory.Sink<T>? {
                return OutputSinkFactory.Sink { obj ->
                    if (obj != null) {
                        if (obj is String) {
                            if (!obj.startsWith("Analysing type")) sb.append(obj)
                        } else {
                            try {
                                val method = obj.javaClass.getMethod("getJava")
                                val javaCode = method.invoke(obj) as? String
                                if (javaCode != null) sb.append(javaCode)
                            } catch (e: Exception) {}
                        }
                    }
                }
            }
        }

        val options = HashMap<String, String>()
        options["showversion"] = "false"
        
        val driver = CfrDriver.Builder()
            .withOptions(options)
            .withOutputSink(sinkFactory)
            .build()

        driver.analyse(listOf(classFile.absolutePath))
        return sb.toString()
    }
}
