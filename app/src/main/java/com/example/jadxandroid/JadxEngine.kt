package com.example.jadxandroid

import android.util.Log
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

class JadxEngine(private val currentFileName: String) : DecompilerEngine {

    private val TAG = "JadxEngine"

    override fun getName(): String = "JADX"

    private fun shouldFilter(className: String): Boolean {
        return className.startsWith("com.google.android.gms") || 
               className.startsWith("com.google.firebase") ||    
               className.startsWith("androidx.") ||               
               className.startsWith("android.support") ||         
               className.startsWith("kotlin.") ||                 
               className.startsWith("kotlinx.")                   
    }

    override suspend fun decompilePreview(file: File, filterSdk: Boolean): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            val args = JadxArgs().apply {
                inputFiles = listOf(file)
                isSkipResources = true 
            }

            JadxDecompiler(args).use { decompiler ->
                decompiler.load()
                
                val rawClasses = decompiler.classes
                val filteredClasses = if (filterSdk) {
                    rawClasses.filter { !shouldFilter(it.fullName) }
                } else {
                    rawClasses
                }

                if (filteredClasses.isEmpty()) {
                    return@withContext "未在文件中找到可解析的类（可能均被 SDK 过滤器过滤）。"
                }
                
                sb.append("// 成功解析，共找到 ${rawClasses.size} 个类（已过滤保留 ${filteredClasses.size} 个类）\n\n")
                val displayLimit = minOf(filteredClasses.size, 5)
                for (i in 0 until displayLimit) {
                    val cls = filteredClasses[i]
                    sb.append("// 类名: ${cls.fullName}\n")
                    sb.append(cls.code)
                    sb.append("\n\n// ==========================================\n\n")
                }
                if (filteredClasses.size > displayLimit) {
                    sb.append("// ... 其余 ${filteredClasses.size - displayLimit} 个类未完全展示 ...")
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
        filterSdk: Boolean,
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
                val classesToDecompile = ArrayList<String>()

                createFreshDecompiler().use { decompiler ->
                    decompiler.load()
                    val rawClasses = decompiler.classes
                    totalClassesCount = rawClasses.size
                    
                    for (cls in rawClasses) {
                        if (!filterSdk || !shouldFilter(cls.fullName)) {
                            classesToDecompile.add(cls.fullName)
                        }
                    }
                }

                writer.write("// ==========================================\n")
                writer.write("//  JADX 手机版 (JADX 引擎) 自动生成\n")
                writer.write("//  源文件: $currentFileName\n")
                writer.write("//  总类数: $totalClassesCount\n")
                writer.write("//  实际导出类数(已过滤SDK): ${classesToDecompile.size}\n")
                writer.write("// ==========================================\n\n")
                
                var lastUpdateTime = 0L
                val runtime = Runtime.getRuntime()
                
                val BATCH_SIZE = 300 
                var classIndex = 0
                val totalExportCount = classesToDecompile.size

                while (classIndex < totalExportCount) {
                    yield() 
                    
                    Log.d(TAG, "===> 正在创建全新 JADX 实例，当前索引: $classIndex")
                    
                    createFreshDecompiler().use { decompiler ->
                        decompiler.load()
                        
                        val classesMap = decompiler.classes.associateBy { it.fullName }
                        val batchEnd = minOf(classIndex + BATCH_SIZE, totalExportCount)
                        
                        for (i in classIndex until batchEnd) {
                            val clsName = classesToDecompile[i]
                            val cls = classesMap[clsName]
                            val currentCount = i + 1
                            
                            if (cls != null) {
                                val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                                Log.i(TAG, "[$currentCount/$totalExportCount] 正在解析: ${cls.fullName} | 当前堆已用: ${usedMemory}MB")
                                
                                writer.write("// [类 $currentCount/$totalExportCount] 类名: ${cls.fullName}\n")
                                
                                try {
                                    val code = cls.code
                                    writer.write(code)
                                } catch (e: Throwable) {
                                    Log.e(TAG, "类 ${cls.fullName} 解析发生异常: ${e.localizedMessage}")
                                    writer.write("// !!! 警告：该类反编译失败 (已自动跳过) !!!\n")
                                    writer.write("// 错误异常类型: ${e.javaClass.simpleName}\n")
                                    writer.write("// 错误日志信息: ${e.localizedMessage}\n")
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
                    Log.d(TAG, "===> 主动垃圾回收成功。当前堆已用: ${(runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)}MB")
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
