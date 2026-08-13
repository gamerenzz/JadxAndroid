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

    private fun shouldKeepJavaClass(cls: JavaClass, filterMode: FilterMode, appCodeSet: Set<String>): Boolean {
        return FilterHelper.shouldKeepClass(cls.fullName, filterMode, appCodeSet)
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
                val analysisResult = AppPackageDetector.analyzeJadx(context, file, rawClasses)
                val appCodeSet = analysisResult.getAllAllowedPackages()

                val filteredClasses = rawClasses.filter { shouldKeepJavaClass(it, filterMode, appCodeSet) }

                if (filteredClasses.isEmpty()) {
                    return@withContext "未在文件中找到匹配当前过滤模式 [${filterMode.displayName}] 的类。"
                }

                sb.append("// ==========================================\n")
                sb.append("//  JADX 手机版 结构化代码提取报告\n")
                sb.append("//  识别主业务根包: ${analysisResult.corePackages}\n")
                if (analysisResult.modulePackages.isNotEmpty()) {
                    sb.append("//  识别扩展模块包: ${analysisResult.modulePackages}\n")
                }
                if (analysisResult.nativeBridges.isNotEmpty()) {
                    sb.append("//  识别 Native 桥接: ${analysisResult.nativeBridges}\n")
                }
                sb.append("//  当前过滤模式: ${filterMode.displayName}\n")
                sb.append("//  原始类总数: ${rawClasses.size} | 展示类数: ${filteredClasses.size}\n")
                sb.append("// ==========================================\n\n")

                val displayLimit = minOf(filteredClasses.size, 5)
                for (i in 0 until displayLimit) {
                    val cls = filteredClasses[i]
                    val category = analysisResult.classify(cls.fullName)
                    sb.append("// [预览 ${i + 1}/$displayLimit] [${category.code}] 类名: ${cls.fullName}\n")
                    sb.append(cls.code)
                    sb.append("\n\n// ==========================================\n\n")
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
                lateinit var analysisResult: AppCodeAnalysisResult
                val classesToDecompile = ArrayList<String>()

                createFreshDecompiler().use { decompiler ->
                    decompiler.load()
                    val rawClasses = decompiler.classes
                    totalClassesCount = rawClasses.size
                    analysisResult = AppPackageDetector.analyzeJadx(context, file, rawClasses)
                    val appCodeSet = analysisResult.getAllAllowedPackages()

                    for (cls in rawClasses) {
                        if (shouldKeepJavaClass(cls, filterMode, appCodeSet)) {
                            classesToDecompile.add(cls.fullName)
                        }
                    }
                }

                val categoryCounts = HashMap<ClassCategory, Int>()
                for (clsName in classesToDecompile) {
                    val cat = analysisResult.classify(clsName)
                    categoryCounts[cat] = (categoryCounts[cat] ?: 0) + 1
                }

                val totalExported = classesToDecompile.size
                val formatPct = { count: Int ->
                    if (totalExported > 0) String.format("%.1f%%", (count.toDouble() / totalExported) * 100) else "0.0%"
                }

                writer.write("// ==========================================\n")
                writer.write("//  JADX 手机版 (JADX 引擎) 结构化代码提取报告\n")
                writer.write("//  源文件: $currentFileName\n")
                writer.write("//  过滤模式: ${filterMode.displayName}\n")
                writer.write("//  \n")
                writer.write("//  📊 导出代码包分布分类统计:\n")

                val coreCount = categoryCounts[ClassCategory.APP_CORE] ?: 0
                if (analysisResult.corePackages.isNotEmpty() || coreCount > 0) {
                    writer.write("//   📌 [APP_CORE] 主业务核心代码 ($coreCount 类, ${formatPct(coreCount)}):\n")
                    analysisResult.corePackages.forEach { writer.write("//      - $it.*\n") }
                }

                val moduleCount = categoryCounts[ClassCategory.APP_MODULE] ?: 0
                if (analysisResult.modulePackages.isNotEmpty() || moduleCount > 0) {
                    writer.write("//   📌 [APP_MODULE] 应用组件/扩展模块 ($moduleCount 类, ${formatPct(moduleCount)}):\n")
                    analysisResult.modulePackages.forEach { writer.write("//      - $it.*\n") }
                }

                val nativeCount = categoryCounts[ClassCategory.NATIVE_BRIDGE] ?: 0
                if (analysisResult.nativeBridges.isNotEmpty() || nativeCount > 0) {
                    writer.write("//   📌 [NATIVE_BRIDGE] Go/C++ 原生内核桥接层 ($nativeCount 类, ${formatPct(nativeCount)}):\n")
                    analysisResult.nativeBridges.forEach { writer.write("//      - $it.*\n") }
                }

                val engineCount = categoryCounts[ClassCategory.GAME_ENGINE] ?: 0
                if (analysisResult.gameEngines.isNotEmpty() || engineCount > 0) {
                    writer.write("//   📌 [GAME_ENGINE] 游戏引擎/框架基类 ($engineCount 类, ${formatPct(engineCount)}):\n")
                    analysisResult.gameEngines.forEach { writer.write("//      - $it.*\n") }
                }

                val extCount = categoryCounts[ClassCategory.EXTERNAL_DEP] ?: 0
                if (analysisResult.externalDeps.isNotEmpty() || extCount > 0) {
                    writer.write("//   📌 [EXTERNAL_DEP] 关联第三方依赖库 ($extCount 类, ${formatPct(extCount)}):\n")
                    analysisResult.externalDeps.forEach { writer.write("//      - $it.*\n") }
                }

                val unknownCount = categoryCounts[ClassCategory.UNKNOWN] ?: 0
                if (unknownCount > 0) {
                    writer.write("//   📌 [UNKNOWN] 未确定归属代码 ($unknownCount 类, ${formatPct(unknownCount)})\n")
                }

                writer.write("//  \n")
                writer.write("//  原始类总数: $totalClassesCount | 实际导出类数: $totalExported\n")
                writer.write("// ==========================================\n\n")

                var lastUpdateTime = 0L
                val BATCH_SIZE = 300
                var classIndex = 0

                while (classIndex < totalExported) {
                    yield()

                    createFreshDecompiler().use { decompiler ->
                        decompiler.load()

                        val classesMap = decompiler.classes.associateBy { it.fullName }
                        val batchEnd = minOf(classIndex + BATCH_SIZE, totalExported)

                        for (i in classIndex until batchEnd) {
                            val clsName = classesToDecompile[i]
                            val cls = classesMap[clsName]
                            val currentCount = i + 1

                            if (cls != null) {
                                val category = analysisResult.classify(cls.fullName)
                                writer.write("// [$currentCount/$totalExported] [分类: ${category.code}] 类名: ${cls.fullName}\n")

                                try {
                                    val code = cls.code
                                    writer.write(code)
                                } catch (e: Throwable) {
                                    Log.e(TAG, "类 ${cls.fullName} 解析异常: ${e.localizedMessage}")
                                    writer.write("// !!! 警告：该类反编译失败 (已跳过) !!!\n")
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
