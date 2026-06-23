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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox 
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts 
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectFile: Button
    private lateinit var btnSaveTxt: Button
    private lateinit var cbFilterSdk: CheckBox 
    private lateinit var spEngine: Spinner // 绑定下拉选择器
    private lateinit var tvStatus: TextView
    private lateinit var tvCode: TextView

    private var currentPreparedFile: File? = null 
    private var currentFileName: String = ""       

    // 当前处于活动状态的反编译引擎
    private var activeEngine: DecompilerEngine = JadxEngine("")

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
        cbFilterSdk = findViewById(R.id.cb_filter_sdk) 
        spEngine = findViewById(R.id.sp_engine)
        tvStatus = findViewById(R.id.tv_status)
        tvCode = findViewById(R.id.tv_code)

        // 初始化下拉菜单数据源
        val engineList = arrayOf("JADX (安卓)", "CFR (Java)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, engineList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spEngine.adapter = adapter

        // 下拉菜单切换事件监听
        spEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // 根据当前选中的索引，实例化对应引擎
                activeEngine = if (position == 1) {
                    CfrEngine(this@MainActivity, currentFileName)
                } else {
                    JadxEngine(currentFileName)
                }

                // 核心：如果当前已经加载了文件，切换引擎时直接重新反编译刷新预览！
                if (currentPreparedFile != null) {
                    triggerDecompilePreview()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 核心：当用户切换“过滤SDK”复选框时，也立即重新反编译，体验极佳
        cbFilterSdk.setOnCheckedChangeListener { _, _ ->
            if (currentPreparedFile != null) {
                triggerDecompilePreview()
            }
        }

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
                
                // 智能切换：根据文件类型，自动选择最佳默认引擎位置
                // .apk/.dex 对应 0 (JADX), 其它对应 1 (CFR)
                val ext = currentFileName.substringAfterLast(".").lowercase()
                val idealPosition = if (ext == "apk" || ext == "dex") 0 else 1

                if (spEngine.selectedItemPosition == idealPosition) {
                    // 如果下拉菜单目前选中的已经是理想引擎，手动触发反编译
                    triggerDecompilePreview()
                } else {
                    // 如果不一致，设置选择，将通过 onItemSelectedListener 自动触发反编译
                    spEngine.setSelection(idealPosition)
                }
            } else {
                tvStatus.text = "状态: 复制文件失败"
            }
        }
    }

    // 核心重构：统一的反编译预览执行入口
    private fun triggerDecompilePreview() {
        val tempFile = currentPreparedFile ?: return
        if (!tempFile.exists()) return

        tvStatus.text = "状态: 正在反编译中 (引擎: ${activeEngine.getName()})..."
        tvCode.text = ""
        btnSaveTxt.isEnabled = false

        lifecycleScope.launch {
            val decompiledCode = activeEngine.decompilePreview(tempFile, cbFilterSdk.isChecked)
            tvStatus.text = "状态: 反编译完成 (引擎: ${activeEngine.getName()})"
            tvCode.text = decompiledCode
            btnSaveTxt.isEnabled = true
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

    private fun exportAllSourceToTxt(file: File) {
        tvStatus.text = "状态: 正在初始化导出 (引擎: ${activeEngine.getName()})..."
        btnSaveTxt.isEnabled = false
        btnSelectFile.isEnabled = false

        lifecycleScope.launch {
            val baseName = currentFileName.substringBeforeLast(".")
            val filterSuffix = if (cbFilterSdk.isChecked) "_filtered" else ""
            val exportFileName = "${baseName}${filterSuffix}_${activeEngine.getName().lowercase()}_decompiled.txt"
            
            val outputStream = getOutputStreamForDownload(exportFileName)
            if (outputStream != null) {
                // 调用当前活动引擎执行完整的流式导出
                val success = activeEngine.decompileAll(file, outputStream, cbFilterSdk.isChecked) { current, total, className ->
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "状态: 正在导出... ($current / $total)\n当前解析: $className"
                    }
                }
                
                if (success) {
                    tvStatus.text = "状态: 导出成功！已保存在：Download/$exportFileName"
                    Toast.makeText(this@MainActivity, "保存成功，请去手机『下载/Download』文件夹查看", Toast.LENGTH_LONG).show()
                } else {
                    tvStatus.text = "状态: 导出失败"
                    Toast.makeText(this@MainActivity, "写入文件失败，请查看日志", Toast.LENGTH_SHORT).show()
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
}
