package com.example.jadxandroid

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import jadx.api.JavaClass
import java.io.File

object AppPackageDetector {

    private const val TAG = "AppPackageDetector"

    /**
     * 智能探测 App 自有业务的代码包集合 (运用最长公共包树聚合算法)
     */
    fun detectAppCodeSet(context: Context, file: File, rawClasses: List<JavaClass>): Set<String> {
        val candidatePackages = HashSet<String>()

        // 1. 搜集 Manifest 中声明的所有真实组件包名
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
                        candidatePackages.add(pkg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 Manifest 异常: ${e.localizedMessage}")
        }

        // 2. 搜集所有 *.BuildConfig 所在的源码包名
        for (cls in rawClasses) {
            val fullName = cls.fullName
            if (fullName.endsWith(".BuildConfig") || fullName == "BuildConfig") {
                val pkg = if (fullName.contains(".")) fullName.substringBeforeLast(".") else ""
                if (pkg.isNotEmpty() && !FilterHelper.isThirdPartyLibrary(pkg)) {
                    candidatePackages.add(pkg)
                }
            }
        }

        // 3. 如果前两步候选包为空，搜集所有非第三方类的包名作为候选
        if (candidatePackages.isEmpty()) {
            for (cls in rawClasses) {
                val fullName = cls.fullName
                if (!FilterHelper.isResourceClass(fullName) && !FilterHelper.isThirdPartyLibrary(fullName)) {
                    val pkg = if (fullName.contains(".")) fullName.substringBeforeLast(".") else ""
                    if (pkg.isNotEmpty()) candidatePackages.add(pkg)
                }
            }
        }

        // 4. 核心算法：通过公共前缀树算法，将 candidatePackages 合并归一化为极简的核心业务包树
        val consolidatedAppRoots = consolidatePackageTree(candidatePackages)
        Log.i(TAG, "归一化提取到的 App 业务根包: $consolidatedAppRoots")

        return consolidatedAppRoots
    }

    /**
     * 核心包树归一化算法：
     * 输入：[io.nekohasekai.sagernet.ui, io.nekohasekai.sagernet.bg, io.nekohasekai.sagernet.database]
     * 输出：[io.nekohasekai.sagernet]
     * 输入：[com.wangwu.jymod52, com.wangwu.jymod52.ui]
     * 输出：[com.wangwu.jymod52]
     */
    private fun consolidatePackageTree(packages: Collection<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()

        val validPkgs = packages.filter { !FilterHelper.isThirdPartyLibrary(it) }
        if (validPkgs.isEmpty()) return emptySet()

        val rootCandidates = HashSet<String>()

        for (pkg in validPkgs) {
            val parts = pkg.split(".")
            // 如果包名包含 3 段或以上，先提取最可能的基准前缀（如取前 3 段或前 4 段）
            if (parts.size >= 4) {
                rootCandidates.add(parts.take(3).joinToString("."))
                rootCandidates.add(parts.take(4).joinToString("."))
            } else {
                rootCandidates.add(pkg)
            }
        }

        // 按照包名长度升序排序，优先保留最简短的根包
        val sortedCandidates = rootCandidates.sortedBy { it.length }
        val finalRoots = HashSet<String>()

        for (cand in sortedCandidates) {
            // 如果当前候选包不属于任何已存在根包的子包，则作为新的根包加入
            if (finalRoots.none { root -> cand == root || cand.startsWith("$root.") }) {
                finalRoots.add(cand)
            }
        }

        return finalRoots
    }
}
