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

    private fun getPackageName(fullName: String): String {
        return if (fullName.contains(".")) fullName.substringBeforeLast(".") else ""
    }

    /**
     * 从类列表中智能推断出 APK 的主包名
     */
    private fun inferAppPackageName(classes: List<JavaClass>): String? {
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
        // JADX 中顶层主类解析时会自动嵌套其内部类代码；独立遍历时过滤 isInner 可避免重复代码打印
        if (cls.isInner) return false

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
                val appPackageName = inferAppPackageName(rawClasses)

                val filteredClasses = rawClasses.filter { shouldKeepJavaClass(it, filterMode, appPackageName) }

                if (filteredClasses.isEmpty()) {
                    return@withContext "未在文件中找到匹配当前过滤模式 [${filterMode.displayName}] 的类。"
                }

                sb.append("// ==========================================\n")
                sb.append("//  JADX 引擎反编译预览\n")
                if (!appPackageName.isNullOrEmpty()) {
                    sb.append("//  💡 自动识别主包名: $appPackageName\n")
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
                    detectedPackageName = inferAppPackageName(rawClasses)

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
                                    writer.write("// 错误信息: ${e.localizedMessage}\n")
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
