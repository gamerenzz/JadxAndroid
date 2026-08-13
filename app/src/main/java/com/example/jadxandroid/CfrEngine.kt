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

    private fun openZipFile(file: File): ZipFile {
        return try {
            ZipFile(file, Charset.forName("GBK"))
        } catch (e: Exception) {
            ZipFile(file, Charset.forName("UTF-8"))
        }
    }

    /**
     * 针对 ZIP/JAR 文件推断其 Java 业务代码包集合
     */
    private fun inferAppCodeSetFromZip(entries: List<String>): Set<String> {
        val classNames = entries.map { it.replace('/', '.').substringBeforeLast(".class") }
        val candidatePkgs = classNames
            .filter { !FilterHelper.isResourceClass(it) && !FilterHelper.isThirdPartyLibrary(it) }
            .mapNotNull { if (it.contains(".")) it.substringBeforeLast(".") else null }
            .toSet()

        if (candidatePkgs.isEmpty()) return emptySet()

        val sorted = candidatePkgs.sortedBy { it.length }
        val roots = HashSet<String>()
        for (pkg in sorted) {
            if (roots.none { root -> pkg == root || pkg.startsWith("$root.") }) {
                roots.add(pkg)
            }
        }
        return roots
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
                sb.append("// 成功解析 1 个类\n\n")
                sb.append(code)
            } else {
                openZipFile(file).use { zip ->
                    val allEntryNames = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .map { it.name }
                        .toList()

                    val appCodeSet = inferAppCodeSetFromZip(allEntryNames)

                    val entries = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .filter { entry ->
                            val className = entry.name.replace('/', '.').substringBeforeLast(".class")
                            FilterHelper.shouldKeepClass(className, filterMode, appCodeSet)
                        }
                        .toList()

                    if (entries.isEmpty()) {
                        return@withContext "未在文件中找到符合当前过滤规则的类。"
                    }

                    sb.append("// 成功解析，保留 ${entries.size} 个类（模式: ${filterMode.displayName}）\n\n")
                    
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
                        val allEntryNames = zip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.endsWith(".class") }
                            .map { it.name }
                            .toList()

                        val appCodeSet = inferAppCodeSetFromZip(allEntryNames)

                        val entries = zip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.endsWith(".class") }
                            .filter { entry ->
                                val className = entry.name.replace('/', '.').substringBeforeLast(".class")
                                FilterHelper.shouldKeepClass(className, filterMode, appCodeSet)
                            }
                            .toList()

                        writer.write("// ==========================================\n")
                        writer.write("//  JADX 手机版 (CFR 引擎) 自动生成\n")
                        writer.write("//  源文件: $currentFileName\n")
                        if (appCodeSet.isNotEmpty()) {
                            writer.write("//  识别应用业务根包: $appCodeSet\n")
                        }
                        writer.write("//  过滤模式: ${filterMode.displayName}\n")
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
