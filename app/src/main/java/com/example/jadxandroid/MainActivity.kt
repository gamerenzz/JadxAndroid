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
import androidx.documentfile.provider.DocumentFile // 核心引入：安卓 SAF 文件目录树支持库
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectFile: Button
    private lateinit var btnSelectFolder: Button // 新增：选择目录按钮
    private lateinit var btnSaveTxt: Button
    private lateinit var cbFilterSdk: CheckBox 
    private lateinit var spEngine: Spinner 
    private lateinit var tvStatus: TextView
    private lateinit var tvCode: TextView

    private var currentPreparedFile: File? = null 
    private var currentFileName: String = ""       

    private var activeEngine: DecompilerEngine = JadxEngine("")

    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFile(uri)
            }
        }
    }

    // 核心新增：文件夹/目录选择器
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleSelectedFolder(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSelectFile = findViewById(R.id.btn_select_file)
        btnSelectFolder = findViewById(R.id.btn_select_folder) // 绑定按钮
        btnSaveTxt = findViewById(R.id.btn_save_txt)
        cbFilterSdk = findViewById(R.id.cb_filter_sdk) 
        spEngine = findViewById(R.id.sp_engine)
        tvStatus = findViewById(R.id.tv_status)
        tvCode = findViewById(R.id.tv_code)

        val engineList = arrayOf("JADX (安卓)", "CFR (Java)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, engineList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spEngine.adapter = adapter

        spEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                activeEngine = if (position == 1) {
                    CfrEngine(this@MainActivity, currentFileName)
                } else {
                    JadxEngine(currentFileName)
                }

                if (currentPreparedFile != null) {
                    triggerDecompilePreview()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

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

        // 点击“选择目录”启动 SAF 树形结构目录选择
        btnSelectFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
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
                
                val ext = currentFileName.substringAfterLast(".").lowercase()
                val idealPosition = if (ext == "apk" || ext == "dex") 0 else 1

                if (spEngine.selectedItemPosition == idealPosition) {
                    triggerDecompilePreview()
                } else {
                    spEngine.setSelection(idealPosition)
                }
            } else {
                tvStatus.text = "状态: 复制文件失败"
            }
        }
    }

    // 核心新增：处理用户选择的本地文件夹
    private fun handleSelectedFolder(uri: Uri) {
        tvStatus.text = "状态: 正在打包目录文件，请稍候..."
        tvCode.text = ""
        btnSaveTxt.isEnabled = false

        lifecycleScope.launch {
            val documentFolder = DocumentFile.fromTreeUri(this@MainActivity, uri)
            if (documentFolder != null && documentFolder.exists()) {
                currentFileName = documentFolder.name ?: "Folder"
                val tempZipFile = File(cacheDir, "FolderDecompile.zip")
                if (tempZipFile.exists()) tempZipFile.delete()

                // 后台流式打包目录内的 class 文件到临时 Zip 包，保留目录树结构
                val success = withContext(Dispatchers.IO) {
                    try {
                        FileOutputStream(tempZipFile).use { fos ->
                            ZipOutputStream(fos).use { zos ->
                                zipDocumentFolder(documentFolder, zos)
                            }
                        }
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }

                if (success && tempZipFile.exists() && tempZipFile.length() > 0) {
                    currentPreparedFile = tempZipFile
                    // 文件夹反编译全都是类文件，默认定位到最适的 CFR (Java) 引擎 (索引 1)
                    val idealPosition = 1
                    if (spEngine.selectedItemPosition == idealPosition) {
                        triggerDecompilePreview()
                    } else {
                        spEngine.setSelection(idealPosition)
                    }
                } else {
                    tvStatus.text = "状态: 目录打包失败或未在其内找到任何类文件"
                }
            } else {
                tvStatus.text = "状态: 无法访问选中的目录"
            }
        }
    }

    // 核心新增：递归遍历 DocumentTree，并将 class 和 jar 流式压缩入包
    private suspend fun zipDocumentFolder(
        folder: DocumentFile, 
        zipOut: ZipOutputStream, 
        currentPath: String = ""
    ): Unit = withContext(Dispatchers.IO) {
        val files = folder.listFiles()
        for (file in files) {
            yield() // 保持协程协作，防卡死
            if (file.isDirectory) {
                val nextPath = if (currentPath.isEmpty()) file.name else "$currentPath/${file.name}"
                if (nextPath != null) {
                    zipDocumentFolder(file, zipOut, nextPath)
                }
            } else {
                val name = file.name ?: continue
                val isClassOrJar = name.endsWith(".class", ignoreCase = true) || name.endsWith(".jar", ignoreCase = true)
                if (isClassOrJar) {
                    // 拼接保留相对路径，供反编译引擎进行类路径 Inline 分析
                    val entryName = if (currentPath.isEmpty()) name else "$currentPath/$name"
                    val entry = ZipEntry(entryName)
                    try {
                        zipOut.putNextEntry(entry)
                        contentResolver.openInputStream(file.uri)?.use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

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
        btnSelectFolder.isEnabled = false

        lifecycleScope.launch {
            val baseName = currentFileName.substringBeforeLast(".")
            val filterSuffix = if (cbFilterSdk.isChecked) "_filtered" else ""
            val exportFileName = "${baseName}${filterSuffix}_${activeEngine.getName().lowercase()}_decompiled.txt"
            
            val outputStream = getOutputStreamForDownload(exportFileName)
            if (outputStream != null) {
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
            btnSelectFolder.isEnabled = true
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
