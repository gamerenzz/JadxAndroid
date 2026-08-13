package com.example.jadxandroid

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import jadx.api.JavaClass
import java.io.File

object AppPackageDetector {

    private const val TAG = "AppPackageDetector"

    /**
     * 智能探测整个 APK/JAR 属于 App 自有业务的所有根包名集合
     */
    fun detectAppCodeSet(context: Context, file: File, rawClasses: List<JavaClass>): Set<String> {
        val codeSet = HashSet<String>()

        // 策略 1：读取 AndroidManifest 中声明的所有组件 (Activity, Service, Receiver, Provider) 真实的类所属包
        try {
            val flags = PackageManager.GET_ACTIVITIES or
                        PackageManager.GET_SERVICES or
                        PackageManager.GET_RECEIVERS or
                        PackageManager.GET_PROVIDERS

            val archiveInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            if (archiveInfo != null) {
                val components = ArrayList<String>()
                archiveInfo.activities?.forEach { components.add(it.name) }
                archiveInfo.services?.forEach { components.add(it.name) }
                archiveInfo.receivers?.forEach { components.add(it.name) }
                archiveInfo.providers?.forEach { components.add(it.name) }

                for (clsName in components) {
                    val pkg = if (clsName.contains(".")) clsName.substringBeforeLast(".") else ""
                    if (pkg.isNotEmpty() && !FilterHelper.isThirdPartyLibrary(pkg)) {
                        val rootPkg = extractRootPackage(pkg)
                        codeSet.add(rootPkg)
                    }
                }
                Log.d(TAG, "通过 Manifest 组件成功识别到的业务根包: $codeSet")
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 Manifest 组件失败: ${e.localizedMessage}")
        }

        // 策略 2：扫描项目中所有 *.BuildConfig 所在源码包名 (精准提取 applicationId 之外的源码包)
        for (cls in rawClasses) {
            val fullName = cls.fullName
            if (fullName.endsWith(".BuildConfig") || fullName == "BuildConfig") {
                val pkg = if (fullName.contains(".")) fullName.substringBeforeLast(".") else ""
                if (pkg.isNotEmpty() && !FilterHelper.isThirdPartyLibrary(pkg)) {
                    val rootPkg = extractRootPackage(pkg)
                    codeSet.add(rootPkg)
                }
            }
        }

        // 策略 3：若前两步未提取到（非 APK 文件或无组件），退化使用非第三方类的频次树聚合推断
        if (codeSet.isEmpty()) {
            val inferredPkg = inferPackageFromClassTree(rawClasses)
            if (!inferredPkg.isNullOrEmpty()) {
                codeSet.add(inferredPkg)
            }
        }

        Log.i(TAG, "最终认定的 App 业务代码包集合: $codeSet")
        return codeSet
    }

    private fun extractRootPackage(pkg: String): String {
        val parts = pkg.split(".")
        return if (parts.size >= 3) {
            parts.take(3).joinToString(".") // 保留前三段包名，如 io.nekohasekai.sagernet
        } else {
            pkg
        }
    }

    private fun inferPackageFromClassTree(classes: List<JavaClass>): String? {
        val nonThirdPartyClasses = classes.filter {
            !FilterHelper.isResourceClass(it.fullName) && !FilterHelper.isThirdPartyLibrary(it.fullName)
        }
        if (nonThirdPartyClasses.isEmpty()) return null

        val packageCounts = HashMap<String, Int>()
        for (cls in nonThirdPartyClasses) {
            val fullName = cls.fullName
            val pkg = if (fullName.contains(".")) fullName.substringBeforeLast(".") else ""
            if (pkg.isNotEmpty()) {
                packageCounts[pkg] = (packageCounts[pkg] ?: 0) + 1
            }
        }

        val mostFrequent = packageCounts.maxByOrNull { it.value }?.key ?: return null
        return extractRootPackage(mostFrequent)
    }
}
