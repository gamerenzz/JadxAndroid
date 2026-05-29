package com.example.jadxandroid

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts 
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private val TAG = "JadxAndroidExport"

    private lateinit var btnSelectFile: Button
    private lateinit var btnSaveTxt: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCode: TextView

    private var currentPreparedFile: File? = null 
    private var currentFileName: String = ""       

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFile(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSelectFile = findViewById(R.id.btn_select_file)
        btnSaveTxt = findViewById(R.id.btn_save_txt)
        tvStatus = findViewById(R.id.tv_status)
        tvCode = findViewById(R.id.tv_code)

        btnSelectFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*" 
            }
            filePickerLauncher.launch(intent)
        }

        btnSaveTxt.setOnClickListener {
            val fileToExport = currentPreparedFile
            if (fileToExport != null && fileToExport.exists()) {
                exportAllSourceToTxt(fileToExport)
            } else {
                Toast.makeText(this, "没有可导出的文件，请先选择文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleSelectedFile(uri: Uri) {
        tvStatus.text = "状态: 正在准备文件..."
        tvCode.text = ""
        btnSaveTxt.isEnabled = false 

        lifecycleScope.launch {
            val tempFile = copyUriToTempFile(uri)
            if (tempFile != null && tempFile.exists()) {
                currentPreparedFile = tempFile
                tvStatus.text = "状态: 正在反编译中，请稍候..."
                val decompiledCode = decompilePreview(tempFile)
                tvStatus.text = "状态: 反编译完成"
                tvCode.text = decompiledCode
                btnSaveTxt.isEnabled = true 
            } else {
                tvStatus.text = "状态: 复制文件失败"
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "temp_file"
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        } else {
            uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) {
                    name = path.substring(cut + 1)
                }
            }
        }
        return name
    }

    private suspend fun copyUriToTempFile(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return@withContext null
            currentFileName = getFileNameFromUri(uri)
            val tempFile = File(cacheDir, currentFileName)
            
            if (tempFile.exists()) tempFile.delete()
            
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun decompilePreview(file: File): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            val args = JadxArgs().apply {
                inputFiles = listOf(file)
                isSkipResources = true 
            }

            JadxDecompiler(args).use { decompiler ->
                decompiler.load()
                val classes = decompiler.classes
                if (classes.isEmpty()) {
                    return@withContext "未在文件中找到可解析的类。"
                }
                
                sb.append("// 成功解析，共找到 ${classes.size} 个类\n\n")
                val displayLimit = minOf(classes.size, 5)
                for (i in 0 until displayLimit) {
                    val cls = classes[i]
                    sb.append("// 类名: ${cls.fullName}\n")
                    sb.append(cls.code)
                    sb.append("\n\n// ==========================================\n\n")
                }
                if (classes.size > displayLimit) {
                    sb.append("// ... 其余 ${classes.size - displayLimit} 个类未完全展示 ...")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sb.append("反编译过程中发生错误:\n${e.localizedMessage}")
        }
        sb.toString()
    }

    private fun exportAllSourceToTxt(file: File) {
        tvStatus.text = "状态: 正在初始化导出..."
        btnSaveTxt.isEnabled = false
        btnSelectFile.isEnabled = false

        lifecycleScope.launch {
            val baseName = currentFileName.substringBeforeLast(".")
            val exportFileName = "${baseName}_decompiled.txt"
            
            val outputStream = getOutputStreamForDownload(exportFileName)
            if (outputStream != null) {
                val success = doStreamingDecompile(file, outputStream) { current, total, className ->
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "状态: 正在导出... ($current / $total)\n当前解析: $className"
                    }
                }
                
                if (success) {
                    tvStatus.text = "状态: 导出成功！文件已保存在：Download/$exportFileName"
                    Toast.makeText(this@MainActivity, "保存成功，请去手机『下载/Download』文件夹查看", Toast.LENGTH_LONG).show()
                } else {
                    tvStatus.text = "状态: 导出失败（可能因某特定类卡死或崩溃）"
                    Toast.makeText(this@MainActivity, "写入文件失败", Toast.LENGTH_SHORT).show()
                }
            } else {
                tvStatus.text = "状态: 无法在 Download 创建文件"
            }
            btnSaveTxt.isEnabled = true
            btnSelectFile.isEnabled = true
        }
    }

    private fun getOutputStreamForDownload(fileName: String): OutputStream? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let { resolver.openOutputStream(it) }
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val targetFile = File(downloadDir, fileName)
                FileOutputStream(targetFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // 实现了分批重建与主动垃圾回收的安全反编译流
    private suspend fun doStreamingDecompile(
        inputFile: File, 
        outputStream: OutputStream,
        onProgress: suspend (current: Int, total: Int, className: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            outputStream.bufferedWriter().use { writer ->
                val args = JadxArgs().apply {
                    inputFiles = listOf(inputFile)
                    isSkipResources = true
                }

                // 1. 首先加载一次，仅用于获取总类数（速度极快）
                var totalClassesCount = 0
                JadxDecompiler(args).use { decompiler ->
                    decompiler.load()
                    totalClassesCount = decompiler.classes.size
                }

                writer.write("// ==========================================\n")
                writer.write("//  JADX 手机版 自动生成 (内存优化分批重建版)\n")
                writer.write("//  源文件: $currentFileName\n")
                writer.write("//  类总数: $totalClassesCount\n")
                writer.write("// ==========================================\n\n")
                
                var lastUpdateTime = 0L
                val runtime = Runtime.getRuntime()
                
                // 核心控制参数：每反编译 300 个类就彻底销毁并重建一次 JADX 实例，彻底释放内存
                val BATCH_SIZE = 300 
                var classIndex = 0

                while (classIndex < totalClassesCount) {
                    yield() // 释放协程时间片，保持 UI 线程活跃
                    
                    Log.d(TAG, "===> 创建全新的 JADX 实例，当前索引: $classIndex")
                    
                    JadxDecompiler(args).use { decompiler ->
                        decompiler.load()
                        val classes = decompiler.classes
                        
                        // 计算当前批次的结束位置
                        val batchEnd = minOf(classIndex + BATCH_SIZE, classes.size)
                        
                        for (i in classIndex until batchEnd) {
                            val cls = classes[i]
                            val currentCount = i + 1
                            
                            // 打印内存状态
                            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                            Log.i(TAG, "[$currentCount/$totalClassesCount] 正在解析: ${cls.fullName} | 当前堆已用: ${usedMemory}MB")
                            
                            writer.write("// [类 $currentCount/$totalClassesCount] 类名: ${cls.fullName}\n")
                            writer.write(cls.code)
                            writer.write("\n\n// ------------------------------------------\n\n")
                            
                            cls.unload() // 释放当前类的源码占用
                            
                            // 限制最快每 500ms 更新一次 UI 进度
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime > 500 || currentCount == totalClassesCount) {
                                lastUpdateTime = currentTime
                                onProgress(currentCount, totalClassesCount, cls.fullName)
                            }
                            
                            yield()
                        }
                        classIndex = batchEnd
                    } // 退出 use 块后，JadxDecompiler 被 close()，释放累计 300 个类的所有 AST 树和全局缓存

                    // 重建间隔，主动触发系统垃圾清理
                    System.gc()
                    Log.d(TAG, "===> 已销毁旧 JADX 实例，主动回收垃圾。当前堆已用: ${(runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)}MB")
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
