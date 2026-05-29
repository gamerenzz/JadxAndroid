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
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectFile: Button
    private lateinit var btnSaveTxt: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCode: TextView

    private var currentPreparedFile: File? = null // 保存当前已准备好的临时文件引用
    private var currentFileName: String = ""       // 保存当前选中的文件名

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
        btnSaveTxt.isEnabled = false // 在准备新文件时先禁用保存按钮

        lifecycleScope.launch {
            val tempFile = copyUriToTempFile(uri)
            if (tempFile != null && tempFile.exists()) {
                currentPreparedFile = tempFile
                tvStatus.text = "状态: 正在反编译中，请稍候..."
                val decompiledCode = decompilePreview(tempFile)
                tvStatus.text = "状态: 反编译完成"
                tvCode.text = decompiledCode
                btnSaveTxt.isEnabled = true // 解析成功后启用“保存为TXT”按钮
            } else {
                tvStatus.text = "状态: 复制文件失败"
            }
        }
    }

    // 获取真实文件名
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

    // 复制文件并保留其原本的后缀名
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

    // 仅反编译前 5 个类用于界面预览（防止 OOM 和 UI 卡顿）
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

    // 核心新增：流式导出所有类到公共 Download 文件夹
    private fun exportAllSourceToTxt(file: File) {
        tvStatus.text = "状态: 正在导出完整源码到 TXT..."
        btnSaveTxt.isEnabled = false
        btnSelectFile.isEnabled = false

        lifecycleScope.launch {
            // 在文件名后加上 _decompiled.txt 后缀
            val baseName = currentFileName.substringBeforeLast(".")
            val exportFileName = "${baseName}_decompiled.txt"
            
            val outputStream = getOutputStreamForDownload(exportFileName)
            if (outputStream != null) {
                val success = doStreamingDecompile(file, outputStream)
                if (success) {
                    tvStatus.text = "状态: 导出成功！文件已保存至：Download/$exportFileName"
                    Toast.makeText(this@MainActivity, "保存成功，请去手机『下载/Download』文件夹查看", Toast.LENGTH_LONG).show()
                } else {
                    tvStatus.text = "状态: 导出失败"
                    Toast.makeText(this@MainActivity, "写入文件失败", Toast.LENGTH_SHORT).show()
                }
            } else {
                tvStatus.text = "状态: 无法在 Download 创建文件"
            }
            btnSaveTxt.isEnabled = true
            btnSelectFile.isEnabled = true
        }
    }

    // 兼容高低版本 Android 的公共 Download 目录写入流获取
    private fun getOutputStreamForDownload(fileName: String): OutputStream? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 (API 29) 及以上，使用 MediaStore API
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let { resolver.openOutputStream(it) }
            } else {
                // Android 9 及以下，使用传统的外部存储路径（需注意：如果目标是旧设备且未授权存储可能失效）
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

    // 流式反编译，逐个类反编译并写入，同时释放内存以防止 OOM
    private suspend fun doStreamingDecompile(inputFile: File, outputStream: OutputStream): Boolean = withContext(Dispatchers.IO) {
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
                    
                    classes.forEachIndexed { index, cls ->
                        // 提示进度（在控制台或后台）
                        writer.write("// [类 ${index + 1}/${classes.size}] 类名: ${cls.fullName}\n")
                        writer.write(cls.code)
                        writer.write("\n\n// ------------------------------------------\n\n")
                        
                        // 关键：写完一个类立即从内存中卸载该类缓存的源码和 AST 树，防止 5000+ 类堆积导致 OOM
                        cls.unload() 
                    }
                    writer.flush()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
