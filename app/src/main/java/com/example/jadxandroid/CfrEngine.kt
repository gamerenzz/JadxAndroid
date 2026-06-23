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

    private fun shouldFilter(className: String): Boolean {
        return className.startsWith("com.google.android.gms") || 
               className.startsWith("com.google.firebase") ||    
               className.startsWith("androidx.") ||               
               className.startsWith("android.support") ||         
               className.startsWith("kotlin.") ||                 
               className.startsWith("kotlinx.")                   
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
        sb.append("// 💡 [大内密探智能依赖诊断助手]\n")
        sb.append("// --------------------------------------------------------------------------\n")
        sb.append("// 探测报告：微臣发现此代码中包含未解析的外部类。这会导致类型推导退化或部分合并失效。\n")
        
        if (missingClasses.isNotEmpty()) {
            sb.append("// 🔍 检查到当前缺失的第三方核心包/类（请在依赖包中查找包含以下类的 JAR 并放入手机）：\n")
            // 循环遍历，全量列出所有缺失的外部类
            for (missingCls in missingClasses) {
                sb.append("//    📌 $missingCls\n")
            }
        }
        
        sb.append("// 📁 依赖存放目录：$path\n")
        sb.append("// 💡 放入后重新解析，CFR 引擎将自动读取并进行高精度类型推导。\n")
        sb.append("// --------------------------------------------------------------------------\n")
        return sb.toString()
    }

    override suspend fun decompilePreview(file: File, filterSdk: Boolean): String = withContext(Dispatchers.IO) {
        if (isApkOrDex(file)) {
            return@withContext "CFR 引擎仅支持标准 Java .class 或 .jar/.zip 文件。\n安卓 .apk/.dex 请切换至 JADX 引擎解析。"
        }

        val sb = StringBuilder()
        try {
            val ext = file.name.substringAfterLast(".").lowercase()
            if (ext == "class") {
                val code = decompileSingleClass(file, null)
                
                // 界面内显示（In-memory）不受编码探测干扰，依然保留在顶部，方便用户第一眼看到
                val missingClasses = extractMissingClasses(code)
                if (missingClasses.isNotEmpty()) {
                    sb.append(getDiagnosisBanner(missingClasses))
                    sb.append("\n")
                }
                
                sb.append("// 成功解析 1 个类\n\n")
                sb.append(code)
            } else { // jar 或 zip
                openZipFile(file).use { zip ->
                    val entries = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .filter { !it.name.contains("$") }
                        .filter { !filterSdk || !shouldFilter(it.name.replace('/', '.')) }
                        .toList()

                    if (entries.isEmpty()) {
                        return@withContext "未在文件中找到可解析的类（可能均被 SDK 过滤器过滤）。"
                    }

                    sb.append("// 成功解析，共找到 ${entries.size} 个类\n\n")
                    
                    // 同样，界面预览区我们在最顶部插入诊断助手
                    val tempFirstFile = File(context.cacheDir, "TempFirstPreview.class")
                    if (tempFirstFile.exists()) tempFirstFile.delete()
                    zip.getInputStream(entries[0]).use { input ->
                        FileOutputStream(tempFirstFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    val checkCode = decompileSingleClass(tempFirstFile, file)
                    tempFirstFile.delete()
                    val missingClasses = extractMissingClasses(checkCode)
                    if (missingClasses.isNotEmpty()) {
                        sb.append(getDiagnosisBanner(missingClasses))
                        sb.append("\n")
                    }

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
        try {
            // 使用纯净的、无 BOM 的标准 UTF-8 进行文件流写入
            outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
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
                    val code = decompileSingleClass(file, null)
                    writer.write(code)
                    
                    // 核心优化 1：针对导出的单类文本，将“诊断报告”移写入到文件最末尾（防止文件头部中文过多导致编辑器误判编码）
                    val missingClasses = extractMissingClasses(code)
                    if (missingClasses.isNotEmpty()) {
                        writer.write("\n\n")
                        writer.write(getDiagnosisBanner(missingClasses))
                    }
                    
                    writer.flush()
                } else { // jar 或 zip
                    openZipFile(file).use { zip ->
                        val entries = zip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.endsWith(".class") }
                            .filter { !it.name.contains("$") }
                            .filter { !filterSdk || !shouldFilter(it.name.replace('/', '.')) }
                            .toList()

                        writer.write("// ==========================================\n")
                        writer.write("//  JADX 手机版 (CFR 引擎) 自动生成\n")
                        writer.write("//  源文件: $currentFileName\n")
                        writer.write("//  实际导出类数: ${entries.size}\n")
                        writer.write("// ==========================================\n\n")

                        val total = entries.size
                        var lastUpdateTime = 0L
                        
                        // 准备一个列表，收集整个导出过程中所有类缺失的依赖（进行去重汇总）
                        val allMissingClasses = ArrayList<String>()

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
                                
                                // 收集缺失依赖
                                val missing = extractMissingClasses(code)
                                if (missing.isNotEmpty()) {
                                    allMissingClasses.addAll(missing)
                                }
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
                        
                        // 核心优化 2：对于导出的整包文件，去重后将完整的诊断报告作为“Decompilation Summary”写入到文件最底部
                        if (allMissingClasses.isNotEmpty()) {
                            val uniqueMissing = allMissingClasses.distinct()
                            writer.write("\n")
                            writer.write(getDiagnosisBanner(uniqueMissing))
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
                            if (!obj.startsWith("Analysing type")) {
                                sb.append(obj)
                            }
                        } else {
                            try {
                                val method = obj.javaClass.getMethod("getJava")
                                val javaCode = method.invoke(obj) as? String
                                if (javaCode != null) {
                                    sb.append(javaCode)
                                }
                            } catch (e: Exception) {
                                // 忽略
                            }
                        }
                    }
                }
            }
        }

        val options = HashMap<String, String>()
        options["showversion"] = "false" 
        
        val cpBuilder = StringBuilder()
        
        if (classpathFile != null && classpathFile.exists()) {
            cpBuilder.append(classpathFile.absolutePath)
        }
        
        val localLibs = libsDir?.listFiles { file -> 
            file.isFile && file.name.endsWith(".jar", ignoreCase = true) 
        }
        if (!localLibs.isNullOrEmpty()) {
            for (jar in localLibs) {
                if (cpBuilder.isNotEmpty()) {
                    cpBuilder.append(":") 
                }
                cpBuilder.append(jar.absolutePath)
            }
        }

        if (cpBuilder.isNotEmpty()) {
            options["extraclasspath"] = cpBuilder.toString()
            Log.d(TAG, "已成功挂载 ClassPath: ${cpBuilder.toString()}")
        }

        val driver = CfrDriver.Builder()
            .withOptions(options)
            .withOutputSink(sinkFactory)
            .build()

        driver.analyse(listOf(classFile.absolutePath))
        return sb.toString()
    }
}
