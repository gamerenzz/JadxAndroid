package com.example.jadxandroid

import android.util.Log
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.OutputStream

class JadxEngine(private val currentFileName: String) : DecompilerEngine {

    private val TAG = "JadxEngine"

    override fun getName(): String = "JADX"

    // 广谱第三方库黑名单
    private fun isThirdPartyLibrary(className: String): Boolean {
        return className.startsWith("android.") ||
               className.startsWith("androidx.") ||
               className.startsWith("com.google.") ||
               className.startsWith("kotlin.") ||
               className.startsWith("kotlinx.") ||
               className.startsWith("org.libsdl.") ||
               className.startsWith("org.apache.") ||
               className.startsWith("org.intellij.") ||
               className.startsWith("org.jetbrains.") ||
               className.startsWith("com.squareup.") ||
               className.startsWith("io.reactivex.") ||
               className.startsWith("com.bumptech.glide.") ||
               className.startsWith("com.google.gson.") ||
               className.startsWith("com.alibaba.") ||
               className.startsWith("com.tencent.") && !className.contains("jy") // 避免误杀特定包
    }

    private fun shouldKeepClass(cls: JavaClass, filterMode: FilterMode, appPackageName: String?): Boolean {
        // JADX 中内部类（如 Outer$Inner）会被自动合并到外层主类代码中，不需要单独作为顶层类导出
        if (cls.isInner) return false

        val className = cls.fullName
        return when (filterMode) {
            FilterMode.ALL -> true
            FilterMode.FILTER_THIRDPARTY -> !isThirdPartyLibrary(className)
            FilterMode.APP_ONLY -> {
                if (!appPackageName.isNullOrEmpty()) {
                    className == appPackageName || className.startsWith("$appPackageName.")
                } else {
                    // 如果无法提取到包名（非 APK 文件），退化为过滤第三方库
                    !isThirdPartyLibrary(className)
                }
            }
        }
    }

    override suspend fun decompilePreview(file: File, filterMode: FilterMode): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            val args = JadxArgs().apply {
                inputFiles = listOf(file)
                isSkipResources = true
            }

            JadxDecompiler(args).use { decompiler ->
                decompiler.load()

                val appPackageName = decompiler.manifestData?.packageName
                val rawClasses = decompiler.classes

                val filteredClasses = rawClasses.filter { shouldKeepClass(it, filterMode, appPackageName) }

                if (filteredClasses.isEmpty()) {
                    return@withContext "未在文件中找到匹配当前过滤模式 [${filterMode.displayName}] 的类。"
                }

                sb.append("// ==========================================\n")
                sb.append("//  JADX 引擎反编译预览\n")
                if (!appPackageName.isNullOrEmpty()) {
                    sb.append("//  检测到 APK 主包名: $appPackageName\n")
                }
                sb.append("//  当前过滤模式: ${filterMode.displayName}\n")
                sb.append("//  原始类总数: ${rawClasses.size} | 过滤后保留: ${filteredClasses.size}\n")
                sb.append("// ==========================================\n\n")

                val displayLimit = minOf(filteredClasses.size, 5)
                for (i in 0 until displayLimit) {
                    val cls = filteredClasses[i]
                    sb.append("// [预览 ${i + 1}/$displayLimit] 类名: ${cls.fullName}\n")
                    sb.append(cls.code)
                    sb.append("\n\n// ==========================================\n\n")
                }
                if (filteredClasses.size > displayLimit) {
                    sb.append("// ... 其余 ${filteredClasses.size - displayLimit} 个类未完全展示，点击下方按钮导出完整 TXT ...")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sb.append("JADX 反编译预览出错:\n${e.localizedMessage}")
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
            outputStream.bufferedWriter().use { writer ->

                val createFreshDecompiler = {
                    val freshArgs = JadxArgs().apply {
                        inputFiles = listOf(file)
                        isSkipResources = true
                    }
                    JadxDecompiler(freshArgs)
                }

                var totalClassesCount = 0
                var detectedPackageName: String? = null
                val classesToDecompile = ArrayList<String>()

                // 首次加载：读取 PackageName 并筛选出目标类名列表
                createFreshDecompiler().use { decompiler ->
                    decompiler.load()
                    detectedPackageName = decompiler.manifestData?.packageName
                    val rawClasses = decompiler.classes
                    totalClassesCount = rawClasses.size

                    for (cls in rawClasses) {
                        if (shouldKeepClass(cls, filterMode, detectedPackageName)) {
                            classesToDecompile.add(cls.fullName)
                        }
                    }
                }

                writer.write("// ==========================================\n")
                writer.write("//  JADX 手机版 (JADX 引擎) 自动生成\n")
                writer.write("//  源文件: $currentFileName\n")
                if (!detectedPackageName.isNullOrEmpty()) {
                    writer.write("//  APK 主包名: $detectedPackageName\n")
                }
                writer.write("//  过滤模式: ${filterMode.displayName}\n")
                writer.write("//  原始类总数: $totalClassesCount\n")
                writer.write("//  实际导出类数: ${classesToDecompile.size}\n")
                writer.write("// ==========================================\n\n")

                var lastUpdateTime = 0L
                val runtime = Runtime.getRuntime()

                val BATCH_SIZE = 300
                var classIndex = 0
                val totalExportCount = classesToDecompile.size

                while (classIndex < totalExportCount) {
                    yield()

                    createFreshDecompiler().use { decompiler ->
                        decompiler.load()

                        val classesMap = decompiler.classes.associateBy { it.fullName }
                        val batchEnd = minOf(classIndex + BATCH_SIZE, totalExportCount)

                        for (i in classIndex until batchEnd) {
                            val clsName = classesToDecompile[i]
                            val cls = classesMap[clsName]
                            val currentCount = i + 1

                            if (cls != null) {
                                writer.write("// [$currentCount/$totalExportCount] 类名: ${cls.fullName}\n")

                                try {
                                    val code = cls.code
                                    writer.write(code)
                                } catch (e: Throwable) {
                                    Log.e(TAG, "类 ${cls.fullName} 解析发生异常: ${e.localizedMessage}")
                                    writer.write("// !!! 警告：该类反编译失败 (已自动跳过) !!!\n")
                                    writer.write("// 错误日志: ${e.localizedMessage}\n")
                                }

                                writer.write("\n\n// ------------------------------------------\n\n")
                                cls.unload()
                            }

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime > 500 || currentCount == totalExportCount) {
                                lastUpdateTime = currentTime
                                onProgress(currentCount, totalExportCount, clsName)
                            }

                            yield()
                        }
                        classIndex = batchEnd
                    }

                    System.gc()
                }
                writer.flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "导出过程中发生致命错误: ${e.localizedMessage}")
            e.printStackTrace()
            false
        }
    }
}
