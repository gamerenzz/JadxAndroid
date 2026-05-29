package com.example.jadxandroid

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
// 核心修复：修改为 JADX 1.4.7 对应的导包路径
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
                type = "*/*" // 允许选择任意文件，JADX 内部会识别格式
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

    // 将系统 Uri 转换为本地 File 以供 JADX 读取
    private suspend fun copyUriToTempFile(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return@withContext null
            val tempFile = File(cacheDir, "temp_input_file")
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

    // 后台反编译逻辑
    private suspend fun decompile(file: File): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            val args = JadxArgs().apply {
                inputFiles = listOf(file)
                isSkipResources = true // 仅解析代码，加快速度
            }

            JadxDecompiler(args).use { decompiler ->
                decompiler.load()
                val classes = decompiler.classes
                if (classes.isEmpty()) {
                    return@withContext "未在文件中找到可解析的类。"
                }
                
                sb.append("// 成功解析，共找到 ${classes.size} 个类\n\n")
                // 限制一次性展示的数量以防界面卡死，此处默认读取前 5 个类
                val displayLimit = minOf(classes.size, 5)
                for (i in 0 until displayLimit) {
                    val cls = classes[i]
                    sb.append("// 类名: ${cls.fullName}\n")
                    sb.append(cls.code)
                    sb.append("\n\n// ==========================================\n\n")
                }
                if (classes.size > displayLimit) {
                    sb.append("// ... 其余 ${classes.size - displayLimit} 个类未完全展示。")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sb.append("反编译过程中发生错误:\n${e.localizedMessage}")
        }
        sb.toString()
    }
}
