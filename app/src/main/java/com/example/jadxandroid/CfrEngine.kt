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
import java.util.zip.ZipFile

class CfrEngine(
    private val context: Context,
    private val currentFileName: String
) : DecompilerEngine {

    private val TAG = "CfrEngine"

    override fun getName(): String = "CFR"

    private fun isApkOrDex(file: File): Boolean {
        val ext = file.name.substringAfterLast(".").lowercase()
        return ext == "apk" || ext == "dex"
    }

    private fun shouldFilter(className: String): Boolean {
        return className.startsWith("com.google.android.gms") || 
               className.startsWith("com.google.firebase") ||    
               className.startsWith("androidx.") ||               
               className.startsWith("android.support") ||         
               className.startsWith("kotlin.") ||                 
               className.startsWith("kotlinx.")                   
    }

    override suspend fun decompilePreview(file: File, filterSdk: Boolean): String = withContext(Dispatchers.IO) {
        if (isApkOrDex(file)) {
            return@withContext "CFR 引擎仅支持标准 Java .class 或 .jar/.zip 文件。\n安卓 .apk/.dex 请切换至 JADX 引擎解析。"
        }

        val sb = StringBuilder()
        try {
            val ext = file.name.substringAfterLast(".").lowercase()
            if (ext == "class") {
                val code = decompileSingleClass(file)
                sb.append("// 成功解析 1 个类\n\n")
                sb.append(code)
            } else { // jar 或 zip
                ZipFile(file).use { zip ->
                    val entries = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .filter { !filterSdk || !shouldFilter(it.name.replace('/', '.')) }
                        .toList()

                    if (entries.isEmpty()) {
                        return@withContext "未在文件中找到可解析的类（可能均被 SDK 过滤器过滤）。"
                    }

                    sb.append("// 成功解析，共找到 ${entries.size} 个类\n\n")
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

                        val code = decompileSingleClass(tempClassFile)
                        tempClassFile.delete()

                        sb.append("// 类名: ${entry.name.replace('/', '.').substringBeforeLast(".class")}\n")
                        sb.append(code)
                        sb.append("\n\n// ==========================================\n\n")
                    }
                    if (entries.size > displayLimit) {
                        sb.append("// ... 其余 ${entries.size - displayLimit} 个类未完全展示 ...")
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
        filterSdk: Boolean,
        onProgress: suspend (current: Int, total: Int, className: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (isApkOrDex(file)) {
            return@withContext false
        }

        try {
            outputStream.bufferedWriter().use { writer ->
                val ext = file.name.substringAfterLast(".").lowercase()
                if (ext == "class") {
                    val className = file.name.substringBeforeLast(".class")
                    writer.write("// ==========================================\n")
                    writer.write("//  JADX 手机版 (CFR 引擎) 自动生成\n")
                    writer.write("//  源文件: $currentFileName\n")
                    writer.write("//  类总数: 1\n")
                    writer.write("// ==========================================\n\n")
                    onProgress(1, 1, className)
                    
                    writer.write("// 类名: $className\n")
                    writer.write(decompileSingleClass(file))
                    writer.flush()
                } else { // jar 或 zip
                    ZipFile(file).use { zip ->
                        val entries = zip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.endsWith(".class") }
                            .filter { !filterSdk || !shouldFilter(it.name.replace('/', '.')) }
                            .toList()

                        writer.write("// ==========================================\n")
                        writer.write("//  JADX 手机版 (CFR 引擎) 自动生成\n")
                        writer.write("//  源文件: $currentFileName\n")
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

                                val code = decompileSingleClass(tempClassFile)
                                tempClassFile.delete()

                                writer.write(code)
                            } catch (e: Throwable) {
                                Log.e(TAG, "CFR 解析类 $className 发生异常: ${e.localizedMessage}")
                                writer.write("// !!! 警告：该类反编译失败 !!!\n")
                                writer.write("// 错误异常: ${e.javaClass.simpleName} - ${e.localizedMessage}\n")
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

    // 调用 CFR 原生 API 进行纯内存形式的快速单类反编译
    private fun decompileSingleClass(classFile: File): String {
        val sb = StringBuilder()
        val sinkFactory = object : OutputSinkFactory {
            override fun getListTypes(): List<OutputSinkFactory.SinkClass> {
                return listOf(OutputSinkFactory.SinkClass.DECOMPILED)
            }

            override fun <T> getSink(sinkClass: OutputSinkFactory.SinkClass, sinkType: OutputSinkFactory.SinkType): OutputSinkFactory.Sink<T> {
                return OutputSinkFactory.Sink { obj ->
                    sb.append(obj)
                }
            }
        }

        val options = HashMap<String, String>()
        options["showversion"] = "false" // 移除 CFR 版本号输出，让排版更干净
        val driver = CfrDriver.Builder()
            .withOptions(options)
            .withOutputSink(sinkFactory)
            .build()

        driver.analyse(listOf(classFile.absolutePath))
        return sb.toString()
    }
}
