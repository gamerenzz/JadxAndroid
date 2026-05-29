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
                // 回调函数增加 className 参数，用于在主屏幕上实时显示具体卡在哪个类
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

                JadxDecompiler(args).use { decompiler ->
                    decompiler.load()
                    val classes = decompiler.classes
                    
                    writer.write("// ==========================================\n")
                    writer.write("//  JADX 手机版 自动生成\n")
                    writer.write("//  源文件: $currentFileName\n")
                    writer.write("//  类总数: ${classes.size}\n")
                    writer.write("// ==========================================\n\n")
                    
                    var lastUpdateTime = 0L
                    val runtime = Runtime.getRuntime()

                    classes.forEachIndexed { index, cls ->
                        val currentCount = index + 1
                        
                        // 1. 向系统 Logcat 输出当前反编译类的具体信息以及堆内存占用情况，方便排查崩溃
                        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                        Log.i(TAG, "[$currentCount/${classes.size}] 正在解析类: ${cls.fullName} | 当前堆内存已用: ${usedMemory}MB")
                        
                        // 2. 写入文件
                        writer.write("// [类 $currentCount/${classes.size}] 类名: ${cls.fullName}\n")
                        writer.write(cls.code)
                        writer.write("\n\n// ------------------------------------------\n\n")
                        
                        // 3. 内存释放
                        cls.unload() 
                        
                        // 4. 防抖更新机制：限制最快每 500 毫秒才更新一次 UI。
                        // 既保证了进度的流畅展示，又彻底避免了频繁更新 UI 导致的系统响应堵塞。
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime > 500 || currentCount == classes.size) {
                            lastUpdateTime = currentTime
                            // 将当前解析的类名 cls.fullName 也传回界面，卡死时一眼便知卡在哪个类上
                            onProgress(currentCount, classes.size, cls.fullName)
                        }

                        // 5. 极其重要：在每一次循环中主动让出时间片，
                        // 给操作系统的其他线程（如系统的垃圾回收器 GC、主 UI 线程）运行机会，防止应用被判定为 ANR
                        yield()
                    }
                    writer.flush()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "导出过程中发生致命错误: ${e.localizedMessage}")
            e.printStackTrace()
            false
        }
    }
}
