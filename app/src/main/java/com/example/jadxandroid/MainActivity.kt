package com.example.jadxandroid

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
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

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectFile: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCode: TextView

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
        tvStatus = findViewById(R.id.tv_status)
        tvCode = findViewById(R.id.tv_code)

        btnSelectFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*" 
            }
            filePickerLauncher.launch(intent)
        }
    }

    private fun handleSelectedFile(uri: Uri) {
        tvStatus.text = "状态: 正在准备文件..."
        tvCode.text = ""

        lifecycleScope.launch {
            val tempFile = copyUriToTempFile(uri)
            if (tempFile != null && tempFile.exists()) {
                tvStatus.text = "状态: 正在反编译中，请稍候..."
                val decompiledCode = decompile(tempFile)
                tvStatus.text = "状态: 反编译完成"
                tvCode.text = decompiledCode
            } else {
                tvStatus.text = "状态: 复制文件失败"
            }
        }
    }

    // 新增：获取用户选择文件的真实文件名（包含后缀如 .class）
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
            val realFileName = getFileNameFromUri(uri) // 获取带后缀的真实文件名
            val tempFile = File(cacheDir, realFileName)
            
            // 清理旧缓存文件
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

    private suspend fun decompile(file: File): String = withContext(Dispatchers.IO) {
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
                    return@withContext "未在文件中找到可解析的类。请确认该文件是有效的 .class, .jar 或 .apk 文件。"
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
}
