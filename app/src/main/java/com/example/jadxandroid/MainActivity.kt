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
import android.widget.CheckBox // 引入 CheckBox
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
    private lateinit var cbFilterSdk: CheckBox // 声明 CheckBox
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
        cbFilterSdk = findViewById(R.id.cb_filter_sdk) // 绑定 CheckBox
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
                // 传入是否开启过滤的参数
                val decompiledCode = decompilePreview(tempFile, cbFilterSdk.isChecked)
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

    // 新增：过滤匹配逻辑（跳过谷歌服务、广告、Firebase、AndroidX系统包、Kotlin标准库）
    private fun shouldFilter(className: String): Boolean {
        return className.startsWith("com.google.android.gms") || // 谷歌服务、广告等
               className.startsWith("com.google.firebase") ||    // Firebase
               className.startsWith("androidx.") ||               // Jetpack组件
               className.startsWith("android.support") ||         // 传统兼容包
               className.startsWith("kotlin.") ||                 // Kotlin 核心库
               className.startsWith("kotlinx.")                   // Kotlin 协程与扩展库
    }

    // 预览区支持过滤显示
    private suspend fun decompilePreview(file: File, filterSdk: Boolean): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            val args = JadxArgs().apply {
                inputFiles = listOf(file)
                isSkipResources = true 
            }

            JadxDecompiler(args).use { decompiler ->
                decompiler.load()
                
                // 根据过滤选择，筛选出非 SDK 类
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
            // 命名区分是否启用了过滤
            val filterSuffix = if (cbFilterSdk.isChecked) "_filtered" else ""
            val exportFileName = "${baseName}${filterSuffix}_decompiled.txt"
            
            val outputStream = getOutputStreamForDownload(exportFileName)
            if (outputStream != null) {
                // 传入复选框的状态值
                val success = doStreamingDecompile(file, outputStream, cbFilterSdk.isChecked) { current, total, className ->
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

    // 导出的流式反编译支持过滤
    private suspend fun doStreamingDecompile(
        inputFile: File, 
        outputStream: OutputStream,
        filterSdk: Boolean, // 接收过滤标志
        onProgress: suspend (current: Int, total: Int, className: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            outputStream.bufferedWriter().use { writer ->
                val args = JadxArgs().apply {
                    inputFiles = listOf(inputFile)
                    isSkipResources = true
                }

                // 先获取总类列表并进行过滤预处理
                var totalClassesCount = 0
                val classesToDecompile = ArrayList<String>() // 存储需要反编译的类名全称

                JadxDecompiler(args).use { decompiler ->
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
                writer.write("//  JADX 手机版 自动生成 (内存优化分批重建版)\n")
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
                    
                    Log.d(TAG, "===> 创建全新的 JADX 实例，当前索引: $classIndex")
                    
                    JadxDecompiler(args).use { decompiler ->
                        decompiler.load()
                        
                        // 由于重新载入，这里通过映射来按名称匹配我们要解析的类，避免对已卸载类的重复加载
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
                                writer.write(cls.code)
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
