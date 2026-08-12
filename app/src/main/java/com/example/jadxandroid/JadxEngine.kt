package com.example.jadxandroid

import android.content.Context
import android.util.Log
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.OutputStream

class JadxEngine(
    private val context: Context,
    private val currentFileName: String
) : DecompilerEngine {

    private val TAG = "JadxEngine"

    override fun getName(): String = "JADX"

    private fun getPackageName(fullName: String): String {
        return if (fullName.contains(".")) fullName.substringBeforeLast(".") else ""
    }

    /**
     * 获取应用主包名 (双重保险机制)：
     * 1. 优先调用 Android 系统原生 API 解析 APK Manifest (100% 准确)
     * 2. 若为 DEX/JAR/ZIP 等无 Manifest 文件，退化为类频次统计推断
     */
    private fun getAppPackageName(file: File, classes: List<JavaClass>): String? {
        // 优先级 1：针对 APK，直接调用 Android 原生 API 获取真实 Manifest 包名
        try {
            val archiveInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            if (archiveInfo != null && !archiveInfo.packageName.isNullOrEmpty()) {
                Log.d(TAG, "从 Android 原生 Manifest 成功获取包名: ${archiveInfo.packageName}")
                return archiveInfo.packageName
            }
        } catch (e: Exception) {
            Log.w(TAG, "无法通过原生 API 获取 Manifest: ${e.localizedMessage}")
        }

        // 优先级 2：保底方案 - 统计频次推断 (适用于 DEX/JAR/ZIP)
        val nonThirdPartyClasses = classes.filter { 
            !FilterHelper.isResourceClass(it.fullName) && !FilterHelper.isThirdPartyLibrary(it.fullName) 
        }
        if (nonThirdPartyClasses.isEmpty()) return null

        val packageCounts = HashMap<String, Int>()
        for (cls in nonThirdPartyClasses) {
            val pkg = getPackageName(cls.fullName)
            if (pkg.isNotEmpty()) {
                packageCounts[pkg] = (packageCounts[pkg] ?: 0) + 1
            }
        }
        if (packageCounts.isEmpty()) return null

        val mostFrequentPkg = packageCounts.maxByOrNull { it.value }?.key ?: return null
        val parts = mostFrequentPkg.split(".")

        return if (parts.size >= 3) {
            parts.take(3).joinToString(".")
        } else {
            mostFrequentPkg
        }
    }

    private fun shouldKeepJavaClass(cls: JavaClass, filterMode: FilterMode, appPackageName: String?): Boolean {
        // 核心修改：不再全局剥离 isInner！保留如 JYmodActivity$1 等业务逻辑内部类，仅由 FilterHelper 剔除 R$ 资源类
        return FilterHelper.shouldKeepClass(cls.fullName, filterMode, appPackageName)
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

                val rawClasses = decompiler.classes
                val appPackageName = getAppPackageName(file, rawClasses)

                val filteredClasses = rawClasses.filter { shouldKeepJavaClass(it, filterMode, appPackageName) }

                if (filteredClasses.isEmpty()) {
                    return@withContext "未在文件中找到匹配当前过滤模式 [${filterMode.displayName}] 的类。"
                }

                sb.append("// ==========================================\n")
                sb.append("//  JADX 引擎反编译预览\n")
                if (!appPackageName.isNullOrEmpty()) {
                    sb.append("//  💡 识别应用主包名: $appPackageName\n")
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
                    sb.append("// ... 其余 ${filteredClasses.size - displayLimit} 个类未展示，点击保存导出完整 TXT ...")
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

                createFreshDecompiler().use { decompiler ->
                    decompiler.load()
                    val rawClasses = decompiler.classes
                    totalClassesCount = rawClasses.size
                    detectedPackageName = getAppPackageName(file, rawClasses)

                    for (cls in rawClasses) {
                        if (shouldKeepJavaClass(cls, filterMode, detectedPackageName)) {
                            classesToDecompile.add(cls.fullName)
                        }
                    }
                }

                writer.write("// ==========================================\n")
                writer.write("//  JADX 手机版 (JADX 引擎) 自动生成\n")
                writer.write("//  源文件: $currentFileName\n")
                if (!detectedPackageName.isNullOrEmpty()) {
                    writer.write("//  应用主包名: $detectedPackageName\n")
                }
                writer.write("//  过滤模式: ${filterMode.displayName}\n")
                writer.write("//  原始类总数: $totalClassesCount\n")
                writer.write("//  实际导出类数: ${classesToDecompile.size}\n")
                writer.write("// ==========================================\n\n")

                var lastUpdateTime = 0L
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
                                    Log.e(TAG, "类 ${cls.fullName} 解析异常: ${e.localizedMessage}")
                                    writer.write("// !!! 警告：该类反编译失败 (已跳过) !!!\n")
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
            Log.e(TAG, "导出致命错误: ${e.localizedMessage}")
            e.printStackTrace()
            false
        }
    }
}
