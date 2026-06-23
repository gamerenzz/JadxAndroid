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
    private var libsDir: File? = null

    init {
        // 核心初始化：自动创建免权限依赖库文件夹 "libs"
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

    // 智能依赖诊断：判断反编译出来的代码是否包含“缺失依赖”的特征注释
    private fun checkDependencyMissing(code: String): Boolean {
        return code.contains("Could not load the following classes:")
    }

    // 生成智能诊断提示横幅
    private fun getDiagnosisBanner(): String {
        val path = libsDir?.absolutePath ?: "内部存储/Android/data/com.example.jadxandroid/files/libs"
        return """
            // 💡 [大内密探智能依赖诊断助手]
            // --------------------------------------------------------------------------
            // 探测报告：微臣发现此代码中包含未解析的外部类。这会导致类型推导退化或部分合并失效。
            // 增强建议：您可以将此项目依赖的 SDK 或是公共类库（.jar / .class 文件）放入手机以下目录：
            // 📁 $path
            // 放入后重新反编译，CFR 引擎将自动读取并进行高精度类型推导。
            // --------------------------------------------------------------------------
            
        """.trimIndent()
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
                
                // 如果检测到缺失依赖，在预览顶部插入智能诊断小助手
                if (checkDependencyMissing(code)) {
                    sb.append(getDiagnosisBanner())
                }
                
                sb.append("// 成功解析 1 个类\n\n")
                sb.append(code)
            } else { // jar 或 zip
                ZipFile(file).use { zip ->
                    val entries = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .filter { !it.name.contains("$") }
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

                        val code = decompileSingleClass(tempClassFile, file)
                        tempClassFile.delete()

                        // 预览的第一个类如果缺失依赖，也打印诊断助手
                        if (i == 0 && checkDependencyMissing(code)) {
                            sb.append(getDiagnosisBanner())
                        }

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
                    
                    val code = decompileSingleClass(file, null)
                    if (checkDependencyMissing(code)) {
                        writer.write(getDiagnosisBanner())
                    }
                    
                    writer.write("// ==========================================\n")
                    writer.write("//  JADX 手机版 (CFR 引擎) 自动生成\n")
                    writer.write("//  源文件: $currentFileName\n")
                    writer.write("//  类总数: 1\n")
                    writer.write("// ==========================================\n\n")
                    onProgress(1, 1, className)
                    
                    writer.write("// 类名: $className\n")
                    writer.write(code)
                    writer.flush()
                } else { // jar 或 zip
                    ZipFile(file).use { zip ->
                        val entries = zip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.endsWith(".class") }
                            .filter { !it.name.contains("$") }
                            .filter { !filterSdk || !shouldFilter(it.name.replace('/', '.')) }
                            .toList()

                        // 如果导出的第一个类检测到依赖缺失，在导出的 TXT 最顶部也附赠一份智能诊断说明
                        if (entries.isNotEmpty()) {
                            val firstEntry = entries[0]
                            val tempClassFile = File(context.cacheDir, "TempFirst.class")
                            if (tempClassFile.exists()) tempClassFile.delete()
                            zip.getInputStream(firstEntry).use { input ->
                                FileOutputStream(tempClassFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            val checkCode = decompileSingleClass(tempClassFile, file)
                            tempClassFile.delete()
                            if (checkDependencyMissing(checkCode)) {
                                writer.write(getDiagnosisBanner())
                            }
                        }

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

                                val code = decompileSingleClass(tempClassFile, file)
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
        
        // 核心增强：构建并注入动态类路径（ClassPath）
        val cpBuilder = StringBuilder()
        
        // A. 首先挂载当前正在反编译的 ZIP/JAR
        if (classpathFile != null && classpathFile.exists()) {
            cpBuilder.append(classpathFile.absolutePath)
        }
        
        // B. 自动扫描 libs 文件夹下的所有第三方依赖 .jar 包并进行拼装挂载
        val localLibs = libsDir?.listFiles { file -> 
            file.isFile && file.name.endsWith(".jar", ignoreCase = true) 
        }
        if (!localLibs.isNullOrEmpty()) {
            for (jar in localLibs) {
                if (cpBuilder.isNotEmpty()) {
                    cpBuilder.append(":") // 安卓底层（Linux核心）使用冒号作类路径分隔符
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
